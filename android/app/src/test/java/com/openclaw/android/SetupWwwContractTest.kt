package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The first-install page's texts and wiring, read from the www sources: the `setup_*` keys of the
 * three locales agree (keys and `{placeholders}`), the wording rules hold (only NO_SPACE may say
 * nothing was installed; VERIFY_FAILED says the install was stopped for safety; the keep-open
 * notice and the "press Enter in the terminal" clause exist in every locale; no model names with
 * versions), the reason table covers what native sends, and the onboarding command and the cancel
 * are wired as the plan says.
 */
internal class SetupWwwContractTest {
    private val locales = listOf("en", "ko", "zh")

    private fun www(path: String): String {
        val f = File("../www/src/$path")
        assertTrue(f.isFile, "expected ${f.absolutePath}")
        return f.readText()
    }

    /** key → value (quotes and the trailing comma removed, `\'` unescaped) of every `  key: '…',` line. */
    private fun entries(locale: String): Map<String, String> =
        Regex("""(?m)^\s+([a-z_0-9]+):\s*(['"`])(.*)\2,?\s*$""")
            .findAll(www("i18n/$locale.ts"))
            .associate { it.groupValues[1] to it.groupValues[3].replace("\\'", "'").replace("\\\"", "\"") }

    private val all by lazy { locales.associateWith { entries(it) } }

    private fun setupKeys(locale: String) =
        all
            .getValue(locale)
            .keys
            .filter { it.startsWith("setup_") }
            .toSet()

    private fun placeholders(text: String) =
        Regex("""\{([a-zA-Z]+)\}""").findAll(text).map { it.groupValues[1] }.toSet()

    @Test
    fun `the setup keys are the same in the three locales and none is empty`() {
        val en = setupKeys("en")
        assertTrue(en.size >= 50, "too few setup keys parsed: ${en.size}")
        locales.forEach { locale ->
            assertEquals(en, setupKeys(locale), locale)
            setupKeys(locale).forEach { assertTrue(all.getValue(locale).getValue(it).isNotBlank(), "$locale $it") }
        }
    }

    @Test
    fun `every key's placeholders are the same in the three locales`() {
        val en = all.getValue("en")
        en.forEach { (key, text) ->
            val want = placeholders(text)
            locales.drop(1).forEach { locale ->
                val other = all.getValue(locale)[key] ?: return@forEach
                assertEquals(want, placeholders(other), "$locale $key")
            }
        }
    }

    @Test
    fun `the placeholders the page fills are the ones the texts have`() {
        val expected =
            mapOf(
                "setup_reason_no_space_mb" to setOf("need", "have"),
                "setup_stopped_at" to setOf("n", "total", "name"),
                "setup_warn_tool" to setOf("name"),
                "status_stage" to setOf("n", "total"),
                "setup_tools_desc" to setOf("platform"),
            )
        expected.forEach { (key, want) ->
            locales.forEach { assertEquals(want, placeholders(all.getValue(it).getValue(key)), "$it $key") }
        }
        val run = www("lib/setupRun.ts")
        assertTrue(run.contains("vars: { need: String(needMb), have: String(haveMb) }"))
        val setup = www("screens/Setup.tsx")
        assertTrue(
            setup.contains(
                "t('setup_stopped_at', { n: String(n), total: String(SETUP_STAGE_TOTAL), " +
                    "name: t(SETUP_STAGE_KEYS[n - 1]) })",
            ),
        )
        assertTrue(setup.contains("t('setup_warn_tool', { name })"))
    }

    @Test
    fun `no setup text names a model with a version`() {
        val version = Regex("""(?i)\b(claude|opus|sonnet|haiku|gpt|gemini|codex)[ -]?\d""")
        locales.forEach { locale ->
            setupKeys(locale).forEach { key ->
                val text = all.getValue(locale).getValue(key)
                assertFalse(version.containsMatchIn(text), "$locale $key: $text")
            }
        }
    }

    // ── wording ─────────────────────────────────────────────────────────────

    /** "Nothing was installed / changed" — only the storage refusal may say it (the script does). */
    private val nothingClaims =
        mapOf(
            "en" to
                listOf(
                    Regex("""(?i)nothing (was|has been) (installed|changed)"""),
                    Regex("""(?i)no changes? (was|were) made"""),
                ),
            "ko" to listOf(Regex("""아무것도 설치하지 않았"""), Regex("""변경하지 않았"""), Regex("""바뀌지 않았""")),
            "zh" to listOf(Regex("""未安装任何"""), Regex("""未(作|做)?任何(更改|改动)"""), Regex("""未更改""")),
        )

    @Test
    fun `only the NO_SPACE keys say that nothing was installed`() {
        val allowed = setOf("setup_reason_no_space", "setup_reason_no_space_mb")
        locales.forEach { locale ->
            setupKeys(locale).forEach { key ->
                val text = all.getValue(locale).getValue(key)
                val claims = nothingClaims.getValue(locale).any { it.containsMatchIn(text) }
                if (key in allowed) {
                    assertTrue(claims, "$locale $key must say nothing was installed: $text")
                } else {
                    assertFalse(claims, "$locale $key claims nothing was installed: $text")
                }
            }
        }
    }

    @Test
    fun `VERIFY_FAILED says the install was stopped for safety, in every locale`() {
        assertTrue(all.getValue("en").getValue("setup_reason_verify_failed").contains("stopped for safety"))
        assertTrue(all.getValue("ko").getValue("setup_reason_verify_failed").contains("안전을 위해 설치를 중단했습니다"))
        assertTrue(all.getValue("zh").getValue("setup_reason_verify_failed").contains("为安全起见已中止安装"))
    }

