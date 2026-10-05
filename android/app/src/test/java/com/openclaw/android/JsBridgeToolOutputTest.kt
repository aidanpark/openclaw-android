package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the page receives of the script's output: each line without ANSI codes and cut to its last
 * 300 characters, and a line merged away by the 200 ms limit still reaches the page through the
 * 1 s pump even when the script prints nothing more for a long time (npm resolving, a download).
 */
internal class JsBridgeToolOutputTest : JsBridgeToolInstallFixture() {
    @Test
    fun `a 5000 character line reaches the page as its last 300 characters`() {
        marker()
        fakeScript(
            """
            |mkdir -p "${'$'}PREFIX/bin" && : > "${'$'}PREFIX/bin/tmux"
            |printf 'schema=1\nrun=%s\ntmux=ok\nexit=0\n' "${'$'}(date +%s)" > "${'$'}R"
            |i=0; while [ ${'$'}i -lt $LONG_LINE ]; do printf 'x'; i=${'$'}((i+1)); done; printf 'TAIL\n'
            |exit 0
            """.trimMargin(),
        )
        val (bridge, web) = page()
        bridge.installTool("tmux")
        val end = finalEvent(web)
        assertEquals("done", end.data["phase"])

        val expected = ("x".repeat(LONG_LINE) + "TAIL").takeLast(MAX_LINE)
        assertEquals(expected, end.data["message"], "the final state does not carry the cut last line")
        assertEquals(expected, bridge.toolState()["message"])
        val longest = web.events("tool_progress").maxOf { (it.data["message"] as String?)?.length ?: 0 }
        assertTrue(longest <= MAX_LINE, "an event carried a $longest-character message")
    }

    @Test
    fun `ANSI codes are removed before the line is cut, so the cut keeps 300 visible characters`() {
        marker()
        fakeScript(
            """
            |mkdir -p "${'$'}PREFIX/bin" && : > "${'$'}PREFIX/bin/tmux"
            |printf 'schema=1\nrun=%s\ntmux=ok\nexit=0\n' "${'$'}(date +%s)" > "${'$'}R"
            |i=0; while [ ${'$'}i -lt $LONG_LINE ]; do printf 'y'; i=${'$'}((i+1)); done
            |printf '\033[1;32mEND\033[0m\033[K\n'
            |exit 0
            """.trimMargin(),
        )
        val (bridge, web) = page()
        bridge.installTool("tmux")
        val end = finalEvent(web)
        val message = end.data["message"] as String
        assertEquals(("y".repeat(LONG_LINE) + "END").takeLast(MAX_LINE), message)
        assertTrue(message.none { it == '\u001B' }, message.takeLast(SHOW))
        assertEquals(message, bridge.toolState()["message"])
    }

    /**
     * "first" opens a 200 ms window, "second" comes right after and is only stored. The script then
     * prints nothing until released: without the pump's emit the page would show "first" until the
     * install ends (minutes on a real npm install).
     */
    @Test
    fun `a last line merged by the 200 ms limit reaches the page within 1_5 s while the script is silent`() {
        marker()
        fakeScript(
            """
            |printf 'first\nsecond\n'
            |while [ ! -f "${'$'}HOME/release" ]; do sleep 0.05; done
            |mkdir -p "${'$'}PREFIX/bin" && : > "${'$'}PREFIX/bin/tmux"
            |printf 'schema=1\nrun=%s\ntmux=ok\nexit=0\n' "${'$'}(date +%s)" > "${'$'}R"
            |exit 0
            """.trimMargin(),
        )
        val (bridge, web) = page()
        bridge.installTool("tmux")
        assertTrue(TestWait.until { ToolInstallGuard.snapshot().message == "second" }, "the script never printed")
        val stored = System.nanoTime()

        val arrived =
            TestWait.until(PUMP_DEADLINE_MS) { web.events("tool_progress").any { it.data["message"] == "second" } }
        val waitedMs = (System.nanoTime() - stored) / NANOS_PER_MS
        assertTrue(arrived, "the last line did not reach the page in $PUMP_DEADLINE_MS ms: ${web.scripts}")
        assertTrue(ToolInstallGuard.isRunning(), "the install ended before the check: the final emit carried it")
        assertTrue(waitedMs <= PUMP_DEADLINE_MS, "arrived after $waitedMs ms")
        assertEquals("running", web.events("tool_progress").last().data["phase"])

        releaseFile.writeText("go")
        assertEquals("done", finalEvent(web).data["phase"])
        assertEquals("done", bridge.toolState()["phase"])
    }

    private companion object {
        const val LONG_LINE = 5000
        const val MAX_LINE = 300
        const val SHOW = 40
        const val PUMP_DEADLINE_MS = 1_500L
        const val NANOS_PER_MS = 1_000_000L
    }
}
