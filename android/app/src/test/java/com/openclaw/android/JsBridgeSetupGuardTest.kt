package com.openclaw.android

import android.util.Log
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Two JsBridge instances stand in for the page before and after an Activity recreation. With a
 * per-instance guard the second bridge would start a second install; the guard must be shared.
 */
class JsBridgeSetupGuardTest {
    private val releaseFirst = CountDownLatch(1)
    private val firstEntered = CountDownLatch(1)

    @BeforeEach
    fun setup() {
        SetupGuard.finish()
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @AfterEach
    fun teardown() {
        releaseFirst.countDown()
        SetupGuard.finish()
        unmockkStatic(Log::class)
    }

    private fun bridge(bootstrap: BootstrapManager) =
        JsBridge(
            mockk<MainActivity>(relaxed = true),
            mockk<TerminalSessionManager>(relaxed = true),
            bootstrap,
            mockk<EventBridge>(relaxed = true),
        )

    @Test
    fun `a second bridge cannot start setup while the first install runs`() {
        val firstBootstrap = mockk<BootstrapManager>(relaxed = true)
        coEvery { firstBootstrap.startSetup(any()) } coAnswers {
            firstEntered.countDown()
            releaseFirst.await(WAIT_SECONDS, TimeUnit.SECONDS)
            Unit
        }
        val secondBootstrap = mockk<BootstrapManager>(relaxed = true)
        val thirdBootstrap = mockk<BootstrapManager>(relaxed = true)

        bridge(firstBootstrap).startSetup()
        assertTrue(firstEntered.await(WAIT_SECONDS, TimeUnit.SECONDS), "first install never started")
        assertTrue(SetupGuard.isRunning())

        // With a per-instance guard the second install would launch on Dispatchers.IO, so give it
        // time to show up before concluding it never started
        bridge(secondBootstrap).startSetup()
        val secondStarted =
            waitUntil(NEGATIVE_WAIT_MS) {
                runCatching { coVerify(atLeast = 1) { secondBootstrap.startSetup(any()) } }.isSuccess
            }
        assertFalse(secondStarted, "a second bridge started another install while the first was running")
        coVerify(exactly = 0) { secondBootstrap.startSetup(any()) }

        releaseFirst.countDown()
        assertTrue(waitUntil { !SetupGuard.isRunning() }, "guard was not released after the install")
        coVerify(exactly = 1) { firstBootstrap.startSetup(any()) }

        bridge(thirdBootstrap).startSetup()
        assertTrue(
            waitUntil { runCatching { coVerify(exactly = 1) { thirdBootstrap.startSetup(any()) } }.isSuccess },
            "a new install could not start after the first finished",
        )
        assertTrue(waitUntil { !SetupGuard.isRunning() })
    }

    @Test
    fun `the same bridge refuses a double tap`() {
        val bootstrap = mockk<BootstrapManager>(relaxed = true)
        coEvery { bootstrap.startSetup(any()) } coAnswers {
            firstEntered.countDown()
            releaseFirst.await(WAIT_SECONDS, TimeUnit.SECONDS)
            Unit
        }
        val bridge = bridge(bootstrap)
        bridge.startSetup()
        assertTrue(firstEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))
        bridge.startSetup()
        Thread.sleep(NEGATIVE_WAIT_MS)
        coVerify(exactly = 1) { bootstrap.startSetup(any()) }
        releaseFirst.countDown()
        assertTrue(waitUntil { !SetupGuard.isRunning() })
        coVerify(exactly = 1) { bootstrap.startSetup(any()) }
    }

    @Test
    fun `the guard is released when the install throws`() {
        val bootstrap = mockk<BootstrapManager>(relaxed = true)
        coEvery { bootstrap.startSetup(any()) } throws IllegalStateException("boom")
        bridge(bootstrap).startSetup()
        assertTrue(waitUntil { !SetupGuard.isRunning() }, "guard stuck after a failed install")
        coVerify(exactly = 1) { bootstrap.startSetup(any()) }
        assertTrue(SetupGuard.tryStart())
    }

    @Test
    fun `the guard is released after a refused download`() {
        val bootstrap = mockk<BootstrapManager>(relaxed = true)
        coEvery { bootstrap.startSetup(any()) } throws
            BootstrapDownloadException(BootstrapDownloadException.Kind.NETWORK, "offline")
        bridge(bootstrap).startSetup()
        assertTrue(waitUntil { !SetupGuard.isRunning() }, "guard stuck after a refused download")
        assertFalse(SetupGuard.isRunning())
    }

    private fun waitUntil(
        timeoutMs: Long = TimeUnit.SECONDS.toMillis(WAIT_SECONDS),
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_MS)
        }
        return condition()
    }

    private companion object {
        const val WAIT_SECONDS = 5L
        const val POLL_MS = 10L
        const val NEGATIVE_WAIT_MS = 500L
    }
}
