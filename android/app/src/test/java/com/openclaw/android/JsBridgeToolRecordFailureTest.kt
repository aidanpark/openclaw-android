package com.openclaw.android

import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * The outcome record is a display aid: whatever it throws (a lazy store whose `filesDir` is not
 * there yet, a refused write) must not keep the install guard held, must not change the end the
 * page is told, and must not block the next install. Also: a failed end keeps the script's last
 * output line (ANSI removed, at most 300 characters) as the state's message — the page shows it.
 */
internal class JsBridgeToolRecordFailureTest : JsBridgeToolInstallFixture() {
    private var storeMocked = false

    @AfterEach
    fun unmockStore() {
        if (storeMocked) unmockkConstructor(ToolOutcomeStore::class)
        // A guard left held by a broken finally would fail every later tool test in this JVM (the
        // guard is static): release it here so only this class reports the defect
        if (!TestWait.until(END_WAIT_MS) { !ToolInstallGuard.isRunning() }) {
            ToolInstallGuard.process.get()?.destroyForcibly()
            ToolInstallGuard.finish(ToolVerdict.Failure(ToolFailure.UNKNOWN))
        }
    }

    /** The script leaves the files and reports [outcome] for this run. */
    private fun resultScript(outcome: String) =
        fakeScript(
            """
            |mkdir -p "${'$'}PREFIX/bin" && : > "${'$'}PREFIX/bin/tmux"
            |printf 'schema=1\nrun=%s\ntmux=$outcome\nexit=0\n' "${'$'}(date +%s)" > "${'$'}R"
            |exit 0
            """.trimMargin(),
        )

    /** `getFilesDir()` before the Activity has its base context throws an NPE inside the framework. */
    private fun noFilesDir(): Pair<JsBridge, RecordingWebView> =
        page { every { filesDir } throws NullPointerException("filesDir: no base context") }

    private fun recordThrows(): Pair<JsBridge, RecordingWebView> {
        mockkConstructor(ToolOutcomeStore::class)
        storeMocked = true
        every { anyConstructed<ToolOutcomeStore>().record(any(), any()) } throws SecurityException("write refused")
        return page()
    }

    private fun bridgeFor(failure: String) =
        when (failure) {
            "lazy-npe" -> noFilesDir()
            "record-throws" -> recordThrows()
            else -> error(failure)
        }

    private fun assertEnd(
        bridge: JsBridge,
        web: RecordingWebView,
        phase: String,
        reason: String?,
    ) {
        val end = finalEvent(web)
        assertEquals(phase, end.data["phase"], end.toString())
        assertEquals(reason, end.data["reason"], end.toString())
        assertEquals("tmux", end.data["target"])
        assertFalse(end.data.containsKey("errorKind"), "the end came from the error path: $end")
        assertFalse(ToolInstallGuard.isRunning(), "the guard is still held")
        assertEquals(phase, bridge.toolState()["phase"])
    }

    @ParameterizedTest(name = "{0}, script says {1}")
    @CsvSource(
        "lazy-npe, ok, done, ",
        "lazy-npe, failed:install, failed, INSTALL_FAILED",
        "record-throws, ok, done, ",
        "record-throws, failed:verify, failed, VERIFY_FAILED",
    )
    fun `a record that throws still releases the guard, tells the real end, and lets the next install start`(
        failure: String,
        outcome: String,
        phase: String,
        reason: String?,
    ) {
        marker()
        resultScript(outcome)
        val (bridge, web) = bridgeFor(failure)

        bridge.installTool("tmux")
        assertEnd(bridge, web, phase, reason)
        assertEquals(1, calls().count { it == "--tools-only tmux" }, calls().toString())
        assertFalse(outcomesFile.exists(), "a record was written although the store failed")

        // The same bridge (the store still fails) starts the next install: the guard was released
        web.clear()
        resultScript("ok")
        bridge.installTool("tmux")
        assertEnd(bridge, web, "done", null)
        assertEquals(2, calls().count { it == "--tools-only tmux" }, "the next install never ran: ${calls()}")
    }

    // ── the script's last output line on a failed end ───────────────────────

    @Test
    fun `a failed end keeps the script's last output line as the state message`() {
        marker()
        fakeScript(
            """
            |echo "Installing tmux..."
            |printf 'schema=1\nrun=%s\ntmux=failed:install\nexit=1\n' "${'$'}(date +%s)" > "${'$'}R"
            |printf '\033[31mboom: link error\033[0m\n'
            |exit 1
            """.trimMargin(),
        )
        val (bridge, web) = page()
        bridge.installTool("tmux")
        val end = finalEvent(web)
        assertFailed(end, ToolFailure.INSTALL_FAILED)
        assertEquals("boom: link error", end.data["message"], end.toString())
        val state = bridge.toolState()
        assertEquals("failed", state["phase"])
        assertEquals(ToolFailure.INSTALL_FAILED.name, state["reason"])
        assertEquals("boom: link error", state["message"], "a page made after the end would not see the line")
        // A page made later (a new bridge after the Activity was recreated) reads the same line
        assertEquals("boom: link error", page().first.toolState()["message"])
    }

    @Test
    fun `a long coloured last line is kept as its last 300 visible characters`() {
        marker()
        fakeScript(
            """
            |printf 'schema=1\nrun=%s\ntmux=failed:install\nexit=1\n' "${'$'}(date +%s)" > "${'$'}R"
            |i=0; while [ ${'$'}i -lt $LONG_LINE ]; do printf 'z'; i=${'$'}((i+1)); done
            |printf '\033[1;31mboom: link error\033[0m\033[K\n'
            |exit 1
            """.trimMargin(),
        )
        val (bridge, web) = page()
        bridge.installTool("tmux")
        assertFailed(finalEvent(web), ToolFailure.INSTALL_FAILED)
        val message = bridge.toolState()["message"] as String
        assertEquals(("z".repeat(LONG_LINE) + "boom: link error").takeLast(MAX_LINE), message)
        assertTrue(message.none { it == '\u001B' }, message.takeLast(SHOW))
        assertTrue(message.endsWith("boom: link error"), message.takeLast(SHOW))
    }

    private companion object {
        const val LONG_LINE = 400
        const val MAX_LINE = 300
        const val SHOW = 40
    }
}
