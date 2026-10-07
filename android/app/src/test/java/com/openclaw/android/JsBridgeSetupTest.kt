package com.openclaw.android

import com.google.gson.Gson
import com.termux.terminal.TerminalSession
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The bridge side of the first install's managed SETUP: `startRun("SETUP")` (allowed kind, the
 * shared [RunLease] with updates and tool installs), `getSetupResult()`'s JSON as the page gets it,
 * `showTerminal`'s automatic `bash <script>` (typed only for an older script, never while a managed
 * run holds the lease or the guard, never after the marker), and the onboarding command (typed
 * without Enter).
 */
internal class JsBridgeSetupTest : JsBridgeToolInstallFixture() {
    private lateinit var s: SetupWorld

    @BeforeEach
    fun setupWorld() {
        ManagedRunWorld.resetShared()
        s = SetupWorld(File(root, "world").apply { mkdirs() })
    }

    @AfterEach
    fun teardownWorld() {
        val ended = s.cleanup()
        ManagedRunWorld.resetShared()
        assertTrue(ended, "a managed run outlived its test")
    }

    private class SetupPage(
        val bridge: JsBridge,
        val web: RecordingWebView,
        val sessions: TerminalSessionManager,
        val session: TerminalSession,
        val bootstrap: BootstrapManager,
        val activity: MainActivity,
    )

    /** A page whose session manager hands out [session] and whose bootstrap says what the test sets. */
    private fun setupPage(
        capable: Boolean = true,
        marker: Boolean = false,
        hasSession: Boolean = false,
    ): SetupPage {
        val web = RecordingWebView()
        pages.add(web)
        val session = mockk<TerminalSession>(relaxed = true)
        val sessions = mockk<TerminalSessionManager>(relaxed = true)
        every { sessions.activeSession } returns (if (hasSession) session else null)
        every { sessions.createSession() } returns session
        every { sessions.writeWhenReady(session, any(), any()) } answers {
            val text = secondArg<String>()
            val stillWanted = thirdArg<() -> Boolean>()
            pendingWrites.add { if (stillWanted()) session.write(text) }
            true
        }
        val bootstrap = mockk<BootstrapManager>(relaxed = true)
        every { bootstrap.prefixDir } returns prefix
        every { bootstrap.homeDir } returns s.home
        every { bootstrap.postSetupScript } returns s.script
        every { bootstrap.needsPostSetup() } returns true
        every { bootstrap.setupScriptCapable() } returns capable
        every { bootstrap.setupMarkerPresent() } returns marker
        val activity = mockk<MainActivity>(relaxed = true)
        every { activity.filesDir } returns appFilesDir
        val bridge = JsBridge(activity, sessions, bootstrap, EventBridge(web.view))
        return SetupPage(bridge, web, sessions, session, bootstrap, activity)
    }

    private fun JsBridge.useSetupWorld(web: RecordingWebView): JsBridge {
        val bridge = EventBridge(web.view)
        val runner = s.runner(emit = { type, data -> bridge.emit(type, data) })
        val field = JsBridge::class.java.getDeclaredField("runs\$delegate")
        field.isAccessible = true
        field.set(this, lazyOf(runner))
        return this
    }

    @Suppress("UNCHECKED_CAST")
    private fun json(text: String): Map<String, Any?> = Gson().fromJson(text, Map::class.java) as Map<String, Any?>

    private fun runEnd(web: RecordingWebView): EmittedEvent {
        assertTrue(
            TestWait.until(ManagedRunWorld.END_WAIT_MS) {
                web.events("run_progress").any { it.data["phase"] in ManagedRunWorld.END_PHASES }
            },
            "no end event: ${web.scripts}",
        )
        assertTrue(TestWait.until { !ManagedRunGuard.isRunning() && RunLease.owner() == null })
        return web.events("run_progress").last { it.data["phase"] in ManagedRunWorld.END_PHASES }
    }

    // ── showTerminal: the automatic `bash <script>` ─────────────────────────

