package com.openclaw.android

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [SetupVerdict.decide] as a pure function: a SETUP run is a success ONLY with this run's result
 * file (`run` ≥ the start, `exit` present and equal to the process's exit code), `exit=0`,
 * `stage=done`, no `error` and the marker. Every input that misses one of those is a failure, and
 * the reason follows the file's `error=`, the exit code (2 = BUSY, 129..192 = a signal) or the
 * storage line printed before the lock.
 */
internal class SetupVerdictTest {
    private val start = 1_000L

    private fun file(
        run: Long = start,
        stage: String? = ManagedSetup.DONE_STAGE,
        error: String? = null,
        exit: Int? = 0,
        facts: SetupFacts = SetupFacts(),
    ) = SetupResultFile(run, stage, error, exit, facts)

    private fun decide(
        result: SetupResultFile?,
        exitCode: Int,
        marker: Boolean = true,
        failLines: List<String> = emptyList(),
        lastWarning: String = "",
    ) = SetupVerdict.decide(result, exitCode, start, marker, failLines, lastWarning)

    private fun assertFailure(
        verdict: RunVerdict,
        reason: UpdateReason,
        message: String = verdict.toString(),
    ): RunVerdict.Failure {
        assertTrue(verdict is RunVerdict.Failure, "not a failure: $message")
        assertEquals(reason, (verdict as RunVerdict.Failure).reason, message)
        return verdict
    }

    // ── success needs every condition ───────────────────────────────────────

    @Test
    fun `this run's file with exit 0, stage done, no error and the marker is a success`() {
        val v = decide(file(), 0)
        assertEquals(RunVerdict.Success(0, 0, "", SetupFacts()), v)
    }

    @Test
    fun `a file written later in the same run (run greater than the start) is still this run's`() {
        assertTrue(decide(file(run = start + 30), 0) is RunVerdict.Success)
    }

    @Test
    fun `each missing condition of a success is UNKNOWN, never a success`() {
        val cases =
            mapOf(
                "no file" to decide(null, 0),
                "stale file (run before the start)" to decide(file(run = start - 1), 0),
                "file without exit (the run never wrote its end)" to decide(file(exit = null), 0),
                "file exit differs from the process's" to decide(file(exit = 1), 0),
                "stage not done" to decide(file(stage = "7"), 0),
                "no stage" to decide(file(stage = null), 0),
                "no marker" to decide(file(), 0, marker = false),
            )
        cases.forEach { (case, verdict) -> assertFailure(verdict, UpdateReason.UNKNOWN, "$case: $verdict") }
    }

    @Test
    fun `an error in this run's file is never a success, even with exit 0, stage done and the marker`() {
        val v = decide(file(error = "deb"), 0)
        assertFailure(v, UpdateReason.NETWORK)
    }

    @Test
    fun `a process that ended 0 while the file says 1 is not this run's file and is UNKNOWN`() {
        assertFailure(decide(file(exit = 1, error = "npm-openclaw"), 0), UpdateReason.UNKNOWN)
    }

    @Test
    fun `a stale file that claimed success does not make a later run that wrote nothing a success`() {
        // the earlier run left exit=0 stage=done; this run ended 0 without writing (e.g. a script
        // that stopped at "already completed") — still not proven by this run
        val v = decide(file(run = start - 3600), 0, marker = true)
        assertFailure(v, UpdateReason.UNKNOWN)
    }

    // ── exit 2: another run holds the lock ──────────────────────────────────

    @Test
    fun `exit 2 without a result file is BUSY`() {
        assertFailure(decide(null, 2, marker = false), UpdateReason.BUSY)
    }

    @Test
    fun `exit 2 with the other run's file (older, or newer but without exit) is BUSY`() {
        assertFailure(decide(file(run = start - 10, exit = null, stage = "4"), 2), UpdateReason.BUSY)
        // the other run started after this one asked: its file has no exit yet
        assertFailure(decide(file(run = start + 1, exit = null, stage = "1"), 2), UpdateReason.BUSY)
        // the other run already ended (exit 1): its exit is not this process's 2
        assertFailure(decide(file(run = start + 1, exit = 1, error = "deb"), 2), UpdateReason.BUSY)
    }

    @Test
    fun `exit 2 is never read as no-space, even after a storage line`() {
        val lines = listOf("Not enough free storage to install OpenClaw: 2000 MB needed, 10 MB available.")
        assertFailure(decide(null, 2, failLines = lines), UpdateReason.BUSY)
    }

    @Test
    fun `a this-run file that itself says exit 2 (the script never writes it) is UNKNOWN, not BUSY`() {
        // characterization: the script normalizes every exit but 0/1/129/130/143 to 1
        assertFailure(decide(file(exit = 2, stage = "3"), 2), UpdateReason.UNKNOWN)
    }

    // ── signals ─────────────────────────────────────────────────────────────

    @Test
    fun `signal exits 129 to 192 without a file of this run are INTERRUPTED, the edges outside are not`() {
        for (code in listOf(129, 130, 137, 143, 192)) {
            assertFailure(decide(null, code), UpdateReason.INTERRUPTED, "exit $code")
            assertFailure(decide(file(run = start - 1), code), UpdateReason.INTERRUPTED, "stale, exit $code")
        }
        for (code in listOf(1, 3, 126, 127, 128, 193, 255)) {
            assertFailure(decide(null, code), UpdateReason.UNKNOWN, "exit $code")
        }
    }

    @Test
    fun `the trap's own file (error interrupted, exit 143) is INTERRUPTED`() {
        assertFailure(decide(file(stage = "4", error = "interrupted", exit = 143), 143), UpdateReason.INTERRUPTED)
    }

    @Test
    fun `a this-run file ending in a signal without an error is INTERRUPTED`() {
        assertFailure(decide(file(stage = "2", exit = 130), 130), UpdateReason.INTERRUPTED)
    }

    /**
     * The known window (flagged): `oa_full_finalize` writes the marker and `exit=0`, then resets
     * its traps before `OA_NO_ONBOARD` ends the script. A TERM in that gap ends the process with
     * 143 although the install is complete. The verdict is then INTERRUPTED (CANCELLED after the
     * user's cancel) — the safe direction (never a false success), but a false failure.
     */
    @Test
    fun `a signal after the final write is a failure although the file and the marker say complete`() {
        val v = decide(file(exit = 0), 143, marker = true)
        assertFailure(v, UpdateReason.INTERRUPTED)
    }

    // ── the storage refusal printed before the lock ─────────────────────────

    private val noSpaceBlock =
        listOf(
            "Not enough free storage to install OpenClaw: 2000 MB needed, 123 MB available.",
            "Nothing was changed. Free some space (clear other apps' caches, delete unused files) " +
                "and open the app again.",
        )

    @Test
    fun `exit 1, no result file and the storage FAIL block is NO_SPACE with the sizes`() {
        val v = assertFailure(decide(null, 1, marker = false, failLines = noSpaceBlock), UpdateReason.NO_SPACE)
        assertEquals(SetupFacts(needMb = 2000, haveMb = 123), v.setup)
        assertEquals(1, v.exit)
        assertEquals(noSpaceBlock.joinToString("\n"), v.detail)
    }

    @Test
    fun `the storage block with a stale file of an earlier run is still NO_SPACE`() {
        val v = decide(file(run = start - 50, error = "deb", exit = 1), 1, failLines = noSpaceBlock)
        assertEquals(SetupFacts(2000, 123), assertFailure(v, UpdateReason.NO_SPACE).setup)
    }

    @Test
    fun `the storage block is not read on exit 0, a signal or exit 2`() {
        assertFailure(decide(null, 0, failLines = noSpaceBlock), UpdateReason.UNKNOWN)
        assertFailure(decide(null, 143, failLines = noSpaceBlock), UpdateReason.INTERRUPTED)
        assertFailure(decide(null, 2, failLines = noSpaceBlock), UpdateReason.BUSY)
    }

    @Test
    fun `another FAIL block or none on exit 1 without a file is UNKNOWN`() {
        assertFailure(decide(null, 1), UpdateReason.UNKNOWN)
        assertFailure(decide(null, 1, failLines = listOf("Could not create the run lock")), UpdateReason.UNKNOWN)
        // the sizes are read from the block's first line only
        assertFailure(decide(null, 1, failLines = listOf("x") + noSpaceBlock), UpdateReason.UNKNOWN)
    }

    @Test
    fun `this run's error free-space carries need_mb and have_mb from the file`() {
        val facts = SetupFacts(needMb = 2000, haveMb = 512)
        val v = decide(file(stage = null, error = "free-space", exit = 1, facts = facts), 1, marker = false)
        assertEquals(facts, assertFailure(v, UpdateReason.NO_SPACE).setup)
    }

    // ── error codes ─────────────────────────────────────────────────────────

    @Test
    fun `every error code of this run's file gives its reason through the verdict`() {
        val table =
            mapOf(
                "free-space" to UpdateReason.NO_SPACE,
                "index-download" to UpdateReason.NETWORK,
                "node-download" to UpdateReason.NETWORK,
                "deb" to UpdateReason.NETWORK,
                "index-mismatch" to UpdateReason.VERIFY_FAILED,
                "index-noentry" to UpdateReason.VERIFY_FAILED,
                "index-1" to UpdateReason.VERIFY_FAILED,
                "index-2" to UpdateReason.VERIFY_FAILED,
                "index-3" to UpdateReason.VERIFY_FAILED,
                "index-4" to UpdateReason.VERIFY_FAILED,
                "index-10" to UpdateReason.VERIFY_FAILED,
                "index-11" to UpdateReason.VERIFY_FAILED,
                "index-12" to UpdateReason.VERIFY_FAILED,
                "node-checksum" to UpdateReason.VERIFY_FAILED,
                "glibc" to UpdateReason.INSTALL_FAILED,
                "git" to UpdateReason.INSTALL_FAILED,
                "git-wrapper" to UpdateReason.INSTALL_FAILED,
                "node-run" to UpdateReason.INSTALL_FAILED,
                "node-verify" to UpdateReason.INSTALL_FAILED,
                "npm-openclaw" to UpdateReason.INSTALL_FAILED,
                "openclaw-incomplete" to UpdateReason.OPENCLAW_INCOMPLETE,
                "env" to UpdateReason.ENV,
                "interrupted" to UpdateReason.INTERRUPTED,
                "unknown" to UpdateReason.UNKNOWN,
                "a-later-code" to UpdateReason.UNKNOWN,
                "usage" to UpdateReason.UNKNOWN,
            )
        table.forEach { (code, reason) ->
            assertFailure(decide(file(stage = "3", error = code, exit = 1), 1, marker = false), reason, code)
        }
    }

    @Test
    fun `codes that are not well formed are UNKNOWN`() {
        for (code in listOf("INDEX-1", "index-", "index-1000", "index-1a", "Deb", "a".repeat(41), "free space")) {
            assertEquals(UpdateReason.UNKNOWN, SetupReasons.fromError(code), code)
        }
    }

    @Test
    fun `the failure detail is the collected FAIL block, line by line`() {
        val lines = listOf("Could not verify the Termux package list.", "Check your network connection")
        val v = decide(file(stage = "1", error = "index-download", exit = 1), 1, marker = false, failLines = lines)
        assertEquals(
            "Could not verify the Termux package list.\nCheck your network connection",
            (v as RunVerdict.Failure).detail,
        )
    }

    // ── warnings ────────────────────────────────────────────────────────────

    @Test
    fun `a success with warn entries counts them and keeps the last WARN sentence`() {
        val facts = SetupFacts(warn = listOf("tools:tmux", "clawdhub"))
        val v = decide(file(facts = facts), 0, lastWarning = "clawdhub could not be installed")
        assertEquals(RunVerdict.Success(0, 2, "clawdhub could not be installed", facts), v)
    }

    @Test
    fun `a success without warn entries has no detail even if a WARN line was printed`() {
        val v = decide(file(), 0, lastWarning = "something printed")
        assertEquals(RunVerdict.Success(0, 0, "", SetupFacts()), v)
    }

    @Test
    fun `a failure of this run keeps the warn count and facts`() {
        val facts = SetupFacts(warn = listOf("oa-cli"))
        val v = decide(file(stage = "6", error = "env", exit = 1, facts = facts), 1, marker = false)
        val f = assertFailure(v, UpdateReason.ENV)
        assertEquals(1, f.warnings)
        assertEquals(facts, f.setup)
    }

    // ── CANCELLED comes from the guard (the user's cancel) ──────────────────

    @BeforeEach
    @AfterEach
    fun freeGuard() = ManagedRunWorld.resetShared()

    @Test
    fun `an interrupted end after the user's cancel settles as CANCELLED, without a cancel it stays INTERRUPTED`() {
        val interrupted = decide(file(stage = "2", error = "interrupted", exit = 143), 143)
        assertEquals(interrupted, ManagedRunGuard.settle(interrupted), "no cancel was requested")

        assertTrue(ManagedRunGuard.tryStart(RunKinds.SETUP, start, "t"))
        ManagedRunGuard.progress(2, ManagedSetup.STAGE_TOTAL, "▸ [2/7] glibc")
        assertTrue(ManagedRunGuard.requestCancel { true })
        val settled = ManagedRunGuard.settle(interrupted)
        assertFailure(settled, UpdateReason.CANCELLED)
        // a real failure is not turned into a cancel
        val network = decide(file(stage = "2", error = "deb", exit = 1), 1, marker = false)
        assertFailure(ManagedRunGuard.settle(network), UpdateReason.NETWORK)
        ManagedRunGuard.finish(settled)
        assertEquals(ManagedRunGuard.CANCELLED, ManagedRunGuard.snapshot().phase)
    }

    @Test
    fun `a SETUP run is cancelable at every stage, 7 included`() {
        val spec = RunKinds.spec(RunKinds.SETUP)!!
        (0..7).forEach { assertTrue(spec.cancelableAt(it), "stage $it") }
        assertTrue(ManagedRunGuard.tryStart(RunKinds.SETUP, start, "t"))
        ManagedRunGuard.progress(7, 7, "▸ [7/7] tools")
        assertTrue(ManagedRunGuard.snapshot().cancelable)
        assertTrue(ManagedRunGuard.requestCancel { true })
        ManagedRunGuard.finish(RunVerdict.Failure(UpdateReason.INTERRUPTED, 143, "", 0))
    }
}
