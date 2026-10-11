package com.openclaw.android

import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Terminal sessions are created on the UI thread whichever thread asks (UiThreadCall), and the list
 * they are kept in is safe to read from a worker. The factory stands in for the real session (it binds a
 * Looper and a pty); the "UI thread" is one named thread behind `activity.runOnUiThread`.
 */
class SessionCreationThreadContractTest {
    private val ui = Executors.newSingleThreadExecutor { Thread(it, "fake-ui") }
    private lateinit var root: File
    private lateinit var activity: MainActivity
    private val createdOn = AtomicReference<String>()

    @BeforeEach
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        root = Files.createTempDirectory("session-thread").toFile()
        activity = mockk(relaxed = true)
        every { activity.filesDir } returns root
        every { activity.runOnUiThread(any()) } answers { ui.execute(firstArg<Runnable>()) }
    }

    @AfterEach
    fun teardown() {
        unmockkStatic(Log::class)
        ui.shutdownNow()
        root.deleteRecursively()
    }

    private fun manager(isUiThread: () -> Boolean): TerminalSessionManager {
        val factory =
            TerminalSessionManager.SessionFactory { _, _, _, _, _ ->
                createdOn.set(Thread.currentThread().name)
                mockk<TerminalSession>(relaxed = true)
            }
        return TerminalSessionManager(
            activity,
            mockk<TerminalSessionClient>(relaxed = true),
            mockk<EventBridge>(relaxed = true),
            isUiThread,
            factory,
        )
    }

    @Test
    fun `asked from another thread, the session is created on the UI thread`() {
        val session = manager(isUiThread = { false }).createSession()
        assertNotNull(session)
        assertEquals("fake-ui", createdOn.get())
    }

    @Test
    fun `asked on the UI thread, it is created right there, on that thread`() {
        manager(isUiThread = { true }).createSession()
        assertEquals(Thread.currentThread().name, createdOn.get())
    }

    @Test
    fun `a failure while creating reaches the caller`() {
        val failing =
            TerminalSessionManager(
                activity,
                mockk<TerminalSessionClient>(relaxed = true),
                mockk<EventBridge>(relaxed = true),
                { false },
                TerminalSessionManager.SessionFactory { _, _, _, _, _ -> error("no pty") },
            )
        val thrown = runCatching { failing.createSession() }.exceptionOrNull()
        assertEquals("no pty", thrown?.message)
    }

    @Test
    fun `the sessions are in a copy-on-write list`() {
        val field = TerminalSessionManager::class.java.getDeclaredField("sessions")
        assertEquals(CopyOnWriteArrayList::class.java, field.type)
    }
}
