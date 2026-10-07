package com.openclaw.android

import com.google.gson.Gson
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

/**
 * The bridge side of managed runs: the kind allow-list, the [RunLease] shared with `installTool`
 * (an update and a tool install never overlap, in either order or racing), the state and record
 * getters, and the gateway calls. The page's runner is swapped for one wired to a fake `oa`
 * ([ManagedRunWorld]) where a test needs the script to run; the bridge's own runner is used where
 * nothing outside the app is reached.
 */
internal class JsBridgeManagedRunTest : JsBridgeToolInstallFixture() {
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

    private fun nonNullStateKeys() =
        ManagedRunner.stateEvent(ManagedRunGuard.snapshot()).filterValues { it != null }.keys

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

    // ── kind allow-list ─────────────────────────────────────────────────────

    @Test
    fun `startRun with a kind that is not allowed runs nothing and takes no lease`() {
        w.fakeOa(w.successBody)
        val (bridge, web) = page()
        bridge.useWorld(web)
        val hostile =
            listOf(
                "",
                "update",
                "INSTALL",
                "TOOLS",
                "UPDATE ",
                "UPDATE\n",
                "\$(touch ${root.path}/pwned)",
                "`touch ${root.path}/pwned`",
                "UPDATE;id",
            )
        for (kind in hostile) {
            web.clear()
            bridge.startRun(kind, true)
            val events = web.events("run_progress")
            assertEquals(1, events.size, "$kind: ${web.scripts}")
            assertEquals("refused", events.single().data["phase"])
            assertEquals("UNKNOWN", events.single().data["reason"])
            assertNull(events.single().data["kind"])
            assertNull(RunLease.owner(), "$kind took the lease")
        }
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertEquals(emptyList<String>(), w.oaCalls())
        assertFalse(File(root, "pwned").exists())
        assertEquals(emptyList<Pair<Int, Int>>(), w.gwSignals.toList())
    }

    // ── the bridge's own runner (no script reached) ─────────────────────────

    @Test
    fun `without oa on the app's PATH the update is refused NOT_INSTALLED, nothing recorded, lease freed`() {
        val (bridge, web) = page()
        bridge.startRun("UPDATE", false)
        val end = runEnd(web)
        assertEquals("refused", end.data["phase"])
        assertEquals("NOT_INSTALLED", end.data["reason"])
        assertEquals("UPDATE", end.data["kind"])
        assertFalse(File(appFilesDir, "last-run.conf").exists())
        assertEquals("{}", bridge.getLastRun())
    }

    @Test
    fun `getLastRun reads last-run conf from the app's files dir`() {
        RunOutcomeStore(File(appFilesDir, "last-run.conf"))
            .record("UPDATE", 77, RunVerdict.Failure(UpdateReason.NO_SPACE, 1, "Not enough free storage", 0))
        val (bridge, _) = page()
        val last = json(bridge.getLastRun())

        @Suppress("UNCHECKED_CAST")
        val update = last.getValue("UPDATE") as Map<String, Any?>
        assertEquals(setOf("at", "verdict", "reason", "exit", "detail", "warnings"), update.keys)
        assertEquals("failure", update["verdict"])
        assertEquals("NO_SPACE", update["reason"])
        assertEquals(77.0, update["at"])
    }

    @Test
    fun `getRunState has the shape of run_progress, idle or ended`() {
        val (bridge, _) = page()
        // Gson leaves out null values on both paths (events and getters): compare the non-null keys
        assertEquals(nonNullStateKeys(), json(bridge.getRunState()).keys)
    }

    // ── against the fake oa ─────────────────────────────────────────────────

    @Test
    fun `a full update through the bridge is done, getRunState matches the events while running and after`() {
        w.fakeOa("step 1 \"Pre-flight Check\"\necho waiting\nhold release\n${w.successBody}")
        val (bridge, web) = page()
        bridge.useWorld(web)
        bridge.startRun("UPDATE", false)
        assertTrue(TestWait.until { ManagedRunGuard.snapshot().message == "waiting" })
        val running = json(bridge.getRunState())
        assertEquals(nonNullStateKeys(), running.keys)
        val lastEvent = web.events("run_progress").last()
        assertEquals(lastEvent.data.keys, running.keys, "getRunState and run_progress differ in shape")
        assertEquals("running", running["phase"])
        assertEquals(1.0, running["stage"])
        assertEquals(true, running["cancelable"])
        w.release("release")
        val end = runEnd(web)
        assertEquals("done", end.data["phase"])
        assertEquals(end.data, json(bridge.getRunState()))
        assertEquals("success", (json(bridge.getLastRun())["UPDATE"] as Map<*, *>)["verdict"])
    }

