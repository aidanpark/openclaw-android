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
 * The first-install page (`Setup.tsx`) as it BEHAVES, run in node like [StatusScreenBehaviorTest]
 * (the same React hook stand-in, `t()` that renders `{key}` — or `{key}` plus the real text of a
 * locale — and fake timers), over the real `lib/bridge.ts`, `lib/setupRoute.ts` and
 * `lib/setupRun.ts` and a fake `window.OpenClaw`: the bootstrap's end continues into ONE managed
 * SETUP run, an older script keeps the terminal flow, the resume screen's variants, the failure
 * screens per reason, the progress card (n/7, stage name, keep-open notice), the cancel's
 * confirmation, the finished screen's warnings and the onboarding button (typed, no Enter), the
 * 60 s preparing limit, and what a recreated page restores.
 *
 * Skipped (not failed) when node or `www/node_modules/esbuild` is missing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class SetupScreenBehaviorTest {
    private val www = File("../www").absoluteFile
    private val work: File by lazy { Files.createTempDirectory("setup-screen").toFile() }
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
    private fun list(
        map: Map<String, Any?>,
        fact: String,
    ): List<Any?> = map[fact] as List<Any?>

    @Suppress("UNCHECKED_CAST")
    private fun keys(
        name: String,
        fact: String,
    ): List<String> = result(name)[fact] as List<String>

    @Suppress("UNCHECKED_CAST")
    private fun keysOf(map: Map<String, Any?>): List<String> = map["keys"] as List<String>

    private fun count(
        calls: List<Any?>,
        name: String,
    ) = calls.count { (it as List<*>).first() == name }

    private fun num(value: Any?) = (value as Number).toInt()

    // ── the bootstrap continues into one SETUP run ──────────────────────────

    @Test
    fun `the wizard saves the tools and starts the bootstrap, showing both parts and the keep-open notice`() {
        assertTrue("setup_choose_platform" in keys("freshFlow", "k0"))
        assertTrue("setup_optional_tools" in keys("freshFlow", "k1"))
        val calls = list(result("freshFlow"), "calls")
        assertEquals(1, count(calls, "saveToolSelections"))
        assertEquals(1, count(calls, "startSetup"))
        val k2 = keys("freshFlow", "k2")
        listOf(
            "setup_setting_up",
            "setup_part_bootstrap",
            "setup_part_runtime",
            "setup_part_waiting",
            "setup_keep_open",
        ).forEach {
            assertTrue(it in k2, "$it missing while the bootstrap runs: $k2")
        }
    }

    @Test
    fun `the bootstrap's end starts SETUP exactly once, also when the end repeats and the page rerenders`() {
        val r = result("freshFlow")
        assertEquals(1, num(r["startsAfterEnd"]))
        assertEquals(1, num(r["startsAfterRepeat"]))
        assertEquals(1, num(r["startsAfterLateEnd"]), "a late bootstrap end started a second run")
        val start = list(r, "calls").first { (it as List<*>).first() == "startRun" }
        assertEquals(listOf("startRun", "SETUP", false), start)
        val k4 = keys("freshFlow", "k4")
        assertTrue("setup_run_prechecking" in k4, k4.toString())
        assertTrue("status_preparing" in k4, k4.toString())
    }

    @Test
    fun `the progress card shows step n of 7, the stage's name, the keep-open notice and a cancel button`() {
        val k5 = keys("freshFlow", "k5")
        listOf(
            "status_stage",
            "setup_stage_3",
            "setup_keep_open",
            "status_cancel",
        ).forEach { assertTrue(it in k5, "$it: $k5") }
        assertFalse("setup_run_prechecking" in k5, k5.toString())
        val en = result("freshFlow")["text5"] as String
        assertTrue(en.contains("Step 3/7"), en)
        assertTrue(en.contains("Node.js"), en)
        assertTrue(en.contains("Keep the app open until the installation finishes."), en)
    }

    @Test
    fun `cancel asks first - keep installing sends nothing, confirming sends one cancelRun`() {
        val r = result("freshFlow")
        val k6 = keys("freshFlow", "k6")
        // (the card's two buttons are pressed below: setup_cancel_keep, setup_cancel_confirm)
        listOf("setup_cancel_title", "setup_cancel_body").forEach { assertTrue(it in k6, "$it: $k6") }
        assertFalse("status_cancel" in k6, "the cancel button stays next to its confirmation: $k6")
        assertEquals(0, num(r["cancelAfterAsk"]))
        assertFalse("setup_cancel_title" in keys("freshFlow", "k7"))
        assertEquals(0, num(r["cancelAfterKeep"]))
        assertEquals(1, num(r["cancelAfterConfirm"]))
    }

    @Test
    fun `a cancelled run says so and offers to continue, which starts SETUP again`() {
        val k8 = keys("freshFlow", "k8")
        listOf("setup_run_cancelled_title", "setup_reason_cancelled", "setup_resume_btn").forEach {
            assertTrue(it in k8, "$it: $k8")
        }
        assertFalse("setup_retry" in k8)
        assertEquals(2, num(result("freshFlow")["startsAfterResume"]))
    }

    @Test
    fun `an older script keeps the terminal flow - one part only, no keep-open notice, the old done screen`() {
        val installing = keys("terminalFlow", "installing")
        assertFalse("setup_part_bootstrap" in installing, installing.toString())
        assertFalse("setup_keep_open" in installing, installing.toString())
        val done = keys("terminalFlow", "done")
        assertTrue("setup_done_title" in done && "setup_open_terminal" in done, done.toString())
        val calls = list(result("terminalFlow"), "calls")
        assertEquals(0, count(calls, "startRun"))
        assertEquals(1, count(calls, "showTerminal"))
        assertEquals(1, count(calls, "onComplete"))
    }

    @Test
    fun `with the marker already there at the bootstrap's end no SETUP is started`() {
        assertEquals(0, count(list(result("markerAtBootstrapEnd"), "calls"), "startRun"))
    }

    // ── the resume screen ───────────────────────────────────────────────────

    private fun variant(name: String) = sub("resumeVariants", name)

    @Test
    fun `resume after an interrupted run - the headline, the reason, the stage it stopped at, the button, no start`() {
        val k = keysOf(variant("interrupted"))
        listOf(
            "setup_resume_title",
            "setup_resume_desc",
            "setup_resume_interrupted",
            "setup_reason_interrupted",
            "setup_stopped_at",
            "setup_resume_btn",
        ).forEach { assertTrue(it in k, "$it: $k") }
        assertFalse("setup_choose_platform" in k)
        assertEquals(0, count(list(variant("interrupted"), "calls"), "startRun"))
    }

    @Test
    fun `resume after the user's cancel says cancelled, not interrupted`() {
        val k = keysOf(variant("cancelled"))
        assertTrue("setup_run_cancelled_title" in k && "setup_reason_cancelled" in k, k.toString())
        assertFalse("setup_resume_interrupted" in k || "setup_reason_interrupted" in k, k.toString())
    }

    @Test
    fun `resume after a failure shows its reason and the script output kept with the record`() {
        val v = variant("lastFailed")
        val k = keysOf(v)
        listOf("setup_resume_last_failed", "setup_reason_network", "setup_stopped_at", "status_output").forEach {
            assertTrue(it in k, "$it: $k")
        }
        assertEquals(listOf("Could not verify the Termux package list."), list(v, "boxes"))
    }

    @Test
    fun `resume without a result file shows only the button`() {
        val k = keysOf(variant("noResult"))
        assertTrue("setup_resume_btn" in k)
        assertTrue(
            k.none {
                it.startsWith("setup_reason_") ||
                    it == "setup_resume_last_failed" ||
                    it == "setup_resume_interrupted"
            },
            k.toString(),
        )
    }

    @Test
    fun `resume while a run goes on elsewhere says busy, without a headline or a stage`() {
        val k = keysOf(variant("busy"))
        assertTrue("setup_reason_busy" in k, k.toString())
        assertFalse(
            "setup_resume_last_failed" in k || "setup_resume_interrupted" in k || "setup_stopped_at" in k,
            k.toString(),
        )
    }

    @Test
    fun `resume after no space names the sizes`() {
        val v = variant("noSpace")
        assertTrue("setup_reason_no_space_mb" in keysOf(v))
        val en = v["en"] as String
        assertTrue(en.contains("2000 MB is needed and 500 MB is available"), en)
    }

    @Test
    fun `resume with a script the app cannot run keeps the terminal button (the old flow)`() {
        val k = keysOf(variant("managedFalse"))
        assertTrue("setup_open_terminal" in k && "setup_done_desc" in k, k.toString())
        assertFalse("setup_resume_btn" in k || k.any { it.startsWith("setup_reason_") }, k.toString())
        // The terminal continues the install; the page stays (no onComplete while the marker is missing)
        val calls = list(result("resumeManagedFalsePress"), "calls")
        assertEquals(listOf(listOf("showTerminal")), calls)
        assertTrue("setup_open_terminal" in keys("resumeManagedFalsePress", "keys"))
    }

    @Test
    fun `reinstalling the base system asks first, then opens the first-install wizard and starts only from it`() {
        assertTrue("setup_reinstall_btn" in keys("reinstall", "resume"))
        val confirm = keys("reinstall", "confirm")
        assertTrue("setup_reinstall_title" in confirm && "setup_reinstall_body" in confirm, confirm.toString())
        val kept = keys("reinstall", "kept")
        assertTrue("setup_resume_btn" in kept && "setup_reinstall_title" !in kept, kept.toString())
        for (fact in listOf("wizard", "wizardAfterShown")) {
            val k = keys("reinstall", fact)
            assertTrue("setup_choose_platform" in k && "setup_resume_title" !in k, "$fact: $k")
        }
        assertTrue(list(result("reinstall"), "callsBeforeStart").isEmpty())
        val calls = list(result("reinstall"), "calls")
        assertEquals(1, count(calls, "startSetup"), calls.toString())
        assertEquals(0, count(calls, "startRun"), calls.toString())
    }

    @Test
    fun `the failure screen offers the reinstall, a run going on elsewhere does not`() {
        assertTrue("setup_reinstall_btn" in keys("reinstall", "failed"))
        val busy = keys("reinstall", "busy")
        assertTrue("setup_reason_busy" in busy && "setup_reinstall_btn" !in busy, busy.toString())
    }

    @Test
    fun `with the marker there the reinstall is neither offered nor started, and the page leaves for the dashboard`() {
        val r = result("reinstallMarker")

        @Suppress("UNCHECKED_CAST")
        fun fact(label: String) = r[label] as Map<String, Any?>
        for (label in listOf("atConfirm", "shown", "wizard")) {
            val k = keysOf(fact(label))
            // The terminal finished the install: no finished screen, no warning of the old outcome (MASTER D2/D3)
            assertTrue(
                k.none {
                    it.startsWith("setup_finished_") ||
                        it.startsWith("setup_warn_") ||
                        it.startsWith("setup_onboard_")
                },
                "$label: $k",
            )
            assertFalse("setup_choose_platform" in k || "setup_reinstall_title" in k, "$label: $k")
            val calls = list(fact(label), "calls")
            assertEquals(0, count(calls, "startSetup") + count(calls, "startRun"), "$label: $calls")
            assertEquals(1, count(calls, "onComplete"), "$label: $calls")
        }
        assertFalse("setup_reinstall_btn" in keysOf(fact("hidden")), keysOf(fact("hidden")).toString())
    }

    private fun backFrom(label: String) = sub("resumeTerminalThenBack", label)

    @Test
    fun `back from the terminal before the install finished, the resume screen stays and nothing starts`() {
        val r = backFrom("notYet")
        val calls = list(r, "calls")
        assertEquals(1, count(calls, "showTerminal"), calls.toString())
        assertEquals(0, count(calls, "onComplete"), calls.toString())
        assertEquals(0, count(calls, "startRun"), calls.toString())
        val k = keysOf(r)
        assertTrue("setup_resume_title" in k && "setup_open_terminal" in k, k.toString())
    }

    @Test
    fun `back from the terminal after it finished the install, the page leaves to the dashboard`() {
        val calls = list(backFrom("finished"), "calls")
        assertEquals(listOf(listOf("showTerminal"), listOf("onComplete")), calls)
    }

    @Test
    fun `back from the terminal after the script became capable, the managed resume screen is offered`() {
        val r = backFrom("capable")
        val calls = list(r, "calls")
        assertEquals(0, count(calls, "onComplete") + count(calls, "startRun"), calls.toString())
        val k = keysOf(r)
        assertTrue("setup_resume_btn" in k && "setup_open_terminal" !in k, k.toString())
    }

    @Test
    fun `a page made again after the terminal finished the install leaves instead of offering a first install`() {
        val r = backFrom("remount")
        assertEquals(listOf(listOf("onComplete")), list(r, "calls"))
    }

    /**
     * MainActivity refreshes the script in the background and, when the new copy is capable, types
     * nothing and brings the page back (`webview_shown`). A page created before the refresh must then
     * offer the managed run, not a terminal where nothing runs (its session already exists, so
     * `showTerminal` types nothing either).
     */
    @Test
    fun `the resume screen re-reads managed when the page is shown again after the script became capable`() {
        val r = result("resumeManagedFlips")
        assertTrue("setup_open_terminal" in keys("resumeManagedFlips", "before"), r.toString())
        assertTrue("setup_resume_btn" in keys("resumeManagedFlips", "afterFlip"), r.toString())
        assertFalse("setup_open_terminal" in keys("resumeManagedFlips", "afterFlip"), r.toString())
        assertEquals(0, count(list(r, "calls"), "startRun"), "being shown again starts nothing")
    }

    @Test
    fun `the resume terminal button re-reads managed when pressed and starts the managed run once`() {
        val r = result("resumeManagedFlipsPress")
        val calls = list(r, "calls")
        assertEquals(listOf(listOf("startRun", "SETUP", false)), calls.filter { (it as List<*>).first() == "startRun" })
        assertEquals(0, count(calls, "showTerminal"), calls.toString())
        assertEquals(0, count(calls, "onComplete"), calls.toString())
        assertTrue("setup_run_prechecking" in keys("resumeManagedFlipsPress", "keys"), r.toString())
    }

    @Test
    fun `resume with a finished run and no reason shows no card`() {
        val k = keysOf(variant("unknownExit0"))
        assertTrue(k.none { it.startsWith("setup_reason_") }, k.toString())
        assertTrue("setup_resume_btn" in k)
    }

    @Test
    fun `continue pressed twice starts once`() {
        assertEquals(1, num(result("resumeStartOnce")["starts"]))
        assertTrue("setup_run_prechecking" in keys("resumeStartOnce", "keys"))
    }

    @Test
    fun `continue when the marker appeared meanwhile starts nothing and leaves for the dashboard`() {
        // The terminal finished the install (and ran the onboarding): no finished screen (MASTER D2)
        assertEquals(0, num(result("resumeMarkerMeanwhile")["starts"]))
        assertEquals(1, num(result("resumeMarkerMeanwhile")["completes"]))
        val k = keys("resumeMarkerMeanwhile", "keys")
        assertTrue(k.none { it.startsWith("setup_finished_") || it.startsWith("setup_onboard_") }, k.toString())
    }

    // ── failure screens ─────────────────────────────────────────────────────

    private fun failure(label: String) = sub("failures", label)

    @Test
    fun `each reason gets its own line, the stage and the script output`() {
        val expected =
            mapOf(
                "NO_SPACE_MB" to "setup_reason_no_space_mb",
                "NO_SPACE" to "setup_reason_no_space",
                "NETWORK" to "setup_reason_network",
                "VERIFY_FAILED" to "setup_reason_verify_failed",
                "INSTALL_FAILED" to "setup_reason_install_failed",
                "ENV" to "setup_reason_env",
                "OPENCLAW_INCOMPLETE" to "setup_reason_openclaw_incomplete",
                "INTERRUPTED" to "setup_reason_interrupted",
                "BUSY" to "setup_reason_busy",
                "UNKNOWN" to "setup_reason_unknown",
                "LATER" to "setup_reason_unknown",
            )
        expected.forEach { (label, key) ->
            val f = failure(label)
            val k = keysOf(f)
            assertTrue(key in k, "$label: $k")
            assertEquals(1, k.count { it.startsWith("setup_reason_") }, "$label: one reason line: $k")
            assertTrue("setup_failed_title" in k && "setup_stopped_at" in k && "status_output" in k, "$label: $k")
            assertEquals(listOf("first line\nsecond line"), list(f, "boxes"), label)
            val button = if (label == "INTERRUPTED") "setup_resume_btn" else "setup_retry"
            assertTrue(button in k, "$label: $k")
        }
    }

    @Test
    fun `only NO_SPACE says that nothing was installed, in every locale`() {
        val claims = mapOf("en" to "Nothing was installed", "ko" to "아무것도 설치하지 않았습니다", "zh" to "未安装任何内容")
        for (label in listOf(
            "NO_SPACE_MB",
            "NO_SPACE",
            "NETWORK",
            "VERIFY_FAILED",
            "INSTALL_FAILED",
            "ENV",
            "OPENCLAW_INCOMPLETE",
            "INTERRUPTED",
            "BUSY",
            "UNKNOWN",
            "LATER",
        )) {
            claims.forEach { (lang, claim) ->
                val text = failure(label)[lang] as String
                val says = text.contains(claim, ignoreCase = true)
                assertEquals(label.startsWith("NO_SPACE"), says, "$label/$lang: $text")
            }
        }
        val en = failure("NO_SPACE_MB")["en"] as String
        assertTrue(en.contains("2000 MB is needed and 321 MB is available"), en)
    }

    @Test
    fun `OPENCLAW_INCOMPLETE shows its own text in every locale, the output box and a retry (not a resume)`() {
        val f = failure("OPENCLAW_INCOMPLETE")
        assertTrue((f["en"] as String).contains("OpenClaw is not completely installed"), f["en"].toString())
        assertTrue((f["ko"] as String).contains("OpenClaw 설치가 불완전합니다"), f["ko"].toString())
        assertTrue((f["zh"] as String).contains("OpenClaw 未完整安装"), f["zh"].toString())
        val k = keysOf(f)
        assertTrue("setup_retry" in k && "setup_resume_btn" !in k, k.toString())
        assertTrue("setup_reinstall_btn" in k, "the reinstall is offered as for the other failures: $k")
        assertEquals(listOf("first line\nsecond line"), list(f, "boxes"))
    }

    @Test
    fun `VERIFY_FAILED says the install was stopped for safety`() {
        assertTrue((failure("VERIFY_FAILED")["en"] as String).contains("stopped for safety"))
        assertTrue((failure("VERIFY_FAILED")["ko"] as String).contains("안전을 위해 설치를 중단했습니다"))
        assertTrue((failure("VERIFY_FAILED")["zh"] as String).contains("为安全起见已中止安装"))
    }

    @Test
    fun `a refusal NOT_INSTALLED of the page's own start falls back to the terminal flow`() {
        val k = keys("refusals", "notInstalled")
        assertTrue("setup_done_title" in k && "setup_open_terminal" in k, k.toString())
        assertFalse("setup_failed_title" in k)
    }

    @Test
    fun `a refusal without a kind after the page's own start says the install could not start`() {
        val k = keys("refusals", "noKind")
        assertTrue("setup_refused_unknown" in k, k.toString())
        assertFalse("setup_stopped_at" in k || "setup_reason_unknown" in k, k.toString())
    }

    @Test
    fun `a BUSY refusal while native still runs keeps the running card, otherwise it is the busy failure`() {
        val keep = keys("refusals", "busyKeep")
        assertTrue("status_stage" in keep && "setup_failed_title" !in keep, keep.toString())
        val end = keys("refusals", "busyEnd")
        assertTrue("setup_reason_busy" in end && "setup_failed_title" in end, end.toString())
    }

    @Test
    fun `an update's run event is not this page's`() {
        assertEquals(keys("refusals", "before"), keys("refusals", "afterUpdate"))
    }

    // ── finished ────────────────────────────────────────────────────────────

    @Test
    fun `finished with skipped tools names each tool, then the other parts, then the script's sentence`() {
        val f = sub("finished", "tools")
        val k = keysOf(f)
        assertEquals(2, k.count { it == "setup_warn_tool" }, k.toString())
        listOf(
            "setup_finished_title",
            "setup_warn_other",
            "status_output",
            "setup_onboard_btn",
            "setup_onboard_later",
        ).forEach {
            assertTrue(it in k, "$it: $k")
        }
        assertEquals(listOf("tmux could not be installed (non-critical)"), list(f, "boxes"))
        val en = f["en"] as String
        assertTrue(en.contains("The optional tool Claude Code could not be installed"), en)
        assertTrue(en.contains("The optional tool tmux could not be installed"), en)
    }

    @Test
    fun `finished with only non-essential parts skipped shows one generic line`() {
        val k = keysOf(sub("finished", "generic"))
        assertTrue("setup_warn_other" in k)
        assertFalse("setup_warn_tool" in k)
    }

    @Test
    fun `finished with hardlink-patch says OpenClaw may be incomplete, first, and is not a plain success`() {
        val k = keysOf(sub("finished", "incomplete"))
        assertTrue("setup_finished_title_warn" in k && "setup_warn_incomplete" in k, k.toString())
        assertFalse("setup_finished_title" in k || "setup_finished_desc" in k, k.toString())
        // checkOnStart still gets the generic line; hardlink-patch is not said twice
        assertTrue(k.indexOf("setup_warn_incomplete") < k.indexOf("setup_warn_other"), k.toString())
        assertTrue("setup_onboard_btn" in k, k.toString())
        val only = keysOf(sub("finished", "incompleteOnly"))
        assertTrue("setup_warn_incomplete" in only && "setup_warn_other" !in only, only.toString())
        assertTrue("setup_warn_incomplete" !in keysOf(sub("finished", "generic")))
    }

    @Test
    fun `finished without warnings shows no warning card`() {
        val f = sub("finished", "none")
        val k = keysOf(f)
        assertTrue(k.none { it.startsWith("setup_warn_") } && "status_output" !in k, k.toString())
        assertEquals(emptyList<Any?>(), list(f, "boxes"))
    }

    @Test
    fun `the onboarding button opens the terminal, then only types the command id, without Enter`() {
        val f = sub("finished", "onboard")
        // Typed at once (native writes it when the new session's shell starts): no timer, nothing later
        val typed =
            listOf(listOf("showTerminal"), listOf("writeCommandToTerminal", "openclawOnboard"), listOf("onComplete"))
        assertEquals(typed, list(f, "callsAtPress"))
        assertEquals(typed, list(f, "callsAt799"))
        assertEquals(typed, list(f, "callsAt800"))
        // the id names a fixed command without a line end (BridgeGuard)
        assertEquals("openclaw onboard", BridgeGuard.terminalCommands["openclawOnboard"])
    }

    @Test
    fun `later only leaves the page`() {
        assertEquals(listOf(listOf("onComplete")), list(sub("finished", "later"), "calls"))
    }

    // ── preparing ───────────────────────────────────────────────────────────

    @Test
    fun `preparing gives the page back to the resume screen after 60 s without an answer, not before`() {
        val r = result("preparing")
        assertTrue("setup_run_prechecking" in keys("preparing", "at0"))
        assertTrue("setup_run_prechecking" in keys("preparing", "at59"), keys("preparing", "at59").toString())
        val at60 = keys("preparing", "at60")
        assertFalse("setup_run_prechecking" in at60, at60.toString())
        assertTrue("setup_resume_title" in at60, at60.toString())
        assertTrue(num(r["readsAt60"]) > num(r["readsAtStart"]), "the result file is read again")
        assertEquals(1, num(r["starts"]))
    }

    @Test
    fun `preparing shows a run that started even if its first event was lost`() {
        val k = keys("preparing", "seen")
        assertTrue("status_stage" in k && "setup_run_prechecking" !in k, k.toString())
    }

    // ── a page created later ────────────────────────────────────────────────

    @Test
    fun `a recreated page restores the run it finds and never starts one`() {
        val running = sub("restore", "running")
        assertTrue("status_stage" in keysOf(running) && "setup_stage_5" in keysOf(running), running.toString())
        assertEquals(0, count(list(running, "calls"), "startRun"))
        assertTrue("setup_finished_title" in (sub("restore", "done")["keys"] as List<*>))
        val failed = sub("restore", "failed")["keys"] as List<*>
        assertTrue("setup_failed_title" in failed && "setup_reason_network" in failed, failed.toString())
        val boot = sub("restore", "bootstrapRunning")
        assertTrue("setup_setting_up" in keysOf(boot))
        assertEquals(0, count(list(boot, "calls"), "startRun") + count(list(boot, "calls"), "startSetup"))
    }

    @Test
    fun `a page recreated after the bootstrap ended elsewhere offers to continue instead of starting a second run`() {
        val gone = sub("restore", "bootstrapDoneGone")
        assertTrue("setup_resume_title" in keysOf(gone), gone.toString())
        assertEquals(0, count(list(gone, "calls"), "startRun"))
    }

    @Test
    fun `an update that runs is not shown as the setup's run, and no bootstrap opens the wizard`() {
        val upd = sub("restore", "updateRunning")
        assertTrue("setup_resume_title" in keysOf(upd), upd.toString())
        assertTrue("setup_choose_platform" in (sub("restore", "freshNoBootstrap")["keys"] as List<*>))
    }

    // internal: SetupReinstallTest runs its own scenarios on the same page harness
    internal companion object {
        val HARNESS =
            """
            |import fs from 'node:fs'
            |import path from 'node:path'
            |import { createRequire } from 'node:module'
            |import { pathToFileURL } from 'node:url'
            |const [esbuildMain, wwwSrc, work] = process.argv.slice(2)
            |const esbuild = createRequire(import.meta.url)(esbuildMain)
            |const stub = n => path.join(work, n)
            |fs.writeFileSync(stub('fake-react-setup.mjs'), "export * from './fake-react.mjs'\nexport const Fragment = 'fragment'\n")
            |const stubs = {
            |  'react': stub('fake-react-setup.mjs'),
            |  '../lib/useNativeEvent': stub('stub-native-event.mjs'),
            |  '../components/ConfirmCard': stub('stub-confirm-card.mjs'),
            |  '../i18n': stub('stub-i18n.mjs'),
            |  'real-i18n-en': path.join(wwwSrc, 'i18n/en.ts'),
            |  'real-i18n-ko': path.join(wwwSrc, 'i18n/ko.ts'),
            |  'real-i18n-zh': path.join(wwwSrc, 'i18n/zh.ts'),
            |}
            |const out = stub('setup.bundle.mjs')
            |await esbuild.build({
            |  entryPoints: [path.join(wwwSrc, 'screens/Setup.tsx')],
            |  bundle: true, format: 'esm', platform: 'node', outfile: out, logLevel: 'error',
            |  jsx: 'transform', jsxFactory: '__h', jsxFragment: '__Frag', inject: [stub('jsx.mjs')],
            |  tsconfigRaw: {},
            |  plugins: [{ name: 'stubs', setup(b) { b.onResolve({ filter: /.*/ }, a => (stubs[a.path] ? { path: stubs[a.path] } : undefined)) } }],
            |})
            |const { Setup } = await import(pathToFileURL(out).href)
            |globalThis.window = globalThis
            |const R = (globalThis.__fr = globalThis.__fr || { slots: [], idx: 0, pending: [], dirty: false, listeners: {} })
            |let now = 0
            |Date.now = () => now
            |const timers = new Map()
            |let nextTimer = 0
            |window.setInterval = (fn, ms) => { nextTimer++; timers.set(nextTimer, { fn, ms, due: now + ms, repeat: true }); return nextTimer }
            |window.clearInterval = id => { timers.delete(id) }
            |window.setTimeout = (fn, ms) => { nextTimer++; timers.set(nextTimer, { fn, ms: ms || 0, due: now + (ms || 0), repeat: false }); return nextTimer }
            |window.clearTimeout = id => { timers.delete(id) }
            |
            |const IDLE = { kind: null, phase: 'idle', stage: 0, stageTotal: 0, progress: 0, message: '', cancelable: false, cancelRequested: false, longRunning: false, reason: null, exit: null, detail: '', warnings: 0 }
            |const busy = (stage, extra) => Object.assign({}, IDLE, { kind: 'SETUP', phase: 'running', stage, stageTotal: 7, cancelable: true, message: 'line ' + stage, warn: [] }, extra)
            |const ended = (phase, extra) => Object.assign({}, IDLE, { kind: 'SETUP', phase, stageTotal: 7, warn: [] }, extra)
            |const TF = { bootstrapInstalled: true, platformInstalled: false }
            |const KEY = /\{((?:setup|status|step|tip|tool)_[a-z_0-9]+)\}/g
            |
            |function mount(native) {
            |  R.slots = []; R.idx = 0; R.pending = []; R.dirty = false; R.listeners = {}
            |  timers.clear(); now = 0; globalThis.__lang = null
            |  const n = Object.assign({ status: { bootstrapInstalled: false, platformInstalled: false }, setupState: {}, runState: IDLE, setupResult: { present: false, warn: [], interrupted: false, managed: true }, lastRun: {}, calls: [], reads: {} }, native)
            |  const record = name => (...a) => { n.calls.push([name].concat(a)) }
            |  const read = (name, f) => () => { n.reads[name] = (n.reads[name] || 0) + 1; return JSON.stringify(f()) }
            |  window.OpenClaw = {
            |    getSetupStatus: read('getSetupStatus', () => n.status),
            |    getSetupState: read('getSetupState', () => n.setupState),
            |    getRunState: read('getRunState', () => n.runState),
            |    getSetupResult: read('getSetupResult', () => n.setupResult),
            |    getLastRun: read('getLastRun', () => n.lastRun),
            |    getAvailablePlatforms: () => JSON.stringify([{ id: 'openclaw', name: 'OpenClaw', icon: 'C', desc: 'AI agent platform' }]),
            |    startRun: record('startRun'), cancelRun: record('cancelRun'), startSetup: record('startSetup'),
            |    saveToolSelections: record('saveToolSelections'), showTerminal: record('showTerminal'),
            |    writeCommandToTerminal: record('writeCommandToTerminal'),
            |  }
            |  const onComplete = record('onComplete')
            |  let tree
            |  const render = () => {
            |    for (let k = 0; k < 50; k++) {
            |      R.idx = 0; R.pending = []; R.dirty = false
            |      tree = Setup({ onComplete })
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
            |  const buttons = () => {
            |    const list = []
            |    walk(tree, x => {
            |      if (typeof x !== 'object') return
            |      if (x.type === 'button') list.push({ keys: keysOf(x.children), press: x.props.onClick })
            |      if (x.type === 'ConfirmCard') {
            |        list.push({ keys: keysOf(x.props.confirmLabel), press: x.props.onConfirm })
            |        list.push({ keys: keysOf(x.props.cancelLabel), press: x.props.onCancel })
            |      }
            |    })
            |    return list
            |  }
            |  const button = key => {
            |    const b = buttons().find(x => x.keys.includes(key))
            |    if (!b) throw new Error('no button ' + key + ' in ' + JSON.stringify(keysOf(tree)))
            |    return b
            |  }
            |  render()
            |  const page = {
            |    native: n,
            |    keys: () => keysOf(tree),
            |    textIn: lang => { globalThis.__lang = lang; render(); const t = textOf(tree); globalThis.__lang = null; render(); return t },
            |    boxes: () => {
            |      const list = []
            |      walk(tree, x => { if (typeof x === 'object' && x.props && x.props.style && x.props.style.fontFamily === 'monospace') list.push(textOf(x.children)) })
            |      return list
            |    },
            |    press: key => { button(key).press(); render() },
            |    pressHandlerTwice: key => { const b = button(key); b.press(); b.press(); render() },
            |    click: text => {
            |      let target = null
            |      walk(tree, x => { if (!target && typeof x === 'object' && x.type !== 'button' && x.props && typeof x.props.onClick === 'function' && textOf(x).includes(text)) target = x })
            |      if (!target) throw new Error('nothing clickable with ' + text)
            |      target.props.onClick(); render()
            |    },
            |    emit: (type, data) => { R.listeners[type](data); render() },
            |    render: () => render(),
            |    tick: ms => {
            |      const end = now + ms
            |      for (;;) {
            |        const due = Array.from(timers.entries()).filter(([, t]) => t.due <= end).sort((a, b) => a[1].due - b[1].due)[0]
            |        if (!due) break
            |        const [id, t] = due
            |        now = t.due
            |        if (t.repeat) t.due += t.ms
            |        else timers.delete(id)
            |        t.fn()
            |        render()
            |      }
            |      now = end
            |      render()
            |    },
            |    count: name => n.calls.filter(c => c[0] === name).length,
            |  }
            |  return page
            |}
            |
            |const resumeWith = (setupResult, lastRun) => mount({ status: TF, setupResult: Object.assign({ warn: [], managed: true }, setupResult), lastRun: lastRun || {} })
            |
            |const scenarios = {
            |  freshFlow() {
            |    const p = mount({})
            |    const r = {}
            |    r.k0 = p.keys()
            |    p.click('OpenClaw')
            |    r.k1 = p.keys()
            |    p.press('setup_start')
            |    r.k2 = p.keys()
            |    p.emit('setup_progress', { progress: 0.4, message: 'Extracting' })
            |    r.k3 = p.keys()
            |    p.native.status = TF
            |    p.emit('setup_progress', { progress: 1, message: 'Setup complete' })
            |    r.startsAfterEnd = p.count('startRun')
            |    p.emit('setup_progress', { progress: 1, message: 'Setup complete' })
            |    p.render(); p.render()
            |    r.startsAfterRepeat = p.count('startRun')
            |    r.k4 = p.keys()
            |    p.native.runState = busy(3)
            |    p.emit('run_progress', busy(3))
            |    r.k5 = p.keys()
            |    r.text5 = p.textIn('en')
            |    // a late repeat of the bootstrap's end, after the run answered (the start guard is free again)
            |    p.emit('setup_progress', { progress: 1, message: 'Setup complete' })
            |    r.startsAfterLateEnd = p.count('startRun')
            |    p.press('status_cancel')
            |    r.k6 = p.keys(); r.cancelAfterAsk = p.count('cancelRun')
            |    p.press('setup_cancel_keep')
            |    r.k7 = p.keys(); r.cancelAfterKeep = p.count('cancelRun')
            |    p.press('status_cancel'); p.press('setup_cancel_confirm')
            |    r.cancelAfterConfirm = p.count('cancelRun')
            |    const cancelled = ended('cancelled', { reason: 'CANCELLED', stage: 3, exit: 143 })
            |    p.native.runState = cancelled
            |    p.emit('run_progress', cancelled)
            |    r.k8 = p.keys()
            |    p.press('setup_resume_btn')
            |    r.startsAfterResume = p.count('startRun')
            |    r.calls = p.native.calls
            |    return r
            |  },
            |  terminalFlow() {
            |    const p = mount({ setupResult: { present: false, warn: [], interrupted: false, managed: false } })
            |    p.click('OpenClaw'); p.press('setup_start')
            |    const installing = p.keys()
            |    p.native.status = TF
            |    p.emit('setup_progress', { progress: 1 })
            |    const done = p.keys()
            |    p.press('setup_open_terminal')
            |    return { installing, done, calls: p.native.calls }
            |  },
            |  markerAtBootstrapEnd() {
            |    const p = mount({})
            |    p.click('OpenClaw'); p.press('setup_start')
            |    p.native.status = { bootstrapInstalled: true, platformInstalled: true }
            |    p.emit('setup_progress', { progress: 1 })
            |    return { keys: p.keys(), calls: p.native.calls }
            |  },
            |  resumeVariants() {
            |    const out = {}
            |    const v = (name, sr, lr) => { const p = resumeWith(sr, lr); out[name] = { keys: p.keys(), boxes: p.boxes(), en: p.textIn('en'), calls: p.native.calls } }
            |    v('interrupted', { present: true, stage: '3', interrupted: true, reason: 'INTERRUPTED' })
            |    v('cancelled', { present: true, stage: '3', interrupted: true, reason: 'INTERRUPTED' }, { SETUP: { at: 5, verdict: 'failure', reason: 'CANCELLED', exit: 143, warnings: 0 } })
            |    v('lastFailed', { present: true, stage: '1', error: 'index-download', reason: 'NETWORK', exit: 1, interrupted: false }, { SETUP: { at: 5, verdict: 'failure', reason: 'NETWORK', exit: 1, detail: 'Could not verify the Termux package list.', warnings: 0 } })
            |    v('noResult', { present: false, interrupted: false })
            |    v('busy', { present: true, stage: '2', interrupted: false })
            |    v('noSpace', { present: true, stage: '1', error: 'free-space', reason: 'NO_SPACE', needMb: 2000, haveMb: 500, exit: 1, interrupted: false })
            |    v('managedFalse', { present: true, stage: '3', interrupted: true, reason: 'INTERRUPTED', managed: false })
            |    v('unknownExit0', { present: true, stage: 'done', exit: 0, interrupted: false })
            |    return out
            |  },
            |  resumeManagedFalsePress() {
            |    const p = resumeWith({ present: false, managed: false })
            |    p.press('setup_open_terminal')
            |    return { calls: p.native.calls.slice(), keys: p.keys() }
            |  },
            |  reinstall() {
            |    const out = {}
            |    let p = resumeWith({ present: true, stage: '3', interrupted: true, reason: 'INTERRUPTED' })
            |    out.resume = p.keys()
            |    p.press('setup_reinstall_btn')
            |    out.confirm = p.keys()
            |    p.press('setup_reinstall_keep')
            |    out.kept = p.keys()
            |    p.press('setup_reinstall_btn'); p.press('setup_reinstall_confirm')
            |    out.wizard = p.keys()
            |    // Back from the terminal while in the wizard: the live page stays in the wizard
            |    p.emit('webview_shown', {})
            |    out.wizardAfterShown = p.keys()
            |    out.callsBeforeStart = p.native.calls.slice()
            |    p.click('OpenClaw'); p.press('setup_start')
            |    out.calls = p.native.calls.slice()
            |    p = mount({ status: TF, runState: busy(4) })
            |    const end = ended('failed', { reason: 'NETWORK', stage: 4, exit: 1 })
            |    p.native.runState = end
            |    p.emit('run_progress', end)
            |    out.failed = p.keys()
            |    // A run goes on elsewhere (BUSY): replacing usr under it is not offered
            |    out.busy = resumeWith({ present: true, stage: '2', interrupted: false }).keys()
            |    return out
            |  },
            |  reinstallMarker() {
            |    const out = {}
            |    const DONE = { bootstrapInstalled: true, platformInstalled: true }
            |    const failedPage = () => {
            |      const p = mount({ status: TF, runState: busy(4) })
            |      // warn data on the failed outcome: none of it may show anywhere once the page leaves
            |      const end = ended('failed', { reason: 'NETWORK', stage: 4, exit: 1, warnings: 2, warn: ['hardlink-patch', 'tools:tmux'] })
            |      p.native.runState = end
            |      p.emit('run_progress', end)
            |      return p
            |    }
            |    // The marker appears while the confirm card is open: nothing is replaced
            |    let p = failedPage()
            |    p.press('setup_reinstall_btn')
            |    p.native.status = DONE
            |    p.press('setup_reinstall_confirm')
            |    out.atConfirm = { keys: p.keys(), calls: p.native.calls.slice() }
            |    // The marker is there when the failure screen is drawn: no offer
            |    p = failedPage()
            |    p.native.status = DONE
            |    p.render()
            |    out.hidden = { keys: p.keys() }
            |    // Back from the terminal that finished the install: the failure screen becomes finished
            |    p = failedPage()
            |    p.native.status = DONE
            |    p.emit('webview_shown', {})
            |    out.shown = { keys: p.keys(), calls: p.native.calls.slice() }
            |    // The marker appears while the user is in the wizard: Start does not replace usr
            |    p = failedPage()
            |    p.press('setup_reinstall_btn'); p.press('setup_reinstall_confirm')
            |    p.native.status = DONE
            |    p.click('OpenClaw'); p.press('setup_start')
            |    out.wizard = { keys: p.keys(), calls: p.native.calls.slice() }
            |    return out
            |  },
            |  resumeTerminalThenBack() {
            |    const out = {}
            |    // (a) back from the terminal before the install finished: the page stays on the resume screen
            |    let p = resumeWith({ present: true, stage: '3', interrupted: true, reason: 'INTERRUPTED', managed: false })
            |    p.press('setup_open_terminal')
            |    p.emit('webview_shown', {})
            |    out.notYet = { calls: p.native.calls.slice(), keys: p.keys() }
            |    // (b) the terminal finished the install (the marker is there): the page leaves to the dashboard
            |    p.native.status = { bootstrapInstalled: true, platformInstalled: true }
            |    p.emit('webview_shown', {})
            |    out.finished = { calls: p.native.calls.slice(), keys: p.keys() }
            |    // (c) the script became capable while the user was in the terminal: the managed resume screen
            |    p = resumeWith({ present: true, stage: '3', interrupted: true, reason: 'INTERRUPTED', managed: false })
            |    p.press('setup_open_terminal')
            |    p.native.setupResult = Object.assign({}, p.native.setupResult, { managed: true })
            |    p.emit('webview_shown', {})
            |    out.capable = { calls: p.native.calls.slice(), keys: p.keys() }
            |    // A page made again (back through the Dashboard tab) after the terminal finished: no first-install screen
            |    p = mount({ status: { bootstrapInstalled: true, platformInstalled: true } })
            |    out.remount = { calls: p.native.calls.slice(), keys: p.keys() }
            |    return out
            |  },
            |  resumeManagedFlips() {
            |    const p = resumeWith({ present: true, stage: '2', interrupted: true, reason: 'INTERRUPTED', managed: false })
            |    const before = p.keys()
            |    // MainActivity's refresh made the home copy capable meanwhile; the page is shown again
            |    p.native.setupResult = Object.assign({}, p.native.setupResult, { managed: true })
            |    p.emit('webview_shown', {})
            |    return { before, afterFlip: p.keys(), calls: p.native.calls }
            |  },
            |  resumeManagedFlipsPress() {
            |    const p = resumeWith({ present: true, stage: '2', interrupted: true, reason: 'INTERRUPTED', managed: false })
            |    // The script became capable but no webview_shown came: the button asks again when pressed
            |    p.native.setupResult = Object.assign({}, p.native.setupResult, { managed: true })
            |    p.press('setup_open_terminal')
            |    return { calls: p.native.calls, keys: p.keys() }
            |  },
            |  resumeStartOnce() {
            |    const p = resumeWith({ present: false })
            |    p.pressHandlerTwice('setup_resume_btn')
            |    p.render()
            |    return { starts: p.count('startRun'), keys: p.keys() }
            |  },
            |  resumeMarkerMeanwhile() {
            |    const p = resumeWith({ present: true, stage: '6', interrupted: true, reason: 'INTERRUPTED' })
            |    p.native.status = { bootstrapInstalled: true, platformInstalled: true }
            |    p.press('setup_resume_btn')
            |    return { starts: p.count('startRun'), completes: p.count('onComplete'), keys: p.keys() }
            |  },
            |  failures() {
            |    const out = {}
            |    const reasons = [
            |      ['NO_SPACE_MB', 'NO_SPACE', { needMb: 2000, haveMb: 321 }], ['NO_SPACE', 'NO_SPACE', {}], ['NETWORK', 'NETWORK', {}],
            |      ['VERIFY_FAILED', 'VERIFY_FAILED', {}], ['INSTALL_FAILED', 'INSTALL_FAILED', {}], ['ENV', 'ENV', {}],
            |      ['OPENCLAW_INCOMPLETE', 'OPENCLAW_INCOMPLETE', {}],
            |      ['INTERRUPTED', 'INTERRUPTED', {}], ['BUSY', 'BUSY', {}], ['UNKNOWN', 'UNKNOWN', {}], ['LATER', 'SOME_LATER_REASON', {}],
            |    ]
            |    for (const [label, reason, extra] of reasons) {
            |      const p = mount({ status: TF, runState: busy(4) })
            |      const end = ended('failed', Object.assign({ reason, stage: 4, exit: 1, detail: 'first line\nsecond line' }, extra))
            |      p.native.runState = end
            |      p.emit('run_progress', end)
            |      out[label] = { keys: p.keys(), boxes: p.boxes(), en: p.textIn('en'), ko: p.textIn('ko'), zh: p.textIn('zh') }
            |    }
            |    return out
            |  },
            |  refusals() {
            |    let p = resumeWith({ present: false })
            |    p.press('setup_resume_btn')
            |    p.emit('run_progress', ended('refused', { reason: 'NOT_INSTALLED', stage: 0 }))
            |    const notInstalled = p.keys()
            |    p = resumeWith({ present: false })
            |    p.press('setup_resume_btn')
            |    p.emit('run_progress', Object.assign({}, IDLE, { kind: null, phase: 'refused', reason: 'UNKNOWN' }))
            |    const noKind = p.keys()
            |    p = mount({ status: TF, runState: busy(2) })
            |    p.emit('run_progress', ended('refused', { reason: 'BUSY' }))
            |    const busyKeep = p.keys()
            |    p = resumeWith({ present: false })
            |    p.press('setup_resume_btn')
            |    p.emit('run_progress', ended('refused', { reason: 'BUSY' }))
            |    const busyEnd = p.keys()
            |    p = resumeWith({ present: false })
            |    const before = p.keys()
            |    p.emit('run_progress', Object.assign({}, busy(2), { kind: 'UPDATE', stageTotal: 5 }))
            |    const afterUpdate = p.keys()
            |    return { notInstalled, noKind, busyKeep, busyEnd, before, afterUpdate }
            |  },
            |  finished() {
            |    const out = {}
            |    const fin = (name, extra) => {
            |      const p = mount({ status: TF, runState: busy(7) })
            |      const end = ended('done', Object.assign({ stage: 7, progress: 1, exit: 0 }, extra))
            |      p.native.runState = end
            |      p.native.status = { bootstrapInstalled: true, platformInstalled: true }
            |      p.emit('run_progress', end)
            |      out[name] = { keys: p.keys(), boxes: p.boxes(), en: p.textIn('en') }
            |      return p
            |    }
            |    fin('tools', { warn: ['tools:tmux', 'tools:claude-code', 'clawdhub'], warnings: 3, detail: 'tmux could not be installed (non-critical)' })
            |    fin('generic', { warn: ['oa-cli'], warnings: 1, detail: '' })
            |    fin('none', { warn: [], warnings: 0, detail: 'x' })
            |    // QA vc27: a v1.2.2 resume after a cut during OpenClaw's npm extraction ends like this
            |    fin('incomplete', { warn: ['hardlink-patch', 'checkOnStart'], warnings: 2, detail: '' })
            |    fin('incompleteOnly', { warn: ['hardlink-patch'], warnings: 1, detail: '' })
            |    const p = fin('onboard', { warn: [], warnings: 0 })
            |    p.press('setup_onboard_btn')
            |    out.onboard.callsAtPress = p.native.calls.slice()
            |    p.tick(799)
            |    out.onboard.callsAt799 = p.native.calls.slice()
            |    p.tick(1)
            |    out.onboard.callsAt800 = p.native.calls.slice()
            |    const q = fin('later', {})
            |    q.press('setup_onboard_later')
            |    out.later.calls = q.native.calls
            |    return out
            |  },
            |  preparing() {
            |    const p = resumeWith({ present: false })
            |    p.press('setup_resume_btn')
            |    const at0 = p.keys()
            |    const readsAtStart = p.native.reads.getSetupResult
            |    p.tick(59000)
            |    const at59 = p.keys()
            |    p.tick(1000)
            |    const at60 = p.keys()
            |    const readsAt60 = p.native.reads.getSetupResult
            |    const q = resumeWith({ present: false })
            |    q.press('setup_resume_btn')
            |    q.native.runState = busy(2)
            |    q.tick(1000)
            |    return { at0, at59, at60, readsAtStart, readsAt60, starts: p.count('startRun'), seen: q.keys() }
            |  },
            |  restore() {
            |    const out = {}
            |    let p = mount({ status: TF, runState: busy(5) })
            |    out.running = { keys: p.keys(), calls: p.native.calls }
            |    out.done = { keys: mount({ status: TF, runState: ended('done', { stage: 7, exit: 0 }) }).keys() }
            |    out.failed = { keys: mount({ status: TF, runState: ended('failed', { reason: 'NETWORK', stage: 3, exit: 1 }) }).keys() }
            |    p = mount({ status: TF, setupState: { phase: 'running', progress: 0.3, message: 'x' } })
            |    out.bootstrapRunning = { keys: p.keys(), calls: p.native.calls }
            |    p = mount({ status: TF, setupState: { phase: 'done' } })
            |    out.bootstrapDoneGone = { keys: p.keys(), calls: p.native.calls }
            |    p = mount({ status: TF, runState: Object.assign({}, busy(2), { kind: 'UPDATE', stageTotal: 5 }) })
            |    out.updateRunning = { keys: p.keys(), calls: p.native.calls }
            |    out.freshNoBootstrap = { keys: mount({}).keys() }
            |    return out
            |  },
            |}
            |
            |const results = {}
            |for (const [name, fn] of Object.entries(scenarios)) {
            |  try { results[name] = fn() } catch (e) { results[name] = { error: String(e && e.stack || e) } }
            |}
            |console.log(JSON.stringify(results))
            |
            """.trimMargin()
    }
}
