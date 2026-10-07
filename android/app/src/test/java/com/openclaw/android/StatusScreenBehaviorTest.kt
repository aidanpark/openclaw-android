package com.openclaw.android

import com.google.gson.Gson
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Settings → Install & Update (`SettingsStatus.tsx`) as it BEHAVES: the screen is bundled from its
 * source with the esbuild of `android/www/node_modules` and run in node, against
 *  - a stand-in for React's hooks (`useState`, `useRef`, `useCallback`, `useEffect`: the component is
 *    called again until its state settles, effects run after each call, cleanups when deps change),
 *  - the real `lib/bridge.ts` over a fake `window.OpenClaw` (the native side: `getRunState`,
 *    `getLastRun`, `getGatewayStatus`, and the calls the page makes),
 *  - `t()` that renders `{key}`, so the test sees which text the page chose,
 *  - fake `setInterval` and `Date.now`, so the pre-check poll and its 60 s limit run instantly.
 * Native events are delivered to the handler the page registered with `useNativeEvent`.
 *
 * Skipped (not failed) when node or `www/node_modules/esbuild` is missing; the structural checks of
 * [StatusScreenWwwContractTest] still run then.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class StatusScreenBehaviorTest {
    private val www = File("../www").absoluteFile
    private val work: File by lazy { Files.createTempDirectory("status-screen").toFile() }

    /** scenario name → what it observed; computed once for the class (one node run). */
    private val results: Map<String, Map<String, Any?>> by lazy { runHarness(www, work, HARNESS) }

    @AfterAll
    fun cleanup() {
        work.deleteRecursively()
    }

    private fun result(name: String): Map<String, Any?> {
        val r = results[name] ?: error("no scenario $name: ${results.keys}")
        assertFalse(r.containsKey("error"), "scenario $name failed: ${r["error"]}")
        return r
    }

    @Suppress("UNCHECKED_CAST")
    private fun keys(
        name: String,
        fact: String,
    ): List<String> = result(name)[fact] as List<String>

    @Suppress("UNCHECKED_CAST")
    private fun calls(name: String): List<List<Any?>> = result(name)["calls"] as List<List<Any?>>

    // ── a refusal ends the start like any end ───────────────────────────────

    @Test
    fun `a refusal after the consent replaces the preparing card with a failed result card`() {
        val r = "refusedNotInstalled"
        assertTrue("status_prechecking" in keys(r, "afterConfirm"), keys(r, "afterConfirm").toString())
        assertEquals(listOf(listOf("startRun", "UPDATE", false)), calls(r))
        val after = keys(r, "afterRefusal")
        assertFalse("status_prechecking" in after, "the preparing card stays after the refusal: $after")
        listOf("status_result_failed", "status_reason_not_installed", "status_unchanged", "status_retry").forEach {
            assertTrue(it in after, "$it missing after the refusal: $after")
        }
        assertFalse("status_maybe_changed" in after)
        assertFalse("status_output" in after, "a refusal shows script output")
    }

    @Test
    fun `while preparing nothing else can be started and the buttons come back after the refusal`() {
        val r = "refusedNotInstalled"
        assertEquals(true, result(r)["terminalDisabledWhilePreparing"])
        assertEquals(false, result(r)["terminalDisabledAfterRefusal"])
    }

    @Test
    fun `after a refusal Try again asks again and starts again`() {
        val r = "refusedThenRetry"
        assertTrue("status_confirm_title" in keys(r, "afterRetry"))
        assertEquals(2, calls(r).count { it.first() == "startRun" }, calls(r).toString())
    }

    @Test
    fun `a refusal native could not name says the update could not be started`() {
        val after = keys("refusedUnknown", "afterRefusal")
        assertTrue("status_refused_unknown" in after, after.toString())
        assertFalse("status_reason_unknown" in after, after.toString())
        assertTrue("status_unchanged" in after, after.toString())
    }

    @Test
    fun `a BUSY refusal of a second start keeps showing the run that is going`() {
        val after = keys("refusedWhileBusy", "afterRefusal")
        assertTrue("status_stage" in after, after.toString())
        assertFalse("status_result_failed" in after, after.toString())
    }

    // ── the result card's lines ─────────────────────────────────────────────

    @Test
    fun `a failure up to stage 2 says nothing was changed, from stage 3 that files may have changed`() {
        assertTrue("status_unchanged" in keys("stageLines", "download2"))
        assertFalse("status_maybe_changed" in keys("stageLines", "download2"))
        assertTrue("status_maybe_changed" in keys("stageLines", "install3"))
        assertFalse("status_unchanged" in keys("stageLines", "install3"))
    }

    @Test
    fun `a failure event without a stage gets no stage line`() {
        val k = keys("stageLines", "noStage")
        assertTrue("status_result_failed" in k, k.toString())
        assertFalse("status_unchanged" in k || "status_maybe_changed" in k, k.toString())
    }

    @Test
    fun `a migration or health failure gets the doctor hint and no stage line`() {
        for (fact in listOf("migration4", "health5")) {
            val k = keys("stageLines", fact)
            assertTrue("status_hint_doctor" in k, "$fact: $k")
            assertFalse("status_unchanged" in k || "status_maybe_changed" in k, "$fact: $k")
        }
    }

    @Test
    fun `script output is shown under a failure except for the gateway reasons`() {
        assertTrue("status_output" in keys("stageLines", "download2"))
        assertFalse("status_output" in keys("stageLines", "gatewayRunning"))
        assertFalse("status_output" in keys("stageLines", "gatewayStopFailed"))
        assertTrue("status_output" in keys("stageLines", "install3"))
    }

    // ── preparing ───────────────────────────────────────────────────────────

    @Test
    fun `preparing gives the page back after 60 s without an answer, not before`() {
        val r = "precheckGivesUp"
        assertTrue("status_prechecking" in keys(r, "at59s"), keys(r, "at59s").toString())
        assertFalse("status_prechecking" in keys(r, "at60s"), keys(r, "at60s").toString())
        assertTrue("status_update_btn" in keys(r, "at60s"))
        assertEquals(false, result(r)["terminalDisabledAt60s"])
    }

    @Test
    fun `preparing shows the run as soon as native is busy, even if its first event was lost`() {
        val k = keys("precheckSeesRun", "afterPoll")
        assertTrue("status_stage" in k, k.toString())
        assertFalse("status_prechecking" in k, k.toString())
    }

    @Test
    fun `preparing comes before the consent and the result in the render order`() {
        // a result card from before and a consent card cannot hide the preparing card
        val k = keys("precheckOverResult", "afterConfirm")
        assertTrue("status_prechecking" in k, k.toString())
        assertFalse("status_result_failed" in k || "status_confirm_title" in k, k.toString())
    }

    @Test
    fun `the gateway stop button is disabled while preparing`() {
        assertEquals(true, result("precheckGateway")["stopDisabledWhilePreparing"])
        assertEquals(false, result("precheckGateway")["stopDisabledBefore"])
    }

    // ── the last-update line ────────────────────────────────────────────────

    @Test
    fun `shown again, the page reads the gateway and the last update again`() {
        val before = keys("visibleAgain", "before")
        assertTrue("status_gw_not_running" in before && "status_last_none" in before, before.toString())
        val after = keys("visibleAgain", "after")
        assertTrue("status_gw_running" in after && "status_last_success" in after, after.toString())
    }

    @Test
    fun `shown again during a run, the run stays and only the gateway is read again`() {
        val k = keys("visibleAgain", "busy")
        assertTrue("status_updating" in k && "status_gw_running" in k, k.toString())
        assertFalse("status_reason_migration_failed" in k || "status_last_failure" in k, k.toString())
        @Suppress("UNCHECKED_CAST")
        val calls = result("visibleAgain")["busyCalls"] as List<Any?>
        assertTrue(calls.isEmpty(), calls.toString())
    }

    @Test
    fun `while a run goes on the last-update line says updating and hides the start record's reason`() {
        val k = keys("lastUpdateLine", "busy")
        assertTrue("status_updating" in k, k.toString())
        assertFalse("status_last_failure" in k, k.toString())
        assertFalse("status_reason_interrupted" in k, k.toString())
        assertFalse("status_hint_doctor" in k, k.toString())
    }

    @Test
    fun `while preparing the last-update line hides the previous failure too`() {
        val k = keys("lastUpdateLine", "preparing")
        assertFalse("status_reason_migration_failed" in k || "status_hint_doctor" in k, k.toString())
    }

    @Test
    fun `when idle the last-update line shows the recorded failure with its reason and the doctor hint`() {
        val k = keys("lastUpdateLine", "idle")
        listOf("status_last_failure", "status_reason_migration_failed", "status_hint_doctor").forEach {
            assertTrue(it in k, "$it missing: $k")
        }
    }

    // ── the consent card ────────────────────────────────────────────────────

    @Test
    fun `the consent for our running gateway says it will be stopped and asks native to stop it`() {
        val k = keys("consentOurs", "card")
        assertTrue("status_confirm_gateway" in k, k.toString())
        assertFalse("status_gw_not_ours" in k, k.toString())
        assertEquals(listOf(listOf("startRun", "UPDATE", true)), calls("consentOurs"))
    }

    @Test
    fun `the consent for a gateway the app does not own only says the app cannot stop it`() {
        val k = keys("consentNotOurs", "card")
        assertTrue("status_gw_not_ours" in k, k.toString())
        assertFalse("status_confirm_gateway" in k, k.toString())
        assertEquals(listOf(listOf("startRun", "UPDATE", true)), calls("consentNotOurs"))
    }

    @Test
    fun `a GATEWAY_RUNNING result for a gateway the app does not own adds that it cannot stop it`() {
        val k = keys("consentNotOurs", "afterRefusal")
        assertTrue("status_reason_gateway_running" in k, k.toString())
        assertTrue(k.count { it == "status_gw_not_ours" } >= 2, "result card lacks the not-ours line: $k")
    }

    @Test
    fun `the consent confirmed twice starts once`() {
        assertEquals(1, calls("doubleConfirm").count { it.first() == "startRun" })
    }

    // ── the repair hint: state ownership vs. doctor (vc23) ──────────────────

    /**
     * The expected hint, written out per case rather than recomputed from the page's pattern: only
     * MIGRATION_FAILED and HEALTH_FAILED get one; their detail naming state ownership, a
     * `StateOwnerContention` or a busy database (any case) points at stopping the gateway, any
     * other detail (none, empty, a near miss) at `openclaw doctor --fix`.
     */
    private val stateBusyDetails =
        setOf("ownership", "contention", "busyDb", "upperBusy", "lowerContention", "titleOwnership", "deep")
    private val doctorDetails = setOf("none", "empty", "other", "nearBusy", "nearOwnership", "nearContention")
    private val hintReasons = setOf("MIGRATION_FAILED", "HEALTH_FAILED")

    @Suppress("UNCHECKED_CAST")
    private fun hintTable(): Map<String, Map<String, String>> =
        result("hintTable")["table"] as Map<String, Map<String, String>>

    private fun expectedHint(
        reason: String,
        label: String,
    ): String =
        when {
            reason !in hintReasons -> "none"
            label in stateBusyDetails -> "state_busy"
            else -> "doctor"
        }

    @Test
    fun `the hint table covers every reason and detail case`() {
        val reasons =
            listOf(
                "MIGRATION_FAILED",
                "HEALTH_FAILED",
                "DOWNLOAD",
                "INSTALL_FAILED",
                "NO_SPACE",
                "INTERRUPTED",
                "CANCELLED",
                "UNKNOWN",
                "null",
            )
        val expected = reasons.flatMap { r -> (stateBusyDetails + doctorDetails).map { "$r|$it" } }.toSet()
        assertEquals(expected, hintTable().keys)
    }

    @Test
    fun `the result card's hint follows the reason x detail truth table`() {
        val wrong =
            hintTable().filter { (k, v) ->
                v["card"] != expectedHint(k.substringBefore('|'), k.substringAfter('|'))
            }
        assertEquals(emptyMap<String, Any?>(), wrong.mapValues { it.value["card"] })
    }

    @Test
    fun `the last-update line's hint follows the same truth table`() {
        val wrong =
            hintTable().filter { (k, v) ->
                v["last"] != expectedHint(k.substringBefore('|'), k.substringAfter('|'))
            }
        assertEquals(emptyMap<String, Any?>(), wrong.mapValues { it.value["last"] })
    }

    @Test
    fun `the state hint is chosen from the whole detail although the card shows only its first 6 lines`() {
        val text = result("hintTable")["deepText"] as String
        assertTrue(text.contains("{status_hint_state_busy}"), text)
        assertTrue(text.contains("l6") && !text.contains("l7"), "the output box is no longer cut to 6 lines: $text")
        assertFalse(text.contains("database is busy"), text)
    }

    @Suppress("UNCHECKED_CAST")
    private fun stateBusy(lang: String): Map<String, Any?> = result("stateBusyText")[lang] as Map<String, Any?>

    @Test
    fun `a state ownership failure shows the stop-the-gateway hint and never advises oa --restore`() {
        for (lang in listOf("en", "ko", "zh")) {
            val r = stateBusy(lang)
            val busy = r["busy"] as String
            val doctor = r["doctor"] as String
            for (where in listOf("card", "last")) {
                val text = r["${where}Text"] as String

                @Suppress("UNCHECKED_CAST")
                val keys = r["${where}Keys"] as List<String>
                assertTrue("status_hint_state_busy" in keys, "$lang $where: $keys")
                assertFalse("status_hint_doctor" in keys, "$lang $where: $keys")
                assertTrue(text.contains(busy), "$lang $where: the state hint's text is not shown: $text")
                assertFalse(text.contains(doctor), "$lang $where: the doctor hint's text is shown: $text")
                // the one mention left is the state hint's own "not needed"
                assertFalse(text.replace(busy, "").contains("oa --restore"), "$lang $where: $text")
            }
        }
    }

    @Test
    fun `a general health failure still shows the doctor hint with oa --restore, in every language`() {
        for (lang in listOf("en", "ko", "zh")) {
            val r = stateBusy(lang)
            val text = r["generalText"] as String
            assertTrue(text.contains(r["doctor"] as String), "$lang: $text")
            assertTrue(text.contains("oa --restore"), "$lang: $text")
            assertFalse(text.contains(r["busy"] as String), "$lang: $text")
        }
    }

    // ── a cancelled last run ────────────────────────────────────────────────

    @Test
    fun `a cancelled last run reads cancelled with its time and has no reason line or hint`() {
        for (fact in listOf("idle", "detailIdle")) {
            val k = keys("cancelledLast", fact)
            assertTrue("status_last_cancelled" in k, "$fact: $k")
            listOf(
                "status_last_failure",
                "status_reason_cancelled",
                "status_reason_unknown",
                "status_hint_doctor",
                "status_hint_state_busy",
            ).forEach { assertFalse(it in k, "$fact: $it shown: $k") }
        }
        val ko = result("cancelledLast")["koText"] as String
        assertTrue(Regex("""\{status_last_cancelled}취소됨 · \d""").containsMatchIn(ko), ko)
        val en = result("cancelledLast")["enText"] as String
        assertTrue(Regex("""\{status_last_cancelled}Cancelled · \d""").containsMatchIn(en), en)
    }

    @Test
    fun `a cancelled result card says cancelled with no reason line, hint or stage line`() {
        val k = keys("cancelledLast", "card")
        assertTrue("status_result_cancelled" in k, k.toString())
        listOf(
            "status_result_failed",
            "status_reason_cancelled",
            "status_hint_doctor",
            "status_unchanged",
            "status_maybe_changed",
            "status_output",
        ).forEach { assertFalse(it in k, "$it shown: $k") }
        assertTrue("status_last_cancelled" in k, k.toString())
    }

    // ── the result card and the last-update line do not repeat each other ───

    @Test
    fun `while the result card is shown the last-update line hides its reason and hint, and shows them once closed`() {
        val before = keys("resultHidesLast", "before")
        assertTrue("status_reason_migration_failed" in before && "status_hint_doctor" in before, before.toString())
        val during = keys("resultHidesLast", "during")
        assertTrue("status_result_failed" in during && "status_reason_download" in during, during.toString())
        assertTrue("status_last_failure" in during, "the one-line summary stays: $during")
        assertFalse("status_reason_migration_failed" in during, during.toString())
        assertFalse("status_hint_doctor" in during, during.toString())
        val after = keys("resultHidesLast", "after")
        assertFalse("status_result_failed" in after, after.toString())
        assertTrue("status_reason_migration_failed" in after && "status_hint_doctor" in after, after.toString())
    }

    @Test
    fun `the same failed run shows its reason and hint once - on the card - and on the line after closing`() {
        for (fact in listOf("sameDuring", "sameAfter", "reopened")) {
            val k = keys("resultHidesLast", fact)
            assertEquals(1, k.count { it == "status_reason_health_failed" }, "$fact: $k")
            assertEquals(1, k.count { it == "status_hint_state_busy" }, "$fact: $k")
            assertFalse("status_hint_doctor" in k, "$fact: $k")
        }
        assertTrue("status_result_failed" in keys("resultHidesLast", "sameDuring"))
        assertTrue("status_result_failed" in keys("resultHidesLast", "reopened"), "a recreated page lost the card")
        assertFalse("status_result_failed" in keys("resultHidesLast", "sameAfter"))
    }

    @Test
    fun `when Try again replaces the card with the consent the last-update reason comes back`() {
        val k = keys("resultHidesLast", "consent")
        assertTrue("status_confirm_title" in k, k.toString())
        assertFalse("status_result_failed" in k, k.toString())
        assertTrue("status_reason_migration_failed" in k, k.toString())
    }

    @Test
    fun `while a run goes on or is being prepared the state hint of the last run is hidden too`() {
        for (fact in listOf("running", "preparing")) {
            val k = keys("busyHidesStateHint", fact)
            assertFalse("status_hint_state_busy" in k || "status_reason_health_failed" in k, "$fact: $k")
        }
        val idle = keys("busyHidesStateHint", "idle")
        assertTrue("status_hint_state_busy" in idle && "status_reason_health_failed" in idle, idle.toString())
    }

    // ── v1.2.2: a success's last warning, shown as the script printed it ────

    private val gatewayWarn = ManagedRunWarningEndToEndTest.GATEWAY_WARN_SENTENCE

    private class View(
        val keys: List<String>,
        val boxes: List<String>,
    )

    @Suppress("UNCHECKED_CAST")
    private fun view(
        scenario: String,
        fact: String,
    ): View {
        val v = result(scenario)[fact] as Map<String, Any?>
        return View(v["keys"] as List<String>, v["boxes"] as List<String>)
    }

    private fun assertNoWarningBox(
        v: View,
        label: String,
    ) {
        assertEquals(emptyList<String>(), v.boxes, "$label: ${v.keys}")
        assertFalse("status_output" in v.keys, "$label: ${v.keys}")
    }

    @Test
    fun `a success with a warning and its sentence shows the detail summary and the sentence in an output box`() {
        val v = view("warningCard", "card")
        listOf("status_result_success", "status_result_warnings_detail", "status_output").forEach {
            assertTrue(it in v.keys, "$it missing: ${v.keys}")
        }
        assertFalse("status_result_warnings" in v.keys, "the plain summary is shown too: ${v.keys}")
        // one box: the card's (the last-update line keeps only its summary while the card is up)
        assertEquals(listOf(gatewayWarn), v.boxes)
        assertEquals(1, v.keys.count { it == "status_output" }, v.keys.toString())
        assertTrue("status_last_success_warn" in v.keys, v.keys.toString())
    }

    @Test
    fun `the sentence is shown untranslated, word for word, under the translated summary in every language`() {
        for (lang in listOf("en", "ko", "zh")) {
            @Suppress("UNCHECKED_CAST")
            val r = result("warningLang")[lang] as Map<String, Any?>
            val card = r["cardText"] as String
            assertEquals(listOf(gatewayWarn), r["cardBoxes"], lang)
            assertTrue(card.contains(r["summary"] as String), "$lang: the detail summary is not shown: $card")
            assertTrue(card.contains(r["output"] as String), "$lang: no output label: $card")
            assertTrue(card.contains(gatewayWarn), "$lang: the sentence is not shown as printed: $card")
            assertFalse(card.contains(r["plain"] as String), "$lang: the plain summary is shown too: $card")
            // the summary comes first, the script's words after it
            assertTrue(card.indexOf(r["summary"] as String) < card.indexOf(gatewayWarn), "$lang: $card")
            assertEquals(listOf(gatewayWarn), r["lastBoxes"], "$lang: closed card, line")
            assertTrue((r["lastText"] as String).contains(gatewayWarn), lang)
        }
    }

    @Test
    fun `a success without warnings shows no summary and no box even when a detail is sent`() {
        val v = view("warningCard", "zeroWarnings")
        assertFalse("status_result_warnings" in v.keys || "status_result_warnings_detail" in v.keys, v.keys.toString())
        assertTrue("status_result_success" in v.keys)
        assertNoWarningBox(v, "zeroWarnings")
    }

    @Test
    fun `a success with warnings but no sentence keeps the plain warnings summary and shows no box`() {
        for (fact in listOf("emptyDetail", "nullDetail", "noDetailKey")) {
            val v = view("warningCard", fact)
            assertTrue("status_result_warnings" in v.keys, "$fact: ${v.keys}")
            assertFalse("status_result_warnings_detail" in v.keys, "$fact: ${v.keys}")
            assertNoWarningBox(v, fact)
        }
    }

    @Test
    fun `the box shows the first line, trimmed, cut to 200 characters`() {
        assertEquals(listOf("first line"), view("warningCard", "multiLine").boxes)
        // only the first line, whatever spaces end it (native sends one trimmed sentence anyway)
        assertEquals("first", view("warningCard", "innerSpaces").boxes.single().trimEnd())
        assertEquals(listOf("x".repeat(200)), view("warningCard", "long").boxes)
        assertEquals(listOf("z".repeat(200)), view("warningCard", "exact").boxes)
        assertEquals(listOf("y".repeat(200)), view("warningLastLine", "long").boxes)
    }

    @Test
    fun `the state words in a success's warning bring no repair hint - the state pattern is for failures`() {
        for ((scenario, fact) in listOf("warningCard" to "stateWords", "warningLastLine" to "stateWords")) {
            val v = view(scenario, fact)
            assertFalse("status_hint_state_busy" in v.keys || "status_hint_doctor" in v.keys, "$scenario: ${v.keys}")
            assertEquals(listOf("state ownership: StateOwnerContention, database is busy"), v.boxes, scenario)
        }
    }

    @Test
    fun `a failed card keeps its own output rule - the FAIL block, never the warning box or summary`() {
        val failed = view("warningCard", "failed")
        assertEquals(listOf("Failed to download release"), failed.boxes)
        assertEquals(1, failed.keys.count { it == "status_output" }, failed.keys.toString())
        assertFalse("status_result_warnings" in failed.keys || "status_result_warnings_detail" in failed.keys)
        // the gateway reasons show no output at all, whatever the detail says
        assertNoWarningBox(view("warningCard", "failedGateway"), "failedGateway")
    }

    @Test
    fun `a cancelled or refused end with warnings and a detail shows no warning box`() {
        for (fact in listOf("cancelled", "refused")) {
            val v = view("warningCard", fact)
            assertNoWarningBox(v, fact)
            val summaries = listOf("status_result_warnings_detail", "status_result_warnings")
            assertFalse(summaries.any { it in v.keys }, "$fact: ${v.keys}")
        }
    }

    @Test
    fun `the last-update line shows a recorded success's warning under its summary when idle`() {
        val idleViews = listOf("warningLastLine" to "idle", "warningCard" to "before", "warningLastLine" to "recreated")
        for ((scenario, fact) in idleViews) {
            val v = view(scenario, fact)
            assertTrue("status_last_success_warn" in v.keys, "$scenario.$fact: ${v.keys}")
            assertTrue("status_output" in v.keys, "$scenario.$fact: ${v.keys}")
            assertEquals(listOf(gatewayWarn), v.boxes, "$scenario.$fact")
            assertFalse("status_result_success" in v.keys, "$scenario.$fact: a card is shown: ${v.keys}")
        }
    }

    @Test
    fun `the last-update line hides the warning while a run goes on or is being prepared`() {
        for (fact in listOf("running", "preparing")) {
            val v = view("warningLastLine", fact)
            assertNoWarningBox(v, fact)
            assertTrue("status_updating" in v.keys, "$fact: ${v.keys}")
        }
        assertNoWarningBox(view("warningLastLine", "cycRunning"), "cycRunning")
    }

    @Test
    fun `the last-update line hides the warning while a result card is shown and shows it again once closed`() {
        val overCard = view("warningLastLine", "overCard")
        assertTrue("status_result_failed" in overCard.keys, overCard.keys.toString())
        assertEquals(listOf("Failed to download"), overCard.boxes, "the line's warning shows under a failure card")
        assertEquals(listOf(gatewayWarn), view("warningLastLine", "overClosed").boxes)

        assertEquals(listOf("older advice"), view("warningLastLine", "cycBefore").boxes)
        val cycCard = view("warningLastLine", "cycCard")
        assertTrue("status_result_warnings_detail" in cycCard.keys, cycCard.keys.toString())
        assertEquals(listOf(gatewayWarn), cycCard.boxes, "card and line both show the warning")
        assertEquals(listOf(gatewayWarn), view("warningLastLine", "cycClosed").boxes)
        assertEquals(listOf(gatewayWarn), view("warningCard", "dismissed").boxes)
    }

    @Test
    fun `the last-update line shows no warning box for a success without warnings or sentence, or for a failure`() {
        for (fact in listOf("zero", "noDetail", "failure", "cancelledRec")) {
            assertNoWarningBox(view("warningLastLine", fact), fact)
        }
        assertTrue("status_last_success" in view("warningLastLine", "zero").keys)
        assertTrue("status_last_success_warn" in view("warningLastLine", "noDetail").keys)
    }

    internal companion object {
        /** node on the PATH, nvm or Homebrew; null when there is none. */
        fun node(): String? {
            val candidates =
                buildList {
                    System.getenv("PATH")?.split(File.pathSeparator)?.forEach { add(File(it, "node")) }
                    System.getenv("NVM_BIN")?.let { add(File(it, "node")) }
                    File(System.getProperty("user.home"), ".nvm/versions/node")
                        .listFiles()
                        ?.sortedDescending()
                        ?.forEach { add(File(it, "bin/node")) }
                    add(File("/opt/homebrew/bin/node"))
                    add(File("/usr/local/bin/node"))
                }
            return candidates.firstOrNull { it.canExecute() }?.path
        }

        /**
         * Writes [STUBS] and [harness] to [work] and runs it: scenario name → what it observed.
         * Skips the calling test (an assumption) when node or esbuild is missing.
         */
        fun runHarness(
            www: File,
            work: File,
            harness: String,
        ): Map<String, Map<String, Any?>> {
            val node = node()
            val esbuild = File(www, "node_modules/esbuild/lib/main.js")
            assumeTrue(node != null, "node not found: the behavior of the status screen is not checked")
            assumeTrue(esbuild.isFile, "www/node_modules/esbuild missing (npm ci in android/www)")
            STUBS.forEach { (name, text) -> File(work, name).writeText(text) }
            File(work, "harness.mjs").writeText(harness)
            val p =
                ProcessBuilder(node, "harness.mjs", esbuild.path, File(www, "src").path, work.path)
                    .directory(work)
                    .redirectErrorStream(true)
                    .start()
            val out = p.inputStream.bufferedReader().readText()
            assertTrue(p.waitFor(60, TimeUnit.SECONDS), "harness timed out")
            assertEquals(0, p.exitValue(), out)
            val json = out.lines().last { it.startsWith("{") }

            @Suppress("UNCHECKED_CAST")
            return Gson().fromJson(json, Map::class.java) as Map<String, Map<String, Any?>>
        }

        val STUBS =
            mapOf(
                "fake-react.mjs" to
                    """
                    |// The hooks SettingsStatus uses, state kept per call order like React's
                    |const R = (globalThis.__fr = globalThis.__fr || { slots: [], idx: 0, pending: [], dirty: false, listeners: {} })
                    |const changed = (a, b) => !a || !b || a.length !== b.length || a.some((x, j) => !Object.is(x, b[j]))
                    |export function useState(init) {
                    |  const i = R.idx++
                    |  if (!(i in R.slots)) R.slots[i] = { v: typeof init === 'function' ? init() : init }
                    |  const s = R.slots[i]
                    |  return [s.v, n => { const v = typeof n === 'function' ? n(s.v) : n; if (!Object.is(v, s.v)) { s.v = v; R.dirty = true } }]
                    |}
                    |export function useRef(init) {
                    |  const i = R.idx++
                    |  if (!(i in R.slots)) R.slots[i] = { current: init }
                    |  return R.slots[i]
                    |}
                    |export function useCallback(fn, deps) {
                    |  const i = R.idx++
                    |  const s = R.slots[i]
                    |  if (!s || changed(s.deps, deps)) R.slots[i] = { fn, deps }
                    |  return R.slots[i].fn
                    |}
                    |export function useEffect(fn, deps) {
                    |  const i = R.idx++
                    |  const s = R.slots[i]
                    |  if (!s || deps === undefined || changed(s.deps, deps)) {
                    |    R.pending.push(() => {
                    |      if (s && typeof s.cleanup === 'function') s.cleanup()
                    |      R.slots[i] = { deps, cleanup: fn() }
                    |    })
                    |  }
                    |}
                    |export default { useState, useRef, useCallback, useEffect }
                    |
                    """.trimMargin(),
                "jsx.mjs" to
                    """
                    |export const __Frag = 'fragment'
                    |export function __h(type, props, ...children) {
                    |  const kids = children.flat(Infinity)
                    |  const p = Object.assign({}, props || {}, { children: kids })
                    |  if (typeof type === 'function') return type(p)
                    |  return { type, props: p, children: kids }
                    |}
                    |
                    """.trimMargin(),
                "stub-router.mjs" to "export function useRoute() { return { navigate() {} } }\n",
                "stub-native-event.mjs" to
                    "export function useNativeEvent(type, handler) { globalThis.__fr.listeners[type] = handler }\n",
                "stub-probes.mjs" to "export function useRuntimeProbes() { return {} }\n",
                // The newest callback of the page, called by emit('visible-again') (the page shown again)
                "stub-visible-again.mjs" to
                    "export function useVisibleAgain(fn) { globalThis.__fr.listeners['visible-again'] = fn }\n",
                // `{key}` always (the tests read which text was chosen); with `globalThis.__lang` set, the
                // real translation of that locale follows it (the tests read what the user would see)
                "stub-i18n.mjs" to
                    """
                    |import { en } from 'real-i18n-en'
                    |import { ko } from 'real-i18n-ko'
                    |import { zh } from 'real-i18n-zh'
                    |const dicts = (globalThis.__dicts = { en, ko, zh })
                    |export function t(key, vars) {
                    |  const lang = globalThis.__lang
                    |  if (!lang) return '{' + key + '}'
                    |  let text = dicts[lang][key] || key
                    |  if (vars) for (const [k, v] of Object.entries(vars)) text = text.replace('{' + k + '}', v)
                    |  return '{' + key + '}' + text
                    |}
                    |export function getLocale() { return 'en' }
                    |
                    """.trimMargin(),
                "stub-confirm-card.mjs" to
                    """
                    |export function ConfirmCard(p) {
                    |  return { type: 'ConfirmCard', props: p, children: [p.title, p.children] }
                    |}
                    |
                    """.trimMargin(),
            )

        /** Bundles SettingsStatus with the stubs above, runs every scenario, prints one JSON line. */
        val HARNESS =
            """
            |import path from 'node:path'
            |import { createRequire } from 'node:module'
            |import { pathToFileURL } from 'node:url'
            |const [esbuildMain, wwwSrc, work] = process.argv.slice(2)
            |const esbuild = createRequire(import.meta.url)(esbuildMain)
            |const stub = n => path.join(work, n)
            |const stubs = {
            |  'react': stub('fake-react.mjs'),
            |  '../lib/router': stub('stub-router.mjs'),
            |  '../lib/useNativeEvent': stub('stub-native-event.mjs'),
            |  '../lib/useRuntimeProbes': stub('stub-probes.mjs'),
            |  '../lib/useVisibleAgain': stub('stub-visible-again.mjs'),
            |  '../components/ConfirmCard': stub('stub-confirm-card.mjs'),
            |  '../i18n': stub('stub-i18n.mjs'),
            |  'real-i18n-en': path.join(wwwSrc, 'i18n/en.ts'),
            |  'real-i18n-ko': path.join(wwwSrc, 'i18n/ko.ts'),
            |  'real-i18n-zh': path.join(wwwSrc, 'i18n/zh.ts'),
            |}
            |const out = stub('status.bundle.mjs')
            |await esbuild.build({
            |  entryPoints: [path.join(wwwSrc, 'screens/SettingsStatus.tsx')],
            |  bundle: true, format: 'esm', platform: 'node', outfile: out, logLevel: 'error',
            |  jsx: 'transform', jsxFactory: '__h', jsxFragment: '__Frag', inject: [stub('jsx.mjs')],
            |  tsconfigRaw: {},
            |  plugins: [{ name: 'stubs', setup(b) { b.onResolve({ filter: /.*/ }, a => (stubs[a.path] ? { path: stubs[a.path] } : undefined)) } }],
            |})
            |globalThis.window = globalThis
            |const R = (globalThis.__fr = globalThis.__fr || { slots: [], idx: 0, pending: [], dirty: false, listeners: {} })
            |let now = 0
            |Date.now = () => now
            |const timers = new Map()
            |let nextTimer = 0
            |window.setInterval = fn => { nextTimer++; timers.set(nextTimer, fn); return nextTimer }
            |window.clearInterval = id => { timers.delete(id) }
            |const { SettingsStatus } = await import(pathToFileURL(out).href)
            |
            |const IDLE = { kind: null, phase: 'idle', stage: 0, stageTotal: 0, progress: 0, message: '', cancelable: false, cancelRequested: false, longRunning: false, reason: null, exit: null, detail: '', warnings: 0 }
            |const busy = stage => Object.assign({}, IDLE, { kind: 'UPDATE', phase: 'running', stage, stageTotal: 5, progress: stage / 5, cancelable: stage <= 2, message: 'line' })
            |const ended = (phase, extra) => Object.assign({}, IDLE, { kind: 'UPDATE', phase, stageTotal: 5 }, extra)
            |const doneEvent = extra => ended('done', Object.assign({ stage: 5, progress: 1, exit: 0 }, extra))
            |// platforms/openclaw/update.sh v1.2.2, as native passes it (marker and colors removed)
            |const GATEWAY_WARN = 'The gateway is using the OpenClaw state, so the data check was skipped. Stop the gateway, then run: openclaw doctor'
            |// every word of STATE_BUSY_PATTERN: it must not turn a success's warning into a repair hint
            |const STATE_WORDS = 'state ownership: StateOwnerContention, database is busy'
            |const HINT_REASONS = ['MIGRATION_FAILED', 'HEALTH_FAILED', 'DOWNLOAD', 'INSTALL_FAILED', 'NO_SPACE', 'INTERRUPTED', 'CANCELLED', 'UNKNOWN', null]
            |const HINT_DETAILS = {
            |  ownership: 'Error: state ownership is held by pid 42',
            |  contention: 'StateOwnerContention: another process',
            |  busyDb: 'SQLITE_BUSY: database is busy',
            |  upperBusy: 'DATABASE IS BUSY',
            |  lowerContention: 'stateownercontention',
            |  titleOwnership: 'State Ownership lost',
            |  deep: 'l1\nl2\nl3\nl4\nl5\nl6\nl7\nlock: database is busy',
            |  none: null,
            |  empty: '',
            |  other: 'npm ERR! code ELIFECYCLE',
            |  nearBusy: 'database busy',
            |  nearOwnership: 'state  ownership',
            |  nearContention: 'StateOwner Contention',
            |}
            |
            |function mount(native) {
            |  R.slots = []; R.idx = 0; R.pending = []; R.dirty = false; R.listeners = {}
            |  timers.clear(); now = 0
            |  globalThis.__lang = (native && native.lang) || null
            |  const n = Object.assign({ runState: IDLE, lastRun: {}, gateway: { running: false, ours: false, pids: [] }, calls: [] }, native)
            |  const record = name => (...a) => { n.calls.push([name].concat(a)) }
            |  window.OpenClaw = {
            |    getRunState: () => JSON.stringify(n.runState),
            |    getLastRun: () => JSON.stringify(n.lastRun),
            |    getGatewayStatus: () => JSON.stringify(n.gateway),
            |    startRun: record('startRun'), stopGateway: record('stopGateway'), cancelRun: record('cancelRun'),
            |    showTerminal: record('showTerminal'), writeCommandToTerminal: record('writeCommandToTerminal'),
            |  }
            |  let tree
            |  const render = () => {
            |    for (let k = 0; k < 50; k++) {
            |      R.idx = 0; R.pending = []; R.dirty = false
            |      tree = SettingsStatus()
            |      const effects = R.pending
            |      R.pending = []
            |      effects.forEach(e => e())
            |      if (!R.dirty) return
            |    }
            |    throw new Error('the page never settles')
            |  }
            |  const walk = (node, visit) => {
            |    if (node == null || node === false || node === true) return
            |    if (Array.isArray(node)) return node.forEach(c => walk(c, visit))
            |    visit(node)
            |    if (typeof node === 'object') walk(node.children, visit)
            |  }
            |  const textOf = node => { const parts = []; walk(node, x => { if (typeof x !== 'object') parts.push(String(x)) }); return parts.join('') }
            |  const keysOf = node => (textOf(node).match(/\{(status_[a-z_0-9]+)\}/g) || []).map(s => s.slice(1, -1))
            |  const buttons = () => {
            |    const list = []
            |    walk(tree, x => {
            |      if (typeof x !== 'object') return
            |      if (x.type === 'button') list.push({ keys: keysOf(x.children), disabled: !!x.props.disabled, press: x.props.onClick })
            |      if (x.type === 'ConfirmCard') {
            |        list.push({ keys: keysOf(x.props.confirmLabel), disabled: false, press: x.props.onConfirm })
            |        list.push({ keys: keysOf(x.props.cancelLabel), disabled: false, press: x.props.onCancel })
            |      }
            |    })
            |    return list
            |  }
            |  const button = key => {
            |    const b = buttons().find(x => x.keys.includes(key))
            |    if (!b) throw new Error('no button ' + key + ' in ' + JSON.stringify(keysOf(tree)))
            |    return b
            |  }
            |  render()
            |  const page = {
            |    native: n,
            |    keys: () => keysOf(tree),
            |    text: () => textOf(tree),
            |    // the text of every script-output box (OUTPUT_BOX: the one monospace style of the page)
            |    boxes: () => {
            |      const list = []
            |      walk(tree, x => { if (typeof x === 'object' && x.props && x.props.style && x.props.style.fontFamily === 'monospace') list.push(textOf(x.children)) })
            |      return list
            |    },
            |    cardKeys: titleKey => {
            |      let found = []
            |      walk(tree, x => { if (typeof x === 'object' && x.type === 'ConfirmCard' && keysOf(x.props.title).includes(titleKey)) found = keysOf(x) })
            |      return found
            |    },
            |    disabled: key => button(key).disabled,
            |    press: key => { const b = button(key); if (b.disabled) throw new Error(key + ' is disabled'); b.press(); render() },
            |    pressHandlerTwice: key => { const b = button(key); b.press(); b.press(); render() },
            |    emit: (type, data) => { R.listeners[type](data); render() },
            |    tick: ms => { now += ms; Array.from(timers.values()).forEach(f => f()); render() },
            |  }
            |  return page
            |}
            |
            |function startFromIdle(native) {
            |  const p = mount(native)
            |  p.press('status_update_btn')
            |  p.press('status_confirm_start')
            |  return p
            |}
            |
            |const scenarios = {
            |  refusedNotInstalled() {
            |    const p = mount({})
            |    p.press('status_update_btn')
            |    p.press('status_confirm_start')
            |    const afterConfirm = p.keys()
            |    const terminalDisabledWhilePreparing = p.disabled('status_terminal_btn')
            |    p.emit('run_progress', ended('refused', { reason: 'NOT_INSTALLED', stage: 0 }))
            |    return { afterConfirm, terminalDisabledWhilePreparing, afterRefusal: p.keys(), terminalDisabledAfterRefusal: p.disabled('status_terminal_btn'), calls: p.native.calls }
            |  },
            |  refusedThenRetry() {
            |    const p = startFromIdle({})
            |    p.emit('run_progress', ended('refused', { reason: 'BUSY', stage: 0 }))
            |    p.press('status_retry')
            |    const afterRetry = p.keys()
            |    p.press('status_confirm_start')
            |    return { afterRetry, calls: p.native.calls }
            |  },
            |  refusedUnknown() {
            |    const p = startFromIdle({})
            |    // the bridge's answer when the runner cannot be built: no kind, no stage total
            |    p.emit('run_progress', Object.assign({}, IDLE, { phase: 'refused', reason: 'UNKNOWN' }))
            |    return { afterRefusal: p.keys() }
            |  },
            |  refusedWhileBusy() {
            |    const p = mount({ runState: busy(1) })
            |    p.emit('run_progress', ended('refused', { reason: 'BUSY' }))
            |    return { afterRefusal: p.keys() }
            |  },
            |  stageLines() {
            |    const end = extra => { const p = mount({}); p.emit('run_progress', ended('failed', extra)); return p.keys() }
            |    return {
            |      download2: end({ stage: 2, reason: 'DOWNLOAD', exit: 1, detail: 'Failed to download release' }),
            |      install3: end({ stage: 3, reason: 'INSTALL_FAILED', exit: 1, detail: 'Could not install openclaw' }),
            |      migration4: end({ stage: 4, reason: 'MIGRATION_FAILED', exit: 1, detail: 'x' }),
            |      health5: end({ stage: 5, reason: 'HEALTH_FAILED', exit: 1, detail: 'x' }),
            |      noStage: (() => { const p = mount({}); p.emit('run_progress', { kind: 'UPDATE', phase: 'failed', reason: 'UNKNOWN' }); return p.keys() })(),
            |      gatewayRunning: end({ stage: 1, reason: 'GATEWAY_RUNNING', exit: 1, detail: 'pkill -f openclaw' }),
            |      gatewayStopFailed: end({ stage: 0, reason: 'GATEWAY_STOP_FAILED', detail: 'kill -9' }),
            |    }
            |  },
            |  precheckGivesUp() {
            |    const p = startFromIdle({})
            |    for (let s = 0; s < 59; s++) p.tick(1000)
            |    const at59s = p.keys()
            |    p.tick(1000)
            |    return { at59s, at60s: p.keys(), terminalDisabledAt60s: p.disabled('status_terminal_btn') }
            |  },
            |  precheckSeesRun() {
            |    const p = startFromIdle({})
            |    p.native.runState = busy(1)
            |    p.tick(1000)
            |    return { afterPoll: p.keys() }
            |  },
            |  precheckOverResult() {
            |    const p = mount({})
            |    p.emit('run_progress', ended('failed', { stage: 1, reason: 'DOWNLOAD', exit: 1 }))
            |    p.press('status_retry')
            |    p.press('status_confirm_start')
            |    return { afterConfirm: p.keys() }
            |  },
            |  precheckGateway() {
            |    const p = mount({ gateway: { running: true, ours: true, pids: [42] } })
            |    const stopDisabledBefore = p.disabled('status_gw_stop')
            |    p.press('status_update_btn')
            |    p.press('status_confirm_start')
            |    return { stopDisabledBefore, stopDisabledWhilePreparing: p.disabled('status_gw_stop') }
            |  },
            |  visibleAgain() {
            |    const failed = { UPDATE: { at: 1700000000, verdict: 'failure', reason: 'MIGRATION_FAILED', exit: 1, warnings: 0 } }
            |    const p = mount({})
            |    const before = p.keys()
            |    // The gateway was started and an update ran in the terminal while the page was away
            |    p.native.gateway = { running: true, ours: true, pids: [7] }
            |    p.native.lastRun = { UPDATE: { at: 1700000000, verdict: 'success', warnings: 0 } }
            |    p.emit('visible-again')
            |    const after = p.keys()
            |    const q = mount({ runState: busy(3) })
            |    q.native.gateway = { running: true, ours: true, pids: [7] }
            |    q.native.lastRun = failed
            |    q.emit('visible-again')
            |    return { before, after, busy: q.keys(), busyCalls: q.native.calls }
            |  },
            |  lastUpdateLine() {
            |    const interrupted = { UPDATE: { at: 1700000000, verdict: 'failure', reason: 'INTERRUPTED', warnings: 0 } }
            |    const migration = { UPDATE: { at: 1700000000, verdict: 'failure', reason: 'MIGRATION_FAILED', exit: 1, warnings: 0 } }
            |    const busyPage = mount({ runState: busy(3), lastRun: interrupted })
            |    const preparing = startFromIdle({ lastRun: migration })
            |    const idle = mount({ lastRun: migration })
            |    return { busy: busyPage.keys(), preparing: preparing.keys(), idle: idle.keys() }
            |  },
            |  consentOurs() {
            |    const p = mount({ gateway: { running: true, ours: true, pids: [42] } })
            |    p.press('status_update_btn')
            |    const card = p.cardKeys('status_confirm_title')
            |    p.press('status_confirm_start')
            |    return { card, calls: p.native.calls }
            |  },
            |  consentNotOurs() {
            |    const p = mount({ gateway: { running: true, ours: false, pids: [42] } })
            |    p.press('status_update_btn')
            |    const card = p.cardKeys('status_confirm_title')
            |    p.press('status_confirm_start')
            |    p.emit('run_progress', ended('refused', { reason: 'GATEWAY_RUNNING' }))
            |    return { card, afterRefusal: p.keys(), calls: p.native.calls }
            |  },
            |  doubleConfirm() {
            |    const p = mount({})
            |    p.press('status_update_btn')
            |    p.pressHandlerTwice('status_confirm_start')
            |    return { calls: p.native.calls }
            |  },
            |  // Which repair hint each (reason, detail) gets: on the result card (the event's detail) and on
            |  // the last-update line (the recorded detail, lines joined with " / " as native keeps it)
            |  hintTable() {
            |    const hint = k => {
            |      const b = k.includes('status_hint_state_busy'), d = k.includes('status_hint_doctor')
            |      return b && d ? 'both' : b ? 'state_busy' : d ? 'doctor' : 'none'
            |    }
            |    const table = {}
            |    for (const reason of HINT_REASONS) {
            |      for (const [label, detail] of Object.entries(HINT_DETAILS)) {
            |        const p = mount({})
            |        p.emit('run_progress', ended('failed', { stage: 4, reason, exit: 1, detail }))
            |        const rec = { at: 1700000000, verdict: 'failure', exit: 1, warnings: 0 }
            |        if (reason !== null) rec.reason = reason
            |        if (detail !== null) rec.detail = detail.split('\n').join(' / ')
            |        const q = mount({ lastRun: { UPDATE: rec } })
            |        table[String(reason) + '|' + label] = { card: hint(p.keys()), last: hint(q.keys()) }
            |      }
            |    }
            |    // the output box shows 6 lines; the hint is chosen from the whole detail
            |    const deep = mount({})
            |    deep.emit('run_progress', ended('failed', { stage: 5, reason: 'HEALTH_FAILED', exit: 1, detail: HINT_DETAILS.deep }))
            |    return { table, deepText: deep.text() }
            |  },
            |  stateBusyText() {
            |    const out = {}
            |    for (const lang of ['en', 'ko', 'zh']) {
            |      const busyDetail = 'Error: StateOwnerContention (pid 42)'
            |      const card = mount({ lang })
            |      card.emit('run_progress', ended('failed', { stage: 5, reason: 'HEALTH_FAILED', exit: 1, detail: busyDetail }))
            |      const last = mount({ lang, lastRun: { UPDATE: { at: 1700000000, verdict: 'failure', reason: 'MIGRATION_FAILED', exit: 1, detail: busyDetail, warnings: 0 } } })
            |      const general = mount({ lang })
            |      general.emit('run_progress', ended('failed', { stage: 5, reason: 'HEALTH_FAILED', exit: 1, detail: 'health check failed' }))
            |      const d = globalThis.__dicts[lang]
            |      out[lang] = {
            |        cardText: card.text(), cardKeys: card.keys(), lastText: last.text(), lastKeys: last.keys(),
            |        generalText: general.text(), busy: d.status_hint_state_busy, doctor: d.status_hint_doctor,
            |      }
            |    }
            |    return out
            |  },
            |  cancelledLast() {
            |    const cancelled = { UPDATE: { at: 1700000000, verdict: 'failure', reason: 'CANCELLED', exit: 143, warnings: 0 } }
            |    const withDetail = { UPDATE: Object.assign({}, cancelled.UPDATE, { detail: 'Error: database is busy' }) }
            |    const idle = mount({ lastRun: cancelled }).keys()
            |    const detailIdle = mount({ lastRun: withDetail }).keys()
            |    const koText = mount({ lang: 'ko', lastRun: cancelled }).text()
            |    const enText = mount({ lang: 'en', lastRun: cancelled }).text()
            |    const p = mount({ lastRun: cancelled })
            |    p.emit('run_progress', ended('cancelled', { stage: 2, reason: 'CANCELLED', exit: 143 }))
            |    return { idle, detailIdle, koText, enText, card: p.keys() }
            |  },
            |  resultHidesLast() {
            |    const migration = { UPDATE: { at: 1700000000, verdict: 'failure', reason: 'MIGRATION_FAILED', exit: 1, detail: 'x', warnings: 0 } }
            |    const p = mount({ lastRun: migration })
            |    const before = p.keys()
            |    p.emit('run_progress', ended('failed', { stage: 2, reason: 'DOWNLOAD', exit: 1, detail: 'Failed to download' }))
            |    const during = p.keys()
            |    p.press('status_dismiss')
            |    const after = p.keys()
            |    // the usual case: the card and the record are the same run
            |    const busyDetail = 'sqlite: database is busy'
            |    const same = { UPDATE: { at: 1700000000, verdict: 'failure', reason: 'HEALTH_FAILED', exit: 1, detail: busyDetail, warnings: 0 } }
            |    const q = mount({ lastRun: same })
            |    q.emit('run_progress', ended('failed', { stage: 5, reason: 'HEALTH_FAILED', exit: 1, detail: busyDetail }))
            |    const sameDuring = q.keys()
            |    q.press('status_dismiss')
            |    const sameAfter = q.keys()
            |    // "Try again" replaces the card with the consent: the card is no longer on screen
            |    const r = mount({ lastRun: migration })
            |    r.emit('run_progress', ended('failed', { stage: 2, reason: 'DOWNLOAD', exit: 1 }))
            |    r.press('status_retry')
            |    const consent = r.keys()
            |    // a page created after a failed run (getRunState says failed) shows the card at once
            |    const s = mount({ lastRun: same, runState: ended('failed', { stage: 5, reason: 'HEALTH_FAILED', exit: 1, detail: busyDetail }) })
            |    const reopened = s.keys()
            |    return { before, during, after, sameDuring, sameAfter, consent, reopened }
            |  },
            |  busyHidesStateHint() {
            |    const same = { UPDATE: { at: 1700000000, verdict: 'failure', reason: 'HEALTH_FAILED', exit: 1, detail: 'StateOwnerContention', warnings: 0 } }
            |    const running = mount({ runState: busy(2), lastRun: same }).keys()
            |    const preparing = startFromIdle({ lastRun: same }).keys()
            |    const idle = mount({ lastRun: same }).keys()
            |    return { running, preparing, idle }
            |  },
            |  // v1.2.2: the last [WARN] sentence of a success (native's detail), on the card and the last-update line
            |  warningCard() {
            |    const okRec = { UPDATE: { at: 1700000000, verdict: 'success', exit: 0, detail: GATEWAY_WARN, warnings: 1 } }
            |    const view = p => ({ keys: p.keys(), boxes: p.boxes() })
            |    const p = mount({ lastRun: okRec })
            |    const before = view(p)
            |    p.emit('run_progress', doneEvent({ warnings: 1, detail: GATEWAY_WARN }))
            |    const card = view(p)
            |    p.press('status_dismiss')
            |    const dismissed = view(p)
            |    const one = e => { const q = mount({}); q.emit('run_progress', e); return view(q) }
            |    const noKey = doneEvent({ warnings: 1 }); delete noKey.detail
            |    return {
            |      before, card, dismissed,
            |      zeroWarnings: one(doneEvent({ warnings: 0, detail: GATEWAY_WARN })),
            |      emptyDetail: one(doneEvent({ warnings: 1, detail: '' })),
            |      nullDetail: one(doneEvent({ warnings: 2, detail: null })),
            |      noDetailKey: one(noKey),
            |      multiLine: one(doneEvent({ warnings: 1, detail: '  first line\nsecond line  ' })),
            |      // the whole detail is trimmed, not each line: a first line's own trailing spaces stay
            |      innerSpaces: one(doneEvent({ warnings: 1, detail: 'first  \nsecond' })),
            |      long: one(doneEvent({ warnings: 1, detail: 'x'.repeat(250) })),
            |      exact: one(doneEvent({ warnings: 1, detail: 'z'.repeat(200) })),
            |      stateWords: one(doneEvent({ warnings: 1, detail: STATE_WORDS })),
            |      failed: one(ended('failed', { stage: 3, reason: 'DOWNLOAD', exit: 1, detail: 'Failed to download release', warnings: 2 })),
            |      failedGateway: one(ended('failed', { stage: 1, reason: 'GATEWAY_RUNNING', exit: 1, detail: GATEWAY_WARN, warnings: 1 })),
            |      cancelled: one(ended('cancelled', { stage: 2, reason: 'CANCELLED', exit: 143, detail: GATEWAY_WARN, warnings: 1 })),
            |      refused: one(ended('refused', { reason: 'BUSY', detail: GATEWAY_WARN, warnings: 1 })),
            |    }
            |  },
            |  warningLang() {
            |    const out = {}
            |    for (const lang of ['en', 'ko', 'zh']) {
            |      const okRec = { UPDATE: { at: 1700000000, verdict: 'success', exit: 0, detail: GATEWAY_WARN, warnings: 1 } }
            |      const p = mount({ lang, lastRun: okRec })
            |      p.emit('run_progress', doneEvent({ warnings: 1, detail: GATEWAY_WARN }))
            |      const cardText = p.text(), cardBoxes = p.boxes()
            |      p.press('status_dismiss')
            |      const d = globalThis.__dicts[lang]
            |      out[lang] = {
            |        cardText, cardBoxes, lastText: p.text(), lastBoxes: p.boxes(),
            |        summary: d.status_result_warnings_detail.replace('{n}', '1'), plain: d.status_result_warnings.replace('{n}', '1'),
            |        output: d.status_output,
            |      }
            |    }
            |    return out
            |  },
            |  warningLastLine() {
            |    const rec = extra => ({ UPDATE: Object.assign({ at: 1700000000, verdict: 'success', exit: 0, detail: GATEWAY_WARN, warnings: 1 }, extra) })
            |    const view = p => ({ keys: p.keys(), boxes: p.boxes() })
            |    const over = mount({ lastRun: rec({}) })
            |    over.emit('run_progress', ended('failed', { stage: 2, reason: 'DOWNLOAD', exit: 1, detail: 'Failed to download' }))
            |    const overCard = view(over)
            |    over.press('status_dismiss')
            |    const overClosed = view(over)
            |    // a run from the page: running hides the line, the end shows the card (the line stays hidden), closing shows the line
            |    const cyc = mount({ lastRun: rec({ detail: 'older advice' }) })
            |    const cycBefore = view(cyc)
            |    cyc.native.runState = busy(1)
            |    cyc.emit('run_progress', busy(1))
            |    const cycRunning = view(cyc)
            |    cyc.native.runState = doneEvent({ warnings: 1, detail: GATEWAY_WARN })
            |    cyc.native.lastRun = rec({})
            |    cyc.emit('run_progress', doneEvent({ warnings: 1, detail: GATEWAY_WARN }))
            |    const cycCard = view(cyc)
            |    cyc.press('status_dismiss')
            |    const cycClosed = view(cyc)
            |    return {
            |      idle: view(mount({ lastRun: rec({}) })),
            |      running: view(mount({ runState: busy(3), lastRun: rec({}) })),
            |      preparing: view(startFromIdle({ lastRun: rec({}) })),
            |      zero: view(mount({ lastRun: rec({ warnings: 0 }) })),
            |      noDetail: view(mount({ lastRun: { UPDATE: { at: 1700000000, verdict: 'success', exit: 0, warnings: 2 } } })),
            |      failure: view(mount({ lastRun: { UPDATE: { at: 1700000000, verdict: 'failure', reason: 'DOWNLOAD', exit: 1, detail: GATEWAY_WARN, warnings: 1 } } })),
            |      cancelledRec: view(mount({ lastRun: { UPDATE: { at: 1700000000, verdict: 'failure', reason: 'CANCELLED', exit: 143, detail: GATEWAY_WARN, warnings: 1 } } })),
            |      long: view(mount({ lastRun: rec({ detail: 'y'.repeat(250) }) })),
            |      stateWords: view(mount({ lastRun: rec({ detail: STATE_WORDS }) })),
            |      // a page made after the success: getRunState says done, no card (a success shows on the line only)
            |      recreated: view(mount({ runState: doneEvent({ warnings: 1, detail: GATEWAY_WARN }), lastRun: rec({}) })),
            |      overCard, overClosed, cycBefore, cycRunning, cycCard, cycClosed,
            |    }
            |  },
            |}
            |const results = {}
            |for (const [name, run] of Object.entries(scenarios)) {
            |  // one scenario that throws (a button gone, a page that never settles) fails only its own tests
            |  try { results[name] = run() } catch (e) { results[name] = { error: String((e && e.stack) || e) } }
            |}
            |console.log(JSON.stringify(results))
            |
            """.trimMargin()
    }
}
