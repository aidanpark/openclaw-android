package com.openclaw.android

/**
 * Text the app types into a terminal session before the session's shell has started.
 *
 * `TerminalSession.write` drops everything written while the shell pid is still 0, and the shell
 * starts only when the TerminalView lays the session out (`updateSize` → `initializeEmulator`) —
 * later than any fixed wait can promise. So the text waits here, in order, until the session
 * reports its shell pid (`TerminalSessionClient.setTerminalShellPid`, on the UI thread), and is
 * written then. No timer is involved.
 *
 * Rules (one lock for all of them, so a bridge call on the JavaScript thread and the UI thread's
 * shell-start callback never interleave):
 * - shell already started: written at once ([Outcome.WRITTEN]);
 * - not started yet: queued in submission order ([Outcome.QUEUED]), written by [shellStarted];
 * - the session ended or was closed ([forget]): dropped now and for every later submit;
 * - over [maxInputs] queued texts or [maxBytes] queued UTF-8 bytes for one session: the new text is
 *   dropped (a defensive bound: nothing types that much before a shell starts). The bound counts
 *   bytes because `initializeEmulator` reports the pid BEFORE it starts the thread that drains the
 *   session's 4096-byte input queue, so a flush larger than that queue would block the UI thread for
 *   good. [MAX_BYTES] stays well under it.
 * - every text carries a `stillWanted` check, asked at the moment of writing (now, or when the
 *   shell starts): false — or a check that throws — drops it. E.g. `bash post-setup.sh` must not run
 *   if the setup marker appeared or a managed run started while it waited.
 * - a `write` that throws drops that text only; the texts queued after it are still written.
 *
 * Keys are session handles (`TerminalSession.mHandle`); the writer is the session's `write`.
 */
internal class TerminalInputQueue(
    private val maxInputs: Int = MAX_INPUTS,
    private val maxBytes: Int = MAX_BYTES,
) {
    enum class Outcome { WRITTEN, QUEUED, DROPPED }

    private class Input(
        val text: String,
        val stillWanted: () -> Boolean,
    ) {
        val bytes: Int = text.toByteArray(Charsets.UTF_8).size
    }

    private val lock = Any()
    private val pending = HashMap<String, ArrayDeque<Input>>()
    private val started = HashSet<String>()
    private val ended = HashSet<String>()

    /** Write [text] now if [key]'s shell runs, else queue it (see the class rules). Any thread. */
    fun submit(
        key: String,
        text: String,
        stillWanted: () -> Boolean = { true },
        write: (String) -> Unit,
    ): Outcome =
        synchronized(lock) {
            val input = Input(text, stillWanted)
            when {
                key in ended -> Outcome.DROPPED
                key in started -> if (writeChecked(input, write)) Outcome.WRITTEN else Outcome.DROPPED
                else -> enqueue(key, input)
            }
        }

    /**
     * [key]'s shell started with [pid] (UI thread, from `setTerminalShellPid`): write its queued
     * texts in order, each after its check. A pid that is not positive means no shell will ever read
     * the text: the queue is dropped and later submits are refused. False when [key] was already
     * closed or ended ([forget]) — nothing was written; a caller whose shell did start must end it.
     */
    fun shellStarted(
        key: String,
        pid: Int,
        write: (String) -> Unit,
    ): Boolean =
        synchronized(lock) {
            when {
                pid <= 0 -> {
                    dropQueued(key, "the shell did not start")
                    ended.add(key)
                    false
                }
                key in ended -> false
                else -> {
                    started.add(key)
                    pending.remove(key)?.forEach { writeChecked(it, write) }
                    true
                }
            }
        }

    /**
     * The session ended or was closed: drop what it still waits for, refuse anything later. True
     * when its shell had started (reported through [shellStarted]) — only then is there a process to end.
     */
    fun forget(key: String): Boolean =
        synchronized(lock) {
            dropQueued(key, "the session ended before its shell started")
            ended.add(key)
            started.remove(key)
        }

    /** Number of texts waiting for [key]'s shell. */
    fun queued(key: String): Int = synchronized(lock) { pending[key]?.size ?: 0 }

    private fun enqueue(
        key: String,
        input: Input,
    ): Outcome {
        val queue = pending.getOrPut(key) { ArrayDeque() }
        val bytes = queue.sumOf { it.bytes } + input.bytes
        if (queue.size >= maxInputs || bytes > maxBytes) {
            AppLogger.w(TAG, "Typed input dropped: too much is already waiting for the shell to start")
            if (queue.isEmpty()) pending.remove(key)
            return Outcome.DROPPED
        }
        queue.addLast(input)
        return Outcome.QUEUED
    }

    /** True when the text was written. Neither the check nor the write may throw out of here. */
    private fun writeChecked(
        input: Input,
        write: (String) -> Unit,
    ): Boolean {
        val wanted =
            runCatching(input.stillWanted).getOrElse {
                AppLogger.w(TAG, "Typed input dropped: its check failed", it)
                false
            }
        if (!wanted) {
            AppLogger.i(TAG, "Typed input dropped: no longer wanted when it could be written")
            return false
        }
        return runCatching { write(input.text) }
            .onFailure { AppLogger.w(TAG, "Typed input dropped: the session refused it", it) }
            .isSuccess
    }

    private fun dropQueued(
        key: String,
        why: String,
    ) {
        val dropped = pending.remove(key) ?: return
        AppLogger.w(TAG, "Dropped ${dropped.size} typed input(s): $why")
    }

    companion object {
        private const val TAG = "TerminalInput"
        const val MAX_INPUTS = 16

        /** Half of `TerminalSession`'s 4096-byte input queue: a flush always fits without a reader. */
        const val MAX_BYTES = 2_048
    }
}