    /** Writes handed to `sessions.writeWhenReady`, waiting for the shell to start (see [setupPage]). */
    private val pendingWrites = mutableListOf<() -> Unit>()

    /**
     * Runs [block] with the session's shell not started yet: what the bridge hands to
     * `writeWhenReady` waits until the test starts the shell ([runDelayed] — writes each text whose
     * check still says yes, like TerminalInputQueue), so a state change between the click and the
     * write can be placed in between.
     */
    private fun withShellStart(block: (runDelayed: () -> Unit) -> Unit) {
        pendingWrites.clear()
        block {
            pendingWrites.toList().forEach { it() }
            pendingWrites.clear()
        }
    }

    @Test
    fun `showTerminal types bash script only for an older script, no marker and no managed run - all eight rows`() {
        val rows =
            listOf(
                Triple(false, false, false) to true,
                Triple(false, false, true) to false,
                Triple(false, true, false) to false,
                Triple(false, true, true) to false,
                Triple(true, false, false) to false,
                Triple(true, false, true) to false,
                Triple(true, true, false) to false,
                Triple(true, true, true) to false,
            )
        for ((input, typed) in rows) {
            val (capable, marker, active) = input
            withShellStart { runDelayed ->
                val p = setupPage(capable = capable, marker = marker)
                if (active) assertTrue(RunLease.tryAcquire(RunLease.TOOLS))
                p.bridge.showTerminal()
                runDelayed()
                val expected = if (typed) 1 else 0
                verify(exactly = expected) { p.session.write("bash ${s.script.absolutePath}\n") }
                verify(exactly = 0) { p.session.write(match<String> { it != "bash ${s.script.absolutePath}\n" }) }
                verify(exactly = 1) { p.activity.showTerminal() }
                if (active) RunLease.release(RunLease.TOOLS)
            }
        }
    }

    @Test
    fun `a managed run that started between the click and the delayed write keeps the terminal empty`() {
        for (holder in listOf(RunKinds.SETUP, RunKinds.UPDATE, RunLease.TOOLS)) {
            withShellStart { runDelayed ->
                val p = setupPage(capable = false)
                p.bridge.showTerminal()
                assertTrue(RunLease.tryAcquire(holder))
                runDelayed()
                verify(exactly = 0) { p.session.write(any<String>()) }
                RunLease.release(holder)
            }
        }
        // the guard alone (no lease) counts too
        withShellStart { runDelayed ->
            val p = setupPage(capable = false)
            p.bridge.showTerminal()
            assertTrue(ManagedRunGuard.tryStart(RunKinds.SETUP, 0, "t"))
            runDelayed()
            verify(exactly = 0) { p.session.write(any<String>()) }
            ManagedRunGuard.finish(RunVerdict.Success(0, 0))
        }
    }

    @Test
    fun `the marker written between the click and the delayed write keeps the terminal empty`() {
        withShellStart { runDelayed ->
            val p = setupPage(capable = false, marker = false)
            p.bridge.showTerminal()
            every { p.bootstrap.setupMarkerPresent() } returns true
            runDelayed()
            verify(exactly = 0) { p.session.write(any<String>()) }
        }
    }

    @Test
    fun `with a session already open or a finished setup nothing is created or typed`() {
        withShellStart { runDelayed ->
            val p = setupPage(capable = false, hasSession = true)
            p.bridge.showTerminal()
            runDelayed()
            verify(exactly = 0) { p.sessions.createSession() }
            verify(exactly = 0) { p.session.write(any<String>()) }
        }
        withShellStart { runDelayed ->
            val p = setupPage(capable = false)
            every { p.bootstrap.needsPostSetup() } returns false
            p.bridge.showTerminal()
            runDelayed()
            verify(exactly = 1) { p.sessions.createSession() }
            verify(exactly = 0) { p.session.write(any<String>()) }
        }
    }

    // ── the onboarding command ──────────────────────────────────────────────

