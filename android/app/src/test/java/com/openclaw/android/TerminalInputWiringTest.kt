package com.openclaw.android

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * D4 (deterministic terminal input) end to end on the native side: [JsBridge] over the REAL
 * [TerminalSessionManager] and REAL [TerminalSession]s — not a mock that re-implements the queue.
 * `showTerminal` / `writeCommandToTerminal` hand their text to the session, it waits until the
 * test starts the shell, and the setup decision is asked at that moment (marker, lease, guard).
 * Then the source structure the Activity and the page must keep (no fixed waits around terminal
 * writes, the shell-start callback wired to the manager) — an Activity cannot be unit-tested.
 */
internal class TerminalInputWiringTest {
    @TempDir
    lateinit var root: File

    private lateinit var web: RecordingWebView
    private lateinit var manager: TerminalSessionManager
    private lateinit var bootstrap: BootstrapManager
    private lateinit var activity: MainActivity
    private lateinit var bridge: JsBridge
    private lateinit var script: File
    private var marker = false
    private var capable = false

    @BeforeEach
    fun setup() {
        ManagedRunWorld.resetShared()
        web = RecordingWebView()
        activity = mockk(relaxed = true)
        every { activity.filesDir } returns File(root, "files").apply { mkdirs() }
        manager = TerminalSessionManager(activity, mockk<TerminalSessionClient>(relaxed = true), EventBridge(web.view))
        script = File(root, "files/home/.openclaw-android/post-setup.sh")
        bootstrap = mockk(relaxed = true)
        every { bootstrap.postSetupScript } returns script
        every { bootstrap.homeDir } returns File(root, "files/home")
        every { bootstrap.needsPostSetup() } returns true
        every { bootstrap.setupScriptCapable() } answers { capable }
        every { bootstrap.setupMarkerPresent() } answers { marker }
        bridge = JsBridge(activity, manager, bootstrap, EventBridge(web.view))
    }

    @AfterEach
    fun teardown() {
        EventBridge.detach(web.view)
        ManagedRunWorld.resetShared()
    }

    private val bashLine get() = "bash ${script.absolutePath}\n"

    private fun startShell(session: TerminalSession) {
        TerminalSessionFakes.startShell(session, PID)
        manager.onShellStarted(session, PID)
    }

    private fun active(): TerminalSession = manager.activeSession ?: error("no active session")

    /** A fresh page and session manager inside one test (the loop rows below). */
    private fun fresh() {
        teardown()
        setup()
    }

    // ── showTerminal: `bash <script>` waits for the shell, decided again at write time ──

    @Test
    fun `showTerminal types bash script only once the new session's shell has started`() {
        bridge.showTerminal()
        val s = active()
        assertEquals("", TerminalSessionFakes.typed(s), "written before the shell started")
        startShell(s)
        assertEquals(bashLine, TerminalSessionFakes.typed(s))
        verify(exactly = 1) { activity.showTerminal() }
    }

    @Test
    fun `the marker appearing between the click and the shell start keeps the terminal empty`() {
        bridge.showTerminal()
        marker = true
        startShell(active())
        assertEquals("", TerminalSessionFakes.typed(active()))
    }

    @Test
    fun `a managed run taking the lease or the guard between the click and the shell start keeps the terminal empty`() {
        for (holder in listOf(RunKinds.SETUP, RunKinds.UPDATE, RunLease.TOOLS)) {
            fresh()
            bridge.showTerminal()
            assertTrue(RunLease.tryAcquire(holder))
            startShell(active())
            assertEquals("", TerminalSessionFakes.typed(active()), holder)
            RunLease.release(holder)
        }
        fresh()
        bridge.showTerminal()
        assertTrue(ManagedRunGuard.tryStart(RunKinds.SETUP, 0, "t"))
        startShell(active())
        assertEquals("", TerminalSessionFakes.typed(active()), "the guard alone")
        ManagedRunGuard.finish(RunVerdict.Success(0, 0))
    }

    @Test
    fun `a run that held the lease at the click but ended before the shell started no longer blocks the script`() {
        assertTrue(RunLease.tryAcquire(RunLease.TOOLS))
        bridge.showTerminal()
        RunLease.release(RunLease.TOOLS)
        startShell(active())
        assertEquals(bashLine, TerminalSessionFakes.typed(active()), "the decision is the one at write time")
    }

    @Test
    fun `a script the app can run itself is never typed`() {
        capable = true
        bridge.showTerminal()
        startShell(active())
        assertEquals("", TerminalSessionFakes.typed(active()))
    }