    @Test
    fun `a success with the gateway warning - getRunState and getLastRun carry the sentence as printed`() {
        val sentence = ManagedRunWarningEndToEndTest.GATEWAY_WARN_SENTENCE
        w.fakeOa(
            "step 1 \"Pre-flight Check\"\nstep 4 \"Update Platform\"\n" +
                "echo -e \"\${YELLOW}[WARN]\${NC} $sentence\"\n${w.successBody}",
        )
        val (bridge, web) = page()
        bridge.useWorld(web)
        bridge.startRun("UPDATE", false)
        val end = runEnd(web)
        assertEquals("done", end.data["phase"])
        assertEquals(sentence, end.data["detail"])
        val state = json(bridge.getRunState())
        assertEquals(end.data, state)
        assertEquals(sentence, state["detail"])

        @Suppress("UNCHECKED_CAST")
        val update = json(bridge.getLastRun()).getValue("UPDATE") as Map<String, Any?>
        assertEquals(setOf("at", "verdict", "exit", "detail", "warnings"), update.keys, update.toString())
        assertEquals("success", update["verdict"])
        assertEquals(0.0, update["exit"])
        assertEquals(1.0, update["warnings"])
        assertEquals(sentence, update["detail"])
    }

    @Test
    fun `cancelRun through the bridge at stage 2 ends the run cancelled`() {
        w.fakeOa(
            "step 1 \"Pre-flight Check\"\nstep 2 \"Download Latest Release (tarball)\"\n" +
                "echo dl\nhold to3\n${w.successBody}",
        )
        val (bridge, web) = page()
        bridge.useWorld(web)
        bridge.startRun("UPDATE", false)
        assertTrue(TestWait.until { ManagedRunGuard.snapshot().message == "dl" })
        bridge.cancelRun()
        val end = runEnd(web)
        assertEquals("cancelled", end.data["phase"])
        assertEquals("CANCELLED", end.data["reason"])
        assertEquals(1, w.runSignals.size)
    }

    @Test
    fun `after a cancel getLastRun says failure CANCELLED and getRunState says cancelled CANCELLED`() {
        w.fakeOa(
            "step 1 \"Pre-flight Check\"\nstep 2 \"Download Latest Release (tarball)\"\n" +
                "echo dl\nhold to3\n${w.successBody}",
        )
        val (bridge, web) = page()
        bridge.useWorld(web)
        bridge.startRun("UPDATE", false)
        assertTrue(TestWait.until { ManagedRunGuard.snapshot().message == "dl" })
        bridge.cancelRun()
        runEnd(web)

        @Suppress("UNCHECKED_CAST")
        val update = json(bridge.getLastRun()).getValue("UPDATE") as Map<String, Any?>
        assertEquals(setOf("at", "verdict", "reason", "exit", "warnings"), update.keys, update.toString())
        assertEquals("failure", update["verdict"])
        assertEquals("CANCELLED", update["reason"])
        assertEquals(143.0, update["exit"])
        val state = json(bridge.getRunState())
        assertEquals("cancelled", state["phase"])
        assertEquals("CANCELLED", state["reason"])
    }

    @Test
    fun `gateway status and stop through the bridge`() {
        w.gwProc.add(SESSION, 1, listOf("bash"))
        w.gwProc.add(GATEWAY, SESSION, listOf("openclaw", "gateway"))
        w.sessionPids = listOf(SESSION)
        w.portOpen = true
        w.onGatewaySignal = { pid, _ ->
            w.gwProc.zombie(pid)
            w.portOpen = false
        }
        val (bridge, web) = page()
        bridge.useWorld(web)
        assertEquals(
            mapOf("running" to true, "ours" to true, "pids" to listOf(GATEWAY.toDouble())),
            json(bridge.getGatewayStatus()),
        )
        bridge.stopGateway(false)
        assertTrue(
            TestWait.until { web.events("gateway_state").any { it.data["phase"] == "done" } },
            web.scripts.toString(),
        )
        assertEquals("STOPPED", web.events("gateway_state").last().data["result"])
        assertEquals(listOf(GATEWAY to RunSignal.SIGTERM), w.gwSignals.toList())
        assertEquals(
            mapOf("running" to false, "ours" to false, "pids" to emptyList<Double>()),
            json(bridge.getGatewayStatus()),
        )
    }

    // ── the lease shared with tool installs ─────────────────────────────────

    @Test
    fun `while a tool install runs, startRun is refused BUSY and oa never runs`() {
        marker()
        blockingScript()
        w.fakeOa(w.successBody)
        val (bridge, web) = page()
        bridge.useWorld(web)
        bridge.installTool("tmux")
        awaitRealCall()
        bridge.startRun("UPDATE", true)
        val refused = web.events("run_progress").single()
        assertEquals("refused", refused.data["phase"])
        assertEquals("BUSY", refused.data["reason"])
        assertEquals(RunLease.TOOLS, RunLease.owner())
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertEquals(emptyList<String>(), w.oaCalls())
        releaseFile.writeText("go")
        assertEquals("done", finalEvent(web).data["phase"])
        assertTrue(TestWait.until { RunLease.owner() == null }, "the tool install kept the lease")
    }

