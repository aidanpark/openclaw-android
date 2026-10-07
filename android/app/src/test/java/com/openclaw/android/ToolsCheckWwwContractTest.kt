package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Settings → Tools asks native to RUN each installed tool's check (`checkInstalledToolsAsync`) when
 * the page is made and after every install end, keeps only the answers of its newest request, and
 * shows a tool as broken only when its check said `failed` (never for `unknown` or `ok`). Checked
 * against the www SOURCE and the shipped bundle (structural: no page is rendered).
 */
internal class ToolsCheckWwwContractTest {
    private fun www(path: String): String {
        val f = File("../www/src/$path")
        assertTrue(f.isFile, "expected ${f.absolutePath}")
        return f.readText()
    }

    private val tools by lazy { www("screens/SettingsTools.tsx") }
    private val bridgeTs by lazy { www("lib/bridge.ts") }

    private val bundle: String by lazy {
        val index = File("src/main/assets/www/index.html")
        assertTrue(index.isFile, index.absolutePath)
        val js = Regex("""src="\./(assets/index-[A-Za-z0-9_-]+\.js)"""").find(index.readText())
        assertNotNull(js, "index.html names no bundle")
        File("src/main/assets/www/${js!!.groupValues[1]}").readText()
    }

    /** The text between the `{` that ends [head] and its matching `}`. */
    private fun blockAfter(
        source: String,
        head: String,
    ): String {
        val at = source.indexOf(head)
        assertTrue(at >= 0, "'$head' not found")
        val open = at + head.length - 1
        assertEquals('{', source[open], head)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open + 1, i)
            }
        }
        error("unbalanced braces after '$head'")
    }

    private fun lines(block: String) =
        block
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") }

    // ── the request ─────────────────────────────────────────────────────────

    @Test
    fun `startCheck sends a fresh callback id to checkInstalledToolsAsync and returns it`() {
        assertEquals(
            listOf(
                "checkCounter += 1",
                "const id = `tools-check-\${checkCounter}`",
                "bridge.call('checkInstalledToolsAsync', id)",
                "return id",
            ),
            lines(blockAfter(tools, "function startCheck(): string {")),
        )
        assertEquals(1, Regex("""'checkInstalledToolsAsync'""").findAll(tools).count(), "called outside startCheck")
    }

    @Test
    fun `a check is started when the page is made and after every install end, and nowhere else`() {
        assertEquals(2, Regex("""checkRef\.current = startCheck\(\)""").findAll(tools).count())
        assertEquals(3, Regex("""\bstartCheck\(\)""").findAll(tools).count(), "declared once, called twice")

        // Mount: after the tools_check listener is on, so no answer can be missed
        val listen = tools.indexOf("useNativeEvent('tools_check', onToolsCheck)")
        assertTrue(listen >= 0, "no tools_check listener")
        val mount = Regex("""useEffect\(\(\) => \{([\s\S]*?)\n  }, \[]\)""").find(tools, listen)
        assertNotNull(mount, "no mount effect after the tools_check listener")
        assertTrue(mount!!.groupValues[1].contains("checkRef.current = startCheck()"), mount.value)

        // Every final phase (done, failed, cancelled): earlier answers are dropped, then a new check
        val finals = listOf(ToolInstallGuard.DONE, ToolInstallGuard.FAILED, ToolInstallGuard.CANCELLED)
        val head = "if (" + finals.joinToString(" || ") { "d.phase === '$it'" } + ") {"
        val end = lines(blockAfter(tools, head))
        val cleared = end.indexOf("setChecked({})")
        val started = end.indexOf("checkRef.current = startCheck()")
        assertTrue(cleared >= 0, "an install end keeps the stale check answers: $end")
        assertTrue(started > cleared, "the new check is not started after the old answers are dropped: $end")
        assertTrue(end.indexOf("setInfo(readInstalled())") in 0 until started, "the disk is not re-read first: $end")

        // A busy event does not start a check (it would see a half-written tool)
        val busy = blockAfter(tools, "if (d.phase === 'running' || d.phase === 'cancelling') {")
        assertFalse(busy.contains("startCheck"), busy)
    }

    // ── the answers ─────────────────────────────────────────────────────────

    @Test
    fun `only answers of the newest request are kept`() {
        val handler = blockAfter(tools, "const onToolsCheck = useCallback((data: unknown) => {")
        assertEquals(
            listOf(
                "const d = data as { callbackId?: string; target?: string; status?: string }",
                "const { target, status } = d",
                "if (d.callbackId !== checkRef.current || !target || !status) return",
                "setChecked(prev => ({ ...prev, [target]: status }))",
            ),
            lines(handler),
        )
        assertEquals(1, Regex("""\bcheckRef = useRef\(''\)""").findAll(tools).count())
    }

    @Test
    fun `only a failed check joins broken, ok and unknown do not`() {
        val decl = tools.lineSequence().single { it.trimStart().startsWith("const broken = ") }.trim()
        assertEquals(
            "const broken = new Set([...info.broken, ...Object.keys(checked).filter(id => checked[id] === 'failed')])",
            decl,
        )
        // The literal the page compares with is the one native sends for a failed check
        assertEquals("failed", ToolProbe.FAILED)
        assertFalse(
            Regex("""checked\[\w+]\s*!==""").containsMatchIn(tools),
            "a negated comparison would let unknown in",
        )
        for (other in listOf(ToolProbe.OK, ToolProbe.UNKNOWN)) {
            assertFalse(tools.contains("=== '$other'"), "'$other' is compared somewhere")
        }
        // broken still decides both the line and the reinstall button
        assertTrue(tools.contains("installed.has(tool.id) && !broken.has(tool.id) ?"))
    }

    @Test
    fun `bridge ts declares checkInstalledToolsAsync with the native signature`() {
        assertTrue(Regex("""(?m)^\s*checkInstalledToolsAsync\(callbackId: string\): void""").containsMatchIn(bridgeTs))
        val m = JsBridge::class.java.methods.single { it.name == "checkInstalledToolsAsync" }
        assertEquals(listOf(String::class.java), m.parameterTypes.toList())
    }

    // ── the shipped bundle ──────────────────────────────────────────────────

    private fun bundleStartCheck(): String {
        val fn =
            Regex(
                """function ([\w$]+)\(\)\{([\w$]+)\+=1;const ([\w$]+)=`tools-check-\$\{\2}`;""" +
                    """return [\w$]+\.call\("checkInstalledToolsAsync",\3\),\3}""",
            ).find(bundle)
        assertNotNull(fn, "bundle lacks startCheck (rebuild www)")
        return Regex.escape(fn!!.groupValues[1])
    }

    @Test
    fun `the shipped bundle starts a check on mount and after an install end`() {
        val fn = bundleStartCheck()
        assertEquals(1, Regex(""""checkInstalledToolsAsync"""").findAll(bundle).count())
        assertEquals(2, Regex("""\.current=$fn\(\)""").findAll(bundle).count(), "bundle starts the check elsewhere")
        assertTrue(
            Regex(
                """\.phase==="done"\|\|\w+\.phase==="failed"\|\|\w+\.phase==="cancelled"\)&&\([^;]*?""" +
                    """[\w$]+\(\{\}\),[\w$]+\.current=$fn\(\)""",
            ).containsMatchIn(bundle),
            "bundle does not drop old answers and re-check after an install end (rebuild www)",
        )
        assertTrue(
            Regex(""""tools_check",[\w$]+\),[\w$]+\.useEffect\(\(\)=>\{[^}]*\.current=$fn\(\)},\[]\)""")
                .containsMatchIn(bundle),
            "bundle does not check on mount after the listener (rebuild www)",
        )
    }

    @Test
    fun `the shipped bundle keeps only the newest answers and lets only failed join broken`() {
        val listener =
            Regex(
                // minified names may contain `$` (e.g. `$t`): every name is [\w$]+
                """useCallback\(([\w$]+)=>\{const ([\w$]+)=\1,\{target:([\w$]+),status:([\w$]+)}=\2;""" +
                    """\2\.callbackId!==([\w$]+)\.current\|\|!\3\|\|!\4\|\|""" +
                    """[\w$]+\(([\w$]+)=>\(\{\.\.\.\6,\[\3]:\4}\)\)},\[]\);""" +
                    """[\w$]+\("tools_check",""",
            ).find(bundle)
        assertNotNull(listener, "bundle lacks the newest-answer filter (rebuild www)")
        val ref = Regex.escape(listener!!.groupValues[5])
        val fn = bundleStartCheck()
        assertTrue(Regex("""$ref\.current=$fn\(\)""").containsMatchIn(bundle), "filter uses another ref")
        assertTrue(
            Regex(
                """new Set\(\[\.\.\.[\w$]+\.broken,""" +
                    """\.\.\.Object\.keys\(([\w$]+)\)\.filter\(([\w$]+)=>\1\[\2]==="failed"\)]\)""",
            ).containsMatchIn(bundle),
            "bundle's broken is not native broken plus failed checks only (rebuild www)",
        )
    }

    @Test
    fun `the shipped bundle carries the seven detail reasons without UNKNOWN`() {
        val m = Regex("""=\[("INSTALL_FAILED"[^\]]*)]""").find(bundle)
        assertNotNull(m, "bundle lacks DETAIL_REASONS (rebuild www)")
        val reasons = Regex(""""([A-Z_]+)"""").findAll(m!!.groupValues[1]).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf("INSTALL_FAILED", "VERIFY_FAILED", "FILE_MISMATCH", "INDEX_NETWORK", "INDEX_VERIFY", "ENV", "LOCK"),
            reasons,
        )
    }
}
