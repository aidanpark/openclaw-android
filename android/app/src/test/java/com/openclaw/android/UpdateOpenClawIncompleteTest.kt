package com.openclaw.android

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

/**
 * `oa --update` when OpenClaw was installed in this run but is incomplete
 * (platforms/openclaw/update.sh v1.2.3: `[FAIL] OpenClaw <ver> was installed but is incomplete (files
 * are missing or it does not start).`, `reason=npm_install changed=true`, exit 1, in step [4/5]),
 * end to end through the REAL [ManagedRunner] against a fake `oa` run by bash ([ManagedRunWorld]):
 * without a result file the FAIL sentence decides, with one its reason does — INSTALL_FAILED both
 * ways, the FAIL block as the detail. The status card then says the files may have been changed:
 * read from SettingsStatus.tsx (`stageLineKey`: a failure past LAST_UNTOUCHED_STAGE = 2), and
 * checked on the page harness of [StatusScreenBehaviorTest].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class UpdateOpenClawIncompleteTest {
    @TempDir
    lateinit var root: File

    private lateinit var w: ManagedRunWorld

    @BeforeEach
    fun setup() {
        ManagedRunWorld.resetShared()
        w = ManagedRunWorld(File(root, "w${System.nanoTime()}").apply { mkdirs() })
    }

    @AfterEach
    fun teardown() {
        val ended = w.cleanup()
        ManagedRunWorld.resetShared()
        assertTrue(ended, "a run outlived its test")
    }

    private fun incompleteRun(resultFile: Boolean): String =
        """
        |step 1 "Pre-flight Check"
        |step 2 "Download Latest Release (tarball)"
        |step 3 "Update Core Infrastructure"
        |step 4 "Update Platform"
        |echo "  Installing openclaw@2026.5.1..."
        |echo -e "${'$'}{RED}[FAIL]${'$'}{NC} $SENTENCE"
        |echo "       Your data is untouched. Run: oa --update"
        |${if (resultFile) RESULT_LINE else ":"}
        |exit 1
        """.trimMargin()

    @Test
    fun `the phrase is in the app's table as INSTALL_FAILED and the script prints it`() {
        assertEquals(
            UpdateReason.INSTALL_FAILED,
            UpdateOutput.failPhrases.first { it.first == "was installed but is incomplete" }.second,
        )
        assertEquals(UpdateReason.INSTALL_FAILED, UpdateReasons.fromFailLine(SENTENCE))
        assertEquals(UpdateReason.INSTALL_FAILED, UpdateReasons.fromCode("npm_install"))
        val script = File("../../platforms/openclaw/update.sh").readText()
        assertTrue(
            script.contains(
                "OpenClaw \$PIN_VER was installed but is incomplete (files are missing or it does not start).",
            ),
        )
        assertTrue(script.contains("oa_note reason=npm_install changed=true"))
    }

    @Test
    fun `without a result file the FAIL sentence ends the update failed INSTALL_FAILED, its block as detail`() {
        w.fakeOa(incompleteRun(resultFile = false))
        w.runToEnd(w.runner())
        val end = w.finalEvent()
        assertEquals("failed", end.data["phase"], end.toString())
        assertEquals("INSTALL_FAILED", end.data["reason"], end.toString())
        assertEquals(1, end.data["exit"])
        assertEquals(4, end.data["stage"])
        assertEquals("$SENTENCE\nYour data is untouched. Run: oa --update", end.data["detail"])
        val last = RunOutcomeStore(w.lastRunFile).load().getValue("UPDATE")
        assertEquals(UpdateReason.INSTALL_FAILED, last.reason)
        assertFalse(last.success)
    }

    @Test
    fun `with the script's result file (reason npm_install) the reason is the same and the detail too`() {
        w.fakeOa(incompleteRun(resultFile = true))
        w.runToEnd(w.runner())
        val end = w.finalEvent()
        assertEquals("failed", end.data["phase"], end.toString())
        assertEquals("INSTALL_FAILED", end.data["reason"], end.toString())
        assertEquals(4, end.data["stage"])
        assertEquals("$SENTENCE\nYour data is untouched. Run: oa --update", end.data["detail"])
    }

    // ── the status card ─────────────────────────────────────────────────────

    @Test
    fun `the status card's maybe-changed line comes from the stage, and step 4 is past the untouched stages`() {
        val status = File("../www/src/screens/SettingsStatus.tsx").readText()
        assertTrue(status.contains("const LAST_UNTOUCHED_STAGE = 2"))
        assertTrue(
            status.contains("return r.stage <= LAST_UNTOUCHED_STAGE ? 'status_unchanged' : 'status_maybe_changed'"),
        )
        val core = File("../../update-core.sh").readText()
        val step4 = core.indexOf("step 4 \"Update Platform\"")
        val call = core.indexOf("bash \"\$RELEASE_TMP/platforms/\$PLATFORM/update.sh\"")
        assertTrue(step4 in 0 until call, "the platform update no longer runs in step 4")
    }

    private val www = File("../www").absoluteFile
    private val work: File by lazy { Files.createTempDirectory("update-incomplete").toFile() }
    private val page by lazy {
        val prefix = StatusScreenBehaviorTest.HARNESS.substringBefore("const scenarios = {")
        StatusScreenBehaviorTest.runHarness(www, work, prefix + SCENARIOS)
    }

    @AfterAll
    fun cleanupWork() {
        work.deleteRecursively()
    }

    @Test
    fun `on the page the failure shows the install-failed reason, the may-have-changed line and the script's words`() {
        val r = page["incomplete"] ?: error("no scenario: ${page.keys}")
        assertFalse(r.containsKey("error"), r["error"].toString())
        @Suppress("UNCHECKED_CAST")
        val k = r["keys"] as List<String>
        assertTrue("status_maybe_changed" in k && "status_unchanged" !in k, k.toString())
        assertTrue(k.any { it.startsWith("status_reason_install") }, k.toString())
        @Suppress("UNCHECKED_CAST")
        val boxes = r["boxes"] as List<String>
        assertTrue(boxes.any { SENTENCE in it }, boxes.toString())
    }

    private companion object {
        const val SENTENCE =
            "OpenClaw 2026.5.1 was installed but is incomplete " +
                "(files are missing or it does not start)."
        const val RESULT_LINE =
            "printf 'schema=1\\nrun=%s\\nphase=platform\\nreason=npm_install\\nchanged=true\\nexit=1\\n' " +
                "\"\$(date +%s)\" > \"\$R\""
        val SCENARIOS =
            """
            |const scenarios = {
            |  incomplete() {
            |    const p = mount({ runState: busy(4) })
            |    const end = ended('failed', { stage: 4, reason: 'INSTALL_FAILED', exit: 1, detail: '$SENTENCE\nYour data is untouched. Run: oa --update' })
            |    p.native.runState = end
            |    p.emit('run_progress', end)
            |    return { keys: p.keys(), boxes: p.boxes() }
            |  },
            |}
            |const results = {}
            |for (const [name, run] of Object.entries(scenarios)) {
            |  try { results[name] = run() } catch (e) { results[name] = { error: String((e && e.stack) || e) } }
            |}
            |console.log(JSON.stringify(results))
            |
            """.trimMargin()
    }
}
