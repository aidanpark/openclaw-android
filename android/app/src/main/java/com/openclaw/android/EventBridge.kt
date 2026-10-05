package com.openclaw.android

import android.webkit.WebView
import com.google.gson.Gson
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

/**
 * Kotlin → WebView event dispatch (§2.8).
 * Uses evaluateJavascript + CustomEvent pattern.
 *
 * WebView side (index.html) must include:
 *   window.__oc = {
 *     emit(type, data) {
 *       window.dispatchEvent(new CustomEvent(`native:${type}`, { detail: data }));
 *     }
 *   };
 */
class EventBridge(
    webView: WebView,
) {
    private val gson = Gson()

    init {
        // The newest page wins: a recreated Activity builds a new bridge for its new WebView
        current.set(WeakReference(webView))
    }

    /**
     * Emit a named event to the WebView currently on screen — not necessarily the one this
     * bridge was created for. A long-running coroutine (an install) outlives the Activity that
     * started it; events must follow the page that replaced it. With no page attached the event
     * is dropped; state that matters is also kept where the next page can query it.
     * React side listens via: useNativeEvent('type', handler)
     */
    fun emit(
        type: String,
        data: Any?,
    ) {
        val json = gson.toJson(data ?: emptyMap<String, Any>())
        val script = "window.__oc&&window.__oc.emit('$type',$json)"
        val target = current.get()?.get() ?: return
        target.post { target.evaluateJavascript(script, null) }
    }

    companion object {
        private val current = AtomicReference<WeakReference<WebView>?>(null)

        /**
         * Forget [webView] when its Activity is destroyed — but only if it is still the current
         * one. A new Activity may already have attached its own view by the time the old one's
         * onDestroy runs; clearing unconditionally would cut the new page off.
         */
        fun detach(webView: WebView) {
            while (true) {
                val ref = current.get() ?: return
                if (ref.get() !== webView) return
                if (current.compareAndSet(ref, null)) return
            }
        }

        /** Test hook: the view events currently go to. */
        internal fun attachedView(): WebView? = current.get()?.get()
    }
}
