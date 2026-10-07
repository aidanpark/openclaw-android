package com.openclaw.android

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.nio.file.Files

/**
 * v0.4.4 S2 and N7 on the status screen as it BEHAVES (the harness of [StatusScreenBehaviorTest]:
 * the screen bundled from its source and run in node against a fake native side, `t()` rendering
 * `{key}` and, with a locale, the real translation after it).
 *  - S2: an end whose checks stopped the gateway (`gatewayStopped: true`) shows a result card that
 *    says so — success, failure, cancel, or a refusal that came after the stop (native sends the flag
 *    on a refusal only then), from the event or restored from `getRunState`. On a refusal card the
 *    line comes last: failed → reason (and not-ours) → `status_unchanged` → the line. A false or
 *    missing flag (a refusal before anything was stopped), the running card and the last-update line
 *    never show it. `status_unchanged` speaks of the installed files only, so both lines can stand
 *    on one card.
 *  - N7: `status_gw_not_ours` names no Termux and tells to `kill <PID>` a detached gateway, in the
 *    three places it is shown (gateway row, consent card, result card).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class StatusScreenGatewayStoppedBehaviorTest {
    private val www = File("../www").absoluteFile
    private val work: File by lazy { Files.createTempDirectory("status-gw-stopped").toFile() }
    private val langs = listOf("en", "ko", "zh")

    private val harness: String by lazy {
        val base = StatusScreenBehaviorTest.HARNESS
        val open = "const scenarios = {"
        val close = "\nconst results = {}"
        assertTrue(base.contains(open) && base.contains(close), "the shared harness changed shape")
        base.substringBefore(open) + open + "\n" + SCENARIOS + "}" + close + base.substringAfter(close)
    }

    private val results: Map<String, Map<String, Any?>> by lazy {
        StatusScreenBehaviorTest.runHarness(www, work, harness)
    }

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
    private fun lang(
        name: String,
        lang: String,
    ): Map<String, Any?> = result(name)[lang] as Map<String, Any?>

    private val line = "status_gateway_was_stopped"

    // ── S2: the result card says the gateway was stopped ────────────────────

    @Test
    fun `a success, a failure and a cancel whose checks stopped the gateway each show the line once`() {
        for (fact in listOf("done", "failed", "cancelled", "failedEarly", "doneWarn")) {
            val k = keys("cardLine", fact)
            assertEquals(1, k.count { it == line }, "$fact: $k")
        }
        assertTrue("status_result_success" in keys("cardLine", "done"))
        assertTrue("status_result_failed" in keys("cardLine", "failed"))
        assertTrue("status_result_cancelled" in keys("cardLine", "cancelled"))
    }

    @Test
    fun `the line comes after the stage line and before the script output`() {
        val failed = keys("cardLine", "failed")
        val stage = failed.indexOf("status_maybe_changed")
        val at = failed.indexOf(line)
        val output = failed.indexOf("status_output")
        assertTrue(stage in 0 until at, "stage line not before: $failed")
        assertTrue(output > at, "output not after: $failed")
        assertTrue(failed.indexOf("status_reason_install_failed") in 0 until at, failed.toString())
        val early = keys("cardLine", "failedEarly")
        assertTrue(early.indexOf("status_unchanged") in 0 until early.indexOf(line), early.toString())
        val warn = keys("cardLine", "doneWarn")
        assertTrue(warn.indexOf(line) in 0 until warn.indexOf("status_output"), warn.toString())
    }

    /** The update card only: between its section title and the terminal section. */
    private fun card(k: List<String>): List<String> {
        val from = k.indexOf("status_section_update")
        val to = k.indexOf("status_section_terminal")
        assertTrue(from in 0 until to, "no update section: $k")
        return k.subList(from + 1, to)
    }

    @Suppress("UNCHECKED_CAST")
    private fun boxes(
        name: String,
        fact: String,
    ): List<String> = (result(name)[fact] as Map<String, Any?>)["boxes"] as List<String>

    @Suppress("UNCHECKED_CAST")
    private fun viewKeys(
        name: String,
        fact: String,
    ): List<String> = (result(name)[fact] as Map<String, Any?>)["keys"] as List<String>

    private val buttons = listOf("status_dismiss", "status_retry")

    /** A refusal card's lines before the flag's line: failed, [reason], unchanged. */
    private fun refusedLines(reason: String) = listOf("status_result_failed", reason, "status_unchanged")

    /** GATEWAY_RUNNING for a gateway the app does not own, after the stop: every line in order. */
    private val notOursRefusal =
        listOf("status_result_failed", "status_reason_gateway_running", "status_gw_not_ours", "status_unchanged", line)

    @Test
    fun `a refusal after the stop shows the line last - failed, reason, unchanged, then the line`() {
        val expected =
            mapOf(
                "gateway" to listOf("status_result_failed", "status_reason_gateway_running", "status_unchanged", line),
                "busy" to listOf("status_result_failed", "status_reason_busy", "status_unchanged", line),
                "unknown" to listOf("status_result_failed", "status_refused_unknown", "status_unchanged", line),
            )
        for ((fact, lines) in expected) {
            assertEquals(lines + buttons, card(viewKeys("refusedCard", fact)), fact)
        }
    }

    @Test
    fun `a GATEWAY_RUNNING refusal for a gateway not ours puts the not-ours text before unchanged and the line`() {
        val lines = notOursRefusal
        assertEquals(lines + buttons, card(viewKeys("refusedCard", "notOurs")))
        // the same refusal before anything was stopped: everything but the line
        assertEquals(lines.dropLast(1) + buttons, card(viewKeys("refusedCard", "notOursFalse")))
    }

    @Test
    fun `a refusal with the flag false or missing - before any stop - shows no line, the card otherwise the same`() {
        val expected =
            mapOf(
                "gatewayFalse" to listOf("status_result_failed", "status_reason_gateway_running", "status_unchanged"),
                "busyMissing" to listOf("status_result_failed", "status_reason_busy", "status_unchanged"),
                "notInstalledFalse" to refusedLines("status_reason_not_installed"),
                "stopFailedFalse" to refusedLines("status_reason_gateway_stop_failed"),
                "busyString" to listOf("status_result_failed", "status_reason_busy", "status_unchanged"),
            )
        for ((fact, lines) in expected) {
            assertEquals(lines + buttons, card(viewKeys("refusedCard", fact)), fact)
        }
    }

    @Test
    fun `a refusal card shows no script output, warning box or stage line, with or without the flag`() {
        for (fact in listOf("gateway", "busy", "notOurs", "gatewayFalse", "stopFailedFalse")) {
            assertEquals(emptyList<String>(), boxes("refusedCard", fact), fact)
            val k = card(viewKeys("refusedCard", fact))
            val never = listOf("status_output", "status_maybe_changed", "status_stage", "status_result_warnings")
            (never + "status_result_warnings_detail").forEach { assertFalse(it in k, "$fact: $it in $k") }
            assertEquals(1, k.count { it == "status_unchanged" }, "$fact: $k")
        }
    }

    @Test
    fun `from the consent to a refusal after the stop - the line shows, and Try again brings the consent back`() {
        val refused = card(keys("refusedFlow", "refused"))
        assertEquals(notOursRefusal + buttons, refused)
        assertFalse("status_prechecking" in keys("refusedFlow", "refused"))
        val retry = keys("refusedFlow", "retry")
        assertTrue("status_confirm_title" in retry, retry.toString())
        assertFalse(line in retry || "status_result_failed" in retry, retry.toString())
    }

    @Test
    fun `the refusal card in every language - the line after the files-only unchanged sentence, both translated`() {
        val files = mapOf("en" to "installed files", "ko" to "설치된 파일", "zh" to "已安装的文件")
        for (l in langs) {
            val r = lang("refusedLang", l)
            val text = r["text"] as String

            fun at(
                key: String,
                value: String,
            ): Int {
                val shown = "{$key}${r[value] as String}"
                assertEquals(1, Regex(Regex.escape(shown)).findAll(text).count(), "$l: $shown in $text")
                return text.indexOf(shown)
            }
            val order =
                listOf(
                    at("status_result_failed", "failed"),
                    at("status_reason_gateway_running", "reason"),
                    // the gateway row shows it too: the card's is the last one
                    text.lastIndexOf("{status_gw_not_ours}${r["notOurs"] as String}"),
                    at("status_unchanged", "unchanged"),
                    at(line, "line"),
                )
            assertEquals(order.sorted(), order, "$l: $order")
            assertTrue((r["unchanged"] as String).contains(files.getValue(l)), "$l: ${r["unchanged"]}")
        }
    }

    @Test
    fun `a false, missing or non-boolean flag shows no line`() {
        for (fact in listOf("doneFalse", "failedFalse", "cancelledFalse", "doneMissing", "doneString", "doneOne")) {
            val k = keys("noLine", fact)
            assertTrue(k.any { it.startsWith("status_result_") }, "$fact: no card: $k")
            assertFalse(line in k, "$fact: $k")
        }
    }

    @Test
    fun `the running card and the preparing card never show the line`() {
        for (fact in listOf("running", "preparing")) {
            val k = keys("noLine", fact)
            assertTrue("status_updating" in k, "$fact: $k")
            assertFalse(line in k, "$fact: $k")
        }
    }

    @Test
    fun `a page made after a failed or cancelled run shows the line from getRunState`() {
        for (fact in listOf("failed", "cancelled")) {
            val k = keys("restored", fact)
            assertTrue(k.any { it == "status_result_failed" || it == "status_result_cancelled" }, "$fact: $k")
            assertEquals(1, k.count { it == line }, "$fact: $k")
        }
        // the same restore without the flag
        assertFalse(line in keys("restored", "failedFalse"))
    }

    @Test
    fun `the last-update line never shows it - a restored success, a closed card, a record carrying the key`() {
        for (fact in listOf("doneRestored", "dismissed", "recordWithKey", "recordFailureWithKey")) {
            val k = keys("lastLine", fact)
            assertFalse(line in k, "$fact: $k")
            assertTrue(k.any { it.startsWith("status_last_") }, "$fact: $k")
        }
        assertFalse(keys("lastLine", "dismissed").any { it.startsWith("status_result_") })
    }

    @Test
    fun `the line is shown in every language with its translation, and says to start the gateway again`() {
        val again = mapOf("en" to "start it again", "ko" to "다시 시작", "zh" to "重新启动")
        for (l in langs) {
            val r = lang("langText", l)
            val text = r["text"] as String
            val translated = r["line"] as String
            assertTrue(translated.isNotBlank() && translated != line, "$l: no translation")
            assertTrue(text.contains("{$line}$translated"), "$l: $text")
            assertTrue(translated.contains(again.getValue(l)), "$l: $translated")
        }
    }

    @Test
    fun `the consent for our gateway says it must be started again after the update, in every language`() {
        val again = mapOf("en" to "start it again", "ko" to "다시 시작해야", "zh" to "需要重新启动")
        for (l in langs) {
            val r = lang("langText", l)
            val confirm = r["confirm"] as String
            assertTrue(confirm.contains(again.getValue(l)), "$l: $confirm")
            assertTrue((r["consentText"] as String).contains("{status_confirm_gateway}$confirm"), "$l: consent text")
        }
    }

    // ── N7: a gateway the app does not own ──────────────────────────────────

    @Test
    fun `the not-ours text names no Termux and says to kill a detached gateway by pid, in every language`() {
        for (l in langs) {
            val notOurs = lang("notOurs", l)["text"] as String
            assertFalse(notOurs.contains("termux", ignoreCase = true), "$l: $notOurs")
            assertTrue(notOurs.contains("kill <PID>"), "$l: $notOurs")
            assertTrue(notOurs.contains("nohup"), "$l: $notOurs")
            assertTrue(notOurs.contains("tmux"), "$l: $notOurs")
        }
    }

    @Test
    fun `the not-ours text is what the gateway row, the consent card and the result card show`() {
        for (l in langs) {
            val r = lang("notOurs", l)
            val text = "{status_gw_not_ours}" + r["text"] as String

            fun shown(fact: String) = Regex(Regex.escape(text)).findAll(r[fact] as String).count()
            assertEquals(1, shown("row"), "$l: gateway row")
            // the row and the consent card
            assertEquals(2, shown("consent"), "$l: consent card")
            assertTrue((r["cardKeys"] as List<*>).contains("status_gw_not_ours"), "$l: consent card keys")
            // the row and the GATEWAY_RUNNING result card
            assertEquals(2, shown("result"), "$l: result card")
            assertTrue((r["resultKeys"] as List<*>).contains("status_reason_gateway_running"), "$l")
            listOf("row", "consent", "result").forEach {
                assertFalse((r[it] as String).contains("Termux"), "$l $it: ${r[it]}")
            }
        }
    }

    private companion object {
        /** Scenarios over the shared harness's `mount`, `startFromIdle`, `busy`, `ended` and `doneEvent`. */
        val SCENARIOS =
            """
            |  cardLine() {
            |    const end = e => { const p = mount({}); p.emit('run_progress', e); return p.keys() }
            |    return {
            |      done: end(doneEvent({ gatewayStopped: true })),
            |      doneWarn: end(doneEvent({ gatewayStopped: true, warnings: 1, detail: 'Stop the gateway, then run: openclaw doctor' })),
            |      failed: end(ended('failed', { stage: 3, reason: 'INSTALL_FAILED', exit: 1, detail: 'Could not install openclaw', gatewayStopped: true })),
            |      failedEarly: end(ended('failed', { stage: 2, reason: 'DOWNLOAD', exit: 1, gatewayStopped: true })),
            |      cancelled: end(ended('cancelled', { stage: 2, reason: 'CANCELLED', exit: 143, gatewayStopped: true })),
            |    }
            |  },
            |  noLine() {
            |    const end = e => { const p = mount({}); p.emit('run_progress', e); return p.keys() }
            |    const missing = doneEvent({}); delete missing.gatewayStopped
            |    const runningPage = mount({ runState: Object.assign(busy(2), { gatewayStopped: true }) })
            |    runningPage.emit('run_progress', Object.assign(busy(2), { gatewayStopped: true }))
            |    return {
            |      doneFalse: end(doneEvent({ gatewayStopped: false })),
            |      failedFalse: end(ended('failed', { stage: 3, reason: 'INSTALL_FAILED', exit: 1, gatewayStopped: false })),
            |      cancelledFalse: end(ended('cancelled', { stage: 2, reason: 'CANCELLED', exit: 143, gatewayStopped: false })),
            |      doneMissing: end(missing),
            |      doneString: end(doneEvent({ gatewayStopped: 'true' })),
            |      doneOne: end(doneEvent({ gatewayStopped: 1 })),
            |      running: runningPage.keys(),
            |      preparing: startFromIdle({ runState: Object.assign({}, IDLE, { gatewayStopped: true }) }).keys(),
            |    }
            |  },
            |  refusedCard() {
            |    const view = native => e => { const p = mount(native); p.emit('run_progress', e); return { keys: p.keys(), boxes: p.boxes() } }
            |    const plain = view({})
            |    const notOurs = view({ gateway: { running: true, ours: false, pids: [42] } })
            |    const refused = extra => ended('refused', extra)
            |    return {
            |      gateway: plain(refused({ reason: 'GATEWAY_RUNNING', gatewayStopped: true, detail: 'pkill -f openclaw', warnings: 1 })),
            |      busy: plain(refused({ reason: 'BUSY', gatewayStopped: true, detail: 'Update Complete!', warnings: 1 })),
            |      unknown: plain(Object.assign({}, IDLE, { phase: 'refused', reason: 'UNKNOWN', gatewayStopped: true })),
            |      notOurs: notOurs(refused({ reason: 'GATEWAY_RUNNING', gatewayStopped: true })),
            |      notOursFalse: notOurs(refused({ reason: 'GATEWAY_RUNNING', gatewayStopped: false })),
            |      gatewayFalse: plain(refused({ reason: 'GATEWAY_RUNNING', gatewayStopped: false, detail: 'pkill -f openclaw' })),
            |      // IDLE has no gatewayStopped: the key is missing
            |      busyMissing: plain(refused({ reason: 'BUSY' })),
            |      busyString: plain(refused({ reason: 'BUSY', gatewayStopped: 'true' })),
            |      notInstalledFalse: plain(refused({ reason: 'NOT_INSTALLED', gatewayStopped: false })),
            |      stopFailedFalse: plain(refused({ reason: 'GATEWAY_STOP_FAILED', gatewayStopped: false, detail: 'kill -9' })),
            |    }
            |  },
            |  refusedFlow() {
            |    const p = mount({ gateway: { running: true, ours: true, pids: [42] } })
            |    p.press('status_update_btn')
            |    p.press('status_confirm_start')
            |    // the checks stopped ours; one the app does not own is still there
            |    p.native.gateway = { running: true, ours: false, pids: [77] }
            |    p.emit('run_progress', ended('refused', { reason: 'GATEWAY_RUNNING', gatewayStopped: true }))
            |    const refused = p.keys()
            |    p.press('status_retry')
            |    return { refused, retry: p.keys() }
            |  },
            |  refusedLang() {
            |    const out = {}
            |    for (const lang of ['en', 'ko', 'zh']) {
            |      const d = globalThis.__dicts[lang]
            |      const p = mount({ lang, gateway: { running: true, ours: false, pids: [42] } })
            |      p.emit('run_progress', ended('refused', { reason: 'GATEWAY_RUNNING', gatewayStopped: true }))
            |      out[lang] = {
            |        text: p.text(), unchanged: d.status_unchanged, line: d.status_gateway_was_stopped,
            |        failed: d.status_result_failed, reason: d.status_reason_gateway_running, notOurs: d.status_gw_not_ours,
            |      }
            |    }
            |    return out
            |  },
            |  restored() {
            |    const at = runState => mount({ runState }).keys()
            |    return {
            |      failed: at(ended('failed', { stage: 3, reason: 'INSTALL_FAILED', exit: 1, gatewayStopped: true })),
            |      cancelled: at(ended('cancelled', { stage: 2, reason: 'CANCELLED', exit: 143, gatewayStopped: true })),
            |      failedFalse: at(ended('failed', { stage: 3, reason: 'INSTALL_FAILED', exit: 1, gatewayStopped: false })),
            |    }
            |  },
            |  lastLine() {
            |    const ok = { UPDATE: { at: 1700000000, verdict: 'success', exit: 0, warnings: 0 } }
            |    const p = mount({ lastRun: ok })
            |    p.emit('run_progress', doneEvent({ gatewayStopped: true }))
            |    p.press('status_dismiss')
            |    return {
            |      // a success is not restored as a card: only the line shows it
            |      doneRestored: mount({ runState: doneEvent({ gatewayStopped: true }), lastRun: ok }).keys(),
            |      dismissed: p.keys(),
            |      recordWithKey: mount({ lastRun: { UPDATE: Object.assign({}, ok.UPDATE, { gatewayStopped: true }) } }).keys(),
            |      recordFailureWithKey: mount({ lastRun: { UPDATE: { at: 1700000000, verdict: 'failure', reason: 'DOWNLOAD', exit: 1, warnings: 0, gatewayStopped: true } } }).keys(),
            |    }
            |  },
            |  langText() {
            |    const out = {}
            |    for (const lang of ['en', 'ko', 'zh']) {
            |      const d = globalThis.__dicts[lang]
            |      const p = mount({ lang })
            |      p.emit('run_progress', ended('failed', { stage: 3, reason: 'INSTALL_FAILED', exit: 1, gatewayStopped: true }))
            |      const text = p.text()
            |      const c = mount({ lang, gateway: { running: true, ours: true, pids: [42] } })
            |      c.press('status_update_btn')
            |      out[lang] = { text, line: d.status_gateway_was_stopped, confirm: d.status_confirm_gateway, consentText: c.text() }
            |    }
            |    return out
            |  },
            |  notOurs() {
            |    const out = {}
            |    for (const lang of ['en', 'ko', 'zh']) {
            |      const p = mount({ lang, gateway: { running: true, ours: false, pids: [42] } })
            |      const row = p.text()
            |      p.press('status_update_btn')
            |      const consent = p.text()
            |      const cardKeys = p.cardKeys('status_confirm_title')
            |      p.press('status_confirm_start')
            |      p.emit('run_progress', ended('refused', { reason: 'GATEWAY_RUNNING' }))
            |      out[lang] = { text: globalThis.__dicts[lang].status_gw_not_ours, row, consent, cardKeys, result: p.text(), resultKeys: p.keys() }
            |    }
            |    return out
            |  },
            """.trimMargin()
    }
}
