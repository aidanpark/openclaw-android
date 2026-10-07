package com.openclaw.android

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * What [ManagedRunner.run] checks before the script: a known kind, `oa` on the PATH, no updater
 * already running outside the app, and the gateway — stopped first only when the page asked, and
 * only if it is the app's own. A refusal runs nothing, records nothing and starts no guard.
 */
internal class ManagedRunnerPrecheckTest {
    @TempDir
    lateinit var root: File

    private lateinit var w: ManagedRunWorld

    @BeforeEach
    fun setup() {
        ManagedRunWorld.resetShared()
        w = ManagedRunWorld(root)
    }

    @AfterEach
    fun teardown() {
        val ended = w.cleanup()
        ManagedRunWorld.resetShared()
        assertTrue(ended, "a run outlived its test")
    }

    private fun assertRefused(
        reason: UpdateReason,
        kind: String? = RunKinds.UPDATE,
    ) {
        val end = w.finalEvent()
        assertEquals(ManagedRunner.REFUSED, end.data["phase"], w.runEvents().toString())
        assertEquals(reason.name, end.data["reason"])
        assertEquals(kind, end.data["kind"])
        assertEquals(ManagedRunWorld.STATE_KEYS, end.data.keys)
        assertEquals(emptyList<String>(), w.oaCalls(), "the script ran")
        assertFalse(w.lastRunFile.exists(), "a refusal was recorded")
        assertEquals(1, w.runEvents().size, "a refusal sends one event: ${w.runEvents()}")
        // the guard never started: its state is still the reset's
        assertEquals("reset", ManagedRunGuard.runToken)
        assertTrue(TestWait.until { RunLease.owner() == null }, "lease kept: ${RunLease.owner()}")
    }

    /** The app's terminal session [SESSION] with the gateway (its real loader shape) below it, port open. */
    private fun ourGateway() {
        w.gwProc.add(SESSION, 1, listOf("bash"))
        w.gwProc.add(
            GATEWAY,
            SESSION,
            listOf(
                "/usr/glibc/lib/ld-linux-aarch64.so.1",
                "--library-path",
                "/usr/glibc/lib",
                "/home/.openclaw-android/node/bin/node.real",
                "/usr/lib/node_modules/openclaw/openclaw.mjs",
                "gateway",
            ),
        )
        w.sessionPids = listOf(SESSION)
        w.portOpen = true
    }

    private fun gatewayStopsOnSignal() {
        w.onGatewaySignal = { pid, _ ->
            w.gwProc.zombie(pid)
            w.portOpen = false
        }
    }

    // ── kind ────────────────────────────────────────────────────────────────

    @Test
    fun `an unknown kind runs nothing and is refused UNKNOWN without a kind`() {
        w.fakeOa(w.successBody)
        for (kind in listOf("INSTALL", "", "update", "UPDATE\n", "\$(touch pwned)", "`touch pwned`")) {
            w.events.clear()
            assertTrue(RunLease.tryAcquire(kind.ifEmpty { "x" }))
            kotlinx.coroutines.runBlocking { w.runner().run(kind, stopGateway = true) }
            RunLease.release("x")
            assertRefused(UpdateReason.UNKNOWN, kind = null)
        }
        assertFalse(File(w.home, "pwned").exists())
        assertFalse(File(root, "pwned").exists())
        assertEquals(emptyList<Pair<Int, Int>>(), w.gwSignals.toList())
    }

    // ── oa ──────────────────────────────────────────────────────────────────

    @Test
    fun `no oa on the PATH is NOT_INSTALLED and nothing runs`() {
        w.runToEnd(w.runner())
        assertRefused(UpdateReason.NOT_INSTALLED)
    }

    @Test
    fun `an oa that is not executable is NOT_INSTALLED`() {
        w.fakeOa(w.successBody)
        w.oa.setExecutable(false)
        w.runToEnd(w.runner())
        assertRefused(UpdateReason.NOT_INSTALLED)
    }

    // ── an updater outside the app ──────────────────────────────────────────

    @Test
    fun `an update already running in a terminal is BUSY and nothing runs`() {
        w.fakeOa(w.successBody)
        w.runProc.add(4242, 1, listOf("bash", "/data/data/com.termux/files/usr/tmp/update-core.Xy12.sh"))
        w.runToEnd(w.runner())
        assertRefused(UpdateReason.BUSY)
    }

    @Test
    fun `a post-setup run outside the app is BUSY too`() {
        w.fakeOa(w.successBody)
        w.runProc.add(4243, 1, listOf("bash", "/home/.openclaw-android/post-setup.sh", "--tools-only", "tmux"))
        w.runToEnd(w.runner())
        assertRefused(UpdateReason.BUSY)
    }

