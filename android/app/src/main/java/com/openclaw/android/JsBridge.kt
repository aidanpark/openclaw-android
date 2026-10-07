package com.openclaw.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * WebView → Kotlin bridge via @JavascriptInterface (§2.6).
 * All methods callable from JavaScript as window.OpenClaw.<method>().
 * All return values are JSON strings. Async operations use EventBridge (§2.8).
 */
@Suppress("TooManyFunctions", "LargeClass") // WebView bridge — single facade by design
class JsBridge(
    private val activity: MainActivity,
    private val sessionManager: TerminalSessionManager,
    private val bootstrapManager: BootstrapManager,
    private val eventBridge: EventBridge,
) {
    private val gson = Gson()

    companion object {
        private const val TAG = "JsBridge"

        private const val API_TIMEOUT_MS = 5000
        private const val MILLIS_PER_SECOND = 1000L
        private const val LONG_RUNNING_MS = 30L * 60 * 1000
        private const val CANCEL_RETRY_MS = 1_000L
        private const val VERIFY_TIMEOUT_MS = 10_000L
        private const val EMIT_INTERVAL_MS = 200L
        private const val MAX_LINE_CHARS = 300

        /** Tool installs have their own event, so the OpenClaw platform/update screens never see them. */
        private const val TOOL_EVENT = "tool_progress"
        private val ansiPattern = Regex("\u001B\\[[0-9;?]*[A-Za-z]")
        private const val LIST_TIMEOUT_MS = 15_000L
        private const val PROGRESS_PREPARE = 0.05f
        private const val PROGRESS_START = 0f
        private const val PROGRESS_HALF = 0.5f
    }

    /**
     * Launch a coroutine on Dispatchers.IO with error handling.
     * Catches all exceptions to prevent app crashes from unhandled coroutine failures.
     * Errors are logged and emitted to the WebView via EventBridge.
     */
    private fun launchWithErrorHandling(
        errorEventType: String = "error",
        errorContext: Map<String, Any?> = emptyMap(),
        block: suspend CoroutineScope.() -> Unit,
    ) {
        val handler =
            CoroutineExceptionHandler { _, throwable ->
                AppLogger.e(TAG, "Coroutine error [$errorEventType]: ${throwable.message}", throwable)
                eventBridge.emit(
                    errorEventType,
                    errorContext +
                        mapOf(
                            "error" to (throwable.message ?: "Unknown error"),
                            "progress" to PROGRESS_START,
                            "message" to "Error: ${throwable.message}",
                        ),
                )
            }
        CoroutineScope(Dispatchers.IO + handler).launch(block = block)
    }
    // ═══════════════════════════════════════════
    // Terminal domain
    // ═══════════════════════════════════════════

    @JavascriptInterface
    fun showTerminal() {
        // Create session if none exists (e.g., after first-time setup)
        if (sessionManager.activeSession == null) {
            val session = sessionManager.createSession()
            if (bootstrapManager.needsPostSetup()) {
                val script = bootstrapManager.postSetupScript.absolutePath
                // A script the app can run itself is run by the page (managed SETUP), not typed here
                val capable = bootstrapManager.setupScriptCapable()
                // The new session's shell starts only once the view lays it out: the text waits for
                // it (writeWhenReady), and the decision is asked at write time — the marker may have
                // appeared or a managed run started meanwhile
                sessionManager.writeWhenReady(session, "bash $script\n") {
                    ManagedSetup.shouldRunInTerminal(
                        scriptCapable = capable,
                        markerPresent = bootstrapManager.setupMarkerPresent(),
                        managedRunActive = ManagedSetup.managedRunActive(),
                    )
                }
            }
        }
        activity.showTerminal()
    }

    @JavascriptInterface
    fun showWebView() = activity.showWebView()

    @JavascriptInterface
    fun createSession(): String {
        val session = sessionManager.createSession()
        return gson.toJson(mapOf("id" to session.mHandle, "name" to (session.title ?: "Terminal")))
    }

    @JavascriptInterface
    fun switchSession(id: String) =
        activity.runOnUiThread {
            sessionManager.switchSession(id)
        }

    @JavascriptInterface
    fun closeSession(id: String) {
        sessionManager.closeSession(id)
    }

    @JavascriptInterface
    fun getTerminalSessions(): String = gson.toJson(sessionManager.getSessionsInfo())

    /**
     * Type one of the dashboard's fixed commands into the active terminal. The WebView names a
     * command ID; it can no longer send arbitrary text to the shell. No newline is added — the
     * user reviews the command and presses Enter. A session that [showTerminal] has just created
     * gets the command once its shell has started (writeWhenReady), so the page needs no wait.
     */
    @JavascriptInterface
    fun writeCommandToTerminal(commandId: String) {
        val command = BridgeGuard.terminalCommands[commandId] ?: return
        val session = sessionManager.activeSession ?: return
        sessionManager.writeWhenReady(session, command)
    }

    // ═══════════════════════════════════════════
    // Setup domain
    // ═══════════════════════════════════════════

    @JavascriptInterface
    fun getSetupStatus(): String = gson.toJson(bootstrapManager.getStatus())

    @JavascriptInterface
    fun getBootstrapStatus(): String =
        gson.toJson(
            mapOf(
                "installed" to bootstrapManager.isInstalled(),
                "prefixPath" to bootstrapManager.prefixDir.absolutePath,
            ),
        )

    @JavascriptInterface
    fun startSetup() {
        // A second tap or a retry racing the first would run two installs over the same prefix
        if (!SetupGuard.tryStart()) {
            AppLogger.w(TAG, "startSetup ignored: an install is already running")
            // Answer with where the running install is, so a page created after an Activity
            // recreation picks it up instead of staying at 0%
            val now = SetupGuard.snapshot()
            val state = mutableMapOf<String, Any?>("progress" to now.progress, "message" to now.message)
            if (now.errorKind != null) {
                // A failure that is not yet cleared: same shape the failure event has, so the page
                // shows the failure screen instead of a stuck 0%
                state["error"] = now.error
                state["errorKind"] = now.errorKind
            }
            eventBridge.emit("setup_progress", state)
            return
        }
        launchWithErrorHandling(
            errorEventType = "setup_progress",
            errorContext = mapOf("progress" to PROGRESS_START, "errorKind" to "UNKNOWN"),
        ) {
            try {
                bootstrapManager.startSetup { progress, message ->
                    SetupGuard.progress(progress, message)
                    eventBridge.emit(
                        "setup_progress",
                        mapOf("progress" to progress, "message" to message),
                    )
                }
            } catch (e: BootstrapDownloadException) {
                // Expected refusals (network, missing file, checksum): the WebView shows a
                // translated message for errorKind; `usr` was not touched, so retrying is safe
                AppLogger.w(TAG, "Bootstrap download refused: ${e.kind}", e)
                SetupGuard.failed(e.kind.name, e.message ?: e.kind.name)
                eventBridge.emit(
                    "setup_progress",
                    mapOf(
                        "progress" to PROGRESS_START,
                        "error" to (e.message ?: e.kind.name),
                        "errorKind" to e.kind.name,
                        "message" to (e.message ?: e.kind.name),
                    ),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancellation is not a failure to remember or report
                throw e
            } catch (e: Exception) {
                // Anything else: remember it for a page created later, then let the handler report it
                SetupGuard.failed("UNKNOWN", e.message ?: "Unknown error")
                throw e
            } finally {
                SetupGuard.finish()
            }
        }
    }

    /**
     * Where the install is — for a page that was created while one runs (Activity recreation)
     * and missed the progress events. phase: idle | running | done | failed.
     */
    @JavascriptInterface
    fun getSetupState(): String {
        val now = SetupGuard.snapshot()
        return gson.toJson(
            mapOf(
                "phase" to now.phase,
                "running" to now.running,
                "progress" to now.progress,
                "message" to now.message,
                "errorKind" to now.errorKind,
                "error" to now.error,
            ),
        )
    }

    /**
     * The last full setup (`post-setup-result.conf`) for the page that offers to continue an
     * unfinished install, and whether the app can run the setup itself (`managed`; else the page
     * keeps the terminal flow). Shape: [ManagedSetup.resultState].
     */
    @JavascriptInterface
    fun getSetupResult(): String =
        gson.toJson(
            ManagedSetup.resultState(bootstrapManager.homeDir, ProcScan(), bootstrapManager.setupScriptCapable()),
        )

    @JavascriptInterface
    fun saveToolSelections(json: String) {
        // post-setup.sh reads this file line by line and acts only on the known INSTALL_* keys with
        // true/false, so only those may reach it (see BridgeGuard.toolSelectionIds).
        val selections =
            BridgeGuard.parseToolSelections(json) ?: run {
                AppLogger.w(TAG, "Rejected tool selections: not a map of known tool ids to booleans")
                return
            }
        val configFile = java.io.File(bootstrapManager.homeDir, ".openclaw-android/tool-selections.conf")
        configFile.parentFile?.mkdirs()
        val lines =
            selections.entries.joinToString("\n") { (key, value) ->
                "INSTALL_${key.uppercase().replace("-", "_")}=$value"
            }
        configFile.writeText(lines + "\n")
    }

    // ═══════════════════════════════════════════
    // Platform domain
    // ═══════════════════════════════════════════

    @JavascriptInterface
    fun getAvailablePlatforms(): String {
        // Single built-in platform
        return gson.toJson(
            listOf(
                mapOf(
                    "id" to "openclaw",
                    "name" to "OpenClaw",
                    "icon" to "/openclaw.svg",
                    "desc" to "AI agent platform",
                ),
            ),
        )
    }

    @JavascriptInterface
    fun getActivePlatform(): String {
        val markerFile = java.io.File(bootstrapManager.homeDir, ".openclaw-android/.platform")
        val id = BridgeGuard.sanitizePlatformId(if (markerFile.exists()) markerFile.readText() else null)
        return gson.toJson(mapOf("id" to id, "name" to id.replaceFirstChar { it.uppercase() }))
    }

    // ═══════════════════════════════════════════
    // Tools domain
    // ═══════════════════════════════════════════

    @JavascriptInterface
    fun getInstalledTools(): String {
        // Files are there, but the last install of the tool ended "does not work": say so, not "installed"
        val onDisk = BridgeGuard.toolIds.filter { toolOnDisk(it) }
        runCatching { toolOutcomes.prune(onDisk.toSet()) }
        val brokenTools = toolOutcomes.load().keys
        val tools =
            onDisk
                .map { mapOf("id" to it, "name" to it, "version" to "installed", "broken" to (it in brokenTools)) }
        return gson.toJson(tools)
    }

    /**
     * Does each installed tool actually run? One `tools_check` event per tool
     * (`status` = ok | failed | unknown) and a last one with `done`. Skipped while an install is
     * running (a tool half-written by it would read as broken). Only the `--version` style checks in
     * [BridgeGuard.toolVerifyCommands] run — nothing the page can choose.
     */
    @JavascriptInterface
    fun checkInstalledToolsAsync(callbackId: String) {
        launchWithErrorHandling(
            errorEventType = "tools_check",
            errorContext = mapOf("callbackId" to callbackId, "done" to true),
        ) {
            if (!ToolInstallGuard.isRunning()) {
                val env = probeEnvironment()
                coroutineScope {
                    BridgeGuard.toolVerifyCommands
                        .filterKeys { toolOnDisk(it) }
                        .map { (id, cmd) ->
                            async {
                                val result =
                                    ProbeLimiter.semaphore.withPermit {
                                        CommandRunner.runExecutable(
                                            cmd.executable,
                                            cmd.args,
                                            env,
                                            bootstrapManager.homeDir,
                                            VERIFY_TIMEOUT_MS,
                                        )
                                    }
                                eventBridge.emit(
                                    "tools_check",
                                    mapOf(
                                        "callbackId" to callbackId,
                                        "target" to id,
                                        "status" to
                                            ToolProbe.status(
                                                result.exitCode,
                                                result.stderr.startsWith(CommandRunner.TIMEOUT_PREFIX),
                                            ),
                                    ),
                                )
                            }
                        }.awaitAll()
                }
            }
            eventBridge.emit("tools_check", mapOf("callbackId" to callbackId, "done" to true))
        }
    }

    @JavascriptInterface
    fun isToolInstalled(id: String): String =
        gson.toJson(
            mapOf(
                "installed" to (id in BridgeGuard.toolIds && toolOnDisk(id)),
            ),
        )

    private val toolOutcomes by lazy { ToolOutcomeStore(java.io.File(activity.filesDir, "tool-outcomes.conf")) }

    private fun toolOnDisk(id: String): Boolean =
        ToolDetection.isInstalled(id, bootstrapManager.prefixDir, bootstrapManager.homeDir)

    /**
     * Install one tool through `post-setup.sh --tools-only` — the same signed package chain the
     * first install uses. The app builds no install command of its own. What it reports is not
     * the exit code: success needs this run's result file to say `ok` AND the files to be there.
     */
    @Suppress("TooGenericExceptionCaught") // the outcome record is a display aid: nothing it throws may escape
    @JavascriptInterface
    fun installTool(id: String) {
        if (id !in BridgeGuard.toolIds) return
        if (id in BridgeGuard.terminalOnlyTools) {
            return emitNotSupported(id, "TERMINAL_ONLY", "$id cannot be installed from the app yet")
        }
        val scriptId = BridgeGuard.toolInstallIds[id] ?: return
        val startedAtSec = System.currentTimeMillis() / MILLIS_PER_SECOND
        // One run that changes the install at a time, tool install or update (released in the finally below)
        val leased = RunLease.tryAcquire(RunLease.TOOLS)
        if (!leased || !ToolInstallGuard.tryStart(id, startedAtSec)) return refuseToolInstall(id, leased)
        launchWithErrorHandling(
            errorEventType = TOOL_EVENT,
            errorContext =
                mapOf(
                    "target" to id,
                    "phase" to ToolInstallGuard.FAILED,
                    "errorKind" to "TOOL_INSTALL_FAILED",
                    "reason" to ToolFailure.UNKNOWN.name,
                ),
        ) {
            var verdict: ToolVerdict = ToolVerdict.Failure(ToolFailure.UNKNOWN)
            val longRunning =
                launch {
                    delay(LONG_RUNNING_MS)
                    ToolInstallGuard.markLongRunning()
                    emitToolState()
                }
            // A cancel asked for before the script had written its pid is delivered as soon as it can be
            val cancelPump =
                launch {
                    while (true) {
                        delay(CANCEL_RETRY_MS)
                        ToolInstallGuard.retryCancel(::sendCancelSignal)
                        emitToolState() // lines merged away by the 200ms limit still reach the page
                    }
                }
            try {
                emitToolState()
                verdict = runToolInstall(id, scriptId, startedAtSec)
                // Apart, so a check that throws leaves the interrupted verdict in place
                verdict = settleLateCancel(id, verdict)
            } catch (e: Exception) {
                AppLogger.w(TAG, "Tool install failed unexpectedly: $id", e)
            } finally {
                longRunning.cancel()
                cancelPump.cancel()
                // The script has ended: another run may start (before anything below can throw)
                RunLease.release(RunLease.TOOLS)
                try {
                    // A display aid only: nothing that goes wrong here may keep the guard held
                    try {
                        toolOutcomes.record(id, verdict)
                    } catch (e: Throwable) {
                        // Even an Error stops here: escaping would make the error handler tell the page
                        // "failed" about an install that did finish
                        AppLogger.w(TAG, "Could not record the tool outcome: $id", e)
                    } finally {
                        ToolInstallGuard.finish(verdict)
                    }
                } finally {
                    // The page gets the real end state even if the record threw something worse than an Exception
                    emitToolState()
                }
            }
        }
    }

    /** The whole install, in the order that keeps an older script from doing harm. */
    private suspend fun runToolInstall(
        id: String,
        scriptId: String,
        startedAtSec: Long,
    ): ToolVerdict {
        val home = bootstrapManager.homeDir
        val ocaDir = java.io.File(home, ".openclaw-android")
        val script = bootstrapManager.postSetupScript.absolutePath
        val env = EnvironmentBuilder.build(activity)
        val notReady = prepareInstaller(ocaDir, script, env, scriptId)
        if (notReady != null) return notReady
        var hint: ToolFailure? = null
        var lastEmitMs = 0L
        val exitCode =
            CommandRunner.streamLong(
                listOf("bash", script, "--tools-only", scriptId),
                env + (ToolSignal.ENV_NAME to ToolInstallGuard.runToken),
                home,
                ToolInstallGuard.process,
            ) { raw ->
                val full = ansiPattern.replace(raw, "")
                val line = full.takeLast(MAX_LINE_CHARS)
                hint = ToolInstallVerdict.hintFromOutput(full) ?: hint
                ToolInstallGuard.progress(PROGRESS_HALF, line)
                // npm prints thousands of lines: the page gets at most a few updates per second
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastEmitMs >= EMIT_INTERVAL_MS) {
                    lastEmitMs = nowMs
                    emitToolState()
                }
                ToolInstallGuard.retryCancel(::sendCancelSignal)
            }
        val facts = ToolRunFacts(startedAtSec, scriptId, exitCode, toolOnDisk(id), hint)
        return ToolInstallVerdict.decide(readToolResult(ocaDir), facts)
    }

    /**
     * A cancelled run whose install had already finished ([ToolCancelCheck]): the tool's own
     * `--version` check decides. OK → success, which [ToolInstallGuard.finish] ends as done with
     * reason CANCEL_TOO_LATE; anything else (no check for the tool, failed, timeout) keeps [verdict].
     */
    private suspend fun settleLateCancel(
        id: String,
        verdict: ToolVerdict,
    ): ToolVerdict {
        val cmd = BridgeGuard.toolVerifyCommands[id]
        if (cmd == null || !ToolCancelCheck.worthProbing(verdict, ToolInstallGuard.cancelRequested(), toolOnDisk(id))) {
            return verdict
        }
        val result =
            ProbeLimiter.semaphore.withPermit {
                CommandRunner.runExecutable(
                    cmd.executable,
                    cmd.args,
                    probeEnvironment(),
                    bootstrapManager.homeDir,
                    VERIFY_TIMEOUT_MS,
                )
            }
        val status = ToolProbe.status(result.exitCode, result.stderr.startsWith(CommandRunner.TIMEOUT_PREFIX))
        AppLogger.i(TAG, "Cancelled install of $id: its check after the run says $status")
        return ToolCancelCheck.settle(verdict, status)
    }

    /** Marker, updated installer, `--tools-only` support — each step may be cut short by a cancel. */
    private suspend fun prepareInstaller(
        ocaDir: java.io.File,
        script: String,
        env: Map<String, String>,
        scriptId: String,
    ): ToolVerdict.Failure? =
        markerFailure(ocaDir)
            ?: cancelFailure()
            ?: refreshInstaller()
            ?: checkListing(script, env, scriptId)

    private fun cancelFailure(): ToolVerdict.Failure? =
        if (ToolInstallGuard.cancelRequested()) ToolVerdict.Failure(ToolFailure.NOT_RUN) else null

    // An older script with no marker would start a FULL install for any argument
    private fun markerFailure(ocaDir: java.io.File): ToolVerdict.Failure? =
        if (java.io
                .File(
                    ocaDir,
                    ".post-setup-done",
                ).exists()
        ) {
            null
        } else {
            ToolVerdict.Failure(ToolFailure.SETUP_INCOMPLETE)
        }

    private suspend fun refreshInstaller(): ToolVerdict.Failure? {
        ToolInstallGuard.progress(PROGRESS_PREPARE, "Updating the installer...")
        emitToolState()
        withContext(Dispatchers.IO) { bootstrapManager.refreshPostSetupScript() }
        return cancelFailure()
    }

    /** Only a script that lists its tools understands --tools-only; "already complete" means it does not. */
    private suspend fun checkListing(
        script: String,
        env: Map<String, String>,
        scriptId: String,
    ): ToolVerdict.Failure? {
        val list =
            withContext(Dispatchers.IO) {
                CommandRunner.runExecutable(
                    "bash",
                    listOf(script, "--tools-only", "--list"),
                    env,
                    bootstrapManager.homeDir,
                    LIST_TIMEOUT_MS,
                )
            }
        return cancelFailure()
            ?: when {
                ToolListCheck.supports(list.stdout, list.exitCode, scriptId) -> null
                // -1: the check itself could not run or timed out — a connection/launch problem, not an old script
                list.exitCode == -1 -> ToolVerdict.Failure(ToolFailure.NOT_RUN)
                else -> ToolVerdict.Failure(ToolFailure.SCRIPT_OUTDATED)
            }
    }

    private fun readToolResult(ocaDir: java.io.File): ToolResultFile? {
        val file = java.io.File(ocaDir, "tools-result.conf")
        if (!file.isFile || file.length() > ToolResultParser.MAX_CHARS) return null
        return ToolResultParser.parse(file.readText())
    }

    /** Ask the running install to stop. The script only stops after its current step (SIGTERM). */
    @JavascriptInterface
    fun cancelToolInstall() {
        if (ToolInstallGuard.requestCancel(::sendCancelSignal)) emitToolState()
    }

    private fun sendCancelSignal(): Boolean =
        try {
            ToolSignal.sendTerm(
                java.io.File(bootstrapManager.homeDir, ".openclaw-android/.tools.lock/pid"),
                ToolInstallGuard.runToken,
            )
        } catch (e: SecurityException) {
            AppLogger.w(TAG, "Cancel signal was refused", e)
            false // retried by the pump; the page keeps saying "cancel requested" until the run ends
        }

    /** Where the tool install is — for a page created while one runs, or after it ended. */
    @JavascriptInterface
    fun getToolInstallState(): String = gson.toJson(toolStateEvent(ToolInstallGuard.snapshot()))

    private fun toolStateEvent(now: ToolInstallGuard.State): Map<String, Any?> =
        mapOf(
            "target" to now.tool,
            "phase" to now.phase,
            "progress" to now.progress,
            "message" to now.message,
            "cancelRequested" to now.cancelRequested,
            "longRunning" to now.longRunning,
            "reason" to now.reason,
        )

    private fun emitToolState() = eventBridge.emit(TOOL_EVENT, toolStateEvent(ToolInstallGuard.snapshot()))

    /**
     * The install did not start. Another tool install is running: tell the page what is running
     * instead of staying silent (as before). The run lease is held by a managed run (an update):
     * this install says BUSY. [leased]: the lease was taken for this call and must be given back.
     */
    private fun refuseToolInstall(
        id: String,
        leased: Boolean,
    ) {
        if (leased) RunLease.release(RunLease.TOOLS)
        AppLogger.w(TAG, "installTool ignored: ${RunLease.owner() ?: "another tool install"} is running")
        if (ToolInstallGuard.isRunning()) return emitToolState()
        eventBridge.emit(
            TOOL_EVENT,
            mapOf(
                "target" to id,
                "phase" to ToolInstallGuard.FAILED,
                "progress" to PROGRESS_START,
                "message" to "",
                "cancelRequested" to false,
                "longRunning" to false,
                "reason" to ToolFailure.BUSY.name,
            ),
        )
    }

    /** No work is done: a plain, honest "not from the app" for a tool or action with no safe path. */
    private fun emitNotSupported(
        id: String,
        errorKind: String,
        message: String,
    ) {
        eventBridge.emit(
            TOOL_EVENT,
            mapOf(
                "target" to id,
                "phase" to "unsupported",
                "progress" to PROGRESS_START,
                "errorKind" to errorKind,
                "message" to message,
            ),
        )
    }

    /** Removing needs a record of what was installed (the script keeps none yet): not offered. */
    @JavascriptInterface
    fun uninstallTool(id: String) {
        if (id !in BridgeGuard.toolIds) return
        emitNotSupported(id, "UNINSTALL_UNSUPPORTED", "Removing $id is not supported from the app yet")
    }

    // ═══════════════════════════════════════════
    // Managed runs domain (`oa --update`, the first install's `post-setup.sh` as a child process) and the gateway
    // ═══════════════════════════════════════════

    private val runs by lazy {
        ManagedRunner(
            homeDir = bootstrapManager.homeDir,
            environment = { EnvironmentBuilder.build(activity) },
            outcomes = RunOutcomeStore(java.io.File(activity.filesDir, "last-run.conf")),
            // The app's own process is a root too: after the Activity is recreated the terminal
            // shells (its children) are still below it, though the new session manager lists none.
            // Other apps' processes have another uid and are not visible in /proc.
            gateway = GatewayControl(sessionPids = { sessionManager.sessionPids() + android.os.Process.myPid() }),
            emit = { type, data -> eventBridge.emit(type, data) },
            refreshSetupScript = { bootstrapManager.refreshPostSetupScript() },
        )
    }

    /**
     * Start a managed run by kind ([BridgeGuard.runKinds]; the command behind it is native's). Its
     * progress and end arrive as `run_progress` events. With [stopGateway] a running gateway the app
     * started is stopped first; otherwise a running gateway refuses the run (GATEWAY_RUNNING). A SETUP
     * run has no gateway check ([stopGateway] is ignored); it is refused NOT_INSTALLED when the script
     * cannot be run by the app (`getSetupResult().managed` is false) — the page uses the terminal then.
     */
    @Suppress("TooGenericExceptionCaught") // a runner that cannot be built must not leave the lease held
    @JavascriptInterface
    fun startRun(
        kind: String,
        stopGateway: Boolean,
    ) {
        // The runner is built before the lease is taken: if building it fails, nothing is left held
        val runner =
            try {
                runs
            } catch (e: Exception) {
                AppLogger.w(TAG, "startRun: the managed runner could not be created", e)
                // Nothing was taken or started: say so, so the page does not wait for a run that never begins
                eventBridge.emit(
                    ManagedRunner.RUN_EVENT,
                    ManagedRunner.stateEvent(
                        ManagedRunGuard.State(
                            null,
                            ManagedRunner.REFUSED,
                            0,
                            0,
                            "",
                            0L,
                            reason = UpdateReason.UNKNOWN.name,
                        ),
                    ),
                )
                return
            }
        if (kind !in BridgeGuard.runKinds) return runner.emitRefused(null, UpdateReason.UNKNOWN)
        // Taken here, on the caller thread, before anything is launched; ManagedRunner.run releases it
        if (!RunLease.tryAcquire(kind)) {
            AppLogger.w(TAG, "startRun ignored: ${RunLease.owner()} is running")
            return runner.emitBusy(kind)
        }
        launchWithErrorHandling(
            errorEventType = ManagedRunner.RUN_EVENT,
            errorContext =
                mapOf(
                    "kind" to kind,
                    "phase" to ManagedRunGuard.FAILED,
                    "reason" to UpdateReason.UNKNOWN.name,
                ),
        ) {
            runner.run(kind, stopGateway)
        }
    }

    /** Ask the managed run to stop; refused once it is past its cancelable stages (an update after `[2/5]`). */
    @JavascriptInterface
    fun cancelRun() = runs.cancel()

    /** Where the managed run is, in the shape of `run_progress` — for a page created while one runs or after. */
    @JavascriptInterface
    fun getRunState(): String = gson.toJson(ManagedRunner.stateEvent(ManagedRunGuard.snapshot()))

    /** How the last run of each kind ended, kept across app restarts (`last-run.conf`). */
    @JavascriptInterface
    fun getLastRun(): String = gson.toJson(runs.lastRuns())

    /** `{running, ours, pids}` — `running` when a gateway process exists, `ours` when one is below the app. */
    @JavascriptInterface
    fun getGatewayStatus(): String = gson.toJson(runs.gatewayStatus())

    /**
     * Stop the gateway the app's terminal started (never one started elsewhere, never by name).
     * [force] = SIGKILL, only after the page asked a second time. Answers on `gateway_state`.
     */
    @JavascriptInterface
    fun stopGateway(force: Boolean) {
        launchWithErrorHandling(
            errorEventType = ManagedRunner.GATEWAY_EVENT,
            errorContext = mapOf("phase" to ManagedRunner.GATEWAY_DONE),
        ) {
            runs.stopGatewayAndReport(force)
        }
    }

    // ═══════════════════════════════════════════
    // Commands domain
    // ═══════════════════════════════════════════

    /**
     * Run one of the fixed version probes by ID (see [BridgeGuard.versionCommands]) without
     * blocking the WebView: the result comes back as one `command_result` event carrying
     * [callbackId]. A synchronous call here froze the page's JS for the whole run (a hung probe
     * stalled it for seconds). At most [MAX_CONCURRENT_PROBES] run at once.
     */
    @JavascriptInterface
    fun runProbeAsync(
        callbackId: String,
        commandId: String,
    ) {
        val command = BridgeGuard.versionCommands[commandId]
        if (command == null) {
            emitProbeResult(callbackId, commandId, CommandRunner.CommandResult(-1, "", "Command is not allowed"))
            return
        }
        launchWithErrorHandling(
            errorEventType = "command_result",
            errorContext = mapOf("callbackId" to callbackId, "commandId" to commandId, "exitCode" to -1),
        ) {
            val result =
                ProbeLimiter.semaphore.withPermit {
                    CommandRunner.runExecutable(
                        command.executable,
                        command.args,
                        probeEnvironment(),
                        bootstrapManager.homeDir,
                    )
                }
            val shown =
                if (command.firstLineOnly) {
                    result.copy(
                        stdout =
                            result.stdout
                                .lineSequence()
                                .firstOrNull()
                                .orEmpty(),
                    )
                } else {
                    result
                }
            emitProbeResult(callbackId, commandId, shown)
        }
    }

    private fun emitProbeResult(
        callbackId: String,
        commandId: String,
        result: CommandRunner.CommandResult,
    ) {
        eventBridge.emit(
            "command_result",
            mapOf(
                "callbackId" to callbackId,
                "commandId" to commandId,
                "exitCode" to result.exitCode,
                "stdout" to result.stdout,
                "stderr" to result.stderr,
            ),
        )
    }

    /**
     * The app environment plus the OpenClaw wrapper directory (where the `node` wrapper lives),
     * appended last so it can never shadow a command the app already resolves.
     */
    private fun probeEnvironment(): Map<String, String> {
        val env = EnvironmentBuilder.build(activity)
        val wrappers = "${bootstrapManager.homeDir.absolutePath}/.openclaw-android/bin"
        return env + ("PATH" to "${env["PATH"].orEmpty()}:$wrappers")
    }

    // ═══════════════════════════════════════════
    // Updates domain
    // ═══════════════════════════════════════════

    /** Check GitHub for a newer app release without blocking the WebView; answers with an `apk_update_info` event. */
    @JavascriptInterface
    fun getApkUpdateInfoAsync(callbackId: String) {
        launchWithErrorHandling(
            errorEventType = "apk_update_info",
            errorContext = mapOf("callbackId" to callbackId),
        ) {
            eventBridge.emit("apk_update_info", mapOf("callbackId" to callbackId) + fetchApkUpdateInfo())
        }
    }

    private fun fetchApkUpdateInfo(): Map<String, Any?> =
        try {
            val url = java.net.URL("https://api.github.com/repos/AidanPark/openclaw-android/releases/latest")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = API_TIMEOUT_MS
            conn.readTimeout = API_TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val release = gson.fromJson(body, Map::class.java) as? Map<*, *>
            val tagName = release?.get("tagName") as? String ?: release?.get("tag_name") as? String
            if (tagName == null) {
                mapOf("error" to "no tag")
            } else {
                val latestVersion = tagName.trimStart('v')
                val currentVersion =
                    activity.packageManager
                        .getPackageInfo(activity.packageName, 0)
                        .versionName ?: "0.0.0"
                mapOf(
                    "currentVersion" to currentVersion,
                    "latestVersion" to latestVersion,
                    "updateAvailable" to (compareVersions(latestVersion, currentVersion) > 0),
                )
            }
        } catch (e: Exception) {
            mapOf("error" to e.message)
        }

    // ═══════════════════════════════════════════
    // System domain
    // ═══════════════════════════════════════════

    @JavascriptInterface
    fun getAppInfo(): String {
        val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return gson.toJson(
            mapOf(
                "versionName" to (pInfo.versionName ?: "unknown"),
                "versionCode" to pInfo.versionCode,
                "packageName" to activity.packageName,
            ),
        )
    }

    @JavascriptInterface
    fun getBatteryOptimizationStatus(): String {
        val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        return gson.toJson(
            mapOf("isIgnoring" to pm.isIgnoringBatteryOptimizations(activity.packageName)),
        )
    }

    @JavascriptInterface
    fun requestBatteryOptimizationExclusion() {
        activity.runOnUiThread {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:${activity.packageName}")
            startActivitySafely(intent)
        }
    }

    @JavascriptInterface
    fun openSystemSettings(page: String) {
        activity.runOnUiThread {
            val intent =
                when (page) {
                    "battery" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                    "app_info" ->
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${activity.packageName}")
                        }
                    else -> Intent(Settings.ACTION_SETTINGS)
                }
            startActivitySafely(intent)
        }
    }

    /** Runs on the UI thread, outside WebView's exception guard: a missing handler must not crash the app. */
    private fun startActivitySafely(intent: Intent) {
        try {
            activity.startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            AppLogger.w(TAG, "No activity can handle ${intent.action}", e)
        }
    }

    /** Copy one of the app's fixed texts (see [BridgeGuard.clipboardTexts]) — the WebView names an ID, not the text. */
    @JavascriptInterface
    fun copyText(textId: String) {
        val text = BridgeGuard.clipboardTexts[textId] ?: return
        activity.runOnUiThread {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("OpenClaw", text))
        }
    }

    @JavascriptInterface
    fun getStorageInfo(): String {
        val filesDir = activity.filesDir
        val totalSpace = filesDir.totalSpace
        val freeSpace = filesDir.freeSpace
        val bootstrapSize = directorySize(bootstrapManager.prefixDir)
        val wwwSize = directorySize(bootstrapManager.wwwDir)

        return gson.toJson(
            mapOf(
                "totalBytes" to totalSpace,
                "freeBytes" to freeSpace,
                "bootstrapBytes" to bootstrapSize,
                "wwwBytes" to wwwSize,
            ),
        )
    }

    /**
     * Total size of a tree without following directory symlinks (a link loop would never end).
     * Only directories are checked — a per-file canonical lookup over tens of thousands of files
     * would stall this synchronous call.
     */
    private fun directorySize(root: java.io.File): Long =
        root
            .walkTopDown()
            .onEnter { dir -> dir == root || !isSymlink(dir) }
            .filter { it.isFile }
            .sumOf { it.length() }

    /** One lstat call (no path resolution); false when the entry cannot be inspected. */
    private fun isSymlink(file: java.io.File): Boolean =
        try {
            android.system.OsConstants.S_ISLNK(
                android.system.Os
                    .lstat(file.path)
                    .st_mode,
            )
        } catch (_: android.system.ErrnoException) {
            false
        }

    @JavascriptInterface
    fun clearCache() {
        // Never follows a link out of the cache dir (into files/, usr or home)
        SafeTree.deleteNoFollow(activity.cacheDir)
        activity.cacheDir.mkdirs()
    }

    @JavascriptInterface
    fun openUrl(url: String) {
        if (!BridgeGuard.isSafeExternalUrl(url)) {
            AppLogger.w(TAG, "Blocked openUrl for non-https URL")
            return
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            AppLogger.w(TAG, "No browser to open the URL", e)
        }
    }

    /** Returns positive if a > b, negative if a < b, 0 if equal (semver: major.minor.patch) */
    private fun compareVersions(
        a: String,
        b: String,
    ): Int {
        val aParts = a.split(".").map { it.toIntOrNull() ?: 0 }
        val bParts = b.split(".").map { it.toIntOrNull() ?: 0 }
        val len = maxOf(aParts.size, bParts.size)
        for (i in 0 until len) {
            val diff = (aParts.getOrElse(i) { 0 }) - (bParts.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }
}
