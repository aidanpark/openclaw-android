package com.openclaw.android

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared fixture for the installTool tests: compiled JsBridge instances, a real EventBridge, the
 * real ToolInstallGuard and CommandRunner, against a FAKE `post-setup.sh` (a small /bin/sh script
 * in a temp home that plays the `--tools-only` contract). Nothing from the real install chain
 * runs; macOS/Linux `/bin/bash` runs the fake.
 */
internal abstract class JsBridgeToolInstallFixture {
    @TempDir
    lateinit var root: File

    protected lateinit var prefix: File
    protected lateinit var home: File
    protected lateinit var oca: File
    protected lateinit var script: File
    protected lateinit var callsLog: File
    protected lateinit var releaseFile: File
    protected val pages = mutableListOf<RecordingWebView>()
    protected val bootstraps = mutableListOf<BootstrapManager>()
    protected var signalMocked = false
    protected val signalCalls = AtomicInteger()
    protected val signalFiles: MutableList<File> = Collections.synchronizedList(mutableListOf())
    protected val signalledPids: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    protected val signalTokens: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @BeforeEach
    fun setup() {
        resetGuard()
        EventBridge.attachedView()?.let { EventBridge.detach(it) }
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        prefix = File(root, "usr").apply { mkdirs() }
        home = File(root, "home").apply { mkdirs() }
        oca = File(home, ".openclaw-android").apply { mkdirs() }
        script = File(oca, "post-setup.sh")
        callsLog = File(home, "calls.log")
        releaseFile = File(home, "release")
        mockkObject(EnvironmentBuilder)
        every { EnvironmentBuilder.build(any<Context>()) } returns
            mapOf("PATH" to "/bin:/usr/bin", "HOME" to home.absolutePath, "PREFIX" to prefix.absolutePath)
    }

    @AfterEach
    fun teardown() {
        releaseFile.writeText("go")
        val ended = TestWait.until(END_WAIT_MS) { !ToolInstallGuard.isRunning() }
        if (!ended) ToolInstallGuard.process.get()?.destroyForcibly()
        pages.forEach { EventBridge.detach(it.view) }
        unmockkObject(EnvironmentBuilder)
        if (signalMocked) unmockkObject(ToolSignal)
        unmockkStatic(Log::class)
        assertTrue(ended, "a tool install outlived its test")
        resetGuard()
    }

