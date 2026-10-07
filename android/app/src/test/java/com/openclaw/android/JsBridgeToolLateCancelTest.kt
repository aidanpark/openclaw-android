package com.openclaw.android

import android.content.Context
import com.google.gson.Gson
import io.mockk.every
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * A cancel that came too late, end to end through the bridge (v0.4.4 QA finding): the app's
 * SIGTERM reaches bash at once, but bash runs the TERM trap only after the foreground step — here a
 * child that installs the tool and then takes its time. The run ends `error=interrupted` although
 * the tool is in place; the tool's own `--version` check (a fake executable in `$PREFIX/bin`)
 * decides: ok → `done` + CANCEL_TOO_LATE, anything else → `cancelled`. The rows a cancel did not
 * touch never run the check.
 */
internal class JsBridgeToolLateCancelTest : JsBridgeToolInstallFixture() {
    private val probeLog get() = File(home, "probe.log")
    private val installedFlag get() = File(home, "installed")

    @BeforeEach
    fun probeOnPath() {
        // The probe resolves `tmux` on this PATH: the file the install put in $PREFIX/bin
        every { EnvironmentBuilder.build(any<Context>()) } returns env()
    }

    private fun env() =
        mapOf(
            "PATH" to "${prefix.absolutePath}/bin:/bin:/usr/bin",
            "HOME" to home.absolutePath,
            "PREFIX" to prefix.absolutePath,
        )

    /** The tool the install puts in place; [body] decides what its `-V` check does. Logs each call. */
    private fun fakeTool(body: String) {
        File(home, "fake-tool").writeText("#!/bin/sh\necho \"${'$'}*\" >> \"${'$'}HOME/probe.log\"\n$body\n")
    }

    /**
     * Like post-setup.sh: TERM trap writing `error=interrupted`, pid in the tools lock, then the
     * foreground step — a child that installs [what] (when [installs]) and waits for `$HOME/release`.
     * Without a TERM the script reports `<id>=ok`.
     */
    private fun lateScript(
        id: String = "tmux",
        installs: Boolean = true,
        what: String = INSTALL_TMUX,
    ) {
        val install = if (installs) "$what && : > \"${'$'}HOME/installed\"; " else ": > \"${'$'}HOME/installed\"; "
        fakeScript(
            """
            |RUN=${'$'}(date +%s)
            |trap 'printf "schema=1\nrun=%s\nerror=interrupted\nexit=143\n" "${'$'}RUN" > "${'$'}R"; exit 143' TERM
            |echo "working"
            |mkdir -p "${'$'}HOME/.openclaw-android/.tools.lock" && echo ${'$'}${'$'} > "${'$'}HOME/.openclaw-android/.tools.lock/pid"
            |sh -c '$install while [ ! -f "${'$'}HOME/release" ]; do sleep 0.05; done'
            |printf 'schema=1\nrun=%s\n$id=ok\nexit=0\n' "${'$'}RUN" > "${'$'}R"
            |exit 0
            """.trimMargin(),
            list = "printf 'tmux\\nttyd\\ndufs\\nplaywright\\n'; exit 0",
        )
    }

    /** Install, wait for the foreground step to have put the tool in place, cancel (SIGTERM delivered). */
    private fun cancelAfterInstall(
        id: String = "tmux",
        deliver: Boolean = true,
    ): Pair<JsBridge, RecordingWebView> {
        marker()
        fakeSignal { deliver }
        val (bridge, web) = page()
        bridge.installTool(id)
        assertTrue(TestWait.until { installedFlag.exists() && lockPidFile.isFile }, "the step never ran: ${calls()}")
        bridge.cancelToolInstall()
        assertEquals("cancelling", bridge.toolState()["phase"])
        if (deliver) assertTrue(TestWait.until { signalledPids.isNotEmpty() }, "the SIGTERM was not delivered")
        return bridge to web
    }

    private fun assertLateCancelDone(
        bridge: JsBridge,
        end: EmittedEvent,
    ) {
        assertEquals("done", end.data["phase"], end.toString())
        assertEquals(ToolInstallGuard.CANCEL_TOO_LATE, end.data["reason"], end.toString())
        assertEquals(1.0, (end.data["progress"] as Number).toDouble())
        assertEquals(false, end.data["cancelRequested"])
        assertEquals("tmux", end.data["target"])
        val state = bridge.toolState()
        assertEquals("done", state["phase"])
        assertEquals(ToolInstallGuard.CANCEL_TOO_LATE, state["reason"])
    }

    private fun assertCancelled(end: EmittedEvent) {
        assertEquals("cancelled", end.data["phase"], end.toString())
        assertEquals("INTERRUPTED", end.data["reason"], end.toString())
        assertEquals(0.0, (end.data["progress"] as Number).toDouble())
    }

