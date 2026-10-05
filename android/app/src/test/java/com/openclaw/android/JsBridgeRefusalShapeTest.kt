package com.openclaw.android

import android.util.Log
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The shape of the refused-start re-emit (a held guard over a failed state must carry error and
 * errorKind so Setup.tsx draws the failure screen) and cancellation not being recorded as a failure.
 */
class JsBridgeRefusalShapeTest {
    private val pages = mutableListOf<RecordingWebView>()

    /** True when the test itself took the guard (no install coroutine will release it). */
    private var heldByTest = false

    @BeforeEach
    fun setup() {
        resetGlobals()
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @AfterEach
    fun teardown() {
        if (!heldByTest) {
            assertTrue(TestWait.until { !SetupGuard.isRunning() }, "an install coroutine outlived its test")
        }
        resetGlobals()
        unmockkStatic(Log::class)
    }

    private fun resetGlobals() {
        SetupGuard.finish()
        heldByTest = false
        // Clear a leftover done/failed state: a fresh tryStart+finish goes back to idle
        if (SetupGuard.tryStart()) SetupGuard.finish()
        pages.forEach { EventBridge.detach(it.view) }
        EventBridge.attachedView()?.let { EventBridge.detach(it) }
    }

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

    @Test
    fun `a refused start over a failed state re-emits error and errorKind with progress and message`() {
        // Hold the guard first: failed() after tryStart() leaves running=true with a failed snapshot
        assertTrue(SetupGuard.tryStart())
        heldByTest = true
        SetupGuard.failed("NETWORK", "m")
        val bootstrap = mockk<BootstrapManager>(relaxed = true)
        val (bridge, web) = page(bootstrap)

        bridge.startSetup()

        val events = web.events("setup_progress")
        assertEquals(1, events.size, web.scripts.toString())
        val data = events.single().data
        assertEquals("NETWORK", data["errorKind"])
        assertEquals("m", data["error"])
        assertEquals("m", data["message"])
        assertTrue(data.containsKey("progress"), "progress missing: $data")
        assertEquals(0.0, (data["progress"] as Number).toDouble(), EPS)
        assertTrue(SetupGuard.isRunning(), "the refusal released a guard it does not own")
    }

    @Test
    fun `a refused start over a running state carries no error keys`() {
        assertTrue(SetupGuard.tryStart())
        heldByTest = true
        SetupGuard.progress(MID, "Extracting")
        val (bridge, web) = page(mockk(relaxed = true))

        bridge.startSetup()

        val events = web.events("setup_progress")
        assertEquals(1, events.size, web.scripts.toString())
        val data = events.single().data
        assertFalse(data.containsKey("error"), "error key present: $data")
        assertFalse(data.containsKey("errorKind"), "errorKind key present: $data")
        assertEquals("Extracting", data["message"])
        assertEquals(MID.toDouble(), (data["progress"] as Number).toDouble(), EPS)
    }

    @Test
    fun `a cancelled install releases the guard and is not recorded as failed`() {
        val bootstrap = mockk<BootstrapManager>(relaxed = true)
        coEvery { bootstrap.startSetup(any()) } throws CancellationException("x")
        val (bridge, web) = page(bootstrap)

        bridge.startSetup()

        assertTrue(TestWait.until { !SetupGuard.isRunning() }, "guard stuck after cancellation")
        val s = SetupGuard.snapshot()
        assertNotEquals(SetupGuard.PHASE_FAILED, s.phase, "cancellation recorded as failure: $s")
        assertEquals(SetupGuard.PHASE_IDLE, s.phase)
        assertEquals(null, s.errorKind)
        // Observation only (not asserted): whether the coroutine handler emitted anything
        Thread.sleep(OBSERVE_MS)
        println("cancel-observation: setup_progress events = ${web.events("setup_progress")}")
        assertTrue(SetupGuard.tryStart(), "a new install could not start after cancellation")
        SetupGuard.finish()
    }

    private companion object {
        const val MID = 0.3f
        const val EPS = 1e-6
        const val OBSERVE_MS = 300L
    }
}
