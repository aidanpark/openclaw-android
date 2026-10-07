package com.openclaw.android

import android.system.Os
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The REAL [TerminalSessionManager] with REAL [TerminalSession]s (no shell process: the JVM cannot
 * start one, so a test "starts" the shell by setting the pid the way `initializeEmulator` does and
 * then reports it like `setTerminalShellPid`). What reaches the shell is read from the session's own
 * input queue — the bytes `TerminalSession.write` hands to the pty writer thread. Covers
 * `writeWhenReady` / `onShellStarted`, the queue being cleared by `closeSession` and
 * `onSessionFinished`, and (characterization) what `closeSession` does to a session whose shell
 * never started.
 */
internal class TerminalSessionInputTest {
    @TempDir
    lateinit var root: File

    private lateinit var manager: TerminalSessionManager
    private lateinit var web: RecordingWebView

    @BeforeEach
    fun setup() {
        mockkStatic(Os::class)
        every { Os.kill(any(), any()) } just Runs
        web = RecordingWebView()
        val activity = mockk<MainActivity>(relaxed = true)
        every { activity.filesDir } returns File(root, "files").apply { mkdirs() }
        manager = TerminalSessionManager(activity, mockk<TerminalSessionClient>(relaxed = true), EventBridge(web.view))
    }

    @AfterEach
    fun teardown() {
        EventBridge.detach(web.view)
        unmockkStatic(Os::class)
    }

    // ── writeWhenReady / onShellStarted ─────────────────────────────────────

    @Test
    fun `a text typed before the shell starts reaches it when the shell starts, once, in order`() {
        val s = manager.createSession()
        assertTrue(manager.writeWhenReady(s, "bash /x/post-setup.sh\n"))
        assertTrue(manager.writeWhenReady(s, "openclaw onboard"))
        // TerminalSession.write itself would have dropped these (pid 0): nothing is in its queue
        assertEquals("", s.typed())
        s.startShell(PID)
        manager.onShellStarted(s, PID)
        assertEquals("bash /x/post-setup.sh\nopenclaw onboard", s.typed())
        // Nothing is written a second time
        manager.onShellStarted(s, PID)
        assertEquals("", s.typed())
    }

    @Test
    fun `a text typed after the shell started is written at once`() {
        val s = manager.createSession()
        s.startShell(PID)
        manager.onShellStarted(s, PID)
        assertTrue(manager.writeWhenReady(s, "openclaw gateway\n"))
        assertEquals("openclaw gateway\n", s.typed())
    }

    @Test
    fun `the check is asked when the shell starts - a no then drops the text and writeWhenReady had said true`() {
        val s = manager.createSession()
        var wanted = true
        assertTrue(manager.writeWhenReady(s, "bash x\n") { wanted })
        wanted = false
        s.startShell(PID)
        manager.onShellStarted(s, PID)
        assertEquals("", s.typed())
    }

    @Test
    fun `a check that says no for a started shell makes writeWhenReady false and writes nothing`() {
        val s = manager.createSession()
        s.startShell(PID)
        manager.onShellStarted(s, PID)
        assertFalse(manager.writeWhenReady(s, "bash x\n") { false })
        assertEquals("", s.typed())
    }

    @Test
    fun `sessions do not share what waits`() {
        val a = manager.createSession()
        val b = manager.createSession()
        manager.writeWhenReady(a, "for-a")
        manager.writeWhenReady(b, "for-b")
        b.startShell(PID + 1)
        manager.onShellStarted(b, PID + 1)
        assertEquals("for-b", b.typed())
        assertEquals("", a.typed())
        a.startShell(PID)
        manager.onShellStarted(a, PID)
        assertEquals("for-a", a.typed())
    }

    // ── a session that is closed or ends drops what waits ───────────────────

