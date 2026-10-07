package com.openclaw.android

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.nio.file.Files

/**
 * The `warn=` tokens of a finished install, read from the script that raises them
 * (`oa_warn <token>` in post-setup.sh at the repository root) against what the page does with each
 * one (`warnLines` in www/src/lib/setupRun.ts, run in node): `hardlink-patch` is the strong
 * "OpenClaw may be incomplete" warning and `tools:<id>` names a skipped tool — a rename on either
 * side would silently turn them into the generic line. Every other token is generic; the known set
 * is listed here, so a NEW token fails and forces a decision. Then the worst-case `warn=` line
 * against the result file's 200-character line limit (a longer line is dropped whole).
 *
 * The node part is skipped (not failed) when node or `www/node_modules/esbuild` is missing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class SetupWarnTokenContractTest {
    private val script: String by lazy {
        val f = File("../../post-setup.sh")
        assertTrue(f.isFile, "expected ${f.absolutePath}")
        f.readText()
    }

    /** Every `oa_warn` call's token, in script order (the definition and comments left out). */
    private val raised: List<String> by lazy {
        script
            .lines()
            .filterNot { it.trimStart().startsWith("#") || it.trimStart().startsWith("oa_warn()") }
            .flatMap { line -> callPattern.findAll(line).map { it.groupValues[1].removeSurrounding("\"") }.toList() }
    }

    private val toolTokens get() = raised.filter { it.startsWith(TOOL_PREFIX) }
    private val plainTokens get() = raised.filterNot { it.startsWith(TOOL_PREFIX) }

    // ── the script side ─────────────────────────────────────────────────────

    @Test
    fun `every oa_warn call has a literal token - none is built from a variable`() {
        assertTrue(raised.size >= 10, "the reader found only $raised")
        raised.forEach { assertTrue(literal.matches(it), "not a literal token: $it") }
        val anyCall = Regex("""\boa_warn\s+\S""")
        val calls = script.lines().filterNot { it.trimStart().startsWith("#") }.sumOf { anyCall.findAll(it).count() }
        assertEquals(raised.size, calls, "an oa_warn call the reader does not understand")
    }

    @Test
    fun `the script still raises hardlink-patch and tools-prefixed tokens in exactly the app's spelling`() {
        assertTrue(INCOMPLETE in plainTokens, "post-setup.sh no longer raises `oa_warn $INCOMPLETE`: $raised")
        assertTrue(toolTokens.isNotEmpty(), "no tools:<id> token any more")
        // the app's own spelling of both
        val run = File("../www/src/lib/setupRun.ts").readText()
        assertTrue(run.contains("const INCOMPLETE_WARN = '$INCOMPLETE'"), "setupRun.ts spells the token differently")
        assertTrue(run.contains("const TOOL_WARN_PREFIX = '$TOOL_PREFIX'"))
    }

    @Test
    fun `the tokens raised are exactly the known set - a new one needs a decision here`() {
        assertEquals(KNOWN_GENERIC + INCOMPLETE, plainTokens.toSet(), "a plain token was added or renamed")
        assertEquals(
            KNOWN_TOOLS.map { TOOL_PREFIX + it }.toSet(),
            toolTokens.toSet(),
            "a tool token was added or renamed",
        )
    }

    @Test
    fun `the documented list on the oa_warn comment is the raised set`() {
        val doc = Regex("""^# oa_warn <id>: a non-fatal problem \(([^)]*)\)""", RegexOption.MULTILINE).find(script)
        assertTrue(doc != null, "the `# oa_warn <id>:` comment line is gone")
        val listed =
            doc!!
                .groupValues[1]
                .split(",")
                .map { it.trim() }
                .toSet()
        assertEquals(plainTokens.toSet() + "tools:<id>", listed)
    }

    @Test
    fun `every token passes the result parser's warn pattern`() {
        for (t in raised) {
            val text = "schema=1\nrun=5\nstage=done\nwarn=$t\nexit=0\n"
            assertEquals(listOf(t), SetupResultParser.parse(text)!!.facts.warn, t)
        }
    }

    @Test
    fun `the worst-case warn line fits the 200-character line limit`() {
        val all = raised.distinct()
        val line = "warn=" + all.joinToString(",")
        // reported number: see the assertion message
        assertTrue(
            line.length <= ToolResultParser.MAX_LINE,
            "worst case ${line.length} > ${ToolResultParser.MAX_LINE}: $line",
        )
        assertTrue(all.size <= SetupResultParser.MAX_WARN, "${all.size} tokens > MAX_WARN")
        assertEquals(WORST_CASE_LENGTH, line.length, "the worst case changed (was $WORST_CASE_LENGTH): $line")
        val text = "schema=1\nrun=5\nstage=done\n$line\nexit=0\n"
        assertEquals(all, SetupResultParser.parse(text)!!.facts.warn, "the worst case is not read back whole")
    }

    // ── the page side ───────────────────────────────────────────────────────

    private val www = File("../www").absoluteFile
    private val work: File by lazy { Files.createTempDirectory("warn-tokens").toFile() }

    @AfterAll
    fun cleanup() {
        work.deleteRecursively()
    }

    @Suppress("UNCHECKED_CAST")
    private val lines: Map<String, Map<String, Any?>> by lazy {
        val tokens = (raised.distinct() + listOf("a-token-from-a-later-script")).joinToString(",") { "'$it'" }
        val r = StatusScreenBehaviorTest.runHarness(www, work, harness(tokens))
        r.getValue("lines") as Map<String, Map<String, Any?>>
    }

    @Test
    fun `on the page hardlink-patch is the incomplete warning, tools name the tool, the rest is generic`() {
        for (t in raised.distinct()) {
            val l = lines.getValue(t)

            @Suppress("UNCHECKED_CAST")
            val tools = l["tools"] as List<String>
            when {
                t == INCOMPLETE ->
                    assertEquals(
                        listOf(true, false, 0),
                        listOf(l["incomplete"], l["other"], tools.size),
                        t,
                    )
                t.startsWith(TOOL_PREFIX) -> {
                    assertEquals(listOf(false, false), listOf(l["incomplete"], l["other"]), t)
                    assertEquals(listOf("<${t.removePrefix(TOOL_PREFIX)}>"), tools, t)
                }
                else -> assertEquals(listOf(false, true, 0), listOf(l["incomplete"], l["other"], tools.size), t)
            }
        }
        // a token no script raises yet is shown generically, never dropped
        assertEquals(true, lines.getValue("a-token-from-a-later-script")["other"])
    }

    private fun harness(tokens: String) =
        """
        |import path from 'node:path'
        |import { createRequire } from 'node:module'
        |import { pathToFileURL } from 'node:url'
        |const [esbuildMain, wwwSrc, work] = process.argv.slice(2)
        |const esbuild = createRequire(import.meta.url)(esbuildMain)
        |const out = path.join(work, 'setuprun.bundle.mjs')
        |await esbuild.build({ entryPoints: [path.join(wwwSrc, 'lib/setupRun.ts')], bundle: true, format: 'esm', platform: 'node', outfile: out, logLevel: 'error', tsconfigRaw: {} })
        |globalThis.window = globalThis
        |const { warnLines } = await import(pathToFileURL(out).href)
        |const lines = {}
        |for (const t of [$tokens]) lines[t] = warnLines([t], id => '<' + id + '>')
        |console.log(JSON.stringify({ lines }))
        |
        """.trimMargin()

    private companion object {
        const val INCOMPLETE = "hardlink-patch"
        const val TOOL_PREFIX = "tools:"

        /** The tokens the page shows as the one generic "other parts" line. */
        val KNOWN_GENERIC = setOf("clawdhub", "oa-cli", "backup-scripts", "compat-js", "checkOnStart")

        /** The optional tools the full run can skip with a `tools:<id>` warning. */
        val KNOWN_TOOLS =
            setOf("tmux", "ttyd", "dufs", "code-server", "playwright", "claude-code", "gemini-cli", "codex-cli")

        /** `warn=` with every token of the script at once (computed from the script; pinned to notice growth). */
        const val WORST_CASE_LENGTH = 192

        val callPattern = Regex("""\boa_warn\s+("[^"]*"|[^\s;}|&]+)""")
        val literal = Regex("^[A-Za-z0-9][A-Za-z0-9:_.-]*$")
    }
}
