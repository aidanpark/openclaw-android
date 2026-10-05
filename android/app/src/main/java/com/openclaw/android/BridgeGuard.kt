package com.openclaw.android

import com.google.gson.Gson
import java.net.URI

/**
 * Allow-lists for everything the WebView may ask the native side to run or open.
 * The WebView is untrusted input: it names an ID, native code owns the command behind it.
 */
internal object BridgeGuard {
    data class Executable(
        val executable: String,
        val args: List<String> = emptyList(),
        val firstLineOnly: Boolean = false,
    )

    const val DEFAULT_PLATFORM = "openclaw"

    /** Read-only version probes behind `runCommand` / `runCommandAsync`. */
    val versionCommands: Map<String, Executable> =
        mapOf(
            "nodeVersion" to Executable("node", listOf("-v")),
            "gitVersion" to Executable("git", listOf("--version")),
            "openclawVersion" to Executable("openclaw", listOf("--version")),
            "oaVersion" to Executable("oa", listOf("--version"), firstLineOnly = true),
        )

    /**
     * Read-only "does it run" check per installed tool (`--version` style). The file being on disk
     * proves nothing: a launcher can stay after a broken install, or break later outside the app.
     * `playwright` is a library (no launcher) and most terminal-only tools are not checked.
     * `code-server` is terminal-only (its npm install fails here) but a copy installed from the
     * terminal is checked like any other, so a broken one never reads as "installed".
     */
    val toolVerifyCommands: Map<String, Executable> =
        mapOf(
            "tmux" to Executable("tmux", listOf("-V")),
            "ttyd" to Executable("ttyd", listOf("--version")),
            "dufs" to Executable("dufs", listOf("--version")),
            "android-tools" to Executable("adb", listOf("--version")),
            "code-server" to Executable("code-server", listOf("--version")),
            "claude-code" to Executable("claude", listOf("--version")),
            "gemini-cli" to Executable("gemini", listOf("--version")),
            "codex-cli" to Executable("codex", listOf("--version")),
        )

    /** Commands the dashboard may type into the terminal (no newline — the user presses Enter). */
    val terminalCommands: Map<String, String> =
        mapOf(
            "openclawGateway" to "openclaw gateway",
            "openclawStatus" to "openclaw status",
            "openclawOnboard" to "openclaw onboard",
            "openclawLogs" to "openclaw logs --follow",
            "oaUpdate" to "oa --update",
            "oaInstall" to "oa --install",
        )

    /** Known platform ids (value: the npm package the install scripts manage). */
    val platformPackages: Map<String, String> = mapOf(DEFAULT_PLATFORM to "openclaw")

    /** Tool ids shown in Settings → Tools. Keep in sync with `android/www/src/screens/SettingsTools.tsx`. */
    val toolIds: Set<String> =
        setOf(
            "tmux",
            "ttyd",
            "dufs",
            "openssh-server",
            "android-tools",
            "chromium",
            "playwright",
            "code-server",
            "claude-code",
            "gemini-cli",
            "codex-cli",
            "opencode",
        )

    /**
     * Tools the app cannot install itself yet: they have no signed install chain, and the
     * apt route is dead (dpkg's hardcoded paths — it reports success without installing). The
     * UI shows them as "can't be installed from the app yet" instead of a false "installed".
     */
    val terminalOnlyTools: Set<String> = setOf("openssh-server", "opencode", "chromium", "code-server")

    /**
     * App tool id → the id `post-setup.sh --tools-only` takes (its `OA_TOOL_IDS`). Every tool the
     * app can install is here; anything else is [terminalOnlyTools] or unknown.
     */
    val toolInstallIds: Map<String, String> =
        listOf(
            "tmux",
            "ttyd",
            "dufs",
            "android-tools",
            "playwright",
            "claude-code",
            "gemini-cli",
            "codex-cli",
        ).associateWith { it }

    /**
     * Keys the setup wizard may save: exactly the variables `post-setup.sh` reads from
     * `tool-selections.conf` (INSTALL_TMUX … INSTALL_CODEX_CLI). The script ignores anything else
     * with a warning, so the app refuses it up front. `playwright` has no UI toggle but the
     * script recognizes it. Keep in sync with the `[7/7] Optional Tools` block in post-setup.sh.
     */
    val toolSelectionIds: Set<String> =
        setOf("tmux", "ttyd", "dufs", "code-server", "playwright", "claude-code", "gemini-cli", "codex-cli")

    /** Fixed texts the UI may copy to the clipboard, by ID. */
    val clipboardTexts: Map<String, String> =
        mapOf(
            "ppkCommand" to
                "adb shell device_config set_sync_disabled_for_tests activity_manager/max_phantom_processes 2147483647",
        )

    fun isPlatform(id: String): Boolean = platformPackages.containsKey(id)

    /** A persisted platform marker is only trusted if it names a known platform. */
    fun sanitizePlatformId(saved: String?): String = saved?.trim()?.takeIf { isPlatform(it) } ?: DEFAULT_PLATFORM

    /**
     * Parse the setup wizard's tool selections. Keys must be in [toolSelectionIds] and values booleans:
     * the result is written into a file `post-setup.sh` reads and acts on, so nothing else may reach it.
     * Returns null when the input is not exactly that.
     */
    fun parseToolSelections(json: String): Map<String, Boolean>? {
        val parsed =
            try {
                Gson().fromJson(json, Map::class.java)
            } catch (_: Exception) {
                null
            } ?: return null
        val result = LinkedHashMap<String, Boolean>()
        for ((key, value) in parsed) {
            if (key !is String || key !in toolSelectionIds || value !is Boolean) return null
            result[key] = value
        }
        return result
    }

    /** Only plain https links may be handed to the system (no file:, content:, intent:, custom schemes). */
    fun isSafeExternalUrl(url: String): Boolean =
        try {
            val uri = URI(url)
            uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrEmpty()
        } catch (_: Exception) {
            false
        }
}
