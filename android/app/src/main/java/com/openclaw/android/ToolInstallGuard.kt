package com.openclaw.android

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide: one tool install at a time, and the place a page created later (the Activity was
 * recreated) asks "what is installing, how far, was it cancelled?". Same reasoning as
 * [SetupGuard] — an `object` so no instance member can shadow it.
 *
 * Phases: idle → running → (cancelling →) done | failed | cancelled. A cancel only asks the
 * script to stop (SIGTERM): bash defers it until the foreground step (npm, curl) ends, so the
 * guard stays held — and the page keeps saying "cancel requested" — until the process is really
 * gone. Nothing here pretends a cancel already worked.
 */
internal object ToolInstallGuard {
    const val IDLE = "idle"
    const val RUNNING = "running"
    const val CANCELLING = "cancelling"
    const val DONE = "done"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"

    /** Immutable snapshot: readers never see a half-updated state. */
    data class State(
        val phase: String,
        val tool: String?,
        val progress: Float,
        val message: String,
        val startedAtSec: Long,
        val longRunning: Boolean = false,
        val reason: String? = null,
    ) {
        val busy: Boolean get() = phase == RUNNING || phase == CANCELLING
        val cancelRequested: Boolean get() = phase == CANCELLING
    }

    private val running = AtomicBoolean(false)

    /** After a cancel request, an interrupted run — or one that never got to write a result — counts as cancelled. */
    private val cancelOutcomes = setOf(ToolFailure.INTERRUPTED, ToolFailure.NOT_RUN)

    @Volatile
    private var state = State(IDLE, null, 0f, "", 0L)

    /** The running script process (kept for the stream reader; a cancel does NOT go through it). */
    val process = AtomicReference<Process?>(null)

    /** True once a SIGTERM has really been delivered for the current run. */
    private var termSent = false

    /** True while one thread is reading /proc / signalling for the current run. */
    private var signalling = false

    /**
     * Marks the script process of THIS run: the app puts it in the script's environment, and a
     * cancel signals only a pid whose environment carries it (never another run's, e.g. a terminal's).
     */
    @Volatile
    var runToken: String = ""
        private set

    fun tryStart(
        tool: String,
        startedAtSec: Long,
        token: String =
            java.util.UUID
                .randomUUID()
                .toString(),
    ): Boolean {
        if (!running.compareAndSet(false, true)) return false
        synchronized(this) {
            termSent = false
            signalling = false
            runToken = token
            state = State(RUNNING, tool, 0f, "", startedAtSec)
        }
        return true
    }

    @Synchronized
    fun progress(
        progress: Float,
        message: String,
    ) {
        val now = state
        if (!now.busy) return
        state = now.copy(progress = progress, message = message)
    }

    @Synchronized
    fun markLongRunning() {
        val now = state
        if (now.busy) state = now.copy(longRunning = true)
    }

    /**
     * Ask the script to stop. Idempotent; false when nothing is running. Does NOT release the
     * guard — [finish] does, once the process has really ended. [signal] sends one SIGTERM and
     * returns whether it was delivered; it is retried through [retryCancel] until it is, because
     * the script's pid is not known during the first seconds of a run. `Process.destroy()` is not
     * used: on Android it also closes the output stream, which would end the read as an error
     * while bash and npm are still running.
     */
    fun requestCancel(signal: () -> Boolean): Boolean {
        val send =
            synchronized(this) {
                val now = state
                if (!now.busy) return false
                if (now.phase == RUNNING) state = now.copy(phase = CANCELLING)
                claimSignal()
            }
        if (send) deliver(signal)
        return true
    }

    /** Called periodically while cancelling: delivers the SIGTERM that could not be sent yet. */
    fun retryCancel(signal: () -> Boolean) {
        val send = synchronized(this) { state.phase == CANCELLING && claimSignal() }
        if (send) deliver(signal)
    }

    /** One sender at a time, and none after a delivery: the script's trap must not run twice. */
    private fun claimSignal(): Boolean {
        if (termSent || signalling) return false
        signalling = true
        return true
    }

    /** Reads /proc and signals OUTSIDE the monitor, so a slow target never blocks the output reader. */
    private fun deliver(signal: () -> Boolean) {
        val token = runToken
        var delivered = false
        try {
            delivered = signal()
        } finally {
            synchronized(this) {
                // A new run may have started meanwhile: its flags are not ours to set
                if (runToken == token) {
                    signalling = false
                    if (delivered) termSent = true
                }
            }
        }
    }

    /** True when a cancel was requested for the run that is still going. */
    fun cancelRequested(): Boolean = state.phase == CANCELLING

    /** The install coroutine is over: record the outcome and release. */
    @Synchronized
    fun finish(verdict: ToolVerdict) {
        val now = state
        val cancelled = now.phase == CANCELLING
        state =
            when {
                verdict is ToolVerdict.Success -> now.copy(phase = DONE, progress = 1f, reason = null)
                cancelled && (verdict as ToolVerdict.Failure).reason in cancelOutcomes ->
                    now.copy(phase = CANCELLED, progress = 0f, reason = ToolFailure.INTERRUPTED.name)
                else -> now.copy(phase = FAILED, progress = 0f, reason = (verdict as ToolVerdict.Failure).reason.name)
            }
        process.set(null)
        running.set(false)
    }

    fun snapshot(): State = state

    /** True while an install coroutine is working. */
    internal fun isRunning(): Boolean = running.get()
}

/**
 * Sends the cancel SIGTERM to the install script by the pid it records in `.tools.lock/pid`
 * (bash's `$$` — the process the app started). The pid is used only if `/proc/<pid>/cmdline` names
 * `post-setup.sh` AND `/proc/<pid>/environ` carries this run's [ENV_NAME]=token, so neither a
 * recycled pid nor another run holding the lock (a terminal's `oa`) is ever signalled.
 */
internal object ToolSignal {
    private const val SIGTERM = 15
    private const val SCRIPT_NAME = "post-setup.sh"

    /** Environment variable the app sets on the script process; its value is [ToolInstallGuard.runToken]. */
    const val ENV_NAME = "OA_APP_RUN_TOKEN"

    fun sendTerm(
        lockPidFile: java.io.File,
        token: String,
        procDir: java.io.File = java.io.File("/proc"),
        send: (Int, Int) -> Unit = { pid, sig -> android.os.Process.sendSignal(pid, sig) },
    ): Boolean {
        val pid = readPid(lockPidFile)
        val ours = token.isNotEmpty() && pid != null && pid > 1 && isOurRun(procDir, pid, token)
        if (ours) send(pid!!, SIGTERM)
        return ours
    }

    private fun readPid(file: java.io.File): Int? =
        try {
            file.readText().trim().toIntOrNull()
        } catch (_: java.io.IOException) {
            null
        }

    private fun isOurRun(
        procDir: java.io.File,
        pid: Int,
        token: String,
    ): Boolean =
        try {
            val cmdline =
                java.io
                    .File(procDir, "$pid/cmdline")
                    .readBytes()
                    .toString(Charsets.UTF_8)
            val environ =
                java.io
                    .File(procDir, "$pid/environ")
                    .readBytes()
                    .toString(Charsets.UTF_8)
            cmdline.contains(SCRIPT_NAME) && environ.split('\u0000').contains("$ENV_NAME=$token")
        } catch (_: java.io.IOException) {
            false
        }
}
