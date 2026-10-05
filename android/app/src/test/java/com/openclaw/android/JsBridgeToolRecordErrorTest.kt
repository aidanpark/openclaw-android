package com.openclaw.android

import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * The outcome record's own `catch (Exception)` does not take an [Error] (a StackOverflowError, an
 * OutOfMemoryError …). The guard is released in the record's own `finally`, so even then the next
 * install can start and the native state keeps the run's real verdict; the end emit sits in a
 * `finally` of its own around that, so the page is still sent the real end state.
 */
internal class JsBridgeToolRecordErrorTest : JsBridgeToolInstallFixture() {
    private class RecordBrokeError : Error("record broke")

    @AfterEach
    fun unmockStore() {
        unmockkConstructor(ToolOutcomeStore::class)
        if (!TestWait.until(END_WAIT_MS) { !ToolInstallGuard.isRunning() }) {
            ToolInstallGuard.process.get()?.destroyForcibly()
            ToolInstallGuard.finish(ToolVerdict.Failure(ToolFailure.UNKNOWN))
        }
    }

    private fun resultScript(outcome: String) =
        fakeScript(
            """
            |mkdir -p "${'$'}PREFIX/bin" && : > "${'$'}PREFIX/bin/tmux"
            |printf 'schema=1\nrun=%s\ntmux=$outcome\nexit=0\n' "${'$'}(date +%s)" > "${'$'}R"
            |exit 0
            """.trimMargin(),
        )

    private fun recordThrows(kind: String) {
        mockkConstructor(ToolOutcomeStore::class)
        val error: Error =
            when (kind) {
                "stack-overflow" -> StackOverflowError("deep")
                "custom-error" -> RecordBrokeError()
                "assertion-error" -> AssertionError("unexpected")
                else -> error(kind)
            }
        every { anyConstructed<ToolOutcomeStore>().record(any(), any()) } throws error
    }

    @ParameterizedTest(name = "{0}, script says {1}")
    @CsvSource(
        "stack-overflow, ok, done, ",
        "stack-overflow, failed:install, failed, INSTALL_FAILED",
        "custom-error, ok, done, ",
        "assertion-error, failed:verify, failed, VERIFY_FAILED",
    )
    fun `an Error thrown by record still releases the guard, keeps the real verdict and lets the next install start`(
        kind: String,
        outcome: String,
        phase: String,
        reason: String?,
    ) {
        marker()
        resultScript(outcome)
        recordThrows(kind)
        val (bridge, web) = page()

        bridge.installTool("tmux")
        assertTrue(TestWait.until(END_WAIT_MS) { !ToolInstallGuard.isRunning() }, "the guard is still held")
        val state = bridge.toolState()
        assertEquals(phase, state["phase"], "the native state lost the run's verdict: $state")
        assertEquals(reason, state["reason"], state.toString())
        assertEquals(phase, page().first.toolState()["phase"], "a page made later reads another end")
        // The page is told that the run is over (some final phase), never left at "running"
        assertTrue(
            TestWait.until { web.events("tool_progress").any { it.data["phase"] in FINAL_PHASES } },
            "the page was never told the run ended: ${web.scripts}",
        )
        assertFalse(outcomesFile.exists(), "a record was written although record threw")

        // The guard was released: the same bridge starts the next install
        web.clear()
        resultScript("ok")
        bridge.installTool("tmux")
        assertTrue(TestWait.until(END_WAIT_MS) { calls().count { it == "--tools-only tmux" } == 2 }, calls().toString())
        assertTrue(TestWait.until(END_WAIT_MS) { !ToolInstallGuard.isRunning() })
        assertEquals("done", bridge.toolState()["phase"])
    }

    /** The final tool_progress events the page got, once the guard is free and an end has arrived. */
    private fun finalEvents(web: RecordingWebView): List<EmittedEvent> {
        assertTrue(TestWait.until(END_WAIT_MS) { !ToolInstallGuard.isRunning() }, "the guard is still held")
        assertTrue(
            TestWait.until { web.events("tool_progress").any { it.data["phase"] in FINAL_PHASES } },
            "the page was never told the run ended: ${web.scripts}",
        )
        // let any event that is still on its way (the coroutine's error handler) arrive
        Thread.sleep(SETTLE_MS)
        return web.events("tool_progress").filter { it.data["phase"] in FINAL_PHASES }
    }

    private fun withoutNulls(m: Map<String, Any?>) = m.filterValues { it != null }

    @ParameterizedTest(name = "{0}, script says {1}")
    @CsvSource(
        "stack-overflow, ok, done, ",
        "stack-overflow, failed:install, failed, INSTALL_FAILED",
        "custom-error, ok, done, ",
        "assertion-error, failed:verify, failed, VERIFY_FAILED",
    )
    fun `an Error thrown by record still sends the page the real end state, the same as getToolInstallState`(
        kind: String,
        outcome: String,
        phase: String,
        reason: String?,
    ) {
        marker()
        resultScript(outcome)
        recordThrows(kind)
        val (bridge, web) = page()

        bridge.installTool("tmux")
        val ends = finalEvents(web)
        val state = withoutNulls(bridge.toolState())
        val real = ends.filter { it.data["phase"] == phase && it.data["reason"] == reason && "errorKind" !in it.data }
        assertEquals(1, real.size, "the page did not get the real end exactly once: $ends")
        assertEquals(state, withoutNulls(real.single().data), "the end the page got differs from getToolInstallState")
        assertEquals("tmux", real.single().data["target"])
    }

    /**
     * Prove-It test for a suspected defect in the app (not fixed here): after the end emit, the
     * Error leaves the coroutine and launchWithErrorHandling's handler sends one more tool_progress
     * event (phase failed, reason UNKNOWN, errorKind TOOL_INSTALL_FAILED). The page's last end
     * event then differs from getToolInstallState. Enable once the app is fixed.
     */
    @ParameterizedTest(name = "{0}, script says {1}")
    @CsvSource(
        "stack-overflow, ok, done, ",
        "stack-overflow, failed:install, failed, INSTALL_FAILED",
        "custom-error, ok, done, ",
        "assertion-error, failed:verify, failed, VERIFY_FAILED",
    )
    fun `an Error thrown by record leaves the page at the real end state as its last end event`(
        kind: String,
        outcome: String,
        phase: String,
        reason: String?,
    ) {
        marker()
        resultScript(outcome)
        recordThrows(kind)
        val (bridge, web) = page()

        bridge.installTool("tmux")
        val last = finalEvents(web).last().data
        assertEquals(phase, last["phase"], "the last end event is not the run's verdict: $last")
        assertEquals(reason, last["reason"], last.toString())
        assertFalse("errorKind" in last, "the last end event is an error report: $last")
        assertEquals(withoutNulls(bridge.toolState()), withoutNulls(last), "the page ends on another state than native")
    }

    private companion object {
        const val SETTLE_MS = 500L
    }
}
