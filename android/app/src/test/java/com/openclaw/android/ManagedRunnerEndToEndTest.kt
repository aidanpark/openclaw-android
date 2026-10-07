package com.openclaw.android

import com.google.gson.Gson
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.util.stream.Stream

/**
 * [ManagedRunner] end to end against a fake `oa` run by the real bash (see [ManagedRunWorld]):
 * success, each refusal by its `[FAIL]` sentence, the result file, the event stream and its end,
 * the outcome record, and that a broken record never keeps the guard or the lease held.
 */
internal class ManagedRunnerEndToEndTest {
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

    private fun num(v: Any?): Double = (v as Number).toDouble()

    // ── success ─────────────────────────────────────────────────────────────

    @Test
    fun `a full run with the banner is done, recorded, and its events climb through the stages to the real end`() {
        w.fakeOa(w.successBody)
        w.runToEnd(w.runner())
        val end = w.finalEvent()

        assertEquals("done", end.data["phase"], w.runEvents().toString())
        assertEquals(1.0, num(end.data["progress"]))
        assertEquals(0, end.data["exit"])
        assertEquals(false, end.data["cancelable"])
        assertNull(end.data["reason"])
        assertEquals(listOf("--update"), w.oaCalls())

        val events = w.runEvents()
        assertEquals("running", events.first().data["phase"])
        val stages = events.map { (it.data["stage"] as Number).toInt() }
        assertEquals(stages.sorted(), stages, "the stage went back: $stages")
        assertEquals((0..5).toList(), stages.distinct(), "a stage change was merged away: $stages")
        assertTrue(events.dropLast(1).none { it.data["phase"] in setOf("done", "failed", "cancelled") })
        events.forEach { assertEquals(ManagedRunWorld.STATE_KEYS, it.data.keys, it.toString()) }
        // the moment stage 3 is reached, the page hears that cancel is gone
        val first3 = events.first { (it.data["stage"] as Number).toInt() == 3 }
        assertEquals(false, first3.data["cancelable"])
        assertTrue(
            events.filter { (it.data["stage"] as Number).toInt() in 0..2 && it.data["phase"] == "running" }.all {
                it.data["cancelable"] ==
                    true
            },
        )

        val last = RunOutcomeStore(w.lastRunFile).load().getValue("UPDATE")
        assertTrue(last.success)
        assertEquals(0, last.exit)
        assertTrue(TestWait.until { RunLease.owner() == null }, "lease not released")
        assertEquals(ManagedRunner.stateEvent(ManagedRunGuard.snapshot()), end.data)
    }

    @Test
    fun `output lines are cleaned of ANSI and carriage-return redraws and cut to 300 characters`() {
        w.fakeOa(
            """
            |step 1 "Pre-flight Check"
            |echo -e "${'$'}{YELLOW}[WARN]${'$'}{NC} colored"
            |printf ' 10%%\r 50%%\r100%%\n'
            |printf '%0500d\n' 7
            |hold release
            |exit 0
            """.trimMargin(),
        )
        w.startInBackground(w.runner())
        assertTrue(
            TestWait.until { ManagedRunGuard.snapshot().message.length == 300 },
            ManagedRunGuard.snapshot().toString(),
        )
        val messages = w.runEvents().map { it.data["message"] as String }
        assertTrue(messages.none { '\u001B' in it || '\r' in it }, messages.toString())
        w.release("release")
        w.finalEvent()
    }