    @Test
    fun `a lookalike process is not BUSY`() {
        w.fakeOa(w.successBody)
        w.runProc.add(4244, 1, listOf("tail", "-f", "/home/.openclaw-android/update.log"))
        w.runToEnd(w.runner())
        assertEquals("done", w.finalEvent().data["phase"])
    }

    // ── gateway ─────────────────────────────────────────────────────────────

    @Test
    fun `a running gateway without the stop consent is GATEWAY_RUNNING, not stopped, nothing runs`() {
        w.fakeOa(w.successBody)
        ourGateway()
        w.runToEnd(w.runner(), stopGateway = false)
        assertRefused(UpdateReason.GATEWAY_RUNNING)
        assertEquals(emptyList<Pair<Int, Int>>(), w.gwSignals.toList())
        assertEquals(emptyList<EmittedEvent>(), w.gatewayEvents())
    }

    @Test
    fun `with the consent our gateway gets SIGTERM, the gateway events come first, then the update runs`() {
        w.fakeOa(w.successBody)
        ourGateway()
        gatewayStopsOnSignal()
        w.runToEnd(w.runner(), stopGateway = true)
        assertEquals("done", w.finalEvent().data["phase"])
        assertEquals(listOf(GATEWAY to RunSignal.SIGTERM), w.gwSignals.toList())
        assertEquals(listOf("--update"), w.oaCalls())
        val types = w.events.map { it.type }
        assertEquals(listOf("gateway_state", "gateway_state"), types.take(2), types.toString())
        val (stopping, done) = w.gatewayEvents()
        assertEquals(mapOf("phase" to "stopping", "running" to true), stopping.data)
        assertEquals(mapOf("phase" to "done", "result" to "STOPPED", "running" to false), done.data)
    }

    @Test
    fun `with the consent a gateway that does not stop is GATEWAY_STOP_FAILED, never force-killed, nothing runs`() {
        w.fakeOa(w.successBody)
        ourGateway()
        w.runToEnd(w.runner(), stopGateway = true)
        assertRefused(UpdateReason.GATEWAY_STOP_FAILED)
        assertEquals(listOf(GATEWAY to RunSignal.SIGTERM), w.gwSignals.toList())
        assertTrue(w.gwSignals.none { it.second == RunSignal.SIGKILL })
        assertEquals(GatewayControl.STOP_WAIT_MS, w.gwSlept.get(), "the wait for the port is not bounded")
        assertEquals("STILL_RUNNING", w.gatewayEvents().last().data["result"])
    }

    @Test
    fun `with the consent a gateway started elsewhere is GATEWAY_RUNNING and never signalled`() {
        w.fakeOa(w.successBody)
        w.gwProc.add(3000, 1, listOf("bash"))
        w.gwProc.add(3001, 3000, listOf("openclaw", "gateway"))
        w.sessionPids = listOf(SESSION)
        w.gwProc.add(SESSION, 1, listOf("bash"))
        w.portOpen = true
        w.runToEnd(w.runner(), stopGateway = true)
        assertRefused(UpdateReason.GATEWAY_RUNNING)
        assertEquals(emptyList<Pair<Int, Int>>(), w.gwSignals.toList())
        assertEquals("NOT_OURS", w.gatewayEvents().last().data["result"])
    }

    /**
     * An open port with no gateway process this app can see is another app's gateway (another uid):
     * the app cannot stop it and the script (`pgrep -f`, the same view of /proc) does not refuse
     * because of it, so the update goes ahead, with or without the consent, and nothing is stopped.
     */
    @Test
    fun `an open port with no gateway process does not hold the update back, with or without the consent`() {
        for (consent in listOf(false, true)) {
            ManagedRunWorld.resetShared()
            w.events.clear()
            // the previous round's fake oa registered itself: it is not an updater running elsewhere
            w.runProc.dir
                .listFiles()
                ?.forEach { it.deleteRecursively() }
            w.fakeOa(w.successBody)
            w.portOpen = true
            w.runToEnd(w.runner(), stopGateway = consent)
            assertEquals("done", w.finalEvent().data["phase"], "consent=$consent: ${w.runEvents()}")
            assertEquals(emptyList<EmittedEvent>(), w.gatewayEvents(), "consent=$consent")
            assertEquals(emptyList<Pair<Int, Int>>(), w.gwSignals.toList())
        }
        assertEquals(listOf("--update", "--update"), w.oaCalls())
    }

    @Test
    fun `with the consent our gateway stops while another app's gateway keeps the port - the update runs`() {
        w.fakeOa(w.successBody)
        ourGateway()
        w.onGatewaySignal = { pid, _ -> w.gwProc.zombie(pid) } // the port stays open
        w.runToEnd(w.runner(), stopGateway = true)
        assertEquals("done", w.finalEvent().data["phase"], w.runEvents().toString())
        assertEquals(listOf(GATEWAY to RunSignal.SIGTERM), w.gwSignals.toList())
        assertEquals("STOPPED", w.gatewayEvents().last().data["result"])
        assertEquals(0L, w.gwSlept.get(), "waited for a port no gateway of this app holds")
    }

