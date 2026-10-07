package com.openclaw.android

/**
 * The last check before every real `android.os.Process.sendSignal` of the app: a signal is never
 * sent to pid 0 or below (a process group or every process), to pid 1 (init), or to the app's own
 * process. The callers pick their pids with their own rules (a gateway below the app, a run's
 * token); this is a defence in depth for a pid that slipped through them, so it only logs.
 *
 * Only the real signal goes through it: the default `send` of [GatewayControl], [RunProcesses],
 * [RunSignal] and [ToolSignal]. A `send` injected by a test replaces the whole default, filter
 * included; [allowed] and [send] take the app's pid as a parameter so the filter itself can be tested.
 */
internal object SafeSignal {
    private const val TAG = "SafeSignal"

    /** True when [pid] may be signalled: above 1 and not [myPid]. */
    fun allowed(
        pid: Int,
        myPid: Int,
    ): Boolean = pid > 1 && pid != myPid

    /**
     * Sends [sig] to [pid] through [deliver] only when [allowed]; otherwise logs and sends nothing.
     * Returns whether the signal was handed to [deliver].
     */
    fun send(
        pid: Int,
        sig: Int,
        myPid: () -> Int = { android.os.Process.myPid() },
        deliver: (Int, Int) -> Unit = { p, s -> android.os.Process.sendSignal(p, s) },
    ): Boolean {
        val self = myPid()
        if (!allowed(pid, self)) {
            AppLogger.w(TAG, "Signal $sig to pid $pid not sent (app pid $self)")
            return false
        }
        deliver(pid, sig)
        return true
    }
}
