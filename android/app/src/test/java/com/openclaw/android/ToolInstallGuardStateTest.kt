package com.openclaw.android

import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** The compiled process-wide [ToolInstallGuard]: one install at a time, cancel, outcome, snapshots. */
internal class ToolInstallGuardStateTest {
    @BeforeEach
    fun setup() = release()

    @AfterEach
    fun teardown() {
        release()
        assertFalse(ToolInstallGuard.isRunning())
        assertNull(ToolInstallGuard.process.get())
    }

    /** Whatever a previous test left, end with the guard free and no process. */
    private fun release() {
        ToolInstallGuard.process.set(null)
        if (ToolInstallGuard.isRunning()) ToolInstallGuard.finish(ToolVerdict.Success)
        assertTrue(ToolInstallGuard.tryStart("reset", 0L), "guard held by someone else")
        ToolInstallGuard.finish(ToolVerdict.Success)
    }

    private fun failure(reason: ToolFailure) = ToolVerdict.Failure(reason)

    /** A signal that could not be delivered (no pid yet): these tests are about the state, not delivery. */
    private val noSignal: () -> Boolean = { false }

    @Test
    fun `tryStart sets a fresh running state`() {
        assertTrue(ToolInstallGuard.tryStart("tmux", START))
        val s = ToolInstallGuard.snapshot()
        assertEquals(ToolInstallGuard.State(ToolInstallGuard.RUNNING, "tmux", 0f, "", START), s)
        assertTrue(s.busy)
        assertFalse(s.cancelRequested)
        assertTrue(ToolInstallGuard.isRunning())
    }

    @Test
    fun `a second tryStart is refused, for the same or another tool, and keeps the running state`() {
        assertTrue(ToolInstallGuard.tryStart("tmux", START))
        ToolInstallGuard.progress(HALF, "x")
        assertFalse(ToolInstallGuard.tryStart("tmux", START + 1))
        assertFalse(ToolInstallGuard.tryStart("ttyd", START + 1))
        val s = ToolInstallGuard.snapshot()
        assertEquals("tmux", s.tool)
        assertEquals(START, s.startedAtSec)
        assertEquals(HALF, s.progress)
        assertEquals("x", s.message)
    }

