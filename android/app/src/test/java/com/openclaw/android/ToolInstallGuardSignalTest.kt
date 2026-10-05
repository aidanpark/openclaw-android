package com.openclaw.android

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * How [ToolInstallGuard] delivers the cancel SIGTERM: the `/proc` read and the signal run OUTSIDE
 * the monitor, only one thread signals at a time, a failed or thrown delivery can be retried, and a
 * delivery that ends after its run was finished and a new run started never touches the new run's
 * flags. A signal is played by a lambda that can be held inside, so the calls overlap for sure.
 */
internal class ToolInstallGuardSignalTest {
    private val held = mutableListOf<HeldSignal>()
    private val threads = mutableListOf<Thread>()

    @BeforeEach
    fun setup() = release()

    @AfterEach
    fun teardown() {
        held.forEach { it.open() }
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS)) }
        val stuck = threads.filter { it.isAlive }
        release()
        assertTrue(stuck.isEmpty(), "threads still blocked: $stuck")
    }

    private fun release() {
        ToolInstallGuard.process.set(null)
        if (ToolInstallGuard.isRunning()) ToolInstallGuard.finish(ToolVerdict.Success)
        assertTrue(ToolInstallGuard.tryStart("reset", 0L), "guard held by someone else")
        ToolInstallGuard.finish(ToolVerdict.Success)
    }

    /** A signal that, once called, waits inside until [open], then answers [delivered]. */
    private inner class HeldSignal(
        private val delivered: Boolean = true,
    ) : () -> Boolean {
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        private val gate = CountDownLatch(1)

        init {
            held.add(this)
        }

        override fun invoke(): Boolean {
            calls.incrementAndGet()
            entered.countDown()
            gate.await(WAIT_SECONDS, TimeUnit.SECONDS)
            return delivered
        }

        fun open() = gate.countDown()

        fun awaitEntered() = assertTrue(entered.await(WAIT_SECONDS, TimeUnit.SECONDS), "the signal was never called")
    }

    /** Counts calls and answers at once. */
    private class CountingSignal(
        private val delivered: Boolean = true,
    ) : () -> Boolean {
        val calls = AtomicInteger()

        override fun invoke(): Boolean {
            calls.incrementAndGet()
            return delivered
        }
    }

    private fun background(body: () -> Unit): Thread = Thread(body).also { threads.add(it) }.apply { start() }

    /** Runs [body] on another thread and says whether it returned within [ms] (it is not blocked). */
    private fun returnsWithin(
        ms: Long = PROMPT_MS,
        body: () -> Unit,
    ): Boolean {
        val done = CountDownLatch(1)
        background {
            body()
            done.countDown()
        }
        return done.await(ms, TimeUnit.MILLISECONDS)
    }

    // ── one sender at a time ────────────────────────────────────────────────

    @Test
    fun `a requestCancel and a retryCancel while the first signal is still being sent do not signal again`() {
        val signal = HeldSignal(delivered = true)
        ToolInstallGuard.tryStart("tmux", START, token = TOKEN)
        background { ToolInstallGuard.requestCancel(signal) }
        signal.awaitEntered()

        // The page's second tap and the 1 s pump arrive while the first SIGTERM is under way
        assertTrue(returnsWithin { assertTrue(ToolInstallGuard.requestCancel(signal)) }, "requestCancel blocked")
        assertTrue(returnsWithin { ToolInstallGuard.retryCancel(signal) }, "retryCancel blocked")
        assertEquals(1, signal.calls.get(), "a second thread signalled while the first was still signalling")

        signal.open()
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS)) }
        repeat(RETRIES) { ToolInstallGuard.retryCancel(signal) }
        ToolInstallGuard.requestCancel(signal)
        assertEquals(1, signal.calls.get(), "a delivered SIGTERM was sent again")
        assertEquals(ToolInstallGuard.CANCELLING, ToolInstallGuard.snapshot().phase)
    }

    @Test
    fun `many threads racing requestCancel and retryCancel call the signal exactly once`() {
        val calls = AtomicInteger()
        val inside = AtomicInteger()
        val overlap = AtomicInteger()
        val slowSignal: () -> Boolean = {
            calls.incrementAndGet()
            if (inside.incrementAndGet() > 1) overlap.incrementAndGet()
            Thread.sleep(SLOW_SIGNAL_MS)
            inside.decrementAndGet()
            true
        }
        ToolInstallGuard.tryStart("tmux", START, token = TOKEN)
        ToolInstallGuard.requestCancel { false } // cancelling, nothing delivered yet
        val barrier = CyclicBarrier(RACERS)
        repeat(RACERS) { i ->
            background {
                barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                if (i % 2 == 0) ToolInstallGuard.requestCancel(slowSignal) else ToolInstallGuard.retryCancel(slowSignal)
            }
        }
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS)) }
        assertEquals(1, calls.get(), "the SIGTERM was sent ${calls.get()} times")
        assertEquals(0, overlap.get(), "two signals ran at the same time")
    }

    @Test
    fun `an undelivered signal frees the sender, so the next retry signals again`() {
        val first = HeldSignal(delivered = false)
        ToolInstallGuard.tryStart("tmux", START, token = TOKEN)
        background { ToolInstallGuard.requestCancel(first) }
        first.awaitEntered()
        first.open()
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS)) }

        val second = CountingSignal(delivered = true)
        ToolInstallGuard.retryCancel(second)
        assertEquals(1, second.calls.get(), "the retry after an undelivered signal was refused")
    }

    // ── the signal never blocks the output reader or the page ───────────────

    @Test
    fun `progress, markLongRunning, snapshot and cancelRequested are not blocked while the signal is slow`() {
        val signal = HeldSignal()
        ToolInstallGuard.tryStart("tmux", START, token = TOKEN)
        background { ToolInstallGuard.requestCancel(signal) }
        signal.awaitEntered()

        assertTrue(returnsWithin { ToolInstallGuard.progress(HALF, "npm line") }, "progress() waited for the signal")
        assertTrue(returnsWithin { ToolInstallGuard.markLongRunning() }, "markLongRunning() waited for the signal")
        assertTrue(returnsWithin { ToolInstallGuard.snapshot() }, "snapshot() waited for the signal")
        assertTrue(returnsWithin { ToolInstallGuard.cancelRequested() }, "cancelRequested() waited for the signal")
        assertEquals(1, signal.calls.get())

        val s = ToolInstallGuard.snapshot()
        assertEquals(ToolInstallGuard.CANCELLING, s.phase)
        assertEquals("npm line", s.message)
        assertEquals(HALF, s.progress)
        assertTrue(s.longRunning)
    }

    @Test
    fun `finish is not blocked by a signal that is still being sent`() {
        val signal = HeldSignal()
        ToolInstallGuard.tryStart("tmux", START, token = TOKEN)
        background { ToolInstallGuard.requestCancel(signal) }
        signal.awaitEntered()
        assertTrue(returnsWithin { ToolInstallGuard.finish(ToolVerdict.Failure(ToolFailure.INTERRUPTED)) })
        assertEquals(ToolInstallGuard.CANCELLED, ToolInstallGuard.snapshot().phase)
        assertFalse(ToolInstallGuard.isRunning())
    }

    // ── a signal that throws ────────────────────────────────────────────────

    @Test
    fun `a signal that throws leaves the sender free, so the next retry can deliver`() {
        val thrown = CountingSignal()
        val throwing: () -> Boolean = {
            thrown.invoke()
            throw SecurityException("sendSignal refused")
        }
        ToolInstallGuard.tryStart("tmux", START, token = TOKEN)
        // Whether the error reaches the caller is not the point here; the flag must be released
        runCatching { ToolInstallGuard.requestCancel(throwing) }
        assertEquals(1, thrown.calls.get())
        assertEquals(ToolInstallGuard.CANCELLING, ToolInstallGuard.snapshot().phase)

        val next = CountingSignal(delivered = true)
        ToolInstallGuard.retryCancel(next)
        assertEquals(1, next.calls.get(), "a thrown signal kept the sender flag set: no retry possible")
        ToolInstallGuard.retryCancel(next)
        assertEquals(1, next.calls.get(), "a delivered SIGTERM was sent again")
    }

    @Test
    fun `a signal that throws on retryCancel also leaves the sender free`() {
        ToolInstallGuard.tryStart("tmux", START, token = TOKEN)
        ToolInstallGuard.requestCancel { false }
        assertThrows(SecurityException::class.java) {
            ToolInstallGuard.retryCancel { throw SecurityException("sendSignal refused") }
        }
        val next = CountingSignal(delivered = true)
        ToolInstallGuard.retryCancel(next)
        assertEquals(1, next.calls.get())
    }

    // ── a delivery that outlives its run ────────────────────────────────────

    @Test
    fun `a delivery that ends after a new run started does not mark the new run as signalled`() {
        val old = HeldSignal(delivered = true)
        ToolInstallGuard.tryStart("tmux", START, token = TOKEN)
        background { ToolInstallGuard.requestCancel(old) }
        old.awaitEntered()

        // The old script ended on its own and the page started the next install meanwhile
        ToolInstallGuard.finish(ToolVerdict.Failure(ToolFailure.INTERRUPTED))
        assertTrue(ToolInstallGuard.tryStart("ttyd", START + 1, token = OTHER_TOKEN))
        old.open()
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS)) }

        val fresh = CountingSignal(delivered = true)
        assertTrue(ToolInstallGuard.requestCancel(fresh))
        assertEquals(1, fresh.calls.get(), "the old run's delivery set termSent on the new run")
        assertEquals(OTHER_TOKEN, ToolInstallGuard.runToken)
    }

    @Test
    fun `a delivery that ends after a new run started does not free the new run's own sender`() {
        val old = HeldSignal(delivered = false)
        ToolInstallGuard.tryStart("tmux", START, token = TOKEN)
        background { ToolInstallGuard.requestCancel(old) }
        old.awaitEntered()
        ToolInstallGuard.finish(ToolVerdict.Failure(ToolFailure.INTERRUPTED))
        assertTrue(ToolInstallGuard.tryStart("ttyd", START + 1, token = OTHER_TOKEN))

        // The new run's cancel is being sent when the old delivery returns
        val current = HeldSignal(delivered = true)
        background { ToolInstallGuard.requestCancel(current) }
        current.awaitEntered()
        old.open()
        threads.first().join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))

        val third = CountingSignal(delivered = true)
        ToolInstallGuard.retryCancel(third)
        assertEquals(0, third.calls.get(), "the old delivery cleared the new run's sender flag: two signals at once")
        current.open()
    }

    private companion object {
        const val START = 1_700_000_000L
        const val TOKEN = "3f1c9a2e-7b44-4d0e-9a51-0c6f2d8e1b7a"
        const val OTHER_TOKEN = "9d2b7e10-1c3a-4f55-8e60-2a7b9c4d5e6f"
        const val HALF = 0.5f
        const val RETRIES = 5
        const val RACERS = 8
        const val SLOW_SIGNAL_MS = 100L
        const val PROMPT_MS = 1_000L
        const val WAIT_SECONDS = 5L
    }
}
