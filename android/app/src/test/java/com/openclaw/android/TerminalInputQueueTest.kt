package com.openclaw.android

import com.openclaw.android.TerminalInputQueue.Outcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [TerminalInputQueue] on its own: what the app types before a session's shell has started waits,
 * in order, and is written exactly once when the shell starts — each text asked again at that
 * moment whether it is still wanted. Bounds, dropping after the session ended, a pid that is not
 * positive, isolation between sessions, and many threads submitting while the shell starts.
 */
internal class TerminalInputQueueTest {
    private val key = "session-a"
    private val written: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val writer: (String) -> Unit = { written.add(it) }

    // ── when the text is written ────────────────────────────────────────────

    @Test
    fun `with the shell already started a text is written at once`() {
        val q = TerminalInputQueue()
        q.shellStarted(key, PID, writer)
        assertEquals(Outcome.WRITTEN, q.submit(key, "ls\n", write = writer))
        assertEquals(listOf("ls\n"), written)
        assertEquals(0, q.queued(key))
    }

    @Test
    fun `before the shell starts a text waits and nothing is written`() {
        val q = TerminalInputQueue()
        assertEquals(Outcome.QUEUED, q.submit(key, "bash x\n", write = writer))
        assertEquals(emptyList<String>(), written)
        assertEquals(1, q.queued(key))
    }

    @Test
    fun `the shell start writes the waiting texts in submission order, once`() {
        val q = TerminalInputQueue()
        listOf("a", "b", "c").forEach { assertEquals(Outcome.QUEUED, q.submit(key, it, write = writer)) }
        q.shellStarted(key, PID, writer)
        assertEquals(listOf("a", "b", "c"), written)
        assertEquals(0, q.queued(key))
        // A second start report (it never comes twice, but it must not repeat anything)
        q.shellStarted(key, PID, writer)
        assertEquals(listOf("a", "b", "c"), written)
        // What comes after the start follows what waited
        assertEquals(Outcome.WRITTEN, q.submit(key, "d", write = writer))
        assertEquals(listOf("a", "b", "c", "d"), written)
    }

    @Test
    fun `the queued texts are written with the writer the shell start passes, not the one given at submit`() {
        val q = TerminalInputQueue()
        val atSubmit = mutableListOf<String>()
        q.submit(key, "a", write = { atSubmit.add(it) })
        q.shellStarted(key, PID, writer)
        assertEquals(emptyList<String>(), atSubmit)
        assertEquals(listOf("a"), written)
    }

    // ── stillWanted: asked at the moment of writing ─────────────────────────

    @Test
    fun `stillWanted is asked when the text is written, not when it is submitted`() {
        val q = TerminalInputQueue()
        val asked = AtomicInteger()
        var wanted = true
        q.submit(key, "bash x\n", { asked.incrementAndGet() > 0 && wanted }, writer)
        assertEquals(0, asked.get(), "the check ran at submit time")
        // E.g. the setup marker appeared or a managed run took the lease while the text waited
        wanted = false
        q.shellStarted(key, PID, writer)
        assertEquals(1, asked.get())
        assertEquals(emptyList<String>(), written)
        assertEquals(0, q.queued(key), "a text no longer wanted is dropped, not kept for later")
    }

    @Test
    fun `a text no longer wanted is dropped alone - the ones around it are still written in order`() {
        val q = TerminalInputQueue()
        q.submit(key, "a", { true }, writer)
        q.submit(key, "b", { false }, writer)
        q.submit(key, "c", { true }, writer)
        q.shellStarted(key, PID, writer)
        assertEquals(listOf("a", "c"), written)
    }

    @Test
    fun `with the shell started a text whose check says no is dropped at once`() {
        val q = TerminalInputQueue()
        q.shellStarted(key, PID, writer)
        assertEquals(Outcome.DROPPED, q.submit(key, "x", { false }, writer))
        assertEquals(emptyList<String>(), written)
    }

