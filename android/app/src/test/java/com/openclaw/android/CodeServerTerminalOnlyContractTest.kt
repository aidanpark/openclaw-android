package com.openclaw.android

import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * code-server left the app's install chain (its `npm install -g` fails even on a clean glibc node):
 * it is terminal-only in native, in Settings → Tools (with a hint naming `oa --install`) and in the
 * setup wizard — checked against the native sets, the www SOURCE and the shipped bundle. The
 * script's selection keys still accept it, so an older `tool-selections.conf` stays valid.
 */
internal class CodeServerTerminalOnlyContractTest {
    @TempDir
    lateinit var root: File

    private val locales = listOf("en", "ko", "zh")
    private val wizardTools = listOf("tmux", "ttyd", "dufs", "claude-code", "gemini-cli", "codex-cli")

    private fun www(path: String): String {
        val f = File("../www/src/$path")
        assertTrue(f.isFile, "expected ${f.absolutePath}")
        return f.readText()
    }

    private val settingsTools by lazy { www("screens/SettingsTools.tsx") }
    private val setup by lazy { www("screens/Setup.tsx") }

    private val bundle by lazy {
        val index = File("src/main/assets/www/index.html")
        assertTrue(index.isFile, "expected ${index.absolutePath}")
        val js = Regex("""src="\./(assets/index-[^"]+\.js)"""").find(index.readText())
        assertNotNull(js, "index.html names no bundle")
        File("src/main/assets/www/${js!!.groupValues[1]}").readText()
    }

    /** `'a', 'b'` or `"a","b"` → the quoted strings, in order. */
    private fun quoted(list: String): List<String> =
        Regex("""['"]([^'"]+)['"]""").findAll(list).map { it.groupValues[1] }.toList()

    private fun sourceArray(name: String): List<String> {
        val m = Regex("""const\s+$name\s*=\s*\[([^\]]*)]""").find(settingsTools)
        assertNotNull(m, "$name not found in SettingsTools.tsx")
        return quoted(m!!.groupValues[1])
    }

    /** key → value (quotes stripped) of one locale file. */
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

    // ── (b) native sets ─────────────────────────────────────────────────────

    @Test
    fun `the app installs exactly eight tools and code-server is not one of them`() {
        assertEquals(
            setOf("tmux", "ttyd", "dufs", "android-tools", "playwright", "claude-code", "gemini-cli", "codex-cli"),
            BridgeGuard.toolInstallIds.keys,
        )
        assertFalse("code-server" in BridgeGuard.toolInstallIds)
        assertEquals(emptySet<String>(), BridgeGuard.toolInstallIds.keys intersect BridgeGuard.terminalOnlyTools)
        assertTrue(BridgeGuard.toolIds.containsAll(BridgeGuard.toolInstallIds.keys + BridgeGuard.terminalOnlyTools))
    }

    @Test
    fun `code-server is terminal-only yet still has a run check`() {
        assertTrue("code-server" in BridgeGuard.terminalOnlyTools)
        val cmd = BridgeGuard.toolVerifyCommands["code-server"]
        assertNotNull(cmd, "a terminal-installed code-server would never be checked")
        assertEquals("code-server", cmd!!.executable)
        assertEquals(listOf("--version"), cmd.args)
    }

    // ── (c) Settings → Tools: source ────────────────────────────────────────

    @Test
    fun `NOT_INSTALLABLE_YET has no duplicates and is the four terminal-only tools`() {
        val ids = sourceArray("NOT_INSTALLABLE_YET")
        assertEquals(ids.size, ids.toSet().size, "duplicate in NOT_INSTALLABLE_YET: $ids")
        assertEquals(4, ids.size, ids.toString())
        assertEquals(BridgeGuard.terminalOnlyTools, ids.toSet())
    }

    @Test
    fun `TERMINAL_INSTALL is exactly code-server and it is not installable from the app`() {
        val ids = sourceArray("TERMINAL_INSTALL")
        assertEquals(listOf("code-server"), ids)
        assertTrue(sourceArray("NOT_INSTALLABLE_YET").containsAll(ids))
        assertTrue(BridgeGuard.terminalOnlyTools.containsAll(ids))
    }

    @Test
    fun `the terminal hint is shown only while the tool is not installed`() {
        val hint =
            Regex(
                """\{\s*TERMINAL_INSTALL\.includes\(tool\.id\)\s*&&\s*!installed\.has\(tool\.id\)\s*&&\s*\(\s*""" +
                    """<div className="card-desc">\{t\('tool_terminal_install_hint'\)\}</div>\s*\)\s*\}""",
            )
        assertTrue(hint.containsMatchIn(settingsTools), "the hint is not gated on TERMINAL_INSTALL && !installed")
        // The key is used nowhere else on the page (no second, ungated place)
        assertEquals(1, Regex("""tool_terminal_install_hint""").findAll(settingsTools).count())
    }

    @Test
    fun `the terminal hint is translated in every locale and names oa --install`() {
        locales.forEach { locale ->
            val text = entries(locale)["tool_terminal_install_hint"]
            assertNotNull(text, "$locale lacks tool_terminal_install_hint")
            assertTrue(text!!.contains("oa --install"), "$locale hint does not name oa --install: $text")
        }
    }

    // ── (c) Settings → Tools: shipped bundle ────────────────────────────────

    @Test
    fun `the bundle carries the four not-installable tools and the gated terminal hint`() {
        val notInstallable =
            Regex("""=\[("[^"\]]+"(?:,"[^"\]]+")*)]""")
                .findAll(bundle)
                .map { quoted(it.groupValues[1]).toSet() }
                .filter { "openssh-server" in it && "opencode" in it }
                .toList()
        assertEquals(listOf(BridgeGuard.terminalOnlyTools), notInstallable, "bundle NOT_INSTALLABLE_YET (rebuild www)")

        val gate =
            Regex("""(\w+)\.includes\((\w+)\.id\)&&!(\w+)\.has\(\2\.id\)&&[^;]{0,120}?"tool_terminal_install_hint"""")
                .find(bundle)
        assertNotNull(gate, "bundle does not gate tool_terminal_install_hint on a list && !installed (rebuild www)")
        val listVar = Regex.escape(gate!!.groupValues[1])
        val list = Regex("""\b$listVar=\[([^\]]*)]""").find(bundle)
        assertNotNull(list, "bundle TERMINAL_INSTALL array not found")
        assertEquals(listOf("code-server"), quoted(list!!.groupValues[1]))
    }

    @Test
    fun `the bundle carries every locale's terminal hint text`() {
        locales.forEach { locale ->
            val text = entries(locale).getValue("tool_terminal_install_hint")
            assertTrue(bundle.contains(text), "bundle's $locale hint is not the source text (rebuild www): $text")
        }
    }

    // ── (d) the setup wizard ────────────────────────────────────────────────

    private fun wizardSourceIds(): List<String> {
        val body = Regex("""function getOptionalTools\(\)\s*\{(.*?)\n}""", RegexOption.DOT_MATCHES_ALL).find(setup)
        assertNotNull(body, "getOptionalTools not found in Setup.tsx")
        return Regex("""\{\s*id:\s*'([^']+)'""").findAll(body!!.groupValues[1]).map { it.groupValues[1] }.toList()
    }

    @Test
    fun `the wizard offers the six tools and not code-server`() {
        assertEquals(wizardTools, wizardSourceIds())
    }

    @Test
    fun `the bundle's wizard list is the same six tools`() {
        val lists =
            Regex("""return\[(\{id:"tmux",name:"tmux",desc:\w+\("tool_tmux"\)\}[^\]]*)]""")
                .findAll(bundle)
                .map { m -> Regex("""\{id:"([^"]+)"""").findAll(m.groupValues[1]).map { it.groupValues[1] }.toList() }
                .toList()
        assertEquals(listOf(wizardTools), lists, "bundle wizard list (rebuild www)")
    }

    @Test
    fun `the script keys still accept code-server for older selection files`() {
        assertTrue("code-server" in BridgeGuard.toolSelectionIds)
        assertEquals(mapOf("code-server" to true), BridgeGuard.parseToolSelections("""{"code-server": true}"""))
    }

    @Test
    fun `what the wizard sends writes no code-server line`() {
        mockkStatic(Log::class)
        val web = RecordingWebView()
        try {
            every { Log.w(any(), any<String>()) } returns 0
            val home = File(root, "home").apply { mkdirs() }
            val bootstrap = mockk<BootstrapManager>(relaxed = true)
            every { bootstrap.homeDir } returns home
            val bridge =
                JsBridge(
                    mockk<MainActivity>(relaxed = true),
                    mockk<TerminalSessionManager>(relaxed = true),
                    bootstrap,
                    EventBridge(web.view),
                )
            // Setup.tsx sends one key per offered tool; every tool selected is the widest case
            val json = wizardSourceIds().joinToString(",", "{", "}") { "\"$it\": true" }
            bridge.saveToolSelections(json)
            val lines = File(home, ".openclaw-android/tool-selections.conf").readLines().filter { it.isNotBlank() }
            assertEquals(wizardTools.size, lines.size, lines.toString())
            assertTrue(lines.none { it.startsWith("INSTALL_CODE_SERVER") }, lines.toString())
            assertTrue("INSTALL_TMUX=true" in lines, lines.toString())
        } finally {
            EventBridge.detach(web.view)
            unmockkStatic(Log::class)
        }
    }
}
