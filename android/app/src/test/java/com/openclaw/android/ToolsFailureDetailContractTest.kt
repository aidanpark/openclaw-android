package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Settings → Tools: the failure notice with the script's last output line (`failureText`), and
 * the "experimental" line on Claude Code / Codex CLI. Checked against the www SOURCE and the shipped
 * bundle (structural: no page is rendered); native values come from compiled code.
 */
internal class ToolsFailureDetailContractTest {
    private val locales = listOf("en", "ko", "zh")

    private fun www(path: String): String {
        val f = File("../www/src/$path")
        assertTrue(f.isFile, "expected ${f.absolutePath}")
        return f.readText()
    }

    private val tools by lazy { www("screens/SettingsTools.tsx") }

    private fun stringArray(name: String): List<String> {
        val m = Regex("""(?m)^const $name = \[([^\]]*)]""").find(tools)
        assertNotNull(m, "$name not found")
        return Regex("""'([^']*)'""").findAll(m!!.groupValues[1]).map { it.groupValues[1] }.toList()
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

    private val shippedBundle: String by lazy {
        val index = File("src/main/assets/www/index.html")
        assertTrue(index.isFile, index.absolutePath)
        val js = Regex("""src="\./(assets/index-[A-Za-z0-9_-]+\.js)"""").find(index.readText())
        assertNotNull(js, "index.html names no bundle")
        File("src/main/assets/www/${js!!.groupValues[1]}").readText()
    }

    // ── DETAIL_REASONS ──────────────────────────────────────────────────────

    @Test
    fun `DETAIL_REASONS are exactly the seven failures whose last output line explains them`() {
        val detail = stringArray("DETAIL_REASONS")
        assertEquals(detail.size, detail.toSet().size, "duplicates: $detail")
        assertEquals(DETAIL, detail.toSet())
        val compiled = ToolFailure.entries.map { it.name }.toSet()
        assertTrue(compiled.containsAll(detail), "not a ToolFailure name: ${detail - compiled}")
    }

    @Test
    fun `progress-only ends never get the last output line, and every ToolFailure is decided`() {
        val detail = stringArray("DETAIL_REASONS").toSet()
        assertEquals(emptySet<String>(), detail intersect NO_DETAIL, "a progress-only end shows its last line")
        // A new ToolFailure must be put on one side on purpose
        assertEquals(ToolFailure.entries.map { it.name }.toSet(), detail + NO_DETAIL)
    }

    @Test
    fun `the shipped bundle carries the same DETAIL_REASONS`() {
        val list = stringArray("DETAIL_REASONS").joinToString(",") { "\"$it\"" }
        assertTrue(shippedBundle.contains("=[$list]"), "bundle's DETAIL_REASONS differ from the source (rebuild www)")
    }

    // ── failureText ─────────────────────────────────────────────────────────

    @Test
    fun `failureText adds the last output line only for a detail reason with a non-empty message`() {
        val fn =
            Regex(
                """function failureText\(reason\?: string \| null, message\?: string\): string \{""" +
                    """([\s\S]*?)\n}""",
            )
        val body = fn.find(tools)
        assertNotNull(body, "failureText not found")
        val lines =
            body!!
                .groupValues[1]
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        assertEquals(
            listOf(
                "const base = reasonText(reason)",
                "const detail = (message ?? '').trim()",
                "return reason && DETAIL_REASONS.includes(reason) && detail ? " +
                    "`\${base}\\n\${t('tool_last_output')}: \${detail}` : base",
            ),
            lines,
        )
    }

    @Test
    fun `failureText is used by the end event notice and by lastEndNotice, and nowhere a raw reasonText is shown`() {
        assertEquals(3, Regex("""\bfailureText\(""").findAll(tools).count(), "declared once, called twice")
        assertTrue(
            tools.contains(
                "setNotice(d.phase === 'done' ? '' : d.phase === 'cancelled' ? t('tool_cancelled') : " +
                    "failureText(d.reason, d.message))",
            ),
            "the end event notice does not use failureText",
        )
        assertTrue(
            tools.contains(
                "if (last.phase === 'failed') return `\${name}: \${failureText(last.reason, last.message)}`",
            ),
            "lastEndNotice does not use failureText",
        )
        // reasonText: declared once, called only from failureText
        assertEquals(2, Regex("""\breasonText\(""").findAll(tools).count())
    }

    @Test
    fun `the shipped bundle uses failureText for the end event notice`() {
        assertTrue(
            Regex(
                """\.phase==="cancelled"\?\w+\("tool_cancelled"\):([\w$]+)\((\w+)\.reason,\2\.message\)\)""",
            ).containsMatchIn(shippedBundle),
            "bundle's end event notice does not pass the message (rebuild www)",
        )
    }

    @Test
    fun `the notice card keeps the line break of failureText`() {
        assertTrue(
            tools.contains(
                "<div className=\"card\" style={{ marginBottom: 16, fontSize: 14, whiteSpace: 'pre-line' }}>" +
                    "{notice}</div>",
            ),
            "the notice card is not pre-line",
        )
        assertTrue(
            Regex("""className:"card",style:\{[^}]*whiteSpace:"pre-line"},children:\w+}""")
                .containsMatchIn(shippedBundle),
            "bundle's notice card is not pre-line (rebuild www)",
        )
    }

    // ── EXPERIMENTAL ────────────────────────────────────────────────────────

    @Test
    fun `EXPERIMENTAL is exactly claude-code and codex-cli, both app tools installable through the chain`() {
        val experimental = stringArray("EXPERIMENTAL")
        assertEquals(listOf("claude-code", "codex-cli"), experimental)
        experimental.forEach {
            assertTrue(it in BridgeGuard.toolIds, "$it is not an app tool")
            assertTrue(it in BridgeGuard.toolInstallIds, "$it is not in the install chain")
            assertTrue(it !in BridgeGuard.terminalOnlyTools, "$it is terminal-only")
        }
    }

    @Test
    fun `UNKNOWN never gets the last output line, which may be an app message rather than the script's`() {
        assertTrue("UNKNOWN" !in stringArray("DETAIL_REASONS"))
        assertTrue("UNKNOWN" in NO_DETAIL)
    }

    @Test
    fun `the experimental line is shown for the tool whether or not it is installed`() {
        // Installed or not, the tool is still not known to work on Android: the warning stays
        assertTrue(
            Regex(
                """\{EXPERIMENTAL\.includes\(tool\.id\) && \(\s*""" +
                    """<div className="card-desc">\{t\('tool_experimental'\)}</div>\s*\)}""",
            ).containsMatchIn(tools),
            "the experimental line is not shown on its list alone",
        )
        val line = tools.lineSequence().first { it.contains("EXPERIMENTAL.includes(") }
        assertFalse(line.contains("installed"), "the experimental line depends on the install state: $line")
        assertFalse(line.contains("broken"), "the experimental line depends on the broken state: $line")
        assertEquals(1, Regex("""'tool_experimental'""").findAll(tools).count(), "tool_experimental shown elsewhere")
        assertEquals(2, Regex("""\bEXPERIMENTAL\b""").findAll(tools).count(), "EXPERIMENTAL used elsewhere")
    }

    @Test
    fun `the shipped bundle shows the experimental line from the same list, with no install condition`() {
        val m =
            Regex("""(\w+)=\["claude-code","codex-cli"]""").find(shippedBundle)
        assertNotNull(m, "bundle lacks the EXPERIMENTAL list (rebuild www)")
        val v = Regex.escape(m!!.groupValues[1])
        assertTrue(
            Regex(
                """[(,{]$v\.includes\((\w+)\.id\)&&\w+\.jsx\("div",\{className:"card-desc",""" +
                    """children:\w+\("tool_experimental"\)}\)""",
            ).containsMatchIn(shippedBundle),
            "bundle does not show tool_experimental on the list alone (rebuild www)",
        )
        assertFalse(
            Regex("""$v\.includes\(\w+\.id\)&&!""").containsMatchIn(shippedBundle),
            "bundle still conditions tool_experimental on something else (rebuild www)",
        )
    }

    // ── i18n ────────────────────────────────────────────────────────────────

    @Test
    fun `tool_experimental and tool_last_output exist non-empty in every locale and are shipped`() {
        locales.forEach { locale ->
            val map = entries(locale)
            for (key in NEW_KEYS) {
                val text = map[key]
                assertNotNull(text, "$key missing from $locale.ts")
                assertTrue(text!!.isNotBlank(), "$key is empty in $locale.ts")
                assertTrue(shippedBundle.contains("$key:\"$text\""), "bundle's $locale $key is not the source text")
            }
        }
        assertTrue(entries("ko").getValue("tool_experimental") != entries("en").getValue("tool_experimental"))
    }

    private companion object {
        val DETAIL =
            setOf(
                "INSTALL_FAILED",
                "VERIFY_FAILED",
                "FILE_MISMATCH",
                "INDEX_NETWORK",
                "INDEX_VERIFY",
                "ENV",
                "LOCK",
            )
        val NO_DETAIL = setOf("BUSY", "SCRIPT_OUTDATED", "SETUP_INCOMPLETE", "NOT_RUN", "INTERRUPTED", "UNKNOWN")
        val NEW_KEYS = listOf("tool_experimental", "tool_last_output")
    }
}
