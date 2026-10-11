package com.openclaw.android

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.nio.file.Files

/**
 * `lib/useRuntimeProbes.ts` as it BEHAVES on a return to the page, run in node with the React stand-in
 * of [StatusScreenBehaviorTest], the real `useVisibleAgain` and a fake `document` / `window`:
 * one return (visibilitychange + focus + webview_shown together) is one re-read of every probe, a
 * hidden window's focus is not a return, a missing value keeps exactly one retry timer through a
 * return, and nothing is read after the page is left.
 *
 * Skipped (not failed) when node or `www/node_modules/esbuild` is missing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class RuntimeProbesVisibleAgainTest {
    private val www = File("../www").absoluteFile
    private val work: File by lazy { Files.createTempDirectory("runtime-probes").toFile() }
    private val results by lazy { StatusScreenBehaviorTest.runHarness(www, work, HARNESS) }

    @AfterAll
    fun cleanup() {
        work.deleteRecursively()
    }

    private fun num(
        name: String,
        fact: String,
    ): Int {
        val r = results[name] ?: error("no scenario $name: ${results.keys}")
        assertFalse(r.containsKey("error"), "scenario $name failed: ${r["error"]}")
        return (r[fact] as Number).toInt()
    }

    @Test
    fun `the page is mounted, every probe is read once`() {
        assertEquals(2, num("mount", "probes"))
    }

    @Test
    fun `one return with all three events re-reads every probe once, not once per event`() {
        assertEquals(2, num("oneReturn", "afterMount"))
        assertEquals(4, num("oneReturn", "afterReturn"))
    }

    @Test
    fun `a later return is another re-read`() {
        assertEquals(6, num("laterReturn", "afterSecond"))
    }

    @Test
    fun `focus while the document is hidden is not a return, native webview_shown is one`() {
        assertEquals(2, num("hiddenFocus", "probes"))
        // Native sends it only with the WebView in front; the document's state may lag behind it
        assertEquals(4, num("hiddenNative", "probes"))
    }

    @Test
    fun `a missing value keeps exactly one retry timer, also through a return`() {
        assertEquals(1, num("retry", "timersBefore"))
        assertEquals(1, num("retry", "timersAfterReturn"))
    }

    @Test
    fun `nothing is read after the page is left, and no timer is left`() {
        assertEquals(2, num("unmount", "probes"))
        assertEquals(0, num("unmount", "timers"))
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
            |fs.writeFileSync(stub('probe-bridge.mjs'), 'export const bridge = { probe(id) { globalThis.__probes.push(id); return Promise.resolve({ exitCode: 0, stdout: globalThis.__out[id] ?? "" }) } }\n')
            |const out = stub('probes.bundle.mjs')
            |await esbuild.build({
            |  entryPoints: [path.join(wwwSrc, 'lib/useRuntimeProbes.ts')],
            |  bundle: true, format: 'esm', platform: 'node', outfile: out, logLevel: 'error', tsconfigRaw: {},
            |  plugins: [{ name: 'stubs', setup(b) {
            |    b.onResolve({ filter: /^react${'$'}/ }, () => ({ path: stub('fake-react.mjs') }))
            |    b.onResolve({ filter: /^\.\/bridge${'$'}/ }, () => ({ path: stub('probe-bridge.mjs') }))
            |  } }],
            |})
            |const { useRuntimeProbes } = await import(pathToFileURL(out).href)
            |globalThis.window = globalThis
            |const R = globalThis.__fr
            |let now = 1000000
            |Date.now = () => now
            |let timerId = 0
            |const active = new Map()
            |window.setTimeout = fn => { timerId++; active.set(timerId, fn); return timerId }
            |window.clearTimeout = id => { active.delete(id) }
            |const SPECS = [{ label: 'node', commandId: 'nodeVersion' }, { label: 'git', commandId: 'gitVersion' }]
            |const flush = async () => { for (let i = 0; i < 10; i++) await Promise.resolve() }
            |
            |function dom() {
            |  const target = () => {
            |    const ls = {}
            |    return {
            |      addEventListener: (t, f) => { (ls[t] = ls[t] || []).push(f) },
            |      removeEventListener: (t, f) => { ls[t] = (ls[t] || []).filter(x => x !== f) },
            |      fire: t => (ls[t] || []).slice().forEach(f => f({ type: t })),
            |    }
            |  }
            |  const d = target(); const w = target()
            |  d.visibilityState = 'visible'
            |  globalThis.document = d
            |  window.addEventListener = w.addEventListener
            |  window.removeEventListener = w.removeEventListener
            |  return { d, w }
            |}
            |
            |function mount(out) {
            |  globalThis.__probes = []
            |  globalThis.__out = out
            |  active.clear()
            |  R.slots = []; R.idx = 0; R.pending = []; R.dirty = false
            |  for (let k = 0; k < 20; k++) {
            |    R.idx = 0; R.pending = []; R.dirty = false
            |    useRuntimeProbes(SPECS, true)
            |    const effects = R.pending
            |    R.pending = []
            |    effects.forEach(e => e())
            |    if (!R.dirty) break
            |  }
            |  return { unmount: () => R.slots.forEach(s => { if (s && typeof s.cleanup === 'function') s.cleanup() }) }
            |}
            |const FULL = { nodeVersion: 'v24', gitVersion: '2.4' }
            |
            |const scenarios = {
            |  async mount() {
            |    dom(); mount(FULL); await flush()
            |    return { probes: globalThis.__probes.length }
            |  },
            |  async oneReturn() {
            |    const { d, w } = dom(); mount(FULL); await flush()
            |    const afterMount = globalThis.__probes.length
            |    d.fire('visibilitychange'); w.fire('focus'); w.fire('native:webview_shown'); await flush()
            |    return { afterMount, afterReturn: globalThis.__probes.length }
            |  },
            |  async laterReturn() {
            |    const { w } = dom(); mount(FULL); await flush()
            |    w.fire('native:webview_shown'); await flush()
            |    now += 500
            |    w.fire('native:webview_shown'); await flush()
            |    return { afterSecond: globalThis.__probes.length }
            |  },
            |  async hiddenFocus() {
            |    const { d, w } = dom(); mount(FULL); await flush()
            |    d.visibilityState = 'hidden'
            |    w.fire('focus'); await flush()
            |    return { probes: globalThis.__probes.length }
            |  },
            |  async hiddenNative() {
            |    const { d, w } = dom(); mount(FULL); await flush()
            |    d.visibilityState = 'hidden'
            |    w.fire('native:webview_shown'); await flush()
            |    return { probes: globalThis.__probes.length }
            |  },
            |  async retry() {
            |    const { w } = dom(); mount({ nodeVersion: 'v24', gitVersion: '' }); await flush()
            |    const timersBefore = active.size
            |    now += 500
            |    w.fire('native:webview_shown'); await flush()
            |    return { timersBefore, timersAfterReturn: active.size }
            |  },
            |  async unmount() {
            |    const { w } = dom(); const m = mount({ nodeVersion: 'v24', gitVersion: '' }); await flush()
            |    m.unmount()
            |    now += 500
            |    w.fire('native:webview_shown'); w.fire('focus'); await flush()
            |    return { probes: globalThis.__probes.length, timers: active.size }
            |  },
            |}
            |const results = {}
            |for (const [name, run] of Object.entries(scenarios)) {
            |  try { results[name] = await run() } catch (e) { results[name] = { error: String((e && e.stack) || e) } }
            |}
            |console.log(JSON.stringify(results))
            |
            """.trimMargin()
    }
}
