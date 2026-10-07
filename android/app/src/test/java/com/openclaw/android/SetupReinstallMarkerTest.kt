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
 * 「Reinstall the base system」 never replaces `usr` once the setup finished (the marker
 * `.post-setup-done`, `getSetupStatus().platformInstalled`), as the page BEHAVES on the harness of
 * [SetupScreenBehaviorTest]. The truth table of bootstrap installed × marker over the four entry
 * points — the button (each render), the confirmation, being shown again (`webview_shown`) and the
 * wizard's start (`startInstall`) — on the failure, resume, wizard and bootstrap-failure screens,
 * the marker appearing only after the question opened, a BUSY run, the unchanged behavior without
 * the marker, `startSetup` called exactly 0 or 1 times, a found marker leaving for the dashboard
 * (never the finished screen — that one only after a managed run's done end, MASTER D2), and no
 * timer added for any of it.
 *
 * Skipped (not failed) when node or `www/node_modules/esbuild` is missing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class SetupReinstallMarkerTest {
    private val www = File("../www").absoluteFile
    private val work: File by lazy { Files.createTempDirectory("reinstall-marker").toFile() }
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
    private fun row(
        scenario: String,
        label: String,
    ): Map<String, Any?> {
        val r = results[scenario] ?: error("no scenario $scenario: ${results.keys}")
        assertFalse(r.containsKey("error"), "scenario $scenario failed: ${r["error"]}")
        return r[label] as? Map<String, Any?> ?: error("no row $label in $scenario: ${r.keys}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.keys() = this["keys"] as List<String>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.calls() = this["calls"] as List<List<Any?>>

    private fun Map<String, Any?>.count(name: String) = calls().count { it.first() == name }

    private val cells = listOf(true, false).flatMap { b -> listOf(true, false).map { m -> b to m } }

    private fun label(
        b: Boolean,
        m: Boolean,
    ) = "b${if (b) 1 else 0}m${if (m) 1 else 0}"

    // ── the button, read at each render ─────────────────────────────────────

    @Test
    fun `the button is offered on the failure and resume screens exactly when the marker is absent`() {
        for (screen in listOf("runFailed", "resume")) {
            for ((b, m) in cells) {
                val r = row("button", "$screen-${label(b, m)}")
                assertEquals(!m, "setup_reinstall_btn" in r.keys(), "$screen ${label(b, m)}: ${r.keys()}")
            }
        }
    }

    @Test
    fun `a BUSY run never offers it, marker or not`() {
        for (m in listOf(true, false)) {
            for (screen in listOf("runFailedBusy", "resumeBusy")) {
                val k = row("button", "$screen-m${if (m) 1 else 0}").keys()
                assertFalse("setup_reinstall_btn" in k, "$screen m=$m: $k")
            }
        }
    }

    // ── the confirmation: the marker read at that moment ────────────────────

    /**
     * MASTER D2: a marker found on these paths came from a terminal run of post-setup.sh (which ran
     * the onboarding itself and may have warned): the page leaves for the dashboard, exactly once,
     * and shows no finished screen and nothing of the old outcome (D3).
     */
    private fun assertLeftWithoutFinished(
        r: Map<String, Any?>,
        what: String,
    ) {
        assertEquals(1, r.count("onComplete"), "$what: ${r.calls()}")
        assertTrue(
            r.keys().none {
                it.startsWith("setup_finished_") ||
                    it.startsWith("setup_warn_") ||
                    it.startsWith("setup_onboard_")
            },
            "$what: ${r.keys()}",
        )
    }

    @Test
    fun `the marker appearing after the question opened - confirming starts nothing and leaves for the dashboard`() {
        for ((b, m) in cells) {
            val r = row("confirm", label(b, m))
            if (m) {
                assertLeftWithoutFinished(r, label(b, m))
                assertFalse("setup_choose_platform" in r.keys(), "${label(b, m)}: ${r.keys()}")
                assertFalse("setup_reinstall_title" in r.keys(), "the question stayed open: ${r.keys()}")
            } else {
                assertTrue("setup_choose_platform" in r.keys(), "${label(b, m)}: ${r.keys()}")
                assertEquals(0, r.count("onComplete"), "${label(b, m)}: ${r.calls()}")
            }
            assertEquals(0, r.count("startSetup") + r.count("startRun"), "${label(b, m)}: ${r.calls()}")
        }
    }

    // ── shown again ─────────────────────────────────────────────────────────

    @Test
    fun `shown again on the failure screen - with the marker the page leaves, without it stays`() {
        for ((b, m) in cells) {
            val r = row("shown", "runFailed-${label(b, m)}")
            if (m) {
                assertLeftWithoutFinished(r, label(b, m))
                assertFalse("setup_reinstall_btn" in r.keys() || "setup_reinstall_title" in r.keys())
            } else {
                assertEquals(0, r.count("onComplete"), "${label(b, m)}: ${r.calls()}")
                assertTrue(
                    "setup_failed_title" in r.keys() && "setup_reinstall_btn" in r.keys(),
                    "${label(b, m)}: ${r.keys()}",
                )
            }
            assertEquals(0, r.count("startSetup") + r.count("startRun"), r.calls().toString())
        }
    }

    @Test
    fun `shown again with the question open and the marker there - the question is gone`() {
        val r = row("shown", "questionOpen")
        assertFalse("setup_reinstall_title" in r.keys(), r.keys().toString())
        assertLeftWithoutFinished(r, "questionOpen")
    }

    @Test
    fun `shown again on the resume screen with the marker leaves the page, without it stays`() {
        for ((b, m) in cells) {
            val r = row("shown", "resume-${label(b, m)}")
            assertEquals(if (m) 1 else 0, r.count("onComplete"), "${label(b, m)}: ${r.calls()}")
            assertEquals(0, r.count("startSetup"))
        }
    }

    // ── the wizard's start and the bootstrap-failure retry ──────────────────

    @Test
    fun `the wizard's start runs the bootstrap unless bootstrap and marker are both there`() {
        for ((b, m) in cells) {
            val r = row("start", "wizard-${label(b, m)}")
            val expected = if (b && m) 0 else 1
            assertEquals(expected, r.count("startSetup"), "${label(b, m)}: ${r.calls()}")
            if (expected == 0) {
                assertLeftWithoutFinished(r, "wizard ${label(b, m)}")
            } else {
                assertTrue(
                    "setup_setting_up" in r.keys() || "setup_preparing" in r.keys(),
                    "${label(b, m)}: ${r.keys()}",
                )
            }
        }
    }

    @Test
    fun `the bootstrap-failure retry follows the same rule - no usr but the marker still downloads`() {
        for ((b, m) in cells) {
            val r = row("start", "retry-${label(b, m)}")
            // one start before the failure, then the retry
            assertEquals(if (b && m) 1 else 2, r.count("startSetup"), "${label(b, m)}: ${r.calls()}")
            if (b && m) assertLeftWithoutFinished(r, "retry ${label(b, m)}")
        }
    }

    // ── without the marker nothing changed ──────────────────────────────────

    @Test
    fun `without the marker the reinstall runs as before - one bootstrap, then one SETUP run`() {
        val r = row("noMarker", "flow")
        assertEquals(1, r.count("startSetup"), r.calls().toString())
        assertEquals(listOf(listOf<Any?>("startRun", "SETUP", false)), r.calls().filter { it.first() == "startRun" })
    }

    // ── the finished screen: only a managed run that ended done on this page ─

    @Test
    fun `the retry and resume buttons tapped after the marker appeared leave once and start nothing`() {
        for (label in listOf("retryButton", "resumeButton", "resumeShown")) {
            val r = row("leaveChecks", label)
            assertLeftWithoutFinished(r, label)
            assertEquals(0, r.count("startRun") + r.count("startSetup"), "$label: ${r.calls()}")
        }
    }

    @Test
    fun `leaving clears the failed outcome - its reason and its warnings are not on the page afterwards`() {
        // The fixture's failed outcome is NETWORK with warn hardlink-patch and tools:tmux
        for (label in listOf("retryButton")) {
            val k = row("leaveChecks", label).keys()
            assertFalse("setup_reason_network" in k, "$label: the old outcome is still shown: $k")
        }
        for ((b, m) in cells.filter { it.second }) {
            for (r in listOf(row("confirm", label(b, m)), row("shown", "runFailed-${label(b, m)}"))) {
                assertFalse("setup_reason_network" in r.keys(), "the old outcome is still shown: ${r.keys()}")
            }
        }
    }

    @Test
    fun `a managed run's done end still shows the finished screen with its warnings, the marker there or not`() {
        val k = row("leaveChecks", "managedWarn").keys()
        assertTrue("setup_finished_title_warn" in k && "setup_warn_incomplete" in k, k.toString())
        assertTrue("setup_warn_tool" in k && "setup_warn_other" in k, k.toString())
        assertTrue("setup_onboard_btn" in k && "setup_onboard_later" in k, k.toString())
        assertEquals(0, row("leaveChecks", "managedWarn").count("onComplete"))
        // Shown again on the finished screen: it stays (only resume and failure screens leave)
        val shown = row("leaveChecks", "managedWarnShown")
        assertEquals(0, shown.count("onComplete"), shown.calls().toString())
        assertTrue("setup_finished_title_warn" in shown.keys(), shown.keys().toString())
    }

    @Test
    fun `a page made again after a managed done run restores the finished screen, warnings included`() {
        val warn = row("leaveChecks", "restoredWarn").keys()
        assertTrue("setup_finished_title_warn" in warn && "setup_warn_incomplete" in warn, warn.toString())
        val plain = row("leaveChecks", "restoredPlain").keys()
        assertTrue("setup_finished_title" in plain && "setup_finished_desc" in plain, plain.toString())
        assertFalse(plain.any { it.startsWith("setup_warn_") }, plain.toString())
        for (label in listOf("restoredWarn", "restoredPlain")) {
            assertEquals(0, row("leaveChecks", label).count("onComplete"), label)
            assertEquals(0, row("leaveChecks", label).count("startRun"), label)
        }
    }

    @Test
    fun `the finished screen comes only from a managed run's done end, never from a marker found`() {
        assertLeftWithoutFinished(row("finishedOnlyManaged", "marker"), "marker")
        val managed = row("finishedOnlyManaged", "managed")
        val k = managed.keys()
        assertTrue("setup_finished_title" in k && "setup_onboard_btn" in k, k.toString())
        assertEquals(0, managed.count("onComplete"), managed.calls().toString())
        assertEquals(
            listOf(listOf<Any?>("startRun", "SETUP", false)),
            managed.calls().filter { it.first() == "startRun" },
        )
    }

    // ── no polling ──────────────────────────────────────────────────────────

    @Test
    fun `no timer reads the marker - the one interval is the preparing poll, no timeout at all`() {
        val src = File("../www/src/screens/Setup.tsx").readText()
        assertEquals(1, Regex("""setInterval\(""").findAll(src).count(), "a new interval in Setup.tsx")
        assertEquals(0, Regex("""setTimeout\(""").findAll(src).count(), "a timeout in Setup.tsx")
        val interval = src.substringAfter("window.setInterval(").substringBefore("}, PRECHECK_POLL_MS)")
        assertFalse(interval.contains("setupMarkerPresent") || interval.contains("installComplete"), interval)
    }

    private companion object {
        val SCENARIOS =
            """
            |const statusOf = (b, m) => ({ bootstrapInstalled: b, platformInstalled: m })
            |const L = (b, m) => 'b' + (b ? 1 : 0) + 'm' + (m ? 1 : 0)
            |const CELLS = [[true, true], [true, false], [false, true], [false, false]]
            |const snap = p => ({ keys: p.keys(), calls: p.native.calls.slice() })
            |// A SETUP run that ended failed (NETWORK) on a live page, bootstrap there, no marker yet. It
            |// carries warn data so a leftover outcome would show if it leaked onto a screen (MASTER D3)
            |const failedPage = (reason) => {
            |  const p = mount({ status: TF, runState: busy(4) })
            |  const end = ended('failed', { reason: reason || 'NETWORK', stage: 4, exit: 1, warnings: 2, warn: ['hardlink-patch', 'tools:tmux'] })
            |  p.native.runState = end
            |  p.emit('run_progress', end)
            |  return p
            |}
            |const resumePage = (extra) => resumeWith(Object.assign({ present: true, stage: '3', interrupted: true, reason: 'INTERRUPTED' }, extra || {}))
            |const scenarios = {
            |  button() {
            |    const out = {}
            |    for (const [b, m] of CELLS) {
            |      let p = failedPage(); p.native.status = statusOf(b, m); p.render(); out['runFailed-' + L(b, m)] = snap(p)
            |      p = resumePage(); p.native.status = statusOf(b, m); p.render(); out['resume-' + L(b, m)] = snap(p)
            |    }
            |    for (const m of [true, false]) {
            |      let p = failedPage('BUSY'); p.native.status = statusOf(true, m); p.render(); out['runFailedBusy-m' + (m ? 1 : 0)] = snap(p)
            |      p = resumeWith({ present: true, stage: '2', interrupted: false }); p.native.status = statusOf(true, m); p.render(); out['resumeBusy-m' + (m ? 1 : 0)] = snap(p)
            |    }
            |    return out
            |  },
            |  confirm() {
            |    const out = {}
            |    for (const [b, m] of CELLS) {
            |      const p = failedPage()
            |      p.press('setup_reinstall_btn')
            |      // the marker (or not) appears only now, with the question open
            |      p.native.status = statusOf(b, m)
            |      // press reads the card as it is still drawn (no render since the change): the user taps
            |      // the confirmation that was on screen when the marker appeared
            |      p.press('setup_reinstall_confirm')
            |      out[L(b, m)] = snap(p)
            |    }
            |    return out
            |  },
            |  shown() {
            |    const out = {}
            |    for (const [b, m] of CELLS) {
            |      let p = failedPage(); p.native.status = statusOf(b, m); p.emit('webview_shown', {}); out['runFailed-' + L(b, m)] = snap(p)
            |      p = resumePage(); p.native.status = statusOf(b, m); p.emit('webview_shown', {}); out['resume-' + L(b, m)] = snap(p)
            |    }
            |    const q = failedPage()
            |    q.press('setup_reinstall_btn')
            |    q.native.status = statusOf(true, true)
            |    q.emit('webview_shown', {})
            |    out.questionOpen = snap(q)
            |    return out
            |  },
            |  start() {
            |    const out = {}
            |    for (const [b, m] of CELLS) {
            |      // the wizard reached through the reinstall (no marker then), the marker set while in it
            |      let p = failedPage()
            |      p.press('setup_reinstall_btn'); p.press('setup_reinstall_confirm')
            |      p.native.status = statusOf(b, m)
            |      p.click('OpenClaw'); p.press('setup_start')
            |      out['wizard-' + L(b, m)] = snap(p)
            |      // a first install whose bootstrap failed, then the state changes and Retry is pressed
            |      p = mount({})
            |      p.click('OpenClaw'); p.press('setup_start')
            |      p.emit('setup_progress', { error: 'x', errorKind: 'NETWORK' })
            |      p.native.status = statusOf(b, m)
            |      p.press('setup_retry')
            |      out['retry-' + L(b, m)] = snap(p)
            |    }
            |    return out
            |  },
            |  noMarker() {
            |    const p = failedPage()
            |    p.press('setup_reinstall_btn'); p.press('setup_reinstall_confirm')
            |    p.native.runState = IDLE
            |    p.click('OpenClaw'); p.press('setup_start')
            |    p.emit('setup_progress', { progress: 1 })
            |    p.emit('setup_progress', { progress: 1 })
            |    return { flow: snap(p) }
            |  },
            |  leaveChecks() {
            |    const out = {}
            |    const DONE = statusOf(true, true)
            |    // the failure screen's primary button (retry) with the marker appearing before the tap
            |    let p = failedPage()
            |    p.native.status = DONE
            |    p.press('setup_retry')
            |    out.retryButton = snap(p)
            |    // the resume button likewise
            |    p = resumePage()
            |    p.native.status = DONE
            |    p.press('setup_resume_btn')
            |    out.resumeButton = snap(p)
            |    // shown again on the resume screen with the marker
            |    p = resumePage()
            |    p.native.status = DONE
            |    p.emit('webview_shown', {})
            |    out.resumeShown = snap(p)
            |    // a managed run that ended done (with warnings) while the marker is there: the finished screen,
            |    // and being shown again later does not leave it
            |    p = failedPage()
            |    p.press('setup_retry')
            |    p.native.status = DONE
            |    const done = ended('done', { stage: 7, progress: 1, exit: 0, warn: ['hardlink-patch', 'tools:tmux', 'checkOnStart'], warnings: 3, detail: '' })
            |    p.native.runState = done
            |    p.emit('run_progress', done)
            |    out.managedWarn = snap(p)
            |    p.emit('webview_shown', {})
            |    out.managedWarnShown = snap(p)
            |    // a page made again after such a run (initialPhase): the same finished screen with its warnings
            |    out.restoredWarn = snap(mount({ status: DONE, runState: done }))
            |    out.restoredPlain = snap(mount({ status: DONE, runState: ended('done', { stage: 7, progress: 1, exit: 0, warn: [], warnings: 0 }) }))
            |    return out
            |  },
            |  finishedOnlyManaged() {
            |    const out = {}
            |    // The marker found on a failure screen: the page leaves, no finished screen
            |    const p = failedPage()
            |    p.press('setup_reinstall_btn')
            |    p.native.status = statusOf(true, true)
            |    p.emit('webview_shown', {})
            |    out.marker = snap(p)
            |    // A managed run started on this page that ends done: the finished screen, with its data
            |    const q = failedPage()
            |    q.press('setup_retry')
            |    q.native.status = statusOf(true, true)
            |    const end = ended('done', { stage: 7, progress: 1, exit: 0, warn: [], warnings: 0 })
            |    q.native.runState = end
            |    q.emit('run_progress', end)
            |    out.managed = snap(q)
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