    @Test
    fun `the keep-open notice exists in every locale and is shown on the progress card`() {
        assertEquals("Keep the app open until the installation finishes.", all.getValue("en")["setup_keep_open"])
        assertTrue(all.getValue("ko").getValue("setup_keep_open").contains("앱을 닫지 마세요"))
        assertTrue(all.getValue("zh").getValue("setup_keep_open").contains("不要关闭应用"))
        assertTrue(www("screens/Setup.tsx").contains("{t('setup_keep_open')}"))
    }

    @Test
    fun `the gateway texts tell to press Enter in the terminal, in every locale`() {
        val clause = mapOf("en" to "press Enter in the terminal", "ko" to "터미널에서 Enter 를 누르세요", "zh" to "在终端中按 Enter")
        for (key in listOf("status_gw_stop_body", "status_gateway_was_stopped")) {
            clause.forEach { (locale, words) ->
                assertTrue(
                    all.getValue(locale).getValue(key).contains(words),
                    "$locale $key: ${all.getValue(locale)[key]}",
                )
            }
        }
    }

    @Test
    fun `the onboarding text says the command is only typed and Enter is the user's`() {
        val words = mapOf("en" to "press Enter yourself", "ko" to "Enter 를 직접 누르세요", "zh" to "自行按 Enter")
        words.forEach { (locale, w) ->
            assertTrue(all.getValue(locale).getValue("setup_onboard_desc").contains(w), locale)
        }
        locales.forEach {
            assertTrue(
                all.getValue(it).getValue("setup_onboard_desc").contains("`openclaw onboard`"),
                it,
            )
        }
    }

    @Test
    fun `the cancel confirmation says the install can be continued later`() {
        assertEquals("You can continue the installation later.", all.getValue("en")["setup_cancel_body"])
        assertEquals("다음에 이어서 설치할 수 있습니다.", all.getValue("ko")["setup_cancel_body"])
        assertTrue(all.getValue("zh").getValue("setup_cancel_body").contains("继续安装"))
    }

    // ── the reason table and the wiring ─────────────────────────────────────

    private val reasonKeys: Map<String, String> by lazy {
        val block = www("lib/setupRun.ts").substringAfter("export const SETUP_REASON_KEYS").substringBefore("\n}\n")
        Regex("""([A-Z_]+): '(setup_reason_[a-z_]+)'""").findAll(block).associate {
            it.groupValues[1] to
                it.groupValues[2]
        }
    }

    @Test
    fun `the reason table covers every reason a SETUP run or getSetupResult can give, UNKNOWN by fallback`() {
        val native =
            (
                SetupReasons.codes.values +
                    listOf(UpdateReason.BUSY, UpdateReason.INTERRUPTED, UpdateReason.CANCELLED, UpdateReason.NO_SPACE)
            ).map { it.name }
                .toSet() - "UNKNOWN"
        assertEquals(native, reasonKeys.keys)
        reasonKeys.values.forEach { key ->
            locales.forEach { assertTrue(all.getValue(it).containsKey(key), "$it $key") }
        }
        assertTrue(www("lib/setupRun.ts").contains("|| 'setup_reason_unknown'"))
        assertEquals(reasonKeys.size, reasonKeys.values.toSet().size, "each reason has its own text")
        UpdateReason.SETUP_ONLY.forEach { assertTrue(it.name in reasonKeys, it.name) }
    }

    @Test
    fun `the onboarding command id is typed without Enter and maps to openclaw onboard`() {
        val setup = www("screens/Setup.tsx")
        assertTrue(setup.contains("const ONBOARD_COMMAND = 'openclawOnboard'"))
        val writes =
            Regex("""bridge\.call\('writeCommandToTerminal', ([^)]*)\)""")
                .findAll(setup)
                .map {
                    it.groupValues[1]
                }.toList()
        assertEquals(listOf("ONBOARD_COMMAND"), writes)
        assertEquals("openclaw onboard", BridgeGuard.terminalCommands.getValue("openclawOnboard"))
        BridgeGuard.terminalCommands.values.forEach { assertFalse(it.contains('\n') || it.contains('\r'), it) }
    }

    @Test
    fun `the cancel goes through its confirmation only`() {
        val setup = www("screens/Setup.tsx")
        assertEquals(1, Regex("""bridge\.call\('cancelRun'\)""").findAll(setup).count())
        assertTrue(setup.contains("onConfirm={cancelSetup}"))
        assertEquals(1, Regex("""\bcancelSetup\b""").findAll(setup.substringAfter("function cancelSetup()")).count())
    }

    @Test
    fun `the page starts only the SETUP kind and reads getSetupResult through the bridge`() {
        val run = www("lib/setupRun.ts")
        assertTrue(run.contains("export const SETUP_KIND = 'SETUP'"))
        assertTrue(run.contains("bridge.callJson<SetupResult>('getSetupResult')"))
        assertTrue(www("lib/bridge.ts").contains("getSetupResult(): string"))
        assertTrue(run.contains("export const SETUP_STAGE_TOTAL = 7"))
        val stageKeys =
            Regex("""'(setup_stage_\d)'""")
                .findAll(run.substringAfter("SETUP_STAGE_KEYS"))
                .map {
                    it.groupValues[1]
                }.toList()
        assertEquals((1..7).map { "setup_stage_$it" }, stageKeys)
    }
}
