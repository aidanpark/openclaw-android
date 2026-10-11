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
 * `screens/SettingsKeepAlive.tsx` as it BEHAVES: the battery-optimization status is read on mount and
 * again when the page is shown again (back from the system's screen — at any time later), and the
 * request button starts no timer. Run in node with the React stand-in of [StatusScreenBehaviorTest].
 *
 * Skipped (not failed) when node or `www/node_modules/esbuild` is missing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class SettingsKeepAliveVisibleAgainTest {
    private val www = File("../www").absoluteFile
    private val work: File by lazy { Files.createTempDirectory("keepalive").toFile() }
    private val results by lazy { StatusScreenBehaviorTest.runHarness(www, work, HARNESS) }

    @AfterAll
    fun cleanup() {
        work.deleteRecursively()
    }

    private fun fact(
        name: String,
        key: String,
    ): Any? {
        val r = results[name] ?: error("no scenario $name: ${results.keys}")
        assertFalse(r.containsKey("error"), "scenario $name failed: ${r["error"]}")
        return r[key]
    }

    @Test
    fun `the status is read once on mount and the request button is shown`() {
        assertEquals(1, (fact("mount", "reads") as Number).toInt())
        assertEquals(true, fact("mount", "hasRequestButton"))
    }

    @Test
    fun `asking for the exclusion reads nothing and starts no timer - the return does the re-read`() {
        assertEquals(true, fact("request", "asked"))
        assertEquals(0, (fact("request", "timers") as Number).toInt())
        assertEquals(1, (fact("request", "reads") as Number).toInt())
    }

    @Test
    fun `back at the page, whenever that is, the status is read again and the page follows it`() {
        assertEquals(2, (fact("returned", "reads") as Number).toInt())
        assertEquals(true, fact("returned", "showsExcluded"))
        assertEquals(false, fact("returned", "showsRequest"))
    }

    @Test
    fun `a return while native gives no answer changes nothing and throws nothing`() {
        assertEquals(true, fact("noAnswer", "unchanged"))
    }

    @Test
    fun `the page asks to be called when it is shown again`() {
        assertTrue(fact("mount", "registered") as Boolean)
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
            |fs.writeFileSync(stub('ka-router.mjs'), 'export function useRoute() { return { navigate() {} } }\n')
            |fs.writeFileSync(stub('ka-i18n.mjs'), "export function t(k) { return '{' + k + '}' }\n")
            |fs.writeFileSync(stub('ka-bridge.mjs'), [
            |  'export const bridge = {',
            |  '  callJson(name) { globalThis.__calls.push(["callJson", name]); return globalThis.__status },',
            |  '  call(name) { globalThis.__calls.push(["call", name]) },',
            |  '}',
            |].join('\n') + '\n')
            |fs.writeFileSync(stub('ka-visible.mjs'), 'export function useVisibleAgain(fn) { globalThis.__visible = fn }\n')
            |const stubs = {
            |  'react': stub('fake-react.mjs'),
            |  '../lib/router': stub('ka-router.mjs'),
            |  '../lib/bridge': stub('ka-bridge.mjs'),
            |  '../i18n': stub('ka-i18n.mjs'),
            |  '../lib/useVisibleAgain': stub('ka-visible.mjs'),
            |}
            |const out = stub('keepalive.bundle.mjs')
            |await esbuild.build({
            |  entryPoints: [path.join(wwwSrc, 'screens/SettingsKeepAlive.tsx')],
            |  bundle: true, format: 'esm', platform: 'node', outfile: out, logLevel: 'error',
            |  jsx: 'transform', jsxFactory: '__h', jsxFragment: '__Frag', inject: [stub('jsx.mjs')],
            |  tsconfigRaw: {},
            |  plugins: [{ name: 'stubs', setup(b) { b.onResolve({ filter: /.*/ }, a => (stubs[a.path] ? { path: stubs[a.path] } : undefined)) } }],
            |})
            |globalThis.window = globalThis
            |const R = (globalThis.__fr = globalThis.__fr || { slots: [], idx: 0, pending: [], dirty: false, listeners: {} })
            |let timers = 0
            |window.setTimeout = () => { timers++; return timers }
            |const { SettingsKeepAlive } = await import(pathToFileURL(out).href)
            |
            |const walk = (node, acc = []) => {
            |  if (node == null || typeof node !== 'object') return acc
            |  acc.push(node)
            |  ;(node.children || []).forEach(c => walk(c, acc))
            |  return acc
            |}
            |function mount(status) {
            |  R.slots = []; R.idx = 0; R.pending = []; R.dirty = false
            |  globalThis.__calls = []; globalThis.__status = status; globalThis.__visible = undefined; timers = 0
            |  let tree
            |  const render = () => {
            |    for (let k = 0; k < 20; k++) {
            |      R.idx = 0; R.pending = []; R.dirty = false
            |      tree = SettingsKeepAlive()
            |      const effects = R.pending
            |      R.pending = []
            |      effects.forEach(e => e())
            |      if (!R.dirty) return
            |    }
            |    throw new Error('never settles')
            |  }
            |  render()
            |  return { render, tree: () => tree }
            |}
            |const reads = () => globalThis.__calls.filter(c => c[0] === 'callJson').length
            |const has = (tree, text) => JSON.stringify(tree).includes(text)
            |const requestButton = tree => walk(tree).find(n => n.type === 'button' && JSON.stringify(n.children).includes('{ka_request}'))
            |
            |const scenarios = {
            |  mount() {
            |    const m = mount({ isIgnoring: false })
            |    return { reads: reads(), hasRequestButton: !!requestButton(m.tree()), registered: typeof globalThis.__visible === 'function' }
            |  },
            |  request() {
            |    const m = mount({ isIgnoring: false })
            |    requestButton(m.tree()).props.onClick()
            |    const asked = globalThis.__calls.some(c => c[0] === 'call' && c[1] === 'requestBatteryOptimizationExclusion')
            |    return { asked, timers, reads: reads() }
            |  },
            |  returned() {
            |    const m = mount({ isIgnoring: false })
            |    requestButton(m.tree()).props.onClick()
            |    globalThis.__status = { isIgnoring: true }
            |    globalThis.__visible()
            |    m.render()
            |    return { reads: reads(), showsExcluded: has(m.tree(), '{ka_excluded}'), showsRequest: !!requestButton(m.tree()) }
            |  },
            |  noAnswer() {
            |    const m = mount({ isIgnoring: false })
            |    globalThis.__status = null
            |    globalThis.__visible()
            |    m.render()
            |    return { unchanged: !!requestButton(m.tree()) }
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