    @Test
    fun `a check that throws drops its text, and the texts after it are still written`() {
        val q = TerminalInputQueue()
        q.submit(key, "a", { true }, writer)
        q.submit(key, "b", { error("marker unreadable") }, writer)
        q.submit(key, "c", { true }, writer)
        q.shellStarted(key, PID, writer)
        assertEquals(listOf("a", "c"), written)
        // Immediate path: the throwing check is a drop, never an exception for the caller
        assertEquals(Outcome.DROPPED, q.submit(key, "d", { throw IllegalStateException("x") }, writer))
        assertEquals(Outcome.WRITTEN, q.submit(key, "e", write = writer))
        assertEquals(listOf("a", "c", "e"), written)
    }

    @Test
    fun `a write that throws during the flush drops that text only - the ones after it are still written`() {
        val q = TerminalInputQueue()
        q.submit(key, "a", write = writer)
        q.submit(key, "boom", write = writer)
        q.submit(key, "c", write = writer)
        val throwing: (String) -> Unit = {
            check(it != "boom") { "pty closed" }
            written.add(it)
        }
        // No exception reaches the UI thread's shell-start callback
        assertTrue(q.shellStarted(key, PID, throwing))
        assertEquals(listOf("a", "c"), written)
        assertEquals(0, q.queued(key), "the failed text was kept for a second try")
        // The session counts as started: later texts go straight through; a throwing one is a drop
        assertEquals(Outcome.WRITTEN, q.submit(key, "d", write = writer))
        assertEquals(Outcome.DROPPED, q.submit(key, "boom", write = throwing))
        assertEquals(Outcome.WRITTEN, q.submit(key, "e", write = throwing))
        assertEquals(listOf("a", "c", "d", "e"), written)
    }

    // ── bounds ──────────────────────────────────────────────────────────────

    @Test
    fun `the default bounds are 16 texts and 2048 UTF-8 bytes - half the session's 4096-byte input queue`() {
        assertEquals(16, TerminalInputQueue.MAX_INPUTS)
        assertEquals(2_048, TerminalInputQueue.MAX_BYTES)
        assertTrue(TerminalInputQueue.MAX_BYTES <= SESSION_INPUT_QUEUE_BYTES)
        val q = TerminalInputQueue()
        repeat(16) { assertEquals(Outcome.QUEUED, q.submit(key, "t$it", write = writer), "text $it") }
        assertEquals(Outcome.DROPPED, q.submit(key, "t16", write = writer), "the 17th text")
        assertEquals(16, q.queued(key))
        q.shellStarted(key, PID, writer)
        assertEquals((0 until 16).map { "t$it" }, written)
    }

    @Test
    fun `bytes - exactly 2048 queued in all fit, one byte more is dropped`() {
        val q = TerminalInputQueue()
        assertEquals(Outcome.QUEUED, q.submit(key, "x".repeat(2_000), write = writer))
        assertEquals(Outcome.QUEUED, q.submit(key, "y".repeat(48), write = writer), "2048 in all fits")
        assertEquals(Outcome.DROPPED, q.submit(key, "z", write = writer), "2049 in all")
        assertEquals(2, q.queued(key))
    }

    @Test
    fun `the byte bound is on the total waiting, not on each text`() {
        val q = TerminalInputQueue()
        // Each fits alone; together they are 2049 bytes
        assertEquals(Outcome.QUEUED, q.submit(key, "x".repeat(1_024), write = writer))
        assertEquals(Outcome.DROPPED, q.submit(key, "y".repeat(1_025), write = writer))
        assertEquals(1, q.queued(key))
    }

