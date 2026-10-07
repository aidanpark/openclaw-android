package com.openclaw.android

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * How an `oa --update` that was stopped is told apart (vc23): the user's cancel is CANCELLED — in the
 * end event, in `last-run.conf` and in `getLastRun` — while a stop the app did not ask for (a signal
 * from elsewhere, or the app process dying mid-run) stays INTERRUPTED. The settle happens once, in
 * [ManagedRunner]'s end, before the record: so a run that threw after the cancel is CANCELLED too,
 * and only the start record (written before the script) can survive a dead app.
 */
internal class ManagedRunCancelOutcomeTest {
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

    private fun lastRecord(): RunOutcome = RunOutcomeStore(w.lastRunFile).load().getValue(RunKinds.UPDATE)

    private fun waitMessage(message: String) =
        assertTrue(TestWait.until { ManagedRunGuard.snapshot().message == message }, "never saw '$message'")

    /** Stages 1-2, then waits at `[2/5]` for `to3` (a cancel is still allowed there). */
    private val heldAtStage2 =
        """
        |step 1 "Pre-flight Check"
        |step 2 "Download Latest Release (tarball)"
        |echo "downloading"
        |hold to3
        |step 3 "Update Core Infrastructure"
        |exit 0
        """.trimMargin()

    // ── 1. the user's cancel ────────────────────────────────────────────────

    @Test
    fun `a cancel that ends the script by SIGTERM is CANCELLED in the end event, last-run conf and lastRuns`() {
        w.fakeOa(heldAtStage2)
        val runner = w.runner()
        w.startInBackground(runner)
        waitMessage("downloading")
        val started = ManagedRunGuard.snapshot().startedAtSec

        runner.cancel()
        val end = w.finalEvent()

        assertEquals("cancelled", end.data["phase"], w.runEvents().toString())
        assertEquals("CANCELLED", end.data["reason"])
        assertEquals(143, end.data["exit"])
        assertFalse(w.runEvents().any { it.data["phase"] == "failed" }, "a cancelled run was also shown failed")
        val line = w.lastRunFile.readText().trim()
        assertTrue(Regex("""UPDATE\|\d+\|failure\|CANCELLED\|143\|0\|""").matches(line), line)
        assertTrue(lastRecord().atSec >= started, line)
        assertEquals(
            mapOf(
                "at" to lastRecord().atSec,
                "verdict" to "failure",
                "reason" to "CANCELLED",
                "exit" to 143,
                "warnings" to 0,
            ),
            runner.lastRuns().getValue(RunKinds.UPDATE),
        )
    }

    // ── 2. a signal the app did not send ────────────────────────────────────

    @Test
    fun `a SIGTERM from elsewhere with no cancel requested is failed INTERRUPTED, in the event and the record`() {
        w.fakeOa(heldAtStage2)
        w.startInBackground(w.runner())
        waitMessage("downloading")
        val pid = w.registeredPids().single()

        // e.g. the terminal's `kill`: the app asked for nothing
        ProcessBuilder("kill", "-TERM", "$pid").start().waitFor()
        val end = w.finalEvent()

        assertEquals("failed", end.data["phase"], w.runEvents().toString())
        assertEquals("INTERRUPTED", end.data["reason"])
        assertEquals(143, end.data["exit"])
        assertEquals(emptyList<Pair<Int, Int>>(), w.runSignals.toList(), "the app signalled although nobody cancelled")
        assertEquals(UpdateReason.INTERRUPTED, lastRecord().reason)
    }

    // ── 3. the app dies after the cancel was asked ──────────────────────────

