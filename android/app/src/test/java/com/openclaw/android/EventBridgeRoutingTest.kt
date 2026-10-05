package com.openclaw.android

import android.webkit.WebView
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Drives the compiled EventBridge with mocked WebViews standing in for the page before (A) and
 * after (B) an Activity recreation. The process-wide "current view" is real; nothing is stubbed
 * inside EventBridge.
 */
class EventBridgeRoutingTest {
    private val created = Collections.synchronizedList(mutableListOf<WebView>())

    @BeforeEach
    fun setup() = clearCurrent()

    @AfterEach
    fun teardown() {
        clearCurrent()
        assertNull(EventBridge.attachedView(), "a test left a WebView attached")
    }

    private fun clearCurrent() {
        EventBridge.attachedView()?.let { EventBridge.detach(it) }
        created.forEach { EventBridge.detach(it) }
    }

    /** A WebView whose posted Runnables run inline; scripts handed to evaluateJavascript are kept. */
    private fun webView(scripts: MutableList<String> = Collections.synchronizedList(mutableListOf())): WebView {
        val view = mockk<WebView>(relaxed = true)
        every { view.post(any()) } answers {
            firstArg<Runnable>().run()
            true
        }
        every { view.evaluateJavascript(any(), any()) } answers {
            scripts.add(firstArg())
            Unit
        }
        created.add(view)
        return view
    }

    @Test
    fun `emit goes to the view the bridge was created with while it is current`() {
        val a = webView()
        val bridge = EventBridge(a)
        assertSame(a, EventBridge.attachedView())

        bridge.emit("setup_progress", mapOf("progress" to 0.5))

        verify(exactly = 1) { a.post(any()) }
    }

    @Test
    fun `an old bridge emits to the newer view after recreation`() {
        val a = webView()
        val b = webView()
        val oldBridge = EventBridge(a)
        EventBridge(b)
        assertSame(b, EventBridge.attachedView())

        oldBridge.emit("setup_progress", mapOf("progress" to 0.5))

        verify(exactly = 1) { b.post(any()) }
        verify(exactly = 0) { a.post(any()) }
    }

    @Test
    fun `a late detach of the old view does not cut off the new one`() {
        val a = webView()
        val b = webView()
        val oldBridge = EventBridge(a)
        EventBridge(b)

        // The old Activity's onDestroy runs after the new one attached
        EventBridge.detach(a)

        assertSame(b, EventBridge.attachedView())
        oldBridge.emit("x", null)
        verify(exactly = 1) { b.post(any()) }
        verify(exactly = 0) { a.post(any()) }
    }

    @Test
    fun `after the current view detaches events are dropped without error`() {
        val a = webView()
        val b = webView()
        val oldBridge = EventBridge(a)
        val newBridge = EventBridge(b)
        EventBridge.detach(b)

        assertNull(EventBridge.attachedView())
        assertDoesNotThrow {
            oldBridge.emit("x", mapOf("k" to "v"))
            newBridge.emit("y", null)
        }
        verify(exactly = 0) { a.post(any()) }
        verify(exactly = 0) { b.post(any()) }
    }

    @Test
    fun `the posted runnable evaluates the window __oc emit script with escaped json`() {
        val scripts = Collections.synchronizedList(mutableListOf<String>())
        val bridge = EventBridge(webView(scripts))

        bridge.emit("setup_progress", mapOf("message" to "it's \"quoted\"\n", "progress" to 0.25))

        assertEquals(1, scripts.size)
        val script = scripts.single()
        val prefix = "window.__oc&&window.__oc.emit('setup_progress',"
        assertTrue(script.startsWith(prefix), script)
        assertTrue(script.endsWith(")"), script)
        val json = script.removePrefix(prefix).removeSuffix(")")

        // The JSON is valid and round-trips the original values (quotes and newline escaped)
        @Suppress("UNCHECKED_CAST")
        val parsed =
            com.google.gson
                .Gson()
                .fromJson(json, Map::class.java) as Map<String, Any?>
        assertEquals("it's \"quoted\"\n", parsed["message"])
        assertEquals(0.25, parsed["progress"])
        assertTrue(!json.contains("\n"), "raw newline leaked into the script: $json")
    }

    @Test
    fun `a null payload is sent as an empty object`() {
        val scripts = Collections.synchronizedList(mutableListOf<String>())
        EventBridge(webView(scripts)).emit("ping", null)
        assertEquals("window.__oc&&window.__oc.emit('ping',{})", scripts.single())
    }

    @Test
    fun `concurrent attach and detach leave a consistent current view`() {
        repeat(ROUNDS) {
            val a = webView()
            val b = webView()
            EventBridge(a)
            val barrier = CyclicBarrier(2)
            val error = AtomicReference<Throwable?>(null)
            val attacher =
                Thread {
                    runCatching {
                        barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                        EventBridge(b)
                    }.onFailure { error.set(it) }
                }
            val detacher =
                Thread {
                    runCatching {
                        barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                        EventBridge.detach(a)
                    }.onFailure { error.set(it) }
                }
            attacher.start()
            detacher.start()
            attacher.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
            detacher.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))

            assertNull(error.get())
            // Whichever ran first, B was attached and detach(A) may only clear A — never B
            assertSame(b, EventBridge.attachedView())
            EventBridge.detach(b)
        }
    }

    @Test
    fun `two concurrent attaches end with exactly one of them current`() {
        repeat(ROUNDS) {
            val a = webView()
            val b = webView()
            val barrier = CyclicBarrier(2)
            val error = AtomicReference<Throwable?>(null)
            val threads =
                listOf(a, b).map { view ->
                    Thread {
                        runCatching {
                            barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                            EventBridge(view)
                        }.onFailure { error.set(it) }
                    }
                }
            threads.forEach { it.start() }
            threads.forEach { it.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS)) }

            assertNull(error.get())
            val now = EventBridge.attachedView()
            assertTrue(now === a || now === b, "current view is neither attached view")
            EventBridge.detach(a)
            EventBridge.detach(b)
            assertNull(EventBridge.attachedView())
        }
    }

    @Test
    fun `concurrent attach of B and detach of B ends with null or B`() {
        repeat(ROUNDS) {
            val b = webView()
            val barrier = CyclicBarrier(2)
            val error = AtomicReference<Throwable?>(null)
            val threads =
                listOf(
                    Thread {
                        runCatching {
                            barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                            EventBridge(b)
                        }.onFailure { error.set(it) }
                    },
                    Thread {
                        runCatching {
                            barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                            EventBridge.detach(b)
                        }.onFailure { error.set(it) }
                    },
                )
            threads.forEach { it.start() }
            threads.forEach { it.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS)) }

            assertNull(error.get())
            val now = EventBridge.attachedView()
            assertTrue(now == null || now === b)
            EventBridge.detach(b)
        }
    }

    private companion object {
        const val ROUNDS = 200
        const val WAIT_SECONDS = 5L
    }
}