    @Test
    fun `multibyte characters count as their UTF-8 bytes, not as characters`() {
        val q = TerminalInputQueue()
        // 1024 characters of 2 bytes each: 2048 bytes, the whole budget
        assertEquals(Outcome.QUEUED, q.submit(key, "é".repeat(1_024), write = writer))
        assertEquals(Outcome.DROPPED, q.submit(key, "a", write = writer), "1025 characters but 2049 bytes")
        val r = TerminalInputQueue()
        // 1025 characters of 2 bytes: within any 2048-CHARACTER bound, over the byte bound
        assertEquals(Outcome.DROPPED, r.submit(key, "é".repeat(1_025), write = writer))
        // 683 three-byte characters (2049 bytes)
        assertEquals(Outcome.DROPPED, r.submit(key, "한".repeat(683), write = writer))
        assertEquals(Outcome.QUEUED, r.submit(key, "한".repeat(682), write = writer), "2046 bytes")
        assertEquals(1, r.queued(key))
    }

    @Test
    fun `the bound is not a latch - a dropped text uses no budget, and the shell start frees it`() {
        val q = TerminalInputQueue()
        assertEquals(Outcome.QUEUED, q.submit(key, "x".repeat(2_000), write = writer))
        assertEquals(Outcome.DROPPED, q.submit(key, "y".repeat(49), write = writer))
        // A smaller text after a dropped one still fits
        assertEquals(Outcome.QUEUED, q.submit(key, "y".repeat(48), write = writer))
        q.shellStarted(key, PID, writer)
        assertEquals(listOf("x".repeat(2_000), "y".repeat(48)), written)
        // Nothing waits any more: a text as large as the whole budget goes straight through
        assertEquals(Outcome.WRITTEN, q.submit(key, "z".repeat(2_048), write = writer))
        // Another session still has its whole budget
        assertEquals(Outcome.QUEUED, q.submit("other", "w".repeat(2_048), write = writer))
    }

    @Test
    fun `one text over the byte bound is dropped and leaves nothing behind`() {
        val q = TerminalInputQueue()
        assertEquals(Outcome.DROPPED, q.submit(key, "x".repeat(2_049), write = writer))
        assertEquals(0, q.queued(key))
        assertEquals(Outcome.QUEUED, q.submit(key, "ok", write = writer))
        q.shellStarted(key, PID, writer)
        assertEquals(listOf("ok"), written)
    }

    @Test
    fun `the bounds hold only for what waits - a started shell gets any number and length at once`() {
        val q = TerminalInputQueue(maxInputs = 2, maxBytes = 10)
        q.shellStarted(key, PID, writer)
        repeat(5) { assertEquals(Outcome.WRITTEN, q.submit(key, "0123456789ABC", write = writer)) }
        assertEquals(5, written.size)
    }

    @Test
    fun `the bounds count per session`() {
        val q = TerminalInputQueue(maxInputs = 1, maxBytes = 100)
        assertEquals(Outcome.QUEUED, q.submit("k1", "a", write = writer))
        assertEquals(Outcome.QUEUED, q.submit("k2", "b", write = writer))
        assertEquals(Outcome.DROPPED, q.submit("k1", "c", write = writer))
    }

    // ── the session ended or was closed ─────────────────────────────────────

    @Test
    fun `forget drops what waits, and every later submit is dropped`() {
        val q = TerminalInputQueue()
        q.submit(key, "bash x\n", write = writer)
        assertFalse(q.forget(key), "a session whose shell never started has no process to end")
        assertEquals(0, q.queued(key))
        assertEquals(Outcome.DROPPED, q.submit(key, "late", write = writer))
        // A start report after the close (a racing UI callback) writes nothing, revives nothing and
        // says so: the caller must end that shell
        assertFalse(q.shellStarted(key, PID, writer))
        assertEquals(Outcome.DROPPED, q.submit(key, "later", write = writer))
        assertEquals(emptyList<String>(), written)
        // A second forget of the same session: still nothing to end
        assertFalse(q.forget(key))
    }

    @Test
    fun `forget after the shell started says so once, and refuses later texts`() {
        val q = TerminalInputQueue()
        assertTrue(q.shellStarted(key, PID, writer))
        assertEquals(Outcome.WRITTEN, q.submit(key, "a", write = writer))
        assertTrue(q.forget(key), "a started shell must be ended by the caller")
        assertFalse(q.forget(key), "a second close would signal the shell twice")
        assertEquals(Outcome.DROPPED, q.submit(key, "b", write = writer))
        assertEquals(listOf("a"), written)
    }