    @Test
    fun `openclawOnboard types openclaw onboard without Enter`() {
        val p = setupPage(hasSession = true)
        p.bridge.writeCommandToTerminal("openclawOnboard")
        verify(exactly = 1) { p.sessions.writeWhenReady(p.session, "openclaw onboard", any()) }
        assertEquals("openclaw onboard", BridgeGuard.terminalCommands["openclawOnboard"])
        BridgeGuard.terminalCommands.values.forEach { assertFalse(it.contains('\n') || it.contains('\r'), it) }
    }

    // ── startRun("SETUP") ───────────────────────────────────────────────────

    @Test
    fun `SETUP is an allowed kind next to UPDATE, nothing else`() {
        assertEquals(setOf(RunKinds.UPDATE, RunKinds.SETUP), BridgeGuard.runKinds)
        val p = setupPage()
        p.bridge.useSetupWorld(p.web)
        for (kind in listOf("setup", "SETUP ", "Setup", "SETUP\n", "TOOLS")) {
            p.web.clear()
            p.bridge.startRun(kind, false)
            val e = p.web.events("run_progress").single()
            assertEquals("refused", e.data["phase"], kind)
            assertEquals("UNKNOWN", e.data["reason"], kind)
            assertNull(RunLease.owner(), kind)
        }
    }

    @Test
    fun `startRun SETUP runs the script to the end through the bridge and getRunState carries the setup keys`() {
        s.fakeSetup("busy\nresult\nstage 1 a\nhold go\n${s.successBody}")
        val p = setupPage()
        p.bridge.useSetupWorld(p.web)
        p.bridge.startRun(RunKinds.SETUP, true)
        assertTrue(TestWait.until { ManagedRunGuard.snapshot().stage == 1 })
        val running = json(p.bridge.getRunState())
        assertEquals("SETUP", running["kind"])
        assertEquals("running", running["phase"])
        assertEquals(7.0, running["stageTotal"])
        assertEquals(true, running["cancelable"])
        assertEquals(emptyList<String>(), running["warn"])
        s.release("go")
        val end = runEnd(p.web)
        assertEquals("done", end.data["phase"])
        val last = json(p.bridge.getLastRun())
        assertTrue(last.containsKey("SETUP"), last.toString())
        assertEquals(emptyList<Pair<Int, Int>>(), s.w.gwSignals.toList(), "stopGateway is ignored for SETUP")
    }

    @Test
    fun `startRun SETUP while a tool install or an update holds the lease is BUSY and runs nothing`() {
        s.fakeSetup(s.successBody)
        for (holder in listOf(RunLease.TOOLS, RunKinds.UPDATE)) {
            val p = setupPage()
            p.bridge.useSetupWorld(p.web)
            assertTrue(RunLease.tryAcquire(holder))
            p.bridge.startRun(RunKinds.SETUP, false)
            val e = p.web.events("run_progress").single()
            assertEquals("refused", e.data["phase"], holder)
            assertEquals("BUSY", e.data["reason"], holder)
            assertEquals(holder, RunLease.owner())
            RunLease.release(holder)
        }
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertEquals(emptyList<String>(), s.calls())
    }

    @Test
    fun `an update asked for while SETUP runs shows the SETUP run instead of starting`() {
        s.fakeSetup("busy\nresult\nstage 2 a\nhold go\n${s.successBody}")
        val p = setupPage()
        p.bridge.useSetupWorld(p.web)
        p.bridge.startRun(RunKinds.SETUP, false)
        assertTrue(TestWait.until { ManagedRunGuard.snapshot().stage == 2 })
        p.web.clear()
        p.bridge.startRun(RunKinds.UPDATE, true)
        // the answer is the SETUP run's state (the run's own periodic events may be among them)
        val events = p.web.events("run_progress")
        assertTrue(events.isNotEmpty())
        events.forEach {
            assertEquals("SETUP", it.data["kind"], it.toString())
            assertEquals("running", it.data["phase"], it.toString())
        }
        assertEquals(RunKinds.SETUP, RunLease.owner())
        assertEquals(emptyList<String>(), s.w.oaCalls())
        s.release("go")
        assertEquals("done", runEnd(p.web).data["phase"])
    }

