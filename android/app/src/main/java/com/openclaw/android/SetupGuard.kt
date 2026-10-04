package com.openclaw.android

import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide guard: only one bootstrap install may run at a time.
 *
 * The Activity — and the JsBridge it owns — is recreated on rotation, locale or font-size change
 * and in multi-window, while an install coroutine (not tied to the Activity) keeps running. State
 * kept in a JsBridge instance would let the new page start a second install over the same
 * `usr-staging`. This lives in an `object` so no instance member can shadow it.
 */
internal object SetupGuard {
    private val running = AtomicBoolean(false)

    /** True if the caller may start an install; false if one is already running. */
    fun tryStart(): Boolean = running.compareAndSet(false, true)

    fun finish() = running.set(false)

    /** Test hook. */
    internal fun isRunning(): Boolean = running.get()
}

/** Process-wide cap on concurrent version probes (same reason as [SetupGuard]). */
internal object ProbeLimiter {
    const val MAX_CONCURRENT = 3
    val semaphore = Semaphore(MAX_CONCURRENT)
}