    @Test
    fun `thousands of lines are merged to a few events, stage changes still sent at once`() {
        w.fakeOa(
            """
            |step 1 "Pre-flight Check"
            |step 2 "Download Latest Release (tarball)"
            |for i in ${'$'}(seq 1 $MANY); do echo "npm line ${'$'}i"; done
            |step 3 "Update Core Infrastructure"
            |for i in ${'$'}(seq 1 $MANY); do echo "npm line ${'$'}i"; done
            |step 4 "Update Platform"
            |step 5 "Update Optional Tools"
            |echo -e "${'$'}{GREEN}${'$'}{BOLD}  Update Complete!${'$'}{NC}"
            |exit 0
            """.trimMargin(),
        )
        w.runToEnd(w.runner())
        assertEquals("done", w.finalEvent().data["phase"])
        val events = w.runEvents()
        assertTrue(events.size < MANY / 4, "not merged: ${events.size} events for ${2 * MANY} lines")
        val stages = events.map { (it.data["stage"] as Number).toInt() }.distinct()
        assertEquals((0..5).toList(), stages)
    }

    // ── refusals by their FAIL sentence ─────────────────────────────────────

    @ParameterizedTest(name = "{1}")
    @MethodSource("refusals")
    fun `a FAIL sentence of the scripts ends the run failed with its reason and the explanation`(
        sentence: String,
        reason: UpdateReason,
    ) {
        w.fakeOa(
            """
            |step 1 "Pre-flight Check"
            |echo -e "${'$'}{RED}[FAIL]${'$'}{NC} $sentence"
            |echo "       Advice for the user."
            |echo ""
            |echo "Log saved to somewhere"
            |exit 1
            """.trimMargin(),
        )
        w.runToEnd(w.runner())
        val end = w.finalEvent()
        assertEquals("failed", end.data["phase"], end.toString())
        assertEquals(reason.name, end.data["reason"])
        assertEquals(1, end.data["exit"])
        assertEquals("$sentence\nAdvice for the user.", end.data["detail"])
        val last = RunOutcomeStore(w.lastRunFile).load().getValue("UPDATE")
        assertEquals(reason, last.reason)
        assertFalse(last.success)
        assertEquals("$sentence / Advice for the user.", last.detail)
    }

    /**
     * As the real update prints it: the pre-update backup fails in `[3/5]` (the updater goes on
     * without it), then a fatal failure in `[4/5]` stops the run. The reason and the detail come
     * from the fatal block only.
     */
    @ParameterizedTest(name = "{1}")
    @MethodSource("fatalAfterBackup")
    fun `a failed backup before a fatal failure ends with the fatal failure's reason and explanation`(
        fatal: String,
        reason: UpdateReason,
    ) {
        w.fakeOa(
            """
            |step 1 "Pre-flight Check"
            |step 2 "Download Latest Release (tarball)"
            |step 3 "Update Core Infrastructure"
            |echo -e "${'$'}{RED}[FAIL]${'$'}{NC} Failed to create archive: /sdcard/oa-backup.tar.gz"
            |echo "       Continuing without a backup."
            |echo -e "${'$'}{GREEN}[OK]${'$'}{NC}   Core files updated"
            |step 4 "Update Platform"
            |echo -e "${'$'}{RED}[FAIL]${'$'}{NC} $fatal"
            |echo "       openclaw doctor --fix may repair it."
            |exit 1
            """.trimMargin(),
        )
        w.runToEnd(w.runner())
        val end = w.finalEvent()
        assertEquals("failed", end.data["phase"], end.toString())
        assertEquals(reason.name, end.data["reason"])
        assertEquals(4, end.data["stage"])
        assertEquals("$fatal\nopenclaw doctor --fix may repair it.", end.data["detail"])
        val last = RunOutcomeStore(w.lastRunFile).load().getValue("UPDATE")
        assertEquals(reason, last.reason)
        assertEquals("$fatal / openclaw doctor --fix may repair it.", last.detail)
    }

    @Test
    fun `a FAIL line before the banner and exit 0 is done with one more warning`() {
        w.fakeOa(
            """
            |step 1 "Pre-flight Check"
            |echo -e "${'$'}{YELLOW}[WARN]${'$'}{NC} one"
            |step 3 "Update Core Infrastructure"
            |echo -e "${'$'}{RED}[FAIL]${'$'}{NC} Pre-update backup failed"
            |step 5 "Update Optional Tools"
            |echo -e "${'$'}{GREEN}${'$'}{BOLD}  Update Complete!${'$'}{NC}"
            |exit 0
            """.trimMargin(),
        )
        w.runToEnd(w.runner())
        val end = w.finalEvent()
        assertEquals("done", end.data["phase"])
        assertEquals(2, end.data["warnings"])
        assertEquals(2, RunOutcomeStore(w.lastRunFile).load().getValue("UPDATE").warnings)
    }

