package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

/** [ToolDetection.isInstalled] against real files in a temporary prefix and home. */
class ToolDetectionTest {
    @TempDir
    lateinit var root: File

    private lateinit var prefix: File
    private lateinit var home: File

    @BeforeEach
    fun setUp() {
        prefix = File(root, "usr").apply { mkdirs() }
        home = File(root, "home").apply { mkdirs() }
    }

    private fun touch(
        base: File,
        path: String,
    ) = File(base, path).apply {
        parentFile.mkdirs()
        writeText("x")
    }

    private fun installed(id: String) = ToolDetection.isInstalled(id, prefix, home)

    @Test
    fun `nothing is installed in an empty prefix and home`() {
        BridgeGuard.toolIds.forEach { assertFalse(installed(it), it) }
    }

    @ParameterizedTest
    @CsvSource("tmux, tmux", "ttyd, ttyd", "dufs, dufs", "android-tools, adb", "openssh-server, sshd")
    fun `package tools are found by their binary in prefix bin`(
        id: String,
        binary: String,
    ) {
        assertFalse(installed(id))
        touch(prefix, "bin/$binary")
        assertTrue(installed(id))
    }

    @ParameterizedTest
    @CsvSource("tmux, tmux", "android-tools, adb", "openssh-server, sshd")
    fun `package tools are not found in the home launcher directories`(
        id: String,
        binary: String,
    ) {
        touch(home, ".local/bin/$binary")
        touch(home, ".openclaw-android/node/bin/$binary")
        assertFalse(installed(id))
    }

    @Test
    fun `android-tools is not detected by a binary named after its id`() {
        touch(prefix, "bin/android-tools")
        assertFalse(installed("android-tools"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["chromium-browser", "chromium"])
    fun `chromium is found by either browser binary`(binary: String) {
        touch(prefix, "bin/$binary")
        assertTrue(installed("chromium"))
    }

    @Test
    fun `playwright is found only by the playwright-core package json file`() {
        File(prefix, "lib/node_modules/playwright-core").mkdirs()
        assertFalse(installed("playwright"), "an empty package directory is not an install")
        File(prefix, "lib/node_modules/playwright-core/package.json").mkdirs()
        assertFalse(installed("playwright"), "a directory named package.json is not an install")
    }

    @Test
    fun `playwright with its package json file is installed`() {
        touch(prefix, "lib/node_modules/playwright-core/package.json")
        assertTrue(installed("playwright"))
        assertFalse(installed("chromium"), "the library does not mean the browser is there")
    }

    @Test
    fun `playwright is not detected by a launcher in bin`() {
        touch(prefix, "bin/playwright")
        assertFalse(installed("playwright"))
    }

    @ParameterizedTest
    @CsvSource(
        "code-server, code-server",
        "claude-code, claude",
        "gemini-cli, gemini",
        "codex-cli, codex",
        "opencode, opencode",
    )
    fun `npm tools are found by their launcher in any of the three bin directories`(
        id: String,
        launcher: String,
    ) {
        val dirs = listOf(prefix to "bin", home to ".local/bin", home to ".openclaw-android/node/bin")
        for ((base, dir) in dirs) {
            val f = touch(base, "$dir/$launcher")
            assertTrue(installed(id), "$id via $dir")
            assertTrue(f.delete())
            assertFalse(installed(id), "$id still found after removing $dir/$launcher")
        }
    }

    @Test
    fun `an npm tool is not detected by a launcher named after its id when that differs`() {
        touch(prefix, "bin/claude-code")
        touch(prefix, "bin/gemini-cli")
        touch(prefix, "bin/codex-cli")
        assertFalse(installed("claude-code"))
        assertFalse(installed("gemini-cli"))
        assertFalse(installed("codex-cli"))
    }

    @Test
    fun `npm launchers in an unrelated home directory do not count`() {
        touch(home, "bin/claude")
        touch(home, ".openclaw-android/bin/claude")
        assertFalse(installed("claude-code"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "sh", "bash", "openssh", "chromium-browser", "../bin/tmux", "tmux "])
    fun `unknown ids are never installed even if a file by that name exists`(id: String) {
        touch(prefix, "bin/sh")
        touch(prefix, "bin/bash")
        touch(prefix, "bin/tmux")
        touch(prefix, "bin/chromium-browser")
        assertFalse(installed(id), "'$id'")
    }

    @Test
    fun `every app tool id has a detection rule`() {
        // Install everything a rule could look for, then every app tool must be seen
        listOf(
            "tmux",
            "ttyd",
            "dufs",
            "adb",
            "sshd",
            "chromium",
            "code-server",
            "claude",
            "gemini",
            "codex",
            "opencode",
        ).forEach { touch(prefix, "bin/$it") }
        touch(prefix, "lib/node_modules/playwright-core/package.json")
        assertEquals(BridgeGuard.toolIds, BridgeGuard.toolIds.filter { installed(it) }.toSet())
    }
}
