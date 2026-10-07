package com.openclaw.android

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Cancelling `oa --update` for real: a SIGTERM reaches the fake script (found by its token in the
 * fake `/proc`) only before `[3/5]`, once, and the run ends cancelled; from `[3/5]` on a cancel is
 * refused and the script runs to its end.
 */
internal class ManagedRunnerCancelTest {
    @TempDir
    lateinit var root: File

    private lateinit var w: ManagedRunWorld

    @BeforeEach
    fun setup() {
        ManagedRunWorld.resetShared()
        w = ManagedRunWorld(root)
    }

    @AfterEach
    fun teardown() {
        val ended = w.cleanup()
        ManagedRunWorld.resetShared()
        assertTrue(ended, "a run outlived its test")
    }

    private fun stage() = ManagedRunGuard.snapshot().stage

    /** Steps 1-2, wait for `to3`, mark `reached3`, steps 3-5 and the banner. */
    private val pausedAt2 =
        """
        |step 1 "Pre-flight Check"
        |step 2 "Download Latest Release (tarball)"
        |echo "downloading"
        |hold to3
        |: > "${'$'}HOME/reached3"
        |step 3 "Update Core Infrastructure"
        |step 4 "Update Platform"
        |step 5 "Update Optional Tools"
        |echo -e "${'$'}{GREEN}${'$'}{BOLD}  Update Complete!${'$'}{NC}"
        |exit 0
        """.trimMargin()

    @Test
    fun `a cancel at stage 2 sends one real SIGTERM to the script and the run ends cancelled`() {
        w.fakeOa(pausedAt2)
        val runner = w.runner()
        w.startInBackground(runner)
        assertTrue(TestWait.until { ManagedRunGuard.snapshot().message == "downloading" })
        val pid = w.registeredPids().single()

        runner.cancel()
        val end = w.finalEvent()

        assertEquals("cancelled", end.data["phase"], w.runEvents().toString())
        // the user's cancel is CANCELLED in the event and the record; INTERRUPTED is a stop from elsewhere
        assertEquals("CANCELLED", end.data["reason"])
        assertEquals(143, end.data["exit"], "the script did not die of the SIGTERM")
        assertEquals(listOf(pid to RunSignal.SIGTERM), w.runSignals.toList())
        assertFalse(w.mark("reached3"), "the script went on past the cancel")
        assertTrue(w.runEvents().any { it.data["phase"] == "cancelling" && it.data["cancelRequested"] == true })
        val last = RunOutcomeStore(w.lastRunFile).load().getValue("UPDATE")
        assertEquals(UpdateReason.CANCELLED, last.reason)
        assertTrue(TestWait.until { RunLease.owner() == null })
    }

    @Test
    fun `from stage 3 a cancel is refused, nothing is signalled and the run finishes done`() {
        w.fakeOa(
            """
            |step 1 "Pre-flight Check"
            |step 2 "Download Latest Release (tarball)"
            |step 3 "Update Core Infrastructure"
            |echo "replacing core"
            |hold release
            |step 4 "Update Platform"
            |step 5 "Update Optional Tools"
            |echo -e "${'$'}{GREEN}${'$'}{BOLD}  Update Complete!${'$'}{NC}"
            |exit 0
            """.trimMargin(),
        )
        val runner = w.runner()
        w.startInBackground(runner)
        assertTrue(TestWait.until { ManagedRunGuard.snapshot().message == "replacing core" })
        val before = w.runEvents().size
        runner.cancel()
        runner.cancel()
        // the page still hears that the run is not cancelable
        val after = w.runEvents().drop(before)
        assertTrue(
            after.isNotEmpty() &&
                after.all {
                    it.data["cancelable"] == false && it.data["phase"] == "running"
                },
            "$after",
        )
        Thread.sleep(PUMP_WAIT_MS)
        assertEquals(emptyList<Pair<Int, Int>>(), w.runSignals.toList())
        w.release("release")
        assertEquals("done", w.finalEvent().data["phase"])
    }

