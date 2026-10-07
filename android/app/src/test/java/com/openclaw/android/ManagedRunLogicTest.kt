package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The line-level pieces of a managed run: ANSI and `\r` cleanup, the `[N/5]` stage parser and its
 * cancel boundary, the `[FAIL]` collector's limits, the output watcher, the result-file parser's
 * limits and the reason codes.
 */
internal class ManagedRunLogicTest {
    private val esc = "\u001B"
    private val red = "$esc[0;31m"
    private val bold = "$esc[1m"
    private val nc = "$esc[0m"
    private val update = RunKinds.spec(RunKinds.UPDATE)!!

    // ── RunText ─────────────────────────────────────────────────────────────

    @Test
    fun `clean removes ANSI colors and keeps the last non-blank piece of a carriage-return line`() {
        assertEquals("[FAIL] x", RunText.clean("$red[FAIL]$nc x"))
        assertEquals("100%", RunText.clean("  1%\r 50%\r100%"))
        assertEquals("100%", RunText.clean("100%\r"))
        assertEquals("", RunText.clean("\r\r"))
        assertEquals("plain", RunText.clean("plain"))
    }

    // ── UpdateStageParser ───────────────────────────────────────────────────

    @Test
    fun `stage reads the update-core step line, with the bold color of step()`() {
        assertEquals(1, UpdateStageParser.stage("$bold[1/5] Pre-flight Check$nc"))
        assertEquals(2, UpdateStageParser.stage("[2/5] Download Latest Release (tarball)"))
        assertEquals(3, UpdateStageParser.stage("$bold[3/5] Update Core Infrastructure$nc"))
        assertEquals(5, UpdateStageParser.stage("[5/5] Update Optional Tools"))
    }

    @Test
    fun `stage ignores other counters, indented or quoted markers and malformed steps`() {
        listOf(
            "[0/5] x",
            "[6/5] x",
            "[1/7] Installing",
            "[3/3] three",
            " [3/5] indented",
            "echo [3/5] later in the line",
            "[3/5]no space",
            "[3/5]",
            "[10/5] two digits",
            "▸ [3/7] post-setup style",
        ).forEach { assertNull(UpdateStageParser.stage(it), it) }
    }

    @Test
    fun `the cancel boundary is between 2 and 3 of 5`() {
        assertTrue(update.cancelableAt(0))
        assertTrue(update.cancelableAt(1))
        assertTrue(update.cancelableAt(2))
        assertFalse(update.cancelableAt(3))
        assertFalse(update.cancelableAt(4))
        assertFalse(update.cancelableAt(5))
        assertEquals(listOf("oa", "--update"), update.command)
        assertEquals(5, update.stageTotal)
    }

    @Test
    fun `RunKinds knows only UPDATE`() {
        assertNotNull(RunKinds.spec("UPDATE"))
        listOf(null, "", "update", "SETUP", "TOOLS", "UPDATE ", "UPDATE\n", "\$(UPDATE)").forEach {
            assertNull(RunKinds.spec(it), "$it")
        }
        assertEquals(setOf("UPDATE"), BridgeGuard.runKinds)
    }

    // ── FailLineCollector ───────────────────────────────────────────────────

    private fun collect(vararg lines: String): List<String> =
        FailLineCollector().apply { lines.forEach(::accept) }.lines()

    @Test
    fun `no FAIL line collects nothing`() {
        assertEquals(emptyList<String>(), collect("[1/5] x", "  indented", "[WARN] y", "[OK] z"))
    }

    @Test
    fun `the FAIL marker and colors are dropped and the indented advice lines follow`() {
        val got =
            collect(
                "$red[FAIL]$nc The OpenClaw gateway is running.",
                "       Stop it first, then run oa --update again.",
                "\tTab-indented too",
                "Not indented: the explanation ended",
                "    never collected",
            )
        assertEquals(
            listOf(
                "The OpenClaw gateway is running.",
                "Stop it first, then run oa --update again.",
                "Tab-indented too",
            ),
            got,
        )
    }

    /**
     * The scripts stop right after a fatal `[FAIL]`; an earlier one belongs to a step they went on
     * without (the pre-update backup), so the LAST block is the cause.
     */
    @Test
    fun `only the last FAIL block is kept - a new FAIL line drops the block before it`() {
        assertEquals(listOf("second", "more"), collect("[FAIL] first", "[FAIL] second", "  more"))
    }

