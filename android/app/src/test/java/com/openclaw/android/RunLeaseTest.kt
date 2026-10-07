package com.openclaw.android

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

/** [RunLease]: one holder at a time whatever the kind, released only by its holder. */
internal class RunLeaseTest {
    @BeforeEach
    fun setup() = clear()

    @AfterEach
    fun teardown() = clear()

    private fun clear() {
        RunLease.owner()?.let { RunLease.release(it) }
        assertNull(RunLease.owner())
    }

    @Test
    fun `a tool install and an update exclude each other in both orders`() {
        assertTrue(RunLease.tryAcquire(RunLease.TOOLS))
        assertFalse(RunLease.tryAcquire(RunKinds.UPDATE))
        assertFalse(RunLease.tryAcquire(RunLease.TOOLS), "a second tool install got the lease")
        RunLease.release(RunLease.TOOLS)
        assertTrue(RunLease.tryAcquire(RunKinds.UPDATE))
        assertFalse(RunLease.tryAcquire(RunLease.TOOLS))
        assertEquals(RunKinds.UPDATE, RunLease.owner())
    }

    @Test
    fun `only the holder can release - a late release of another run frees nothing`() {
        assertTrue(RunLease.tryAcquire(RunKinds.UPDATE))
        RunLease.release(RunLease.TOOLS)
        RunLease.release("")
        assertEquals(RunKinds.UPDATE, RunLease.owner())
        RunLease.release(RunKinds.UPDATE)
        assertNull(RunLease.owner())
        RunLease.release(RunKinds.UPDATE) // twice: harmless
        assertNull(RunLease.owner())
    }

    @Test
    fun `an empty owner never gets the lease`() {
        assertFalse(RunLease.tryAcquire(""))
        assertNull(RunLease.owner())
    }

    @Test
    fun `eight threads racing for the lease - exactly one wins every round`() {
        val owners = listOf(RunLease.TOOLS, RunKinds.UPDATE)
        repeat(200) { round ->
            clear()
            val barrier = CyclicBarrier(THREADS)
            val winners = Collections.synchronizedList(mutableListOf<String>())
            val threads =
                (0 until THREADS).map { i ->
                    Thread {
                        val owner = owners[i % owners.size]
                        barrier.await(5, TimeUnit.SECONDS)
                        if (RunLease.tryAcquire(owner)) winners += owner
                    }.also { it.start() }
                }
            threads.forEach { it.join(TimeUnit.SECONDS.toMillis(5)) }
            assertEquals(1, winners.size, "round $round: $winners")
            assertEquals(winners.single(), RunLease.owner())
        }
    }

    private companion object {
        const val THREADS = 8
    }
}
