package com.openclaw.android

import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide guard: only one bootstrap install may run at a time — and the place a freshly
 * created page asks "is an install running, and how far is it?".
 *
 * The Activity — and the JsBridge it owns — is recreated on rotation, locale or font-size change
 * and in multi-window, while an install coroutine (not tied to the Activity) keeps running. State
 * kept in a JsBridge instance would let the new page start a second install over the same
 * `usr-staging`, and would leave that page staring at 0%. This lives in an `object` so no
 * instance member can shadow it; its lifetime is the process (a killed app starts clean).
 */
internal object SetupGuard {
    /** Immutable snapshot: readers never see a half-updated state. */
    data class State(
        val phase: String,
        val progress: Float,
        val message: String,
        val errorKind: String? = null,
        val error: String? = null,
    ) {
        val running: Boolean get() = phase == PHASE_RUNNING
    }

    const val PHASE_IDLE = "idle"
    const val PHASE_RUNNING = "running"
    const val PHASE_DONE = "done"
    const val PHASE_FAILED = "failed"

    private val running = AtomicBoolean(false)

    @Volatile
    private var state = State(PHASE_IDLE, 0f, "")

    /** True if the caller may start an install; false if one is already running. */
    fun tryStart(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        state = State(PHASE_RUNNING, 0f, "")
        return true
    }

    /** Record progress; 1.0 means the install finished. */
    fun progress(
        progress: Float,
        message: String,
    ) {
        state = State(if (progress >= 1f) PHASE_DONE else PHASE_RUNNING, progress, message)
    }

    /** Record a failure so a page created later can show it. */
    fun failed(
        errorKind: String,
        error: String,
    ) {
        state = State(PHASE_FAILED, 0f, error, errorKind, error)
    }

    /** The install coroutine is over. The last result (done/failed) stays readable. */
    fun finish() {
        if (state.phase == PHASE_RUNNING) state = State(PHASE_IDLE, 0f, "")
        running.set(false)
    }

    fun snapshot(): State = state

    /** True while an install coroutine is working (also read by MainActivity to avoid acting mid-install). */
    internal fun isRunning(): Boolean = running.get()
}

/** Process-wide cap on concurrent version probes (same reason as [SetupGuard]). */
internal object ProbeLimiter {
    const val MAX_CONCURRENT = 3
    val semaphore = Semaphore(MAX_CONCURRENT)
}
