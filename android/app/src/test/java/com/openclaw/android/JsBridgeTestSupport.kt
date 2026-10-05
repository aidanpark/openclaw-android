package com.openclaw.android

import android.webkit.WebView
import com.google.gson.Gson
import io.mockk.every
import io.mockk.mockk
import java.util.Collections
import java.util.concurrent.TimeUnit

/** One event as the page would receive it: `window.__oc.emit(type, data)`. */
internal data class EmittedEvent(
    val type: String,
    val data: Map<String, Any?>,
)

/**
 * A mocked WebView behind a real EventBridge: posted Runnables run inline and every script handed
 * to evaluateJavascript is parsed back into the event the page would see.
 */
internal class RecordingWebView {
    val scripts: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val view: WebView = mockk(relaxed = true)

    init {
        every { view.post(any()) } answers {
            firstArg<Runnable>().run()
            true
        }
        every { view.evaluateJavascript(any(), any()) } answers {
            scripts.add(firstArg())
            Unit
        }
    }

    fun events(): List<EmittedEvent> = synchronized(scripts) { scripts.toList() }.map(::parse)

    fun events(type: String): List<EmittedEvent> = events().filter { it.type == type }

    fun clear() = scripts.clear()

    private fun parse(script: String): EmittedEvent {
        val match = SCRIPT.matchEntire(script) ?: error("unexpected script shape: $script")

        @Suppress("UNCHECKED_CAST")
        val data = Gson().fromJson(match.groupValues[2], Map::class.java) as Map<String, Any?>
        return EmittedEvent(match.groupValues[1], data)
    }

    private companion object {
        val SCRIPT = Regex("""window\.__oc&&window\.__oc\.emit\('([^']+)',(.*)\)""", RegexOption.DOT_MATCHES_ALL)
    }
}

internal object TestWait {
    const val WAIT_SECONDS = 5L
    private const val POLL_MS = 10L

    fun until(
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
}
