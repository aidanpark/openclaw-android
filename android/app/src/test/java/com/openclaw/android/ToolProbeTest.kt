package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * [ToolProbe.status] turns the exit code of a tool's `--version` check into what the page shows.
 * 0 is "ok"; a plain failure (1..127, including 126 "not executable" and 127 "not found / link
 * error") is "failed". -1 is the runner's own code for "no exit code": when the check did NOT time
 * out it means the process could not even be started although its file is on disk (no execute
 * permission, a link error at exec) — the tool does not run, "failed". A timeout (-1 with
 * `timedOut`) and a death by signal (>= 128) say nothing about the tool and stay "unknown".
 */
internal class ToolProbeTest {
    @ParameterizedTest(name = "exit {0} -> {1}")
    @CsvSource(
        "0, ok",
        "1, failed",
        "2, failed",
        "64, failed",
        "125, failed",
        "126, failed",
        "127, failed",
        "128, unknown",
        "129, unknown",
        "130, unknown",
        "137, unknown",
        "143, unknown",
        "255, unknown",
        "256, unknown",
        "-1, failed",
        "-2, unknown",
        "-127, unknown",
        "-128, unknown",
        "2147483647, unknown",
        "-2147483648, unknown",
    )
    fun `exit code without a timeout maps to ok, failed or unknown`(
        exitCode: Int,
        expected: String,
    ) {
        assertEquals(expected, ToolProbe.status(exitCode))
        assertEquals(expected, ToolProbe.status(exitCode, timedOut = false), "the default is not timedOut=false")
    }

    @Test
    fun `a launch failure that did not time out reads failed`() {
        assertEquals(ToolProbe.FAILED, ToolProbe.status(-1, timedOut = false))
    }

    @Test
    fun `a check that timed out reads unknown, never a false broken`() {
        assertEquals(ToolProbe.UNKNOWN, ToolProbe.status(-1, timedOut = true))
    }

    @Test
    fun `timedOut changes the verdict of -1 only`() {
        for (code in -RANGE..RANGE) {
            if (code == LAUNCH_FAILED) continue
            assertEquals(ToolProbe.status(code, false), ToolProbe.status(code, true), "exit $code")
        }
        for (code in listOf(Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertEquals(ToolProbe.status(code, false), ToolProbe.status(code, true), "exit $code")
        }
    }

    @Test
    fun `the three statuses are the strings the page compares against`() {
        assertEquals("ok", ToolProbe.OK)
        assertEquals("failed", ToolProbe.FAILED)
        assertEquals("unknown", ToolProbe.UNKNOWN)
    }

    @Test
    fun `only 0 is ok and exactly 1 to 127 and an untimed -1 are failed over a wide range`() {
        for (timedOut in listOf(false, true)) {
            for (code in -RANGE..RANGE) {
                val expected =
                    when {
                        code == 0 -> ToolProbe.OK
                        code in 1..LAST_PLAIN_EXIT -> ToolProbe.FAILED
                        code == LAUNCH_FAILED && !timedOut -> ToolProbe.FAILED
                        else -> ToolProbe.UNKNOWN
                    }
                assertEquals(expected, ToolProbe.status(code, timedOut), "exit $code timedOut=$timedOut")
            }
        }
    }

    private companion object {
        const val RANGE = 1_024
        const val LAST_PLAIN_EXIT = 127
        const val LAUNCH_FAILED = -1
    }
}
