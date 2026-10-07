package com.openclaw.android

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * v0.4.4 S2: `gatewayStopped` of `run_progress` and `getRunState()` is true exactly when this run's
 * checks STOPPED a running gateway ([GatewayControl.StopResult.STOPPED]). A run that then started
 * keeps it through its end (done, failed, cancelled) and never passes it to the next run. A refusal
 * AFTER that stop carries it in its event only — exactly two: a gateway the app cannot stop still
 * runs (GATEWAY_RUNNING), or another run took the guard meanwhile (BUSY, sent as a refusal, never as
 * the other run's state); the guard (`getRunState`), `last-run.conf` and [ManagedRunner.lastRuns]
 * stay as they were and `oa` is never called. Every other refusal is false (nothing was stopped).
 * Real runner, guard, gateway control and outcome store over the fake world ([ManagedRunWorld]).
 */
internal class ManagedRunGatewayStoppedTest {
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

    /** The app's terminal session with the gateway below it, port open. */
    private fun ourGateway() {
        w.gwProc.add(SESSION, 1, listOf("bash"))
        w.gwProc.add(GATEWAY, SESSION, listOf("openclaw", "gateway"))
        w.sessionPids = listOf(SESSION)
        w.portOpen = true
    }

    private fun gatewayStopsOnSignal() {
        w.onGatewaySignal = { pid, _ ->
            w.gwProc.zombie(pid)
            w.portOpen = false
        }
    }

    /** A gateway started in a shell the app does not own. */
    private fun foreignGateway() {
        w.gwProc.add(FOREIGN_SHELL, 1, listOf("bash"))
        w.gwProc.add(FOREIGN, FOREIGN_SHELL, listOf("openclaw", "gateway"))
    }

    private fun flags(events: List<EmittedEvent> = w.runEvents()) = events.map { it.data["gatewayStopped"] }

    private fun assertAll(
        expected: Boolean,
        label: String,
    ) {
        val events = w.runEvents()
        assertTrue(events.isNotEmpty(), "$label: no events")
        assertEquals(List(events.size) { expected }, flags(events), "$label: ${events.map { it.data["phase"] }}")
        events.forEach { assertEquals(ManagedRunWorld.STATE_KEYS, it.data.keys, label) }
    }

    private fun assertRefusedWithoutFlag(reason: UpdateReason) {
        val end = w.finalEvent()
        assertEquals(ManagedRunner.REFUSED, end.data["phase"], w.runEvents().toString())
        assertEquals(reason.name, end.data["reason"])
        assertAll(false, "refused $reason")
        // the guard never started: getRunState is still the reset's, without the flag
        assertEquals(false, ManagedRunner.stateEvent(ManagedRunGuard.snapshot())["gatewayStopped"])
    }

    private fun stateFlag() = ManagedRunner.stateEvent(ManagedRunGuard.snapshot())["gatewayStopped"]

    /** What a refusal must leave as it was: the guard (`getRunState`), its run token, the record. */
    private data class Untouched(
        val guard: ManagedRunGuard.State,
        val token: String,
        val record: String?,
        val lastRuns: Map<String, Map<String, Any?>>,
    )

    private fun untouched(runner: ManagedRunner) =
        Untouched(
            ManagedRunGuard.snapshot(),
            ManagedRunGuard.runToken,
            w.lastRunFile.takeIf { it.isFile }?.readText(),
            runner.lastRuns(),
        )

    /** A run before this one, on record: a refusal must neither replace nor remove it. */
    private fun seedRecord() {
        val prior = RunVerdict.Failure(UpdateReason.DOWNLOAD, 1, "", 0)
        RunOutcomeStore(w.lastRunFile).record(RunKinds.UPDATE, PRIOR_AT, prior)
        assertTrue(w.lastRunFile.isFile, "seed record not written")
    }

    /** The one `run_progress` of a refusal, field by field: nothing of a run in it, [flag] as given. */
    private fun refusalPayload(
        reason: UpdateReason,
        flag: Boolean,
    ): Map<String, Any?> =
        mapOf(
            "kind" to RunKinds.UPDATE,
            "phase" to ManagedRunner.REFUSED,
            "stage" to 0,
            "stageTotal" to UPDATE_STAGES,
            "progress" to 0f,
            "message" to "",
            "cancelable" to false,
            "cancelRequested" to false,
            "longRunning" to false,
            "reason" to reason.name,
            "exit" to null,
            "detail" to "",
            "warnings" to 0,
            "gatewayStopped" to flag,
        )

    /** Refused after the checks STOPPED our gateway: one event with the flag, nothing else changed. */
    private fun assertRefusedWithFlag(
        reason: UpdateReason,
        runner: ManagedRunner,
        before: Untouched,
    ) {
        assertTrue(TestWait.until { RunLease.owner() == null }, "lease kept: ${RunLease.owner()}")
        assertEquals("STOPPED", w.gatewayEvents().last().data["result"], w.gatewayEvents().toString())
        val events = w.runEvents()
        assertEquals(1, events.size, "a refusal is one event: $events")
        assertEquals(refusalPayload(reason, flag = true), events.single().data)
        assertEquals(before, untouched(runner), "a refusal changed the guard or the record")
        assertEquals(emptyList<String>(), w.oaCalls(), "oa ran for a refusal")
        assertEquals(emptyList<Int>(), w.registeredPids())
    }

    // ── the checks stopped the gateway: true through the end ────────────────

    @Test
    fun `a run that started after the checks stopped our gateway carries the flag in every event and when done`() {
        w.fakeOa(w.successBody)
        ourGateway()
        gatewayStopsOnSignal()
        w.runToEnd(w.runner(), stopGateway = true)
        assertEquals("done", w.finalEvent().data["phase"])
        assertEquals("STOPPED", w.gatewayEvents().last().data["result"])
        assertAll(true, "done")
        assertEquals(true, stateFlag(), "getRunState after the end")
    }

    @Test
    fun `a failed run after the stop keeps the flag at its end`() {
        w.fakeOa("step 1 \"Pre-flight Check\"\nstep 3 \"Update Core Infrastructure\"\nexit 1")
        ourGateway()
        gatewayStopsOnSignal()
        w.runToEnd(w.runner(), stopGateway = true)
        val end = w.finalEvent()
        assertEquals("failed", end.data["phase"], end.data.toString())
        assertEquals(true, end.data["gatewayStopped"])
        assertEquals(true, stateFlag())
    }

    @Test
    fun `a cancelled run after the stop keeps the flag through cancelling, settle and finish`() {
        w.fakeOa(
            "step 1 \"Pre-flight Check\"\nstep 2 \"Download Latest Release (tarball)\"\n" +
                "echo dl\nhold to3\n${w.successBody}",
        )
        ourGateway()
        gatewayStopsOnSignal()
        val runner = w.runner()
        w.startInBackground(runner, stopGateway = true)
        assertTrue(TestWait.until { ManagedRunGuard.snapshot().message == "dl" }, w.runEvents().toString())
        runner.cancel()
        assertEquals(true, stateFlag(), "cancelling")
        val end = w.finalEvent()
        assertEquals("cancelled", end.data["phase"], w.runEvents().toString())
        assertAll(true, "cancelled")
        assertEquals(true, stateFlag())
    }

    @Test
    fun `the record and lastRuns are the same with or without the flag`() {
        w.fakeOa(w.successBody)
        ourGateway()
        gatewayStopsOnSignal()
        val runner = w.runner()
        w.runToEnd(runner, stopGateway = true)
        assertEquals(true, w.finalEvent().data["gatewayStopped"])
        val lines = w.lastRunFile.readLines()
        assertEquals(1, lines.size, lines.toString())
        assertTrue(Regex("""^UPDATE\|\d+\|success\|\|0\|0\|$""").matches(lines.single()), lines.single())
        assertEquals(setOf("at", "verdict", "exit", "warnings"), runner.lastRuns().getValue("UPDATE").keys)
        assertEquals(1, RunOutcomeStore(w.lastRunFile).load().size)
    }

    // ── nothing was stopped: false ──────────────────────────────────────────

    @Test
    fun `no gateway, with the consent - the run starts with the flag false`() {
        w.fakeOa(w.successBody)
        w.runToEnd(w.runner(), stopGateway = true)
        assertEquals("done", w.finalEvent().data["phase"])
        assertAll(false, "no gateway")
        assertEquals(false, stateFlag())
    }

    /** The gateway was there for the first look and gone when the stop looked again: NOT_RUNNING. */
    @Test
    fun `a gateway gone by the time of the stop (NOT_RUNNING) starts the run with the flag false`() {
        w.fakeOa(w.successBody)
        ourGateway()
        var looks = 0
        val gateway =
            GatewayControl(
                sessionPids = {
                    // the first status() has already seen the gateway; it ends before the stop's own look
                    if (++looks == 1) w.gwProc.remove(GATEWAY)
                    listOf(SESSION)
                },
                scan = ProcScan(w.gwProc.dir),
                portOpen = { false },
                send = { pid, sig -> w.gwSignals += pid to sig },
                sleep = {},
            )
        val runner =
            ManagedRunner(
                homeDir = w.home,
                environment = w::environment,
                outcomes = RunOutcomeStore(w.lastRunFile),
                gateway = gateway,
                emit = w::emit,
                processes = RunProcesses(ProcScan(w.runProc.dir)) { _, _ -> },
            )
        w.runToEnd(runner, stopGateway = true)
        assertEquals("done", w.finalEvent().data["phase"], w.runEvents().toString())
        assertEquals("NOT_RUNNING", w.gatewayEvents().last().data["result"])
        assertEquals(emptyList<Pair<Int, Int>>(), w.gwSignals.toList())
        assertAll(false, "NOT_RUNNING")
    }

    @Test
    fun `the next run does not inherit the flag of the run before`() {
        w.fakeOa(w.successBody)
        ourGateway()
        gatewayStopsOnSignal()
        w.runToEnd(w.runner(), stopGateway = true)
        assertEquals(true, w.finalEvent().data["gatewayStopped"])
        // the gateway stays stopped: the second run stops nothing
        w.events.clear()
        w.runProc.dir
            .listFiles()
            ?.forEach { it.deleteRecursively() }
        w.runToEnd(w.runner(), stopGateway = true)
        assertEquals("done", w.finalEvent().data["phase"])
        assertEquals(emptyList<EmittedEvent>(), w.gatewayEvents())
        assertAll(false, "second run")
        assertEquals(false, stateFlag())
    }

    // ── refusals: always false ──────────────────────────────────────────────

    @Test
    fun `no consent with our gateway running is refused without the flag`() {
        w.fakeOa(w.successBody)
        ourGateway()
        w.runToEnd(w.runner(), stopGateway = false)
        assertRefusedWithoutFlag(UpdateReason.GATEWAY_RUNNING)
    }

    @Test
    fun `a gateway the app does not own (NOT_OURS) is refused without the flag`() {
        w.fakeOa(w.successBody)
        foreignGateway()
        w.sessionPids = listOf(SESSION)
        w.gwProc.add(SESSION, 1, listOf("bash"))
        w.portOpen = true
        w.runToEnd(w.runner(), stopGateway = true)
        assertEquals("NOT_OURS", w.gatewayEvents().last().data["result"])
        assertRefusedWithoutFlag(UpdateReason.GATEWAY_RUNNING)
    }

    @Test
    fun `a gateway that does not stop (STILL_RUNNING) is refused without the flag`() {
        w.fakeOa(w.successBody)
        ourGateway()
        w.runToEnd(w.runner(), stopGateway = true)
        assertEquals("STILL_RUNNING", w.gatewayEvents().last().data["result"])
        assertRefusedWithoutFlag(UpdateReason.GATEWAY_STOP_FAILED)
    }

    // ── refused after the stop: the event carries the flag, nothing else changes ──

    /** Ours was STOPPED and stays down although nothing ran: the refusal says to start it again. */
    @Test
    fun `our gateway stopped but a foreign one still running is refused GATEWAY_RUNNING with the flag`() {
        w.fakeOa(w.successBody)
        seedRecord()
        ourGateway()
        foreignGateway()
        w.onGatewaySignal = { pid, _ -> w.gwProc.zombie(pid) }
        val runner = w.runner()
        val before = untouched(runner)
        w.runToEnd(runner, stopGateway = true)
        assertEquals(listOf(GATEWAY to RunSignal.SIGTERM), w.gwSignals.toList(), "only ours is signalled")
        assertRefusedWithFlag(UpdateReason.GATEWAY_RUNNING, runner, before)
        assertEquals(false, stateFlag(), "getRunState after the refusal")
    }

    @Test
    fun `an updater running outside the app (BUSY) is refused without the flag`() {
        w.fakeOa(w.successBody)
        ourGateway()
        gatewayStopsOnSignal()
        w.runProc.add(4242, 1, listOf("bash", "/data/data/com.termux/files/usr/tmp/update-core.Xy12.sh"))
        w.runToEnd(w.runner(), stopGateway = true)
        assertRefusedWithoutFlag(UpdateReason.BUSY)
        assertEquals(emptyList<Pair<Int, Int>>(), w.gwSignals.toList(), "stopped before the BUSY check")
    }

    /**
     * The guard taken after the checks stopped the gateway: the BUSY refusal says the gateway was
     * stopped, and is sent as a refusal — never as the other run's state (its stage, message, flag).
     */
    @Test
    fun `a guard taken by another run after the stop (BUSY) is refused with the flag, none of its state`() {
        w.fakeOa(w.successBody)
        seedRecord()
        ourGateway()
        val runner = w.runner()
        var before: Untouched? = null
        w.onGatewaySignal = { pid, _ ->
            w.gwProc.zombie(pid)
            w.portOpen = false
            // between the checks and tryStart: another run holds the guard, mid-way, its own flag false
            assertTrue(ManagedRunGuard.tryStart(RunKinds.UPDATE, OTHER_AT, "other"))
            ManagedRunGuard.progress(OTHER_STAGE, UPDATE_STAGES, OTHER_LINE)
            before = untouched(runner)
        }
        try {
            // the other run holds the guard: wait for this run's thread, not for the guard
            w.runToEnd(runner, stopGateway = true)
            val seen = before ?: error("the gateway was never signalled: ${w.gatewayEvents()}")
            assertEquals(OTHER_STAGE, seen.guard.stage, "the other run is mid-way")
            assertRefusedWithFlag(UpdateReason.BUSY, runner, seen)
            val sent = w.runEvents().single().data
            val leaked = sent["message"] == OTHER_LINE || sent["stage"] == OTHER_STAGE
            assertFalse(leaked, "the other run's state leaked: $sent")
            // the other run goes on as it was, without the flag
            assertEquals("other", ManagedRunGuard.runToken)
            assertEquals(ManagedRunGuard.RUNNING, ManagedRunGuard.snapshot().phase)
            assertEquals(false, stateFlag())
        } finally {
            ManagedRunGuard.finish(RunVerdict.Success(0, 0))
        }
    }

    /** The same refusal while the other run carries its own flag: the event is still this refusal's. */
    @Test
    fun `a BUSY refusal after the stop is sent as this refusal even when the other run's flag is also true`() {
        w.fakeOa(w.successBody)
        ourGateway()
        w.onGatewaySignal = { pid, _ ->
            w.gwProc.zombie(pid)
            w.portOpen = false
            ManagedRunGuard.tryStart(RunKinds.UPDATE, OTHER_AT, "other", gatewayStopped = true)
            ManagedRunGuard.progress(OTHER_STAGE, UPDATE_STAGES, OTHER_LINE)
        }
        try {
            w.runToEnd(w.runner(), stopGateway = true)
            assertTrue(TestWait.until { RunLease.owner() == null }, "lease kept: ${RunLease.owner()}")
            assertEquals(listOf(refusalPayload(UpdateReason.BUSY, flag = true)), w.runEvents().map { it.data })
            assertEquals(OTHER_LINE, ManagedRunGuard.snapshot().message)
        } finally {
            ManagedRunGuard.finish(RunVerdict.Success(0, 0))
        }
    }

    // ── refused before anything was stopped: false ─────────────────────────

    /**
     * NOT_RUNNING: ours ended before the stop looked, so nothing was stopped — a gateway the app does
     * not own that shows up meanwhile refuses the run, and the refusal does not say "start it again".
     */
    @Test
    fun `a refusal after a stop that found nothing to stop (NOT_RUNNING) is without the flag`() {
        w.fakeOa(w.successBody)
        ourGateway()
        var looks = 0
        val gateway =
            GatewayControl(
                sessionPids = {
                    // status() reads sessionPids after its gateway scan: each change shows at the next look
                    when (++looks) {
                        1 -> w.gwProc.remove(GATEWAY)
                        2 -> foreignGateway()
                    }
                    listOf(SESSION)
                },
                scan = ProcScan(w.gwProc.dir),
                portOpen = { false },
                send = { pid, sig -> w.gwSignals += pid to sig },
                sleep = {},
            )
        val runner =
            ManagedRunner(
                homeDir = w.home,
                environment = w::environment,
                outcomes = RunOutcomeStore(w.lastRunFile),
                gateway = gateway,
                emit = w::emit,
                processes = RunProcesses(ProcScan(w.runProc.dir)) { _, _ -> },
            )
        w.runToEnd(runner, stopGateway = true)
        assertEquals("NOT_RUNNING", w.gatewayEvents().last().data["result"], w.gatewayEvents().toString())
        assertEquals(emptyList<Pair<Int, Int>>(), w.gwSignals.toList())
        assertRefusedWithoutFlag(UpdateReason.GATEWAY_RUNNING)
        assertEquals(listOf(refusalPayload(UpdateReason.GATEWAY_RUNNING, flag = false)), w.runEvents().map { it.data })
        assertEquals(emptyList<String>(), w.oaCalls())
    }

    @Test
    fun `no oa (NOT_INSTALLED) and an unknown kind are refused without the flag`() {
        ourGateway()
        gatewayStopsOnSignal()
        w.runToEnd(w.runner(), stopGateway = true)
        assertRefusedWithoutFlag(UpdateReason.NOT_INSTALLED)
        w.events.clear()
        assertTrue(RunLease.tryAcquire("x"))
        runBlocking { w.runner().run("INSTALL", stopGateway = true) }
        RunLease.release("x")
        assertEquals(listOf(false), flags())
        assertEquals("UNKNOWN", w.runEvents().single().data["reason"])
    }

    // ── emitRefused / emitBusy: the flag only when asked for ────────────────

    @Test
    fun `emitRefused without the flag argument sends false, and sends exactly what it is given`() {
        val runner = w.runner()
        runner.emitRefused(RunKinds.UPDATE, UpdateReason.BUSY)
        runner.emitRefused(RunKinds.UPDATE, UpdateReason.GATEWAY_RUNNING, gatewayStopped = true)
        runner.emitRefused(RunKinds.UPDATE, UpdateReason.GATEWAY_RUNNING, gatewayStopped = false)
        assertEquals(
            listOf(
                refusalPayload(UpdateReason.BUSY, flag = false),
                refusalPayload(UpdateReason.GATEWAY_RUNNING, flag = true),
                refusalPayload(UpdateReason.GATEWAY_RUNNING, flag = false),
            ),
            w.runEvents().map { it.data },
        )
        // a refusal never reaches the guard
        assertEquals(false, stateFlag())
    }

    /** JsBridge's BUSY when the lease is taken (a tool install, or the checks of another start): false. */
    @Test
    fun `emitBusy with no run in the guard is a BUSY refusal without the flag`() {
        val runner = w.runner()
        val before = untouched(runner)
        runner.emitBusy(RunKinds.UPDATE)
        assertEquals(listOf(refusalPayload(UpdateReason.BUSY, flag = false)), w.runEvents().map { it.data })
        assertEquals(before, untouched(runner))
    }

    private companion object {
        const val UPDATE_STAGES = 5
        const val PRIOR_AT = 1_700_000_000L
        const val OTHER_AT = 1L
        const val OTHER_STAGE = 3
        const val OTHER_LINE = "the other run's line"

        const val SESSION = 7100
        const val GATEWAY = 7102
        const val FOREIGN_SHELL = 3100
        const val FOREIGN = 3101
    }
}
