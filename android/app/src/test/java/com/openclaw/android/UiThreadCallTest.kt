package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The rule that puts a terminal session's creation on the UI thread (see [UiThreadCall]). */
class UiThreadCallTest {
    /** A stand-in "UI thread": one named thread that runs what is posted to it, in order. */
    private val ui = Executors.newSingleThreadExecutor { Thread(it, "fake-ui") }

    private fun post(r: Runnable) = ui.execute(r)

    @Test
    fun `on the UI thread the block runs right there, nothing is posted`() {
        val posted = AtomicBoolean(false)
        val result = UiThreadCall.run(onUiThread = true, post = { posted.set(true) }) { Thread.currentThread().name }
        assertEquals(Thread.currentThread().name, result)
        assertFalse(posted.get(), "a call from the UI thread was posted (it would wait on itself)")
    }

    @Test
    fun `from another thread the block runs on the UI thread and its result comes back`() {
        val ranOn = AtomicReference<String>()
        val result =
            UiThreadCall.run(onUiThread = false, post = ::post) {
                ranOn.set(Thread.currentThread().name)
                42
            }
        assertEquals(42, result)
        assertEquals("fake-ui", ranOn.get())
    }

    @Test
    fun `an exception of the block reaches the caller as it was`() {
        val boom = IllegalArgumentException("boom")
        val thrown =
            assertThrows(IllegalArgumentException::class.java) {
                UiThreadCall.run<Unit>(onUiThread = false, post = ::post) { throw boom }
            }
        assertSame(boom, thrown)
    }

    @Test
    fun `a UI thread that does not get to it ends the wait, and the call never runs afterwards`() {
        val release = CountDownLatch(1)
        // The UI thread is busy: the posted call waits behind this
        ui.execute { release.await(10, TimeUnit.SECONDS) }
        val ran = AtomicBoolean(false)
        val started = System.nanoTime()
        assertThrows(IllegalStateException::class.java) {
            UiThreadCall.run(onUiThread = false, post = ::post, timeoutMs = 150) { ran.set(true) }
        }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(tookMs in 100..3000, "the wait took ${tookMs}ms")
        release.countDown()
        // Let the UI thread drain: the cancelled call must not run
        ui.submit {}.get(5, TimeUnit.SECONDS)
        assertFalse(ran.get(), "a call that was given up on ran later")
    }

    @Test
    fun `a call the UI thread already started is waited for, not given up on`() {
        val started = CountDownLatch(1)
        val ran = AtomicBoolean(false)
        // The block takes longer than the limit but is already running when the limit passes
        val result =
            UiThreadCall.run(onUiThread = false, post = ::post, timeoutMs = 200) {
                started.countDown()
                Thread.sleep(500)
                ran.set(true)
                "created"
            }
        assertEquals("created", result)
        assertTrue(ran.get())
    }

    @Test
    fun `the default limit is five seconds`() {
        assertEquals(5_000L, UiThreadCall.DEFAULT_TIMEOUT_MS)
    }
}
