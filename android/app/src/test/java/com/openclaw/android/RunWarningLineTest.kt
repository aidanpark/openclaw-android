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
 * The last `[WARN]` sentence of a successful run (v1.2.2): kept by [RunOutputWatcher.lastWarning],
 * put in the success's `detail` by [UpdateVerdict.decide] only when there was at least one `[WARN]`,
 * shown by [ManagedRunGuard.finish] and kept by [RunOutcomeStore]. It never changes the verdict.
 */
internal class RunWarningLineTest {
    @TempDir
    lateinit var dir: File

    private val esc = "\u001B"
    private val yellow = "$esc[1;33m"
    private val red = "$esc[0;31m"
    private val nc = "$esc[0m"
    private val update = RunKinds.spec(RunKinds.UPDATE)!!
    private val start = 1_700_000_000L

    /** platforms/openclaw/update.sh (v1.2.2), printed with `echo -e "${YELLOW}[WARN]${NC} …"`. */
    private val gatewaySentence = ManagedRunWarningEndToEndTest.GATEWAY_WARN_SENTENCE

    private fun watch(vararg lines: String) = RunOutputWatcher(update).apply { lines.forEach { accept(it) } }

    @BeforeEach
    fun freeGuard() = ManagedRunWorld.resetShared()

    @AfterEach
    fun releaseGuard() = ManagedRunWorld.resetShared()

    // ── RunOutputWatcher.lastWarning ────────────────────────────────────────

    @Test
    fun `a run without any WARN line has an empty last warning`() {
        val w = watch("[1/5] Pre-flight Check", "$red[FAIL]$nc broken", "  Update Complete!")
        assertEquals("", w.lastWarning())
        assertEquals(0, w.warnings)
    }

    @Test
    fun `the colored WARN line as the script prints it gives the sentence without marker or colors`() {
        val w = watch("$yellow[WARN]$nc $gatewaySentence")
        assertEquals(gatewaySentence, w.lastWarning())
        assertEquals(1, w.warnings)
        assertFalse(w.lastWarning().contains("[WARN]"))
        assertFalse(w.lastWarning().contains(esc))
    }

    @Test
    fun `a later WARN line replaces the earlier one - the last one wins`() {
        val w = watch("$yellow[WARN]$nc first", "[3/5] Update Core Infrastructure", "$yellow[WARN]$nc second")
        assertEquals("second", w.lastWarning())
        assertEquals(2, w.warnings)
        w.accept("$yellow[WARN]$nc third")
        assertEquals("third", w.lastWarning())
    }

    @Test
    fun `an indented WARN line (the skills migration prints one) counts and is trimmed`() {
        val w = watch("  $yellow[WARN]$nc Failed to migrate my-skill   ")
        assertEquals("Failed to migrate my-skill", w.lastWarning())
        assertEquals(1, w.warnings)
    }

    @Test
    fun `a carriage-return redraw keeps only the WARN piece after the last return`() {
        assertEquals("redrawn", watch(" 10%\r 50%\r$yellow[WARN]$nc redrawn").lastWarning())
        assertEquals("trailing", watch("$yellow[WARN]$nc trailing\r").lastWarning())
        // the WARN piece was drawn over: it is not the line any more
        val over = watch("$yellow[WARN]$nc gone\r100%")
        assertEquals("", over.lastWarning())
        assertEquals(0, over.warnings)
    }

    @Test
    fun `a bare WARN marker is counted but keeps the sentence before it`() {
        val w = watch("$yellow[WARN]$nc kept", "$yellow[WARN]$nc", "[WARN]    ", "   [WARN]")
        assertEquals("kept", w.lastWarning())
        assertEquals(4, w.warnings, "warnings++ is unchanged for a bare marker")
        assertEquals("", watch("[WARN]").lastWarning())
    }