    @Test
    fun `the explanation of an earlier FAIL is dropped with it`() {
        val got =
            collect(
                "[FAIL] Failed to create archive: /sdcard/backup.tar.gz",
                "       Check the free space of the backup folder.",
                "[OK]   continuing without a backup",
                "[4/5] Update Platform",
                "$red[FAIL]$nc OpenClaw 2026.9.1 is installed, but it cannot use your existing data yet:",
                "    doctor: config schema v3 not readable",
                "    Run: openclaw doctor --fix",
            )
        assertEquals(
            listOf(
                "OpenClaw 2026.9.1 is installed, but it cannot use your existing data yet:",
                "doctor: config schema v3 not readable",
                "Run: openclaw doctor --fix",
            ),
            got,
        )
    }

    @Test
    fun `lines between two FAIL blocks are not carried into the later block`() {
        assertEquals(
            listOf("second"),
            collect("[FAIL] first", "  a", "not indented", "  stray indented", "[FAIL] second", "plain"),
        )
    }

    @Test
    fun `an earlier block that was cut at its limit does not shorten the later block`() {
        val many = (1..10).map { "    line $it" }.toTypedArray()
        val later = (1..10).map { "    next $it" }.toTypedArray()
        val got = collect("[FAIL] first", *many, "[FAIL] second", *later)
        assertEquals(listOf("second") + (1..FailLineCollector.MAX_FOLLOW).map { "next $it" }, got)
    }

    @Test
    fun `a later FAIL line without explanation replaces a long earlier block`() {
        assertEquals(listOf("last"), collect("[FAIL] first", "  a", "  b", "  c", "[FAIL] last"))
    }

    @Test
    fun `a blank line ends the explanation`() {
        assertEquals(listOf("first", "a"), collect("[FAIL] first", "  a", "", "  b"))
    }

    @Test
    fun `at most six explaining lines, each cut to 200 characters`() {
        val many = (1..10).map { "    line $it" }.toTypedArray()
        val got = collect("[FAIL] head", *many)
        assertEquals(1 + FailLineCollector.MAX_FOLLOW, got.size, got.toString())
        assertEquals("line 6", got.last())
        val long = collect("[FAIL] " + "x".repeat(500), "  " + "y".repeat(500))
        assertEquals(FailLineCollector.MAX_CHARS, long[0].length)
        assertEquals(FailLineCollector.MAX_CHARS, long[1].length)
    }

    @Test
    fun `carriage returns and ANSI inside the explanation are cleaned`() {
        val got = collect("[FAIL] head\r", "  progress 10%\r  progress 100%$nc")
        assertEquals(listOf("head", "progress 100%"), got)
    }

    @Test
    fun `an indented FAIL marker still counts`() {
        assertEquals(listOf("indented"), collect("   $red[FAIL]$nc indented"))
    }

    // ── RunOutputWatcher ────────────────────────────────────────────────────

    @Test
    fun `the watcher keeps the highest stage, counts WARN lines and sees the banner only as its own line`() {
        val w = RunOutputWatcher(update)
        listOf(
            "$bold[1/5] Pre-flight Check$nc",
            "$bold[3/5] Update Core Infrastructure$nc",
            "[2/5] stray later line",
            "$esc[1;33m[WARN]$nc Something skipped",
            "  [WARN] indented warn",
            "echo [WARN] not a warn",
            "Not the banner: Update Complete!",
        ).forEach { w.accept(it) }
        assertEquals(3, w.stage)
        assertEquals(2, w.warnings)
        assertFalse(w.sawComplete)
        assertEquals("  Update Complete!", w.accept("$esc[0;32m$bold  Update Complete!$nc"))
        assertTrue(w.sawComplete)
    }

    @Test
    fun `the watcher returns the cleaned line`() {
        val w = RunOutputWatcher(update)
        assertEquals("[FAIL] x", w.accept("$red[FAIL]$nc x"))
        assertEquals(listOf("x"), w.failLines())
    }

    // ── RunResultParser ─────────────────────────────────────────────────────

    @Test
    fun `parses the R3 keys and reads error= as the reason when there is no reason=`() {
        val r =
            RunResultParser.parse(
                "schema=1\nrun=12\nphase=done\nexit=0\nchanged=true\nhealthy=false\nbackup=/b\n",
            )!!
        assertEquals(RunResultFile(12, "done", null, 0, true, false, "/b"), r)
        assertEquals("busy", RunResultParser.parse("schema=1\nrun=1\nerror=busy\n")!!.reason)
        assertEquals("no_space", RunResultParser.parse("schema=1\nrun=1\nerror=busy\nreason=no_space\n")!!.reason)
        assertNull(RunResultParser.parse("schema=1\nrun=1\nreason=\n")!!.reason, "an empty reason is no reason")
        assertNull(RunResultParser.parse("schema=1\nrun=1\nhealthy=yes\n")!!.healthy, "only true/false are flags")
    }