    protected fun resetGuard() {
        assertTrue(TestWait.until { !ToolInstallGuard.isRunning() })
        ToolInstallGuard.process.set(null)
        assertTrue(ToolInstallGuard.tryStart("reset", 0L))
        ToolInstallGuard.finish(ToolVerdict.Success)
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    /** [activitySetup] runs after the default stubs, so a test can make e.g. `filesDir` fail. */
    protected fun page(activitySetup: MainActivity.() -> Unit = {}): Pair<JsBridge, RecordingWebView> {
        val web = RecordingWebView()
        pages.add(web)
        val bootstrap = mockk<BootstrapManager>(relaxed = true)
        every { bootstrap.prefixDir } returns prefix
        every { bootstrap.homeDir } returns home
        every { bootstrap.postSetupScript } returns script
        bootstraps.add(bootstrap)
        // The app's private files dir: where the bridge keeps what it remembers across restarts
        // (a relaxed mock's File has no path, and a File made from it throws)
        val activity = mockk<MainActivity>(relaxed = true)
        every { activity.filesDir } returns appFilesDir
        activity.activitySetup()
        val bridge =
            JsBridge(
                activity,
                mockk<TerminalSessionManager>(relaxed = true),
                bootstrap,
                EventBridge(web.view),
            )
        return bridge to web
    }

    protected fun marker() = File(oca, ".post-setup-done").writeText("")

    /** `activity.filesDir` of every page this fixture makes; not created up front (the store makes it). */
    protected val appFilesDir get() = File(root, "files")

    /** Where JsBridge remembers the tools whose last install ended "broken". */
    protected val outcomesFile get() = File(appFilesDir, "tool-outcomes.conf")

    /**
     * The fake script: logs its arguments, records its environment in `$HOME/environ/<pid>` (standing
     * in for `/proc/<pid>/environ`) and its `OA_APP_RUN_TOKEN` in [tokensLog], answers `--list` like the
     * new entry, then runs [body].
     */
    protected fun fakeScript(
        body: String,
        list: String = LIST_OK,
    ) {
        script.writeText(
            """
            |#!/bin/sh
            |echo "${'$'}*" >> "${'$'}HOME/calls.log"
            |mkdir -p "${'$'}HOME/environ" && env > "${'$'}HOME/environ/${'$'}${'$'}"
            |printf '%s|%s\n' "${'$'}{OA_APP_RUN_TOKEN-<unset>}" "${'$'}*" >> "${'$'}HOME/tokens.log"
            |R="${'$'}HOME/.openclaw-android/tools-result.conf"
            |if [ "${'$'}1" = "--tools-only" ] && [ "${'$'}2" = "--list" ] && [ "${'$'}#" -eq 2 ]; then
            |$list
            |fi
            |$body
            |
            """.trimMargin(),
        )
    }

    protected fun calls(): List<String> = if (callsLog.isFile) callsLog.readLines() else emptyList()

    protected val tokensLog get() = File(home, "tokens.log")

    /** The `OA_APP_RUN_TOKEN` value (`<unset>` when absent) each call with exactly [args] was started with. */
    protected fun tokensFor(args: String): List<String> =
        (if (tokensLog.isFile) tokensLog.readLines() else emptyList())
            .filter { it.substringAfter('|') == args }
            .map { it.substringBefore('|') }

    /** Where a script started outside the app records its environment, like the fake script does. */
    protected val environDir get() = File(home, "environ")

    protected fun JsBridge.toolState(): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return Gson().fromJson(getToolInstallState(), Map::class.java) as Map<String, Any?>
    }

    /** Wait for the install to end and return its final tool_progress event. */
    protected fun finalEvent(web: RecordingWebView): EmittedEvent {
        assertTrue(TestWait.until { !ToolInstallGuard.isRunning() }, "install did not end; calls=${calls()}")
        assertTrue(
            TestWait.until { web.events("tool_progress").any { it.data["phase"] in FINAL_PHASES } },
            "no final event: ${web.scripts}",
        )
        assertEquals(emptyList<EmittedEvent>(), web.events("install_progress"), "a tool event used the platform name")
        return web.events("tool_progress").last { it.data["phase"] in FINAL_PHASES }
    }

    protected fun assertFailed(
        event: EmittedEvent,
        reason: ToolFailure,
    ) {
        assertEquals("failed", event.data["phase"], event.toString())
        assertEquals(reason.name, event.data["reason"], event.toString())
        assertEquals("tmux", event.data["target"])
        assertEquals(0.0, (event.data["progress"] as Number).toDouble())
        assertFalse(ToolInstallGuard.isRunning())
    }

    /**
     * Runs until [releaseFile] exists, then reports ok; on TERM writes an interrupted result. Like
     * post-setup.sh it records its pid in `.tools.lock/pid` — after `$HOME/hold-pid` is gone.
     */
    protected fun blockingScript() =
        fakeScript(
            """
            |RUN=${'$'}(date +%s)
            |trap 'printf "schema=1\nrun=%s\nerror=interrupted\nexit=143\n" "${'$'}RUN" > "${'$'}R"; exit 143' TERM
            |echo "working"
            |while [ -f "${'$'}HOME/hold-pid" ]; do sleep 0.05; done
            |mkdir -p "${'$'}HOME/.openclaw-android/.tools.lock" && echo ${'$'}${'$'} > "${'$'}HOME/.openclaw-android/.tools.lock/pid"
            |while [ ! -f "${'$'}HOME/release" ]; do sleep 0.05; done
            |mkdir -p "${'$'}PREFIX/bin" && : > "${'$'}PREFIX/bin/tmux"
            |printf 'schema=1\nrun=%s\ntmux=ok\nexit=0\n' "${'$'}RUN" > "${'$'}R"
            |exit 0
            """.trimMargin(),
        )

