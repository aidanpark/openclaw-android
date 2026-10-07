package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * [UpdateVerdict.decide]: success needs exit 0 in every case; this run's result file wins; without
 * one, exit 0 + the `Update Complete!` banner is success (a `[FAIL]` line before the banner is one
 * more warning), exit 0 without the banner is UNKNOWN — never success.
 */
internal class UpdateVerdictTest {
    private val start = 1_700_000_000L
    private val gatewayFail = listOf("The OpenClaw gateway is running.", "Stop it first")

    private fun result(
        run: Long = start,
        exit: Int? = 0,
        reason: String? = null,
        healthy: Boolean? = null,
        phase: String? = "done",
    ) = RunResultFile(run, phase, reason, exit, changed = null, healthy = healthy, backup = null)

    private fun decide(
        result: RunResultFile? = null,
        exit: Int = 0,
        fails: List<String> = emptyList(),
        banner: Boolean = false,
        warnings: Int = 0,
    ) = UpdateVerdict.decide(result, exit, fails, start, banner, warnings)

    private fun reasonOf(v: RunVerdict): UpdateReason? = (v as? RunVerdict.Failure)?.reason

    // ── no result file: the output decides ──────────────────────────────────

    @Test
    fun `exit 0 with the banner and no FAIL line is success with the counted warnings`() {
        assertEquals(RunVerdict.Success(0, 3), decide(exit = 0, banner = true, warnings = 3))
    }

    @Test
    fun `exit 0 with the banner after a FAIL line is success with one more warning`() {
        assertEquals(RunVerdict.Success(0, 3), decide(exit = 0, fails = gatewayFail, banner = true, warnings = 2))
    }

    @Test
    fun `exit 0 without the banner is UNKNOWN, never success, with or without a FAIL line`() {
        for (fails in listOf(emptyList(), gatewayFail)) {
            val v = decide(exit = 0, fails = fails, banner = false, warnings = 1)
            assertEquals(UpdateReason.UNKNOWN, reasonOf(v), "$fails -> $v")
            assertEquals(0, v.exit)
            assertEquals(1, v.warnings, "a failure keeps the warnings it saw and adds none")
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3, 127, 128, 193, 255, -1])
    fun `a non-zero exit is never success even with the banner`(exit: Int) {
        for (fails in listOf(emptyList(), gatewayFail)) {
            val v = decide(exit = exit, fails = fails, banner = true)
            assertTrue(v is RunVerdict.Failure, "exit $exit fails=$fails -> $v")
            assertEquals(exit, v.exit)
        }
    }

    @Test
    fun `a non-zero exit is classified by the sentence that heads the collected FAIL block`() {
        assertEquals(UpdateReason.GATEWAY_RUNNING, reasonOf(decide(exit = 1, fails = gatewayFail)))
        val v = decide(exit = 1, fails = gatewayFail) as RunVerdict.Failure
        assertEquals("The OpenClaw gateway is running.\nStop it first", v.detail)
    }

    /**
     * The collector keeps the LAST `[FAIL]` block, so a failed pre-update backup earlier in the run
     * (the updater went on without it) no longer hides the fatal failure that stopped it.
     */
    @Test
    fun `a failed backup before a fatal health or migration failure is classified by the fatal one`() {
        for ((fatal, reason) in listOf(
            "OpenClaw 2026.9.1 is installed, but it cannot use your existing data yet:" to UpdateReason.HEALTH_FAILED,
            "The data migration did not succeed (exit code 3):" to UpdateReason.MIGRATION_FAILED,
            "The data migration did not finish within 2 minutes." to UpdateReason.MIGRATION_FAILED,
        )) {
            val c = FailLineCollector()
            listOf(
                "\u001B[0;31m[FAIL]\u001B[0m Failed to create archive: /sdcard/oa-backup.tar.gz",
                "       The update goes on without a backup.",
                "[4/5] Update Platform",
                "\u001B[0;31m[FAIL]\u001B[0m $fatal",
                "       openclaw doctor said: schema v3",
            ).forEach(c::accept)
            val v = decide(exit = 1, fails = c.lines()) as RunVerdict.Failure
            assertEquals(reason, v.reason, fatal)
            assertEquals(
                "$fatal\nopenclaw doctor said: schema v3",
                v.detail,
                "the backup's lines leaked into the detail",
            )
        }
    }

    @Test
    fun `a non-zero exit with an unlisted FAIL sentence is UNKNOWN`() {
        assertEquals(
            UpdateReason.UNKNOWN,
            reasonOf(decide(exit = 1, fails = listOf("Failed to create temp directory"))),
        )
    }

