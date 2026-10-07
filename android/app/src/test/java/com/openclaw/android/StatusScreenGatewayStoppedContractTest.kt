package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * v0.4.4 S2 and N7 against the SOURCE of the status screen, its translations and the shipped bundle:
 * the `gatewayStopped` field the page reads (any end with the flag exactly true, a refusal included:
 * native sets it on a refusal only after its checks stopped the gateway), the one place the line is
 * rendered (the result card, never the last-update row), the three translations, `status_unchanged`
 * limited to the installed files (so it stands beside the line), and the not-ours text without Termux.
 * [StatusScreenGatewayStoppedBehaviorTest] runs the same screen; this pins what it is built from.
 */
internal class StatusScreenGatewayStoppedContractTest {
    private val locales = listOf("en", "ko", "zh")

    private fun www(path: String): String {
        val f = File("../www/src/$path")
        assertTrue(f.isFile, "expected ${f.absolutePath}")
        return f.readText()
    }

    private val status by lazy { www("screens/SettingsStatus.tsx") }

    private fun entries(locale: String): Map<String, String> =
        Regex("""(?m)^\s+([a-z_0-9]+):\s*(.*)$""")
            .findAll(www("i18n/$locale.ts"))
            .associate { m ->
                val raw =
                    m.groupValues[2]
                        .trim()
                        .removeSuffix(",")
                        .trim()
                m.groupValues[1] to raw.removeSurrounding("'").removeSurrounding("\"").removeSurrounding("`")
            }