    @Test
    fun `a WARN in the middle of a line is neither counted nor kept`() {
        val w = watch("$yellow[WARN]$nc real", "npm said: [WARN] deprecated", "echo [WARN] x", "- [WARN] y")
        assertEquals("real", w.lastWarning())
        assertEquals(1, w.warnings)
    }

    @Test
    fun `a sentence of exactly 200 characters is kept whole, 201 is cut to 200`() {
        assertEquals(200, UpdateOutput.MAX_WARNING_CHARS)
        val s200 = "a".repeat(199) + "Z"
        assertEquals(s200, watch("$yellow[WARN]$nc $s200").lastWarning())
        val s201 = "b".repeat(200) + "Y"
        val kept = watch("$yellow[WARN]$nc $s201").lastWarning()
        assertEquals(200, kept.length)
        assertEquals("b".repeat(200), kept)
        // the cut is on the sentence, after the marker and the spaces are gone
        assertEquals("c".repeat(200), watch("   [WARN]      " + "c".repeat(250)).lastWarning())
    }

    @Test
    fun `WARN lines never enter the FAIL block and a FAIL line never becomes the last warning`() {
        val w = watch("$red[FAIL]$nc backup failed", "       Continuing.", "$yellow[WARN]$nc advice")
        assertEquals(listOf("backup failed", "Continuing."), w.failLines())
        assertEquals("advice", w.lastWarning())
        assertEquals("", watch("$red[FAIL]$nc only a failure").lastWarning())
    }

    // ── UpdateVerdict: the success's detail ─────────────────────────────────

    @Suppress("LongParameterList") // the inputs of UpdateVerdict.decide, named as in the tests above
    private fun decide(
        result: RunResultFile? = null,
        exit: Int = 0,
        fails: List<String> = emptyList(),
        banner: Boolean = true,
        warnings: Int = 0,
        lastWarning: String = "",
    ) = UpdateVerdict.decide(result, exit, fails, start, banner, warnings, lastWarning)

    private fun file(
        exit: Int? = 0,
        reason: String? = null,
        healthy: Boolean? = null,
    ) = RunResultFile(start, "done", reason, exit, changed = true, healthy = healthy, backup = null)

    @Test
    fun `a success with a warning carries the last WARN sentence as its detail`() {
        assertEquals(
            RunVerdict.Success(0, 1, gatewaySentence),
            decide(warnings = 1, lastWarning = gatewaySentence),
        )
    }

    @Test
    fun `a success without warnings has an empty detail even when a sentence is passed`() {
        assertEquals(RunVerdict.Success(0, 0, ""), decide(warnings = 0, lastWarning = gatewaySentence))
    }

    @Test
    fun `a success whose only warning is a skipped FAIL step counts one warning and has no detail`() {
        val v = decide(fails = listOf("Pre-update backup failed"), warnings = 0, lastWarning = "")
        assertEquals(RunVerdict.Success(0, 1, ""), v)
        // a stray sentence without a WARN line counted never becomes the detail either
        assertEquals(RunVerdict.Success(0, 1, ""), decide(fails = listOf("x"), warnings = 0, lastWarning = "stray"))
    }

    @Test
    fun `a skipped FAIL step and a WARN line - the detail is the WARN sentence, never the FAIL one`() {
        val v = decide(fails = listOf("Pre-update backup failed"), warnings = 1, lastWarning = gatewaySentence)
        assertEquals(RunVerdict.Success(0, 2, gatewaySentence), v)
    }

    @Test
    fun `a caller that passes no sentence (older call sites) still gets a success with an empty detail`() {
        assertEquals(RunVerdict.Success(0, 3, ""), UpdateVerdict.decide(null, 0, emptyList(), start, true, 3))
    }