    @Test
    fun `a cancel asked before the script is visible is delivered by the pump once it is`() {
        w.fakeOa(
            """
            |step 1 "Pre-flight Check"
            |echo "not yet registered"
            |hold reg-now
            |reg
            |hold to3
            |: > "${'$'}HOME/reached3"
            |step 3 "Update Core Infrastructure"
            |exit 0
            """.trimMargin(),
            register = false,
        )
        val runner = w.runner()
        w.startInBackground(runner)
        assertTrue(TestWait.until { ManagedRunGuard.snapshot().message == "not yet registered" })
        runner.cancel()
        assertEquals(ManagedRunGuard.CANCELLING, ManagedRunGuard.snapshot().phase)
        assertEquals(emptyList<Pair<Int, Int>>(), w.runSignals.toList())
        // registered without printing anything: only the 1s pump can deliver now
        w.release("reg-now")
        val end = w.finalEvent()
        assertEquals("cancelled", end.data["phase"])
        assertEquals(1, w.runSignals.size, w.runSignals.toString())
        assertFalse(w.mark("reached3"))
    }

    @Test
    fun `a cancel that could not be delivered before stage 3 is withdrawn and the run finishes done`() {
        w.fakeOa(
            """
            |step 1 "Pre-flight Check"
            |step 2 "Download Latest Release (tarball)"
            |echo "not yet registered"
            |hold to3
            |step 3 "Update Core Infrastructure"
            |reg
            |echo "registered after the boundary"
            |hold release
            |step 5 "Update Optional Tools"
            |echo -e "${'$'}{GREEN}${'$'}{BOLD}  Update Complete!${'$'}{NC}"
            |exit 0
            """.trimMargin(),
            register = false,
        )
        val runner = w.runner()
        w.startInBackground(runner)
        assertTrue(TestWait.until { ManagedRunGuard.snapshot().message == "not yet registered" })
        runner.cancel()
        assertTrue(ManagedRunGuard.snapshot().cancelRequested)
        w.release("to3")
        assertTrue(TestWait.until { ManagedRunGuard.snapshot().message == "registered after the boundary" })
        val s = ManagedRunGuard.snapshot()
        assertEquals(ManagedRunGuard.RUNNING, s.phase, "a cancel nobody received still shows as requested")
        assertFalse(s.cancelable)
        Thread.sleep(PUMP_WAIT_MS)
        assertEquals(emptyList<Pair<Int, Int>>(), w.runSignals.toList(), "a cancel landed after [3/5]")
        w.release("release")
        val end = w.finalEvent()
        assertEquals("done", end.data["phase"])
        assertEquals(false, end.data["cancelRequested"])
    }

    @Test
    fun `a script that survives the SIGTERM is never signalled twice`() {
        w.fakeOa(
            """
            |trap 'echo "TERM ignored"' TERM
            |step 1 "Pre-flight Check"
            |echo "waiting"
            |hold release
            |echo -e "${'$'}{GREEN}${'$'}{BOLD}  Update Complete!${'$'}{NC}"
            |exit 0
            """.trimMargin(),
        )
        val runner = w.runner()
        w.startInBackground(runner)
        assertTrue(TestWait.until { ManagedRunGuard.snapshot().message == "waiting" })
        runner.cancel()
        assertTrue(TestWait.until { w.runSignals.size == 1 })
        runner.cancel()
        Thread.sleep(PUMP_WAIT_MS)
        assertEquals(1, w.runSignals.size, w.runSignals.toString())
        w.release("release")
        // it did not stop, and ended well: that is what happened
        assertEquals("done", w.finalEvent().data["phase"])
    }

    @Test
    fun `a cancel with nothing running does nothing and says nothing`() {
        val runner = w.runner()
        runner.cancel()
        assertEquals(emptyList<EmittedEvent>(), w.events.toList())
        assertEquals(emptyList<Pair<Int, Int>>(), w.runSignals.toList())
    }

    @Test
    fun `the terminal's own update (another token) is never signalled by the app's cancel`() {
        w.runProc.add(
            TERMINAL_PID,
            1,
            listOf("bash", "/usr/bin/oa", "--update"),
            environ = listOf("OA_APP_RUN_TOKEN=terminal", "HOME=/h"),
        )
        // the terminal's update makes the app refuse to start at all
        w.fakeOa(pausedAt2)
        w.runToEnd(w.runner())
        assertEquals("BUSY", w.finalEvent().data["reason"])
        assertEquals(emptyList<String>(), w.oaCalls())
        assertEquals(emptyList<Pair<Int, Int>>(), w.runSignals.toList())
    }

    private companion object {
        /** Longer than the cancel pump's 1s period, twice. */
        const val PUMP_WAIT_MS = 2_300L
        const val TERMINAL_PID = 999_999
    }
}
