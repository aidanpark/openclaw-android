package com.openclaw.android

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * One kind of managed run: the fixed command the app runs (the page only names the kind), how its
 * stage lines look, and through which stage a cancel is still safe.
 */
internal data class RunSpec(
    val kind: String,
    val command: List<String>,
    val stageTotal: Int,
    val lastCancelableStage: Int,
    val stagePattern: Regex,
) {
    /** True while the run has not reached a stage after [lastCancelableStage]. */
    fun cancelableAt(stage: Int): Boolean = stage <= lastCancelableStage
}

/** The managed runs the app knows. The page may start only these ([BridgeGuard.runKinds]). */
internal object RunKinds {
    const val UPDATE = "UPDATE"

    /**
     * `oa --update`: `update-core.sh` prints `[N/5] <title>` per stage. Only `[1/5] Pre-flight Check`
     * and `[2/5] Download Latest Release` may be cancelled: `[3/5] Update Core Infrastructure`
     * starts with `rm -rf platforms/<p>` then `cp -r` (not atomic) and the pre-update backup.
     */
    private val update =
        RunSpec(
            kind = UPDATE,
            command = listOf("oa", "--update"),
            stageTotal = 5,
            lastCancelableStage = 2,
            stagePattern = UpdateStageParser.pattern,
        )

    private val specs = mapOf(UPDATE to update)

    fun spec(kind: String?): RunSpec? = kind?.let { specs[it] }
}

/** Why a managed run did not succeed. The page maps each to a translated message. */
internal enum class UpdateReason {
    BUSY,
    GATEWAY_RUNNING,
    GATEWAY_STOP_FAILED,
    NO_SPACE,
    CACHE_STALE,
    SESSION_GUARD,
    DOWNLOAD,
    CHECKSUM,
    INSTALL_FAILED,
    MIGRATION_FAILED,
    HEALTH_FAILED,

    /** Ended by a signal (or the app process died) with no cancel asked for in the app. */
    INTERRUPTED,

    /** Ended after the user's cancel ([ManagedRunGuard.settle]); the guard's phase is `cancelled`. */
    CANCELLED,
    NOT_INSTALLED,
    UNKNOWN,
}

/** How one managed run ended. [exit] is the process's exit code (null when it never ran). */
internal sealed interface RunVerdict {
    val exit: Int?
    val warnings: Int

    /**
     * The script's own words for the page, as printed (English, never translated): for a failure the
     * last `[FAIL]` block, for a success with warnings the last `[WARN]` sentence; empty otherwise.
     */
    val detail: String

    /** [detail] is the last `[WARN]` sentence, only when [warnings] > 0 ([UpdateVerdict.decide]). */
    data class Success(
        override val exit: Int,
        override val warnings: Int,
        override val detail: String = "",
    ) : RunVerdict

    data class Failure(
        val reason: UpdateReason,
        override val exit: Int?,
        override val detail: String,
        override val warnings: Int,
    ) : RunVerdict
}

/**
 * Process-wide: where the managed run (`oa --update`) is, for the page that started it and for a
 * page created later (the Activity was recreated). Same rules as [ToolInstallGuard]: an `object`
 * so no instance member can shadow it, immutable snapshots, state changes under the monitor, and
 * /proc reads and signals OUTSIDE it.
 *
 * Phases: idle → running → (cancelling →) done | failed | cancelled. A cancel is accepted only
 * while the run is [State.cancelable] (an update before `[3/5]`); once a run has left that range it
 * never becomes cancelable again, and a cancel that could not be delivered by then is withdrawn
 * (the phase goes back to running) instead of reading as "cancel requested" until the end.
 */
internal object ManagedRunGuard {
    const val IDLE = "idle"
    const val RUNNING = "running"
    const val CANCELLING = "cancelling"
    const val DONE = "done"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"

    /** Immutable snapshot: readers never see a half-updated state. */
    data class State(
        val kind: String?,
        val phase: String,
        val stage: Int,
        val stageTotal: Int,
        val message: String,
        val startedAtSec: Long,
        val longRunning: Boolean = false,
        val cancelable: Boolean = false,
        val reason: String? = null,
        val exit: Int? = null,
        val detail: String = "",
        val warnings: Int = 0,
        /**
         * The checks before this run stopped a running gateway ([GatewayControl.StopResult.STOPPED]);
         * the page says to start it again. In the guard it is set only by [tryStart], kept by every later
         * copy, and not part of `last-run.conf`; a refusal after the stop carries it in its event only
         * ([ManagedRunner.emitRefused]), since a refusal never reaches the guard.
         */
        val gatewayStopped: Boolean = false,
    ) {
        val busy: Boolean get() = phase == RUNNING || phase == CANCELLING
        val cancelRequested: Boolean get() = phase == CANCELLING

        /** 0..1 from the stage lines; 1 once done. */
        val progress: Float
            get() =
                when {
                    phase == DONE -> 1f
                    stageTotal > 0 -> stage.toFloat() / stageTotal
                    else -> 0f
                }
    }

    private val running = AtomicBoolean(false)

    /**
     * After a cancel request, an interrupted run — or one whose end could not be told — counts as
     * cancelled. CANCELLED itself is in the set so [settle] and [finish] agree on an already settled verdict.
     */
    private val cancelOutcomes = setOf(UpdateReason.INTERRUPTED, UpdateReason.UNKNOWN, UpdateReason.CANCELLED)

    @Volatile
    private var state = State(null, IDLE, 0, 0, "", 0L)

    /** The running process (kept for the stream reader; a cancel does NOT go through it). */
    val process = AtomicReference<Process?>(null)

    /** True once a SIGTERM has really been delivered for the current run. */
    private var termSent = false

