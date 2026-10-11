package com.openclaw.android

import java.io.File
import java.nio.file.Files

/**
 * The host's own commands as one PATH directory for tests whose fake tools are small `/bin/sh`
 * scripts (they need `date`, `sleep`, `kill` …), WITHOUT the programs the app itself looks for.
 *
 * A test PATH of `/bin:/usr/bin` lets a real `tmux` (or `node`, `claude` …) of the machine answer
 * where the test expects "not installed" or "cannot be executed": `findExecutable` skips a file without
 * the execute bit and takes the next PATH entry, so `ubuntu-latest` (which has `/usr/bin/tmux`) read
 * `ok` where a Mac read `failed`.
 */
internal object HostCommands {
    /** Names the app's checks and installs resolve on the PATH (BridgeGuard · ToolDetection). */
    private const val APP_PROGRAMS =
        "tmux ttyd dufs adb sshd code-server claude gemini codex opencode chromium chromium-browser " +
            "node npm npx openclaw oa"

    private val hidden: Set<String> = APP_PROGRAMS.split(' ').toSet()

    private val dir: File by lazy {
        val made = Files.createTempDirectory("host-commands").toFile()
        listOf("/bin", "/usr/bin").forEach { source ->
            File(source).listFiles()?.forEach { program ->
                if (program.name in hidden) return@forEach
                runCatching { Files.createSymbolicLink(File(made, program.name).toPath(), program.toPath()) }
            }
        }
        // Links only: each is removed itself, so nothing is followed into the host's directories
        Runtime.getRuntime().addShutdownHook(
            Thread {
                made.listFiles()?.forEach { Files.deleteIfExists(it.toPath()) }
                made.delete()
            },
        )
        made
    }

    /** The PATH entry to put after the app's own directories. */
    val path: String get() = dir.path
}