    @Test
    fun `the parser rejects a whole file that is too big, repeats a key or lacks schema or run`() {
        val ok = "schema=1\nrun=1\nexit=0\n"
        assertNotNull(RunResultParser.parse(ok))
        assertNull(RunResultParser.parse(ok + "#".repeat(RunResultParser.MAX_CHARS)))
        assertNull(RunResultParser.parse(ok + "exit=1\n"), "a repeated key")
        assertNull(RunResultParser.parse("schema=1\nexit=0\n"))
        assertNull(RunResultParser.parse("schema=1\nrun=-5\n"))
        assertNull(RunResultParser.parse(""))
    }

    @Test
    fun `an over-long or control-character line is skipped, not trusted`() {
        val r = RunResultParser.parse("schema=1\nrun=1\nreason=" + "a".repeat(250) + "\nhealthy=false\u0007\n")!!
        assertNull(r.reason)
        assertNull(r.healthy)
        assertEquals(1, r.runEpoch)
    }

    // ── UpdateReasons.fromCode ──────────────────────────────────────────────

    @Test
    fun `reason codes map to reasons and anything else is UNKNOWN`() {
        val expected =
            mapOf(
                "gateway_running" to UpdateReason.GATEWAY_RUNNING,
                "no_space" to UpdateReason.NO_SPACE,
                "cache_old_updater" to UpdateReason.CACHE_STALE,
                "cache_node_downgrade" to UpdateReason.CACHE_STALE,
                "cache_openclaw_downgrade" to UpdateReason.CACHE_STALE,
                "cache_future_kind" to UpdateReason.CACHE_STALE,
                "session_guard" to UpdateReason.SESSION_GUARD,
                "download_failed" to UpdateReason.DOWNLOAD,
                "checksum" to UpdateReason.CHECKSUM,
                "npm_install" to UpdateReason.INSTALL_FAILED,
                "migration_failed" to UpdateReason.MIGRATION_FAILED,
                "migration_timeout" to UpdateReason.MIGRATION_FAILED,
                "health_failed" to UpdateReason.HEALTH_FAILED,
                "interrupted" to UpdateReason.INTERRUPTED,
                "cancelled" to UpdateReason.CANCELLED,
                "busy" to UpdateReason.BUSY,
                "no_platform" to UpdateReason.NOT_INSTALLED,
            )
        expected.forEach { (code, reason) -> assertEquals(reason, UpdateReasons.fromCode(code), code) }
        listOf("", "GATEWAY_RUNNING", "cache", "no space", "x".repeat(41), "busy\n", "../busy").forEach {
            assertEquals(UpdateReason.UNKNOWN, UpdateReasons.fromCode(it), it)
        }
    }

    @Test
    fun `the Node gate FAIL does not replace the Node step's own FAIL that caused it`() {
        val c = FailLineCollector()
        c.accept("[FAIL] Failed to download Node.js v24.21.0")
        c.accept("       Check your network connection and try again.")
        c.accept("[FAIL] Node.js v24.21.0 is required (found: v22.1.0) - update stopped before openclaw.")
        c.accept("       openclaw itself was not changed.")
        assertEquals(
            listOf("Failed to download Node.js v24.21.0", "Check your network connection and try again."),
            c.lines(),
        )
    }

    @Test
    fun `a Node gate FAIL alone is kept - nothing caused it that the app saw`() {
        val c = FailLineCollector()
        c.accept("[FAIL] Node.js v24.21.0 is required (found: v22.1.0) - update stopped before openclaw.")
        assertEquals(1, c.lines().size)
        assertEquals(UpdateReason.INSTALL_FAILED, UpdateReasons.fromFailLine(c.lines().first()))
    }

    @Test
    fun `a later non-gate FAIL still replaces the kept block`() {
        val c = FailLineCollector()
        c.accept("[FAIL] Failed to download Node.js v24.21.0")
        c.accept("[FAIL] Node.js v24.21.0 is required (found: v22.1.0)")
        c.accept("[FAIL] Could not install openclaw 2026.9.8")
        assertEquals(listOf("Could not install openclaw 2026.9.8"), c.lines())
    }
}
