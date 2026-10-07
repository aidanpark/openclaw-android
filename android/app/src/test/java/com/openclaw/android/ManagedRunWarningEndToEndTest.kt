package com.openclaw.android

import com.google.gson.Gson
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
 * v1.2.2: a successful `oa --update` whose script printed CLAW's gateway warning (exit 0) ends `done`
 * with that sentence as `detail` — in the end event, `getRunState()`'s state, `last-run.conf` and
 * [ManagedRunner.lastRuns] — exactly as printed, without the marker or its color. The fake `oa`
 * prints it the way `platforms/openclaw/update.sh` does (`echo -e "${YELLOW}[WARN]${NC} …"`).
 */
internal class ManagedRunWarningEndToEndTest {
    @TempDir
    lateinit var root: File

    private lateinit var w: ManagedRunWorld

    private val sentence = GATEWAY_WARN_SENTENCE

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

    /** The five steps, [warnLines] inside `[4/5]`, the banner and exit 0. */
    private fun successWith(vararg warnLines: String): String =
        (
            listOf(
                "step 1 \"Pre-flight Check\"",
                "step 2 \"Download Latest Release (tarball)\"",
                "step 3 \"Update Core Infrastructure\"",
                "step 4 \"Update Platform\"",
            ) + warnLines.toList() +
                listOf(
                    "step 5 \"Update Optional Tools\"",
                    "echo \"\"",
                    "echo -e \"\${GREEN}\${BOLD}  Update Complete!\${NC}\"",
                    "exit 0",
                )
        ).joinToString("\n")

    /** The line as update.sh has it (the fake oa defines YELLOW and NC the same way). */
    private fun warnEcho(text: String) = "echo -e \"\${YELLOW}[WARN]\${NC} $text\""

    private fun nextRun() {
        assertTrue(TestWait.until { RunLease.owner() == null }, "lease not released")
        w.runProc.dir
            .listFiles()
            ?.forEach { it.deleteRecursively() }
    }

    @Test
    fun `the gateway warning of a successful update reaches the end event, the state, the record and lastRuns`() {
        w.fakeOa(successWith(warnEcho(sentence)))
        val runner = w.runner()
        w.runToEnd(runner)
        val end = w.finalEvent()

        assertEquals("done", end.data["phase"], w.runEvents().toString())
        assertEquals(0, end.data["exit"])
        assertNull(end.data["reason"])
        assertEquals(1, end.data["warnings"])
        assertEquals(sentence, end.data["detail"])
        val detail = end.data["detail"] as String
        assertFalse(detail.contains("[WARN]") || detail.contains('\u001B'), detail)
        assertEquals(ManagedRunWorld.STATE_KEYS, end.data.keys)
        // getRunState() is this snapshot
        assertEquals(end.data, ManagedRunner.stateEvent(ManagedRunGuard.snapshot()))
        // no event before the end carries a detail
        assertTrue(w.runEvents().dropLast(1).all { it.data["detail"] == "" }, w.runEvents().toString())

        val line = w.lastRunFile.readLines().single()
        assertTrue(
            Regex("""^UPDATE\|\d+\|success\|\|0\|1\|""" + Regex.escape(sentence) + "$").matches(line),
            line,
        )
        val last = runner.lastRuns().getValue("UPDATE")
        assertEquals(setOf("at", "verdict", "exit", "detail", "warnings"), last.keys)
        assertEquals("success", last["verdict"])
        assertEquals(0, last["exit"])
        assertEquals(1, last["warnings"])
        assertEquals(sentence, last["detail"])
        val json = Gson().toJson(runner.lastRuns())
        assertTrue(json.contains("\"detail\":\"$sentence\""), json)
    }

    @Test
    fun `a second successful run without warnings replaces the detail everywhere`() {
        val runner = w.runner()
        w.fakeOa(successWith(warnEcho(sentence)))
        w.runToEnd(runner)
        assertEquals(sentence, w.finalEvent().data["detail"])
        nextRun()

        w.events.clear()
        w.fakeOa(w.successBody)
        w.runToEnd(runner)
        val end = w.finalEvent()
        assertEquals("done", end.data["phase"])
        assertEquals(0, end.data["warnings"])
        assertEquals("", end.data["detail"])
        assertEquals("", ManagedRunGuard.snapshot().detail)
        assertTrue(
            w.lastRunFile
                .readLines()
                .single()
                .endsWith("|success||0|0|"),
            w.lastRunFile.readText(),
        )
        assertEquals(setOf("at", "verdict", "exit", "warnings"), runner.lastRuns().getValue("UPDATE").keys)
    }

    @Test
    fun `with this run's result file (no healthy key) the detail is still the printed warning`() {
        w.fakeOa(
            "printf 'schema=1\\nrun=%s\\nphase=done\\nchanged=true\\nexit=0\\n' \"\$(date +%s)\" > \"\$R\"\n" +
                successWith(warnEcho(sentence)),
        )
        w.runToEnd(w.runner())
        val end = w.finalEvent()
        assertEquals("done", end.data["phase"])
        assertEquals(sentence, end.data["detail"])
        assertEquals(sentence, RunOutcomeStore(w.lastRunFile).load().getValue("UPDATE").detail)
    }

