package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Settings → Install & Update (`SettingsStatus.tsx`), checked against its SOURCE and the shipped
 * bundle. Structural: they catch a consent being skipped (`startRun` / force stop without their
 * cards), a reason without a translation, a "nothing was changed" claim on a failure that may have
 * changed things, the restore order, a route or menu entry going missing, and the bridge
 * declarations drifting from the compiled native methods.
 */
internal class StatusScreenWwwContractTest {
    private val locales = listOf("en", "ko", "zh")

    private fun www(path: String): String {
        val f = File("../www/src/$path")
        assertTrue(f.isFile, "expected ${f.absolutePath}")
        return f.readText()
    }

    private val status by lazy { www("screens/SettingsStatus.tsx") }
    private val confirmCard by lazy { www("components/ConfirmCard.tsx") }

    private val allSources by lazy {
        File("../www/src")
            .walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".ts") || it.name.endsWith(".tsx")) }
            .associate { it.path to it.readText() }
    }

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

    private fun statusKeys(locale: String) = entries(locale).filterKeys { it.startsWith("status_") }

    /** `REASON_KEYS` of SettingsStatus: native reason name → translation key. */
    private val reasonKeys by lazy {
        val block = Regex("""const REASON_KEYS[^{]*\{([\s\S]*?)\n}""").find(status)?.groupValues?.get(1)
        assertNotNull(block, "REASON_KEYS not found")
        Regex("""(?m)^\s+([A-Z_]+):\s*'([a-z_]+)'""").findAll(block!!).associate {
            it.groupValues[1] to
                it.groupValues[2]
        }
    }

    /** The `<ConfirmCard …>…</ConfirmCard>` elements of SettingsStatus with the 60 characters before each. */
    private val confirmCards by lazy {
        Regex("""<ConfirmCard[\s\S]*?</ConfirmCard>""")
            .findAll(status)
            .map { m ->
                status.substring(maxOf(0, m.range.first - 60), m.range.first) to m.value
            }.toList()
    }

    // ── reasons and translations ────────────────────────────────────────────

    @Test
    fun `REASON_KEYS covers exactly the native reasons except UNKNOWN, which falls back`() {
        // 14 since CANCELLED (the user's cancel) was split from INTERRUPTED (a stop from elsewhere)
        assertEquals(14, reasonKeys.size, reasonKeys.toString())
        assertEquals(UpdateReason.entries.size - 1, reasonKeys.size, "one key per native reason but UNKNOWN")
        assertEquals("status_reason_cancelled", reasonKeys["CANCELLED"])
        assertEquals(UpdateReason.entries.map { it.name }.toSet() - "UNKNOWN", reasonKeys.keys)
        reasonKeys.forEach { (reason, key) -> assertEquals("status_reason_" + reason.lowercase(), key) }
        assertTrue(status.contains("|| 'status_reason_unknown'"), "no fallback to status_reason_unknown")
    }

    @Test
    fun `every reason key and the fallback exist with text in every locale`() {
        for (locale in locales) {
            val map = entries(locale)
            for (key in reasonKeys.values + "status_reason_unknown") {
                assertTrue(!map[key].isNullOrBlank(), "$locale.ts: $key missing or empty")
            }
        }
    }

    @Test
    fun `status keys are the same in all three locales and none is empty`() {
        val en = statusKeys("en")
        assertTrue(en.size >= 60, "too few status_ keys parsed: ${en.size}")
        for (locale in locales) {
            val keys = statusKeys(locale)
            assertEquals(en.keys, keys.keys, "$locale.ts status_ keys differ from en.ts")
            keys.forEach { (k, v) -> assertTrue(v.isNotBlank(), "$locale.ts: $k is empty") }
        }
    }

    @Test
    fun `every status key the screens use is defined`() {
        val used =
            listOf(status, www("screens/Settings.tsx"))
                .flatMap { src ->
                    Regex("""t\('(status_[a-z_0-9]+)'""").findAll(src).map { it.groupValues[1] }.toList()
                }.toSet()
        assertTrue(used.size > 30, used.toString())
        assertEquals(emptySet<String>(), used - statusKeys("en").keys)
    }

    private val unchangedClaims = UNCHANGED_CLAIMS

    /**
     * No reason text says whether anything was changed: the same reason (NO_SPACE, CACHE_STALE …)
     * comes from the pre-check and from later stages. That is said by one line only,
     * `status_unchanged`, which the page adds for a refusal or a failure up to `[2/5]`.
     */
    @Test
    fun `no reason text claims that nothing was changed or that the update was not started, in any language`() {
        for (locale in locales) {
            val map = entries(locale)
            val claims = unchangedClaims.getValue(locale)
            val reasonTexts = (reasonKeys.values + "status_reason_unknown").associateWith { map.getValue(it) }
            assertEquals(
                emptyMap<String, String>(),
                reasonTexts.filterValues { claims.containsMatchIn(it) },
                "$locale.ts: a reason claims no change",
            )
        }
    }

    @Test
    fun `only status_unchanged claims that nothing was changed, in every language`() {
        for (locale in locales) {
            val claims = unchangedClaims.getValue(locale)
            val claiming = statusKeys(locale).filterValues { claims.containsMatchIn(it) }.keys
            assertEquals(setOf("status_unchanged"), claiming, "$locale.ts")
            assertFalse(claims.containsMatchIn(statusKeys(locale).getValue("status_maybe_changed")), locale)
        }
    }

    // ── vc23: the cancelled line and the state-ownership hint ───────────────

    private val vc23Keys = listOf("status_hint_state_busy", "status_reason_cancelled", "status_last_cancelled")

    @Test
    fun `the three vc23 keys exist with text in every locale`() {
        for (locale in locales) {
            val map = entries(locale)
            vc23Keys.forEach { assertTrue(!map[it].isNullOrBlank(), "$locale.ts: $it missing or empty") }
            assertTrue(map.getValue("status_last_cancelled").contains("{time}"), "$locale.ts: no {time}")
        }
    }

    /** The phrase that says `oa --restore` is not what to run, per locale. */
    private val restoreNotNeeded = mapOf("en" to "is not needed", "ko" to "필요하지 않", "zh" to "不需要")

    @Test
    fun `the state hint says oa --restore is not needed and the doctor hint still offers it, in every language`() {
        for (locale in locales) {
            val map = entries(locale)
            val busy = map.getValue("status_hint_state_busy")
            val doctor = map.getValue("status_hint_doctor")
            assertTrue(busy.contains("openclaw doctor --fix"), "$locale: $busy")
            val mentions = Regex("""oa --restore""").findAll(busy).toList()
            assertTrue(mentions.size <= 1, "$locale: $busy")
            mentions.forEach { m ->
                // zh puts the negation before the command ("不需要 `oa --restore`")
                val around = busy.substring(maxOf(0, m.range.first - 8), minOf(busy.length, m.range.last + 20))
                assertTrue(around.contains(restoreNotNeeded.getValue(locale)), "$locale: oa --restore advised: $busy")
            }
            assertTrue(doctor.contains("oa --restore"), "$locale: $doctor")
            assertFalse(doctor.contains(restoreNotNeeded.getValue(locale)), "$locale: $doctor")
        }
    }

    @Test
    fun `the page's cancelled reason and state pattern are pinned`() {
        val cancelled = Regex("""(?m)^const CANCELLED_REASON = '([A-Z_]+)'$""").find(status)?.groupValues?.get(1)
        assertEquals(UpdateReason.CANCELLED.name, cancelled)
        val pattern = Regex("""(?m)^const STATE_BUSY_PATTERN = (/.*/[a-z]*)$""").find(status)?.groupValues?.get(1)
        // no g flag: test() must keep no state between calls
        assertEquals("/state ownership|StateOwnerContention|database is busy/i", pattern)
        // one rule for both places, and the card judges the whole detail (not the 6 lines it shows)
        assertTrue(status.contains("repairHintKey(result.reason, result.detail)"))
        assertTrue(status.contains("repairHintKey(lastRun?.reason, lastRun?.detail)"))
        assertFalse(Regex("""repairHintKey\([^)]*detailLines""").containsMatchIn(status))
    }

    @Test
    fun `the shipped bundle has the vc23 keys and the state pattern`() {
        val index = File("src/main/assets/www/index.html")
        val js = Regex("""src="\./(assets/index-[A-Za-z0-9_-]+\.js)"""").find(index.readText())
        assertNotNull(js, "index.html names no bundle")
        val bundle = File("src/main/assets/www/${js!!.groupValues[1]}").readText()
        (vc23Keys + listOf("state ownership|StateOwnerContention|database is busy")).forEach {
            assertTrue(bundle.contains(it), "bundle lacks $it (rebuild www)")
        }
        // the translations ship too (ko's "취소됨" as written or escaped)
        val ko = bundle.contains("취소됨") || bundle.contains("\\uCDE8\\uC18C\\uB428", ignoreCase = true)
        assertTrue(ko, "bundle lacks ko text")
    }

    /** The stage line of the result card ([StatusScreenBehaviorTest] runs it; this pins its rules). */
    @Test
    fun `stageLineKey says unchanged only for a refusal or a failure up to the last untouched stage`() {
        val fn = Regex("""function stageLineKey\(r: RunEvent\): TranslationKey \| null \{([\s\S]*?)\n}""").find(status)
        assertNotNull(fn, "stageLineKey not found")
        val steps =
            fn!!
                .groupValues[1]
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("//") }
        assertEquals(
            listOf(
                "if (r.phase === REFUSED_PHASE) return 'status_unchanged'",
                "if (r.phase !== 'failed') return null",
                "if (r.reason && DOCTOR_REASONS.includes(r.reason)) return null",
                "if (typeof r.stage !== 'number') return null",
                "return r.stage <= LAST_UNTOUCHED_STAGE ? 'status_unchanged' : 'status_maybe_changed'",
            ),
            steps,
        )
        assertTrue(status.contains("const resultStageKey = result ? stageLineKey(result) : null"))
        assertTrue(status.contains("{resultStageKey && <div style={SECONDARY}>{t(resultStageKey)}</div>}"))
    }

    /** The page's "nothing was changed up to here" stage is native's last cancelable stage. */
    @Test
    fun `the page's constants match native's phases, reasons and stages`() {
        fun constant(name: String): String {
            val m = Regex("""(?m)^const $name = (.+)$""").find(status)
            assertNotNull(m, "$name not found")
            return m!!.groupValues[1].trim()
        }
        assertEquals("'${ManagedRunner.REFUSED}'", constant("REFUSED_PHASE"))
        assertEquals(
            "['${ManagedRunGuard.DONE}', '${ManagedRunGuard.FAILED}', '${ManagedRunGuard.CANCELLED}', REFUSED_PHASE]",
            constant("END_PHASES"),
        )
        assertEquals("['${ManagedRunGuard.RUNNING}', '${ManagedRunGuard.CANCELLING}']", constant("BUSY_PHASES"))
        assertEquals("${RunKinds.spec(RunKinds.UPDATE)!!.lastCancelableStage}", constant("LAST_UNTOUCHED_STAGE"))
        val names = UpdateReason.entries.map { it.name }.toSet()

        fun list(name: String) = Regex("""'([A-Z_]+)'""").findAll(constant(name)).map { it.groupValues[1] }.toList()
        assertEquals(listOf("MIGRATION_FAILED", "HEALTH_FAILED"), list("DOCTOR_REASONS"))
        assertEquals(listOf("GATEWAY_RUNNING", "GATEWAY_STOP_FAILED"), list("NO_DETAIL_REASONS"))
        assertTrue(names.containsAll(list("DOCTOR_REASONS") + list("NO_DETAIL_REASONS")))
        assertEquals("1000", constant("PRECHECK_POLL_MS"))
        assertEquals("60_000", constant("PRECHECK_GIVE_UP_MS"))
    }

    /** A refusal is an end: the run handler releases the start before anything else. */
    @Test
    fun `the run handler ends the start for every event, a refusal included`() {
        val handler = Regex("""const onRunEvent = useCallback\([\s\S]*?\n {2}}, \[\]\)""").find(status)?.value
        assertNotNull(handler)
        val lines = handler!!.lines().map { it.trim() }
        val filter = lines.indexOf("if (d.kind && d.kind !== RUN_KIND) return")
        assertTrue(filter >= 0, handler)
        assertEquals(
            listOf("starting.current = false", "setPrechecking(false)"),
            lines.subList(filter + 1, filter + 3),
            "the start is not released first",
        )
        val end = handler.substring(handler.indexOf("if (isEnd(d))"))
        assertTrue(end.indexOf("if (isBusy(now))") in 0 until end.indexOf("setResult(d)"), end)
    }

    @Test
    fun `the render order is run, preparing, consent, result, then the update button`() {
        val order = listOf("{run ? (", ") : prechecking ? (", ") : confirmUpdate ? (", ") : result ? (")
        val at = order.map { status.indexOf(it) }
        order.zip(at).forEach { (s, i) -> assertTrue(i >= 0, "'$s' missing") }
        assertEquals(at.sorted(), at, order.zip(at).toString())
        assertTrue(status.contains("const busyUi = run !== null || prechecking"))
        assertEquals(2, Regex("""disabled=\{busyUi}""").findAll(status).count(), "a start button is enabled while busy")
    }

    // ── consent ─────────────────────────────────────────────────────────────

    @Test
    fun `startRun is called only by startUpdate, and startUpdate only by the update consent card`() {
        val calls = Regex("""bridge\.call\('startRun'""").findAll(status).count()
        assertEquals(1, calls)
        val fn = Regex("""function startUpdate\(\)[\s\S]*?\n {2}}""").find(status)?.value
        assertNotNull(fn, "startUpdate not found")
        assertTrue(fn!!.contains("bridge.call('startRun', RUN_KIND, gwRunning)"), fn)
        assertTrue(fn.contains("if (starting.current) return"), "a double tap could start twice")
        // startUpdate is referenced once more: as the consent card's onConfirm
        assertEquals(2, Regex("""\bstartUpdate\b""").findAll(status).count())
        val card = confirmCards.single { it.second.contains("onConfirm={startUpdate}") }.second
        assertTrue(card.contains("confirmLabel={t('status_confirm_start')}"), card)
        val others = allSources.filterKeys { !it.endsWith("SettingsStatus.tsx") && !it.endsWith("bridge.ts") }
        assertEquals(emptyList<String>(), others.filterValues { it.contains("'startRun'") }.keys.toList())
    }

    @Test
    fun `the force stop is called only from its own danger card, shown only after a normal stop failed`() {
        assertEquals(1, Regex("""bridge\.call\('stopGateway'""").findAll(status).count())
        assertEquals(1, Regex("""stopGateway\(true\)""").findAll(status).count())
        assertEquals(1, Regex("""stopGateway\(false\)""").findAll(status).count())
        val (before, force) = confirmCards.single { it.second.contains("onConfirm={() => stopGateway(true)}") }
        assertTrue(force.contains("confirmLabel={t('status_gw_force_confirm')}"), force)
        assertTrue(Regex("""\bdanger\b""").containsMatchIn(force), "the force card is not marked danger")
        assertTrue(before.contains("gwStep === 'confirmForce' &&"), before)
        val (normalBefore, normal) = confirmCards.single { it.second.contains("onConfirm={() => stopGateway(false)}") }
        assertTrue(normal.contains("confirmLabel={t('status_gw_stop_confirm')}"), normal)
        assertTrue(normalBefore.contains("gwStep === 'confirm' &&"), normalBefore)
        // confirmForce is entered only when a NORMAL stop answered STILL_RUNNING
        val sets = Regex("""setGwStep\('confirmForce'\)""").findAll(status).toList()
        assertEquals(1, sets.size)
        val around = status.substring(sets.single().range.first - 200, sets.single().range.first)
        assertTrue(around.contains("d.result === 'STILL_RUNNING'") && around.contains("asked === 'normal'"), around)
        val others = allSources.filterKeys { !it.endsWith("SettingsStatus.tsx") && !it.endsWith("bridge.ts") }
        assertEquals(emptyList<String>(), others.filterValues { it.contains("'stopGateway'") }.keys.toList())
    }

    // ── events and restore ──────────────────────────────────────────────────

    @Test
    fun `the restore effect reads native state after the listeners and late busy events are ignored`() {
        val listenRun = status.indexOf("useNativeEvent('run_progress'")
        val listenGw = status.indexOf("useNativeEvent('gateway_state'")
        val restore = Regex("""useEffect\(\(\) => \{\s*const now = readRunState\(\)""").find(status)
        assertNotNull(restore, "restore effect not found")
        assertTrue(listenRun in 0 until restore!!.range.first, "the restore read runs before the run listener")
        assertTrue(listenGw in 0 until restore.range.first, "the restore read runs before the gateway listener")
        val effect = status.substring(restore.range.first, status.indexOf("}, [])", restore.range.first))
        listOf("setRun(isBusy(now) ? now : null)", "setLastRun(readLastRun())", "setGateway(readGateway())").forEach {
            assertTrue(effect.contains(it), "restore effect lacks $it")
        }
        val handler = Regex("""const onRunEvent = useCallback\([\s\S]*?\n {2}}, \[\]\)""").find(status)?.value
        assertNotNull(handler)
        val busyBranch = handler!!.substring(handler.indexOf("if (isBusy(d))"))
        assertTrue(
            busyBranch.indexOf("if (!isBusy(now))") in 0 until busyBranch.indexOf("setRun(d)"),
            "a busy event is shown without checking native is still busy",
        )
    }

    @Test
    fun `only SettingsStatus listens to run_progress and gateway_state`() {
        for (event in listOf("run_progress", "gateway_state")) {
            val listeners = allSources.filterValues { it.contains("useNativeEvent('$event'") }.keys
            assertEquals(1, listeners.size, "$event: $listeners")
            assertTrue(listeners.single().endsWith("screens/SettingsStatus.tsx"))
        }
    }

    // ── route, menu, dashboard ──────────────────────────────────────────────

    @Test
    fun `the status screen is routed and in the settings menu, the old screens are gone`() {
        val app = www("App.tsx")
        assertTrue(
            Regex("""import\s*\{\s*SettingsStatus\s*}\s*from\s*'\./screens/SettingsStatus'""").containsMatchIn(app),
        )
        assertTrue(Regex("""path\s*===\s*'/settings/status'\)\s*return\s*<SettingsStatus\s*/>""").containsMatchIn(app))
        val menu = www("screens/Settings.tsx").lineSequence().firstOrNull { it.contains("'/settings/status'") }
        assertNotNull(menu, "no /settings/status menu entry")
        assertTrue(menu!!.contains("t('status_title')") && menu.contains("t('status_menu_desc')"), menu)
        for ((path, src) in allSources) {
            assertFalse(src.contains("/settings/platforms"), "$path routes to /settings/platforms")
            assertFalse(src.contains("/settings/updates"), "$path routes to /settings/updates")
            assertFalse(src.contains("SettingsPlatforms") || src.contains("SettingsUpdates"), path)
        }
        assertFalse(File("../www/src/screens/SettingsPlatforms.tsx").exists())
        assertFalse(File("../www/src/screens/SettingsUpdates.tsx").exists())
    }

    @Test
    fun `the dashboard Update item opens the status screen instead of typing oa --update`() {
        val dash = www("screens/Dashboard.tsx")
        val mgmt = Regex("""function getManagement\(\)[\s\S]*?\n}""").find(dash)?.value
        assertNotNull(mgmt)
        val update = mgmt!!.lineSequence().single { it.contains("label: 'Update'") }
        assertTrue(update.contains("route: '/settings/status'"), update)
        assertFalse(update.contains("commandId"), update)
        assertTrue(Regex("""if \(item\.route\) navigate\(item\.route\)""").containsMatchIn(dash))
        // the terminal path is kept on the status screen
        assertTrue(status.contains("bridge.call('writeCommandToTerminal', 'oaUpdate')"))
    }

    // ── ConfirmCard ─────────────────────────────────────────────────────────

    @Test
    fun `ConfirmCard focuses Cancel first, cancels on Escape and puts Cancel before Confirm`() {
        assertTrue(confirmCard.contains("role=\"alertdialog\""))
        assertTrue(
            Regex(
                """useEffect\(\(\) => \{\s*cancelRef\.current\?\.focus\(\)\s*}, \[]\)""",
            ).containsMatchIn(confirmCard),
        )
        assertTrue(Regex("""if \(e\.key === 'Escape'\) onCancel\(\)""").containsMatchIn(confirmCard))
        val cancel = confirmCard.indexOf("ref={cancelRef}")
        val onCancel = confirmCard.indexOf("onClick={onCancel}")
        val onConfirm = confirmCard.indexOf("onClick={onConfirm}")
        assertTrue(cancel in 0 until onCancel, "the focused button is not the cancel button")
        assertTrue(onCancel < onConfirm, "Confirm comes before Cancel")
        assertEquals(1, Regex("""onClick=\{onConfirm}""").findAll(confirmCard).count())
        assertFalse(confirmCard.contains("autoFocus"), "something else takes the focus")
    }

    // ── bridge declarations and the bundle ──────────────────────────────────

    @Test
    fun `bridge ts declares the six managed-run methods with the compiled native signatures`() {
        val ts = www("lib/bridge.ts")
        val expected =
            mapOf(
                "startRun(kind: string, stopGateway: boolean): void" to
                    listOf(String::class.java, Boolean::class.javaPrimitiveType),
                "cancelRun(): void" to emptyList(),
                "getRunState(): string" to emptyList(),
                "getLastRun(): string" to emptyList(),
                "getGatewayStatus(): string" to emptyList(),
                "stopGateway(force: boolean): void" to listOf(Boolean::class.javaPrimitiveType),
            )
        val methods = JsBridge::class.java.methods.associateBy { it.name }
        for ((decl, params) in expected) {
            assertTrue(Regex("""(?m)^\s*${Regex.escape(decl)}\s*$""").containsMatchIn(ts), "bridge.ts lacks: $decl")
            val name = decl.substringBefore('(')
            val m = methods.getValue(name)
            assertEquals(params, m.parameterTypes.toList(), name)
            val returns = if (decl.endsWith("string")) String::class.java else Void.TYPE
            assertEquals(returns, m.returnType, name)
            assertTrue(m.isAnnotationPresent(android.webkit.JavascriptInterface::class.java), "$name is not exposed")
        }
    }

    @Test
    fun `the shipped bundle is built from this screen`() {
        val index = File("src/main/assets/www/index.html")
        val js = Regex("""src="\./(assets/index-[A-Za-z0-9_-]+\.js)"""").find(index.readText())
        assertNotNull(js, "index.html names no bundle")
        val bundle = File("src/main/assets/www/${js!!.groupValues[1]}").readText()
        val present =
            listOf(
                "\"run_progress\"",
                "\"gateway_state\"",
                "/settings/status",
                "\"startRun\"",
                "\"stopGateway\"",
                "alertdialog",
                "Escape",
            ) +
                reasonKeys.values.map { "status_reason_" + it.removePrefix("status_reason_") } +
                "status_reason_unknown" +
                listOf(
                    "status_unchanged",
                    "status_maybe_changed",
                    "status_prechecking",
                    "status_refused_unknown",
                    "\"refused\"",
                )
        present.forEach { assertTrue(bundle.contains(it), "bundle lacks $it") }
        listOf("/settings/platforms", "/settings/updates", "install_progress").forEach {
            assertFalse(bundle.contains(it), "bundle still has $it")
        }
    }

    internal companion object {
        /** Wordings that claim nothing was changed or started, per locale: only `status_unchanged` may match. */
        val UNCHANGED_CLAIMS =
            mapOf(
                "en" to
                    Regex(
                        """(?i)nothing (was|has been) changed|no changes were made|was not changed|""" +
                            """(was|were) not started|never started""",
                    ),
                "ko" to Regex("""변경하지 않았|변경되지 않았|바뀌지 않았|바뀐 것이 없|시작하지 않았|시작되지 않았"""),
                "zh" to Regex("""未做任何更改|没有更改|未更改|没有做任何更改|未开始|没有开始"""),
            )
    }
}