    @Test
    fun `forget of a session never seen is false`() {
        assertFalse(TerminalInputQueue().forget("unknown"))
    }

    @Test
    fun `a pid that is not positive drops what waits and refuses later texts`() {
        for (pid in listOf(0, -1)) {
            val q = TerminalInputQueue()
            val out = mutableListOf<String>()
            q.submit(key, "a", write = { out.add(it) })
            assertFalse(q.shellStarted(key, pid) { out.add(it) }, "pid $pid")
            assertEquals(emptyList<String>(), out, "pid $pid")
            assertEquals(0, q.queued(key), "pid $pid")
            assertEquals(Outcome.DROPPED, q.submit(key, "b", write = { out.add(it) }), "pid $pid")
            assertFalse(q.forget(key), "pid $pid: no shell to end")
            // No later report can turn it into a running shell
            assertFalse(q.shellStarted(key, PID) { out.add(it) })
            assertEquals(Outcome.DROPPED, q.submit(key, "c", write = { out.add(it) }), "pid $pid")
            assertEquals(emptyList<String>(), out, "pid $pid")
        }
    }

    // ── sessions are apart ──────────────────────────────────────────────────

    @Test
    fun `each session has its own queue, start and end`() {
        val q = TerminalInputQueue()
        val one = mutableListOf<String>()
        val two = mutableListOf<String>()
        q.submit("k1", "a1", write = { one.add(it) })
        q.submit("k2", "a2", write = { two.add(it) })
        q.shellStarted("k1", PID) { one.add(it) }
        assertEquals(listOf("a1"), one)
        assertEquals(emptyList<String>(), two)
        assertEquals(1, q.queued("k2"))
        q.forget("k1")
        assertEquals(1, q.queued("k2"), "closing one session dropped another's text")
        assertEquals(Outcome.QUEUED, q.submit("k2", "b2", write = { two.add(it) }))
        q.shellStarted("k2", PID + 1) { two.add(it) }
        assertEquals(listOf("a2", "b2"), two)
        assertEquals(Outcome.DROPPED, q.submit("k1", "late", write = { one.add(it) }))
    }

    // ── many threads ────────────────────────────────────────────────────────

    /**
     * Submitters on several threads (the JavaScript bridge thread, worker threads) while the UI
     * thread reports the shell start in the middle: every text is written exactly once, each
     * submitter's texts keep their order, nothing is lost, and no call blocks forever. Repeated to
     * shake out interleavings; real threads and latches, no sleeps.
     */
    @Test
    fun `texts submitted from many threads while the shell starts are written exactly once, in order per thread`() {
        repeat(ROUNDS) { round ->
            val q = TerminalInputQueue(maxInputs = Int.MAX_VALUE, maxBytes = Int.MAX_VALUE)
            val out: MutableList<String> = Collections.synchronizedList(mutableListOf())
            val write: (String) -> Unit = { out.add(it) }
            val gate = CyclicBarrier(THREADS + 1)
            val halfway = CountDownLatch(THREADS * PER_THREAD / 2)
            val done = CountDownLatch(THREADS + 1)
            val outcomes = Collections.synchronizedList(mutableListOf<Outcome>())
            val failures = Collections.synchronizedList(mutableListOf<Throwable>())
            val threads =
                (0 until THREADS).map { t ->
                    Thread {
                        try {
                            gate.await(TestWait.WAIT_SECONDS, TimeUnit.SECONDS)
                            for (j in 0 until PER_THREAD) {
                                // The check reads the queue too: the lock is reentrant, never a deadlock
                                outcomes.add(q.submit(key, "t$t-$j", { q.queued(key) >= 0 }, write))
                                halfway.countDown()
                            }
                        } catch (e: Throwable) {
                            failures.add(e)
                        } finally {
                            done.countDown()
                        }
                    }
                }
            val starter =
                Thread {
                    try {
                        gate.await(TestWait.WAIT_SECONDS, TimeUnit.SECONDS)
                        halfway.await(TestWait.WAIT_SECONDS, TimeUnit.SECONDS)
                        q.shellStarted(key, PID, write)
                    } catch (e: Throwable) {
                        failures.add(e)
                    } finally {
                        done.countDown()
                    }
                }
            (threads + starter).forEach(Thread::start)
            assertTrue(done.await(TestWait.WAIT_SECONDS, TimeUnit.SECONDS), "round $round: a thread never finished")
            assertEquals(emptyList<Throwable>(), failures.toList(), "round $round")
            val expected = (0 until THREADS).flatMap { t -> (0 until PER_THREAD).map { "t$t-$it" } }
            assertEquals(expected.sorted(), out.toList().sorted(), "round $round: lost or doubled texts")
            assertEquals(out.size, out.toSet().size, "round $round: a text was written twice")
            for (t in 0 until THREADS) {
                val mine = out.filter { it.startsWith("t$t-") }
                assertEquals((0 until PER_THREAD).map { "t$t-$it" }, mine, "round $round: thread $t out of order")
            }
            assertTrue(Outcome.DROPPED !in outcomes, "round $round: $outcomes")
            assertTrue(Outcome.QUEUED in outcomes, "round $round: nothing waited (the start came too early)")
            assertEquals(0, q.queued(key))
        }
    }

