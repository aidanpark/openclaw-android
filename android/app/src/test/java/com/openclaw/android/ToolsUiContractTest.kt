package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The www side of Settings → Tools, checked against its SOURCE. These are structural tests (no
 * page is rendered): they catch a route, binding, key or reason mapping going missing, not how
 * the page behaves. Native values (ToolFailure, ToolInstallGuard phases) come from compiled code.
 */
internal class ToolsUiContractTest {
    private val locales = listOf("en", "ko", "zh")

    private fun www(path: String): String {
        val f = File("../www/src/$path")
        assertTrue(f.isFile, "expected ${f.absolutePath}")
        return f.readText()
    }

    private val app by lazy { www("App.tsx") }
    private val settings by lazy { www("screens/Settings.tsx") }
    private val tools by lazy { www("screens/SettingsTools.tsx") }
    private val bridgeTs by lazy { www("lib/bridge.ts") }

    /** key → value (quotes stripped) for every `  key: '…',` / `"…"` line. */
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

    // ── route and menu ──────────────────────────────────────────────────────

    @Test
    fun `App tsx imports SettingsTools and routes settings tools to it`() {
        assertTrue(
            Regex("""import\s*\{\s*SettingsTools\s*}\s*from\s*'\./screens/SettingsTools'""").containsMatchIn(app),
        )
        assertTrue(
            Regex("""path\s*===\s*'/settings/tools'\)\s*return\s*<SettingsTools\s*/>""").containsMatchIn(app),
            "route missing",
        )
    }

    @Test
    fun `Settings menu links to settings tools with translated label and description`() {
        val line = settings.lineSequence().firstOrNull { it.contains("'/settings/tools'") }
        assertNotNull(line, "no /settings/tools menu entry")
        assertTrue(line!!.contains("t('settings_tools')"), line)
        assertTrue(line.contains("t('settings_tools_desc')"), line)
    }

    // ── bridge bindings ─────────────────────────────────────────────────────

    @Test
    fun `bridge ts declares the tool methods with the native signatures`() {
        assertTrue(Regex("""(?m)^\s*installTool\(id: string\): void""").containsMatchIn(bridgeTs))
        assertTrue(Regex("""(?m)^\s*cancelToolInstall\(\): void""").containsMatchIn(bridgeTs))
        assertTrue(Regex("""(?m)^\s*getToolInstallState\(\): string""").containsMatchIn(bridgeTs))
        // Native side: compiled methods, with the arity the page uses
        val m = JsBridge::class.java.methods.associateBy { it.name }
        assertEquals(1, m.getValue("installTool").parameterCount)
        assertEquals(0, m.getValue("cancelToolInstall").parameterCount)
        assertEquals(String::class.java, m.getValue("getToolInstallState").returnType)
    }

    // ── SettingsTools behavior as written ───────────────────────────────────

