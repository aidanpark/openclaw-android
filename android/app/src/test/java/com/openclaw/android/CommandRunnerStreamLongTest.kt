package com.openclaw.android

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

/**
 * [CommandRunner.streamLong]: the tool install's long run. A broken output stream (on Android,
 * `Process.destroy()` or anything else closing the pipe) must not end the run as an error while
 * the child still works — the call waits for the child's real end and returns its exit code.
 */
internal class CommandRunnerStreamLongTest {
    @TempDir
    lateinit var tempDir: File

    private val env = mapOf("PATH" to "/usr/bin:/bin")

    private fun streamLong(
        script: String,
        holder: AtomicReference<Process?> = AtomicReference(null),
        onOutput: (String) -> Unit = {},
    ): Int =
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                CommandRunner.streamLong(listOf("sh", "-c", script), env, tempDir, holder, onOutput)
            }
        }

    @Test
    fun `returns the exit code and streams every line, stderr merged`() {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val code = streamLong("echo one; echo two >&2; echo three; exit 5") { lines.add(it) }
        assertEquals(5, code)
        assertEquals(listOf("one", "two", "three"), lines)
    }

    @Test
    fun `the process is published in the holder while it runs`() {
        val holder = AtomicReference<Process?>(null)
        var seen: Process? = null
        streamLong("echo x", holder) { seen = holder.get() }
        assertTrue(seen != null, "no process in the holder during the run")
    }

    /**
     * Another thread closes the stdout pipe while the reader waits for the next line; the child
     * keeps working (it writes nothing more, so it gets no SIGPIPE) and ends with its own code.
     */
    @Test
    fun `a stdout closed under the reader by another thread still returns the child's real exit code`() {
        val done = File(tempDir, "done")
        val holder = AtomicReference<Process?>(null)
        val failure = AtomicReference<Throwable?>(null)
        val closer =
            Thread {
                try {
                    assertTrue(TestWait.until { holder.get() != null })
                    Thread.sleep(CLOSE_AFTER_MS)
                    holder.get()!!.inputStream.close()
                } catch (e: Throwable) {
                    failure.set(e)
                }
            }
        closer.start()
        val code =
            streamLong(
                "echo started; sleep $CHILD_SLEEP_S; : > '${done.absolutePath}'; exit 7",
                holder,
            )
        closer.join()
        assertNull(failure.get())
        assertEquals(7, code, "the run did not report the child's own exit code")
        assertTrue(done.exists(), "streamLong returned before the child had really ended")
        assertFalse(holder.get()!!.isAlive)
    }

    @Test
    fun `a stdout closed from inside the line callback still waits for the child`() {
        val done = File(tempDir, "done")
        val holder = AtomicReference<Process?>(null)
        val code =
            streamLong("echo first; sleep $CHILD_SLEEP_S; : > '${done.absolutePath}'; exit 9", holder) {
                val t = Thread { holder.get()!!.inputStream.close() }
                t.start()
                t.join()
            }
        assertEquals(9, code)
        assertTrue(done.exists(), "streamLong returned before the child had really ended")
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val CLOSE_AFTER_MS = 300L
        const val CHILD_SLEEP_S = "1"
    }
}