    @Test
    fun `exit 0 without the banner is failed UNKNOWN, never done`() {
        w.fakeOa("step 1 \"Pre-flight Check\"\nstep 5 \"Update Optional Tools\"\nexit 0")
        w.runToEnd(w.runner())
        val end = w.finalEvent()
        assertEquals("failed", end.data["phase"])
        assertEquals("UNKNOWN", end.data["reason"])
        assertEquals(0, end.data["exit"])
    }

    @Test
    fun `the banner with a non-zero exit is failed`() {
        w.fakeOa("step 5 \"Update Optional Tools\"\necho '  Update Complete!'\nexit 1")
        w.runToEnd(w.runner())
        assertEquals("failed", w.finalEvent().data["phase"])
    }

    // ── the result file ─────────────────────────────────────────────────────

    @Test
    fun `this run's result file wins over the FAIL sentence`() {
        w.fakeOa(
            """
            |printf 'schema=1\nrun=%s\nphase=preflight\nreason=no_space\nexit=1\n' "${'$'}(date +%s)" > "${'$'}R"
            |echo -e "${'$'}{RED}[FAIL]${'$'}{NC} The OpenClaw gateway is running."
            |exit 1
            """.trimMargin(),
        )
        w.runToEnd(w.runner())
        assertEquals("NO_SPACE", w.finalEvent().data["reason"])
    }

    @Test
    fun `a result file whose exit does not match the process is ignored`() {
        w.fakeOa(
            """
            |printf 'schema=1\nrun=%s\nphase=done\nexit=0\n' "${'$'}(date +%s)" > "${'$'}R"
            |echo -e "${'$'}{RED}[FAIL]${'$'}{NC} The OpenClaw gateway is running."
            |exit 1
            """.trimMargin(),
        )
        w.runToEnd(w.runner())
        assertEquals("GATEWAY_RUNNING", w.finalEvent().data["reason"])
    }

    @Test
    fun `a result file older than the run is ignored`() {
        w.fakeOa(
            """
            |printf 'schema=1\nrun=1\nphase=done\nexit=0\n' > "${'$'}R"
            |exit 0
            """.trimMargin(),
        )
        w.runToEnd(w.runner())
        assertEquals("UNKNOWN", w.finalEvent().data["reason"])
    }

    @Test
    fun `a result file without exit (written in progress, then killed) has no authority`() {
        // The script wrote its in-progress file and was killed before its trap could add exit=:
        // the file's reason must not win, the kill decides
        w.fakeOa(
            """
            |step 1 "Pre-flight Check"
            |printf 'schema=1\nrun=%s\nphase=preflight\nreason=no_space\n' "${'$'}(date +%s)" > "${'$'}R"
            |kill -KILL ${'$'}${'$'}
            """.trimMargin(),
        )
        w.runToEnd(w.runner())
        val end = w.finalEvent()
        assertEquals(137, end.data["exit"])
        assertEquals("INTERRUPTED", end.data["reason"], end.toString())
    }

    @Test
    fun `a result file without exit cannot spoil an end the output proves good`() {
        w.fakeOa(
            """
            |printf 'schema=1\nrun=%s\nphase=update\nhealthy=false\n' "${'$'}(date +%s)" > "${'$'}R"
            |${w.successBody}
            """.trimMargin(),
        )
        w.runToEnd(w.runner())
        assertEquals("done", w.finalEvent().data["phase"])
    }