    /** True while one thread is reading /proc / signalling for the current run. */
    private var signalling = false

    /**
     * Marks every process of THIS run: the app puts it in the environment ([ToolSignal.ENV_NAME]),
     * children inherit it, and a cancel signals only pids whose environment carries it.
     */
    @Volatile
    var runToken: String = ""
        private set

    fun tryStart(
        kind: String,
        startedAtSec: Long,
        token: String =
            java.util.UUID
                .randomUUID()
                .toString(),
        gatewayStopped: Boolean = false,
    ): Boolean {
        if (!running.compareAndSet(false, true)) return false
        val spec = RunKinds.spec(kind)
        synchronized(this) {
            termSent = false
            signalling = false
            runToken = token
            state =
                State(
                    kind = kind,
                    phase = RUNNING,
                    stage = 0,
                    stageTotal = spec?.stageTotal ?: 0,
                    message = "",
                    startedAtSec = startedAtSec,
                    cancelable = spec?.cancelableAt(0) ?: false,
                    gatewayStopped = gatewayStopped,
                )
        }
        return true
    }

    /**
     * A new output line. [stage] never goes back (a stray `[1/5]` later in the output changes
     * nothing), and [State.cancelable] only ever goes from true to false.
     */
    @Synchronized
    fun progress(
        stage: Int,
        total: Int,
        message: String,
    ) {
        val now = state
        if (!now.busy) return
        val reached = maxOf(now.stage, stage)
        val cancelable = now.cancelable && RunKinds.spec(now.kind)?.cancelableAt(reached) == true
        // A cancel that was never delivered is withdrawn once the run is past the safe stages
        val withdrawn = now.phase == CANCELLING && !cancelable && !termSent
        state =
            now.copy(
                stage = reached,
                stageTotal = total,
                message = message,
                cancelable = cancelable,
                phase = if (withdrawn) RUNNING else now.phase,
            )
    }

    @Synchronized
    fun markLongRunning() {
        val now = state
        if (now.busy) state = now.copy(longRunning = true)
    }

    /**
     * Ask the run to stop. False — and nothing changes — when nothing runs or the run is no longer
     * cancelable. Does NOT release the guard: [finish] does, once the process has really ended.
     * [signal] sends SIGTERM to the processes of the run whose token it is given (the token taken
     * with the claim, so a run started meanwhile is never the one signalled) and returns whether it
     * reached one; it is retried through [retryCancel] until it does. `Process.destroy()` is not
     * used: on Android it also closes the output stream while the script may still be running.
     */
    fun requestCancel(signal: (String) -> Boolean): Boolean {
        val token =
            synchronized(this) {
                val now = state
                if (!now.busy || !now.cancelable) return false
                if (now.phase == RUNNING) state = now.copy(phase = CANCELLING)
                claimSignal()
            }
        if (token != null) deliver(token, signal)
        return true
    }

    /** Called periodically while cancelling: delivers the SIGTERM that could not be sent yet. */
    fun retryCancel(signal: (String) -> Boolean) {
        val token = synchronized(this) { if (state.phase == CANCELLING && state.cancelable) claimSignal() else null }
        if (token != null) deliver(token, signal)
    }

    /**
     * One sender at a time, and none after a delivery: the script must not be signalled twice.
     * Returns the token of the run the claim is for (under the monitor), or null when not claimed.
     */
    private fun claimSignal(): String? {
        if (termSent || signalling) return null
        signalling = true
        return runToken
    }

    /** Reads /proc and signals OUTSIDE the monitor, so a slow /proc never blocks the output reader. */
    private fun deliver(
        token: String,
        signal: (String) -> Boolean,
    ) {
        var delivered = false
        try {
            delivered = signal(token)
        } finally {
            synchronized(this) {
                // A new run may have started meanwhile: its flags are not ours to set
                if (runToken == token) {
                    signalling = false
                    if (delivered) {
                        termSent = true
                        // Delivered after all (the withdrawal raced it): the run is being stopped
                        if (state.phase == RUNNING) state = state.copy(phase = CANCELLING)
                    }
                }
            }
        }
    }

    /** True when a cancel was requested for the run that is still going. */
    fun cancelRequested(): Boolean = state.phase == CANCELLING

    /**
     * A failure that the requested cancel explains is CANCELLED — so the record says what [finish]
     * shows (cancelled), not "interrupted" or "could not tell". Without a cancel request the verdict
     * is kept: a signal from elsewhere (or the app dying) stays INTERRUPTED.
     */
    fun settle(verdict: RunVerdict): RunVerdict =
        if (verdict is RunVerdict.Failure && cancelRequested() && verdict.reason in cancelOutcomes) {
            verdict.copy(reason = UpdateReason.CANCELLED)
        } else {
            verdict
        }

    /** The run is over: record how it ended and release. */
    @Synchronized
    fun finish(verdict: RunVerdict) {
        val now = state
        if (now.busy) {
            val ended =
                now.copy(cancelable = false, exit = verdict.exit, warnings = verdict.warnings)
            state =
                when (verdict) {
                    is RunVerdict.Success -> ended.copy(phase = DONE, reason = null, detail = verdict.detail)
                    is RunVerdict.Failure ->
                        if (now.phase == CANCELLING && verdict.reason in cancelOutcomes) {
                            ended.copy(
                                phase = CANCELLED,
                                reason = UpdateReason.CANCELLED.name,
                                detail = verdict.detail,
                            )
                        } else {
                            ended.copy(phase = FAILED, reason = verdict.reason.name, detail = verdict.detail)
                        }
                }
        }
        process.set(null)
        running.set(false)
    }

    fun snapshot(): State = state

    /** True while a managed run is working. */
    internal fun isRunning(): Boolean = running.get()
}
