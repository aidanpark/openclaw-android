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
 * D2 「Reinstall the base system」 on the first-install page, as it BEHAVES (the harness of
 * [SetupScreenBehaviorTest]): which screens offer it (every SETUP end that is not BUSY — failed,
 * cancelled, refused — and the managed resume screen), which never do (BUSY, the terminal resume,
 * the NOT_INSTALLED fallback, the finished and the bootstrap-failed screens), that it asks first,
 * and that the wizard it opens runs the bootstrap and then exactly ONE SETUP run. Then the five
 * texts in every locale, read against what `BootstrapManager.startSetup` really does.
 *
 * Skipped (not failed) when node or `www/node_modules/esbuild` is missing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class SetupReinstallTest {
    private val www = File("../www").absoluteFile
    private val work: File by lazy { Files.createTempDirectory("setup-reinstall").toFile() }
    private val results by lazy {
        val prefix = SetupScreenBehaviorTest.HARNESS.substringBefore("const scenarios = {")
        assertTrue(prefix.length < SetupScreenBehaviorTest.HARNESS.length, "the setup harness changed shape")
        StatusScreenBehaviorTest.runHarness(www, work, prefix + SCENARIOS)
    }

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
    private fun keys(
        name: String,
        fact: String,
    ): List<String> = result(name)[fact] as List<String>

    @Suppress("UNCHECKED_CAST")
    private fun calls(
        name: String,
        fact: String,
    ): List<List<Any?>> = result(name)[fact] as List<List<Any?>>

    private fun List<List<Any?>>.count(name: String) = count { it.first() == name }

    // ── where the button is ─────────────────────────────────────────────────

    @Test
    fun `every SETUP end that is not BUSY offers the reinstall - failed, cancelled, refused`() {
        for (fact in listOf(
            "failedNetwork",
            "failedInterrupted",
            "failedIncomplete",
            "cancelled",
            "refusedUnknown",
            "refusedNoKind",
        )) {
            val k = keys("screens", fact)
            assertTrue("setup_reinstall_btn" in k, "$fact: $k")
        }
        assertTrue("setup_run_cancelled_title" in keys("screens", "cancelled"))
    }

    @Test
    fun `a BUSY end never offers it - a run goes on elsewhere`() {
        for (fact in listOf("refusedBusy", "failedBusy", "resumeBusy")) {
            val k = keys("screens", fact)
            assertFalse("setup_reinstall_btn" in k, "$fact: $k")
        }
        assertTrue("setup_reason_busy" in keys("screens", "refusedBusy"), keys("screens", "refusedBusy").toString())
    }

    @Test
    fun `the managed resume screen offers it for every reason but BUSY, also without a result file`() {
        for (fact in listOf(
            "resumeInterrupted",
            "resumeCancelled",
            "resumeFailed",
            "resumeNoResult",
            "resumeNoSpace",
        )) {
            val k = keys("screens", fact)
            assertTrue("setup_reinstall_btn" in k && "setup_resume_btn" in k, "$fact: $k")
        }
    }

    @Test
    fun `terminal resume, NOT_INSTALLED fallback, finished and bootstrap-failed screens never offer it`() {
        val cases =
            mapOf(
                "resumeTerminal" to "setup_open_terminal",
                "notInstalledFallback" to "setup_open_terminal",
                "finished" to "setup_finished_title",
                "bootstrapFailed" to "setup_failed_title",
                "wizard" to "setup_choose_platform",
            )
        for ((fact, marker) in cases) {
            val k = keys("screens", fact)
            assertTrue(marker in k, "$fact is not the screen meant: $k")
            assertFalse("setup_reinstall_btn" in k, "$fact: $k")
        }
    }

    @Test
    fun `a run starting elsewhere while the question is open takes the question away`() {
        assertTrue("setup_reinstall_title" in keys("busyWhileAsking", "asking"))
        val after = keys("busyWhileAsking", "after")
        assertFalse("setup_reinstall_title" in after || "setup_reinstall_btn" in after, after.toString())
        assertEquals(emptyList<Any?>(), calls("busyWhileAsking", "calls"))
    }

    // ── asking, then the wizard, then one bootstrap and ONE SETUP run ───────

    @Test
    fun `from a failure - asks first, keeping returns to the failure, nothing is called`() {
        val confirm = keys("fromFailure", "confirm")
        assertTrue("setup_reinstall_title" in confirm && "setup_reinstall_body" in confirm, confirm.toString())
        val kept = keys("fromFailure", "kept")
        assertTrue("setup_failed_title" in kept && "setup_reinstall_btn" in kept, kept.toString())
        assertFalse("setup_reinstall_title" in kept, kept.toString())
        assertEquals(emptyList<Any?>(), calls("fromFailure", "callsAfterKeep"))
    }

    @Test
    fun `confirming lands on the platform choice (not the resume screen) and starts nothing by itself`() {
        val k = keys("fromFailure", "wizard")
        assertTrue("setup_choose_platform" in k, k.toString())
        assertFalse(
            "setup_resume_title" in k || "setup_failed_title" in k || "setup_reinstall_title" in k,
            k.toString(),
        )
        assertEquals(emptyList<Any?>(), calls("fromFailure", "callsAfterConfirm"))
    }

    @Test
    fun `the wizard runs the bootstrap, then exactly one SETUP run, also when the end repeats`() {
        val atStart = calls("fromFailure", "callsAtStart")
        assertEquals(1, atStart.count("saveToolSelections"), atStart.toString())
        assertEquals(1, atStart.count("startSetup"), atStart.toString())
        assertEquals(0, atStart.count("startRun"), "SETUP started before the bootstrap ended: $atStart")
        val all = calls("fromFailure", "calls")
        assertEquals(1, all.count("startSetup"), all.toString())
        assertEquals(listOf(listOf<Any?>("startRun", "SETUP", false)), all.filter { it.first() == "startRun" })
        assertTrue("status_stage" in keys("fromFailure", "running"), keys("fromFailure", "running").toString())
    }

    @Test
    fun `from the resume screen the same - ask, wizard, bootstrap, one SETUP run`() {
        assertTrue("setup_choose_platform" in keys("fromResume", "wizard"))
        val all = calls("fromResume", "calls")
        assertEquals(1, all.count("startSetup"), all.toString())
        assertEquals(1, all.count("startRun"), all.toString())
    }

    @Test
    fun `on one live page - first install, SETUP fails, reinstall - the second bootstrap starts SETUP again, once`() {
        val first = calls("livePage", "callsAfterFailure")
        assertEquals(1, first.count("startSetup"), first.toString())
        assertEquals(1, first.count("startRun"), first.toString())
        val all = calls("livePage", "calls")
        assertEquals(2, all.count("startSetup"), all.toString())
        assertEquals(2, all.count("startRun"), "the bootstrap after the reinstall did not start SETUP once: $all")
    }

    @Test
    fun `a device whose install is complete (the marker) leaves the setup page instead of offering anything`() {
        assertEquals(listOf(listOf<Any?>("onComplete")), calls("markerPresent", "calls"))
    }

    // ── the texts ───────────────────────────────────────────────────────────

    private val reinstallKeys = listOf("btn", "title", "body", "confirm", "keep").map { "setup_reinstall_$it" }

    private fun texts(locale: String): Map<String, String> {
        val src = File("../www/src/i18n/$locale.ts").readText()
        return reinstallKeys.associateWith { key ->
            val m = Regex("""^\s*$key:\s*(['"])(.*)\1,?\s*$""", RegexOption.MULTILINE).find(src)
            assertTrue(m != null, "$key missing from $locale.ts")
            m!!.groupValues[2]
        }
    }

    @Test
    fun `the five texts exist, are not empty and differ from English in ko and zh`() {
        val en = texts("en")
        for (locale in listOf("en", "ko", "zh")) {
            val t = texts(locale)
            t.forEach { (k, v) -> assertTrue(v.isNotBlank(), "$k empty in $locale") }
            if (locale != "en") {
                assertTrue(
                    t.getValue("setup_reinstall_body") != en.getValue("setup_reinstall_body"),
                    "$locale body is English",
                )
            }
        }
    }

    @Test
    fun `the body says what startSetup does - usr replaced only after the new copy is verified, home kept`() {
        val facts =
            mapOf(
                "en" to
                    listOf(
                        "usr",
                        "verified first",
                        "current one stays",
                        // the failure stages before the swap (BootstrapPrefixSwapTest pins each)
                        "download",
                        "verification",
                        "extraction",
                        "configuration",
                        // the old usr is deleted whole: what was installed in it goes too
                        "npm global tools",
                        "are removed",
                        "home folder is not deleted",
                        "~/.openclaw-android",
                        "~/.bashrc",
                        "~/.gitconfig",
                        "replaced",
                    ),
                "ko" to
                    listOf(
                        "usr",
                        "검증",
                        "그대로 남습니다",
                        "내려받기·검증·압축 해제·구성",
                        "npm 전역 도구",
                        "삭제되며",
                        "홈 폴더는 삭제하지 않",
                        "~/.openclaw-android",
                        "~/.bashrc",
                        "~/.gitconfig",
                        "대체",
                    ),
                "zh" to
                    listOf(
                        "usr",
                        "验证",
                        "保持不变",
                        "下载、验证、解压或配置",
                        "npm 全局工具",
                        "会被删除",
                        "主目录不会被删除",
                        "~/.openclaw-android",
                        "~/.bashrc",
                        "~/.gitconfig",
                        "替换",
                    ),
            )
        for ((locale, words) in facts) {
            val body = texts(locale).getValue("setup_reinstall_body")
            words.forEach { assertTrue(body.contains(it), "$locale body lacks '$it': $body") }
        }
    }

    /** Why the body names ~/.bashrc: the SETUP run after the reinstall writes it whole. */
    @Test
    fun `the setup that follows really rewrites bashrc - the body's warning is grounded`() {
        val script = File("../../post-setup.sh").readText()
        assertTrue(
            Regex("""cat > "\${'$'}HOME/\.bashrc" << """).containsMatchIn(script),
            "post-setup.sh no longer rewrites ~/.bashrc",
        )
        // and resets the GitHub URL rewrites in ~/.gitconfig (the body's "GitHub URL settings")
        assertTrue(script.contains("git config --global --unset-all url.\"https://github.com/\".insteadOf"))
        // npm globals live under usr (lib/node_modules), which the swap deletes whole (links not followed)
        assertTrue(
            File(
                "src/main/java/com/openclaw/android/BootstrapManager.kt",
            ).readText().contains("SafeTree.deleteNoFollow(prefixDir)"),
        )
    }

    @Test
    fun `the body makes no promise about OpenClaw data, projects or settings`() {
        val forbidden =
            mapOf(
                "en" to
                    listOf(
                        "OpenClaw data",
                        "project",
                        "workspace",
                        ".openclaw/",
                        "settings are kept",
                        "nothing is lost",
                        "your data",
                    ),
                "ko" to listOf("OpenClaw 데이터", "프로젝트", "작업 공간", ".openclaw/", "설정은 유지", "데이터는 유지"),
                "zh" to listOf("OpenClaw 数据", "项目", "工作区", ".openclaw/", "设置会保留", "数据会保留"),
            )
        for ((locale, words) in forbidden) {
            val all = texts(locale).values.joinToString(" ")
            words.forEach { assertFalse(all.contains(it), "$locale promises '$it': $all") }
        }
    }

    @Test
    fun `startSetup does what the body says - verified copy first, old usr removed only then, home never deleted`() {
        val bm = File("src/main/java/com/openclaw/android/BootstrapManager.kt").readText()
        val start = bm.indexOf("suspend fun startSetup(")
        val body = bm.substring(start, bm.indexOf("\n    }\n", start))
        val order =
            listOf(
                "getBootstrapArchive(onProgress)",
                "extractBootstrap(",
                "configureApt(stagingDir)",
                "SafeTree.deleteNoFollow(prefixDir)",
                "stagingDir.renameTo(prefixDir)",
            ).map { it to body.indexOf(it) }
        order.forEach { (what, at) -> assertTrue(at >= 0, "startSetup lacks $what") }
        assertEquals(order.sortedBy { it.second }, order, "startSetup order changed: $order")
        // The old usr is deleted in exactly one place, after the configuration
        assertEquals(
            1,
            Regex(
                """(deleteRecursively|deleteNoFollow)\(prefixDir\)|prefixDir\.deleteRecursively""",
            ).findAll(bm).count(),
        )
        // Home is never deleted, and no recursive delete that follows links is left in the file
        assertFalse(Regex("""homeDir\.deleteRecursively|deleteNoFollow\(homeDir""").containsMatchIn(bm))
        assertFalse(bm.contains(".deleteRecursively()"), "a link-following delete is back in BootstrapManager")
    }

    private companion object {
        val SCENARIOS =
            """
            |const scenarios = {
            |  screens() {
            |    const out = {}
            |    const end = (phase, extra) => {
            |      const p = mount({ status: TF, runState: busy(4) })
            |      const e = ended(phase, Object.assign({ stage: 4, exit: 1 }, extra))
            |      p.native.runState = e
            |      p.emit('run_progress', e)
            |      return p.keys()
            |    }
            |    out.failedNetwork = end('failed', { reason: 'NETWORK' })
            |    out.failedIncomplete = end('failed', { reason: 'OPENCLAW_INCOMPLETE' })
            |    out.failedInterrupted = end('failed', { reason: 'INTERRUPTED' })
            |    out.failedBusy = end('failed', { reason: 'BUSY' })
            |    out.cancelled = end('cancelled', { reason: 'CANCELLED', exit: 143 })
            |    // refusals of the page's own start (native idle again)
            |    const refused = ev => { const p = resumeWith({ present: false }); p.press('setup_resume_btn'); p.emit('run_progress', ev); return p.keys() }
            |    out.refusedUnknown = refused(ended('refused', { reason: 'UNKNOWN', stage: 0 }))
            |    out.refusedNoKind = refused(Object.assign({}, IDLE, { kind: null, phase: 'refused', reason: 'UNKNOWN' }))
            |    out.refusedBusy = refused(ended('refused', { reason: 'BUSY', stage: 0 }))
            |    out.notInstalledFallback = refused(ended('refused', { reason: 'NOT_INSTALLED', stage: 0 }))
            |    out.resumeInterrupted = resumeWith({ present: true, stage: '3', interrupted: true, reason: 'INTERRUPTED' }).keys()
            |    out.resumeCancelled = resumeWith({ present: true, stage: '3', interrupted: true, reason: 'INTERRUPTED' }, { SETUP: { at: 5, verdict: 'failure', reason: 'CANCELLED', exit: 143, warnings: 0 } }).keys()
            |    out.resumeFailed = resumeWith({ present: true, stage: '1', error: 'index-download', reason: 'NETWORK', exit: 1, interrupted: false }).keys()
            |    out.resumeNoResult = resumeWith({ present: false }).keys()
            |    out.resumeNoSpace = resumeWith({ present: true, stage: '1', reason: 'NO_SPACE', needMb: 2000, haveMb: 5, exit: 1, interrupted: false }).keys()
            |    out.resumeBusy = resumeWith({ present: true, stage: '2', interrupted: false }).keys()
            |    out.resumeTerminal = resumeWith({ present: true, stage: '3', interrupted: true, reason: 'INTERRUPTED', managed: false }).keys()
            |    out.finished = end('done', { stage: 7, progress: 1, exit: 0, warn: [] })
            |    let p = mount({})
            |    p.click('OpenClaw'); p.press('setup_start')
            |    p.emit('setup_progress', { error: 'x', errorKind: 'NETWORK' })
            |    out.bootstrapFailed = p.keys()
            |    out.wizard = mount({}).keys()
            |    return out
            |  },
            |  busyWhileAsking() {
            |    const p = resumeWith({ present: true, stage: '3', interrupted: true, reason: 'INTERRUPTED' })
            |    p.press('setup_reinstall_btn')
            |    const asking = p.keys()
            |    // a run started elsewhere meanwhile (e.g. the terminal): the page is shown again and re-reads
            |    p.native.setupResult = { present: true, stage: '2', interrupted: false, warn: [], managed: true }
            |    p.native.lastRun = {}
            |    p.emit('webview_shown', {})
            |    return { asking, after: p.keys(), calls: p.native.calls.slice() }
            |  },
            |  fromFailure() {
            |    const out = {}
            |    const p = mount({ status: TF, runState: busy(4) })
            |    const e = ended('failed', { reason: 'NETWORK', stage: 4, exit: 1 })
            |    p.native.runState = e
            |    p.emit('run_progress', e)
            |    p.press('setup_reinstall_btn')
            |    out.confirm = p.keys()
            |    p.press('setup_reinstall_keep')
            |    out.kept = p.keys()
            |    out.callsAfterKeep = p.native.calls.slice()
            |    p.press('setup_reinstall_btn'); p.press('setup_reinstall_confirm')
            |    out.wizard = p.keys()
            |    out.callsAfterConfirm = p.native.calls.slice()
            |    p.native.runState = IDLE
            |    p.click('OpenClaw'); p.press('setup_start')
            |    out.callsAtStart = p.native.calls.slice()
            |    p.emit('setup_progress', { progress: 0.5, message: 'Extracting' })
            |    p.emit('setup_progress', { progress: 1, message: 'Setup complete' })
            |    p.emit('setup_progress', { progress: 1, message: 'Setup complete' })
            |    p.render(); p.render()
            |    p.native.runState = busy(1)
            |    p.emit('run_progress', busy(1))
            |    out.running = p.keys()
            |    p.emit('setup_progress', { progress: 1, message: 'Setup complete' })
            |    out.calls = p.native.calls.slice()
            |    return out
            |  },
            |  fromResume() {
            |    const p = resumeWith({ present: true, stage: '3', interrupted: true, reason: 'INTERRUPTED' })
            |    p.press('setup_reinstall_btn'); p.press('setup_reinstall_confirm')
            |    const wizard = p.keys()
            |    p.click('OpenClaw'); p.press('setup_start')
            |    p.emit('setup_progress', { progress: 1 })
            |    p.emit('setup_progress', { progress: 1 })
            |    return { wizard, calls: p.native.calls.slice() }
            |  },
            |  livePage() {
            |    const p = mount({})
            |    p.click('OpenClaw'); p.press('setup_start')
            |    p.native.status = TF
            |    p.emit('setup_progress', { progress: 1 })
            |    p.native.runState = busy(2)
            |    p.emit('run_progress', busy(2))
            |    const e = ended('failed', { reason: 'INSTALL_FAILED', stage: 2, exit: 1 })
            |    p.native.runState = e
            |    p.emit('run_progress', e)
            |    const callsAfterFailure = p.native.calls.slice()
            |    p.press('setup_reinstall_btn'); p.press('setup_reinstall_confirm')
            |    p.native.runState = IDLE
            |    p.click('OpenClaw'); p.press('setup_start')
            |    p.emit('setup_progress', { progress: 1 })
            |    p.emit('setup_progress', { progress: 1 })
            |    return { callsAfterFailure, calls: p.native.calls.slice() }
            |  },
            |  markerPresent() {
            |    const p = mount({ status: { bootstrapInstalled: true, platformInstalled: true }, setupResult: { present: true, stage: '3', interrupted: true, reason: 'INTERRUPTED', warn: [], managed: true } })
            |    return { calls: p.native.calls.slice() }
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