    @Test
    fun `exit 2 without a result file is BUSY - the shared lock is held by another run`() {
        w.fakeOa(
            """
            |echo -e "${'$'}{RED}[FAIL]${'$'}{NC} Another install or update is running (pid 1234). Try again when it ends."
            |exit 2
            """.trimMargin(),
        )
        w.runToEnd(w.runner())
        val end = w.finalEvent()
        assertEquals("failed", end.data["phase"])
        assertEquals("BUSY", end.data["reason"])
        assertEquals(2, end.data["exit"])
        assertEquals(UpdateReason.BUSY, RunOutcomeStore(w.lastRunFile).load().getValue("UPDATE").reason)
    }

    @Test
    fun `the previous result file is removed before the script starts`() {
        w.resultFile.writeText("schema=1\nrun=9999999999\nphase=done\nexit=0\n")
        w.fakeOa("exit 0")
        w.runToEnd(w.runner())
        assertFalse(File(w.home, "result-at-start.log").exists(), "the script saw the previous run's result file")
        assertEquals("UNKNOWN", w.finalEvent().data["reason"], "a stale success file made an unknown end a success")
    }

    // ── environment ─────────────────────────────────────────────────────────

    @Test
    fun `the script gets OA_ASSUME_YES=1 and a run token that differs per run and is the guard's`() {
        w.fakeOa("hold release\n${w.successBody}")
        w.startInBackground(w.runner())
        assertTrue(TestWait.until { w.oaEnv().size == 1 })
        val token1 = ManagedRunGuard.runToken
        w.release("release")
        w.finalEvent()
        assertTrue(TestWait.until { RunLease.owner() == null })
        w.runProc.dir
            .listFiles()
            ?.forEach { it.deleteRecursively() }
        w.runToEnd(w.runner())
        val env = w.oaEnv()
        assertEquals(2, env.size, env.toString())
        assertEquals(listOf("1", "1"), env.map { it.second })
        assertEquals(token1, env[0].first)
        assertTrue(env.none { it.first == "<unset>" || it.first.isBlank() })
        assertTrue(env[0].first != env[1].first, "the token was reused: $env")
    }

    // ── the record and the end ──────────────────────────────────────────────

    /**
     * The END record throws an Error (the start record, written before the script, works — a
     * failing start record is covered in [ManagedRunnerStartRecordTest]).
     */
    @Test
    fun `a record that throws an Error never keeps the guard or the lease, and the end event is the real one`() {
        val broken = mockk<RunOutcomeStore>()
        every { broken.record(any(), any(), match { it is RunVerdict.Failure }) } returns Unit
        every {
            broken.record(any(), any(), match { it is RunVerdict.Success })
        } throws StackOverflowError("record broke")
        w.fakeOa(w.successBody)
        w.runToEnd(w.runner(outcomes = broken))
        val end = w.finalEvent()
        assertEquals("done", end.data["phase"])
        assertFalse(ManagedRunGuard.isRunning())
        assertTrue(TestWait.until { RunLease.owner() == null }, "lease kept: ${RunLease.owner()}")
        assertTrue(ManagedRunGuard.tryStart(RunKinds.UPDATE, 0L, "next"))
        ManagedRunGuard.finish(RunVerdict.Success(0, 0))
    }

    @Test
    fun `a record that throws an exception changes nothing either`() {
        val broken = mockk<RunOutcomeStore>()
        every { broken.record(any(), any(), any()) } throws IllegalStateException("disk")
        w.fakeOa("echo -e \"\${RED}[FAIL]\${NC} Checksum mismatch for x\"\nexit 1")
        w.runToEnd(w.runner(outcomes = broken))
        val end = w.finalEvent()
        assertEquals("failed", end.data["phase"])
        assertEquals("CHECKSUM", end.data["reason"])
        assertTrue(TestWait.until { RunLease.owner() == null })
    }