    /**
     * What is on disk while the cancelled script is still ending is what a restarted app reads if
     * the app process dies at that moment: the start record, INTERRUPTED — never CANCELLED, since the
     * cancel's settle happens only in the app that saw the end. The live app still records CANCELLED.
     */
    @Test
    fun `an app that dies after asking for the cancel reads the run as INTERRUPTED after a restart`() {
        w.fakeOa(
            """
            |trap ': > "${'$'}HOME/got-term"; hold release; exit 143' TERM
            |$heldAtStage2
            """.trimMargin(),
        )
        val runner = w.runner()
        w.startInBackground(runner)
        waitMessage("downloading")
        val started = ManagedRunGuard.snapshot().startedAtSec
        runner.cancel()
        assertTrue(TestWait.until { w.mark("got-term") }, "the script never got the cancel's SIGTERM")
        assertTrue(ManagedRunGuard.snapshot().cancelRequested)

        val atDeath = File(root, "restarted/last-run.conf")
        w.lastRunFile.copyTo(atDeath)
        w.release("release")
        assertEquals("CANCELLED", w.finalEvent().data["reason"], "the live app's end")
        assertEquals(UpdateReason.CANCELLED, lastRecord().reason)
        ManagedRunWorld.resetShared()

        assertEquals("UPDATE|$started|failure|INTERRUPTED||0|", atDeath.readText().trim())
        val restarted =
            ManagedRunner(
                homeDir = w.home,
                environment = w::environment,
                outcomes = RunOutcomeStore(atDeath),
                gateway = w.gateway(),
                emit = { _, _ -> },
            )
        val last = restarted.lastRuns().getValue(RunKinds.UPDATE)
        assertEquals("failure", last["verdict"])
        assertEquals("INTERRUPTED", last["reason"])
        assertEquals(started, last["at"])
    }

    // ── 4. the run throws ───────────────────────────────────────────────────

    /**
     * An emit that throws on the stream reader's thread (Dispatchers.IO) at `[2/5]` while the phase
     * is [phase]: the exception leaves the output callback, so `runScript` throws and the verdict is
     * the "could not tell" UNKNOWN. Other emits (the test's own `cancel`, the pump, the end) pass.
     */
    private fun throwingAtStage2(phase: String): (String, Map<String, Any?>) -> Unit =
        { type, data ->
            w.emit(type, data)
            val readerThread = Thread.currentThread().name.startsWith("DefaultDispatcher-worker")
            check(!(readerThread && data["stage"] == 2 && data["phase"] == phase)) { "emit broke" }
        }

    private val throwsAtStage2Body =
        """
        |step 1 "Pre-flight Check"
        |echo "not yet registered"
        |hold go
        |step 2 "Download Latest Release (tarball)"
        |hold end
        |exit 0
        """.trimMargin()

    @Test
    fun `a run that throws after the cancel was asked ends CANCELLED and is recorded CANCELLED`() {
        // not registered: the cancel cannot be delivered, so the run is still going when it throws
        w.fakeOa(throwsAtStage2Body, register = false)
        val runner = w.runner(emit = throwingAtStage2(ManagedRunGuard.CANCELLING))
        w.startInBackground(runner)
        waitMessage("not yet registered")
        runner.cancel()
        assertEquals(ManagedRunGuard.CANCELLING, ManagedRunGuard.snapshot().phase)
        w.release("go")
        val end = w.finalEvent()

        assertTrue(w.runEvents().any { it.data["stage"] == 2 }, "the run never reached the throwing emit")
        assertEquals(emptyList<Pair<Int, Int>>(), w.runSignals.toList())
        assertEquals("cancelled", end.data["phase"], w.runEvents().toString())
        assertEquals("CANCELLED", end.data["reason"])
        assertNull(end.data["exit"], "the verdict came from the script, not from the exception")
        assertEquals(UpdateReason.CANCELLED, lastRecord().reason)
        assertNull(lastRecord().exit)
    }

    @Test
    fun `a run that throws with no cancel requested stays failed UNKNOWN`() {
        w.fakeOa(throwsAtStage2Body, register = false)
        w.startInBackground(w.runner(emit = throwingAtStage2(ManagedRunGuard.RUNNING)))
        waitMessage("not yet registered")
        w.release("go")
        val end = w.finalEvent()

        assertEquals("failed", end.data["phase"], w.runEvents().toString())
        assertEquals("UNKNOWN", end.data["reason"])
        assertEquals(UpdateReason.UNKNOWN, lastRecord().reason)
    }

    // ── 5. the script's own `reason=interrupted` ────────────────────────────

    /** The TERM trap of the scripts: writes `reason=interrupted` for THIS run, then exits 143. */
    private val trapWritesInterrupted =
        """
        |trap 'printf "schema=1\nrun=%s\nphase=download\nreason=interrupted\nexit=143\n" "${'$'}(date +%s)" > "${'$'}R"; exit 143' TERM
        |$heldAtStage2
        """.trimMargin()

