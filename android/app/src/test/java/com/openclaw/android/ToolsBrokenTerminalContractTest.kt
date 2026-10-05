package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Settings → Tools, a tool on disk whose run check failed: an app-installable tool gets
 * `tool_installed_broken` and a reinstall button; a tool the app cannot install (a terminal-installed
 * code-server) gets `tool_broken_terminal`, which sends the user to `oa --install`, and no button.
 * Also: the progress card's message wraps a long log line instead of overflowing the card.
 * Checked against the www SOURCE, the i18n files and the shipped bundle (no page is rendered).
 */
internal class ToolsBrokenTerminalContractTest {
    private val locales = listOf("en", "ko", "zh")

    private fun www(path: String): String {
        val f = File("../www/src/$path")
        assertTrue(f.isFile, "expected ${f.absolutePath}")
        return f.readText()
    }

    private val tools by lazy { www("screens/SettingsTools.tsx") }

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
        val m = Regex("""const\s+$name\s*=\s*\[([^\]]*)]""").find(tools)
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

    private val brokenLine =
        Regex(
            """\{broken\.has\(tool\.id\) && installed\.has\(tool\.id\) && \(\s*<div className="card-desc">\s*""" +
                """\{t\((\w+)\.includes\(tool\.id\) \? '(\w+)' : '(\w+)'\)}\s*</div>\s*\)}""",
        )

    /** The key the source's broken line picks for [id]: the ternary evaluated against the source list. */
    private fun brokenKeyFor(id: String): String {
        val m = brokenLine.find(tools)
        assertNotNull(m, "the broken line is not a list-gated choice of two keys")
        val (list, whenIn, otherwise) = m!!.destructured
        return if (id in sourceArray(list)) whenIn else otherwise
    }

    // ── (a) the broken line and the button, in the source ───────────────────

    @Test
    fun `the broken line picks tool_broken_terminal for a not-installable tool and tool_installed_broken otherwise`() {
        val m = brokenLine.find(tools)
        assertNotNull(m, "the broken line is not a list-gated choice of two keys")
        assertEquals(
            listOf("NOT_INSTALLABLE_YET", "tool_broken_terminal", "tool_installed_broken"),
            m!!.groupValues.drop(1),
        )
    }

    @Test
    fun `tool_broken_terminal and tool_installed_broken are each used in one place on the page`() {
        assertEquals(1, Regex("""'tool_broken_terminal'""").findAll(tools).count())
        assertEquals(1, Regex("""'tool_installed_broken'""").findAll(tools).count())
        // That one place is the broken line
        val line = brokenLine.find(tools)!!.range
        assertTrue(tools.indexOf("'tool_broken_terminal'") in line)
        assertTrue(tools.indexOf("'tool_installed_broken'") in line)
    }

    @Test
    fun `a not-installable tool is shown as not available before any install or reinstall button`() {
        // installed && !broken → label; else not-installable → no button; else the install/reinstall button
        val chain =
            Regex(
                """\{installed\.has\(tool\.id\) && !broken\.has\(tool\.id\) \? \(\s*""" +
                    """<span[^>]*>\{t\('tool_installed'\)}</span>\s*""" +
                    """\) : NOT_INSTALLABLE_YET\.includes\(tool\.id\) \? \(\s*""" +
                    """<span[^>]*>\{t\('tool_not_available'\)}</span>\s*""" +
                    """\) : \(\s*<button[\s\S]*?bridge\.call\('installTool', tool\.id\)[\s\S]*?""" +
                    """\{broken\.has\(tool\.id\) && installed\.has\(tool\.id\) \? t\('tool_reinstall'\) """ +
                    """: t\('tool_install'\)}\s*</button>""",
            )
        assertTrue(chain.containsMatchIn(tools), "the not-installable branch no longer precedes the button")
        // The reinstall label and the install call live only inside that button
        assertEquals(1, Regex("""'tool_reinstall'""").findAll(tools).count())
        assertEquals(1, Regex("""bridge\.call\('installTool'""").findAll(tools).count())
    }

    // ── (b) i18n and the bundle ─────────────────────────────────────────────

    @Test
    fun `tool_broken_terminal is translated in every locale, names oa --install and differs from the reinstall text`() {
        locales.forEach { locale ->
            val map = entries(locale)
            val text = map["tool_broken_terminal"]
            assertNotNull(text, "$locale lacks tool_broken_terminal")
            assertTrue(text!!.contains("oa --install"), "$locale tool_broken_terminal lacks oa --install: $text")
            assertNotEquals(map["tool_installed_broken"], text, "$locale broken texts are the same")
        }
    }

    @Test
    fun `the bundle carries every locale's tool_broken_terminal text and the list-gated choice`() {
        locales.forEach { locale ->
            val text = entries(locale).getValue("tool_broken_terminal")
            assertTrue(bundle.contains(text), "bundle's $locale tool_broken_terminal is not the source (rebuild www)")
        }
        val choice =
            Regex("""([\w$]+)\.includes\(([\w$]+)\.id\)\?"tool_broken_terminal":"tool_installed_broken"""")
                .find(bundle)
        assertNotNull(choice, "bundle does not choose the broken key by a list (rebuild www)")
        val listVar = Regex.escape(choice!!.groupValues[1])
        val list = Regex("""(?<![\w$])$listVar=\[([^\]]*)]""").find(bundle)
        assertNotNull(list, "bundle list ${choice.groupValues[1]} not found")
        assertEquals(sourceArray("NOT_INSTALLABLE_YET"), quoted(list!!.groupValues[1]))
    }

    // ── (c) the progress card's message wraps ───────────────────────────────

    @Test
    fun `the progress card message div breaks a long line anywhere`() {
        val div = Regex("""<div style=\{\{([^}]*)}}>\s*\{running\.message}\s*</div>""").find(tools)
        assertNotNull(div, "the progress message div not found")
        val style = div!!.groupValues[1]
        assertTrue(style.contains("overflowWrap: 'anywhere'"), style)
        assertTrue(style.contains("wordBreak: 'break-word'"), style)
    }

    @Test
    fun `the bundle's progress message div breaks a long line anywhere`() {
        val div = Regex("""\.jsx\("div",\{style:\{([^}]*)},children:[\w$]+\.message}\)""").find(bundle)
        assertNotNull(div, "bundle progress message div not found (rebuild www)")
        val style = div!!.groupValues[1]
        assertTrue(style.contains("overflowWrap:\"anywhere\""), style)
        assertTrue(style.contains("wordBreak:\"break-word\""), style)
    }

    // ── (d) code-server takes the terminal path ─────────────────────────────

    @Test
    fun `a broken code-server gets tool_broken_terminal and no button`() {
        assertTrue(tools.contains("{ id: 'code-server',"), "code-server is not listed in Settings → Tools")
        assertTrue("code-server" in sourceArray("NOT_INSTALLABLE_YET"))
        assertTrue("code-server" in BridgeGuard.terminalOnlyTools)
        assertFalse("code-server" in BridgeGuard.toolInstallIds.keys)
        assertEquals("tool_broken_terminal", brokenKeyFor("code-server"))
    }

    @Test
    fun `every app-installable tool keeps tool_installed_broken and every terminal-only tool gets the terminal text`() {
        BridgeGuard.toolInstallIds.keys.forEach { assertEquals("tool_installed_broken", brokenKeyFor(it), it) }
        BridgeGuard.terminalOnlyTools.forEach { assertEquals("tool_broken_terminal", brokenKeyFor(it), it) }
    }
}