    // ── the truth table, through the bridge ─────────────────────────────────

    @Test
    fun `cancel, tool on disk, interrupted, check ok - done with CANCEL_TOO_LATE, checked once`() {
        fakeTool("exit 0")
        lateScript()
        val (bridge, web) = cancelAfterInstall()
        releaseFile.writeText("go")
        val end = finalEvent(web)
        assertLateCancelDone(bridge, end)
        assertTrue(File(oca, "tools-result.conf").readText().contains("error=interrupted"), "the trap did not run")
        assertEquals(listOf("-V"), probeLog.readLines(), "the check ran other than once")
        // No cancelled end was ever shown for this run
        assertTrue(web.events("tool_progress").none { it.data["phase"] == "cancelled" }, web.scripts.toString())
        assertNull(RunLease.owner())
    }

    @Test
    fun `cancel, tool on disk, interrupted, check fails - stays cancelled`() {
        fakeTool("exit 1")
        lateScript()
        val (_, web) = cancelAfterInstall()
        releaseFile.writeText("go")
        assertCancelled(finalEvent(web))
        assertEquals(listOf("-V"), probeLog.readLines())
    }

    @Test
    fun `cancel, tool on disk, interrupted, check killed by a signal (unknown) - stays cancelled`() {
        fakeTool("kill -9 ${'$'}${'$'}")
        lateScript()
        val (_, web) = cancelAfterInstall()
        releaseFile.writeText("go")
        assertCancelled(finalEvent(web))
        assertEquals(listOf("-V"), probeLog.readLines())
    }

    /** VERIFY_TIMEOUT_MS is 10 s: this row takes that long. */
    @Test
    fun `cancel, tool on disk, interrupted, check times out after 10 s - stays cancelled`() {
        fakeTool("exec sleep 30")
        lateScript()
        val (bridge, web) = cancelAfterInstall()
        val released = System.nanoTime()
        releaseFile.writeText("go")
        assertTrue(TestWait.until(TIMEOUT_WAIT_MS) { !ToolInstallGuard.isRunning() }, "the check was not stopped")
        val tookMs = (System.nanoTime() - released) / 1_000_000
        assertTrue(tookMs >= 9_000, "the check was stopped after $tookMs ms, before its 10 s")
        assertCancelled(finalEvent(web))
        assertEquals("cancelled", bridge.toolState()["phase"])
    }

    @Test
    fun `cancel, tool on disk, interrupted, the check throws - the interrupted verdict stays, cancelled`() {
        fakeTool("exit 0")
        lateScript()
        // The run's own environment is built before the release; the probe's (after it) throws
        every { EnvironmentBuilder.build(any<Context>()) } answers {
            check(!releaseFile.exists()) { "filesDir gone" }
            env()
        }
        val (bridge, web) = cancelAfterInstall()
        releaseFile.writeText("go")
        assertCancelled(finalEvent(web))
        assertFalse(probeLog.exists(), "the check ran although its environment threw")
        assertFalse(ToolInstallGuard.isRunning())
        assertNull(RunLease.owner(), "the lease outlived a check that threw")
        assertEquals("cancelled", bridge.toolState()["phase"])
    }

    @Test
    fun `cancel, tool NOT on disk, interrupted - cancelled without a check`() {
        fakeTool("exit 0")
        lateScript(installs = false)
        val (_, web) = cancelAfterInstall()
        releaseFile.writeText("go")
        assertCancelled(finalEvent(web))
        assertFalse(probeLog.exists(), "a tool that is not on disk was checked")
    }

    @Test
    fun `a tool with no check of its own (playwright) stays cancelled although its files are there`() {
        lateScript(
            id = "playwright",
            what =
                "mkdir -p \"${'$'}PREFIX/lib/node_modules/playwright-core\" && " +
                    ": > \"${'$'}PREFIX/lib/node_modules/playwright-core/package.json\"",
        )
        val (bridge, web) = cancelAfterInstall("playwright")
        releaseFile.writeText("go")
        val end = finalEvent(web)
        assertEquals("cancelled", end.data["phase"], end.toString())
        assertEquals("playwright", end.data["target"])
        assertTrue(ToolDetection.isInstalled("playwright", prefix, home), "the fake did not install playwright")
        assertEquals("cancelled", bridge.toolState()["phase"])
    }

    @Test
    fun `a cancel that was never delivered and an install that ended ok - done with CANCEL_TOO_LATE, no check`() {
        fakeTool("exit 0")
        lateScript()
        val (bridge, web) = cancelAfterInstall(deliver = false)
        releaseFile.writeText("go")
        val end = finalEvent(web)
        assertLateCancelDone(bridge, end)
        assertFalse(probeLog.exists(), "a success was checked again")
    }

