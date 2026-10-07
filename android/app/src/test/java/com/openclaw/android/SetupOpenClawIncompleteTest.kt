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
 * `error=openclaw-incomplete` (post-setup.sh v1.2.3: OpenClaw was installed, installed once more,
 * and is still incomplete) through the native SETUP chain: the code table, the verdict, what
 * `getSetupResult` tells the resume screen, and a full managed run against a fake post-setup.sh run
 * by the real bash that prints the script's own `✗ … still incomplete … Restart the app to try
 * again.` line — the run ends failed/OPENCLAW_INCOMPLETE with that sentence as the output box
 * detail. Then the texts: in every locale, no claim that nothing was installed or changed.
 */
internal class SetupOpenClawIncompleteTest {
    @TempDir
    lateinit var root: File

    private lateinit var s: SetupWorld

    @BeforeEach
    fun setUp() {
        ManagedRunWorld.resetShared()
        s = SetupWorld(root)
    }

    @AfterEach
    fun tearDown() {
        val ended = s.cleanup()
        ManagedRunWorld.resetShared()
        assertTrue(ended, "a SETUP run outlived its test")
    }

    // ── the code ────────────────────────────────────────────────────────────

    @Test
    fun `the code maps to OPENCLAW_INCOMPLETE, a SETUP-only reason, and the script writes it in the full run`() {
        assertEquals(UpdateReason.OPENCLAW_INCOMPLETE, SetupReasons.codes["openclaw-incomplete"])
        assertEquals(UpdateReason.OPENCLAW_INCOMPLETE, SetupReasons.fromError("openclaw-incomplete"))
        assertTrue(UpdateReason.OPENCLAW_INCOMPLETE in UpdateReason.SETUP_ONLY)
        assertEquals(
            setOf(UpdateReason.NETWORK, UpdateReason.VERIFY_FAILED, UpdateReason.ENV, UpdateReason.OPENCLAW_INCOMPLETE),
            UpdateReason.SETUP_ONLY,
        )
        // Near spellings are not it
        for (code in listOf("openclaw_incomplete", "OPENCLAW-INCOMPLETE", "openclaw-incomplet", "incomplete")) {
            assertEquals(UpdateReason.UNKNOWN, SetupReasons.fromError(code), code)
        }
        val script = File("../../post-setup.sh").readText()
        val at = script.indexOf("OA_TOOLS_ERROR=openclaw-incomplete")
        assertTrue(at > 0, "post-setup.sh no longer writes the code")
        val before = script.substring(script.lastIndexOf('\n', script.lastIndexOf('\n', at) - 1), at)
        assertTrue(before.contains("still incomplete") && before.contains("Restart the app to try again."), before)
    }

    // ── the verdict ─────────────────────────────────────────────────────────

    @Test
    fun `the verdict of this run's file error=openclaw-incomplete exit=1 is a failure OPENCLAW_INCOMPLETE`() {
        val start = 1_000L
        val file = SetupResultFile(start, "4", "openclaw-incomplete", 1, SetupFacts())
        val v = SetupVerdict.decide(file, 1, start, false, listOf(SENTENCE), "")
        assertTrue(v is RunVerdict.Failure, v.toString())
        assertEquals(UpdateReason.OPENCLAW_INCOMPLETE, (v as RunVerdict.Failure).reason)
        // Even with the marker and stage done, the error decides
        val odd =
            SetupVerdict.decide(
                SetupResultFile(start, "done", "openclaw-incomplete", 0, SetupFacts()),
                0,
                start,
                true,
                emptyList(),
                "",
            )
        assertEquals(UpdateReason.OPENCLAW_INCOMPLETE, (odd as RunVerdict.Failure).reason)
    }

    @Test
    fun `getSetupResult gives the reason to the resume screen, not interrupted`() {
        s.result.writeText("schema=1\nrun=9\nstage=4\nerror=openclaw-incomplete\nexit=1\n")
        val state = ManagedSetup.resultState(s.home, ProcScan(s.w.runProc.dir), managed = true)
        assertEquals("OPENCLAW_INCOMPLETE", state["reason"], state.toString())
        assertEquals(false, state["interrupted"])
        assertEquals("4", state["stage"])
        assertEquals(1, (state["exit"] as Number).toInt())
    }

    // ── a full run ──────────────────────────────────────────────────────────

    @Test
    fun `a run that ends still incomplete is failed OPENCLAW_INCOMPLETE with the script's sentence as detail`() {
        s.fakeSetup(
            """
            |busy
            |result
            |stage 1 "Installing essential packages..."
            |stage 2 "Installing glibc runtime..."
            |stage 3 "Installing Node.js v22..."
            |stage 4 "Installing OpenClaw..."
            |echo -e "  ${'$'}{YELLOW}[WARN]${'$'}{NC} OpenClaw 2026.5.1 is incomplete after the install (files are missing or it does not start): installing it again"
            |ERR=openclaw-incomplete
            |echo -e "  ${'$'}{RED}✗${'$'}{NC} $SENTENCE"
            |result 1
            |exit 1
            """.trimMargin(),
        )
        s.w.runToEnd(s.runner(), RunKinds.SETUP)
        val end = s.w.finalEvent()
        assertEquals("failed", end.data["phase"], end.toString())
        assertEquals("OPENCLAW_INCOMPLETE", end.data["reason"], end.toString())
        assertEquals(RunKinds.SETUP, end.data["kind"])
        assertEquals(1, end.data["exit"])
        assertEquals(4, end.data["stage"])
        assertEquals(SENTENCE, end.data["detail"], "the output box is the ✗ line, nothing before it")
        assertFalse(s.marker.exists())
        val last = s.runner().lastRuns()[RunKinds.SETUP]!!
        assertEquals("OPENCLAW_INCOMPLETE", last["reason"])
        assertEquals("failure", last["verdict"])
        // The resume screen reads the same reason from the file the run left
        val state = ManagedSetup.resultState(s.home, ProcScan(s.w.runProc.dir), managed = true)
        assertEquals("OPENCLAW_INCOMPLETE", state["reason"], state.toString())
    }

    // ── the texts ───────────────────────────────────────────────────────────

    private fun text(locale: String): String {
        val src = File("../www/src/i18n/$locale.ts").readText()
        val m = Regex("""^\s*setup_reason_openclaw_incomplete:\s*(['"])(.*)\1,?\s*$""", RegexOption.MULTILINE).find(src)
        assertTrue(m != null, "setup_reason_openclaw_incomplete missing from $locale.ts")
        return m!!.groupValues[2]
    }

    @Test
    fun `the reason text is in every locale, translated, and the page maps the reason to it`() {
        val en = text("en")
        for (locale in listOf("en", "ko", "zh")) {
            assertTrue(text(locale).isNotBlank(), locale)
            if (locale != "en") assertTrue(text(locale) != en, "$locale is English")
        }
        val run = File("../www/src/lib/setupRun.ts").readText()
        assertTrue(run.contains("OPENCLAW_INCOMPLETE: 'setup_reason_openclaw_incomplete'"))
        assertFalse(
            Regex("""RESUMABLE_REASONS = \[[^]]*OPENCLAW_INCOMPLETE""").containsMatchIn(run),
            "it is a retry, not a resume",
        )
    }

    @Test
    fun `the reason text claims nothing about what was or was not installed or changed`() {
        val forbidden =
            mapOf(
                "en" to listOf("nothing", "not changed", "was not installed", "no changes", "unchanged"),
                "ko" to listOf("아무것도", "변경되지 않", "설치하지 않았", "바뀌지 않"),
                "zh" to listOf("未安装任何", "没有任何", "未更改", "没有更改", "保持不变"),
            )
        for ((locale, words) in forbidden) {
            val v = text(locale)
            words.forEach { assertFalse(v.contains(it, ignoreCase = true), "$locale claims '$it': $v") }
        }
        // It asks to try again and to report with the output, in every locale
        assertTrue(text("en").contains("Try again") && text("en").contains("report"))
        assertTrue(text("ko").contains("다시 시도") && text("ko").contains("제보"))
        assertTrue(text("zh").contains("重试") && text("zh").contains("反馈"))
    }

    private companion object {
        const val SENTENCE =
            "OpenClaw 2026.5.1 is still incomplete (files are missing or it does not start). " +
                "Restart the app to try again."
    }
}