    /**
     * Without any advice among them, the last warning is shown and all are counted. (When one carries
     * advice, that one wins — see the next test.)
     */
    @Test
    fun `when several ordinary warnings are printed the last one is shown and all are counted`() {
        w.fakeOa(
            successWith(
                warnEcho("code-server update failed (non-critical)"),
                warnEcho("clawdhub update failed (non-critical)"),
            ),
        )
        w.runToEnd(w.runner())
        val end = w.finalEvent()
        assertEquals("done", end.data["phase"])
        assertEquals(2, end.data["warnings"])
        assertEquals("clawdhub update failed (non-critical)", end.data["detail"])
    }

    /** The gateway advice is printed first, an ordinary warning after it: both are counted, the advice is shown. */
    @Test
    fun `a warning with advice is shown even when an ordinary warning follows it`() {
        w.fakeOa(successWith(warnEcho(sentence), warnEcho("clawdhub update failed (non-critical)")))
        w.runToEnd(w.runner())
        val end = w.finalEvent()
        assertEquals("done", end.data["phase"])
        assertEquals(2, end.data["warnings"])
        assertEquals(sentence, end.data["detail"])
    }

    /**
     * Prove-It for a suspected gap (not fixed here): the gateway warning asks the user to act, but
     * the scripts can print more `[WARN]` lines after it in the same run — `update.sh:316` "Could not
     * check clawdhub latest version" and step 5 of `update-core.sh` (`:670`, `:704`, `:763`, `:766`,
     * "… update failed (non-critical)"). A plain last-wins rule would show the non-critical line and hide
     * the advice again; `RunOutputWatcher` keeps the last warning that carries advice (ADVICE_PATTERN).
     */
    @Test
    fun `the gateway advice stays the detail when a non-critical warning follows it`() {
        w.fakeOa(
            listOf(
                "step 1 \"Pre-flight Check\"",
                "step 4 \"Update Platform\"",
                warnEcho(sentence),
                warnEcho("Could not check clawdhub latest version"),
                "step 5 \"Update Optional Tools\"",
                warnEcho("code-server update failed (non-critical)"),
                "echo -e \"\${GREEN}\${BOLD}  Update Complete!\${NC}\"",
                "exit 0",
            ).joinToString("\n"),
        )
        w.runToEnd(w.runner())
        val end = w.finalEvent()
        assertEquals("done", end.data["phase"])
        assertEquals(3, end.data["warnings"])
        assertEquals(sentence, end.data["detail"], "the user's action is hidden behind a non-critical warning")
    }

    @Test
    fun `a skipped FAIL step without a WARN line is one warning with no detail`() {
        w.fakeOa(successWith("echo -e \"\${RED}[FAIL]\${NC} Failed to create archive: /sdcard/oa-backup.tar.gz\""))
        w.runToEnd(w.runner())
        val end = w.finalEvent()
        assertEquals("done", end.data["phase"])
        assertEquals(1, end.data["warnings"])
        assertEquals("", end.data["detail"])
        assertFalse(
            w
                .runner()
                .lastRuns()
                .getValue("UPDATE")
                .containsKey("detail"),
        )
    }

    @Test
    fun `the warning does not save a run that failed - exit 1 shows the FAIL block, not the warning`() {
        w.fakeOa(
            listOf(
                "step 1 \"Pre-flight Check\"",
                warnEcho(sentence),
                "echo -e \"\${RED}[FAIL]\${NC} Failed to download release\"",
                "exit 1",
            ).joinToString("\n"),
        )
        w.runToEnd(w.runner())
        val end = w.finalEvent()
        assertEquals("failed", end.data["phase"])
        assertEquals("DOWNLOAD", end.data["reason"])
        assertEquals("Failed to download release", end.data["detail"])
        assertEquals(1, end.data["warnings"])
        assertFalse(
            RunOutcomeStore(w.lastRunFile)
                .load()
                .getValue("UPDATE")
                .detail
                .contains("gateway"),
        )
    }

    @Test
    fun `exit 0 without the banner stays UNKNOWN even with the warning, and shows no detail`() {
        w.fakeOa("step 1 \"Pre-flight Check\"\n${warnEcho(sentence)}\nexit 0")
        w.runToEnd(w.runner())
        val end = w.finalEvent()
        assertEquals("failed", end.data["phase"])
        assertEquals("UNKNOWN", end.data["reason"])
        assertEquals("", end.data["detail"])
    }

    companion object {
        /** `platforms/openclaw/update.sh` v1.2.2 ([UpdateWarnPhraseContractTest] pins it in the script). */
        const val GATEWAY_WARN_SENTENCE =
            "The gateway is using the OpenClaw state, so the data check was skipped. " +
                "Stop the gateway, then run: openclaw doctor"
    }
}
