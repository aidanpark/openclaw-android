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
 * The finished screen when the SETUP run ended `done` with the `hardlink-patch` warning (OpenClaw
 * may be incomplete; QA vc27), as it BEHAVES on the harness of [SetupScreenBehaviorTest] — the
 * rendered tree itself, so the order of the blocks and the alert role are seen, not only the keys:
 * the warning title instead of the plain success, no "everything is installed", the incomplete
 * line FIRST in its own alert card, the other warnings still shown once each, both buttons kept.
 * Then the texts in every locale: no promise of a repair, the two commands named.
 *
 * Skipped (not failed) when node or `www/node_modules/esbuild` is missing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class SetupIncompleteWarnTest {
    private val www = File("../www").absoluteFile
    private val work: File by lazy { Files.createTempDirectory("setup-incomplete").toFile() }
    private val results by lazy {
        val prefix = SetupScreenBehaviorTest.HARNESS.substringBefore("const scenarios = {")
        assertTrue(prefix.length < SetupScreenBehaviorTest.HARNESS.length, "the setup harness changed shape")
        StatusScreenBehaviorTest.runHarness(www, work, prefix + SCENARIOS)
    }

    @AfterAll
    fun cleanup() {
        work.deleteRecursively()
    }

    @Suppress("UNCHECKED_CAST")
    private fun case(name: String): Map<String, Any?> {
        val r = results["finished"] ?: error("no scenario: ${results.keys}")
        assertFalse(r.containsKey("error"), "scenario failed: ${r["error"]}")
        return r[name] as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.list(k: String) = this[k] as List<String>

    private val incompleteCases = listOf("only", "withOther", "withTools", "tokenLast")

    @Test
    fun `with hardlink-patch the title is the warning one and the plain success is never said`() {
        for (c in incompleteCases) {
            val k = case(c).list("keys")
            assertTrue("setup_finished_title_warn" in k && "setup_warn_incomplete" in k, "$c: $k")
            assertFalse("setup_finished_title" in k || "setup_finished_desc" in k, "$c: $k")
            assertEquals("⚠️", case(c)["logo"], c)
        }
    }

    @Test
    fun `the incomplete line is the first block after the title, in its own alert card, said once`() {
        for (c in incompleteCases) {
            val blocks = case(c).list("blocks")
            val title = blocks.indexOfFirst { "setup_finished_title_warn" in it }
            val alert = blocks.indexOfFirst { it.startsWith("alert:") }
            assertTrue(title >= 0 && alert == title + 1, "$c: $blocks")
            assertEquals("alert:setup_warn_incomplete", blocks[alert], "$c: the alert card holds only this line")
            assertEquals(1, case(c).list("keys").count { it == "setup_warn_incomplete" }, c)
            // Every other block (status card, onboarding) comes after it
            assertTrue(blocks.indexOfFirst { it.startsWith("status:") }.let { it == -1 || it > alert }, "$c: $blocks")
        }
    }

    @Test
    fun `other tokens keep their lines - generic once, tools by name - and the token is not one of them`() {
        val other = case("withOther").list("keys")
        assertEquals(1, other.count { it == "setup_warn_other" }, other.toString())
        val tools = case("withTools").list("keys")
        assertEquals(1, tools.count { it == "setup_warn_tool" }, tools.toString())
        assertFalse("setup_warn_other" in tools, "hardlink-patch was also counted as another part: $tools")
        assertFalse("setup_warn_other" in case("only").list("keys"), "hardlink-patch alone said 'other parts'")
        assertTrue(case("withTools").list("blocks").any { it.startsWith("status:") && "setup_warn_tool" in it })
    }

    @Test
    fun `without hardlink-patch the plain success stays - title, description, no alert`() {
        for (c in listOf("none", "otherOnly", "lookalike")) {
            val k = case(c).list("keys")
            assertTrue("setup_finished_title" in k && "setup_finished_desc" in k, "$c: $k")
            assertFalse("setup_finished_title_warn" in k || "setup_warn_incomplete" in k, "$c: $k")
            assertTrue(case(c).list("blocks").none { it.startsWith("alert:") }, c)
            assertEquals("✅", case(c)["logo"], c)
        }
        // A token that only looks like it is another part, not the incomplete warning
        assertTrue("setup_warn_other" in case("lookalike").list("keys"))
    }

    @Test
    fun `the onboarding and later buttons stay and still work with the warning`() {
        val c = case("only")
        assertTrue("setup_onboard_btn" in c.list("keys") && "setup_onboard_later" in c.list("keys"))
        @Suppress("UNCHECKED_CAST")
        val onboard = c["onboardCalls"] as List<List<Any?>>
        assertEquals(
            listOf(listOf("showTerminal"), listOf("writeCommandToTerminal", "openclawOnboard"), listOf("onComplete")),
            onboard,
        )
        @Suppress("UNCHECKED_CAST")
        val later = c["laterCalls"] as List<List<Any?>>
        assertEquals(listOf(listOf("onComplete")), later)
    }

    @Test
    fun `the shown text is the real one in every locale`() {
        val c = case("only")
        assertTrue((c["en"] as String).contains("OpenClaw may be incomplete"), c["en"].toString())
        assertTrue((c["ko"] as String).contains("불완전"), c["ko"].toString())
        assertTrue((c["zh"] as String).contains("不完整"), c["zh"].toString())
    }

    // ── the texts ───────────────────────────────────────────────────────────

    private fun text(
        locale: String,
        key: String,
    ): String {
        val src = File("../www/src/i18n/$locale.ts").readText()
        val m = Regex("""^\s*$key:\s*(['"])(.*)\1,?\s*$""", RegexOption.MULTILINE).find(src)
        assertTrue(m != null, "$key missing from $locale.ts")
        return m!!.groupValues[2]
    }

    @Test
    fun `both keys exist, non-empty and translated, in every locale`() {
        for (key in listOf("setup_warn_incomplete", "setup_finished_title_warn")) {
            val en = text("en", key)
            for (locale in listOf("en", "ko", "zh")) {
                val v = text(locale, key)
                assertTrue(v.isNotBlank(), "$key empty in $locale")
                if (locale != "en") assertTrue(v != en, "$key is English in $locale")
            }
        }
    }

    @Test
    fun `the warning names the check and the update, and promises no repair`() {
        val forbidden =
            mapOf(
                "en" to
                    listOf(
                        "will fix",
                        "fixes",
                        "repair",
                        "nothing was installed",
                        "nothing changed",
                        "is complete",
                    ),
                "ko" to listOf("고쳐", "복구됩니다", "해결됩니다", "아무것도 설치되지", "변경되지 않"),
                "zh" to listOf("修复", "会解决", "没有安装任何", "没有任何更改"),
            )
        for ((locale, words) in forbidden) {
            val v = text(locale, "setup_warn_incomplete")
            assertTrue(v.contains("`openclaw --version`"), "$locale: $v")
            assertTrue(v.contains("`oa --update`"), "$locale: $v")
            // an error that continues is to be reported, not promised away
            words.forEach { assertFalse(v.contains(it, ignoreCase = true), "$locale promises '$it': $v") }
        }
    }

    private companion object {
        val SCENARIOS =
            """
            |// The rendered tree of the live page (one extra render; its effects are dropped)
            |const treeOf = () => { R.idx = 0; R.pending = []; R.dirty = false; const t = Setup({ onComplete: () => {} }); R.pending = []; return t }
            |const walkT = (node, visit) => {
            |  if (node == null || node === false || node === true) return
            |  if (Array.isArray(node)) return node.forEach(c => walkT(c, visit))
            |  visit(node)
            |  if (typeof node === 'object') walkT(node.children, visit)
            |}
            |const textT = node => { const parts = []; walkT(node, x => { if (typeof x !== 'object') parts.push(String(x)) }); return parts.join('') }
            |const keysT = node => (textT(node).match(KEY) || []).map(s => s.slice(1, -1))
            |// The setup container's direct children, in order: 'alert:'/'status:' for the role cards, else their keys
            |function blocks(tree) {
            |  let container = null
            |  walkT(tree, x => { if (!container && typeof x === 'object' && x.props && x.props.className === 'setup-container') container = x })
            |  const out = []
            |  for (const c of (container ? container.children : []).flat(Infinity)) {
            |    if (c == null || c === false || c === true || typeof c !== 'object') continue
            |    const role = c.props && c.props.role
            |    const k = keysT(c).join(',')
            |    out.push(role ? role + ':' + k : k)
            |  }
            |  return out.filter(b => b !== '')
            |}
            |function logoOf(tree) {
            |  let logo = null
            |  walkT(tree, x => { if (!logo && typeof x === 'object' && x.props && x.props.className === 'setup-logo') logo = textT(x) })
            |  return logo
            |}
            |const scenarios = {
            |  finished() {
            |    const out = {}
            |    const fin = (name, warn) => {
            |      const p = mount({ status: TF, runState: busy(7) })
            |      const end = ended('done', { stage: 7, progress: 1, exit: 0, warn, warnings: warn.length, detail: '' })
            |      p.native.runState = end
            |      p.native.status = { bootstrapInstalled: true, platformInstalled: true }
            |      p.emit('run_progress', end)
            |      const tree = treeOf()
            |      out[name] = { keys: p.keys(), blocks: blocks(tree), logo: logoOf(tree) }
            |      return p
            |    }
            |    const p = fin('only', ['hardlink-patch'])
            |    out.only.en = p.textIn('en'); out.only.ko = p.textIn('ko'); out.only.zh = p.textIn('zh')
            |    p.press('setup_onboard_btn')
            |    out.only.onboardCalls = p.native.calls.slice()
            |    const q = fin('laterProbe', ['hardlink-patch'])
            |    q.press('setup_onboard_later')
            |    out.only.laterCalls = q.native.calls.slice()
            |    fin('withOther', ['hardlink-patch', 'checkOnStart', 'clawdhub'])
            |    fin('withTools', ['tools:tmux', 'hardlink-patch'])
            |    fin('tokenLast', ['checkOnStart', 'tools:ttyd', 'hardlink-patch'])
            |    fin('none', [])
            |    fin('otherOnly', ['checkOnStart'])
            |    fin('lookalike', ['hardlink-patch-v2'])
            |    return out
            |  },
            |}
            |const results = {}
            |for (const [name, fn] of Object.entries(scenarios)) {
            |  try { results[name] = fn() } catch (e) { results[name] = { error: String(e && e.stack || e) } }
            |}
            |console.log(JSON.stringify(results))
            |
            """.trimMargin()
    }
}
