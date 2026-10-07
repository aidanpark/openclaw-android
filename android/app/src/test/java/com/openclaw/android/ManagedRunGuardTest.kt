package com.openclaw.android

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * [ManagedRunGuard]: one run at a time, `cancelable` only ever goes true → false (at `[3/5]`), a
 * cancel signal is delivered once and OUTSIDE the monitor, a cancel that could not be delivered by
 * `[3/5]` is withdrawn, the end phase follows the verdict, and nothing of one run leaks into the next.
 */
internal class ManagedRunGuardTest {
    private val gates = mutableListOf<CountDownLatch>()
    private val threads = mutableListOf<Thread>()

    @BeforeEach
    fun setup() = reset()

    @AfterEach
    fun teardown() {
        gates.forEach { it.countDown() }
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(WAIT_S)) }
        val stuck = threads.filter { it.isAlive }
        reset()
        assertTrue(stuck.isEmpty(), "threads still blocked: $stuck")
    }

    private fun reset() {
        ManagedRunGuard.process.set(null)
        if (ManagedRunGuard.isRunning()) ManagedRunGuard.finish(RunVerdict.Success(0, 0))
        assertTrue(ManagedRunGuard.tryStart(RunKinds.UPDATE, 0L, "reset"), "guard held by someone else")
        ManagedRunGuard.finish(RunVerdict.Success(0, 0))
    }

    private fun start(token: String = "t1") = assertTrue(ManagedRunGuard.tryStart(RunKinds.UPDATE, 10L, token))

    private fun at(stage: Int) = ManagedRunGuard.progress(stage, 5, "line $stage")

    private fun now() = ManagedRunGuard.snapshot()

    private fun thread(body: () -> Unit): Thread =
        Thread(body).also {
            threads += it
            it.start()
        }

    /** A signal that blocks inside until [gate] opens, then answers [answer]. */
    private fun heldSignal(
        answer: Boolean,
        calls: AtomicInteger,
        entered: CountDownLatch,
    ): Pair<(String) -> Boolean, CountDownLatch> {
        val gate = CountDownLatch(1).also { gates += it }
        val signal = { _: String ->
            calls.incrementAndGet()
            entered.countDown()
            gate.await(WAIT_S, TimeUnit.SECONDS)
            answer
        }
        return signal to gate
    }

    // ── start ───────────────────────────────────────────────────────────────

    @Test
    fun `a second start is refused while a run is going and allowed after finish`() {
        start()
        assertFalse(ManagedRunGuard.tryStart(RunKinds.UPDATE, 11L, "t2"))
        assertEquals("t1", ManagedRunGuard.runToken)
        ManagedRunGuard.finish(RunVerdict.Success(0, 0))
        assertFalse(ManagedRunGuard.isRunning())
        assertTrue(ManagedRunGuard.tryStart(RunKinds.UPDATE, 12L, "t3"))
    }

    @Test
    fun `a new run starts running, cancelable, at stage 0 of 5`() {
        start()
        val s = now()
        assertEquals(ManagedRunGuard.RUNNING, s.phase)
        assertEquals(RunKinds.UPDATE, s.kind)
        assertEquals(0, s.stage)
        assertEquals(5, s.stageTotal)
        assertTrue(s.cancelable)
        assertTrue(s.busy)
        assertEquals(10L, s.startedAtSec)
    }

    @Test
    fun `progress outside a run changes nothing`() {
        val before = now()
        at(3)
        assertEquals(before, now())
    }

    // ── stage and cancelable ────────────────────────────────────────────────

    @Test
    fun `cancelable turns false at stage 3 and a stray earlier stage line never turns it back`() {
        start()
        at(1)
        at(2)
        assertTrue(now().cancelable)
        at(3)
        assertFalse(now().cancelable)
        at(1)
        assertEquals(3, now().stage, "the stage went back")
        assertFalse(now().cancelable, "cancelable came back after [3/5]")
    }

    @Test
    fun `cancelable is monotone over random stage sequences`() {
        val rnd = Random(7)
        repeat(200) {
            reset()
            start()
            var seenFalse = false
            var maxStage = 0
            repeat(12) {
                val stage = rnd.nextInt(0, 6)
                at(stage)
                maxStage = maxOf(maxStage, stage)
                val s = now()
                assertEquals(maxStage, s.stage)
                if (seenFalse) assertFalse(s.cancelable, "cancelable came back")
                if (!s.cancelable) seenFalse = true
                assertEquals(maxStage <= 2, s.cancelable)
            }
        }
    }

    // ── cancel ──────────────────────────────────────────────────────────────

    @Test
    fun `a cancel with nothing running is refused and signals nothing`() {
        val calls = AtomicInteger()
        assertFalse(ManagedRunGuard.requestCancel { calls.incrementAndGet() > 0 })
        assertEquals(0, calls.get())
    }

    @Test
    fun `a cancel at stage 2 is accepted and signalled once`() {
        start()
        at(2)
        val calls = AtomicInteger()
        assertTrue(ManagedRunGuard.requestCancel { calls.incrementAndGet() > 0 })
        assertEquals(1, calls.get())
        assertEquals(ManagedRunGuard.CANCELLING, now().phase)
        assertTrue(now().cancelRequested)
    }

    @Test
    fun `a cancel at stage 3 or later is refused, signals nothing and changes nothing`() {
        for (stage in 3..5) {
            reset()
            start()
            at(stage)
            val before = now()
            val calls = AtomicInteger()
            assertFalse(ManagedRunGuard.requestCancel { calls.incrementAndGet() > 0 })
            ManagedRunGuard.retryCancel { calls.incrementAndGet() > 0 }
            assertEquals(0, calls.get())
            assertEquals(before, now())
        }
    }

    @Test
    fun `after one delivery neither a second cancel nor the retry pump signals again`() {
        start()
        val calls = AtomicInteger()
        val signal = { _: String -> calls.incrementAndGet() > 0 }
        assertTrue(ManagedRunGuard.requestCancel(signal))
        assertTrue(ManagedRunGuard.requestCancel(signal), "a repeated cancel is still an accepted request")
        repeat(5) { ManagedRunGuard.retryCancel(signal) }
        assertEquals(1, calls.get())
    }

    @Test
    fun `an undelivered cancel is retried by the pump until it reaches a process, then never again`() {
        start()
        val answers = ArrayDeque(listOf(false, false, true))
        val calls = AtomicInteger()
        val signal = { _: String ->
            calls.incrementAndGet()
            answers.removeFirst()
        }
        assertTrue(ManagedRunGuard.requestCancel(signal))
        ManagedRunGuard.retryCancel(signal)
        ManagedRunGuard.retryCancel(signal)
        assertEquals(3, calls.get())
        ManagedRunGuard.retryCancel(signal)
        assertEquals(3, calls.get())
        assertEquals(ManagedRunGuard.CANCELLING, now().phase)
    }

    @Test
    fun `an undelivered cancel is withdrawn at stage 3 and the pump stops trying`() {
        start()
        at(2)
        val calls = AtomicInteger()
        assertTrue(ManagedRunGuard.requestCancel { calls.incrementAndGet() < 0 })
        assertEquals(ManagedRunGuard.CANCELLING, now().phase)
        at(3)
        assertEquals(ManagedRunGuard.RUNNING, now().phase, "a cancel nobody received still reads as requested")
        assertFalse(now().cancelable)
        ManagedRunGuard.retryCancel { calls.incrementAndGet() > 0 }
        assertEquals(1, calls.get())
    }

    @Test
    fun `a delivered cancel is not withdrawn at stage 3`() {
        start()
        assertTrue(ManagedRunGuard.requestCancel { true })
        at(3)
        assertEquals(ManagedRunGuard.CANCELLING, now().phase)
    }

    @Test
    fun `a throwing signal propagates but leaves the guard able to retry`() {
        start()
        assertThrows(IllegalStateException::class.java) {
            ManagedRunGuard.requestCancel { throw IllegalStateException("proc unreadable") }
        }
        assertEquals(ManagedRunGuard.CANCELLING, now().phase)
        val calls = AtomicInteger()
        ManagedRunGuard.retryCancel { calls.incrementAndGet() > 0 }
        assertEquals(1, calls.get(), "the throwing delivery kept the signal claimed")
    }

    @Test
    fun `a slow signal never blocks progress, snapshot or another cancel - it runs outside the monitor`() {
        start()
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val (signal, gate) = heldSignal(true, calls, entered)
        thread { ManagedRunGuard.requestCancel(signal) }
        assertTrue(entered.await(WAIT_S, TimeUnit.SECONDS))
        assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            at(1)
            assertEquals(1, now().stage)
            ManagedRunGuard.markLongRunning()
            assertTrue(ManagedRunGuard.requestCancel { calls.incrementAndGet() > 0 })
            ManagedRunGuard.retryCancel { calls.incrementAndGet() > 0 }
        }
        assertEquals(1, calls.get(), "a second sender ran while the first was still signalling")
        gate.countDown()
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(WAIT_S)) }
        ManagedRunGuard.retryCancel { calls.incrementAndGet() > 0 }
        assertEquals(1, calls.get())
    }

    /**
     * Progress to `[3/5]` against a cancel, 500 times. Like the real signal, this one sends only
     * while the run is still cancelable. Whatever the order: at most one signal, the run is no
     * longer cancelable, and it reads "cancelling" exactly when a signal was really delivered.
     */
    @Test
    fun `progress to stage 3 racing a cancel - 500 rounds`() {
        repeat(500) { round ->
            reset()
            start("race-$round")
            at(2)
            val delivered = AtomicInteger()
            val signal = { _: String ->
                if (ManagedRunGuard.snapshot().cancelable) {
                    delivered.incrementAndGet()
                    true
                } else {
                    false
                }
            }
            val barrier = CyclicBarrier(2)
            val a =
                Thread {
                    barrier.await()
                    at(3)
                }
            val b =
                Thread {
                    barrier.await()
                    ManagedRunGuard.requestCancel(signal)
                }
            a.start()
            b.start()
            a.join()
            b.join()
            ManagedRunGuard.retryCancel(signal)
            val s = now()
            assertTrue(delivered.get() <= 1, "round $round: signalled ${delivered.get()} times")
            assertFalse(s.cancelable, "round $round")
            assertEquals(3, s.stage)
            assertEquals(delivered.get() == 1, s.phase == ManagedRunGuard.CANCELLING, "round $round: $s")
        }
    }

    // ── end ─────────────────────────────────────────────────────────────────

    @Test
    fun `finish maps the verdict to the end phase and releases`() {
        start()
        ManagedRunGuard.finish(RunVerdict.Success(0, 2))
        assertEquals(ManagedRunGuard.DONE, now().phase)
        assertEquals(2, now().warnings)
        assertEquals(1f, now().progress)
        assertFalse(now().cancelable)
        assertFalse(ManagedRunGuard.isRunning())

        start()
        ManagedRunGuard.finish(RunVerdict.Failure(UpdateReason.NO_SPACE, 1, "d", 0))
        assertEquals(ManagedRunGuard.FAILED, now().phase)
        assertEquals("NO_SPACE", now().reason)
        assertEquals(1, now().exit)
        assertEquals("d", now().detail)
    }

    /**
     * The settled verdict is what is recorded and what the page reads: after the user's cancel an
     * interrupted or unknown end is CANCELLED (not INTERRUPTED — that now means "stopped from
     * elsewhere"), any other failure keeps its own reason and stays failed.
     */
    @Test
    fun `after a cancel an interrupted or unknown end is cancelled, any other failure stays failed`() {
        for ((reason, phase) in listOf(
            UpdateReason.INTERRUPTED to ManagedRunGuard.CANCELLED,
            UpdateReason.UNKNOWN to ManagedRunGuard.CANCELLED,
            UpdateReason.CANCELLED to ManagedRunGuard.CANCELLED,
            UpdateReason.NO_SPACE to ManagedRunGuard.FAILED,
        )) {
            reset()
            start()
            assertTrue(ManagedRunGuard.requestCancel { true })
            val settled = ManagedRunGuard.settle(RunVerdict.Failure(reason, 143, "", 0))
            ManagedRunGuard.finish(settled)
            assertEquals(phase, now().phase, "$reason")
            if (phase == ManagedRunGuard.CANCELLED) {
                assertEquals(UpdateReason.CANCELLED, (settled as RunVerdict.Failure).reason, "$reason")
                assertEquals("CANCELLED", now().reason, "$reason")
            } else {
                assertEquals(reason.name, now().reason)
            }
        }
    }

    /** finish's own rule, also for a verdict nobody settled: it agrees with settle on the same set. */
    @Test
    fun `after a cancel finish shows an unsettled interrupted or unknown end as cancelled CANCELLED`() {
        for (reason in listOf(UpdateReason.INTERRUPTED, UpdateReason.UNKNOWN, UpdateReason.CANCELLED)) {
            reset()
            start()
            assertTrue(ManagedRunGuard.requestCancel { true })
            ManagedRunGuard.finish(RunVerdict.Failure(reason, null, "", 0))
            assertEquals(ManagedRunGuard.CANCELLED, now().phase, "$reason")
            assertEquals("CANCELLED", now().reason, "$reason")
        }
    }

    @Test
    fun `with a cancel request settle makes exactly INTERRUPTED, UNKNOWN and CANCELLED into CANCELLED`() {
        start()
        assertTrue(ManagedRunGuard.requestCancel { true })
        val explained = setOf(UpdateReason.INTERRUPTED, UpdateReason.UNKNOWN, UpdateReason.CANCELLED)
        for (reason in UpdateReason.entries) {
            val v = RunVerdict.Failure(reason, 143, "detail", 1)
            val expected = if (reason in explained) v.copy(reason = UpdateReason.CANCELLED) else v
            assertEquals(expected, ManagedRunGuard.settle(v), "$reason")
        }
        val ok = RunVerdict.Success(0, 0)
        assertEquals(ok, ManagedRunGuard.settle(ok), "a success after a cancel stays a success")
    }

    @Test
    fun `without a cancel request settle keeps every reason, INTERRUPTED included`() {
        start()
        for (reason in UpdateReason.entries) {
            val v = RunVerdict.Failure(reason, 143, "", 0)
            assertEquals(v, ManagedRunGuard.settle(v), "$reason")
        }
        ManagedRunGuard.finish(ManagedRunGuard.settle(RunVerdict.Failure(UpdateReason.INTERRUPTED, 143, "", 0)))
        assertEquals(ManagedRunGuard.FAILED, now().phase)
        assertEquals("INTERRUPTED", now().reason)
    }

    @Test
    fun `a cancel that was withdrawn at stage 3 no longer turns an interrupted end into CANCELLED`() {
        start()
        assertTrue(ManagedRunGuard.requestCancel { false })
        at(3)
        assertEquals(ManagedRunGuard.RUNNING, now().phase)
        val v = RunVerdict.Failure(UpdateReason.INTERRUPTED, 143, "", 0)
        assertEquals(v, ManagedRunGuard.settle(v))
        ManagedRunGuard.finish(v)
        assertEquals(ManagedRunGuard.FAILED, now().phase)
        assertEquals("INTERRUPTED", now().reason)
    }

    @Test
    fun `settle after the run finished changes nothing - only a cancel of the run still going counts`() {
        start()
        assertTrue(ManagedRunGuard.requestCancel { true })
        ManagedRunGuard.finish(RunVerdict.Failure(UpdateReason.CANCELLED, 143, "", 0))
        val v = RunVerdict.Failure(UpdateReason.INTERRUPTED, 143, "", 0)
        assertEquals(v, ManagedRunGuard.settle(v))
    }

    @Test
    fun `settle changes nothing without a cancel`() {
        start()
        val v = RunVerdict.Failure(UpdateReason.UNKNOWN, 0, "", 0)
        assertEquals(v, ManagedRunGuard.settle(v))
        val s = RunVerdict.Success(0, 0)
        assertEquals(s, ManagedRunGuard.settle(s))
    }

    @Test
    fun `a success after a cancel request is done`() {
        start()
        assertTrue(ManagedRunGuard.requestCancel { true })
        ManagedRunGuard.finish(RunVerdict.Success(0, 0))
        assertEquals(ManagedRunGuard.DONE, now().phase)
    }

    @Test
    fun `a delivery that ends after its run finished and a new run started does not touch the new run`() {
        start("old")
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val (signal, gate) = heldSignal(true, calls, entered)
        thread { ManagedRunGuard.requestCancel(signal) }
        assertTrue(entered.await(WAIT_S, TimeUnit.SECONDS))
        ManagedRunGuard.finish(RunVerdict.Failure(UpdateReason.INTERRUPTED, 143, "", 0))
        start("new")
        gate.countDown()
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(WAIT_S)) }
        assertEquals(ManagedRunGuard.RUNNING, now().phase, "the old delivery marked the new run as cancelling")
        val newCalls = AtomicInteger()
        assertTrue(ManagedRunGuard.requestCancel { newCalls.incrementAndGet() > 0 })
        assertEquals(1, newCalls.get(), "the old run's flags blocked the new run's signal")
    }

    @Test
    fun `a delivered cancel of the previous run does not block the next run's signal`() {
        start("old")
        assertTrue(ManagedRunGuard.requestCancel { true })
        ManagedRunGuard.finish(RunVerdict.Failure(UpdateReason.INTERRUPTED, 143, "", 0))
        start("new")
        val calls = AtomicInteger()
        assertTrue(ManagedRunGuard.requestCancel { calls.incrementAndGet() > 0 })
        assertEquals(1, calls.get())
    }

    // ── the token the signal is given (L7) ─────────────────────────────────

    @Test
    fun `the signal is given the token of the run the cancel was claimed for`() {
        start("run-a")
        val seen = mutableListOf<String>()
        assertTrue(ManagedRunGuard.requestCancel { token -> seen.add(token) && false })
        ManagedRunGuard.retryCancel { token -> seen.add(token) }
        assertEquals(listOf("run-a", "run-a"), seen, "the request and the retry signal the claimed run")
    }

    @Test
    fun `after a run ends the next run's cancel carries the next run's token, never the previous one`() {
        start("run-a")
        assertTrue(ManagedRunGuard.requestCancel { true })
        ManagedRunGuard.finish(RunVerdict.Failure(UpdateReason.INTERRUPTED, 143, "", 0))
        start("run-b")
        val seen = mutableListOf<String>()
        assertTrue(ManagedRunGuard.requestCancel { token -> seen.add(token) })
        assertEquals(listOf("run-b"), seen)
    }

    private companion object {
        const val WAIT_S = 5L
    }
}