    private val bundle: String by lazy {
        val index = File("src/main/assets/www/index.html")
        val js = Regex("""src="\./(assets/index-[A-Za-z0-9_-]+\.js)"""").find(index.readText())
        assertNotNull(js, "index.html names no bundle")
        File("src/main/assets/www/${js!!.groupValues[1]}").readText()
    }

    /** A TypeScript `interface <name> { … }` body. */
    private fun iface(name: String): String {
        val m = Regex("""interface $name \{([\s\S]*?)\n}""").find(status)
        assertNotNull(m, "interface $name not found")
        return m!!.groupValues[1]
    }

    private val newKeys = listOf("status_gateway_was_stopped", "status_confirm_gateway", "status_gw_not_ours")

    // ── S2 ──────────────────────────────────────────────────────────────────

    @Test
    fun `the run event declares gatewayStopped and the last-run record does not`() {
        assertTrue(Regex("""(?m)^\s+gatewayStopped\?: boolean$""").containsMatchIn(iface("RunEvent")))
        assertFalse(iface("LastRun").contains("gatewayStopped"), "the record is not to carry it")
    }

    @Test
    fun `the line is decided by one rule - any end, a refusal included, with the flag exactly true`() {
        assertTrue(
            status.contains("const resultGatewayStopped = result !== null && result.gatewayStopped === true\n"),
            "the rule changed",
        )
        val rule = status.lines().single { it.contains("const resultGatewayStopped =") }
        assertFalse(rule.contains("phase"), "the rule leaves out some ends again: $rule")
        // read nowhere else: the last-update row has no such field to read
        assertEquals(2, Regex("""\bgatewayStopped\b""").findAll(status).count(), "interface + the rule only")
        assertEquals(2, Regex("""\bresultGatewayStopped\b""").findAll(status).count(), "defined and rendered once")
    }

    @Test
    fun `the line is rendered once, in the result card, after the stage line and before the output`() {
        val render = "{resultGatewayStopped && <div style={SECONDARY}>{t('status_gateway_was_stopped')}</div>}"
        assertEquals(1, Regex(Regex.escape("t('status_gateway_was_stopped')")).findAll(status).count())
        val at = status.indexOf(render)
        assertTrue(at >= 0, "render line changed")
        val card = status.indexOf(") : result ? (")
        val stage = status.indexOf("{resultStageKey && <div style={SECONDARY}>{t(resultStageKey)}</div>}")
        val output = status.indexOf("{resultDetail && (")
        assertTrue(card in 0 until stage && stage < at && at < output, "card $card stage $stage at $at output $output")
        val lastRow = status.indexOf("t('status_last_update')")
        val rowEnd = status.indexOf("t('status_gateway')")
        assertTrue(lastRow in 0 until rowEnd)
        assertFalse(status.substring(lastRow, rowEnd).contains("atewayStopped"), "shown in the last-update row")
        assertFalse(status.substring(lastRow, rowEnd).contains("status_gateway_was_stopped"))
    }

    // ── translations ────────────────────────────────────────────────────────

    @Test
    fun `the three keys exist with text in every locale`() {
        for (locale in locales) {
            val map = entries(locale)
            newKeys.forEach { assertTrue(!map[it].isNullOrBlank(), "$locale.ts: $it missing or empty") }
        }
    }

    @Test
    fun `the stopped line and the gateway consent say the gateway must be started again, in every language`() {
        val again = mapOf("en" to "start it again", "ko" to "다시 시작", "zh" to "重新启动")
        for (locale in locales) {
            val map = entries(locale)
            val word = again.getValue(locale)
            assertTrue(map.getValue("status_gateway_was_stopped").contains(word), "$locale: stopped line")
            assertTrue(map.getValue("status_confirm_gateway").contains(word), "$locale: consent")
        }
        // the line says where: the Dashboard's Gateway
        assertTrue(entries("en").getValue("status_gateway_was_stopped").contains("Dashboard"))
    }

    @Test
    fun `the not-ours text names no Termux and says to kill a gateway run with nohup or tmux, in every language`() {
        for (locale in locales) {
            val text = entries(locale).getValue("status_gw_not_ours")
            assertFalse(text.contains("termux", ignoreCase = true), "$locale: $text")
            assertTrue(text.contains("`kill <PID>`"), "$locale: $text")
            assertTrue(text.contains("nohup") && text.contains("tmux"), "$locale: $text")
        }
    }

    // ── status_unchanged: the installed files only ──────────────────────────

    /** The claim of `status_unchanged`, limited to the installed files, as each locale words it. */
    private val filesOnly =
        mapOf(
            "en" to "nothing was changed in the installed files",
            "ko" to "설치된 파일은 바뀌지 않았습니다",
            "zh" to "已安装的文件未更改",
        )

    @Test
    fun `status_unchanged says the installed files were not changed, not that nothing at all was, in every language`() {
        for (locale in locales) {
            val text = entries(locale).getValue("status_unchanged")
            val limited = filesOnly.getValue(locale)
            assertTrue(text.contains(limited), "$locale: $text")
            val claims = StatusScreenWwwContractTest.UNCHANGED_CLAIMS.getValue(locale)
            // still the one text that claims no change (StatusScreenWwwContractTest) ...
            assertTrue(claims.containsMatchIn(text), "$locale: no longer matches the claim pattern: $text")
            // ... and every claim in it is the one about the files
            assertFalse(claims.containsMatchIn(text.replace(limited, "")), "$locale: an unlimited claim: $text")
        }
        assertEquals(
            "It stopped before the update began, so nothing was changed in the installed files.",
            entries("en").getValue("status_unchanged"),
        )
        assertEquals("업데이트를 시작하기 전에 멈췄으므로 설치된 파일은 바뀌지 않았습니다.", entries("ko").getValue("status_unchanged"))
        assertEquals("更新开始前已停止，因此已安装的文件未更改。", entries("zh").getValue("status_unchanged"))
    }

    @Test
    fun `status_unchanged says nothing about the gateway, which the stopped line says on its own`() {
        val gatewayWords = Regex("""(?i)gateway|게이트웨이|网关""")
        for (locale in locales) {
            val text = entries(locale).getValue("status_unchanged")
            assertFalse(gatewayWords.containsMatchIn(text), "$locale: $text")
            assertTrue(gatewayWords.containsMatchIn(entries(locale).getValue("status_gateway_was_stopped")), locale)
        }
    }

    // ── the shipped bundle ──────────────────────────────────────────────────

    @Test
    fun `the shipped bundle reads gatewayStopped with the same rule`() {
        assertTrue(bundle.contains("status_gateway_was_stopped"), "rebuild www")
        assertTrue(
            Regex("""([\w$]+)!==null&&\1\.gatewayStopped===!0""").containsMatchIn(bundle),
            "the bundle's rule differs from the source",
        )
        assertFalse(
            Regex("""\.phase!==[\w$]+&&[\w$]+\.gatewayStopped""").containsMatchIn(bundle),
            "the bundle still leaves refusals out (rebuild www)",
        )
        assertEquals(1, Regex("""\.gatewayStopped===!0""").findAll(bundle).count(), "read once")
    }

    @Test
    fun `the shipped bundle carries status_unchanged of every locale as the sources have it`() {
        for (locale in locales) {
            val text = entries(locale).getValue("status_unchanged")
            assertTrue(bundle.contains("status_unchanged:\"$text\""), "$locale: not in the bundle (rebuild www)")
        }
        assertEquals(locales.size, Regex("""status_unchanged:"""").findAll(bundle).count())
    }

    @Test
    fun `the shipped bundle carries the three texts of every locale as the sources have them`() {
        for (locale in locales) {
            val map = entries(locale)
            newKeys.forEach { key ->
                assertTrue(bundle.contains(map.getValue(key)), "$locale $key not in the bundle (rebuild www)")
            }
        }
        val shipped = Regex("""status_gw_not_ours:"([^"]*)"""").findAll(bundle).map { it.groupValues[1] }.toList()
        assertEquals(locales.size, shipped.size, shipped.toString())
        shipped.forEach { assertFalse(it.contains("Termux"), it) }
    }
}
