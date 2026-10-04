package com.openclaw.android

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the compiled process-wide guards, not the source text. */
class SetupGuardTest {
    @BeforeEach
    fun reset() = SetupGuard.finish()

    @AfterEach
    fun cleanup() = SetupGuard.finish()

    @Test
    fun `first tryStart wins and a second one is refused until finish`() {
        assertFalse(SetupGuard.isRunning())
        assertTrue(SetupGuard.tryStart())
        assertTrue(SetupGuard.isRunning())
        assertFalse(SetupGuard.tryStart())
        assertTrue(SetupGuard.isRunning())
        SetupGuard.finish()
        assertFalse(SetupGuard.isRunning())
        assertTrue(SetupGuard.tryStart())
    }

    @Test
    fun `finish without a running install is harmless`() {
        SetupGuard.finish()
        assertFalse(SetupGuard.isRunning())
        assertTrue(SetupGuard.tryStart())
    }

    @Test
    fun `exactly one of many concurrent tryStart calls wins`() {
        repeat(ROUNDS) {
            SetupGuard.finish()
            val barrier = CyclicBarrier(THREADS)
            val done = CountDownLatch(THREADS)
            val winners = AtomicInteger(0)
            repeat(THREADS) {
                Thread {
                    try {
                        barrier.await(AWAIT_SECONDS, TimeUnit.SECONDS)
                        if (SetupGuard.tryStart()) winners.incrementAndGet()
                    } finally {
                        done.countDown()
                    }
                }.start()
            }
            assertTrue(done.await(AWAIT_SECONDS, TimeUnit.SECONDS), "threads did not finish")
            assertEquals(1, winners.get())
            assertTrue(SetupGuard.isRunning())
        }
    }

    @Test
    fun `probe limiter allows three concurrent probes`() {
        assertEquals(3, ProbeLimiter.MAX_CONCURRENT)
    }

    @Test
    fun `probe limiter refuses a fourth permit until one is released`() {
        val semaphore = ProbeLimiter.semaphore
        var held = 0
        try {
            repeat(ProbeLimiter.MAX_CONCURRENT) {
                assertTrue(semaphore.tryAcquire())
                held++
            }
            assertEquals(0, semaphore.availablePermits)
            assertFalse(semaphore.tryAcquire())
            semaphore.release()
            held--
            assertTrue(semaphore.tryAcquire())
            held++
        } finally {
            repeat(held) { semaphore.release() }
        }
        assertEquals(ProbeLimiter.MAX_CONCURRENT, semaphore.availablePermits)
    }

    private companion object {
        const val THREADS = 16
        const val ROUNDS = 50
        const val AWAIT_SECONDS = 10L
    }
}