    @Test
    fun `a result file saying interrupted after the user's cancel is CANCELLED`() {
        w.fakeOa(trapWritesInterrupted)
        val runner = w.runner()
        w.startInBackground(runner)
        waitMessage("downloading")
        runner.cancel()
        val end = w.finalEvent()

        assertTrue(w.resultFile.readText().contains("reason=interrupted"), "the trap did not write the result")
        assertEquals("cancelled", end.data["phase"])
        assertEquals("CANCELLED", end.data["reason"])
        assertEquals(UpdateReason.CANCELLED, lastRecord().reason)
    }

    @Test
    fun `a result file saying interrupted with no cancel requested stays failed INTERRUPTED`() {
        w.fakeOa(trapWritesInterrupted)
        w.startInBackground(w.runner())
        waitMessage("downloading")
        ProcessBuilder("kill", "-TERM", "${w.registeredPids().single()}").start().waitFor()
        val end = w.finalEvent()

        assertTrue(w.resultFile.readText().contains("reason=interrupted"), "the trap did not write the result")
        assertEquals("failed", end.data["phase"])
        assertEquals("INTERRUPTED", end.data["reason"])
        assertEquals(UpdateReason.INTERRUPTED, lastRecord().reason)
    }

    /**
     * The file decides on its own (exit 1 with no `[FAIL]` line would otherwise be UNKNOWN): its
     * `interrupted` is INTERRUPTED, and the settle makes it CANCELLED only with a cancel request.
     */
    @Test
    fun `the file's interrupted becomes CANCELLED through settle only when a cancel was requested`() {
        val file =
            RunResultFile(
                runEpoch = 10,
                phase = "download",
                reason = "interrupted",
                exit = 1,
                changed = null,
                healthy = null,
                backup = null,
            )
        val verdict = UpdateVerdict.decide(file, 1, emptyList(), 10, sawComplete = false, warnings = 0)
        assertEquals(UpdateReason.INTERRUPTED, (verdict as RunVerdict.Failure).reason)

        assertTrue(ManagedRunGuard.tryStart(RunKinds.UPDATE, 10, "no-cancel"))
        assertEquals(verdict, ManagedRunGuard.settle(verdict))
        ManagedRunGuard.finish(verdict)

        assertTrue(ManagedRunGuard.tryStart(RunKinds.UPDATE, 10, "cancel"))
        assertTrue(ManagedRunGuard.requestCancel { true })
        assertEquals(verdict.copy(reason = UpdateReason.CANCELLED), ManagedRunGuard.settle(verdict))
        ManagedRunGuard.finish(ManagedRunGuard.settle(verdict))
    }

    // ── 6. codes and the record ─────────────────────────────────────────────

    @Test
    fun `the code cancelled is CANCELLED and interrupted stays INTERRUPTED`() {
        assertEquals(UpdateReason.CANCELLED, UpdateReasons.fromCode("cancelled"))
        assertEquals(UpdateReason.INTERRUPTED, UpdateReasons.fromCode("interrupted"))
        listOf("CANCELLED", "canceled", "cancelled ", "cancel").forEach {
            assertEquals(UpdateReason.UNKNOWN, UpdateReasons.fromCode(it), it)
        }
    }

    @Test
    fun `last-run conf records and reads back CANCELLED, also a line written by hand`() {
        val store = RunOutcomeStore(w.lastRunFile)
        store.record(RunKinds.UPDATE, 100, RunVerdict.Failure(UpdateReason.CANCELLED, 143, "", 0))
        assertEquals("UPDATE|100|failure|CANCELLED|143|0|", w.lastRunFile.readText().trim())
        assertEquals(
            RunOutcome(RunKinds.UPDATE, 100, false, UpdateReason.CANCELLED, 143, 0, ""),
            RunOutcomeStore(w.lastRunFile).load()[RunKinds.UPDATE],
        )
        w.lastRunFile.writeText("UPDATE|200|failure|CANCELLED||0|\n")
        assertEquals(UpdateReason.CANCELLED, lastRecord().reason)
        // a success line cannot carry it
        w.lastRunFile.writeText("UPDATE|300|success|CANCELLED|0|0|\n")
        assertEquals(emptyMap<String, RunOutcome>(), RunOutcomeStore(w.lastRunFile).load())
    }
}