    @Test
    fun `a close racing the shell start leaves each text written once or dropped, never both or twice`() {
        repeat(ROUNDS) { round ->
            val q = TerminalInputQueue(maxInputs = Int.MAX_VALUE, maxBytes = Int.MAX_VALUE)
            val out: MutableList<String> = Collections.synchronizedList(mutableListOf())
            val write: (String) -> Unit = { out.add(it) }
            val outcomes = Collections.synchronizedMap(mutableMapOf<String, Outcome>())
            val gate = CyclicBarrier(3)
            val done = CountDownLatch(3)
            val failures = Collections.synchronizedList(mutableListOf<Throwable>())

            fun worker(body: () -> Unit) =
                Thread {
                    try {
                        gate.await(TestWait.WAIT_SECONDS, TimeUnit.SECONDS)
                        body()
                    } catch (e: Throwable) {
                        failures.add(e)
                    } finally {
                        done.countDown()
                    }
                }
            val texts = (0 until PER_THREAD).map { "x$it" }
            listOf(
                worker { texts.forEach { outcomes[it] = q.submit(key, it, write = write) } },
                worker { q.shellStarted(key, PID, write) },
                worker { q.forget(key) },
            ).forEach(Thread::start)
            assertTrue(done.await(TestWait.WAIT_SECONDS, TimeUnit.SECONDS), "round $round: a thread never finished")
            assertEquals(emptyList<Throwable>(), failures.toList(), "round $round")
            assertEquals(out.size, out.toSet().size, "round $round: a text was written twice")
            // Written texts are a prefix-ordered subset; a WRITTEN outcome is always among them
            assertEquals(texts.filter { it in out }, out.toList(), "round $round: out of order")
            texts.filter { outcomes[it] == Outcome.WRITTEN }.forEach { assertTrue(it in out, "round $round: $it") }
            texts.filter { outcomes[it] == Outcome.DROPPED }.forEach { assertTrue(it !in out, "round $round: $it") }
            assertEquals(0, q.queued(key), "round $round: something still waits after the close")
            assertEquals(Outcome.DROPPED, q.submit(key, "after", write = write))
        }
    }

    private companion object {
        const val PID = 4242
        const val THREADS = 8
        const val PER_THREAD = 50
        const val ROUNDS = 50

        /** `TerminalSession.mTerminalToProcessIOQueue = new ByteQueue(4096)`. */
        const val SESSION_INPUT_QUEUE_BYTES = 4_096
    }
}