    @Test
    fun `while an update runs, installTool is refused BUSY on tool_progress and its script never runs`() {
        marker()
        fakeScript("echo would-install; exit 0")
        w.fakeOa("echo waiting\nhold release\n${w.successBody}")
        val (bridge, web) = page()
        bridge.useWorld(web)
        bridge.startRun("UPDATE", false)
        assertTrue(TestWait.until { ManagedRunGuard.snapshot().message == "waiting" })
        bridge.installTool("tmux")
        val tool = web.events("tool_progress")
        assertEquals(1, tool.size, web.scripts.toString())
        assertEquals("failed", tool.single().data["phase"])
        assertEquals("BUSY", tool.single().data["reason"])
        assertEquals("tmux", tool.single().data["target"])
        assertFalse(ToolInstallGuard.isRunning())
        assertEquals(RunKinds.UPDATE, RunLease.owner(), "the refused install released the update's lease")
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertEquals(emptyList<String>(), calls(), "the tool script ran during the update")
        w.release("release")
        assertEquals("done", runEnd(web).data["phase"])
    }

    @Test
    fun `installTool and startRun racing - exactly one starts, every round`() {
        marker()
        blockingScript()
        w.fakeOa("echo waiting\nhold release\nexit 0", register = false)
        val (bridge, web) = page()
        bridge.useWorld(web)
        repeat(ROUNDS) { round ->
            web.clear()
            val barrier = CyclicBarrier(2)
            val a = Thread { barrier.await(5, TimeUnit.SECONDS).also { bridge.installTool("tmux") } }
            val b = Thread { barrier.await(5, TimeUnit.SECONDS).also { bridge.startRun("UPDATE", false) } }
            a.start()
            b.start()
            a.join()
            b.join()
            val owner = RunLease.owner()
            if (owner == RunLease.TOOLS) {
                val refused = web.events("run_progress").single()
                assertEquals("BUSY", refused.data["reason"], "round $round")
                assertTrue(TestWait.until { calls().count { it == "--tools-only tmux" } == round + 1 - updates })
            } else {
                assertEquals(RunKinds.UPDATE, owner, "round $round: nobody holds the lease")
                assertEquals("BUSY", web.events("tool_progress").single().data["reason"], "round $round")
                updates++
                assertTrue(TestWait.until { w.oaCalls().size == updates })
            }
            releaseFile.writeText("go")
            w.release("release")
            // the winner's end event must be in before the next round clears the page
            if (owner == RunLease.TOOLS) finalEvent(web) else runEnd(web)
            assertTrue(TestWait.until { RunLease.owner() == null }, "round $round: lease kept by ${RunLease.owner()}")
            releaseFile.delete()
            File(w.home, "release").delete()
        }
        assertEquals(ROUNDS, updates + calls().count { it == "--tools-only tmux" })
    }

    private var updates = 0

    /**
     * The lease is taken on the caller thread and released only inside [ManagedRunner.run]. If
     * anything between the two throws — here the lazy creation of the page's runner, which reads
     * `filesDir` — the lease is never released: every later update AND tool install says BUSY
     * until the app process dies.
     */
    @Test
    fun `a runner that cannot be created does not keep the lease`() {
        val (bridge, web) = page { io.mockk.every { filesDir } throws IllegalStateException("no files dir") }
        bridge.startRun("UPDATE", false)
        assertTrue(TestWait.until { web.events("run_progress").isNotEmpty() }, web.scripts.toString())
        assertTrue(TestWait.until { RunLease.owner() == null }, "lease kept by ${RunLease.owner()} for good")
    }

    /**
     * The runner is built before the lease is taken: a failed build answers at once with a refusal
     * the page can show (phase `refused`, reason UNKNOWN) and never takes the lease — not even for
     * a moment, so a tool install started at the same time is not refused BUSY because of it.
     */
    @Test
    fun `a runner that cannot be created is refused UNKNOWN at once and the lease is never taken`() {
        val (bridge, web) = page { io.mockk.every { filesDir } throws IllegalStateException("no files dir") }
        val owners = mutableListOf<String?>()
        // RunLease is an object: watch it from a thread for the whole call
        val watching =
            java.util.concurrent.atomic
                .AtomicBoolean(true)
        val watcher =
            Thread {
                while (watching.get()) {
                    RunLease.owner()?.let { synchronized(owners) { owners += it } }
                }
            }.apply { start() }
        repeat(3) { bridge.startRun("UPDATE", true) }
        watching.set(false)
        watcher.join()
        val events = web.events("run_progress")
        assertEquals(3, events.size, web.scripts.toString())
        events.forEach {
            assertEquals("refused", it.data["phase"])
            assertEquals("UNKNOWN", it.data["reason"])
            assertTrue(ManagedRunWorld.STATE_KEYS.containsAll(it.data.keys), it.data.toString())
        }
        assertNull(RunLease.owner())
        assertEquals(emptyList<String?>(), synchronized(owners) { owners.toList() }, "the lease was taken")
        assertFalse(ManagedRunGuard.isRunning())
        assertEquals(emptyList<String>(), w.oaCalls())
    }

    private companion object {
        const val SESSION = 8000
        const val GATEWAY = 8001
        const val ROUNDS = 20
    }
}
