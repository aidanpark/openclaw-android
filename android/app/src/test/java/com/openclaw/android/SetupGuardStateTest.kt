package com.openclaw.android

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The state a recreated page reads back from the compiled process-wide SetupGuard. */
class SetupGuardStateTest {
    @BeforeEach
    fun setup() = resetToIdle()

    @AfterEach
    fun teardown() {
        resetToIdle()
        assertFalse(SetupGuard.isRunning())
        assertEquals(SetupGuard.PHASE_IDLE, SetupGuard.snapshot().phase)
    }

    /** running → idle is the only route back to idle, so take it. */
    private fun resetToIdle() {
        SetupGuard.finish()
        assertTrue(SetupGuard.tryStart(), "guard still held by someone else")
        SetupGuard.finish()
    }

    @Test
    fun `tryStart sets a fresh running state`() {
        assertTrue(SetupGuard.tryStart())
        val s = SetupGuard.snapshot()
        assertEquals(SetupGuard.PHASE_RUNNING, s.phase)
        assertTrue(s.running)
        assertEquals(0f, s.progress)
        assertEquals("", s.message)
        assertNull(s.errorKind)
        assertNull(s.error)
    }

    @Test
    fun `progress below one stays running and one means done`() {
        SetupGuard.tryStart()
        SetupGuard.progress(PARTIAL, "x")
        SetupGuard.snapshot().let {
            assertEquals(SetupGuard.PHASE_RUNNING, it.phase)
            assertEquals(PARTIAL, it.progress)
            assertEquals("x", it.message)
        }
        SetupGuard.progress(1f, "done")
        SetupGuard.snapshot().let {
            assertEquals(SetupGuard.PHASE_DONE, it.phase)
            assertFalse(it.running)
            assertEquals(1f, it.progress)
            assertEquals("done", it.message)
        }
    }

    @Test
    fun `failed records kind and message`() {
        SetupGuard.tryStart()
        SetupGuard.failed("NETWORK", "m")
        val s = SetupGuard.snapshot()
        assertEquals(SetupGuard.PHASE_FAILED, s.phase)
        assertEquals("NETWORK", s.errorKind)
        assertEquals("m", s.error)
        assertFalse(s.running)
    }

    @Test
    fun `finish keeps a done result readable and releases the guard`() {
        SetupGuard.tryStart()
        SetupGuard.progress(1f, "done")
        SetupGuard.finish()
        assertFalse(SetupGuard.isRunning())
        assertEquals(SetupGuard.PHASE_DONE, SetupGuard.snapshot().phase)
        assertEquals(1f, SetupGuard.snapshot().progress)
    }

    @Test
    fun `finish keeps a failed result readable and releases the guard`() {
        SetupGuard.tryStart()
        SetupGuard.failed("HASH_MISMATCH", "bad")
        SetupGuard.finish()
        assertFalse(SetupGuard.isRunning())
        val s = SetupGuard.snapshot()
        assertEquals(SetupGuard.PHASE_FAILED, s.phase)
        assertEquals("HASH_MISMATCH", s.errorKind)
    }

    @Test
    fun `finish while still running falls back to idle`() {
        SetupGuard.tryStart()
        SetupGuard.progress(PARTIAL, "x")
        SetupGuard.finish()
        assertFalse(SetupGuard.isRunning())
        val s = SetupGuard.snapshot()
        assertEquals(SetupGuard.PHASE_IDLE, s.phase)
        assertEquals(0f, s.progress)
        assertEquals("", s.message)
    }

    @Test
    fun `the next tryStart clears the previous result`() {
        SetupGuard.tryStart()
        SetupGuard.failed("NETWORK", "offline")
        SetupGuard.finish()

        assertTrue(SetupGuard.tryStart())
        val s = SetupGuard.snapshot()
        assertEquals(SetupGuard.PHASE_RUNNING, s.phase)
        assertNull(s.errorKind)
        assertNull(s.error)
        assertEquals(0f, s.progress)
    }

    @Test
    fun `a refused tryStart does not reset the running state`() {
        SetupGuard.tryStart()
        SetupGuard.progress(PARTIAL, "x")
        assertFalse(SetupGuard.tryStart())
        assertEquals(PARTIAL, SetupGuard.snapshot().progress)
        assertEquals("x", SetupGuard.snapshot().message)
    }

    @Test
    fun `an earlier snapshot is not changed by later updates`() {
        SetupGuard.tryStart()
        SetupGuard.progress(PARTIAL, "x")
        val before = SetupGuard.snapshot()
        SetupGuard.progress(1f, "done")
        SetupGuard.failed("NETWORK", "m")
        SetupGuard.finish()

        assertEquals(SetupGuard.State(SetupGuard.PHASE_RUNNING, PARTIAL, "x"), before)
    }

    @Test
    fun `State has no mutable properties`() {
        // Reflection on the compiled class: a var would make a "snapshot" shareable mutable state
        val setters = SetupGuard.State::class.java.methods.filter { it.name.startsWith("set") }
        assertTrue(setters.isEmpty(), setters.toString())
        val nonFinal =
            SetupGuard.State::class.java.declaredFields
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
    fun `concurrent writers and readers never see a torn progress and message pair`() {
        SetupGuard.tryStart()
        SetupGuard.progress(LOW, "a")
        val stop = AtomicBoolean(false)
        val bad = ConcurrentLinkedQueue<SetupGuard.State>()
        val barrier = CyclicBarrier(WRITERS + READERS)
        val writers =
            (0 until WRITERS).map { w ->
                Thread {
                    barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    var i = w
                    while (!stop.get()) {
                        if (i++ % 2 == 0) SetupGuard.progress(LOW, "a") else SetupGuard.progress(HIGH, "b")
                    }
                }
            }
        val readers =
            (0 until READERS).map {
                Thread {
                    barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    while (!stop.get()) {
                        val s = SetupGuard.snapshot()
                        val ok =
                            s.phase == SetupGuard.PHASE_RUNNING &&
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
        assertTrue(SetupGuard.isRunning())
    }

    private companion object {
        const val PARTIAL = 0.4f
        const val LOW = 0.1f
        const val HIGH = 0.9f
        const val WRITERS = 2
        const val READERS = 2
        const val RUN_MS = 300L
        const val WAIT_SECONDS = 5L
        const val SHOW = 5
    }
}