    /**
     * Waits until the script runs and has printed "working". With [web], also until the page got
     * that line: its event is emitted just after the state changes, so a `web.clear()` made before
     * it arrives would still see a late "running" event.
     */
    protected fun awaitRealCall(web: RecordingWebView? = null) {
        assertTrue(
            TestWait.until { calls().contains("--tools-only tmux") && ToolInstallGuard.process.get() != null },
            "the install never reached the script: ${calls()}",
        )
        assertTrue(TestWait.until { ToolInstallGuard.snapshot().message == "working" })
        if (web != null) {
            assertTrue(
                TestWait.until { web.events("tool_progress").any { it.data["message"] == "working" } },
                "the page never got the working line: ${web.scripts}",
            )
        }
    }

    /**
     * The JVM has no `/proc` on macOS and `android.os.Process.sendSignal` is a stub, so the call the
     * app makes (with the real `/proc`) is answered by the REAL [ToolSignal] rule run against a fake
     * `/proc` built for the pid in the lock file: `cmdline` from `ps` and `environ` from what the
     * script recorded in `$HOME/environ/<pid>` (no file: no environ). When the rule says the pid is
     * this run's script and [deliver] allows, a real SIGTERM is sent to it.
     */
    protected fun fakeSignal(deliver: () -> Boolean = { true }) {
        mockkObject(ToolSignal)
        signalMocked = true
        every { ToolSignal.sendTerm(any(), any(), match { it == File("/proc") }, any()) } answers {
            val pidFile = firstArg<File>()
            val token = secondArg<String>()
            signalCalls.incrementAndGet()
            signalFiles.add(pidFile)
            signalTokens.add(token)
            var target: Int? = null
            // procDir is not /proc here, so this call reaches the real ToolSignal
            val ours = ToolSignal.sendTerm(pidFile, token, fakeProc(pidFile)) { pid, _ -> target = pid }
            val pid = target
            if (ours && pid != null && deliver()) {
                signalledPids.add(pid)
                ProcessBuilder("kill", "-TERM", "$pid").start().waitFor()
                true
            } else {
                false
            }
        }
    }

    private val fakeProcCount = AtomicInteger()

    private fun fakeProc(pidFile: File): File {
        val proc = File(root, "fakeproc-${fakeProcCount.incrementAndGet()}").apply { mkdirs() }
        val pid = (if (pidFile.isFile) pidFile.readText().trim().toIntOrNull() else null) ?: return proc
        val dir = File(proc, "$pid").apply { mkdirs() }
        val command = commandLine(pid).trim()
        if (command.isNotEmpty()) File(dir, "cmdline").writeBytes(command.replace(' ', '\u0000').toByteArray())
        val recorded = File(environDir, "$pid")
        if (recorded.isFile) {
            val entries = recorded.readLines().joinToString("\u0000", postfix = "\u0000")
            File(dir, "environ").writeBytes(entries.toByteArray())
        }
        return proc
    }

    private fun commandLine(pid: Int): String {
        val ps = ProcessBuilder("ps", "-o", "command=", "-p", "$pid").redirectErrorStream(true).start()
        val out = ps.inputStream.bufferedReader().readText()
        ps.waitFor()
        return out
    }

    protected val lockPidFile get() = File(oca, ".tools.lock/pid")

    protected companion object {
        const val LIST_OK = "printf 'tmux\\nttyd\\ndufs\\n'; exit 0"
        val FINAL_PHASES = setOf("done", "failed", "cancelled")
        const val MS = 1000L
        const val HOUR = 3600L
        const val END_WAIT_MS = 10_000L
        const val NEGATIVE_WAIT_MS = 300L
        const val MANY_LINES = 3000
    }
}
