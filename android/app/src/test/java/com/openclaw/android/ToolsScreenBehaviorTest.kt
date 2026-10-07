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
 * Settings → Tools (`SettingsTools.tsx`) as it BEHAVES, run in node with the hook stand-in of
 * [StatusScreenBehaviorTest] over the real `lib/bridge.ts` and a fake `window.OpenClaw`:
 * - a cancel that came too late (`done` + CANCEL_TOO_LATE): the page says the install had already
 *   finished, shows the tool installed — never "cancelled" — also on a page made again later
 *   (`lastEndNotice`); a plain `done` says nothing;
 * - shown again (`useVisibleAgain`): nothing while an install runs; otherwise the installed list is
 *   read every time, and the run check is asked only when the last one (the mount's included) is
 *   at least 15 s old.
 *
 * Skipped (not failed) when node or `www/node_modules/esbuild` is missing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class ToolsScreenBehaviorTest {
    private val www = File("../www").absoluteFile
    private val work: File by lazy { Files.createTempDirectory("tools-screen").toFile() }
    private val results by lazy { StatusScreenBehaviorTest.runHarness(www, work, HARNESS) }

    @AfterAll
    fun cleanup() {
        work.deleteRecursively()
    }

    private fun result(name: String): Map<String, Any?> {
        val r = results[name] ?: error("no scenario $name: ${results.keys}")
        assertFalse(r.containsKey("error"), "scenario $name failed: ${r["error"]}")
        return r
    }

    @Suppress("UNCHECKED_CAST")
    private fun sub(
        name: String,
        fact: String,
    ): Map<String, Any?> = result(name)[fact] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun keysOf(map: Map<String, Any?>): List<String> = map["keys"] as List<String>

    @Suppress("UNCHECKED_CAST")
    private fun rowOf(map: Map<String, Any?>): List<String> = map["row"] as List<String>

    private fun num(
        map: Map<String, Any?>,
        fact: String,
    ): Int = (map[fact] as Number).toInt()

    // ── B: a cancel that came too late ──────────────────────────────────────

    @Test
    fun `the done end of a late cancel says the install had already finished, never cancelled`() {
        val f = sub("lateCancel", "end")
        val k = keysOf(f)
        assertTrue("tools_cancel_too_late" in k, k.toString())
        assertFalse("tool_cancelled" in k || "tool_cancel_requested" in k || "tool_installing" in k, k.toString())
        assertTrue((f["text"] as String).contains("{tools_cancel_too_late}"), f.toString())
    }

    @Test
    fun `after a late cancel the tool shows installed, without an install button`() {
        val row = rowOf(sub("lateCancel", "end"))
        assertTrue("tool_installed" in row, row.toString())
        assertFalse("tool_install" in row || "tool_reinstall" in row, row.toString())
        assertEquals(true, sub("lateCancel", "end")["checkStarted"], "no run check after the end")
    }

    @Test
    fun `the late-cancel text is real in every locale`() {
        val f = sub("lateCancel", "end")
        val expected =
            mapOf(
                "en" to "already finished",
                "ko" to "이미 끝나",
                "zh" to "已经完成",
            )
        for ((lang, words) in expected) assertTrue((f[lang] as String).contains(words), "$lang: ${f[lang]}")
    }

    @Test
    fun `a page made again after a late cancel restores the notice with the tool's name`() {
        val r = sub("lateCancel", "reopened")
        assertTrue((r["text"] as String).contains("tmux: {tools_cancel_too_late}"), r.toString())
        assertTrue("tool_installed" in rowOf(r), r.toString())
    }

    @Test
    fun `a plain done says nothing, also when reopened, and an unknown done reason says nothing`() {
        for (fact in listOf("plainEnd", "plainReopened", "otherReason")) {
            val k = keysOf(sub("lateCancel", fact))
            assertFalse("tools_cancel_too_late" in k || "tool_cancelled" in k, "$fact: $k")
        }
    }

    @Test
    fun `a cancel that really interrupted still says cancelled and the tool is not installed`() {
        val f = sub("lateCancel", "cancelled")
        assertTrue("tool_cancelled" in keysOf(f), f.toString())
        assertFalse("tools_cancel_too_late" in keysOf(f), f.toString())
        assertTrue("tool_install" in rowOf(f), f.toString())
    }

    @Test
    fun `starting another install clears the late-cancel notice`() {
        val k = keysOf(sub("lateCancel", "nextInstall"))
        assertFalse("tools_cancel_too_late" in k, k.toString())
    }

    // ── D: shown again ──────────────────────────────────────────────────────

    @Test
    fun `shown again while an install runs does nothing - no read, no check, the progress stays`() {
        val r = result("busy")
        assertEquals(num(r, "readsBefore"), num(r, "readsAfter"), r.toString())
        assertEquals(num(r, "checksBefore"), num(r, "checksAfter"), r.toString())
        @Suppress("UNCHECKED_CAST")
        val k = r["keys"] as List<String>
        assertTrue("tool_installing" in k, k.toString())
    }

    @Test
    fun `shown again reads the installed list every time, a tool installed in the terminal shows installed`() {
        val r = result("rule15")
        assertTrue(num(r, "readsAt1s") > num(r, "readsAtMount"), r.toString())
        assertTrue(num(r, "readsAt14999") > num(r, "readsAt1s"), r.toString())
        assertTrue("tool_installed" in rowOf(sub("rule15", "rowAt1s")), r.toString())
    }

    @Test
    fun `the run check is asked again only 15 s after the last one, the mount's included`() {
        val r = result("rule15")
        assertEquals(1, num(r, "checksAtMount"), "the mount asks once")
        assertEquals(1, num(r, "checksAt1s"), "asked again 1 s after the mount's check")
        assertEquals(1, num(r, "checksAt14999"), "asked again before 15 s")
        assertEquals(2, num(r, "checksAt15000"), "not asked at 15 s")
        assertEquals(2, num(r, "checksAt29999"), "the 15 s count from the second check")
        assertEquals(3, num(r, "checksAt30000"))
    }

    @Test
    fun `only the newest check's answers count`() {
        val r = result("rule15")
        assertEquals(false, r["oldAnswerShown"], r.toString())
        assertEquals(true, r["newAnswerShown"], r.toString())
    }

    private companion object {
        val HARNESS =
            """
            |import fs from 'node:fs'
            |import path from 'node:path'
            |import { createRequire } from 'node:module'
            |import { pathToFileURL } from 'node:url'
            |const [esbuildMain, wwwSrc, work] = process.argv.slice(2)
            |const esbuild = createRequire(import.meta.url)(esbuildMain)
            |const stub = n => path.join(work, n)
            |const stubs = {
            |  'react': stub('fake-react.mjs'),
            |  '../lib/router': stub('stub-router.mjs'),
            |  '../lib/useNativeEvent': stub('stub-native-event.mjs'),
            |  '../lib/useVisibleAgain': stub('stub-visible-again.mjs'),
            |  '../i18n': stub('stub-i18n.mjs'),
            |  'real-i18n-en': path.join(wwwSrc, 'i18n/en.ts'),
            |  'real-i18n-ko': path.join(wwwSrc, 'i18n/ko.ts'),
            |  'real-i18n-zh': path.join(wwwSrc, 'i18n/zh.ts'),
            |}
            |const out = stub('tools.bundle.mjs')
            |await esbuild.build({
            |  entryPoints: [path.join(wwwSrc, 'screens/SettingsTools.tsx')],
            |  bundle: true, format: 'esm', platform: 'node', outfile: out, logLevel: 'error',
            |  jsx: 'transform', jsxFactory: '__h', jsxFragment: '__Frag', inject: [stub('jsx.mjs')],
            |  tsconfigRaw: {},
            |  plugins: [{ name: 'stubs', setup(b) { b.onResolve({ filter: /.*/ }, a => (stubs[a.path] ? { path: stubs[a.path] } : undefined)) } }],
            |})
            |globalThis.window = globalThis
            |const R = (globalThis.__fr = globalThis.__fr || { slots: [], idx: 0, pending: [], dirty: false, listeners: {} })
            |let now = 1000000
            |Date.now = () => now
            |const { SettingsTools } = await import(pathToFileURL(out).href)
            |const KEY = /\{(tools?_[a-z_0-9]+)\}/g
            |const IDLE = { target: null, phase: 'idle', progress: 0, message: '', cancelRequested: false, longRunning: false, reason: null }
            |
            |function mount(native) {
            |  R.slots = []; R.idx = 0; R.pending = []; R.dirty = false; R.listeners = {}
            |  globalThis.__lang = null
            |  const n = Object.assign({ installed: [], broken: [], toolState: IDLE, calls: [], reads: {} }, native)
            |  const record = name => (...a) => { n.calls.push([name].concat(a)) }
            |  const read = (name, f) => () => { n.reads[name] = (n.reads[name] || 0) + 1; return JSON.stringify(f()) }
            |  window.OpenClaw = {
            |    getInstalledTools: read('getInstalledTools', () => n.installed.map(id => ({ id, name: id, version: 'installed', broken: n.broken.includes(id) }))),
            |    getToolInstallState: read('getToolInstallState', () => n.toolState),
            |    checkInstalledToolsAsync: record('checkInstalledToolsAsync'),
            |    installTool: record('installTool'), cancelToolInstall: record('cancelToolInstall'),
            |  }
            |  let tree
            |  const render = () => {
            |    for (let k = 0; k < 50; k++) {
            |      R.idx = 0; R.pending = []; R.dirty = false
            |      tree = SettingsTools()
            |      const effects = R.pending
            |      R.pending = []
            |      effects.forEach(e => e())
            |      if (!R.dirty) return
            |    }
            |    throw new Error('the page never settles')
            |  }
            |  const walk = (node, visit) => {
            |    if (node == null || node === false || node === true) return
            |    if (Array.isArray(node)) return node.forEach(c => walk(c, visit))
            |    visit(node)
            |    if (typeof node === 'object') walk(node.children, visit)
            |  }
            |  const textOf = node => { const parts = []; walk(node, x => { if (typeof x !== 'object') parts.push(String(x)) }); return parts.join('') }
            |  const keysOf = node => (textOf(node).match(KEY) || []).map(s => s.slice(1, -1))
            |  // the keys of one tool's card (found by its label)
            |  const row = name => {
            |    let found = null
            |    walk(tree, x => {
            |      if (found || typeof x !== 'object' || !x.props || x.props.className !== 'card') return
            |      let label = null
            |      walk(x.children, y => { if (!label && typeof y === 'object' && y.props && y.props.className === 'card-label') label = textOf(y.children) })
            |      if (label === name) found = x
            |    })
            |    if (!found) throw new Error('no card ' + name)
            |    return keysOf(found)
            |  }
            |  const pressInstall = name => {
            |    let b = null
            |    walk(tree, x => {
            |      if (b || typeof x !== 'object' || !x.props || x.props.className !== 'card') return
            |      let label = null
            |      walk(x.children, y => { if (!label && typeof y === 'object' && y.props && y.props.className === 'card-label') label = textOf(y.children) })
            |      if (label === name) walk(x.children, y => { if (!b && typeof y === 'object' && y.type === 'button') b = y })
            |    })
            |    if (!b) throw new Error('no install button for ' + name)
            |    b.props.onClick(); render()
            |  }
            |  render()
            |  return {
            |    native: n,
            |    keys: () => keysOf(tree),
            |    text: () => textOf(tree),
            |    textIn: lang => { globalThis.__lang = lang; render(); const t = textOf(tree); globalThis.__lang = null; render(); return t },
            |    row,
            |    pressInstall,
            |    emit: (type, data) => { R.listeners[type](data); render() },
            |    shown: () => { R.listeners['visible-again'](); render() },
            |    reads: name => n.reads[name] || 0,
            |    count: name => n.calls.filter(c => c[0] === name).length,
            |    lastCheckId: () => { const c = n.calls.filter(x => x[0] === 'checkInstalledToolsAsync'); return c.length ? c[c.length - 1][1] : null },
            |  }
            |}
            |
            |const view = p => ({ keys: p.keys(), text: p.text(), row: p.row('tmux') })
            |const running = (extra) => Object.assign({}, IDLE, { target: 'tmux', phase: 'running', progress: 0.5, message: 'npm' }, extra)
            |const doneTooLate = { target: 'tmux', phase: 'done', progress: 1, message: '', cancelRequested: false, longRunning: false, reason: 'CANCEL_TOO_LATE' }
            |
            |const scenarios = {
            |  lateCancel() {
            |    const out = {}
            |    // cancel asked, the install had already finished
            |    let p = mount({ toolState: running({ phase: 'cancelling', cancelRequested: true }) })
            |    const checksBefore = p.count('checkInstalledToolsAsync')
            |    p.native.installed = ['tmux']
            |    p.native.toolState = doneTooLate
            |    p.emit('tool_progress', doneTooLate)
            |    out.end = Object.assign(view(p), { checkStarted: p.count('checkInstalledToolsAsync') > checksBefore })
            |    // another install started from this page clears the notice
            |    p.pressInstall('ttyd')
            |    out.nextInstall = { keys: p.keys(), calls: p.native.calls.slice() }
            |    // The notice is text made when the event arrives: each locale gets its own page
            |    for (const lang of ['en', 'ko', 'zh']) {
            |      const q = mount({ toolState: running({ phase: 'cancelling', cancelRequested: true }) })
            |      q.native.installed = ['tmux']; q.native.toolState = doneTooLate
            |      globalThis.__lang = lang
            |      q.emit('tool_progress', doneTooLate)
            |      out.end[lang] = q.text()
            |      globalThis.__lang = null
            |    }
            |    // a page made again later (another tab, an Activity recreation)
            |    out.reopened = view(mount({ installed: ['tmux'], toolState: doneTooLate }))
            |    // a plain success: nothing to say
            |    p = mount({ toolState: running() })
            |    p.native.installed = ['tmux']
            |    const plain = Object.assign({}, doneTooLate, { reason: null })
            |    p.native.toolState = plain
            |    p.emit('tool_progress', plain)
            |    out.plainEnd = view(p)
            |    out.plainReopened = view(mount({ installed: ['tmux'], toolState: plain }))
            |    out.otherReason = view(mount({ installed: ['tmux'], toolState: Object.assign({}, plain, { reason: 'SOMETHING_ELSE' }) }))
            |    // a cancel that really interrupted
            |    p = mount({ toolState: running({ phase: 'cancelling', cancelRequested: true }) })
            |    const cancelled = { target: 'tmux', phase: 'cancelled', progress: 0, message: '', reason: 'INTERRUPTED' }
            |    p.native.toolState = cancelled
            |    p.emit('tool_progress', cancelled)
            |    out.cancelled = view(p)
            |    return out
            |  },
            |  busy() {
            |    const p = mount({ toolState: running() })
            |    const readsBefore = p.reads('getInstalledTools'), checksBefore = p.count('checkInstalledToolsAsync')
            |    p.native.installed = ['tmux']
            |    now += 60000
            |    p.shown(); p.shown()
            |    return { readsBefore, readsAfter: p.reads('getInstalledTools'), checksBefore, checksAfter: p.count('checkInstalledToolsAsync'), keys: p.keys() }
            |  },
            |  rule15() {
            |    const out = {}
            |    const p = mount({})
            |    out.checksAtMount = p.count('checkInstalledToolsAsync')
            |    out.readsAtMount = p.reads('getInstalledTools')
            |    const mountId = p.lastCheckId()
            |    // tmux was installed in the terminal meanwhile
            |    p.native.installed = ['tmux']
            |    now += 1000; p.shown()
            |    out.checksAt1s = p.count('checkInstalledToolsAsync'); out.readsAt1s = p.reads('getInstalledTools'); out.rowAt1s = { row: p.row('tmux') }
            |    now += 13999; p.shown()
            |    out.checksAt14999 = p.count('checkInstalledToolsAsync'); out.readsAt14999 = p.reads('getInstalledTools')
            |    now += 1; p.shown()
            |    out.checksAt15000 = p.count('checkInstalledToolsAsync')
            |    const newId = p.lastCheckId()
            |    // answers to the mount's check arrive late; only the newest request counts
            |    p.emit('tools_check', { callbackId: mountId, target: 'tmux', status: 'failed' })
            |    out.oldAnswerShown = p.row('tmux').includes('tool_installed_broken')
            |    p.emit('tools_check', { callbackId: newId, target: 'tmux', status: 'failed' })
            |    out.newAnswerShown = p.row('tmux').includes('tool_installed_broken')
            |    now += 14999; p.shown()
            |    out.checksAt29999 = p.count('checkInstalledToolsAsync')
            |    now += 1; p.shown()
            |    out.checksAt30000 = p.count('checkInstalledToolsAsync')
            |    return out
            |  },
            |}
            |const results = {}
            |for (const [name, run] of Object.entries(scenarios)) {
            |  try { results[name] = run() } catch (e) { results[name] = { error: String((e && e.stack) || e) } }
            |}
            |console.log(JSON.stringify(results))
            |
            """.trimMargin()
    }
}
