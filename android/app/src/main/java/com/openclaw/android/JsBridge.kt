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
import kotlinx.coroutines.launch

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
        private const val SHELL_INIT_DELAY_MS = 500L
        private const val PLATFORM_LIST_TIMEOUT_MS = 10_000L
        private const val API_TIMEOUT_MS = 5000
        private const val PROGRESS_START = 0f
        private const val PROGRESS_HALF = 0.5f
        private const val PROGRESS_DONE = 1f
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
                // Delay write until after attachSession() initializes the shell process.
                // createSession() posts attachSession() via runOnUiThread; writing before
                // that runs silently drops the data (mShellPid is still 0).
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    session.write("bash $script\n")
                }, SHELL_INIT_DELAY_MS)
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
     * user reviews the command and presses Enter.
     */
    @JavascriptInterface
    fun writeCommandToTerminal(commandId: String) {
        val command = BridgeGuard.terminalCommands[commandId] ?: return
        sessionManager.activeSession?.write(command)
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
        launchWithErrorHandling(
            errorEventType = "setup_progress",
            errorContext = mapOf("progress" to PROGRESS_START),
        ) {
            bootstrapManager.startSetup { progress, message ->
                eventBridge.emit(
                    "setup_progress",
                    mapOf("progress" to progress, "message" to message),
                )
            }
        }
    }

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
    fun getInstalledPlatforms(): String {
        // Check which platforms are installed via npm/filesystem
        val env = EnvironmentBuilder.build(activity)
        val result =
            CommandRunner.runSync(
                "npm list -g --depth=0 --json 2>/dev/null",
                env,
                bootstrapManager.prefixDir,
                timeoutMs = PLATFORM_LIST_TIMEOUT_MS,
            )
        return result.stdout.ifBlank { "[]" }
    }

    /**
     * Platforms are installed and pinned by the install scripts (`oa --update`), whose version pin
     * is the single source of truth — the app does not run npm itself, so it cannot install
     * `@latest` around that pin. Only known platform ids are acknowledged.
     */
    @JavascriptInterface
    fun installPlatform(id: String) {
        if (!BridgeGuard.isPlatform(id)) return
        eventBridge.emit(
            "install_progress",
            mapOf(
                "target" to id,
                "progress" to PROGRESS_START,
                "message" to "Platforms are managed by the install scripts. Run 'oa --update' in the terminal.",
            ),
        )
    }

    @JavascriptInterface
    fun uninstallPlatform(id: String) {
        if (!BridgeGuard.isPlatform(id)) return
        eventBridge.emit(
            "install_progress",
            mapOf(
                "target" to id,
                "progress" to PROGRESS_START,
                "message" to "Platforms are managed by the install scripts.",
            ),
        )
    }

    @JavascriptInterface
    fun switchPlatform(id: String) {
        if (!BridgeGuard.isPlatform(id)) return
        // Write active platform marker
        val markerFile = java.io.File(bootstrapManager.homeDir, ".openclaw-android/.platform")
        markerFile.parentFile?.mkdirs()
        markerFile.writeText(id)
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
        val env = EnvironmentBuilder.build(activity)
        val prefix = bootstrapManager.prefixDir.absolutePath
        val tools = mutableListOf<Map<String, String>>()

        // Termux packages - check binary path
        val pkgChecks =
            mapOf(
                "tmux" to "$prefix/bin/tmux",
                "ttyd" to "$prefix/bin/ttyd",
                "dufs" to "$prefix/bin/dufs",
                "openssh-server" to "$prefix/bin/sshd",
                "android-tools" to "$prefix/bin/adb",
                "code-server" to "$prefix/bin/code-server",
            )
        for ((id, path) in pkgChecks) {
            if (java.io.File(path).exists()) {
                tools.add(mapOf("id" to id, "name" to id, "version" to "installed"))
            }
        }

        // Chromium - check multiple possible paths
        if (java.io.File("$prefix/bin/chromium-browser").exists() || java.io.File("$prefix/bin/chromium").exists()) {
            tools.add(mapOf("id" to "chromium", "name" to "chromium", "version" to "installed"))
        }

        // npm global packages - check binary file in node bin
        for (id in BridgeGuard.npmToolBinaries.keys) {
            if (isNpmToolInstalled(id)) {
                tools.add(mapOf("id" to id, "name" to id, "version" to "installed"))
            }
        }

        return gson.toJson(tools)
    }

    @JavascriptInterface
    fun installTool(id: String) {
        if (id !in BridgeGuard.toolIds) return
        launchWithErrorHandling(
            errorEventType = "install_progress",
            errorContext = mapOf("target" to id),
        ) {
            val env = EnvironmentBuilder.build(activity)
            val prefix = bootstrapManager.prefixDir.absolutePath
            val aptGet =
                "DEBIAN_FRONTEND=noninteractive $prefix/bin/apt-get" +
                    " -y -o Acquire::AllowInsecureRepositories=true" +
                    " -o APT::Get::AllowUnauthenticated=true"
            val cmd =
                when (id) {
                    // Termux packages (apt-get)
                    "tmux", "ttyd", "dufs", "openssh-server", "android-tools" ->
                        "$aptGet install ${if (id == "openssh-server") "openssh" else id}"
                    // Chromium (from x11-repo)
                    "chromium" ->
                        "$aptGet install chromium"
                    // code-server (custom)
                    "code-server" ->
                        "npm install -g code-server"
                    // npm-based AI CLI tools
                    "claude-code" ->
                        "npm install -g @anthropic-ai/claude-code"
                    "gemini-cli" ->
                        "npm install -g @google/gemini-cli"
                    "codex-cli" ->
                        "npm install -g @mmmbuto/codex-cli-termux"
                    // OpenCode (Bun-based) — requires proot + ld.so concatenation
                    "opencode" ->
                        "curl -fsSL https://raw.githubusercontent.com/" +
                            "AidanPark/openclaw-android/main/scripts/install-opencode.sh | bash"
                    else -> return@launchWithErrorHandling
                }
            eventBridge.emit(
                "install_progress",
                mapOf("target" to id, "progress" to PROGRESS_START, "message" to "Installing $id..."),
            )
            CommandRunner.runStreaming(cmd, env, bootstrapManager.homeDir) { output ->
                eventBridge.emit(
                    "install_progress",
                    mapOf("target" to id, "progress" to PROGRESS_HALF, "message" to output),
                )
            }
            eventBridge.emit(
                "install_progress",
                mapOf("target" to id, "progress" to PROGRESS_DONE, "message" to "$id installed"),
            )
        }
    }

    @JavascriptInterface
    fun uninstallTool(id: String) {
        if (id !in BridgeGuard.toolIds) return
        launchWithErrorHandling(
            errorEventType = "install_progress",
            errorContext = mapOf("target" to id),
        ) {
            val env = EnvironmentBuilder.build(activity)
            val cmd =
                when (id) {
                    "tmux", "ttyd", "dufs", "openssh-server", "android-tools", "chromium" -> {
                        val pkg = if (id == "openssh-server") "openssh" else id
                        "${bootstrapManager.prefixDir.absolutePath}/bin/apt-get remove -y $pkg"
                    }
                    "code-server" ->
                        "npm uninstall -g code-server"
                    "claude-code" ->
                        "npm uninstall -g @anthropic-ai/claude-code"
                    "gemini-cli" ->
                        "npm uninstall -g @google/gemini-cli"
                    "codex-cli" ->
                        "npm uninstall -g @mmmbuto/codex-cli-termux"
                    "opencode" ->
                        "rm -f \$PREFIX/bin/opencode" +
                            " \$HOME/.openclaw-android/bin/ld.so.opencode" +
                            " \$PREFIX/tmp/ld.so.opencode" +
                            " && rm -rf \$HOME/.config/opencode"
                    else -> return@launchWithErrorHandling
                }
            CommandRunner.runSync(cmd, env, bootstrapManager.homeDir)
        }
    }

    @JavascriptInterface
    fun isToolInstalled(id: String): String {
        val prefix = bootstrapManager.prefixDir.absolutePath
        val exists =
            when (id) {
                "openssh-server" -> java.io.File("$prefix/bin/sshd").exists()
                "tmux", "ttyd", "dufs", "android-tools" -> {
                    val bin = if (id == "android-tools") "adb" else id
                    java.io.File("$prefix/bin/$bin").exists()
                }
                "chromium" -> {
                    java.io.File("$prefix/bin/chromium-browser").exists() ||
                        java.io.File("$prefix/bin/chromium").exists()
                }
                "code-server" -> java.io.File("$prefix/bin/code-server").exists()
                else -> isNpmToolInstalled(id)
            }
        return gson.toJson(mapOf("installed" to exists))
    }

    /**
     * npm global installs land under the Termux prefix (`$PREFIX/bin`), some tools drop a launcher
     * in `~/.local/bin`, and the node directory is on the PATH too — so look in all of them, the
     * same places `command -v` would. Unknown ids are never "installed".
     */
    private fun isNpmToolInstalled(id: String): Boolean {
        val bin = BridgeGuard.npmToolBinaries[id] ?: return false
        val dirs =
            listOf(
                "${bootstrapManager.prefixDir.absolutePath}/bin",
                "${bootstrapManager.homeDir.absolutePath}/.local/bin",
                "${bootstrapManager.homeDir.absolutePath}/.openclaw-android/node/bin",
            )
        return dirs.any { java.io.File(it, bin).exists() }
    }

    // ═══════════════════════════════════════════
    // Commands domain
    // ═══════════════════════════════════════════

    /** Run one of the fixed version probes by ID (see [BridgeGuard.versionCommands]). */
    @JavascriptInterface
    fun runCommand(commandId: String): String {
        val command = BridgeGuard.versionCommands[commandId] ?: return blockedCommandResult()
        val env = probeEnvironment()
        val result = CommandRunner.runExecutable(command.executable, command.args, env, bootstrapManager.homeDir)
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
        return gson.toJson(shown)
    }

    @JavascriptInterface
    fun runCommandAsync(
        callbackId: String,
        commandId: String,
    ) {
        val command = BridgeGuard.versionCommands[commandId]
        if (command == null) {
            eventBridge.emit(
                "command_output",
                mapOf("callbackId" to callbackId, "data" to "Command is not allowed", "done" to true),
            )
            return
        }
        launchWithErrorHandling(
            errorEventType = "command_output",
            errorContext = mapOf("callbackId" to callbackId, "done" to true),
        ) {
            val env = probeEnvironment()
            CommandRunner.streamExecutable(command.executable, command.args, env, bootstrapManager.homeDir) { output ->
                eventBridge.emit(
                    "command_output",
                    mapOf("callbackId" to callbackId, "data" to output, "done" to false),
                )
            }
            eventBridge.emit(
                "command_output",
                mapOf("callbackId" to callbackId, "data" to "", "done" to true),
            )
        }
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

    private fun blockedCommandResult(): String =
        gson.toJson(CommandRunner.CommandResult(exitCode = -1, stdout = "", stderr = "Command is not allowed"))

    // ═══════════════════════════════════════════
    // Updates domain
    // ═══════════════════════════════════════════

    /**
     * Over-the-air component updates (www / bootstrap) were removed: the update channel no longer
     * exists, www always ships inside the APK, and downloads were not integrity-checked. The app
     * itself updates through APK releases and the scripts through `oa --update`.
     */
    @JavascriptInterface
    fun checkForUpdates(): String = gson.toJson(emptyList<Map<String, String>>())

    @JavascriptInterface
    fun getApkUpdateInfo(): String {
        return try {
            val url = java.net.URL("https://api.github.com/repos/AidanPark/openclaw-android/releases/latest")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = API_TIMEOUT_MS
            conn.readTimeout = API_TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val release = gson.fromJson(body, Map::class.java) as? Map<*, *>
            val tagName =
                release?.get("tagName") as? String
                    ?: release?.get("tag_name") as? String
                    ?: return gson.toJson(mapOf("error" to "no tag"))
            val latestVersion = tagName.trimStart('v')
            val currentVersion =
                activity.packageManager
                    .getPackageInfo(activity.packageName, 0)
                    .versionName ?: "0.0.0"
            gson.toJson(
                mapOf(
                    "currentVersion" to currentVersion,
                    "latestVersion" to latestVersion,
                    "updateAvailable" to (compareVersions(latestVersion, currentVersion) > 0),
                ),
            )
        } catch (e: Exception) {
            gson.toJson(mapOf("error" to e.message))
        }
    }

    @JavascriptInterface
    fun applyUpdate(component: String) {
        eventBridge.emit(
            "install_progress",
            mapOf(
                "target" to component,
                "progress" to PROGRESS_START,
                "message" to "Over-the-air updates are no longer supported. Update the app instead.",
            ),
        )
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

    @JavascriptInterface
    fun copyToClipboard(text: String) {
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
        val bootstrapSize = bootstrapManager.prefixDir.walkTopDown().sumOf { it.length() }
        val wwwSize = bootstrapManager.wwwDir.walkTopDown().sumOf { it.length() }

        return gson.toJson(
            mapOf(
                "totalBytes" to totalSpace,
                "freeBytes" to freeSpace,
                "bootstrapBytes" to bootstrapSize,
                "wwwBytes" to wwwSize,
            ),
        )
    }

    @JavascriptInterface
    fun clearCache() {
        activity.cacheDir.deleteRecursively()
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
