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
 * The return to the app after HOME, the notification shade or a system screen (QA vc30: the
 * failure/resume screen stayed stale). Native side — MainActivity, which cannot be unit-tested,
 * pinned as source: `leftWindow` is set by `onPause` and by losing the window focus, and
 * `onBackToWindow` (from `onResume` and from regaining the focus) sends ONE `webview_shown` only when
 * the flag is set (clearing it) and the WebView is the visible screen; no other new sender.
 * Page side — the first-install page with the REAL `useVisibleAgain` over a fake DOM (the shared
 * harness of [SetupScreenBehaviorTest] stubs the hook; here it is bundled for real): on the resume
 * and failure screens each of the three sources leaves once when the marker appeared, starts
 * nothing, and bursts are one return; on every other screen nothing happens at all.
 *
 * The page part is skipped (not failed) when node or `www/node_modules/esbuild` is missing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class WindowReturnEventTest {
    // ── MainActivity, as source ─────────────────────────────────────────────

    private val activity by lazy { File("src/main/java/com/openclaw/android/MainActivity.kt").readText() }

    private fun member(signature: String): String {
        val start = activity.indexOf(signature)
        assertTrue(start >= 0, "$signature not found")
        val next =
            Regex(
                """\n {4}(override fun|private fun|fun |private var|private val|@)""",
            ).find(activity, start + 1)
        return activity.substring(start, next?.range?.first ?: activity.length)
    }

    @Test
    fun `leaving the window sets the flag - onPause and losing the focus, nothing else sets it`() {
        assertTrue(
            activity.contains("private var leftWindow = false"),
            "the flag starts unset (no event on the first start)",
        )
        val pause = member("override fun onPause()")
        assertTrue(pause.contains("super.onPause()") && pause.contains("leftWindow = true"), pause)
        val focus = member("override fun onWindowFocusChanged(")
        assertTrue(focus.contains("if (hasFocus) onBackToWindow() else leftWindow = true"), focus)
        assertEquals(2, Regex("""leftWindow = true""").findAll(activity).count(), "another place sets the flag")
    }

    @Test
    fun `coming back - onResume and regaining the focus - both go through onBackToWindow`() {
        val resume = member("override fun onResume()")
        assertTrue(resume.contains("super.onResume()") && resume.contains("onBackToWindow()"), resume)
        assertEquals(
            3,
            Regex("""\bonBackToWindow\(\)""").findAll(activity).count(),
            "declared once, called from onResume and the focus",
        )
    }

    @Test
    fun `onBackToWindow sends one event - only with the flag set, clearing it first, only with the WebView visible`() {
        val back = member("private fun onBackToWindow()")
        val guard = back.indexOf("if (!leftWindow) return")
        val clear = back.indexOf("leftWindow = false")
        val emit = back.indexOf("eventBridge.emit(\"webview_shown\"")
        assertTrue(guard in 0 until clear && clear < emit, "the order is not guard → clear → emit: $back")
        assertTrue(
            back.contains("if (binding.webView.visibility == View.VISIBLE) eventBridge.emit(\"webview_shown\""),
            "the event is not limited to a visible WebView: $back",
        )
        // the flag is cleared even when the terminal is in front: a later return does not send a stale event
        assertTrue(back.substring(clear, emit).contains("if (binding.webView.visibility"), back)
    }

    @Test
    fun `webview_shown is sent from exactly two places - showWebView and the return to the window`() {
        val main = File("src/main/java/com/openclaw/android")
        val senders =
            main
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { f -> Regex("""emit\("webview_shown"""").findAll(f.readText()).map { f.name } }
                .toList()
        assertEquals(listOf("MainActivity.kt", "MainActivity.kt"), senders.sorted())
        assertTrue(member("fun showWebView()").contains("eventBridge.emit(\"webview_shown\""))
        assertTrue(member("private fun onBackToWindow()").contains("eventBridge.emit(\"webview_shown\""))
    }

    @Test
    fun `the page listens through useVisibleAgain, not the native event alone`() {
        val setup = File("../www/src/screens/Setup.tsx").readText()
        assertTrue(setup.contains("useVisibleAgain(onWebViewShown)"))
        assertFalse(
            setup.contains("useNativeEvent('webview_shown'"),
            "Setup.tsx listens to webview_shown on its own again",
        )
        val hook = File("../www/src/lib/useVisibleAgain.ts").readText()
        assertFalse(Regex("""setInterval|setTimeout""").containsMatchIn(hook), "a timer in useVisibleAgain")
    }

    // ── the page with the real hook ─────────────────────────────────────────

    private val www = File("../www").absoluteFile
    private val work: File by lazy { Files.createTempDirectory("window-return").toFile() }
    private val results by lazy {
        val base = SetupScreenBehaviorTest.HARNESS.substringBefore("const scenarios = {")
        val stubLine = "  '../lib/useVisibleAgain': stub('stub-setup-visible-again.mjs'),\n"
        assertTrue(base.contains(stubLine), "the setup harness no longer stubs the hook this way")
        StatusScreenBehaviorTest.runHarness(www, work, base.replace(stubLine, "") + SCENARIOS)
    }

    @AfterAll
    fun cleanup() {
        work.deleteRecursively()
    }

    @Suppress("UNCHECKED_CAST")
    private fun row(label: String): Map<String, Any?> {
        val r = results["returns"] ?: error("no scenario: ${results.keys}")
        assertFalse(r.containsKey("error"), "scenario failed: ${r["error"]}")
        return r[label] as? Map<String, Any?> ?: error("no row $label: ${r.keys}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.calls() = this["calls"] as List<List<Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.keys() = this["keys"] as List<String>

    private fun Map<String, Any?>.count(name: String) = calls().count { it.first() == name }

    private fun Map<String, Any?>.reads(name: String) = ((this["reads"] as Map<*, *>)[name] as Number?)?.toInt() ?: 0

    private val sources = listOf("native", "visibility", "focus")

    @Test
    fun `on the resume and failure screens each source leaves once when the marker appeared, starting nothing`() {
        for (screen in listOf("resume", "runFailed")) {
            for (source in sources) {
                val r = row("$screen-$source")
                assertEquals(1, r.count("onComplete"), "$screen/$source: ${r.calls()}")
                assertEquals(0, r.count("startRun") + r.count("startSetup"), "$screen/$source: ${r.calls()}")
                assertFalse(
                    r.keys().any { it.startsWith("setup_finished_") || it.startsWith("setup_onboard_") },
                    r.keys().toString(),
                )
            }
        }
    }

    @Test
    fun `native webview_shown acts even when the document still says hidden, the DOM events then do not`() {
        assertEquals(1, row("hiddenNative").count("onComplete"), row("hiddenNative").calls().toString())
        assertEquals(0, row("hiddenDom").count("onComplete"), row("hiddenDom").calls().toString())
    }

    @Test
    fun `a burst of the three sources is one handler run, 300 ms later is another`() {
        val r = row("burst")
        assertEquals(1, (r["afterBurst"] as Number).toInt(), r.toString())
        assertEquals(1, (r["at299"] as Number).toInt(), r.toString())
        assertEquals(2, (r["at300"] as Number).toInt(), r.toString())
    }

    @Test
    fun `without the marker a return re-reads what the screen offers and stays - the button follows the new state`() {
        val r = row("rereadBusy")
        assertFalse("setup_reinstall_btn" in (r["before"] as List<*>), r.toString())
        assertTrue("setup_reinstall_btn" in r.keys() && "setup_resume_btn" in r.keys(), r.keys().toString())
        assertEquals(0, r.count("onComplete") + r.count("startRun"), r.calls().toString())
        val f = row("runFailedNoMarker")
        assertTrue("setup_failed_title" in f.keys() && "setup_reinstall_btn" in f.keys(), f.keys().toString())
        assertEquals(0, f.count("onComplete"), f.calls().toString())
    }

    @Test
    fun `on the wizard, preparing, installing and finished screens a return does nothing at all`() {
        for (screen in listOf("wizard", "toolSelect", "preparing", "installing", "finished")) {
            val r = row("other-$screen")
            assertEquals(0, r.count("onComplete"), "$screen: ${r.calls()}")

            fun before(k: String) = (r[k] as Number).toInt()
            assertEquals(
                before("startsBefore"),
                r.count("startRun") + r.count("startSetup"),
                "$screen started something: ${r.calls()}",
            )
            assertEquals(
                before("setupResultReadsBefore"),
                r.reads("getSetupResult"),
                "$screen re-read the setup result",
            )
            assertEquals(before("lastRunReadsBefore"), r.reads("getLastRun"), "$screen re-read the last run")
            assertEquals(r["keysBefore"], r.keys(), "$screen changed what it shows")
        }
    }

    private companion object {
        val SCENARIOS =
            """
            |// A fake DOM per page: listener tables on document and window, a visibility state
            |function dom() {
            |  const table = () => ({})
            |  const d = table(), w = table()
            |  const target = t => ({
            |    add: (type, f) => { (t[type] = t[type] || []).push(f) },
            |    remove: (type, f) => { t[type] = (t[type] || []).filter(x => x !== f) },
            |    fire: type => (t[type] || []).slice().forEach(f => f({ type })),
            |  })
            |  const dt = target(d), wt = target(w)
            |  globalThis.document = { visibilityState: 'visible', addEventListener: dt.add, removeEventListener: dt.remove }
            |  window.addEventListener = wt.add
            |  window.removeEventListener = wt.remove
            |  return {
            |    native: () => wt.fire('native:webview_shown'),
            |    visibility: () => dt.fire('visibilitychange'),
            |    focus: () => wt.fire('focus'),
            |    hide: () => { globalThis.document.visibilityState = 'hidden' },
            |    show: () => { globalThis.document.visibilityState = 'visible' },
            |  }
            |}
            |const DONE = { bootstrapInstalled: true, platformInstalled: true }
            |const snap = p => ({ keys: p.keys(), calls: p.native.calls.slice(), reads: Object.assign({}, p.native.reads) })
            |const fire = (p, f) => { f(); p.render() }
            |const failedPage = () => {
            |  const p = mount({ status: TF, runState: busy(4) })
            |  const end = ended('failed', { reason: 'NETWORK', stage: 4, exit: 1, warnings: 2, warn: ['hardlink-patch', 'tools:tmux'] })
            |  p.native.runState = end
            |  p.emit('run_progress', end)
            |  return p
            |}
            |const resumePage = extra => resumeWith(Object.assign({ present: true, stage: '3', interrupted: true, reason: 'INTERRUPTED' }, extra || {}))
            |const scenarios = {
            |  returns() {
            |    const out = {}
            |    for (const [screen, make] of [['resume', resumePage], ['runFailed', failedPage]]) {
            |      for (const source of ['native', 'visibility', 'focus']) {
            |        const D = dom(); const p = make()
            |        p.native.status = DONE
            |        fire(p, D[source])
            |        out[screen + '-' + source] = snap(p)
            |      }
            |    }
            |    let D = dom(); let p = resumePage()
            |    p.native.status = DONE
            |    D.hide(); fire(p, D.native)
            |    out.hiddenNative = snap(p)
            |    D = dom(); p = resumePage()
            |    p.native.status = DONE
            |    D.hide(); fire(p, D.visibility); fire(p, D.focus)
            |    out.hiddenDom = snap(p)
            |    // bursts: count the handler runs by the setup result reads (marker absent: the page stays)
            |    D = dom(); p = resumePage()
            |    const base = p.native.reads.getSetupResult
            |    fire(p, D.native); fire(p, D.visibility); fire(p, D.focus)
            |    const afterBurst = p.native.reads.getSetupResult - base
            |    p.tick(299); fire(p, D.focus)
            |    const at299 = p.native.reads.getSetupResult - base
            |    p.tick(1); fire(p, D.focus)
            |    out.burst = { afterBurst, at299, at300: p.native.reads.getSetupResult - base }
            |    // without the marker: a run elsewhere ended meanwhile (BUSY → INTERRUPTED), the screen follows
            |    D = dom(); p = resumeWith({ present: true, stage: '2', interrupted: false })
            |    const before = p.keys()
            |    p.native.setupResult = { present: true, stage: '2', interrupted: true, reason: 'INTERRUPTED', warn: [], managed: true }
            |    fire(p, D.focus)
            |    out.rereadBusy = Object.assign(snap(p), { before })
            |    D = dom(); p = failedPage()
            |    fire(p, D.native)
            |    out.runFailedNoMarker = snap(p)
            |    // every other screen, with the marker there: nothing at all
            |    const others = {
            |      wizard: () => mount({}),
            |      toolSelect: () => { const q = mount({}); q.click('OpenClaw'); return q },
            |      preparing: () => { const q = resumePage(); q.press('setup_resume_btn'); return q },
            |      installing: () => { const q = mount({}); q.click('OpenClaw'); q.press('setup_start'); q.emit('setup_progress', { progress: 0.4, message: 'x' }); return q },
            |      finished: () => {
            |        const q = mount({ status: TF, runState: busy(7) })
            |        const end = ended('done', { stage: 7, progress: 1, exit: 0, warn: ['hardlink-patch'], warnings: 1 })
            |        q.native.runState = end
            |        q.emit('run_progress', end)
            |        return q
            |      },
            |    }
            |    for (const [name, make] of Object.entries(others)) {
            |      const E = dom(); const q = make()
            |      q.native.status = DONE
            |      const keysBefore = q.keys()
            |      const startsBefore = q.count('startRun') + q.count('startSetup')
            |      const setupResultReadsBefore = q.native.reads.getSetupResult || 0
            |      const lastRunReadsBefore = q.native.reads.getLastRun || 0
            |      fire(q, E.native); q.tick(400); fire(q, E.visibility); q.tick(400); fire(q, E.focus)
            |      out['other-' + name] = Object.assign(snap(q), { keysBefore, startsBefore, setupResultReadsBefore, lastRunReadsBefore })
            |    }
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