    @Test
    fun `an oa that cannot be started ends failed UNKNOWN and releases everything`() {
        w.fakeOa("exit 0")
        w.oa.writeText("#!/nonexistent/interpreter\n")
        w.runToEnd(w.runner())
        val end = w.finalEvent()
        assertEquals("failed", end.data["phase"])
        assertTrue(TestWait.until { RunLease.owner() == null })
        assertFalse(ManagedRunGuard.isRunning())
    }

    // ── shapes for the page ─────────────────────────────────────────────────

    @Test
    fun `lastRuns and gatewayStatus have the shapes the page reads`() {
        val runner = w.runner()
        assertEquals(emptyMap<String, Any?>(), runner.lastRuns())
        w.fakeOa(w.successBody)
        w.runToEnd(runner)
        w.finalEvent()
        assertEquals(setOf("at", "verdict", "exit", "warnings"), runner.lastRuns().getValue("UPDATE").keys)
        assertEquals("success", runner.lastRuns().getValue("UPDATE")["verdict"])

        assertTrue(TestWait.until { RunLease.owner() == null })
        w.runProc.dir
            .listFiles()
            ?.forEach { it.deleteRecursively() }
        w.fakeOa("echo -e \"\${RED}[FAIL]\${NC} Failed to download release\"\nexit 1")
        w.runToEnd(runner)
        w.finalEvent()
        val failed = runner.lastRuns().getValue("UPDATE")
        assertEquals(setOf("at", "verdict", "reason", "exit", "detail", "warnings"), failed.keys)
        assertEquals("DOWNLOAD", failed["reason"])
        val json = Gson().toJson(runner.lastRuns())
        assertTrue(json.startsWith("{\"UPDATE\":{"), json)

        assertEquals(mapOf("running" to false, "ours" to false, "pids" to emptyList<Int>()), runner.gatewayStatus())
    }

    companion object {
        const val MANY = 3000

        @JvmStatic
        fun refusals(): Stream<Arguments> =
            Stream.of(
                Arguments.of("The OpenClaw gateway is running.", UpdateReason.GATEWAY_RUNNING),
                Arguments.of(
                    "Not enough free storage to update: 2048 MB needed, 300 MB available.",
                    UpdateReason.NO_SPACE,
                ),
                Arguments.of(
                    "The updater downloaded an older copy of itself (cache). Nothing was changed.",
                    UpdateReason.CACHE_STALE,
                ),
                Arguments.of("The updater is out of date (a cached copy was downloaded).", UpdateReason.CACHE_STALE),
                Arguments.of(
                    "Your OpenClaw has saved chat history, and the check could not run.",
                    UpdateReason.SESSION_GUARD,
                ),
                Arguments.of("Failed to download release", UpdateReason.DOWNLOAD),
                Arguments.of("Checksum mismatch for node-v22.tar.xz", UpdateReason.CHECKSUM),
                Arguments.of("Could not install openclaw 2026.9.1", UpdateReason.INSTALL_FAILED),
                Arguments.of("The data migration did not finish within 2 minutes.", UpdateReason.MIGRATION_FAILED),
                Arguments.of(
                    "OpenClaw 2026.9.1 is installed, but it cannot use your existing data yet:",
                    UpdateReason.HEALTH_FAILED,
                ),
                Arguments.of("curl not found. Install it with: pkg install curl", UpdateReason.NOT_INSTALLED),
                Arguments.of("Failed to create temp directory", UpdateReason.UNKNOWN),
            )

        @JvmStatic
        fun fatalAfterBackup(): Stream<Arguments> =
            Stream.of(
                Arguments.of(
                    "OpenClaw 2026.9.1 is installed, but it cannot use your existing data yet:",
                    UpdateReason.HEALTH_FAILED,
                ),
                Arguments.of("The data migration did not succeed (exit code 3):", UpdateReason.MIGRATION_FAILED),
                Arguments.of(
                    "The data migration ran, but OpenClaw still cannot use your data:",
                    UpdateReason.MIGRATION_FAILED,
                ),
            )
    }
}