    @ParameterizedTest
    @ValueSource(ints = [129, 130, 137, 143, 160, 192])
    fun `a signal exit without a FAIL line is INTERRUPTED`(exit: Int) {
        assertEquals(UpdateReason.INTERRUPTED, reasonOf(decide(exit = exit)))
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 3, 126, 128, 193, 255, -1])
    fun `a non-signal exit other than 2 without a FAIL line is UNKNOWN`(exit: Int) {
        assertEquals(UpdateReason.UNKNOWN, reasonOf(decide(exit = exit)))
    }

    @Test
    fun `a signal exit wins over an earlier FAIL line - it is the end, whatever was printed before`() {
        assertEquals(UpdateReason.INTERRUPTED, reasonOf(decide(exit = 143, fails = gatewayFail)))
        assertEquals(UpdateReason.INTERRUPTED, reasonOf(decide(exit = 137, fails = gatewayFail)))
    }

    /**
     * Every combination of exit {0,1,2,3,143,-1} × banner × FAIL line: success only for exit 0 +
     * banner, and exit 2 is BUSY whatever else was printed.
     */
    @Test
    fun `the full matrix of exit, banner and FAIL line`() {
        val cases =
            listOf(0, 1, 2, 3, 143, -1).flatMap { exit ->
                listOf(true, false).flatMap { banner ->
                    listOf(emptyList(), gatewayFail).map { Triple(exit, banner, it) }
                }
            }
        for ((exit, banner, fails) in cases) {
            val v = decide(exit = exit, fails = fails, banner = banner, warnings = 4)
            val label = "exit=$exit banner=$banner fails=$fails -> $v"
            assertEquals(exit == 0 && banner, v is RunVerdict.Success, label)
            val expectedWarnings = if (v is RunVerdict.Success) 4 + fails.size.coerceAtMost(1) else 4
            assertEquals(expectedWarnings, v.warnings, label)
            assertEquals(exit, v.exit, label)
            if (exit == 2) assertEquals(UpdateReason.BUSY, reasonOf(v), label)
        }
    }

    // ── exit 2: the shared run lock's "another run holds it" ────────────────

    @Test
    fun `exit 2 without this run's result file is BUSY, with or without a FAIL line or the banner`() {
        assertEquals(UpdateReason.BUSY, reasonOf(decide(exit = 2)))
        assertEquals(UpdateReason.BUSY, reasonOf(decide(exit = 2, fails = gatewayFail, banner = true)))
        // a file that is not this run's changes nothing
        val old = result(run = start - 1, exit = 2, reason = "no_space")
        assertEquals(UpdateReason.BUSY, reasonOf(decide(old, exit = 2)))
        assertEquals(UpdateReason.BUSY, reasonOf(decide(result(exit = 0), exit = 2)))
        assertEquals(UpdateReason.BUSY, reasonOf(decide(result(exit = null, reason = "no_space"), exit = 2)))
    }

    @Test
    fun `exit 2 with this run's result file follows the file`() {
        assertEquals(UpdateReason.NO_SPACE, reasonOf(decide(result(exit = 2, reason = "no_space"), exit = 2)))
        assertEquals(UpdateReason.UNKNOWN, reasonOf(decide(result(exit = 2), exit = 2)))
    }

    // ── result file of this run wins ────────────────────────────────────────

    @Test
    fun `this run's result file without a reason and exit 0 is success even without the banner`() {
        assertEquals(RunVerdict.Success(0, 0), decide(result(exit = 0), exit = 0, banner = false))
    }

    @Test
    fun `a result file never makes a non-zero exit a success`() {
        for (exit in listOf(1, 2, 143)) {
            val v = decide(result(exit = exit), exit = exit, banner = true)
            assertTrue(v is RunVerdict.Failure, "exit $exit -> $v")
        }
        assertTrue(decide(result(exit = null), exit = 1, banner = true) is RunVerdict.Failure)
    }

    /** A file without `exit=` is a run still going, or one killed before its trap ran: it proves nothing. */
    @Test
    fun `a result file without exit has no authority - the output decides as if there were none`() {
        val noExit = result(exit = null, reason = "no_space")
        assertEquals(UpdateReason.GATEWAY_RUNNING, reasonOf(decide(noExit, exit = 1, fails = gatewayFail)))
        assertEquals(UpdateReason.INTERRUPTED, reasonOf(decide(noExit, exit = 137)))
        // its success cannot make an unknown end a success
        assertEquals(UpdateReason.UNKNOWN, reasonOf(decide(result(exit = null), exit = 0, banner = false)))
        // and its failure cannot spoil an end the output proves good
        assertEquals(RunVerdict.Success(0, 0), decide(noExit, exit = 0, banner = true))
        val unhealthy = result(exit = null, healthy = false)
        assertEquals(RunVerdict.Success(0, 1), decide(unhealthy, exit = 0, fails = gatewayFail, banner = true))
        // a non-numeric exit is no exit either
        val garbled = RunResultParser.parse("schema=1\nrun=$start\nexit=zero\nreason=no_space\n")
        assertEquals(RunVerdict.Success(0, 0), decide(garbled, exit = 0, banner = true))
    }

    @Test
    fun `this run's reason code wins over the FAIL line and the exit code`() {
        val v = decide(result(exit = 1, reason = "no_space"), exit = 1, fails = gatewayFail)
        assertEquals(UpdateReason.NO_SPACE, reasonOf(v))
        // even with exit 0 and the banner a reported reason is a failure
        assertEquals(
            UpdateReason.SESSION_GUARD,
            reasonOf(decide(result(reason = "session_guard"), exit = 0, banner = true)),
        )
    }

    @Test
    fun `healthy=false in this run's result file is HEALTH_FAILED even with exit 0 and the banner`() {
        val v = decide(result(exit = 0, healthy = false), exit = 0, banner = true)
        assertEquals(UpdateReason.HEALTH_FAILED, reasonOf(v))
        assertEquals(RunVerdict.Success(0, 0), decide(result(exit = 0, healthy = true), exit = 0))
    }

    @Test
    fun `an unknown or malformed reason code is a failure (UNKNOWN), never a success`() {
        for (code in listOf("brand_new_reason", "BAD CODE", "x".repeat(41))) {
            assertEquals(UpdateReason.UNKNOWN, reasonOf(decide(result(reason = code), exit = 0, banner = true)), code)
        }
    }

    @Test
    fun `a result file without a reason and a non-zero exit is classified from the output`() {
        assertEquals(UpdateReason.GATEWAY_RUNNING, reasonOf(decide(result(exit = 1), exit = 1, fails = gatewayFail)))
        assertEquals(UpdateReason.INTERRUPTED, reasonOf(decide(result(exit = 143), exit = 143)))
    }

    @Test
    fun `a result file whose exit is not this process's is ignored - the output decides`() {
        // the file says success, the process said 1
        val v = decide(result(exit = 0), exit = 1, fails = gatewayFail)
        assertEquals(UpdateReason.GATEWAY_RUNNING, reasonOf(v))
        // the file says failure, the process ended 0 with the banner
        assertEquals(RunVerdict.Success(0, 0), decide(result(exit = 1, reason = "no_space"), exit = 0, banner = true))
        // the file says success with exit 0, the process ended 0 but no banner: still UNKNOWN
        assertEquals(UpdateReason.UNKNOWN, reasonOf(decide(result(exit = 3), exit = 0)))
    }

    @Test
    fun `a result file older than this run is ignored`() {
        val old = result(run = start - 1, exit = 0)
        assertEquals(UpdateReason.UNKNOWN, reasonOf(decide(old, exit = 0, banner = false)))
        assertEquals(RunVerdict.Success(0, 0), decide(result(run = start, exit = 0), exit = 0))
        assertEquals(RunVerdict.Success(0, 0), decide(result(run = start + 5, exit = 0), exit = 0))
        // an old file's reason is not this run's
        assertEquals(
            RunVerdict.Success(0, 0),
            decide(result(run = start - 1, reason = "no_space"), exit = 0, banner = true),
        )
    }

    @Test
    fun `with this run's result file a FAIL line before success adds no warning`() {
        assertEquals(RunVerdict.Success(0, 2), decide(result(exit = 0), exit = 0, fails = gatewayFail, warnings = 2))
    }

    @Test
    fun `a failure's detail is every collected line and its warnings are kept`() {
        val v = decide(exit = 1, fails = listOf("a", "b", "c"), warnings = 5) as RunVerdict.Failure
        assertEquals("a\nb\nc", v.detail)
        assertEquals(5, v.warnings)
    }

    // ── schema: the parser is the gate ──────────────────────────────────────

    @Test
    fun `a result file without schema=1 or with a non-numeric run is not read at all`() {
        assertEquals(null, RunResultParser.parse("schema=2\nrun=$start\nexit=0\n"))
        assertEquals(null, RunResultParser.parse("run=$start\nexit=0\n"))
        assertEquals(null, RunResultParser.parse("schema=1\nrun=soon\nexit=0\n"))
        val parsed = RunResultParser.parse("schema=1\nrun=$start\nexit=1\nreason=no_space\nhealthy=false\n")
        assertEquals(UpdateReason.NO_SPACE, reasonOf(decide(parsed, exit = 1)))
    }
}