    @Test
    fun `sixteen threads racing tryStart let exactly one in`() {
        val threads = 16
        val barrier = CyclicBarrier(threads)
        val wins = AtomicInteger()
        val workers =
            (0 until threads).map { i ->
                Thread {
                    barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    if (ToolInstallGuard.tryStart("t$i", START)) wins.incrementAndGet()
                }
            }
        workers.forEach { it.start() }
        workers.forEach { it.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS)) }
        assertEquals(1, wins.get())
        assertTrue(ToolInstallGuard.isRunning())
    }

    @Test
    fun `progress is recorded only while busy`() {
        ToolInstallGuard.progress(HALF, "ignored while idle")
        assertEquals(ToolInstallGuard.DONE, ToolInstallGuard.snapshot().phase)
        assertTrue(ToolInstallGuard.snapshot().message != "ignored while idle")

        ToolInstallGuard.tryStart("tmux", START)
        ToolInstallGuard.progress(HALF, "line")
        assertEquals(HALF, ToolInstallGuard.snapshot().progress)
        assertEquals("line", ToolInstallGuard.snapshot().message)

        ToolInstallGuard.requestCancel(noSignal)
        ToolInstallGuard.progress(HIGH, "still streaming")
        ToolInstallGuard.snapshot().let {
            assertEquals(ToolInstallGuard.CANCELLING, it.phase)
            assertEquals("still streaming", it.message)
        }

        ToolInstallGuard.finish(failure(ToolFailure.INSTALL_FAILED))
        ToolInstallGuard.progress(HALF, "after finish")
        assertEquals(ToolInstallGuard.FAILED, ToolInstallGuard.snapshot().phase)
        assertTrue(ToolInstallGuard.snapshot().message != "after finish")
    }

    @Test
    fun `markLongRunning only while busy and it survives progress and cancel`() {
        ToolInstallGuard.markLongRunning()
        assertFalse(ToolInstallGuard.snapshot().longRunning)
        ToolInstallGuard.tryStart("claude-code", START)
        ToolInstallGuard.markLongRunning()
        ToolInstallGuard.progress(HALF, "x")
        ToolInstallGuard.requestCancel(noSignal)
        ToolInstallGuard.snapshot().let {
            assertTrue(it.longRunning)
            assertEquals(ToolInstallGuard.CANCELLING, it.phase)
        }
    }

    @Test
    fun `requestCancel when idle is false and changes nothing`() {
        val before = ToolInstallGuard.snapshot()
        assertFalse(ToolInstallGuard.requestCancel(noSignal))
        assertEquals(before, ToolInstallGuard.snapshot())
    }

    @Test
    fun `requestCancel moves running to cancelling and does not release the guard`() {
        ToolInstallGuard.tryStart("tmux", START)
        assertTrue(ToolInstallGuard.requestCancel(noSignal))
        val s = ToolInstallGuard.snapshot()
        assertEquals(ToolInstallGuard.CANCELLING, s.phase)
        assertTrue(s.cancelRequested)
        assertTrue(s.busy)
        assertTrue(ToolInstallGuard.isRunning(), "a cancel request must not free the guard")
        assertFalse(ToolInstallGuard.tryStart("ttyd", START), "another install started while cancelling")
    }

    @Test
    fun `requestCancel is idempotent`() {
        ToolInstallGuard.tryStart("tmux", START)
        assertTrue(ToolInstallGuard.requestCancel(noSignal))
        val first = ToolInstallGuard.snapshot()
        assertTrue(ToolInstallGuard.requestCancel(noSignal))
        assertEquals(first, ToolInstallGuard.snapshot())
        assertTrue(ToolInstallGuard.isRunning())
    }

    // ── the cancel signal (SIGTERM by pid, never Process.destroy) ───────────

    /** Counts calls; answers [delivered] in order, then the last value forever. */
    private class FakeSignal(
        vararg val delivered: Boolean,
    ) : () -> Boolean {
        val calls = AtomicInteger()

        override fun invoke(): Boolean {
            val i = calls.getAndIncrement()
            return delivered[minOf(i, delivered.size - 1)]
        }
    }

    @Test
    fun `requestCancel sends the signal once and never touches the process`() {
        val process = mockk<Process>(relaxed = true)
        val signal = FakeSignal(true)
        ToolInstallGuard.tryStart("tmux", START)
        ToolInstallGuard.process.set(process)
        ToolInstallGuard.requestCancel(signal)
        ToolInstallGuard.requestCancel(signal)
        ToolInstallGuard.retryCancel(signal)
        assertEquals(1, signal.calls.get(), "a delivered SIGTERM was sent again")
        // destroy() closes the output pipe on Android and would end the read while npm still runs
        verify(exactly = 0) { process.destroy() }
        verify(exactly = 0) { process.destroyForcibly() }
        assertTrue(ToolInstallGuard.isRunning())
    }

    @Test
    fun `an undelivered signal is retried by retryCancel until it is delivered, then never again`() {
        val signal = FakeSignal(false, false, true)
        ToolInstallGuard.tryStart("tmux", START)
        assertTrue(ToolInstallGuard.requestCancel(signal))
        assertEquals(1, signal.calls.get())
        ToolInstallGuard.retryCancel(signal)
        assertEquals(2, signal.calls.get())
        ToolInstallGuard.retryCancel(signal) // delivered here
        assertEquals(3, signal.calls.get())
        repeat(RETRIES) { ToolInstallGuard.retryCancel(signal) }
        ToolInstallGuard.requestCancel(signal)
        assertEquals(3, signal.calls.get(), "the signal was sent after it had been delivered")
        assertEquals(ToolInstallGuard.CANCELLING, ToolInstallGuard.snapshot().phase)
    }

    @Test
    fun `a repeated requestCancel retries an undelivered signal`() {
        val signal = FakeSignal(false, true)
        ToolInstallGuard.tryStart("tmux", START)
        ToolInstallGuard.requestCancel(signal)
        ToolInstallGuard.requestCancel(signal)
        ToolInstallGuard.requestCancel(signal)
        assertEquals(2, signal.calls.get())
    }

    @Test
    fun `retryCancel does nothing while running without a cancel request`() {
        val signal = FakeSignal(true)
        ToolInstallGuard.tryStart("tmux", START)
        repeat(RETRIES) { ToolInstallGuard.retryCancel(signal) }
        assertEquals(0, signal.calls.get(), "a SIGTERM was sent without a cancel request")
        assertEquals(ToolInstallGuard.RUNNING, ToolInstallGuard.snapshot().phase)
    }

    @Test
    fun `retryCancel does nothing when idle or after finish`() {
        val signal = FakeSignal(false)
        ToolInstallGuard.retryCancel(signal)
        ToolInstallGuard.tryStart("tmux", START)
        ToolInstallGuard.requestCancel(signal)
        ToolInstallGuard.finish(failure(ToolFailure.INTERRUPTED))
        val calls = signal.calls.get()
        repeat(RETRIES) { ToolInstallGuard.retryCancel(signal) }
        assertEquals(1, calls)
        assertEquals(calls, signal.calls.get(), "a finished install was signalled")
        assertEquals(ToolInstallGuard.CANCELLED, ToolInstallGuard.snapshot().phase)
    }

    @Test
    fun `requestCancel when idle never calls the signal`() {
        val signal = FakeSignal(true)
        assertFalse(ToolInstallGuard.requestCancel(signal))
        assertEquals(0, signal.calls.get())
    }

    @Test
    fun `the next run can be signalled again after an earlier run's signal was delivered`() {
        val first = FakeSignal(true)
        ToolInstallGuard.tryStart("tmux", START)
        ToolInstallGuard.requestCancel(first)
        ToolInstallGuard.finish(failure(ToolFailure.INTERRUPTED))

        val second = FakeSignal(true)
        ToolInstallGuard.tryStart("ttyd", START + 1)
        ToolInstallGuard.requestCancel(second)
        assertEquals(1, second.calls.get(), "termSent leaked from the previous run")
    }

    @Test
    fun `cancelRequested follows the cancelling phase`() {
        assertFalse(ToolInstallGuard.cancelRequested())
        ToolInstallGuard.tryStart("tmux", START)
        assertFalse(ToolInstallGuard.cancelRequested())
        ToolInstallGuard.requestCancel(noSignal)
        assertTrue(ToolInstallGuard.cancelRequested())
        ToolInstallGuard.finish(failure(ToolFailure.NOT_RUN))
        assertFalse(ToolInstallGuard.cancelRequested())
    }

    @Test
    fun `requestCancel without a process yet still records the request`() {
        ToolInstallGuard.tryStart("tmux", START)
        assertTrue(ToolInstallGuard.requestCancel(noSignal))
        assertEquals(ToolInstallGuard.CANCELLING, ToolInstallGuard.snapshot().phase)
    }

    @Test
    fun `finish with success is done at full progress`() {
        ToolInstallGuard.tryStart("tmux", START)
        ToolInstallGuard.progress(HALF, "x")
        ToolInstallGuard.finish(ToolVerdict.Success)
        val s = ToolInstallGuard.snapshot()
        assertEquals(ToolInstallGuard.DONE, s.phase)
        assertEquals(1f, s.progress)
        assertNull(s.reason)
        assertEquals("tmux", s.tool)
        assertFalse(s.busy)
    }

    @ParameterizedTest
    @EnumSource(ToolFailure::class)
    fun `finish with a failure records its reason`(reason: ToolFailure) {
        ToolInstallGuard.tryStart("tmux", START)
        ToolInstallGuard.progress(HALF, "x")
        ToolInstallGuard.finish(failure(reason))
        val s = ToolInstallGuard.snapshot()
        assertEquals(ToolInstallGuard.FAILED, s.phase)
        assertEquals(reason.name, s.reason)
        assertEquals(0f, s.progress)
    }

    @ParameterizedTest
    @EnumSource(ToolFailure::class, names = ["INTERRUPTED", "NOT_RUN"])
    fun `after a cancel request an interrupted or not run outcome is cancelled`(reason: ToolFailure) {
        ToolInstallGuard.tryStart("tmux", START)
        ToolInstallGuard.requestCancel(noSignal)
        ToolInstallGuard.finish(failure(reason))
        val s = ToolInstallGuard.snapshot()
        assertEquals(ToolInstallGuard.CANCELLED, s.phase)
        assertEquals(ToolFailure.INTERRUPTED.name, s.reason)
        assertFalse(s.cancelRequested)
    }

    @ParameterizedTest
    @EnumSource(ToolFailure::class, names = ["INTERRUPTED", "NOT_RUN"], mode = EnumSource.Mode.EXCLUDE)
    fun `after a cancel request any other failure is still failed with its reason`(reason: ToolFailure) {
        ToolInstallGuard.tryStart("tmux", START)
        ToolInstallGuard.requestCancel(noSignal)
        ToolInstallGuard.finish(failure(reason))
        assertEquals(ToolInstallGuard.FAILED, ToolInstallGuard.snapshot().phase)
        assertEquals(reason.name, ToolInstallGuard.snapshot().reason)
    }

    @Test
    fun `after a cancel request a success is still done`() {
        ToolInstallGuard.tryStart("tmux", START)
        ToolInstallGuard.requestCancel(noSignal)
        ToolInstallGuard.finish(ToolVerdict.Success)
        assertEquals(ToolInstallGuard.DONE, ToolInstallGuard.snapshot().phase)
        assertEquals(1f, ToolInstallGuard.snapshot().progress)
    }

    @Test
    fun `without a cancel request an interrupted run is failed, not cancelled`() {
        ToolInstallGuard.tryStart("tmux", START)
        ToolInstallGuard.finish(failure(ToolFailure.INTERRUPTED))
        assertEquals(ToolInstallGuard.FAILED, ToolInstallGuard.snapshot().phase)
        assertEquals("INTERRUPTED", ToolInstallGuard.snapshot().reason)
    }

    @Test
    fun `finish releases the guard and forgets the process`() {
        val process = mockk<Process>(relaxed = true)
        ToolInstallGuard.tryStart("tmux", START)
        ToolInstallGuard.process.set(process)
        ToolInstallGuard.finish(ToolVerdict.Success)
        assertFalse(ToolInstallGuard.isRunning())
        assertNull(ToolInstallGuard.process.get())
        assertFalse(ToolInstallGuard.requestCancel(noSignal), "a finished install accepted a cancel")
        verify(exactly = 0) { process.destroy() }
    }

    @Test
    fun `the next tryStart clears the previous result`() {
        ToolInstallGuard.tryStart("tmux", START)
        ToolInstallGuard.markLongRunning()
        ToolInstallGuard.requestCancel(noSignal)
        ToolInstallGuard.finish(failure(ToolFailure.INSTALL_FAILED))

        assertTrue(ToolInstallGuard.tryStart("ttyd", START + 10))
        assertEquals(
            ToolInstallGuard.State(ToolInstallGuard.RUNNING, "ttyd", 0f, "", START + 10),
            ToolInstallGuard.snapshot(),
        )
    }

    @Test
    fun `an earlier snapshot is not changed by later updates`() {
        ToolInstallGuard.tryStart("tmux", START)
        ToolInstallGuard.progress(HALF, "x")
        val before = ToolInstallGuard.snapshot()
        ToolInstallGuard.markLongRunning()
        ToolInstallGuard.requestCancel(noSignal)
        ToolInstallGuard.finish(failure(ToolFailure.INTERRUPTED))
        assertEquals(ToolInstallGuard.State(ToolInstallGuard.RUNNING, "tmux", HALF, "x", START), before)
    }

    @Test
    fun `State has no mutable properties`() {
        val setters = ToolInstallGuard.State::class.java.methods.filter { it.name.startsWith("set") }
        assertTrue(setters.isEmpty(), setters.toString())
        val nonFinal =
            ToolInstallGuard.State::class.java.declaredFields
                .filter {
                    !java.lang.reflect.Modifier
                        .isStatic(it.modifiers)
                }.filter {
                    !java.lang.reflect.Modifier
                        .isFinal(it.modifiers)
                }
        assertTrue(nonFinal.isEmpty(), nonFinal.toString())
    }

    @Test
    fun `the guard's running flag and process holder are static`() {
        val running = ToolInstallGuard::class.java.getDeclaredField("running")
        assertTrue(
            java.lang.reflect.Modifier
                .isStatic(running.modifiers),
        )
        val process = ToolInstallGuard::class.java.getDeclaredField("process")
        assertTrue(
            java.lang.reflect.Modifier
                .isStatic(process.modifiers),
        )
    }

    @Test
    fun `concurrent writers and readers never see a torn progress and message pair`() {
        ToolInstallGuard.tryStart("tmux", START)
        ToolInstallGuard.progress(LOW, "a")
        val stop = AtomicBoolean(false)
        val bad = ConcurrentLinkedQueue<ToolInstallGuard.State>()
        val barrier = CyclicBarrier(WRITERS + READERS)
        val writers =
            (0 until WRITERS).map { w ->
                Thread {
                    barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    var i = w
                    while (!stop.get()) {
                        if (i++ % 2 == 0) ToolInstallGuard.progress(LOW, "a") else ToolInstallGuard.progress(HIGH, "b")
                    }
                }
            }
        val readers =
            (0 until READERS).map {
                Thread {
                    barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    while (!stop.get()) {
                        val s = ToolInstallGuard.snapshot()
                        val ok =
                            s.phase == ToolInstallGuard.RUNNING &&
                                s.tool == "tmux" &&
                                ((s.progress == LOW && s.message == "a") || (s.progress == HIGH && s.message == "b"))
                        if (!ok) bad.add(s)
                    }
                }
            }
        (writers + readers).forEach { it.start() }
        Thread.sleep(RUN_MS)
        stop.set(true)
        (writers + readers).forEach { it.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS)) }
        assertTrue(bad.isEmpty(), "torn snapshots: ${bad.take(SHOW)}")
        assertTrue(ToolInstallGuard.isRunning())
    }

    // ── lost updates (were defects: progress/markLongRunning read-copy-wrote without the lock; now @Synchronized) ──

    /**
     * The script's output lines call progress() on the IO thread while the page's cancel calls
     * requestCancel(): if progress read RUNNING, the cancel wrote CANCELLING and progress wrote its
     * RUNNING copy back, the cancel request would be lost (the page offers Cancel again and the
     * run ends as FAILED/INTERRUPTED instead of CANCELLED). Lost in ~99% of 2000 rounds before
     * progress() took the lock.
     */
    @Test
    fun `progress racing a cancel request never loses the cancel`() {
        var lost = 0
        repeat(RACE_ROUNDS) {
            if (ToolInstallGuard.isRunning()) ToolInstallGuard.finish(ToolVerdict.Success)
            ToolInstallGuard.tryStart("tmux", START)
            val stop = AtomicBoolean(false)
            val writer = Thread { while (!stop.get()) ToolInstallGuard.progress(HALF, "line") }
            writer.start()
            Thread.sleep(0, RACE_DELAY_NS)
            ToolInstallGuard.requestCancel(noSignal)
            stop.set(true)
            writer.join()
            if (ToolInstallGuard.snapshot().phase != ToolInstallGuard.CANCELLING) lost++
            ToolInstallGuard.finish(ToolVerdict.Success)
        }
        assertEquals(0, lost, "cancel requests lost in $RACE_ROUNDS rounds")
    }

    /**
     * The 30-minute timer's markLongRunning() racing finish(): if it read RUNNING, finish wrote DONE
     * and freed the guard, and the stale RUNNING copy was written back, the page would show an
     * install that never ends while no install runs. Seen in ~98% of 2000 rounds before
     * markLongRunning() took the lock.
     */
    @Test
    fun `markLongRunning racing finish never brings back a busy state`() {
        var bad = 0
        repeat(RACE_ROUNDS) {
            ToolInstallGuard.tryStart("tmux", START)
            val stop = AtomicBoolean(false)
            val timer = Thread { while (!stop.get()) ToolInstallGuard.markLongRunning() }
            timer.start()
            Thread.sleep(0, RACE_DELAY_NS)
            ToolInstallGuard.finish(ToolVerdict.Success)
            stop.set(true)
            timer.join()
            if (ToolInstallGuard.snapshot().busy) bad++
        }
        assertEquals(0, bad, "busy state after finish in $RACE_ROUNDS rounds")
    }

    private companion object {
        const val RACE_ROUNDS = 500
        const val RETRIES = 5
        const val RACE_DELAY_NS = 200_000
        const val START = 1_700_000_000L
        const val LOW = 0.1f
        const val HALF = 0.5f
        const val HIGH = 0.9f
        const val WRITERS = 2
        const val READERS = 2
        const val RUN_MS = 300L
        const val WAIT_SECONDS = 5L
        const val SHOW = 5
    }
}
