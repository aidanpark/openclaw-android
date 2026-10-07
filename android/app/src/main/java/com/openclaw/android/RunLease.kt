package com.openclaw.android

/**
 * Process-wide: at most one script run that changes the install (`usr`, the global npm tree) at a
 * time, whatever its kind — a tool install ([TOOLS]) or a managed run ([RunKinds] ids such as
 * `UPDATE`). Each guard keeps its own state; this lease only decides who may start. It is taken
 * BEFORE the guard's `tryStart` and released after the run is over (also on every refusal), so a
 * tool install and an update can never overlap.
 */
internal object RunLease {
    /** Owner name of a tool install (`JsBridge.installTool`). */
    const val TOOLS = "TOOLS"

    private var holder: String? = null

    /** False when another run holds the lease (or [owner] is empty). */
    @Synchronized
    fun tryAcquire(owner: String): Boolean {
        if (owner.isEmpty() || holder != null) return false
        holder = owner
        return true
    }

    /** Only the holder can release: a late release of an earlier run never frees a newer one's lease. */
    @Synchronized
    fun release(owner: String) {
        if (holder == owner) holder = null
    }

    /** Who holds the lease now; null when nothing runs. */
    @Synchronized
    fun owner(): String? = holder
}