    @Test
    fun `an install nobody cancelled - done with no reason, no check`() {
        marker()
        fakeTool("exit 0")
        lateScript()
        val (bridge, web) = page()
        bridge.installTool("tmux")
        assertTrue(TestWait.until { installedFlag.exists() })
        releaseFile.writeText("go")
        val end = finalEvent(web)
        assertEquals("done", end.data["phase"])
        assertNull(end.data["reason"], end.toString())
        assertNull(bridge.toolState()["reason"])
        assertFalse(probeLog.exists())
        assertTrue(web.events("tool_progress").none { it.data["reason"] == ToolInstallGuard.CANCEL_TOO_LATE })
    }

    // ── during the check ────────────────────────────────────────────────────

    @Test
    fun `while the check runs the page still sees cancelling and the lease stays held`() {
        val probeRelease = File(home, "probe-release")
        fakeTool("while [ ! -f \"${'$'}HOME/probe-release\" ]; do sleep 0.05; done; exit 0")
        lateScript()
        val (bridge, web) = cancelAfterInstall()
        releaseFile.writeText("go")
        assertTrue(TestWait.until { probeLog.isFile }, "the check never started")
        assertTrue(File(oca, "tools-result.conf").readText().contains("error=interrupted"))
        assertEquals("cancelling", bridge.toolState()["phase"])
        assertTrue(ToolInstallGuard.isRunning())
        assertEquals(RunLease.TOOLS, RunLease.owner(), "another run could start while the check runs")
        assertTrue(web.events("tool_progress").none { it.data["phase"] in FINAL_PHASES }, web.scripts.toString())
        // Another install asked for meanwhile is not started
        bridge.installTool("ttyd")
        assertFalse(calls().contains("--tools-only ttyd"))
        probeRelease.writeText("")
        assertLateCancelDone(bridge, finalEvent(web))
    }

    @Test
    fun `the check waits for a free probe slot (ProbeLimiter) and the run stays cancelling meanwhile`() {
        fakeTool("exit 0")
        lateScript()
        val (bridge, web) = cancelAfterInstall()
        runBlocking { repeat(ProbeLimiter.MAX_CONCURRENT) { ProbeLimiter.semaphore.acquire() } }
        var held = ProbeLimiter.MAX_CONCURRENT
        try {
            releaseFile.writeText("go")
            val result = File(oca, "tools-result.conf")
            assertTrue(TestWait.until { result.isFile && result.readText().contains("error=interrupted") })
            Thread.sleep(NEGATIVE_WAIT_MS)
            assertFalse(probeLog.exists(), "the check ran without a probe slot")
            assertEquals("cancelling", bridge.toolState()["phase"])
            ProbeLimiter.semaphore.release()
            held--
            assertLateCancelDone(bridge, finalEvent(web))
            assertEquals(listOf("-V"), probeLog.readLines())
        } finally {
            repeat(held) { ProbeLimiter.semaphore.release() }
        }
        assertEquals(ProbeLimiter.MAX_CONCURRENT, ProbeLimiter.semaphore.availablePermits, "the check kept its slot")
    }

    // ── what is remembered and listed afterwards ────────────────────────────

    @Test
    fun `a late cancel clears the tool's remembered broken end, and the list shows it installed and not broken`() {
        appFilesDir.mkdirs()
        outcomesFile.writeText("tmux=VERIFY_FAILED\n")
        fakeTool("exit 0")
        lateScript()
        val (bridge, web) = cancelAfterInstall()
        releaseFile.writeText("go")
        assertLateCancelDone(bridge, finalEvent(web))
        assertFalse(outcomesFile.readText().contains("tmux"), outcomesFile.readText())

        @Suppress("UNCHECKED_CAST")
        val tools = Gson().fromJson(bridge.getInstalledTools(), List::class.java) as List<Map<String, Any?>>
        val tmux = tools.single { it["id"] == "tmux" }
        assertEquals(false, tmux["broken"], tools.toString())
    }

    @Test
    fun `a cancel that really interrupted keeps the remembered broken end as it was`() {
        appFilesDir.mkdirs()
        outcomesFile.writeText("tmux=VERIFY_FAILED\n")
        fakeTool("exit 1")
        lateScript()
        val (_, web) = cancelAfterInstall()
        releaseFile.writeText("go")
        assertCancelled(finalEvent(web))
        assertTrue(outcomesFile.readText().contains("tmux=VERIFY_FAILED"), outcomesFile.readText())
    }

    private companion object {
        const val TIMEOUT_WAIT_MS = 20_000L

        /** The foreground step's install: the fake tool copied to `$PREFIX/bin/tmux`, executable. */
        const val INSTALL_TMUX =
            "mkdir -p \"\$PREFIX/bin\" && cp \"\$HOME/fake-tool\" \"\$PREFIX/bin/tmux\" && " +
                "chmod +x \"\$PREFIX/bin/tmux\""
    }
}
