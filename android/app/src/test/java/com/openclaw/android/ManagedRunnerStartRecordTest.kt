package com.openclaw.android

import io.mockk.every
import io.mockk.spyk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The start record: right before the script starts (after the pre-check), `last-run.conf` already
 * says "interrupted" with the run's start time, and the end record replaces it. If the app process
 * dies during the run, the record says so after a restart instead of showing the run before it. A
 * pre-check refusal records nothing; a start record that cannot be written never keeps the run
 * from running.
 */
internal class ManagedRunnerStartRecordTest {
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

    /** The script copies `last-run.conf` as it finds it at its start, then waits for `release`. */
    private fun heldScript(rest: String = w.successBody) =
        w.fakeOa(
            """
            |cat '${w.lastRunFile.path}' > "${'$'}HOME/record-at-start.log" 2>/dev/null || : > "${'$'}HOME/record-at-start.log"
            |echo waiting
            |hold release
            |$rest
            """.trimMargin(),
        )

    private fun recordAtStart(): String = File(w.home, "record-at-start.log").readText()

    private fun previousSuccess() = RunOutcomeStore(w.lastRunFile).record("UPDATE", 77, RunVerdict.Success(0, 0))

    @Test
    fun `before the script starts the record says interrupted, at the run's start time`() {
        previousSuccess()
        heldScript()
        val runner = w.runner()
        w.startInBackground(runner)
        assertTrue(TestWait.until { ManagedRunGuard.snapshot().message == "waiting" })
        val started = ManagedRunGuard.snapshot().startedAtSec
        assertEquals("UPDATE|$started|failure|INTERRUPTED||0|", recordAtStart().trim())
        // what the page reads while the run goes on
        val during = runner.lastRuns().getValue("UPDATE")
        assertEquals(
            mapOf("at" to started, "verdict" to "failure", "reason" to "INTERRUPTED", "exit" to null, "warnings" to 0),
            during,
        )
        w.release("release")
        assertEquals("done", w.finalEvent().data["phase"])
    }

    @Test
    fun `the end record replaces the start record`() {
        heldScript()
        val runner = w.runner()
        w.startInBackground(runner)
        assertTrue(TestWait.until { ManagedRunGuard.snapshot().message == "waiting" })
        w.release("release")
        w.finalEvent()
        val end = RunOutcomeStore(w.lastRunFile).load().getValue("UPDATE")
        assertTrue(end.success, end.toString())
        assertEquals(0, end.exit)
        assertEquals(1, w.lastRunFile.readLines().size, w.lastRunFile.readText())
    }

    @Test
    fun `a failure replaces the start record with its own reason`() {
        heldScript("echo -e \"\${RED}[FAIL]\${NC} Checksum mismatch for x\"\nexit 1")
        w.startInBackground(w.runner())
        assertTrue(TestWait.until { ManagedRunGuard.snapshot().message == "waiting" })
        w.release("release")
        assertEquals("CHECKSUM", w.finalEvent().data["reason"])
        val end = RunOutcomeStore(w.lastRunFile).load().getValue("UPDATE")
        assertEquals(UpdateReason.CHECKSUM, end.reason)
        assertEquals(1, end.exit)
    }

    /**
     * The app process dies during the run: what is on disk at that moment is what a restarted app
     * reads. Simulated by copying `last-run.conf` while the script runs, then a fresh guard (the
     * process-wide state of a new process) and a new runner over the copy.
     */
    @Test
    fun `an app that dies during the run reads the run as interrupted after a restart`() {
        previousSuccess()
        heldScript()
        w.startInBackground(w.runner())
        assertTrue(TestWait.until { ManagedRunGuard.snapshot().message == "waiting" })
        val started = ManagedRunGuard.snapshot().startedAtSec
        val afterRestart = File(root, "restarted/last-run.conf")
        w.lastRunFile.copyTo(afterRestart)
        // the dead process's run is gone with it
        w.release("release")
        w.finalEvent()
        ManagedRunWorld.resetShared()

        val restarted =
            ManagedRunner(
                homeDir = w.home,
                environment = w::environment,
                outcomes = RunOutcomeStore(afterRestart),
                gateway = w.gateway(),
                emit = { _, _ -> },
            )
        val last = restarted.lastRuns().getValue("UPDATE")
        assertEquals("failure", last["verdict"])
        assertEquals("INTERRUPTED", last["reason"])
        assertEquals(started, last["at"])
        assertFalse(ManagedRunGuard.snapshot().busy, "a restarted app shows no run going")
    }

