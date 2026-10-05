package com.openclaw.android

import android.util.Log
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * getSetupState and the refused-start re-emit, exercised through compiled JsBridge instances with
 * a real EventBridge and SetupGuard. Two bridges (each with its own WebView) stand in for the page
 * before and after an Activity recreation during an install.
 */
class JsBridgeSetupStateTest {
    private val release = CountDownLatch(1)
    private val entered = CountDownLatch(1)
    private val pages = mutableListOf<RecordingWebView>()

    @BeforeEach
    fun setup() {
        resetGlobals()
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @AfterEach
    fun teardown() {
        release.countDown()
        assertTrue(TestWait.until { !SetupGuard.isRunning() }, "an install coroutine outlived its test")
        resetGlobals()
        unmockkStatic(Log::class)
    }

    private fun resetGlobals() {
        SetupGuard.finish()
        if (SetupGuard.tryStart()) SetupGuard.finish()
        pages.forEach { EventBridge.detach(it.view) }
        EventBridge.attachedView()?.let { EventBridge.detach(it) }
    }

    /** A new page: its WebView becomes current, as MainActivity.onCreate does. */
    private fun page(bootstrap: BootstrapManager): Pair<JsBridge, RecordingWebView> {
        val web = RecordingWebView()
        pages.add(web)
        val bridge =
            JsBridge(
                mockk<MainActivity>(relaxed = true),
                mockk<TerminalSessionManager>(relaxed = true),
                bootstrap,
                EventBridge(web.view),
            )
        return bridge to web
    }

    /** An install that reports 0.3 "Extracting", then blocks until [release], then reports done. */
    private fun blockingBootstrap(): BootstrapManager {
        val bootstrap = mockk<BootstrapManager>(relaxed = true)
        coEvery { bootstrap.startSetup(any()) } coAnswers {
            val onProgress = firstArg<(Float, String) -> Unit>()
            onProgress(MID, "Extracting")
            entered.countDown()
            release.await(TestWait.WAIT_SECONDS, TimeUnit.SECONDS)
            onProgress(1f, "Setup complete")
        }
        return bootstrap
    }

    @Suppress("UNCHECKED_CAST")
    private fun JsBridge.state(): Map<String, Any?> =
        Gson().fromJson(getSetupState(), Map::class.java) as Map<String, Any?>

    private fun assertNumber(
        expected: Float,
        actual: Any?,
    ) = assertEquals(expected.toDouble(), (actual as Number).toDouble(), EPS)

    @Test
    fun `getSetupState reports the running install and its last progress`() {
        val (bridge, _) = page(blockingBootstrap())
        bridge.startSetup()
        assertTrue(entered.await(TestWait.WAIT_SECONDS, TimeUnit.SECONDS), "install never started")

        val s = bridge.state()
        assertEquals("running", s["phase"])
        assertEquals(true, s["running"])
        assertNumber(MID, s["progress"])
        assertEquals("Extracting", s["message"])
        assertNull(s["errorKind"])
        assertNull(s["error"])
    }

    @Test
    fun `a recreated page reads the running state and its refused start re-emits progress to the new view`() {
        val (oldBridge, oldPage) = page(blockingBootstrap())
        oldBridge.startSetup()
        assertTrue(entered.await(TestWait.WAIT_SECONDS, TimeUnit.SECONDS))
        assertTrue(oldPage.events("setup_progress").isNotEmpty(), "the first page got no progress")

        // Activity recreated: new WebView, new bridge; the old Activity's onDestroy detaches late
        val secondBootstrap = mockk<BootstrapManager>(relaxed = true)
        val (newBridge, newPage) = page(secondBootstrap)
        EventBridge.detach(oldPage.view)
        oldPage.clear()

        val s = newBridge.state()
        assertEquals("running", s["phase"])
        assertNumber(MID, s["progress"])

        newBridge.startSetup()

        // The refusal is synchronous: exactly one re-emit, on the new page, with the current progress
        val reemitted = newPage.events("setup_progress")
        assertEquals(1, reemitted.size, newPage.scripts.toString())
        assertNumber(MID, reemitted.single().data["progress"])
        assertEquals("Extracting", reemitted.single().data["message"])
        assertTrue(newPage.scripts.single().contains("'setup_progress'"))
        assertTrue(newPage.scripts.single().contains("0.3"))
        assertTrue(oldPage.scripts.isEmpty(), "an event went to the destroyed page")
        assertTrue(
            !TestWait.until(NEGATIVE_WAIT_MS) {
                runCatching { coVerify(atLeast = 1) { secondBootstrap.startSetup(any()) } }.isSuccess
            },
            "the second page started another install",
        )

        // The install started by the old bridge now reports to the new page
        release.countDown()
        assertTrue(TestWait.until { newPage.events("setup_progress").any { (it.data["progress"] as Number) == 1.0 } })
        assertTrue(oldPage.scripts.isEmpty(), "an event went to the destroyed page")
    }

    @Test
    fun `after the install completes the state is done and not running`() {
        val (bridge, page) = page(blockingBootstrap())
        bridge.startSetup()
        assertTrue(entered.await(TestWait.WAIT_SECONDS, TimeUnit.SECONDS))
        release.countDown()
        assertTrue(TestWait.until { !SetupGuard.isRunning() })

        val s = bridge.state()
        assertEquals("done", s["phase"])
        assertEquals(false, s["running"])
        assertNumber(1f, s["progress"])
        assertEquals("Setup complete", s["message"])
        assertTrue(page.events("setup_progress").any { it.data["message"] == "Setup complete" })
    }

    @Test
    fun `a refused download is recorded as failed with its kind and a retry starts normally`() {
        val failing = mockk<BootstrapManager>(relaxed = true)
        coEvery { failing.startSetup(any()) } throws
            BootstrapDownloadException(BootstrapDownloadException.Kind.NETWORK, "offline")
        val (bridge, page) = page(failing)
        bridge.startSetup()
        assertTrue(TestWait.until { !SetupGuard.isRunning() })

        val s = bridge.state()
        assertEquals("failed", s["phase"])
        assertEquals(false, s["running"])
        assertEquals("NETWORK", s["errorKind"])
        assertEquals("offline", s["error"])
        assertTrue(TestWait.until { page.events("setup_progress").any { it.data["errorKind"] == "NETWORK" } })
        val event = page.events("setup_progress").first { it.data["errorKind"] == "NETWORK" }
        assertEquals("offline", event.data["error"])

        // Retry from the same page (Setup.tsx → startInstall)
        val retry = blockingBootstrap()
        val (retryBridge, _) = page(retry)
        retryBridge.startSetup()
        assertTrue(entered.await(TestWait.WAIT_SECONDS, TimeUnit.SECONDS), "retry did not start")
        coVerify(exactly = 1) { retry.startSetup(any()) }
        val r = retryBridge.state()
        assertEquals("running", r["phase"])
        assertNull(r["errorKind"], "the previous failure leaked into the retry")
        release.countDown()
        assertTrue(TestWait.until { !SetupGuard.isRunning() })
        assertEquals("done", retryBridge.state()["phase"])
    }

    @Test
    fun `an unexpected exception is recorded as UNKNOWN and releases the guard`() {
        val failing = mockk<BootstrapManager>(relaxed = true)
        coEvery { failing.startSetup(any()) } throws IllegalStateException("boom")
        val (bridge, page) = page(failing)
        bridge.startSetup()
        assertTrue(TestWait.until { !SetupGuard.isRunning() }, "guard stuck after an unexpected exception")

        val s = bridge.state()
        assertEquals("failed", s["phase"])
        assertEquals("UNKNOWN", s["errorKind"])
        assertEquals("boom", s["error"])
        // The coroutine handler reports it to the page too
        assertTrue(TestWait.until { page.events("setup_progress").any { it.data["errorKind"] == "UNKNOWN" } })
        assertTrue(SetupGuard.tryStart(), "a new install could not start")
        SetupGuard.finish()
    }

    @Test
    fun `getSetupState on a fresh process is idle`() {
        val (bridge, _) = page(mockk(relaxed = true))
        val s = bridge.state()
        assertEquals("idle", s["phase"])
        assertEquals(false, s["running"])
        assertFalse(SetupGuard.isRunning())
    }

    private companion object {
        const val MID = 0.3f
        const val EPS = 1e-6
        const val NEGATIVE_WAIT_MS = 300L
    }
}