    /**
     * Our gateway is stopped, but a gateway of the same uid that the app did not start (a Termux
     * shell) is still running: the script would refuse, so the app refuses first, having checked
     * the status again after the stop.
     */
    @Test
    fun `with the consent our gateway stops but a gateway the app does not own still runs - GATEWAY_RUNNING`() {
        w.fakeOa(w.successBody)
        ourGateway()
        w.gwProc.add(3000, 1, listOf("bash"))
        w.gwProc.add(3001, 3000, listOf("openclaw", "gateway"))
        w.onGatewaySignal = { pid, _ -> w.gwProc.zombie(pid) }
        w.runToEnd(w.runner(), stopGateway = true)
        assertRefused(UpdateReason.GATEWAY_RUNNING)
        assertEquals(listOf(GATEWAY to RunSignal.SIGTERM), w.gwSignals.toList(), "the other gateway was signalled")
        assertEquals("STOPPED", w.gatewayEvents().last().data["result"])
    }

    @Test
    fun `no gateway and the consent given - nothing is stopped and the update runs`() {
        w.fakeOa(w.successBody)
        w.runToEnd(w.runner(), stopGateway = true)
        assertEquals("done", w.finalEvent().data["phase"])
        assertEquals(emptyList<EmittedEvent>(), w.gatewayEvents())
    }

    // ── gateway stop asked by the page ──────────────────────────────────────

    @Test
    fun `stopGatewayAndReport answers stopping then done with the result`() {
        ourGateway()
        gatewayStopsOnSignal()
        val runner = w.runner()
        assertEquals(GatewayControl.StopResult.STOPPED, runner.stopGatewayAndReport(force = false))
        assertEquals(listOf(GATEWAY to RunSignal.SIGTERM), w.gwSignals.toList())
        assertEquals(listOf("stopping", "done"), w.gatewayEvents().map { it.data["phase"] })
        assertEquals(mapOf("phase" to "done", "result" to "STOPPED", "running" to false), w.gatewayEvents().last().data)
        assertNull(w.runEvents().firstOrNull())
    }

    @Test
    fun `a forced stop the page asks for first is lowered to SIGTERM`() {
        ourGateway()
        gatewayStopsOnSignal()
        assertEquals(GatewayControl.StopResult.STOPPED, w.runner().stopGatewayAndReport(force = true))
        assertEquals(listOf(GATEWAY to RunSignal.SIGTERM), w.gwSignals.toList())
    }

    @Test
    fun `a forced stop after the plain stop answered STILL_RUNNING is passed through as SIGKILL`() {
        ourGateway()
        val runner = w.runner()
        assertEquals(GatewayControl.StopResult.STILL_RUNNING, runner.stopGatewayAndReport(force = false))
        assertEquals("STILL_RUNNING", w.gatewayEvents().last().data["result"])
        gatewayStopsOnSignal()
        assertEquals(GatewayControl.StopResult.STOPPED, runner.stopGatewayAndReport(force = true))
        assertEquals(listOf(GATEWAY to RunSignal.SIGTERM, GATEWAY to RunSignal.SIGKILL), w.gwSignals.toList())
    }

    @Test
    fun `a forced stop more than five minutes after STILL_RUNNING is lowered to SIGTERM`() {
        ourGateway()
        val runner = w.runner()
        runner.stopGatewayAndReport(force = false)
        w.gwClockMs.addAndGet(GatewayControl.FORCE_VALID_MS + 1)
        runner.stopGatewayAndReport(force = true)
        assertEquals(listOf(GATEWAY to RunSignal.SIGTERM, GATEWAY to RunSignal.SIGTERM), w.gwSignals.toList())
    }

    /** The update's own stop (STILL_RUNNING → GATEWAY_STOP_FAILED) counts as the plain stop a force needs. */
    @Test
    fun `after an update refused GATEWAY_STOP_FAILED the page's forced stop kills on the same runner`() {
        w.fakeOa(w.successBody)
        ourGateway()
        val runner = w.runner()
        w.runToEnd(runner, stopGateway = true)
        assertRefused(UpdateReason.GATEWAY_STOP_FAILED)
        gatewayStopsOnSignal()
        assertEquals(GatewayControl.StopResult.STOPPED, runner.stopGatewayAndReport(force = true))
        assertEquals(RunSignal.SIGKILL, w.gwSignals.last().second)
    }

    private companion object {
        const val SESSION = 7000
        const val GATEWAY = 7002
    }
}