    @Test
    fun `with a session already open showTerminal creates and types nothing`() {
        val existing = manager.createSession()
        startShell(existing)
        bridge.showTerminal()
        assertEquals(1, manager.sessionCount)
        assertEquals("", TerminalSessionFakes.typed(existing))
    }

    @Test
    fun `a session closed before its shell started never gets the script`() {
        bridge.showTerminal()
        val s = active()
        manager.createSession()
        manager.closeSession(s.mHandle)
        startShell(s)
        assertEquals("", TerminalSessionFakes.typed(s))
    }

    // ── writeCommandToTerminal ──────────────────────────────────────────────

    @Test
    fun `the onboarding command right after showTerminal waits for the new shell, without Enter`() {
        every { bootstrap.needsPostSetup() } returns false
        // The page's startOnboarding: showTerminal, then the command id at once (no timer)
        bridge.showTerminal()
        bridge.writeCommandToTerminal("openclawOnboard")
        val s = active()
        assertEquals("", TerminalSessionFakes.typed(s))
        startShell(s)
        assertEquals("openclaw onboard", TerminalSessionFakes.typed(s))
    }

    @Test
    fun `the bash script and a command typed after it reach the new shell in that order`() {
        bridge.showTerminal()
        bridge.writeCommandToTerminal("openclawStatus")
        startShell(active())
        assertEquals(bashLine + "openclaw status", TerminalSessionFakes.typed(active()))
    }

    @Test
    fun `a command for a started shell is written at once`() {
        val s = manager.createSession()
        startShell(s)
        bridge.writeCommandToTerminal("oaUpdate")
        assertEquals("oa --update", TerminalSessionFakes.typed(s))
    }

    @Test
    fun `an unknown command id or no session types nothing`() {
        bridge.writeCommandToTerminal("openclawOnboard") // no session at all: nothing, no crash
        val s = manager.createSession()
        bridge.writeCommandToTerminal("rm -rf /")
        bridge.writeCommandToTerminal("openclawOnboard\n")
        startShell(s)
        assertEquals("", TerminalSessionFakes.typed(s))
        BridgeGuard.terminalCommands.values.forEach { assertFalse(it.contains('\n') || it.contains('\r'), it) }
    }

    // ── source structure: no fixed waits, the callback wired ────────────────

    private fun src(path: String) = File(path).readText()

    private val jsBridge by lazy { src("src/main/java/com/openclaw/android/JsBridge.kt") }
    private val mainActivity by lazy { src("src/main/java/com/openclaw/android/MainActivity.kt") }
    private val sessionManager by lazy { src("src/main/java/com/openclaw/android/TerminalSessionManager.kt") }
    private val setupTsx by lazy { src("../www/src/screens/Setup.tsx") }

    /** From `fun name(` to the next member at class level (4-space indent). */
    private fun body(
        text: String,
        name: String,
    ): String {
        val start = text.indexOf("fun $name(")
        assertTrue(start >= 0, "fun $name not found")
        val next =
            Regex(
                """\n {4}(@JavascriptInterface|override fun|private fun|internal fun|fun |val |var |private val )""",
            ).find(
                text,
                start + 1,
            )
        return text.substring(start, next?.range?.first ?: text.length)
    }

    /** A function inside the Setup component: from `function name(` to its closing brace at 2 spaces. */
    private fun tsBody(name: String): String {
        val start = setupTsx.indexOf("  function $name(")
        assertTrue(start >= 0, "function $name not found")
        val end = setupTsx.indexOf("\n  }\n", start)
        assertTrue(end > start, "function $name has no end")
        return setupTsx.substring(start, end)
    }

    @Test
    fun `no fixed wait is left around terminal writes in JsBridge, MainActivity or the setup page`() {
        for ((name, text) in listOf("JsBridge" to jsBridge, "MainActivity" to mainActivity, "Setup.tsx" to setupTsx)) {
            assertFalse(text.contains("SHELL_INIT_DELAY_MS"), name)
            assertFalse(text.contains("ONBOARD_TYPE_DELAY"), name)
        }
        assertFalse(jsBridge.contains("postDelayed"), "JsBridge")
        assertFalse(jsBridge.contains("Looper.getMainLooper"), "JsBridge")
        // MainActivity keeps one postDelayed: the keyboard after showTerminal, unrelated to typing
        val delayed = Regex("""postDelayed\(""").findAll(mainActivity).toList()
        assertEquals(1, delayed.size, "MainActivity postDelayed: ${delayed.size}")
        assertTrue(body(mainActivity, "showTerminal").contains("KEYBOARD_SHOW_DELAY_MS"))
        assertFalse(body(mainActivity, "showTerminal").contains("write"))
        // No app-typed text bypasses the queue (user key taps and paste still write directly)
        assertFalse(Regex("""\.write\("(bash|\${'$'}platformId|openclaw)""").containsMatchIn(mainActivity))
        assertFalse(mainActivity.contains("terminalView.post {"), "a write posted to the view is a wait in disguise")
        assertFalse(Regex("""setTimeout|setInterval""").containsMatchIn(tsBody("startOnboarding")))
        assertFalse(setupTsx.contains("setTimeout("), "Setup.tsx uses a timeout again")
    }

