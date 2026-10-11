package com.openclaw.android

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a call on the UI thread and hands its result back to a caller on another thread (the
 * WebView's JavaBridge thread).
 *
 * A `TerminalSession` binds its message handler to the Looper of the thread that creates it. Created
 * on the JavaBridge thread, its output and exit messages are handled on that thread — which is blocked
 * for as long as a bridge call runs — and an exception in its handler is uncaught (the app crashes).
 * Created on the UI thread it behaves as the Activity's own sessions do.
 *
 * Waiting is safe: the UI thread never waits on a JavaBridge call. The limit is a defence — when it
 * passes the call is cancelled (one that did not start never runs) and the caller gets an
 * [IllegalStateException].
 */
internal object UiThreadCall {
    const val DEFAULT_TIMEOUT_MS = 5_000L

    fun <T> run(
        onUiThread: Boolean,
        post: (Runnable) -> Unit,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        block: () -> T,
    ): T {
        if (onUiThread) return block()
        // Whoever gets the flag first owns the call: the UI thread (it runs) or the waiter on a timeout
        // (it never runs). A call the UI thread already started is waited for, never abandoned —
        // that would hand the caller a failure for a session that was created.
        val claimed = AtomicBoolean(false)
        val task =
            FutureTask(
                Callable {
                    check(claimed.compareAndSet(false, true)) { "The call was given up on" }
                    block()
                },
            )
        post(task)
        val failure: Throwable =
            try {
                return waitFor(task, claimed, timeoutMs)
            } catch (e: ExecutionException) {
                e.cause ?: e
            } catch (e: TimeoutException) {
                IllegalStateException("The UI thread did not finish the call within ${timeoutMs}ms", e)
            } catch (e: InterruptedException) {
                task.cancel(false)
                Thread.currentThread().interrupt()
                IllegalStateException("Interrupted while waiting for the UI thread", e)
            }
        throw failure
    }

    private fun <T> waitFor(
        task: FutureTask<T>,
        claimed: AtomicBoolean,
        timeoutMs: Long,
    ): T =
        try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            // Not started: take it back, so it never runs. Started: it is running now — wait for it,
            // as long as the first wait again (creating a session is much shorter than that)
            if (claimed.compareAndSet(false, true)) {
                task.cancel(false)
                throw e
            }
            task.get(timeoutMs.coerceAtLeast(DEFAULT_TIMEOUT_MS), TimeUnit.MILLISECONDS)
        }
}