    @Test
    fun `this run's result file (exit 0, no healthy key) still takes the detail from the output`() {
        // the gateway branch of update.sh writes no `healthy=` note: the file says success, the output says why
        val v = decide(file(healthy = null), banner = false, warnings = 1, lastWarning = gatewaySentence)
        assertEquals(RunVerdict.Success(0, 1, gatewaySentence), v)
        assertEquals(
            RunVerdict.Success(0, 1, gatewaySentence),
            decide(file(healthy = true), warnings = 1, lastWarning = gatewaySentence),
        )
        // with the file a skipped FAIL adds no warning, and the WARN sentence is still the detail
        assertEquals(
            RunVerdict.Success(0, 1, gatewaySentence),
            decide(file(), fails = listOf("backup failed"), warnings = 1, lastWarning = gatewaySentence),
        )
    }

    @Test
    fun `a WARN never turns a failure into a success, and a failure's detail is never the WARN sentence`() {
        val exit1 = decide(exit = 1, fails = listOf("Failed to download release"), warnings = 1, lastWarning = "w")
        assertEquals(RunVerdict.Failure(UpdateReason.DOWNLOAD, 1, "Failed to download release", 1), exit1)
        val exit1NoFail = decide(exit = 1, warnings = 2, lastWarning = "w") as RunVerdict.Failure
        assertEquals("", exit1NoFail.detail)
        val noBanner = decide(exit = 0, banner = false, warnings = 1, lastWarning = gatewaySentence)
        assertEquals(RunVerdict.Failure(UpdateReason.UNKNOWN, 0, "", 1), noBanner)
        val busy = decide(exit = 2, warnings = 1, lastWarning = "w") as RunVerdict.Failure
        assertEquals(UpdateReason.BUSY, busy.reason)
        val killed = decide(exit = 143, warnings = 1, lastWarning = "w") as RunVerdict.Failure
        assertEquals(UpdateReason.INTERRUPTED, killed.reason)
        assertEquals("", killed.detail)
        // this run's file with a reason or healthy=false: a failure, the sentence nowhere
        val unhealthy = decide(file(healthy = false), warnings = 1, lastWarning = "w") as RunVerdict.Failure
        assertEquals(UpdateReason.HEALTH_FAILED, unhealthy.reason)
        assertEquals("", unhealthy.detail)
        val reason = decide(file(reason = "no_space"), warnings = 1, lastWarning = "w") as RunVerdict.Failure
        assertEquals(UpdateReason.NO_SPACE, reason.reason)
        assertFalse(reason.detail.contains("w"))
    }

    /**
     * Every exit × banner × FAIL × result file: the verdict (kind, reason, exit, warnings) is the
     * same with or without the sentence.
     */
    @Test
    fun `the full matrix - the last warning changes only a success's detail, nothing else`() {
        val files = listOf(null, file(), file(exit = 1), file(reason = "no_space"), file(healthy = false))
        val failSets = listOf(emptyList(), listOf("The OpenClaw gateway is running."))
        val cases =
            listOf(0, 1, 2, 3, 143, -1).flatMap { exit ->
                listOf(true, false).flatMap { banner ->
                    failSets.flatMap { fails -> files.map { f -> Case(exit, banner, fails, f) } }
                }
            }
        for (c in cases) {
            val without = decide(c.file, c.exit, c.fails, c.banner, 2, "")
            val with = decide(c.file, c.exit, c.fails, c.banner, 2, "advice")
            val label = "$c -> $with"
            when (without) {
                is RunVerdict.Success -> {
                    assertEquals(without.copy(detail = "advice"), with, label)
                    assertEquals("", without.detail, label)
                }
                is RunVerdict.Failure -> assertEquals(without, with, label)
            }
        }
    }

    private data class Case(
        val exit: Int,
        val banner: Boolean,
        val fails: List<String>,
        val file: RunResultFile?,
    )

    // ── ManagedRunGuard.finish ──────────────────────────────────────────────

    @Test
    fun `finish shows a success's detail with phase done and no reason`() {
        assertTrue(ManagedRunGuard.tryStart(RunKinds.UPDATE, 1L, "t1"))
        ManagedRunGuard.finish(RunVerdict.Success(0, 1, gatewaySentence))
        val s = ManagedRunGuard.snapshot()
        assertEquals(ManagedRunGuard.DONE, s.phase)
        assertEquals(gatewaySentence, s.detail)
        assertEquals(1, s.warnings)
        assertNull(s.reason)
        assertEquals(gatewaySentence, ManagedRunner.stateEvent(s)["detail"])
    }