    @Test
    fun `SettingsTools never removes a tool and never claims one optimistically`() {
        assertFalse(tools.contains("uninstallTool"), "SettingsTools calls uninstallTool")
        assertFalse(tools.contains("handleUninstall"), "SettingsTools has an uninstall handler")
        // "installed" and "broken" only ever come from readInstalled() (getInstalledTools), never from an id
        val sets = Regex("""setInfo\(([^)]*\)?)\)""").findAll(tools).map { it.groupValues[1] }.toList()
        assertTrue(sets.isNotEmpty())
        sets.forEach { assertEquals("readInstalled()", it, "setInfo($it) does not re-read native") }
        assertFalse(tools.contains("setInstalled("), "a second setter for installed exists")
        assertTrue(Regex("""useState<InstalledInfo>\(\(\)\s*=>\s*readInstalled\(\)\)""").containsMatchIn(tools))
        assertTrue(tools.contains("const { installed } = info"), "installed is not read from info")
        // broken = what native remembers, plus only the tools whose run check said "failed"
        assertTrue(
            tools.contains(
                "const broken = new Set([...info.broken, " +
                    "...Object.keys(checked).filter(id => checked[id] === 'failed')])",
            ),
            "broken is not info.broken plus the failed run checks",
        )
        assertEquals(1, Regex("""\bconst broken\b""").findAll(tools).count(), "a second broken set exists")
        // the run-check answers only ever come from a tools_check event or are cleared
        val checkedSets = Regex("""setChecked\(([^\n]*)\)""").findAll(tools).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("{}", "prev => ({ ...prev, [target]: status })"), checkedSets)
        assertTrue(tools.contains("bridge.callJson<Array<{ id: string; broken?: boolean }>>('getInstalledTools')"))
    }

    @Test
    fun `SettingsTools re-reads the disk on every final phase native produces`() {
        val finals = listOf(ToolInstallGuard.DONE, ToolInstallGuard.FAILED, ToolInstallGuard.CANCELLED)
        val branch = Regex("""if \(([^)]*'done'[^)]*)\)\s*\{([^}]*)}""").find(tools)
        assertNotNull(branch, "no final-phase branch")
        finals.forEach { assertTrue(branch!!.groupValues[1].contains("d.phase === '$it'"), "phase $it not handled") }
        assertTrue(branch!!.groupValues[2].contains("setInfo(readInstalled())"))
    }

    @Test
    fun `SettingsTools handles the busy phases native produces`() {
        for (phase in listOf(ToolInstallGuard.RUNNING, ToolInstallGuard.CANCELLING)) {
            assertTrue(tools.contains("d.phase === '$phase'"), "event phase $phase not handled")
            assertTrue(tools.contains("now.phase === '$phase'"), "restored phase $phase not handled")
        }
    }

    @Test
    fun `SettingsTools restores from getToolInstallState and offers cancel`() {
        assertTrue(tools.contains("bridge.callJson<ToolEvent>('getToolInstallState')"))
        assertTrue(tools.contains("bridge.call('cancelToolInstall')"))
        assertTrue(tools.contains("t('tool_cancel_requested')"))
        assertTrue(tools.contains("t('tool_cancel')"))
        assertTrue(tools.contains("t('tool_cancelled')"))
        assertTrue(tools.contains("t('tool_long_running')"))
        assertTrue(tools.contains("t('tool_not_available')"))
        assertTrue(tools.contains("d.errorKind === 'TERMINAL_ONLY'"))
        assertTrue(tools.contains("d.errorKind === 'UNINSTALL_UNSUPPORTED'"))
    }

    @Test
    fun `every ToolFailure except UNKNOWN has a translated reason and UNKNOWN falls back`() {
        val body = Regex("""function reasonText[\s\S]*?\n}""").find(tools)?.value
        assertNotNull(body, "reasonText not found")
        val mapped =
            Regex("""(?m)^\s+([A-Z_]+):\s*'(tool_err_[a-z_]+)'""").findAll(body!!).associate {
                it.groupValues[1] to
                    it.groupValues[2]
            }
        val expected = ToolFailure.entries.map { it.name }.toSet() - "UNKNOWN"
        assertEquals(expected, mapped.keys)
        mapped.forEach { (reason, key) -> assertEquals("tool_err_" + reason.lowercase(), key) }
        assertTrue(body.contains("|| 'tool_err_unknown'"), "no fallback to tool_err_unknown")
    }

    // ── event names: tool installs have their own event ─────────────────────

    private fun listened(source: String): Set<String> =
        Regex("""useNativeEvent\(\s*'([a-z_]+)'""").findAll(source).map { it.groupValues[1] }.toSet()

    @Test
    fun `SettingsTools listens to tool_progress and the run check, and not to install_progress`() {
        assertEquals(setOf("tool_progress", "tools_check"), listened(tools))
        assertFalse(tools.contains("install_progress"), "SettingsTools mentions install_progress")
    }

    /**
     * The platform and update screens that listened to `install_progress` were replaced by
     * Settings → Install & Update (status screen, stage A). What the old test protected — tool
     * events never reach another screen and each kind of run has its own event — now reads: only
     * SettingsTools hears `tool_progress`, only SettingsStatus hears `run_progress`/`gateway_state`,
     * and no screen listens to `install_progress` any more.
     */
    @Test
    fun `each run kind has its own listener - tools, managed runs, and nobody on install_progress`() {
        val sources =
            File("../www/src")
                .walkTopDown()
                .filter { it.isFile && (it.name.endsWith(".ts") || it.name.endsWith(".tsx")) }
                .associate { it.name to it.readText() }
        assertTrue(sources.size > 10, sources.keys.toString())
        val listeners = sources.mapValues { listened(it.value) }

        fun who(event: String) = listeners.filterValues { event in it }.keys
        assertEquals(setOf("SettingsTools.tsx"), who("tool_progress"))
        assertEquals(setOf("SettingsStatus.tsx"), who("run_progress"))
        assertEquals(setOf("SettingsStatus.tsx"), who("gateway_state"))
        assertEquals(emptySet<String>(), who("install_progress"))
        assertEquals(setOf("run_progress", "gateway_state"), listened(sources.getValue("SettingsStatus.tsx")))
        assertFalse(sources.getValue("SettingsStatus.tsx").contains("tool_progress"))
        assertFalse(sources.getValue("SettingsStatus.tsx").contains("install_progress"))
        assertFalse(File("../www/src/screens/SettingsPlatforms.tsx").exists(), "SettingsPlatforms.tsx is back")
        assertFalse(File("../www/src/screens/SettingsUpdates.tsx").exists(), "SettingsUpdates.tsx is back")
    }

    @Test
    fun `no other www source listens to tool_progress`() {
        val offenders =
            File("../www/src")
                .walkTopDown()
                .filter { it.isFile && (it.name.endsWith(".ts") || it.name.endsWith(".tsx")) }
                .filter { it.name != "SettingsTools.tsx" && it.readText().contains("tool_progress") }
                .map { it.path }
                .toList()
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `the shipped bundle is built from the tool_progress source`() {
        val index = File("src/main/assets/www/index.html")
        assertTrue(index.isFile, index.absolutePath)
        val js = Regex("""src="\./(assets/index-[A-Za-z0-9_-]+\.js)"""").find(index.readText())
        assertNotNull(js, "index.html names no bundle")
        val bundle = File("src/main/assets/www/${js!!.groupValues[1]}").readText()
        assertTrue(
            bundle.contains("\"tool_progress\"") || bundle.contains("'tool_progress'"),
            "bundle lacks tool_progress",
        )
        assertTrue(bundle.contains("tool_reinstall"), "bundle lacks tool_reinstall")
    }

    @Test
    fun `the shipped bundle carries the broken-tool text and the current script_outdated text of every locale`() {
        val bundle = shippedBundle()
        assertTrue(bundle.contains("tool_installed_broken"), "bundle lacks tool_installed_broken")
        locales.forEach { locale ->
            val map = entries(locale)
            for (key in listOf("tool_installed_broken", "tool_err_script_outdated")) {
                val text = map.getValue(key)
                assertTrue(bundle.contains(text), "bundle's $locale $key is not the source text (rebuild www): $text")
            }
        }
    }

    private fun shippedBundle(): String {
        val index = File("src/main/assets/www/index.html")
        assertTrue(index.isFile, index.absolutePath)
        val js = Regex("""src="\./(assets/index-[A-Za-z0-9_-]+\.js)"""").find(index.readText())
        assertNotNull(js, "index.html names no bundle")
        return File("src/main/assets/www/${js!!.groupValues[1]}").readText()
    }

    @Test
    fun `SettingsTools reads the native state again after its listener is registered`() {
        val listen = tools.indexOf("useNativeEvent('tool_progress', onToolEvent)")
        assertTrue(listen >= 0)
        val effect = Regex("""useEffect\(\(\) => \{([\s\S]*?)\n  }, \[]\)""").find(tools, listen)
        assertNotNull(effect, "no mount effect after the listener")
        val body = effect!!.groupValues[1]
        assertTrue(body.contains("bridge.callJson<ToolEvent>('getToolInstallState')"), body)
        assertTrue(body.contains("setRunning("), body)
        assertTrue(body.contains("setInfo(readInstalled())"), body)
    }

    // ── a tool whose files are on disk but does not run ─────────────────────

    @Test
    fun `broken is read from getInstalledTools, not decided by the page from event reasons`() {
        val body = Regex("""function readInstalled\(\): InstalledInfo \{([\s\S]*?)\n}""").find(tools)
        assertNotNull(body, "readInstalled not found")
        val lines =
            body!!
                .groupValues[1]
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        assertEquals(
            listOf(
                "const result = bridge.callJson<Array<{ id: string; broken?: boolean }>>('getInstalledTools') ?? []",
                "return {",
                "installed: new Set(result.map(x => x.id)),",
                "broken: new Set(result.filter(x => x.broken).map(x => x.id)),",
                "}",
            ),
            lines,
        )
        // The page keeps no broken-reason list of its own: the broken reasons appear only in reasonText's
        // map and in DETAIL_REASONS (which only decides whether the last output line is shown)
        for (gone in listOf("BROKEN_REASONS", "isBrokenEnd", "setBroken")) {
            assertFalse(tools.contains(gone), "SettingsTools still has $gone")
        }
        val reasonTextFn = Regex("""function reasonText[\s\S]*?\n}""").find(tools)!!.value
        val detailDecl = Regex("""(?m)^const DETAIL_REASONS = \[[^\]]*]""").find(tools)
        assertNotNull(detailDecl, "DETAIL_REASONS not found")
        val rest = tools.replace(reasonTextFn, "").replace(detailDecl!!.value, "")
        brokenFailures.forEach { reason ->
            assertEquals(1, Regex("""\b${reason.name}\b""").findAll(reasonTextFn).count(), "$reason in reasonText")
            assertEquals(0, Regex("""\b${reason.name}\b""").findAll(rest).count(), "$reason used elsewhere")
        }
    }

    @Test
    fun `the shipped bundle reads broken from getInstalledTools and has no page-side broken reason list`() {
        val bundle = shippedBundle()
        assertTrue(
            Regex(
                """callJson\("getInstalledTools"\)[^;]*;return\{installed:new Set\((\w+)\.map\(\w+=>\w+\.id\)\),""" +
                    """broken:new Set\(\1\.filter\((\w+)=>\2\.broken\)\.map\(\w+=>\w+\.id\)\)}""",
            ).containsMatchIn(bundle),
            "bundle does not read broken from getInstalledTools (rebuild www)",
        )
        assertFalse(
            bundle.contains("\"VERIFY_FAILED\",\"INSTALL_FAILED\",\"FILE_MISMATCH\""),
            "bundle still carries the old BROKEN_REASONS list",
        )
    }

    // ── the last end is told again on a page made after it ──────────────────

    @Test
    fun `lastEndNotice restores only a failed or cancelled end, with the tool name and its message`() {
        val fn = Regex("""function lastEndNotice\(\): string \{([\s\S]*?)\n}""").find(tools)
        assertNotNull(fn, "lastEndNotice not found")
        val lines =
            fn!!
                .groupValues[1]
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        val failed = ToolInstallGuard.FAILED
        val cancelled = ToolInstallGuard.CANCELLED
        assertEquals(
            listOf(
                "const last = bridge.callJson<ToolEvent>('getToolInstallState')",
                "if (!last || !last.target) return ''",
                "const name = getTools().find(x => x.id === last.target)?.name ?? last.target",
                "if (last.phase === '$failed') return `\${name}: \${failureText(last.reason, last.message)}`",
                "if (last.phase === '$cancelled') return `\${name}: \${t('tool_cancelled')}`",
                "return ''",
            ),
            lines,
        )
        // done, running and cancelling restore nothing
        val phases = Regex("""last\.phase === '([a-z]+)'""").findAll(fn.value).map { it.groupValues[1] }.toSet()
        assertEquals(setOf(failed, cancelled), phases)
    }

    @Test
    fun `the notice starts from lastEndNotice when the page is made`() {
        assertTrue(tools.contains("const [notice, setNotice] = useState(() => lastEndNotice())"))
        // declared once, called once
        assertEquals(2, Regex("""\blastEndNotice\(\)""").findAll(tools).count())
    }

    @Test
    fun `the shipped bundle restores the last failed or cancelled end`() {
        val bundle = shippedBundle()
        val notice =
            Regex(
                """callJson\("getToolInstallState"\);if\(!(\w+)\|\|!\1\.target\)return"";""" +
                    """const (\w+)=[^;]*;return \1\.phase==="failed"\?""" +
                    """`\$\{\2}: \$\{([\w$]+)\(\1\.reason,\1\.message\)}`""" +
                    """:\1\.phase==="cancelled"\?`\$\{\2}: \$\{\w+\("tool_cancelled"\)}`:""""",
            ).find(bundle)
        assertNotNull(notice, "bundle lacks lastEndNotice (rebuild www)")
        // The function it calls is the bundle's failureText (reason text + the last output line)
        val fn = Regex.escape(notice!!.groupValues[3])
        assertTrue(
            Regex(
                """function $fn\((\w+),(\w+)\)\{const (\w+)=[\w$]+\(\1\),(\w+)=\(\2!=null\?\2:""\)\.trim\(\);""" +
                    """return \1&&[\w$]+\.includes\(\1\)&&\4\?`\$\{\3}\n\$\{\w+\("tool_last_output"\)}: \$\{\4}`:\3}""",
            ).containsMatchIn(bundle),
            "lastEndNotice in the bundle does not call failureText (rebuild www)",
        )
    }

    @Test
    fun `a broken tool on disk shows the broken line and a reinstall button, never installed`() {
        assertTrue(
            tools.contains("installed.has(tool.id) && !broken.has(tool.id) ?"),
            "a broken tool shows as installed",
        )
        // The broken line: an app-installable tool gets tool_installed_broken (reinstall here), a
        // terminal-only one gets tool_broken_terminal (reinstall with oa --install)
        assertTrue(
            Regex(
                """\{broken\.has\(tool\.id\) && installed\.has\(tool\.id\) && \(\s*""" +
                    """<div className="card-desc">\s*""" +
                    """\{t\(NOT_INSTALLABLE_YET\.includes\(tool\.id\) \? """ +
                    """'tool_broken_terminal' : 'tool_installed_broken'\)}""" +
                    """\s*</div>""",
            ).containsMatchIn(tools),
            "the broken line is not shown for a broken tool on disk",
        )
        val label = "broken.has(tool.id) && installed.has(tool.id) ? t('tool_reinstall') : t('tool_install')"
        assertTrue(tools.contains(label), "a broken tool on disk is not offered a reinstall")
    }

    @Test
    fun `a running or cancelling event is shown only after native confirms it is still busy`() {
        val branch =
            Regex(
                """if \(d\.phase === 'running' \|\| d\.phase === 'cancelling'\) \{""" +
                    """([\s\S]*?)setRunning\(toRunning\(d\)\)""",
            ).find(tools)
        assertNotNull(branch, "no busy-event branch")
        val body = branch!!.groupValues[1]
        val recheck = body.indexOf("bridge.callJson<ToolEvent>('getToolInstallState')")
        assertTrue(recheck >= 0, "a busy event is shown without asking native: $body")
        val notBusy =
            Regex(
                """if \(now && now\.phase !== '${ToolInstallGuard.RUNNING}' """ +
                    """&& now\.phase !== '${ToolInstallGuard.CANCELLING}'\) \{([^}]*)return\s*}""",
            ).find(body)
        assertNotNull(notBusy, "a stale busy event is not ignored when native is no longer busy: $body")
        assertTrue(notBusy!!.range.first > recheck)
        assertTrue(notBusy.groupValues[1].contains("setRunning(null)"), notBusy.value)
    }

    // ── i18n ────────────────────────────────────────────────────────────────

    private val toolKeys: List<String> by lazy {
        val fixed =
            listOf(
                "settings_tools",
                "settings_tools_desc",
                "tools_title",
                "tools_cat_terminal",
                "tools_cat_ai",
                "tools_cat_network",
                "tools_cat_system",
                "tool_install",
                "tool_reinstall",
                "tool_installed_broken",
                "tool_broken_terminal",
                "tool_installed",
                "tool_installing",
                "tool_not_available",
                "tool_cancel",
                "tool_cancel_requested",
                "tool_cancelled",
                "tool_long_running",
                "tool_terminal_only",
                "tool_uninstall_unsupported",
                "tool_err_unknown",
            )
        fixed + ToolFailure.entries.map { "tool_err_" + it.name.lowercase() }
    }

    @Test
    fun `every tools key exists non-empty in every locale`() {
        locales.forEach { locale ->
            val map = entries(locale)
            toolKeys.distinct().forEach { key ->
                val v = map[key]
                assertNotNull(v, "$key missing from $locale.ts")
                assertTrue(v!!.isNotBlank(), "$key is empty in $locale.ts")
            }
        }
    }

    @Test
    fun `the tools and tool key sets are the same in every locale`() {
        fun toolSet(locale: String) =
            entries(locale)
                .keys
                .filter {
                    it.startsWith("tool") ||
                        it.startsWith("settings_tools")
                }.toSet()
        val en = toolSet("en")
        assertTrue(en.size >= toolKeys.distinct().size, en.toString())
        locales.drop(1).forEach { assertEquals(en, toolSet(it), "tool keys differ in $it.ts") }
    }

    @Test
    fun `every i18n key literal in SettingsTools tsx is defined in en`() {
        // The native event name 'tool_progress' has the key shape but is not a translation key
        val used =
            Regex("""'((?:tools?|settings)_[a-z_0-9]+)'""").findAll(tools).map { it.groupValues[1] }.toSet() -
                listened(tools)
        assertTrue(used.size > 20, used.toString())
        assertEquals(emptySet<String>(), used - entries("en").keys)
    }

    @Test
    fun `every tool shown has a translated description in every locale`() {
        val descKeys =
            Regex(
                """desc:\s*\(\)\s*=>\s*t\('([a-z_]+)'\)""",
            ).findAll(tools).map { it.groupValues[1] }.toList()
        assertEquals(BridgeGuard.toolIds.size, descKeys.size)
        locales.forEach { l -> descKeys.forEach { assertTrue(entries(l)[it]?.isNotBlank() == true, "$it in $l") } }
    }

    // ── R6: setup in progress wins over the start tab ───────────────────────

    @Test
    fun `App tsx sends a page to setup when getSetupState says running`() {
        assertTrue(app.contains("bridge.callJson<{ running?: boolean }>('getSetupState')"))
        assertTrue(
            Regex("""if \(status && setupState\?\.running\)\s*\{[^}]*setSetupDone\(false\)""").containsMatchIn(app),
        )
        assertTrue(
            Regex(
                """if \(!setupDone && !path\.startsWith\('/setup'\)\)\s*\{\s*navigate\('/setup'\)""",
            ).containsMatchIn(app),
        )
    }

    @Test
    fun `App tsx switches to setup on an in-progress setup_progress event but not on a failure or completion`() {
        assertTrue(app.contains("useNativeEvent('setup_progress', onSetupProgress)"))
        val handler =
            Regex(
                """const onSetupProgress = useCallback\(\(data: unknown\) => \{([\s\S]*?)\n  }, \[\]\)""",
            ).find(app)
        assertNotNull(handler, "onSetupProgress not found")
        val h = handler!!.groupValues[1]
        assertTrue(h.contains("d.error === undefined"), h)
        assertTrue(h.contains("!d.errorKind"), h)
        assertTrue(h.contains("d.progress < 1"), h)
        assertTrue(h.contains("setSetupDone(false)"), h)
    }
}
