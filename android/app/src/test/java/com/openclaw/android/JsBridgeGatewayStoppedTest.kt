package com.openclaw.android

import com.google.gson.Gson
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * v0.4.4 S2 through the bridge: `getRunState()` and the `run_progress` end the page reads say
 * `gatewayStopped` for a run whose checks stopped the gateway, and the `run_progress` of a refusal
 * after that stop says it too (getRunState stays as it was); a refusal before any stop — the bridge's
 * own BUSY and UNKNOWN included — says false. `getLastRun()` and `last-run.conf` keep their shape
 * (the flag is not kept across app restarts).
 */
internal class JsBridgeGatewayStoppedTest : JsBridgeToolInstallFixture() {
    private lateinit var w: ManagedRunWorld

    @BeforeEach
    fun setupRuns() {
        ManagedRunWorld.resetShared()
        w = ManagedRunWorld(File(root, "world").apply { mkdirs() })
    }

    @AfterEach
    fun teardownRuns() {
        val ended = w.cleanup()
        ManagedRunWorld.resetShared()
        assertTrue(ended, "a managed run outlived its test")
    }

    /** The page's runner, wired to the fake world but emitting through the page's real EventBridge. */
    private fun JsBridge.useWorld(web: RecordingWebView): JsBridge {
        val bridge = EventBridge(web.view)
        val runner = w.runner(emit = { type, data -> bridge.emit(type, data) })
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

    private fun ourGateway() {
        w.gwProc.add(SESSION, 1, listOf("bash"))
        w.gwProc.add(GATEWAY, SESSION, listOf("openclaw", "gateway"))
        w.sessionPids = listOf(SESSION)
        w.portOpen = true
        w.onGatewaySignal = { pid, _ ->
            w.gwProc.zombie(pid)
            w.portOpen = false
        }
    }

    @Test
    fun `getRunState says false before any run - the key is always there`() {
        val (bridge, _) = page()
        assertEquals(false, json(bridge.getRunState())["gatewayStopped"])
    }

    @Test
    fun `after the checks stopped the gateway the end event and getRunState say true, getLastRun keeps its shape`() {
        w.fakeOa(w.successBody)
        ourGateway()
        val (bridge, web) = page()
        bridge.useWorld(web)
        bridge.startRun("UPDATE", true)
        val end = runEnd(web)
        assertEquals("done", end.data["phase"])
        assertEquals(true, end.data["gatewayStopped"])
        val state = json(bridge.getRunState())
        assertEquals(end.data, state)
        assertEquals(true, state["gatewayStopped"])
        assertTrue(web.events("run_progress").all { it.data["gatewayStopped"] == true }, web.scripts.toString())

        @Suppress("UNCHECKED_CAST")
        val update = json(bridge.getLastRun()).getValue("UPDATE") as Map<String, Any?>
        assertEquals(setOf("at", "verdict", "exit", "warnings"), update.keys, update.toString())
        val line = w.lastRunFile.readText()
        assertTrue(Regex("""^UPDATE\|\d+\|success\|\|0\|0\|$""").matches(line), line)
    }

    @Test
    fun `a refusal through the bridge is false even though a gateway is running`() {
        w.fakeOa(w.successBody)
        ourGateway()
        val (bridge, web) = page()
        bridge.useWorld(web)
        bridge.startRun("UPDATE", false)
        val end = runEnd(web)
        assertEquals("refused", end.data["phase"])
        assertEquals("GATEWAY_RUNNING", end.data["reason"])
        assertEquals(false, end.data["gatewayStopped"])
        assertEquals(false, json(bridge.getRunState())["gatewayStopped"])
    }

    /** Ours stopped, a gateway the app does not own still runs: the page reads the flag on the refusal. */
    @Test
    fun `a refusal after the stop reaches the page with true, getRunState and getLastRun unchanged`() {
        w.fakeOa(w.successBody)
        ourGateway()
        w.gwProc.add(FOREIGN_SHELL, 1, listOf("bash"))
        w.gwProc.add(FOREIGN, FOREIGN_SHELL, listOf("openclaw", "gateway"))
        val (bridge, web) = page()
        bridge.useWorld(web)
        val stateBefore = bridge.getRunState()
        val lastBefore = bridge.getLastRun()
        bridge.startRun("UPDATE", true)
        val end = runEnd(web)
        assertEquals(1, web.events("run_progress").size, web.scripts.toString())
        assertEquals("refused", end.data["phase"])
        assertEquals("GATEWAY_RUNNING", end.data["reason"])
        assertEquals("UPDATE", end.data["kind"])
        assertEquals(true, end.data["gatewayStopped"])
        // the page's JSON leaves out null values (exit): every other key of the state is there
        assertEquals(ManagedRunWorld.STATE_KEYS - "exit", end.data.keys)
        assertEquals("", end.data["message"])
        assertEquals(0.0, end.data["stage"])
        assertEquals("STOPPED", web.events("gateway_state").last().data["result"])
        assertEquals(stateBefore, bridge.getRunState(), "a refusal reached getRunState")
        assertEquals(false, json(bridge.getRunState())["gatewayStopped"])
        assertEquals(lastBefore, bridge.getLastRun())
        assertEquals(emptyList<String>(), w.oaCalls())
    }

    /** The lease is taken (a tool install): BUSY before any check, so nothing was stopped. */
    @Test
    fun `a start refused because the lease is taken carries false and stops no gateway`() {
        w.fakeOa(w.successBody)
        ourGateway()
        val (bridge, web) = page()
        bridge.useWorld(web)
        assertTrue(RunLease.tryAcquire(OTHER_LEASE))
        try {
            bridge.startRun("UPDATE", true)
            val events = web.events("run_progress")
            assertEquals(1, events.size, web.scripts.toString())
            assertEquals("refused", events.single().data["phase"])
            assertEquals("BUSY", events.single().data["reason"])
            assertEquals(false, events.single().data["gatewayStopped"])
            assertEquals(emptyList<EmittedEvent>(), web.events("gateway_state"))
            assertEquals(emptyList<Pair<Int, Int>>(), w.gwSignals.toList())
        } finally {
            RunLease.release(OTHER_LEASE)
        }
    }

    @Test
    fun `an unknown kind refused by the bridge carries false`() {
        val (bridge, web) = page()
        bridge.startRun("SETUP", true)
        val events = web.events("run_progress")
        assertEquals(1, events.size, web.scripts.toString())
        assertEquals(false, events.single().data["gatewayStopped"])
    }

    private companion object {
        const val SESSION = 8100
        const val GATEWAY = 8101
        const val FOREIGN_SHELL = 3200
        const val FOREIGN = 3201
        const val OTHER_LEASE = "TOOL_INSTALL"
    }
}