    @Test
    fun `the setup page's onboarding is showTerminal, the command id, then leaving - in that order`() {
        val b = tsBody("startOnboarding")
        val show = b.indexOf("bridge.call('showTerminal')")
        val write = b.indexOf("bridge.call('writeCommandToTerminal', ONBOARD_COMMAND)")
        val done = b.indexOf("onComplete()")
        assertTrue(show in 0 until write && write < done, b)
        assertTrue(setupTsx.contains("const ONBOARD_COMMAND = 'openclawOnboard'"))
    }

    @Test
    fun `the shipped bundle's onboarding has no timer either`() {
        val dir = File("src/main/assets/www/assets")
        val bundle = dir.listFiles { f -> f.name.startsWith("index-") && f.name.endsWith(".js") }!!.single().readText()
        val m =
            Regex(
                """function [\w$]+\(\)\{([\w$]+)\.call\("showTerminal"\),""" +
                    """\1\.call\("writeCommandToTerminal",([\w$]+)\),[\w$]+\(\)}""",
            ).find(bundle)
        assertNotNull(m, "bundle onboarding is not showTerminal, write, leave (rebuild www)")
        assertTrue(bundle.contains("${m!!.groupValues[2]}=\"openclawOnboard\""), "the id is not openclawOnboard")
    }

    @Test
    fun `JsBridge types only through writeWhenReady, and the setup decision is inside the write-time check`() {
        assertFalse(
            Regex("""\bsession\??\.write\(""").containsMatchIn(jsBridge),
            "JsBridge writes to a session directly",
        )
        val show = body(jsBridge, "showTerminal")
        val check = show.substringAfter("writeWhenReady(session, \"bash \$script\\n\") {", "")
        assertTrue(check.isNotEmpty(), show)
        assertTrue(check.contains("markerPresent = bootstrapManager.setupMarkerPresent()"), check)
        assertTrue(check.contains("managedRunActive = ManagedSetup.managedRunActive()"), check)
        assertTrue(body(jsBridge, "writeCommandToTerminal").contains("sessionManager.writeWhenReady(session, command)"))
    }

    @Test
    fun `MainActivity reports the shell start to the manager and types only through writeWhenReady`() {
        val pid = mainActivity.substringAfter("override fun setTerminalShellPid(", "").substringBefore("override fun")
        assertTrue(pid.contains("sessionManager.onShellStarted(session, pid)"), pid)
        val finished =
            mainActivity
                .substringAfter(
                    "override fun onSessionFinished(",
                    "",
                ).substringBefore("override fun")
        assertTrue(finished.contains("sessionManager.onSessionFinished(finishedSession)"), finished)
        val start = body(mainActivity, "startInstalledTerminal")
        assertTrue(start.contains("sessionManager.writeWhenReady(session, \"\$platformId gateway\\n\")"), start)
        val cont = body(mainActivity, "continueUnfinishedSetup")
        val check = cont.substringAfter("writeWhenReady(session, \"bash \$script\\n\") {", "")
        assertTrue(check.isNotEmpty(), cont)
        // Asked again when the shell starts: the marker read then, not the value captured before
        assertTrue(check.contains("markerPresent = bootstrapManager.setupMarkerPresent()"), check)
        assertTrue(check.contains("managedRunActive = ManagedSetup.managedRunActive()"), check)
        assertFalse(check.substringBefore("}").contains("markerPresent = markerPresent"), check)
    }

    @Test
    fun `the manager clears the queue on close before killing, and on the session's end`() {
        val close = body(sessionManager, "closeSession")
        val forget = close.indexOf("pendingInput.forget(handleId)")
        assertTrue(forget >= 0 && forget < close.indexOf("finishIfRunning()"), close)
        assertTrue(body(sessionManager, "onSessionFinished").contains("pendingInput.forget(session.mHandle)"))
        assertTrue(body(sessionManager, "onShellStarted").contains("pendingInput.shellStarted(session.mHandle, pid)"))
    }

    private companion object {
        const val PID = 4242
    }
}