    @Test
    fun `closeSession drops what waits - a racing start report writes nothing and later texts are refused`() {
        val s = manager.createSession()
        manager.createSession() // another one, so the close does not leave the manager empty
        manager.writeWhenReady(s, "bash x\n")
        manager.closeSession(s.mHandle)
        s.startShell(PID)
        manager.onShellStarted(s, PID)
        assertEquals("", s.typed())
        assertFalse(manager.writeWhenReady(s, "late"))
        assertEquals("", s.typed())
    }

    @Test
    fun `onSessionFinished drops what waits and refuses later texts`() {
        val s = manager.createSession()
        manager.writeWhenReady(s, "bash x\n")
        manager.onSessionFinished(s)
        s.startShell(PID)
        manager.onShellStarted(s, PID)
        assertEquals("", s.typed())
        assertFalse(manager.writeWhenReady(s, "late"))
    }

    @Test
    fun `a shell that reports no pid drops what waits`() {
        val s = manager.createSession()
        manager.writeWhenReady(s, "bash x\n")
        manager.onShellStarted(s, 0)
        s.startShell(PID)
        assertFalse(manager.writeWhenReady(s, "late"))
        assertEquals("", s.typed())
    }

    // ── the flush runs before the session's pty writer thread exists ────────

    /**
     * `initializeEmulator` calls `setTerminalShellPid` BEFORE it starts the thread that drains the
     * session's 4096-byte input queue, so the flush can only fill that queue — with nobody reading
     * it. The largest flush the queue lets wait (its whole 2048-byte budget, here in multibyte
     * characters, over several texts) must return without a reader.
     */
    @Test
    fun `the largest flush the queue allows fits the session's input queue and returns without a reader`() {
        val s = manager.createSession()
        val texts = listOf("é".repeat(512), "한".repeat(200), "x".repeat(TerminalInputQueue.MAX_BYTES - 1_024 - 600))
        texts.forEach { assertTrue(manager.writeWhenReady(s, it), "refused: ${it.length} chars") }
        assertEquals(TerminalInputQueue.MAX_BYTES, texts.sumOf { it.toByteArray(Charsets.UTF_8).size })
        s.startShell(PID)
        val flush = Thread { manager.onShellStarted(s, PID) }.apply { start() }
        flush.join(TestWait.WAIT_SECONDS * 1000)
        assertFalse(flush.isAlive, "the flush blocked: no reader exists yet on the device either")
        assertEquals(texts.joinToString(""), s.typed())
    }

    /**
     * Was a characterization of a hang: 4096 characters of 2 bytes (8192 bytes) were queued and the
     * flush waited for a reader that does not exist yet. The queue now counts UTF-8 bytes, so such
     * text is refused up front and no flush can block.
     */
    @Test
    fun `text over the byte budget is refused up front, so the flush never waits`() {
        val s = manager.createSession()
        assertFalse(manager.writeWhenReady(s, "é".repeat(4_096)), "8192 bytes were accepted")
        assertFalse(manager.writeWhenReady(s, "é".repeat(1_025)), "2050 bytes were accepted")
        assertTrue(manager.writeWhenReady(s, "é".repeat(1_024)), "2048 bytes")
        assertFalse(manager.writeWhenReady(s, "x"), "2049 bytes in all")
        s.startShell(PID)
        val flush = Thread { manager.onShellStarted(s, PID) }.apply { start() }
        flush.join(BLOCK_PROBE_MS)
        assertFalse(flush.isAlive, "the flush blocked")
        assertEquals("é".repeat(1_024), s.typed())
    }

    // ── finishIfRunning on a session whose shell never started ──────────────

    /**
     * `TerminalSession.finishIfRunning` treats every pid but -1 as running, so for a session whose
     * shell never started (pid 0) it would call `Os.kill(0, SIGKILL)` — the app's own process GROUP.
     * The manager now signals only a shell whose start was reported: a never-started session is
     * closed without any signal (its queue dropped, the session removed).
     */
    @Test
    fun `closing a session whose shell never started sends no signal at all`() {
        val neverStarted = manager.createSession()
        val other = manager.createSession()
        manager.writeWhenReady(neverStarted, "bash x\n")
        manager.closeSession(neverStarted.mHandle)
        verify(exactly = 0) { Os.kill(any(), any()) }
        assertEquals(listOf(other.mHandle), manager.getSessionsInfo().map { it["id"] })
        assertFalse(manager.writeWhenReady(neverStarted, "late"))
    }

