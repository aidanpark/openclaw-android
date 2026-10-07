package com.openclaw.android

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A cancel that came too late (v0.4.4 QA): the decision rules on their own. [ToolCancelCheck]
 * says when the tool's own check is worth running and what its answer does to the verdict;
 * [ToolInstallGuard.finish] turns a success after a cancel request into `done` with
 * [ToolInstallGuard.CANCEL_TOO_LATE] — and only that.
 */
internal class ToolLateCancelTest {
    private val interrupted = ToolVerdict.Failure(ToolFailure.INTERRUPTED)
    private val verdicts: List<ToolVerdict> =
        listOf(ToolVerdict.Success) + ToolFailure.entries.map { ToolVerdict.Failure(it) }
    private val probeAnswers = listOf(ToolProbe.OK, ToolProbe.FAILED, ToolProbe.UNKNOWN, "", "OK", "ok ")

    // ── worthProbing: the full truth table ──────────────────────────────────

    @Test
    fun `the check runs only for a cancel request, the tool on disk and the script's own interrupted end`() {
        for (verdict in verdicts) {
            for (cancel in listOf(true, false)) {
                for (onDisk in listOf(true, false)) {
                    val expected = verdict == interrupted && cancel && onDisk
                    assertEquals(
                        expected,
                        ToolCancelCheck.worthProbing(verdict, cancel, onDisk),
                        "verdict=$verdict cancel=$cancel onDisk=$onDisk",
                    )
                }
            }
        }
    }

    @Test
    fun `NOT_RUN after a cancel is never probed - the script never got to install anything`() {
        assertFalse(ToolCancelCheck.worthProbing(ToolVerdict.Failure(ToolFailure.NOT_RUN), true, true))
    }

    // ── settle: only ok turns the interrupted run into a success ────────────

    @Test
    fun `settle - ok makes the interrupted run a success, every other answer keeps it`() {
        for (answer in probeAnswers) {
            val expected: ToolVerdict = if (answer == ToolProbe.OK) ToolVerdict.Success else interrupted
            assertEquals(expected, ToolCancelCheck.settle(interrupted, answer), "answer '$answer'")
        }
    }

    @Test
    fun `the probe's exit codes map to the answers settle understands - timeout and signals are not ok`() {
        val cases =
            listOf(
                Triple(0, false, ToolVerdict.Success),
                Triple(1, false, interrupted),
                Triple(127, false, interrupted),
                Triple(-1, true, interrupted), // timed out (VERIFY_TIMEOUT_MS)
                Triple(-1, false, interrupted), // could not start
                Triple(137, false, interrupted), // killed
                Triple(143, false, interrupted),
            )
        for ((exit, timedOut, expected) in cases) {
            assertEquals(
                expected,
                ToolCancelCheck.settle(interrupted, ToolProbe.status(exit, timedOut)),
                "exit=$exit timedOut=$timedOut",
            )
        }
    }

    // ── ToolInstallGuard.finish ─────────────────────────────────────────────

    @BeforeEach
    @AfterEach
    fun idle() {
        if (ToolInstallGuard.isRunning()) ToolInstallGuard.finish(ToolVerdict.Success)
        assertTrue(ToolInstallGuard.tryStart("reset", 0L))
        ToolInstallGuard.finish(ToolVerdict.Success)
    }

    private fun end(
        cancel: Boolean,
        verdict: ToolVerdict,
    ): ToolInstallGuard.State {
        assertTrue(ToolInstallGuard.tryStart("tmux", 1L))
        ToolInstallGuard.progress(0.5f, "npm notice")
        if (cancel) assertTrue(ToolInstallGuard.requestCancel { false })
        ToolInstallGuard.finish(verdict)
        assertFalse(ToolInstallGuard.isRunning())
        return ToolInstallGuard.snapshot()
    }

    @Test
    fun `a success after a cancel request is done with CANCEL_TOO_LATE, progress full, nothing cancelled`() {
        val s = end(cancel = true, verdict = ToolVerdict.Success)
        assertEquals(ToolInstallGuard.DONE, s.phase)
        assertEquals(ToolInstallGuard.CANCEL_TOO_LATE, s.reason)
        assertEquals(1f, s.progress)
        assertEquals("tmux", s.tool)
        assertFalse(s.cancelRequested)
        assertFalse(s.busy)
    }

    @Test
    fun `a success without a cancel request is done with no reason`() {
        val s = end(cancel = false, verdict = ToolVerdict.Success)
        assertEquals(ToolInstallGuard.DONE, s.phase)
        assertNull(s.reason)
        assertEquals(1f, s.progress)
    }

    @Test
    fun `a cancel that really interrupted stays cancelled, a failure stays a failure - never CANCEL_TOO_LATE`() {
        for (failure in ToolFailure.entries) {
            for (cancel in listOf(true, false)) {
                val s = end(cancel, ToolVerdict.Failure(failure))
                assertTrue(s.reason != ToolInstallGuard.CANCEL_TOO_LATE, "$failure cancel=$cancel")
                assertEquals(0f, s.progress, "$failure cancel=$cancel")
                val cancelled = cancel && failure in setOf(ToolFailure.INTERRUPTED, ToolFailure.NOT_RUN)
                if (cancelled) {
                    assertEquals(ToolInstallGuard.CANCELLED, s.phase, "$failure")
                    assertEquals(ToolFailure.INTERRUPTED.name, s.reason, "$failure")
                } else {
                    assertEquals(ToolInstallGuard.FAILED, s.phase, "$failure cancel=$cancel")
                    assertEquals(failure.name, s.reason, "$failure cancel=$cancel")
                }
            }
        }
    }

    @Test
    fun `the next install starts without the reason of a late cancel`() {
        end(cancel = true, verdict = ToolVerdict.Success)
        assertTrue(ToolInstallGuard.tryStart("ttyd", 2L))
        val s = ToolInstallGuard.snapshot()
        assertEquals(ToolInstallGuard.RUNNING, s.phase)
        assertNull(s.reason)
        ToolInstallGuard.finish(ToolVerdict.Success)
        assertNull(ToolInstallGuard.snapshot().reason)
    }

    @Test
    fun `the record of a late-cancel success clears a remembered broken end of the tool`() {
        val file =
            kotlin.io.path
                .createTempFile("tool-outcomes", ".conf")
                .toFile()
        try {
            val store = ToolOutcomeStore(file)
            store.record("tmux", ToolVerdict.Failure(ToolFailure.VERIFY_FAILED))
            assertEquals(setOf("tmux"), store.load().keys)
            // What installTool records for a late cancel: settle's success
            store.record("tmux", ToolCancelCheck.settle(interrupted, ToolProbe.OK))
            assertEquals(emptySet<String>(), store.load().keys)
            // An interrupted end that stays cancelled leaves the record as it was
            store.record("tmux", ToolVerdict.Failure(ToolFailure.VERIFY_FAILED))
            store.record("tmux", ToolCancelCheck.settle(interrupted, ToolProbe.FAILED))
            assertEquals(setOf("tmux"), store.load().keys)
        } finally {
            file.delete()
        }
    }
}