    // ── refusals record nothing ─────────────────────────────────────────────

    @Test
    fun `a pre-check refusal records nothing and keeps the previous record`() {
        previousSuccess()
        val before = w.lastRunFile.readText()
        // NOT_INSTALLED: no oa
        w.runToEnd(w.runner())
        assertEquals("NOT_INSTALLED", w.finalEvent().data["reason"])
        // BUSY: an updater runs in a terminal
        w.events.clear()
        w.fakeOa(w.successBody)
        w.runProc.add(4242, 1, listOf("bash", "/tmp/update-core.Xy12Ab.sh"))
        w.runToEnd(w.runner())
        assertEquals("BUSY", w.finalEvent().data["reason"])
        // GATEWAY_RUNNING: a gateway without the stop consent
        w.events.clear()
        w.runProc.remove(4242)
        w.gwProc.add(7000, 1, listOf("bash"))
        w.gwProc.add(7001, 7000, listOf("openclaw", "gateway"))
        w.runToEnd(w.runner(), stopGateway = false)
        assertEquals("GATEWAY_RUNNING", w.finalEvent().data["reason"])

        assertEquals(before, w.lastRunFile.readText(), "a refusal changed the record")
        assertEquals(emptyList<String>(), w.oaCalls())
    }

    // ── a start record that cannot be written ───────────────────────────────

    private fun storeFailingAtStart(failure: Throwable): RunOutcomeStore {
        val store = spyk(RunOutcomeStore(w.lastRunFile))
        val startRecord = { v: RunVerdict ->
            v is RunVerdict.Failure &&
                v.reason == UpdateReason.INTERRUPTED &&
                v.exit == null
        }
        every { store.record(any(), any(), match { startRecord(it) }) } throws failure
        return store
    }

    @Test
    fun `a start record that throws does not keep the run from running, and the end is recorded`() {
        w.fakeOa(w.successBody)
        w.runToEnd(w.runner(outcomes = storeFailingAtStart(IllegalStateException("disk"))))
        assertEquals("done", w.finalEvent().data["phase"])
        assertEquals(listOf("--update"), w.oaCalls())
        assertTrue(RunOutcomeStore(w.lastRunFile).load().getValue("UPDATE").success)
        assertTrue(TestWait.until { RunLease.owner() == null })
    }

    @Test
    fun `a start record that cannot be written to disk does not keep the run from running`() {
        // a directory where the record file should be: every write fails inside the store
        w.lastRunFile.mkdirs()
        w.fakeOa(w.successBody)
        w.runToEnd(w.runner())
        assertEquals("done", w.finalEvent().data["phase"])
        assertEquals(listOf("--update"), w.oaCalls())
    }

    /**
     * The end record already stops even an Error ("Even an Error stops here"); the start record
     * catches only Exception, so an Error from it escapes [ManagedRunner] before the script starts:
     * the update never runs and the page is told it failed (UNKNOWN). The design says a failing
     * start record must never keep the run from starting.
     */
    @Test
    fun `a start record that throws an Error does not keep the run from running either`() {
        w.fakeOa(w.successBody)
        w.runToEnd(w.runner(outcomes = storeFailingAtStart(StackOverflowError("record broke"))))
        assertEquals(listOf("--update"), w.oaCalls(), "the update never ran")
        assertEquals("done", w.finalEvent().data["phase"])
    }

    @Test
    fun `a start record that throws an Error still releases the guard and the lease`() {
        w.fakeOa(w.successBody)
        w.runToEnd(w.runner(outcomes = storeFailingAtStart(StackOverflowError("record broke"))))
        assertTrue(TestWait.until { !ManagedRunGuard.isRunning() && RunLease.owner() == null })
        assertTrue(w.runEvents().last().data["phase"] in ManagedRunWorld.END_PHASES, w.runEvents().toString())
    }
}
