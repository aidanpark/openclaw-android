package com.openclaw.android

import android.content.Context
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * installTool end to end against the fake script (see [JsBridgeToolInstallFixture]): order of the
 * steps, the verdict for every result, one install at a time, the list check and output lines.
 */
internal class JsBridgeToolInstallTest : JsBridgeToolInstallFixture() {
    // ── order: marker first ─────────────────────────────────────────────────

    @Test
    fun `without the post-setup marker nothing is refreshed or run and the install fails as SETUP_INCOMPLETE`() {
        fakeScript("echo would-install; exit 0")
        mockkObject(CommandRunner)
        try {
            val (bridge, web) = page()
            bridge.installTool("tmux")
            assertFailed(finalEvent(web), ToolFailure.SETUP_INCOMPLETE)
            verify(exactly = 0) { bootstraps.single().refreshPostSetupScript() }
            verify(exactly = 0) { CommandRunner.runExecutable(any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { CommandRunner.streamLong(any(), any(), any(), any(), any()) }
            assertEquals(emptyList<String>(), calls(), "the script ran without the marker")
            assertEquals("failed", bridge.toolState()["phase"])
            assertTrue(ToolInstallGuard.tryStart("next", 0L), "the guard was not released")
            ToolInstallGuard.finish(ToolVerdict.Success)
        } finally {
            unmockkObject(CommandRunner)
        }
    }

    // ── end to end against the fake script ──────────────────────────────────

    @Test
    fun `a fresh ok result with the binary on disk is done, after refresh then list then the real call`() {
        marker()
        fakeScript(
            """
            |mkdir -p "${'$'}PREFIX/bin" && : > "${'$'}PREFIX/bin/tmux"
            |echo "Installing tmux"
            |printf 'schema=1\nrun=%s\ntmux=ok\nexit=0\n' "${'$'}(date +%s)" > "${'$'}R"
            |echo "TOOL_RESULT tmux ok"
            |exit 0
            """.trimMargin(),
        )
        val (bridge, web) = page()
        bridge.installTool("tmux")
        val end = finalEvent(web)

        assertEquals("done", end.data["phase"], web.scripts.toString())
        assertEquals(1.0, (end.data["progress"] as Number).toDouble())
        assertEquals(listOf("--tools-only --list", "--tools-only tmux"), calls())
        verify(exactly = 1) { bootstraps.single().refreshPostSetupScript() }
        val messages = web.events("tool_progress").map { it.data["message"] }
        assertTrue("TOOL_RESULT tmux ok" in messages, "streamed lines did not reach the page: $messages")
        assertTrue(web.events("tool_progress").first().data["phase"] == "running")
        assertTrue(bridge.isToolInstalled("tmux").contains("true"))
    }

    @Test
    fun `an older script that answers already complete is SCRIPT_OUTDATED and the real call is never made`() {
        marker()
        // An older copy ignores every argument: --list gets the same answer as the real call
        val old = "echo 'Post-setup already completed.'; exit 0"
        fakeScript(body = old, list = old)
        val (bridge, web) = page()
        bridge.installTool("tmux")
        assertFailed(finalEvent(web), ToolFailure.SCRIPT_OUTDATED)
        assertEquals(listOf("--tools-only --list"), calls())
    }

    @Test
    fun `a list call that exits non zero is SCRIPT_OUTDATED`() {
        marker()
        fakeScript(body = "exit 0", list = "echo tmux; exit 3")
        val (bridge, web) = page()
        bridge.installTool("tmux")
        assertFailed(finalEvent(web), ToolFailure.SCRIPT_OUTDATED)
        assertEquals(listOf("--tools-only --list"), calls())
    }

    @Test
    fun `a list that lacks the requested id is SCRIPT_OUTDATED`() {
        marker()
        fakeScript(body = "exit 0", list = "echo ttyd; exit 0")
        val (bridge, web) = page()
        bridge.installTool("tmux")
        assertFailed(finalEvent(web), ToolFailure.SCRIPT_OUTDATED)
        assertEquals(1, calls().size)
    }

    @Test
    fun `a result file left by an earlier run is NOT_RUN even though it says ok and the binary is there`() {
        marker()
        File(prefix, "bin").mkdirs()
        File(prefix, "bin/tmux").writeText("")
        val old = System.currentTimeMillis() / MS - HOUR
        File(oca, "tools-result.conf").writeText("schema=1\nrun=$old\ntmux=ok\nexit=0\n")
        fakeScript("echo 'nothing to do'; exit 0")
        val (bridge, web) = page()
        bridge.installTool("tmux")
        assertFailed(finalEvent(web), ToolFailure.NOT_RUN)
        assertEquals(listOf("--tools-only --list", "--tools-only tmux"), calls())
    }

    @Test
    fun `a result the script writes with a run time before this install started is NOT_RUN`() {
        marker()
        fakeScript(
            """
            |mkdir -p "${'$'}PREFIX/bin" && : > "${'$'}PREFIX/bin/tmux"
            |printf 'schema=1\nrun=%s\ntmux=ok\nexit=0\n' "${'$'}(( ${'$'}(date +%s) - 3600 ))" > "${'$'}R"
            |exit 0
            """.trimMargin(),
        )
        val (bridge, web) = page()
        bridge.installTool("tmux")
        assertFailed(finalEvent(web), ToolFailure.NOT_RUN)
    }

    @Test
    fun `ok in the result file without the binary on disk is FILE_MISMATCH`() {
        marker()
        fakeScript(
            """
            |printf 'schema=1\nrun=%s\ntmux=ok\nexit=0\n' "${'$'}(date +%s)" > "${'$'}R"
            |exit 0
            """.trimMargin(),
        )
        val (bridge, web) = page()
        bridge.installTool("tmux")
        assertFailed(finalEvent(web), ToolFailure.FILE_MISMATCH)
    }

    @Test
    fun `failed install in the result file is INSTALL_FAILED even if the binary exists`() {
        marker()
        File(prefix, "bin").mkdirs()
        File(prefix, "bin/tmux").writeText("")
        fakeScript(
            """
            |printf 'schema=1\nrun=%s\ntmux=failed:install\nexit=1\n' "${'$'}(date +%s)" > "${'$'}R"
            |echo "TOOL_RESULT tmux fail install"
            |exit 1
            """.trimMargin(),
        )
        val (bridge, web) = page()
        bridge.installTool("tmux")
        assertFailed(finalEvent(web), ToolFailure.INSTALL_FAILED)
    }

    @Test
    fun `exit 0 with no result file is NOT_RUN, never success`() {
        marker()
        File(prefix, "bin").mkdirs()
        File(prefix, "bin/tmux").writeText("")
        fakeScript("echo done; exit 0")
        val (bridge, web) = page()
        bridge.installTool("tmux")
        assertFailed(finalEvent(web), ToolFailure.NOT_RUN)
    }

    @Test
    fun `exit 2 with no result file is BUSY`() {
        marker()
        fakeScript("echo 'Another tools run is in progress. Try again when it has finished.' >&2; exit 2")
        val (bridge, web) = page()
        bridge.installTool("tmux")
        assertFailed(finalEvent(web), ToolFailure.BUSY)
    }

    @Test
    fun `exit 2 with a bare error=env line on stderr is ENV`() {
        marker()
        fakeScript("echo 'error=env' >&2; exit 2")
        val (bridge, web) = page()
        bridge.installTool("tmux")
        assertFailed(finalEvent(web), ToolFailure.ENV)
    }

    @Test
    fun `an interrupted run without a cancel request is failed INTERRUPTED`() {
        marker()
        fakeScript(
            """
            |printf 'schema=1\nrun=%s\nerror=interrupted\nexit=143\n' "${'$'}(date +%s)" > "${'$'}R"
            |exit 143
            """.trimMargin(),
        )
        val (bridge, web) = page()
        bridge.installTool("tmux")
        assertFailed(finalEvent(web), ToolFailure.INTERRUPTED)
    }

    // ── one install at a time across bridge instances ───────────────────────

    @Test
    fun `a second bridge's install is refused while one runs and re-emits the current state`() {
        marker()
        blockingScript()
        val (first, firstPage) = page()
        first.installTool("tmux")
        awaitRealCall()

        val (second, secondPage) = page() // the recreated page: events now go here
        EventBridge.detach(firstPage.view)
        firstPage.clear()
        second.installTool("ttyd")

        val reemitted = secondPage.events("tool_progress")
        assertEquals(1, reemitted.size, secondPage.scripts.toString())
        assertEquals(second.toolState(), reemitted.single().data)
        assertEquals("tmux", reemitted.single().data["target"])
        assertEquals("running", reemitted.single().data["phase"])
        assertEquals("working", reemitted.single().data["message"])
        assertTrue(firstPage.scripts.isEmpty())

        releaseFile.writeText("go")
        assertEquals("done", finalEvent(secondPage).data["phase"])
        assertEquals(listOf("--tools-only --list", "--tools-only tmux"), calls(), "ttyd reached the script")
    }

    @Test
    fun `getToolInstallState carries every field the page reads`() {
        marker()
        blockingScript()
        val (bridge, _) = page()
        bridge.installTool("tmux")
        awaitRealCall()
        val running = bridge.toolState()
        assertEquals("tmux", running["target"])
        assertEquals("running", running["phase"])
        assertEquals(0.5, (running["progress"] as Number).toDouble())
        assertEquals("working", running["message"])
        assertEquals(false, running["cancelRequested"])
        assertEquals(false, running["longRunning"])
        // Gson drops null map values: "reason" is absent (= undefined in the page) while running
        assertEquals(setOf("target", "phase", "progress", "message", "cancelRequested", "longRunning"), running.keys)

        releaseFile.writeText("go")
        assertTrue(TestWait.until { !ToolInstallGuard.isRunning() })
        assertEquals("done", bridge.toolState()["phase"])
    }

    @Test
    fun `the state of a failed install includes the reason`() {
        val (bridge, web) = page()
        bridge.installTool("tmux") // no marker
        finalEvent(web)
        val s = bridge.toolState()
        assertEquals(
            setOf("target", "phase", "progress", "message", "cancelRequested", "longRunning", "reason"),
            s.keys,
        )
        assertEquals("SETUP_INCOMPLETE", s["reason"])
    }

    // ── the list check could not run ────────────────────────────────────────

    @Test
    fun `a list call that cannot run at all is NOT_RUN, not SCRIPT_OUTDATED, and the install is not made`() {
        marker()
        fakeScript("exit 0")
        // No bash on this PATH: runExecutable answers exit -1 (could not start; a timeout is the same -1)
        every { EnvironmentBuilder.build(any<Context>()) } returns
            mapOf(
                "PATH" to File(root, "empty-bin").absolutePath,
                "HOME" to home.absolutePath,
                "PREFIX" to prefix.absolutePath,
            )
        mockkObject(CommandRunner)
        try {
            val (bridge, web) = page()
            bridge.installTool("tmux")
            assertFailed(finalEvent(web), ToolFailure.NOT_RUN)
            verify(exactly = 1) { CommandRunner.runExecutable(any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { CommandRunner.streamLong(any(), any(), any(), any(), any()) }
            assertEquals(emptyList<String>(), calls())
        } finally {
            unmockkObject(CommandRunner)
        }
    }

    // ── output lines ────────────────────────────────────────────────────────

    @Test
    fun `ANSI colour codes are removed from the lines the page shows`() {
        marker()
        fakeScript(
            """
            |mkdir -p "${'$'}PREFIX/bin" && : > "${'$'}PREFIX/bin/tmux"
            |printf 'schema=1\nrun=%s\ntmux=ok\nexit=0\n' "${'$'}(date +%s)" > "${'$'}R"
            |printf '\033[1;32m[OK]\033[0m tmux \033[?25linstalled\033[K\n'
            |exit 0
            """.trimMargin(),
        )
        val (bridge, web) = page()
        bridge.installTool("tmux")
        val end = finalEvent(web)
        assertEquals("done", end.data["phase"])
        assertEquals("[OK] tmux installed", end.data["message"])
        val messages = web.events("tool_progress").mapNotNull { it.data["message"] as String? }
        assertTrue(messages.none { it.contains('\u001B') }, messages.toString())
        assertFalse(bridge.getToolInstallState().contains("\\u001b", ignoreCase = true))
    }

    @Test
    fun `a coloured hint line is still recognized after the colour codes are removed`() {
        marker()
        // "[1merror=env" has no word boundary before "error": found only once the code is gone
        fakeScript("printf '\\033[1merror=env\\033[0m\\n' >&2; exit 2")
        val (bridge, web) = page()
        bridge.installTool("tmux")
        assertFailed(finalEvent(web), ToolFailure.ENV)
    }

    @Test
    fun `the lock hint sentence post-setup sh prints is LOCK, not BUSY`() {
        marker()
        fakeScript("echo 'Could not create the tools lock in /h/.openclaw-android (error=lock).' >&2; exit 2")
        val (bridge, web) = page()
        bridge.installTool("tmux")
        assertFailed(finalEvent(web), ToolFailure.LOCK)
    }

    @Test
    fun `thousands of lines are coalesced into few events and the final state carries the last line`() {
        marker()
        fakeScript(
            """
            |mkdir -p "${'$'}PREFIX/bin" && : > "${'$'}PREFIX/bin/tmux"
            |i=0; while [ ${'$'}i -lt $MANY_LINES ]; do echo "line ${'$'}i"; i=${'$'}((i+1)); done
            |printf 'schema=1\nrun=%s\ntmux=ok\nexit=0\n' "${'$'}(date +%s)" > "${'$'}R"
            |echo "last line"
            |exit 0
            """.trimMargin(),
        )
        val (bridge, web) = page()
        bridge.installTool("tmux")
        val end = finalEvent(web)
        assertEquals("done", end.data["phase"])
        assertEquals("last line", end.data["message"], "the final state lost the last line")
        assertEquals("last line", bridge.toolState()["message"])
        val events = web.events("tool_progress")
        assertTrue(events.size < MANY_LINES / 10, "${events.size} events for $MANY_LINES lines")
        assertEquals(end, events.last(), "the final state was not the last event")
    }

    @Test
    fun `a fresh ok result whose exit line is not this process's exit code is NOT_RUN`() {
        marker()
        fakeScript(
            """
            |mkdir -p "${'$'}PREFIX/bin" && : > "${'$'}PREFIX/bin/tmux"
            |printf 'schema=1\nrun=%s\ntmux=ok\nexit=0\n' "${'$'}(date +%s)" > "${'$'}R"
            |exit 1
            """.trimMargin(),
        )
        val (bridge, web) = page()
        bridge.installTool("tmux")
        assertFailed(finalEvent(web), ToolFailure.NOT_RUN)
    }

    // ── what never starts anything ──────────────────────────────────────────

    @Test
    fun `ids that are not app tools start nothing and emit nothing`() {
        marker()
        fakeScript("exit 0")
        val (bridge, web) = page()
        listOf("", "openssh", "bash", "tmux;id", "../tmux", "TMUX", "playwright ").forEach { bridge.installTool(it) }
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertTrue(web.scripts.isEmpty(), web.scripts.toString())
        assertEquals(emptyList<String>(), calls())
        assertFalse(ToolInstallGuard.isRunning())
    }

    @Test
    fun `an app tool with no install chain entry starts nothing`() {
        marker()
        fakeScript("exit 0")
        mockkObject(BridgeGuard)
        try {
            every { BridgeGuard.toolInstallIds } returns mapOf("ttyd" to "ttyd")
            val (bridge, web) = page()
            bridge.installTool("tmux")
            Thread.sleep(NEGATIVE_WAIT_MS)
            assertTrue(web.scripts.isEmpty(), web.scripts.toString())
            assertEquals(emptyList<String>(), calls())
            assertFalse(ToolInstallGuard.isRunning())
            assertEquals("reset", ToolInstallGuard.snapshot().tool, "the guard was taken")
        } finally {
            unmockkObject(BridgeGuard)
        }
    }

    @Test
    fun `the script is called with the mapped script id, and the app id is what is checked on disk`() {
        marker()
        fakeScript(
            """
            |mkdir -p "${'$'}PREFIX/bin" && : > "${'$'}PREFIX/bin/tmux"
            |printf 'schema=1\nrun=%s\nscript-tmux=ok\nexit=0\n' "${'$'}(date +%s)" > "${'$'}R"
            |exit 0
            """.trimMargin(),
            list = "echo script-tmux; exit 0",
        )
        mockkObject(BridgeGuard)
        try {
            every { BridgeGuard.toolInstallIds } returns mapOf("tmux" to "script-tmux")
            val (bridge, web) = page()
            bridge.installTool("tmux")
            assertEquals("done", finalEvent(web).data["phase"], web.scripts.toString())
            assertEquals(listOf("--tools-only --list", "--tools-only script-tmux"), calls())
        } finally {
            unmockkObject(BridgeGuard)
        }
    }

    // ── the run token the cancel looks for ──────────────────────────────────

    @Test
    fun `the install script runs with this run's token in OA_APP_RUN_TOKEN, and the next install uses a new one`() {
        marker()
        fakeScript(
            """
            |mkdir -p "${'$'}PREFIX/bin" && : > "${'$'}PREFIX/bin/tmux"
            |printf 'schema=1\nrun=%s\ntmux=ok\nexit=0\n' "${'$'}(date +%s)" > "${'$'}R"
            |exit 0
            """.trimMargin(),
        )
        val (bridge, web) = page()
        bridge.installTool("tmux")
        assertEquals("done", finalEvent(web).data["phase"], web.scripts.toString())
        val first = ToolInstallGuard.runToken
        assertTrue(first.isNotBlank(), "no run token was set")
        assertEquals(listOf(first), tokensFor("--tools-only tmux"), tokensLog.readText())

        web.clear()
        bridge.installTool("tmux")
        assertEquals("done", finalEvent(web).data["phase"], web.scripts.toString())
        val second = ToolInstallGuard.runToken
        assertTrue(second.isNotBlank(), "no run token was set")
        assertNotEquals(first, second, "the second install reused the first run's token")
        assertEquals(listOf(first, second), tokensFor("--tools-only tmux"), tokensLog.readText())
    }
}
