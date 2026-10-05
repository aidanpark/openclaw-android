package com.openclaw.android

import android.content.Context
import com.google.gson.Gson
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File

/**
 * A code-server installed from the terminal (`oa --install`): the app lists it, checks that it runs
 * like any other tool (so a broken copy reads `failed`, not "installed"), and still never installs
 * it itself. Real CommandRunner against a fake `code-server` program in a temp `$PREFIX/bin`.
 */
internal class CodeServerTerminalCheckTest : JsBridgeToolInstallFixture() {
    private val prefixBin get() = File(prefix, "bin")
    private val probeLog get() = File(home, "probe-calls.log")

    @BeforeEach
    fun appPath() {
        every { EnvironmentBuilder.build(any<Context>()) } returns
            mapOf(
                "PATH" to "${prefixBin.path}:/bin:/usr/bin",
                "HOME" to home.absolutePath,
                "PREFIX" to prefix.absolutePath,
            )
    }

    private fun terminalInstalledCodeServer(exit: Int) {
        prefixBin.mkdirs()
        val f = File(prefixBin, "code-server")
        f.writeText("#!/bin/sh\necho \"code-server ${'$'}*\" >> \"${'$'}HOME/probe-calls.log\"\nexit $exit\n")
        assertTrue(f.setExecutable(true))
    }

    private fun installedIds(bridge: JsBridge): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val list = Gson().fromJson(bridge.getInstalledTools(), List::class.java) as List<Map<String, Any?>>
        return list.associate { it["id"] as String to it["broken"] }
    }

    private fun checkStatuses(
        bridge: JsBridge,
        web: RecordingWebView,
    ): Map<String, Any?> {
        bridge.checkInstalledToolsAsync("cs")
        assertTrue(
            TestWait.until(END_WAIT_MS) { web.events("tools_check").any { it.data["done"] == true } },
            web.scripts.toString(),
        )
        return web
            .events("tools_check")
            .filter { it.data["done"] != true }
            .associate { it.data["target"] as String to it.data["status"] }
    }

    @ParameterizedTest(name = "exit {0} reads {1}")
    @CsvSource("0, ok", "1, failed", "127, failed")
    fun `a terminal-installed code-server is listed and its run check decides ok or failed`(
        exit: Int,
        status: String,
    ) {
        terminalInstalledCodeServer(exit)
        val (bridge, web) = page()
        assertEquals(mapOf("code-server" to false), installedIds(bridge))
        assertEquals(mapOf("code-server" to status), checkStatuses(bridge, web))
        assertEquals(listOf("code-server --version"), probeLog.readLines())
    }

    @ParameterizedTest(name = "exit {0}")
    @CsvSource("0", "1")
    fun `the app still does not install a terminal-installed code-server`(exit: Int) {
        terminalInstalledCodeServer(exit)
        marker()
        // A script that would succeed: if installTool reached it, calls.log would show it
        fakeScript("printf 'schema=1\\nrun=1\\ncode-server=ok\\nexit=0\\n' > \"${'$'}R\"; exit 0")
        val (bridge, web) = page()
        bridge.installTool("code-server")
        Thread.sleep(NEGATIVE_WAIT_MS)
        val e = web.events().single()
        assertEquals("TERMINAL_ONLY", e.data["errorKind"])
        assertEquals("unsupported", e.data["phase"])
        assertEquals(emptyList<String>(), calls(), "installTool ran the script for code-server")
        assertEquals("reset", ToolInstallGuard.snapshot().tool)
    }
}
