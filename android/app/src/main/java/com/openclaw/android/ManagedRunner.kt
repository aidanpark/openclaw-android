package com.openclaw.android

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Runs one managed run ([RunKinds]) for the page: the checks before it, the script as a child
 * process, its progress as `run_progress` events, and its end — recorded in [outcomes] and held by
 * [ManagedRunGuard] for a page created later. Also stops the gateway on request (`gateway_state`).
 * JsBridge validates the kind and takes the [RunLease] on the caller thread; [run] releases it.
 * [processes] (the /proc reader and the signal) is injectable for tests. [refreshSetupScript] fetches
 * the newest `post-setup.sh` before a SETUP run ([BootstrapManager.refreshPostSetupScript]: the
 * public copy, else the copy in place, else the bundled one); blocking, called on the run's thread.
 */
@Suppress("LongParameterList") // the runner's collaborators, each replaced in tests
internal class ManagedRunner(
    private val homeDir: File,
    private val environment: () -> Map<String, String>,
    private val outcomes: RunOutcomeStore,
    private val gateway: GatewayControl,
    private val emit: (String, Map<String, Any?>) -> Unit,
    private val processes: RunProcesses = RunProcesses(),
    private val refreshSetupScript: () -> Unit = {},
) {
    companion object {
        private const val TAG = "ManagedRunner"

        /** Managed runs have their own event; tool installs keep `tool_progress`. */
        const val RUN_EVENT = "run_progress"
        const val GATEWAY_EVENT = "gateway_state"

        /** Phase of a `run_progress` event for a run that was not started (nothing ran, nothing is recorded). */
        const val REFUSED = "refused"
        const val GATEWAY_STOPPING = "stopping"
        const val GATEWAY_DONE = "done"

        /** Answers the script's yes/no questions (`ask_yn`, scripts/lib.sh) — the app has no terminal to ask in. */
        const val ASSUME_YES = "OA_ASSUME_YES"

        /** R3: written by `oa --update` (newer scripts); deleted before each run and checked by its `run=`. */
        const val RESULT_FILE = ".openclaw-android/update-result.conf"

        private const val LONG_RUNNING_MS = 30L * 60 * 1000
        private const val CANCEL_RETRY_MS = 1_000L
        private const val EMIT_INTERVAL_MS = 200L
        private const val MAX_LINE_CHARS = 300
        private const val MILLIS_PER_SECOND = 1000L

        /**
         * The one shape of `run_progress` and `getRunState()`: the guard's snapshot is the truth. A
         * SETUP state also carries `needMb`/`haveMb` (null unless `error=free-space`) and `warn` (the
         * `warn=` list, empty until the end), before `gatewayStopped`; other kinds never have them.
         */
        fun stateEvent(now: ManagedRunGuard.State): Map<String, Any?> =
            buildMap {
                put("kind", now.kind)
                put("phase", now.phase)
                put("stage", now.stage)
                put("stageTotal", now.stageTotal)
                put("progress", now.progress)
                put("message", now.message)
                put("cancelable", now.cancelable)
                put("cancelRequested", now.cancelRequested)
                put("longRunning", now.longRunning)
                put("reason", now.reason)
                put("exit", now.exit)
                put("detail", now.detail)
                put("warnings", now.warnings)
                if (now.kind == RunKinds.SETUP) {
                    val facts = now.setup ?: SetupFacts()
                    put("needMb", facts.needMb)
                    put("haveMb", facts.haveMb)
                    put("warn", facts.warn)
                }
                put("gatewayStopped", now.gatewayStopped)
            }

        private fun nowSec(): Long = System.currentTimeMillis() / MILLIS_PER_SECOND
    }

    fun emitState() = emit(RUN_EVENT, stateEvent(ManagedRunGuard.snapshot()))

    /**
     * Nothing was started: same shape as every `run_progress`, phase [REFUSED]. [gatewayStopped] is
     * true only when this run's own checks stopped a gateway before the refusal, so the page can say
     * to start it again (the refusal is not kept in the guard, so the event is the only record).
     */
    fun emitRefused(
        kind: String?,
        reason: UpdateReason,
        gatewayStopped: Boolean = false,
    ) {
        val total = RunKinds.spec(kind)?.stageTotal ?: 0
        val refused =
            ManagedRunGuard.State(
                kind,
                REFUSED,
                0,
                total,
                "",
                0L,
                reason = reason.name,
                gatewayStopped = gatewayStopped,
            )
        emit(RUN_EVENT, stateEvent(refused))
    }

    /** The lease is taken: show the run that holds it, or say BUSY (a tool install, or checks before a run). */
    fun emitBusy(kind: String) {
        if (ManagedRunGuard.snapshot().busy) emitState() else emitRefused(kind, UpdateReason.BUSY)
    }

    /** The whole run. The caller took the [RunLease] for [kind]; it is released here, whatever happens. */
    suspend fun run(
        kind: String,
        stopGateway: Boolean,
    ) {
        try {
            val spec = RunKinds.spec(kind) ?: return emitRefused(null, UpdateReason.UNKNOWN)
            val token =
                java.util.UUID
                    .randomUUID()
                    .toString()
            // Set only when the checks really stopped a gateway (not when none was running); a refusal
            // after that point still says so, since the gateway is down whether or not the run starts
            var gatewayStopped = false
            val refusal = precheck(spec, token, stopGateway) { gatewayStopped = true }
            if (refusal != null) return emitRefused(kind, refusal, gatewayStopped)
            val startedAtSec = nowSec()
            if (!ManagedRunGuard.tryStart(kind, startedAtSec, token, gatewayStopped)) {
                // Another run took the guard meanwhile: its own state is not ours, so only BUSY is sent
                return emitRefused(kind, UpdateReason.BUSY, gatewayStopped)
            }
            execute(spec, startedAtSec)
        } finally {
            RunLease.release(kind)
        }
    }

    /**
     * Reasons not to start at all: no `oa`, an updater already running elsewhere, a setup script that
     * cannot be run this way, a gateway in the way. [onGatewayStopped] is called when the checks
     * stopped a running gateway ([gatewayRefusal]).
     */
    private fun precheck(
        spec: RunSpec,
        token: String,
        stopGateway: Boolean,
        onGatewayStopped: () -> Unit,
    ): UpdateReason? =
        when {
            CommandRunner.findExecutable(spec.command.first(), environment()) == null -> UpdateReason.NOT_INSTALLED
            processes.scan.externalRunners(token).isNotEmpty() -> UpdateReason.BUSY
            !scriptReady(spec) -> UpdateReason.NOT_INSTALLED
            !spec.gatewayCheck -> null
            else -> gatewayRefusal(stopGateway, onGatewayStopped)
        }

    /**
     * A run of a script in the home directory (SETUP) needs that script refreshed and able to run as
     * a managed run ([ManagedSetup.capable]). An older script would start the interactive onboard and
     * write no result file: the page keeps the terminal flow for it.
     */
    @Suppress("TooGenericExceptionCaught") // the refresh is best effort: the copy in place is checked either way
    private fun scriptReady(spec: RunSpec): Boolean {
        val script = spec.homeScript ?: return true
        try {
            refreshSetupScript()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not refresh the setup script", e)
        }
        return ManagedSetup.capable(File(homeDir, script))
    }

    /**
     * A running gateway is stopped only when the page asked for it; one the app does not own is never
     * touched. [onStopped] is called only for [GatewayControl.StopResult.STOPPED] (not NOT_RUNNING: then
     * there was nothing to stop), so the page can say the gateway must be started again.
     */
    private fun gatewayRefusal(
        stopGateway: Boolean,
        onStopped: () -> Unit,
    ): UpdateReason? {
        if (!gateway.status().running) return null
        if (!stopGateway) return UpdateReason.GATEWAY_RUNNING
        val result = stopGatewayAndReport(force = false)
        if (result == GatewayControl.StopResult.STOPPED) onStopped()
        return when (result) {
            // Ours is gone, but a gateway the app does not own may still run (the script would refuse)
            GatewayControl.StopResult.STOPPED, GatewayControl.StopResult.NOT_RUNNING ->
                if (gateway.status().running) UpdateReason.GATEWAY_RUNNING else null
            GatewayControl.StopResult.NOT_OURS -> UpdateReason.GATEWAY_RUNNING
            GatewayControl.StopResult.STILL_RUNNING -> UpdateReason.GATEWAY_STOP_FAILED
        }
    }

    /** Runs the script and always ends the run: record, release the guard, send the end — in that order. */
    @Suppress("TooGenericExceptionCaught") // the outcome record is a display aid: nothing it throws may escape
    private suspend fun execute(
        spec: RunSpec,
        startedAtSec: Long,
    ) = coroutineScope<Unit> {
        var verdict: RunVerdict = RunVerdict.Failure(UpdateReason.UNKNOWN, null, "", 0)
        val longRunning =
            launch {
                delay(LONG_RUNNING_MS)
                ManagedRunGuard.markLongRunning()
                emitState()
            }
        // A cancel asked for before the processes existed is delivered as soon as it can be
        val cancelPump =
            launch {
                while (true) {
                    delay(CANCEL_RETRY_MS)
                    ManagedRunGuard.retryCancel(::sendCancelSignal)
                    emitState() // lines merged away by the 200ms limit still reach the page
                }
            }
        try {
            emitState()
            verdict = runScript(spec, startedAtSec)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Managed run failed unexpectedly: ${spec.kind}", e)
        } finally {
            longRunning.cancel()
            cancelPump.cancel()
            // An end the user's cancel explains is CANCELLED in the record too (also when the run
            // threw); the start record (INTERRUPTED) stays only if the app dies before this point
            verdict = ManagedRunGuard.settle(verdict)
            try {
                // A display aid only: nothing that goes wrong here may keep the guard held
                try {
                    outcomes.record(spec.kind, nowSec(), verdict)
                } catch (e: Throwable) {
                    // Even an Error stops here: escaping would make the error handler report a
                    // failure for a run that did finish
                    AppLogger.w(TAG, "Could not record the run outcome: ${spec.kind}", e)
                } finally {
                    ManagedRunGuard.finish(verdict)
                }
            } finally {
                // The page gets the real end state even if the record threw something worse than an Exception
                emitState()
            }
        }
    }

    /**
     * The script itself. An update's result file is removed first, so only this run's can be read
     * after. A setup's is kept: the script replaces it once it holds the lock, and a run refused as
     * busy (exit 2) must not remove the file of the run that holds it (it is read by its `run=`).
     */
    private suspend fun runScript(
        spec: RunSpec,
        startedAtSec: Long,
    ): RunVerdict {
        val setup = spec.kind == RunKinds.SETUP
        val resultFile = File(homeDir, spec.resultFile)
        if (!setup) deleteQuietly(resultFile)
        val watcher = RunOutputWatcher(spec)
        var lastEmitMs = 0L
        val env = environment() + (ToolSignal.ENV_NAME to ManagedRunGuard.runToken) + spec.env
        recordStarted(spec.kind, startedAtSec)
        val exitCode =
            CommandRunner.streamLong(spec.argv(homeDir), env, homeDir, ManagedRunGuard.process) { raw ->
                val line = watcher.accept(raw)
                val before = ManagedRunGuard.snapshot()
                val message = if (line.isBlank()) before.message else line.takeLast(MAX_LINE_CHARS)
                ManagedRunGuard.progress(watcher.stage, spec.stageTotal, message)
                val after = ManagedRunGuard.snapshot()
                // A new stage (and the end of the cancelable range) is sent at once; other lines at
                // most every 200ms — npm prints thousands
                val nowMs = System.currentTimeMillis()
                if (after.stage != before.stage ||
                    after.phase != before.phase ||
                    nowMs - lastEmitMs >= EMIT_INTERVAL_MS
                ) {
                    lastEmitMs = nowMs
                    emitState()
                }
                ManagedRunGuard.retryCancel(::sendCancelSignal)
            }
        if (setup) {
            return SetupVerdict.decide(
                SetupResultParser.read(resultFile),
                exitCode,
                startedAtSec,
                File(homeDir, ManagedSetup.MARKER).exists(),
                watcher.failLines(),
                watcher.lastWarning(),
            )
        }
        return UpdateVerdict.decide(
            readResult(resultFile),
            exitCode,
            watcher.failLines(),
            startedAtSec,
            watcher.sawComplete,
            watcher.warnings,
            watcher.lastWarning(),
        )
    }

    /**
     * Recorded before the script starts, as a run that was interrupted: if the app process dies
     * during the run, the record says so after a restart instead of showing the run before it.
     * The record at the end ([execute]) replaces it.
     */
    @Suppress("TooGenericExceptionCaught") // a display aid: it must never keep the run from starting
    private fun recordStarted(
        kind: String,
        startedAtSec: Long,
    ) {
        try {
            outcomes.record(kind, startedAtSec, RunVerdict.Failure(UpdateReason.INTERRUPTED, null, "", 0))
        } catch (e: Throwable) {
            // Even an Error stops here: this record must never keep the update from running
            AppLogger.w(TAG, "Could not record the run start: $kind", e)
        }
    }

    private fun deleteQuietly(file: File) {
        try {
            file.delete()
        } catch (e: SecurityException) {
            AppLogger.w(TAG, "Could not remove the previous result file", e)
        }
    }

    private fun readResult(file: File): RunResultFile? =
        try {
            if (file.isFile &&
                file.length() <= RunResultParser.MAX_CHARS
            ) {
                RunResultParser.parse(file.readText())
            } else {
                null
            }
        } catch (_: java.io.IOException) {
            null
        }

    /** Ask the run to stop; refused (state unchanged) once it is past its cancelable stages. */
    fun cancel() {
        ManagedRunGuard.requestCancel(::sendCancelSignal)
        // Also after a refusal: the page learns the run is no longer cancelable
        if (ManagedRunGuard.snapshot().busy) emitState()
    }

    /** [token] is the run the cancel was claimed for ([ManagedRunGuard.requestCancel]). */
    private fun sendCancelSignal(token: String): Boolean =
        try {
            RunSignal.sendTerm(
                token,
                processes.scan,
                allowed = { ManagedRunGuard.runToken == token && ManagedRunGuard.snapshot().cancelable },
                send = processes.send,
            )
        } catch (e: SecurityException) {
            AppLogger.w(TAG, "Cancel signal was refused", e)
            false // retried by the pump; the page keeps saying "cancel requested" until the run ends
        }

    /** `{ "<KIND>": {at, verdict, reason?, exit, detail?, warnings} }` — `{}` before the first run. */
    fun lastRuns(): Map<String, Map<String, Any?>> =
        outcomes.load().mapValues { (_, o) ->
            buildMap {
                put("at", o.atSec)
                put("verdict", if (o.success) RunOutcomeStore.SUCCESS else RunOutcomeStore.FAILURE)
                o.reason?.let { put("reason", it.name) }
                put("exit", o.exit)
                if (o.detail.isNotEmpty()) put("detail", o.detail)
                put("warnings", o.warnings)
            }
        }

    /** `{running, ours, pids}`. */
    fun gatewayStatus(): Map<String, Any?> {
        val now = gateway.status()
        return mapOf("running" to now.running, "ours" to now.ours, "pids" to now.pids)
    }

    /** Stops the app's own gateway; `gateway_state` says `stopping`, then `done` with the result. */
    fun stopGatewayAndReport(force: Boolean): GatewayControl.StopResult {
        emit(GATEWAY_EVENT, mapOf("phase" to GATEWAY_STOPPING, "running" to true))
        val result = gateway.stop(force)
        emit(
            GATEWAY_EVENT,
            mapOf("phase" to GATEWAY_DONE, "result" to result.name, "running" to gateway.status().running),
        )
        return result
    }
}
