package com.openclaw.android

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.nio.file.Files

/**
 * `lib/useVisibleAgain.ts` as it BEHAVES, run in node with the React hook stand-in of
 * [StatusScreenBehaviorTest] and a minimal fake `document` / `window` (listener tables, a fake
 * clock, timer spies): each way the page is shown again (visibilitychange to visible, window focus,
 * native `webview_shown`) calls back; events within 300 ms are one return; a hidden document never
 * calls; the newest callback is the one called; no timer is ever used; listeners are registered
 * once and removed on unmount; without a DOM nothing throws.
 *
 * Skipped (not failed) when node or `www/node_modules/esbuild` is missing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class VisibleAgainHookTest {
    private val www = File("../www").absoluteFile
    private val work: File by lazy { Files.createTempDirectory("visible-again").toFile() }
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
    private fun list(
        name: String,
        fact: String,
    ): List<Any?> = result(name)[fact] as List<Any?>

    private fun num(
        name: String,
        fact: String,
    ): Int = (result(name)[fact] as Number).toInt()

    @Test
    fun `each way back to the page calls once - visibilitychange to visible, focus, webview_shown`() {
        assertEquals(1, num("eachKind", "visibility"))
        assertEquals(1, num("eachKind", "focus"))
        assertEquals(1, num("eachKind", "webviewShown"))
    }

    @Test
    fun `a burst of all three at once is one call, and 300 ms later is a new return`() {
        assertEquals(listOf(1.0, 1.0, 2.0), list("burst", "calls"))
    }

    @Test
    fun `the 300 ms count from the last call - an event in between does not push the next one out`() {
        // events at 0, 200, 400 ms: 0 calls, 200 is part of it, 400 is 400 ms after the call
        assertEquals(2, num("window", "calls"))
    }

    @Test
    fun `a hidden document never calls, and does not use up the next return`() {
        assertEquals(0, num("hidden", "whileHidden"))
        assertEquals(1, num("hidden", "afterVisible"))
    }

    @Test
    fun `the newest callback is the one called`() {
        assertEquals(listOf("second"), list("latest", "called"))
    }

    @Test
    fun `the listeners are added once however often the page renders, and removed on unmount`() {
        assertEquals(
            listOf("document:visibilitychange", "window:focus", "window:native:webview_shown"),
            list("lifecycle", "added"),
        )
        assertEquals(
            listOf("document:visibilitychange", "window:focus", "window:native:webview_shown"),
            list("lifecycle", "removed"),
        )
        assertEquals(true, result("lifecycle")["sameHandler"])
        assertEquals(0, num("lifecycle", "callsAfterUnmount"))
    }

    @Test
    fun `no timer is ever used - nothing is read while the page stays in front`() {
        assertEquals(0, num("timers", "timeouts"))
        assertEquals(0, num("timers", "intervals"))
    }

    @Test
    fun `without a DOM (or without addEventListener) the hook does nothing and throws nothing`() {
        assertEquals(true, result("domless")["noDocument"])
        assertEquals(true, result("domless")["noAddEventListener"])
    }

    private companion object {
        val HARNESS =
            """
            |import path from 'node:path'
            |import { createRequire } from 'node:module'
            |import { pathToFileURL } from 'node:url'
            |const [esbuildMain, wwwSrc, work] = process.argv.slice(2)
            |const esbuild = createRequire(import.meta.url)(esbuildMain)
            |const stub = n => path.join(work, n)
            |const out = stub('visible.bundle.mjs')
            |await esbuild.build({
            |  entryPoints: [path.join(wwwSrc, 'lib/useVisibleAgain.ts')],
            |  bundle: true, format: 'esm', platform: 'node', outfile: out, logLevel: 'error', tsconfigRaw: {},
            |  plugins: [{ name: 'stubs', setup(b) { b.onResolve({ filter: /^react${'$'}/ }, () => ({ path: stub('fake-react.mjs') })) } }],
            |})
            |const { useVisibleAgain } = await import(pathToFileURL(out).href)
            |globalThis.window = globalThis
            |const R = globalThis.__fr
            |let now = 1000000
            |Date.now = () => now
            |let timeouts = 0, intervals = 0
            |window.setTimeout = () => { timeouts++; return 0 }
            |window.setInterval = () => { intervals++; return 0 }
            |
            |// A fake DOM: listener tables per target, a visibility state, a log of add/remove
            |function dom() {
            |  const log = { added: [], removed: [] }
            |  const target = name => {
            |    const ls = {}
            |    return {
            |      ls,
            |      addEventListener: (t, f) => { log.added.push(name + ':' + t); (ls[t] = ls[t] || []).push(f) },
            |      removeEventListener: (t, f) => { log.removed.push(name + ':' + t); ls[t] = (ls[t] || []).filter(x => x !== f) },
            |      fire: t => (ls[t] || []).slice().forEach(f => f({ type: t })),
            |    }
            |  }
            |  const d = target('document'); const w = target('window')
            |  d.visibilityState = 'visible'
            |  globalThis.document = d
            |  window.addEventListener = w.addEventListener
            |  window.removeEventListener = w.removeEventListener
            |  return { d, w, log }
            |}
            |
            |function mount(fn) {
            |  R.slots = []; R.idx = 0; R.pending = []; R.dirty = false
            |  let current = fn
            |  const render = () => {
            |    for (let k = 0; k < 20; k++) {
            |      R.idx = 0; R.pending = []; R.dirty = false
            |      useVisibleAgain(current)
            |      const effects = R.pending
            |      R.pending = []
            |      effects.forEach(e => e())
            |      if (!R.dirty) return
            |    }
            |    throw new Error('never settles')
            |  }
            |  render()
            |  return {
            |    rerender: f => { current = f; render() },
            |    unmount: () => R.slots.forEach(s => { if (s && typeof s.cleanup === 'function') s.cleanup() }),
            |  }
            |}
            |
            |const scenarios = {
            |  eachKind() {
            |    const one = kind => {
            |      const { d, w } = dom()
            |      let n = 0
            |      mount(() => { n++ })
            |      if (kind === 'visibility') d.fire('visibilitychange')
            |      if (kind === 'focus') w.fire('focus')
            |      if (kind === 'webviewShown') w.fire('native:webview_shown')
            |      return n
            |    }
            |    return { visibility: one('visibility'), focus: one('focus'), webviewShown: one('webviewShown') }
            |  },
            |  burst() {
            |    const { d, w } = dom()
            |    let n = 0
            |    mount(() => { n++ })
            |    const calls = []
            |    d.fire('visibilitychange'); w.fire('focus'); w.fire('native:webview_shown')
            |    calls.push(n)
            |    now += 299; w.fire('focus')
            |    calls.push(n)
            |    now += 1; w.fire('native:webview_shown')
            |    calls.push(n)
            |    return { calls }
            |  },
            |  window() {
            |    const { w } = dom()
            |    let n = 0
            |    mount(() => { n++ })
            |    w.fire('focus'); now += 200; w.fire('focus'); now += 200; w.fire('focus')
            |    return { calls: n }
            |  },
            |  hidden() {
            |    const { d } = dom()
            |    let n = 0
            |    mount(() => { n++ })
            |    d.visibilityState = 'hidden'
            |    d.fire('visibilitychange')
            |    const whileHidden = n
            |    now += 100
            |    d.visibilityState = 'visible'
            |    d.fire('visibilitychange')
            |    return { whileHidden, afterVisible: n }
            |  },
            |  latest() {
            |    const { w } = dom()
            |    const called = []
            |    const m = mount(() => called.push('first'))
            |    m.rerender(() => called.push('second'))
            |    w.fire('focus')
            |    return { called }
            |  },
            |  lifecycle() {
            |    const { w, log } = dom()
            |    let n = 0
            |    const m = mount(() => { n++ })
            |    m.rerender(() => { n++ }); m.rerender(() => { n++ })
            |    const added = log.added.slice()
            |    const handler = w.ls['focus'][0]
            |    const docHandler = globalThis.document.ls['visibilitychange'][0]
            |    m.unmount()
            |    const sameHandler = handler === docHandler && (w.ls['focus'] || []).length === 0 && (w.ls['native:webview_shown'] || []).length === 0 && (globalThis.document.ls['visibilitychange'] || []).length === 0
            |    w.fire('focus'); w.fire('native:webview_shown'); globalThis.document.fire('visibilitychange')
            |    return { added, removed: log.removed.slice(), sameHandler, callsAfterUnmount: n }
            |  },
            |  timers() {
            |    const { d, w } = dom()
            |    const m = mount(() => {})
            |    d.fire('visibilitychange'); now += 5000; w.fire('focus'); now += 5000; w.fire('native:webview_shown')
            |    m.rerender(() => {}); m.unmount()
            |    return { timeouts, intervals }
            |  },
            |  domless() {
            |    const out = {}
            |    delete globalThis.document
            |    window.addEventListener = undefined; window.removeEventListener = undefined
            |    try { const m = mount(() => {}); m.unmount(); out.noDocument = true } catch (e) { out.noDocument = String(e) }
            |    globalThis.document = { visibilityState: 'visible', addEventListener() { throw new Error('used') } }
            |    try { const m = mount(() => {}); m.unmount(); out.noAddEventListener = true } catch (e) { out.noAddEventListener = String(e) }
            |    delete globalThis.document
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
