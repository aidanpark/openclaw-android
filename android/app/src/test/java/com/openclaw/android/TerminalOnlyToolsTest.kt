package com.openclaw.android

import android.content.Context
import android.util.Log
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
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
 * Tools with no signed install chain get an honest TERMINAL_ONLY answer, uninstall is not offered
 * at all, and neither starts a process. CommandRunner is a spy, so any launch would show up; the
 * control case (tmux) proves the spy and the guard are really on the path.
 */
class TerminalOnlyToolsTest {
    @TempDir
    lateinit var root: File

    private lateinit var page: RecordingWebView
    private lateinit var bridge: JsBridge
    private lateinit var bootstrap: BootstrapManager

    @BeforeEach
    fun setup() {
        releaseGuard()
        EventBridge.attachedView()?.let { EventBridge.detach(it) }
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        mockkObject(CommandRunner)
        mockkObject(EnvironmentBuilder)
        every { EnvironmentBuilder.build(any<Context>()) } returns emptyMap()

        page = RecordingWebView()
        bootstrap = mockk(relaxed = true)
        // A home with no `.post-setup-done` marker
        every { bootstrap.prefixDir } returns File(root, "usr")
        every { bootstrap.homeDir } returns File(root, "home").apply { mkdirs() }
        every { bootstrap.postSetupScript } returns File(root, "home/.openclaw-android/post-setup.sh")
        // A relaxed mock's filesDir has no path: a File made from it throws in installTool's cleanup
        val activity = mockk<MainActivity>(relaxed = true)
        every { activity.filesDir } returns File(root, "files")
        bridge =
            JsBridge(
                activity,
                mockk<TerminalSessionManager>(relaxed = true),
                bootstrap,
                EventBridge(page.view),
            )
    }

    @AfterEach
    fun teardown() {
        val ended = TestWait.until { !ToolInstallGuard.isRunning() }
        EventBridge.detach(page.view)
        unmockkObject(EnvironmentBuilder)
        unmockkObject(CommandRunner)
        unmockkStatic(Log::class)
        assertTrue(ended, "a tool install outlived its test")
        releaseGuard()
    }

    private fun releaseGuard() {
        assertTrue(TestWait.until { !ToolInstallGuard.isRunning() })
        assertTrue(ToolInstallGuard.tryStart("reset", 0L))
        ToolInstallGuard.finish(ToolVerdict.Success)
    }

    private fun assertNoProcessStarted() {
        // A wrongly launched coroutine would run on Dispatchers.IO; give it time to show up
        Thread.sleep(NEGATIVE_WAIT_MS)
        verify(exactly = 0) { CommandRunner.runSync(any(), any(), any(), any()) }
        verify(exactly = 0) { CommandRunner.runExecutable(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { CommandRunner.runStreaming(any(), any(), any(), any()) }
        coVerify(exactly = 0) { CommandRunner.streamExecutable(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { CommandRunner.streamLong(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { bootstrap.refreshPostSetupScript() }
        assertFalse(ToolInstallGuard.isRunning())
    }

    @Test
    fun `terminalOnlyTools is exactly openssh-server opencode chromium and code-server and all are app tools`() {
        assertEquals(setOf("openssh-server", "opencode", "chromium", "code-server"), BridgeGuard.terminalOnlyTools)
        assertTrue(BridgeGuard.toolIds.containsAll(BridgeGuard.terminalOnlyTools))
    }

    @Test
    fun `android-tools and playwright are installable now`() {
        assertFalse("android-tools" in BridgeGuard.terminalOnlyTools)
        assertFalse("playwright" in BridgeGuard.terminalOnlyTools)
        assertEquals("android-tools", BridgeGuard.toolInstallIds["android-tools"])
        assertEquals("playwright", BridgeGuard.toolInstallIds["playwright"])
    }

    @Test
    fun `every terminal-only tool gets one TERMINAL_ONLY unsupported event and nothing runs`() {
        // code-server is named so that dropping it from terminalOnlyTools cannot shrink this loop silently
        assertTrue("code-server" in BridgeGuard.terminalOnlyTools)
        assertEquals(4, BridgeGuard.terminalOnlyTools.size)
        for (id in BridgeGuard.terminalOnlyTools) {
            page.clear()
            bridge.installTool(id)
            val e = page.events().single()
            assertEquals("tool_progress", e.type)
            assertEquals("TERMINAL_ONLY", e.data["errorKind"], id)
            assertEquals("unsupported", e.data["phase"], id)
            assertEquals(id, e.data["target"])
            assertEquals(0.0, e.data["progress"])
        }
        assertNoProcessStarted()
        assertEquals("reset", ToolInstallGuard.snapshot().tool, "a terminal-only tool took the guard")
    }

    @Test
    fun `installing code-server from the app is one TERMINAL_ONLY unsupported event, no guard and no process`() {
        bridge.installTool("code-server")
        val e = page.events().single()
        assertEquals("tool_progress", e.type)
        assertEquals("TERMINAL_ONLY", e.data["errorKind"])
        assertEquals("unsupported", e.data["phase"])
        assertEquals("code-server", e.data["target"])
        assertEquals(0.0, e.data["progress"])
        assertNoProcessStarted()
        assertEquals("reset", ToolInstallGuard.snapshot().tool, "code-server took the install guard")
    }

    @Test
    fun `uninstalling any app tool is UNINSTALL_UNSUPPORTED and runs nothing`() {
        for (id in BridgeGuard.toolIds) {
            page.clear()
            bridge.uninstallTool(id)
            val e = page.events().single()
            assertEquals("tool_progress", e.type)
            assertEquals("UNINSTALL_UNSUPPORTED", e.data["errorKind"], id)
            assertEquals("unsupported", e.data["phase"], id)
            assertEquals(id, e.data["target"])
        }
        assertNoProcessStarted()
    }

    @Test
    fun `uninstall is refused even for a tool that is on disk`() {
        File(root, "usr/bin").mkdirs()
        File(root, "usr/bin/tmux").writeText("")
        bridge.uninstallTool("tmux")
        assertEquals("UNINSTALL_UNSUPPORTED", page.events().single().data["errorKind"])
        assertTrue(File(root, "usr/bin/tmux").exists())
        assertNoProcessStarted()
    }

    @Test
    fun `unknown ids get no event and no process`() {
        bridge.installTool("openssh")
        bridge.installTool("")
        bridge.installTool("chromium-browser")
        bridge.uninstallTool("not-a-tool")
        bridge.uninstallTool("")
        assertNoProcessStarted()
        assertTrue(page.scripts.isEmpty(), page.scripts.toString())
    }

    @Test
    fun `control - an installable tool takes the guard but without the marker runs nothing`() {
        // Proves the path is live (the guard is taken and a result is reported) while no process starts
        bridge.installTool("tmux")
        assertTrue(
            TestWait.until { page.events("tool_progress").any { it.data["phase"] == "failed" } },
            page.scripts.toString(),
        )
        val running = page.events("tool_progress").first()
        assertEquals("running", running.data["phase"])
        assertEquals("tmux", running.data["target"])
        val end = page.events("tool_progress").last()
        assertEquals("SETUP_INCOMPLETE", end.data["reason"])
        assertTrue(page.events().none { it.data["errorKind"] == "TERMINAL_ONLY" })
        assertNoProcessStarted()
        assertEquals("tmux", ToolInstallGuard.snapshot().tool)
    }

    private companion object {
        const val NEGATIVE_WAIT_MS = 300L
    }
}
