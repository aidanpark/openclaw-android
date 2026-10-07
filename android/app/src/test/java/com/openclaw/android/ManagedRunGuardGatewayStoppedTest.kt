package com.openclaw.android

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [ManagedRunGuard.State.gatewayStopped] in the guard itself: set only by [ManagedRunGuard.tryStart]
 * (from its argument, never from the previous run), kept by every later change of the same run —
 * progress, long-running, cancel, [ManagedRunGuard.finish] — untouched by [ManagedRunGuard.settle],
 * and always in [ManagedRunner.stateEvent] as a boolean.
 */
internal class ManagedRunGuardGatewayStoppedTest {
    @BeforeEach
    fun reset() = ManagedRunWorld.resetShared()

    @AfterEach
    fun release() = ManagedRunWorld.resetShared()

    private fun flag() = ManagedRunGuard.snapshot().gatewayStopped

    /** phase to flag */
    private fun ended() = ManagedRunGuard.snapshot().let { it.phase to it.gatewayStopped }

    private fun start(
        stopped: Boolean,
        token: String = "t",
    ) = assertTrue(ManagedRunGuard.tryStart(RunKinds.UPDATE, 10L, token, gatewayStopped = stopped))

    @Test
    fun `the state's default is false and tryStart's default argument is false`() {
        assertFalse(ManagedRunGuard.State(null, ManagedRunGuard.IDLE, 0, 0, "", 0L).gatewayStopped)
        start(true)
        ManagedRunGuard.finish(RunVerdict.Success(0, 0))
        assertTrue(ManagedRunGuard.tryStart(RunKinds.UPDATE, 11L, "next"))
        assertFalse(flag(), "the default start inherited the previous run's flag")
    }

    @Test
    fun `tryStart sets the flag from its argument, whatever the run before had`() {
        for ((before, now) in listOf(true to false, false to true, true to true, false to false)) {
            start(before, "a")
            ManagedRunGuard.finish(RunVerdict.Failure(UpdateReason.DOWNLOAD, 1, "", 0))
            assertEquals(before, flag(), "kept by finish")
            start(now, "b")
            assertEquals(now, flag(), "$before -> $now")
            ManagedRunGuard.finish(RunVerdict.Success(0, 0))
        }
    }

    @Test
    fun `progress, long-running and a cancel request keep the flag`() {
        start(true)
        ManagedRunGuard.progress(1, 5, "pre-flight")
        ManagedRunGuard.markLongRunning()
        assertTrue(flag())
        assertTrue(ManagedRunGuard.requestCancel { true })
        assertEquals(ManagedRunGuard.CANCELLING, ManagedRunGuard.snapshot().phase)
        assertTrue(flag())
        ManagedRunGuard.progress(3, 5, "core")
        assertTrue(flag())
    }

    @Test
    fun `every end keeps the flag - done, failed and cancelled`() {
        start(true)
        ManagedRunGuard.finish(RunVerdict.Success(0, 1, "warn"))
        assertEquals(ManagedRunGuard.DONE to true, ended())

        start(true)
        ManagedRunGuard.finish(RunVerdict.Failure(UpdateReason.INSTALL_FAILED, 1, "x", 0))
        assertEquals(ManagedRunGuard.FAILED to true, ended())

        start(true)
        ManagedRunGuard.requestCancel { true }
        val settled = ManagedRunGuard.settle(RunVerdict.Failure(UpdateReason.INTERRUPTED, 143, "", 0))
        assertTrue(flag(), "settle changed the state")
        ManagedRunGuard.finish(settled)
        assertEquals(ManagedRunGuard.CANCELLED to true, ended())
    }

    @Test
    fun `a refused second start does not change the flag of the run that is going`() {
        start(false, "first")
        assertFalse(ManagedRunGuard.tryStart(RunKinds.UPDATE, 11L, "second", gatewayStopped = true))
        assertFalse(flag())
        assertEquals("first", ManagedRunGuard.runToken)
        ManagedRunGuard.finish(RunVerdict.Success(0, 0))

        start(true, "third")
        assertFalse(ManagedRunGuard.tryStart(RunKinds.UPDATE, 12L, "fourth", gatewayStopped = false))
        assertTrue(flag())
    }

    @Test
    fun `stateEvent always carries the flag as a boolean, last among its keys`() {
        val idle = ManagedRunner.stateEvent(ManagedRunGuard.snapshot())
        assertEquals(false, idle["gatewayStopped"])
        assertEquals("gatewayStopped", idle.keys.last())
        assertEquals(ManagedRunWorld.STATE_KEYS, idle.keys)
        start(true)
        assertEquals(true, ManagedRunner.stateEvent(ManagedRunGuard.snapshot())["gatewayStopped"])
        // a refusal's state is built fresh: false even while a run with the flag is going
        val refused =
            ManagedRunner.stateEvent(
                ManagedRunGuard.State(RunKinds.UPDATE, ManagedRunner.REFUSED, 0, 5, "", 0L, reason = "BUSY"),
            )
        assertEquals(false, refused["gatewayStopped"])
    }

    @Test
    fun `a refusal while a flagged run goes on is sent without the flag and the busy event with it`() {
        start(true, "flagged")
        val events = mutableListOf<Map<String, Any?>>()
        val runner =
            ManagedRunner(
                homeDir = java.io.File("."),
                environment = { emptyMap() },
                outcomes = RunOutcomeStore(java.io.File("does-not-exist/last-run.conf")),
                gateway = GatewayControl(sessionPids = { emptyList() }, portOpen = { false }),
                emit = { _, data -> events += data },
            )
        runner.emitRefused(RunKinds.UPDATE, UpdateReason.BUSY)
        runner.emitBusy(RunKinds.UPDATE)
        assertEquals(listOf(ManagedRunner.REFUSED, ManagedRunGuard.RUNNING), events.map { it["phase"] })
        assertEquals(listOf(false, true), events.map { it["gatewayStopped"] })
    }
}