    @Test
    fun `a success without warnings after a failure shows an empty detail, not the failure's`() {
        assertTrue(ManagedRunGuard.tryStart(RunKinds.UPDATE, 1L, "t1"))
        ManagedRunGuard.finish(RunVerdict.Failure(UpdateReason.DOWNLOAD, 1, "Failed to download", 0))
        assertTrue(ManagedRunGuard.tryStart(RunKinds.UPDATE, 2L, "t2"))
        assertEquals("", ManagedRunGuard.snapshot().detail, "a new run starts without the old detail")
        ManagedRunGuard.finish(RunVerdict.Success(0, 0))
        assertEquals("", ManagedRunGuard.snapshot().detail)
    }

    // ── RunOutcomeStore: a success's detail ─────────────────────────────────

    private val lastRun get() = File(dir, "files/last-run.conf")

    private fun store() = RunOutcomeStore(lastRun)

    @Test
    fun `a success's detail is written on its line and read back`() {
        store().record(RunKinds.UPDATE, 100, RunVerdict.Success(0, 1, gatewaySentence))
        assertEquals(listOf("UPDATE|100|success||0|1|$gatewaySentence"), lastRun.readLines())
        assertEquals(RunOutcome("UPDATE", 100, true, null, 0, 1, gatewaySentence), store().load()["UPDATE"])
    }

    @Test
    fun `a success's detail is one line - newlines joined, control characters blanked, a pipe kept, cut to 200`() {
        val detail = "one\ntwo\tthree|four" + "x".repeat(300)
        store().record(RunKinds.UPDATE, 5, RunVerdict.Success(0, 2, detail))
        val o = store().load().getValue("UPDATE")
        assertTrue(o.success)
        assertEquals(RunOutcomeStore.MAX_DETAIL, o.detail.length)
        assertTrue(o.detail.startsWith("one / two three|four"), o.detail)
        assertTrue(o.detail.none { it.isISOControl() })
    }

    @Test
    fun `a later success without warnings replaces the recorded warning sentence`() {
        store().record(RunKinds.UPDATE, 1, RunVerdict.Success(0, 1, gatewaySentence))
        store().record(RunKinds.UPDATE, 2, RunVerdict.Success(0, 0))
        assertEquals(listOf("UPDATE|2|success||0|0|"), lastRun.readLines())
        assertEquals("", store().load().getValue("UPDATE").detail)
    }

    @Test
    fun `success lines with and without a detail are both read - older records stay valid`() {
        lastRun.parentFile.mkdirs()
        lastRun.writeText("UPDATE|7|success||0|0|\n")
        assertEquals(RunOutcome("UPDATE", 7, true, null, 0, 0, ""), store().load()["UPDATE"])
        lastRun.writeText("UPDATE|8|success||0|1|Stop the gateway | then run: openclaw doctor\n")
        assertEquals(
            RunOutcome("UPDATE", 8, true, null, 0, 1, "Stop the gateway | then run: openclaw doctor"),
            store().load()["UPDATE"],
        )
    }

    @Test
    fun `a success line whose detail breaks the limits is ignored`() {
        lastRun.parentFile.mkdirs()
        for (bad in listOf("d".repeat(201), "bell\u0007", "tab\there")) {
            lastRun.writeText("UPDATE|8|success||0|1|$bad\n")
            assertEquals(emptyMap<String, RunOutcome>(), store().load(), bad)
        }
        lastRun.writeText("UPDATE|8|success||0|1|" + "d".repeat(200) + "\n")
        assertEquals(
            200,
            store()
                .load()
                .getValue("UPDATE")
                .detail.length,
        )
    }
}