    @Test
    fun `closing a started session kills its shell by its pid, exactly once`() {
        val s = manager.createSession()
        s.startShell(PID)
        manager.onShellStarted(s, PID)
        manager.closeSession(s.mHandle)
        verify(exactly = 1) { Os.kill(PID, any()) }
        verify(exactly = 1) { Os.kill(any(), any()) }
        // A second close of the same handle finds nothing
        manager.closeSession(s.mHandle)
        verify(exactly = 1) { Os.kill(any(), any()) }
    }

    /**
     * The close came first (e.g. from the JavaScript thread) and the view laid the session out only
     * afterwards: the shell starts orphaned. `onShellStarted` ends it once, by its pid, and types
     * nothing into it.
     */
    @Test
    fun `a shell that starts after its session was closed is killed once, by its pid, and gets no input`() {
        val s = manager.createSession()
        manager.writeWhenReady(s, "bash x\n")
        manager.closeSession(s.mHandle)
        verify(exactly = 0) { Os.kill(any(), any()) }
        s.startShell(PID)
        manager.onShellStarted(s, PID)
        verify(exactly = 1) { Os.kill(PID, any()) }
        verify(exactly = 0) { Os.kill(0, any()) }
        assertEquals("", s.typed())
    }

    @Test
    fun `the pid set but its report racing the close - the late report still ends the shell, once`() {
        val s = manager.createSession()
        s.startShell(PID) // initializeEmulator set the pid; setTerminalShellPid has not run yet
        manager.closeSession(s.mHandle)
        verify(exactly = 0) { Os.kill(any(), any()) }
        manager.onShellStarted(s, PID)
        verify(exactly = 1) { Os.kill(PID, any()) }
    }

    @Test
    fun `a start report without a pid after the close signals nothing`() {
        val s = manager.createSession()
        manager.closeSession(s.mHandle)
        manager.onShellStarted(s, 0)
        manager.onShellStarted(s, -1)
        verify(exactly = 0) { Os.kill(any(), any()) }
    }

    @Test
    fun `closing a session whose shell started and then exited signals nothing`() {
        // The usual order: the exit reaches onSessionFinished, the user closes the tab later
        val a = manager.createSession()
        a.startShell(PID)
        manager.onShellStarted(a, PID)
        a.startShell(-1) // cleanupResources
        manager.onSessionFinished(a)
        manager.closeSession(a.mHandle)
        // The exit not reported yet when the tab is closed: the pid is already -1
        val b = manager.createSession()
        b.startShell(PID + 1)
        manager.onShellStarted(b, PID + 1)
        b.startShell(-1)
        manager.closeSession(b.mHandle)
        verify(exactly = 0) { Os.kill(any(), any()) }
    }

    @Test
    fun `the manager signals only through the queue's answer - no unconditional kill on close`() {
        val src = File("src/main/java/com/openclaw/android/TerminalSessionManager.kt").readText()
        assertEquals(2, Regex("""finishIfRunning\(\)""").findAll(src).count(), "finishIfRunning call sites changed")
        assertTrue(src.contains("if (pendingInput.forget(handleId)) session.finishIfRunning()"))
        assertTrue(src.contains("if (!accepted && pid > 0) session.finishIfRunning()"))
    }

    @Test
    fun `onSessionFinished never signals anything`() {
        val s = manager.createSession()
        manager.onSessionFinished(s)
        verify(exactly = 0) { Os.kill(any(), any()) }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun TerminalSession.startShell(pid: Int) = TerminalSessionFakes.startShell(this, pid)

    private fun TerminalSession.typed(): String = TerminalSessionFakes.typed(this)

    private companion object {
        const val PID = 4242

        /** How long a flush that should return is given before it counts as blocked. */
        const val BLOCK_PROBE_MS = 500L
    }
}
