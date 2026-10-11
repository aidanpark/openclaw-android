package com.openclaw.android

import java.io.File

/**
 * Time limit for a short child run by [CommandRunner] that also reaches its descendants.
 *
 * `Process.destroyForcibly()` on Android is a SIGTERM to the child only: a process that ignores it,
 * or a grandchild it started, stays. coreutils `timeout -k` runs its command in its own process group
 * and, when the time passes, signals the whole group (TERM, then KILL [KILL_AFTER_SEC] seconds later).
 * It is used when the bootstrap has it ([prefixBin]/timeout — the official bootstrap does); without
 * it the run is bounded as before, by the caller's own wait.
 */
internal object TimeoutWrap {
    /** `timeout` ends the command with 124 when the time passed, 137 when it had to KILL it. */
    private const val EXIT_TIMED_OUT = 124
    private const val EXIT_KILLED = 137
    private const val KILL_AFTER_SEC = 2L

    private const val MS_PER_SEC = 1000L

    /** Slack over [Limit.seconds] for `timeout`'s own TERM → KILL steps and the process start. */
    private const val START_SLACK_SEC = 2L
    private const val SAFETY_NET_MS = (KILL_AFTER_SEC + START_SLACK_SEC) * MS_PER_SEC

    /** [seconds]: the whole seconds `timeout` gets; [prefix]: what goes in front of the program. */
    class Limit(
        val seconds: Long,
        timeoutBinary: String,
    ) {
        val prefix: List<String> = listOf(timeoutBinary, "-k", KILL_AFTER_SEC.toString(), seconds.toString())

        /** The caller's own wait: later than `timeout`'s KILL, so `timeout` is what ends the run. */
        val safetyNetMs: Long = seconds * MS_PER_SEC + SAFETY_NET_MS

        /**
         * 124/137 alone is not enough (a program may exit with them itself): the run must also have
         * lasted the limit. A run that exits 137 early was killed by something else.
         */
        fun isTimeout(
            exitCode: Int,
            elapsedMs: Long,
        ): Boolean =
            (exitCode == EXIT_TIMED_OUT || exitCode == EXIT_KILLED) &&
                elapsedMs >= seconds * MS_PER_SEC - ELAPSED_SLACK_MS
    }

    private const val ELAPSED_SLACK_MS = 200L

    /** The limit for a run of [timeoutMs], or null when this environment has no `timeout` to use. */
    fun forRun(
        env: Map<String, String>,
        timeoutMs: Long,
    ): Limit? {
        // No PREFIX is no bootstrap: the host's own `timeout` (a test machine's) is not the app's to use
        val prefix = env["PREFIX"]?.takeIf { it.isNotEmpty() } ?: return null
        val binary = File(prefix, "bin/timeout")
        if (!binary.isFile || !binary.canExecute()) return null
        val seconds = (timeoutMs + MS_PER_SEC - 1) / MS_PER_SEC
        return Limit(seconds.coerceAtLeast(1), binary.path)
    }
}