    @Test
    fun `cancelRun through the bridge cancels a SETUP run`() {
        s.fakeSetup("busy\nresult\nstage 4 a\nhold never\n${s.successBody}")
        val p = setupPage()
        p.bridge.useSetupWorld(p.web)
        p.bridge.startRun(RunKinds.SETUP, false)
        assertTrue(TestWait.until { ManagedRunGuard.snapshot().stage == 4 && s.w.registeredPids().isNotEmpty() })
        p.bridge.cancelRun()
        val end = runEnd(p.web)
        assertEquals("cancelled", end.data["phase"])
        assertEquals("CANCELLED", end.data["reason"])
    }

    @Test
    fun `an older script is refused NOT_INSTALLED through the bridge (the page keeps the terminal flow)`() {
        s.fakeSetup(s.oldBody, capable = false)
        val p = setupPage(capable = false)
        p.bridge.useSetupWorld(p.web)
        p.bridge.startRun(RunKinds.SETUP, false)
        val end = runEnd(p.web)
        assertEquals("refused", end.data["phase"])
        assertEquals("NOT_INSTALLED", end.data["reason"])
        assertEquals("SETUP", end.data["kind"])
        assertEquals(emptyList<String>(), s.calls())
    }

    // ── getSetupResult ──────────────────────────────────────────────────────

    @Test
    fun `getSetupResult without a file - present false, managed as the bootstrap says`() {
        val yes = json(setupPage(capable = true).bridge.getSetupResult())
        assertEquals(setOf("present", "warn", "interrupted", "managed"), yes.keys)
        assertEquals(false, yes["present"])
        assertEquals(false, yes["interrupted"])
        assertEquals(true, yes["managed"])
        assertEquals(false, json(setupPage(capable = false).bridge.getSetupResult())["managed"])
    }

    @Test
    fun `getSetupResult of a failed run gives the reason, stage, sizes and exit`() {
        s.result.writeText("schema=1\nrun=9\nstage=1\nerror=free-space\nneed_mb=2000\nhave_mb=77\nexit=1\n")
        val r = json(setupPage().bridge.getSetupResult())
        assertEquals(true, r["present"])
        assertEquals("NO_SPACE", r["reason"])
        assertEquals("1", r["stage"])
        assertEquals(2000.0, r["needMb"])
        assertEquals(77.0, r["haveMb"])
        assertEquals(1.0, r["exit"])
        assertEquals(false, r["interrupted"])
    }

    @Test
    fun `getSetupResult of a run that never wrote its end, with nothing alive, is interrupted`() {
        // the bridge reads the real /proc: on this machine no post-setup.sh runs (macOS has no /proc)
        s.result.writeText("schema=1\nrun=9\nstage=5\n")
        val r = json(setupPage().bridge.getSetupResult())
        assertEquals(true, r["interrupted"])
        assertEquals("INTERRUPTED", r["reason"])
        assertFalse(r.containsKey("exit"))
    }

    @Test
    fun `getSetupResult while the app's own SETUP run goes on is not interrupted`() {
        s.fakeSetup("busy\nresult\nstage 3 a\nhold go\n${s.successBody}")
        val p = setupPage()
        p.bridge.useSetupWorld(p.web)
        p.bridge.startRun(RunKinds.SETUP, false)
        assertTrue(TestWait.until { ManagedRunGuard.snapshot().stage == 3 })
        val r = json(p.bridge.getSetupResult())
        assertEquals(false, r["interrupted"])
        assertEquals("3", r["stage"])
        assertFalse(r.containsKey("reason"))
        s.release("go")
        assertEquals("done", runEnd(p.web).data["phase"])
    }
}
