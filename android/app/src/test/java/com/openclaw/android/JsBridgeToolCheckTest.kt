package com.openclaw.android

import android.content.Context
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

/**
 * `checkInstalledToolsAsync`: for every tool whose files are on disk, run its fixed `--version`
 * style check ([BridgeGuard.toolVerifyCommands]) and send one `tools_check` event per tool with
 * `ok | failed | unknown`, then one `done` event. Runs the REAL CommandRunner against fake tool
 * executables (small /bin/sh scripts) in a temp prefix and home; the PATH is the app's order
 * (node bin, ~/.local/bin, prefix bin) followed by /bin:/usr/bin for the fakes' own commands.
 */
internal class JsBridgeToolCheckTest : JsBridgeToolInstallFixture() {
    private val nodeBin get() = File(home, ".openclaw-android/node/bin")
    private val localBin get() = File(home, ".local/bin")
    private val prefixBin get() = File(prefix, "bin")
    private val probeLog get() = File(home, "probe-calls.log")

    @BeforeEach
    fun appPath() {
        every { EnvironmentBuilder.build(any<Context>()) } returns
            mapOf(
                "PATH" to "${nodeBin.path}:${localBin.path}:${prefixBin.path}:/bin:/usr/bin",
                "HOME" to home.absolutePath,
                "PREFIX" to prefix.absolutePath,
            )
    }

    /** Where ToolDetection looks for (and the PATH finds) each checked tool's program. */
    private fun dirOf(id: String): File = if (id in PREFIX_TOOLS) prefixBin else nodeBin

    private fun executableOf(id: String) = BridgeGuard.toolVerifyCommands.getValue(id).executable

    /** A fake program: logs `<name> <args>` to [probeLog], then runs [body]. */
    private fun fakeTool(
        id: String,
        body: String = "exit 0",
        dir: File = dirOf(id),
    ): File {
        dir.mkdirs()
        val f = File(dir, executableOf(id))
        f.writeText(
            """
            |#!/bin/sh
            |echo "${'$'}(basename "${'$'}0") ${'$'}*" >> "${'$'}HOME/probe-calls.log"
            |$body
            |
            """.trimMargin(),
        )
        assertTrue(f.setExecutable(true), "could not make $f executable")
        return f
    }

    private fun probeCalls(): List<String> = if (probeLog.isFile) probeLog.readLines() else emptyList()

    /** Start a check and wait for its `done`; returns every tools_check event of [callbackId]. */
    private fun check(
        bridge: JsBridge,
        web: RecordingWebView,
        callbackId: String = "check-1",
        waitMs: Long = END_WAIT_MS,
    ): List<EmittedEvent> {
        bridge.checkInstalledToolsAsync(callbackId)
        assertTrue(
            TestWait.until(waitMs) {
                web.events("tools_check").any { it.data["callbackId"] == callbackId && it.data["done"] == true }
            },
            "no done event for $callbackId: ${web.scripts}",
        )
        return web.events("tools_check").filter { it.data["callbackId"] == callbackId }
    }

    private fun statuses(events: List<EmittedEvent>): Map<String, Any?> =
        events.filter { it.data["done"] != true }.associate { it.data["target"] as String to it.data["status"] }

    private fun assertDoneLastAndOnce(events: List<EmittedEvent>) {
        assertEquals(1, events.count { it.data["done"] == true }, events.toString())
        assertEquals(true, events.last().data["done"], "done is not the last event: $events")
        val done = events.last().data
        assertEquals(setOf("callbackId", "done"), done.keys, "done carries more than its id: $done")
    }

    // ── which tools are checked, with which command ─────────────────────────

    @Test
    fun `every installed tool is checked once with its fixed command and reports ok, then done`() {
        BridgeGuard.toolVerifyCommands.keys.forEach { fakeTool(it) }
        val (bridge, web) = page()
        val events = check(bridge, web)

        assertDoneLastAndOnce(events)
        val perTool = events.filter { it.data["done"] != true }
        assertEquals(BridgeGuard.toolVerifyCommands.size, perTool.size, "a tool was reported twice: $perTool")
        assertEquals(BridgeGuard.toolVerifyCommands.keys.associateWith { ToolProbe.OK }, statuses(events))
        perTool.forEach { assertEquals(setOf("callbackId", "target", "status"), it.data.keys, it.toString()) }
        val expected =
            BridgeGuard.toolVerifyCommands.values
                .map { (listOf(it.executable) + it.args).joinToString(" ") }
                .sorted()
        assertEquals(expected, probeCalls().sorted(), "a check ran something other than its fixed command")
    }

    @Test
    fun `a launcher found in the local bin directory is checked too`() {
        fakeTool("claude-code", dir = localBin)
        val (bridge, web) = page()
        assertEquals(mapOf("claude-code" to ToolProbe.OK), statuses(check(bridge, web)))
        assertEquals(listOf("claude --version"), probeCalls())
    }

    @Test
    fun `a tool that is not on disk gets no event and is not run, and with nothing installed only done arrives`() {
        val (bridge, web) = page()
        val events = check(bridge, web)
        assertEquals(1, events.size, events.toString())
        assertDoneLastAndOnce(events)
        assertEquals(emptyList<String>(), probeCalls())
    }

    @Test
    fun `playwright and the terminal-only tools other than code-server are never checked, even on disk`() {
        File(prefix, "lib/node_modules/playwright-core").mkdirs()
        File(prefix, "lib/node_modules/playwright-core/package.json").writeText("{}")
        // Programs ToolDetection would accept for the terminal-only tools, each logging if run
        val unchecked = listOf(File(prefixBin, "sshd"), File(prefixBin, "chromium"), File(nodeBin, "opencode"))
        unchecked.forEach {
            it.parentFile.mkdirs()
            it.writeText("#!/bin/sh\necho \"${'$'}(basename \"${'$'}0\") ${'$'}*\" >> \"${'$'}HOME/probe-calls.log\"\n")
            it.setExecutable(true)
        }
        fakeTool("tmux")
        listOf("playwright", "openssh-server", "chromium", "opencode").forEach {
            assertTrue(ToolDetection.isInstalled(it, prefix, home), "$it is not on disk in this setup")
        }
        val (bridge, web) = page()
        val events = check(bridge, web)
        assertEquals(mapOf("tmux" to ToolProbe.OK), statuses(events))
        assertEquals(listOf("tmux -V"), probeCalls())
    }

    @Test
    fun `the checked tools are the installable ones minus playwright, plus terminal-only code-server`() {
        val keys = BridgeGuard.toolVerifyCommands.keys
        assertEquals(BridgeGuard.toolInstallIds.keys - "playwright" + "code-server", keys)
        // code-server is installed from the terminal, so a broken copy must still be checked
        assertEquals(setOf("code-server"), keys intersect BridgeGuard.terminalOnlyTools)
        assertFalse("code-server" in BridgeGuard.toolInstallIds)
        assertFalse("playwright" in keys)
        assertTrue(BridgeGuard.toolIds.containsAll(keys))
    }

    @Test
    fun `each check is a read-only version flag run against the program ToolDetection looks for`() {
        BridgeGuard.toolVerifyCommands.forEach { (id, cmd) ->
            assertTrue(cmd.args == listOf("--version") || cmd.args == listOf("-V"), "$id runs ${cmd.args}")
            assertFalse(cmd.executable.contains('/'), "$id names a path: ${cmd.executable}")
            // The program the check runs is the one whose presence makes the tool "on disk"
            val dir = File(root, "detect-$id").apply { mkdirs() }
            val p = File(dir, "usr").apply { mkdirs() }
            val h = File(dir, "home").apply { mkdirs() }
            val bin = if (id in PREFIX_TOOLS) File(p, "bin") else File(h, ".openclaw-android/node/bin")
            bin.mkdirs()
            assertFalse(ToolDetection.isInstalled(id, p, h))
            File(bin, cmd.executable).writeText("")
            assertTrue(ToolDetection.isInstalled(id, p, h), "$id: ToolDetection does not look for ${cmd.executable}")
        }
    }

    // ── exit codes, through the real runner ─────────────────────────────────

    @Test
    fun `a plain failure, not executable, not found and a broken interpreter read failed, a signal reads unknown`() {
        fakeTool("tmux", "exit 1")
        fakeTool("ttyd", "exit 126")
        fakeTool("dufs", "exit 127")
        fakeTool("gemini-cli", "exit 2")
        fakeTool("claude-code", "exit 0")
        fakeTool("code-server", "kill -9 ${'$'}${'$'}") // killed by SIGKILL: 137
        // A launcher whose interpreter is gone (e.g. a node path that broke): the shell cannot start it
        nodeBin.mkdirs()
        File(nodeBin, "codex").apply {
            writeText("#!/nonexistent/node\n")
            setExecutable(true)
        }
        val (bridge, web) = page()
        val events = check(bridge, web)
        assertDoneLastAndOnce(events)
        assertEquals(
            mapOf(
                "tmux" to ToolProbe.FAILED,
                "ttyd" to ToolProbe.FAILED,
                "dufs" to ToolProbe.FAILED,
                "gemini-cli" to ToolProbe.FAILED,
                "claude-code" to ToolProbe.OK,
                "code-server" to ToolProbe.UNKNOWN,
                "codex-cli" to ToolProbe.FAILED,
            ),
            statuses(events),
        )
    }

    @Test
    fun `a check that does not end in time reads unknown and the others are not held back`() {
        // `exec` so the process the runner kills is the sleep itself (nothing left running)
        fakeTool("claude-code", "exec sleep 30")
        fakeTool("tmux")
        val (bridge, web) = page()
        val started = System.nanoTime()
        val events = check(bridge, web, waitMs = TIMEOUT_WAIT_MS)
        val tookMs = (System.nanoTime() - started) / NANOS_PER_MS
        assertEquals(mapOf("claude-code" to ToolProbe.UNKNOWN, "tmux" to ToolProbe.OK), statuses(events))
        assertDoneLastAndOnce(events)
        // The tmux answer came before the timed-out one
        val order = events.filter { it.data["done"] != true }.map { it.data["target"] }
        assertEquals(listOf("tmux", "claude-code"), order)
        assertTrue(tookMs in MIN_TIMEOUT_MS until TIMEOUT_WAIT_MS, "the check took ${tookMs}ms")
    }

    @Test
    fun `a program on disk that cannot be executed reads failed`() {
        prefixBin.mkdirs()
        File(prefixBin, "tmux").writeText("#!/bin/sh\nexit 0\n") // no execute bit
        assertTrue(ToolDetection.isInstalled("tmux", prefix, home))
        val (bridge, web) = page()
        val started = System.nanoTime()
        val events = check(bridge, web)
        val tookMs = (System.nanoTime() - started) / NANOS_PER_MS
        assertDoneLastAndOnce(events)
        // The process could not even be started (the runner's -1, not a timeout): the tool does not run
        assertEquals(mapOf("tmux" to ToolProbe.FAILED), statuses(events))
        assertNotEquals(ToolProbe.OK, statuses(events)["tmux"], "a file that cannot run was reported as working")
        assertTrue(tookMs < MIN_TIMEOUT_MS, "the launch failure waited for the timeout: ${tookMs}ms")
    }

    @Test
    fun `a program that cannot be executed reads failed while one that times out reads unknown, in one check`() {
        prefixBin.mkdirs()
        File(prefixBin, "tmux").writeText("#!/bin/sh\nexit 0\n") // no execute bit
        fakeTool("claude-code", "exec sleep 30")
        val (bridge, web) = page()
        val events = check(bridge, web, waitMs = TIMEOUT_WAIT_MS)
        assertDoneLastAndOnce(events)
        assertEquals(mapOf("tmux" to ToolProbe.FAILED, "claude-code" to ToolProbe.UNKNOWN), statuses(events))
    }

    @Test
    fun `no more than the probe limit of checks run at the same time`() {
        val active = File(home, "active").apply { mkdirs() }
        BridgeGuard.toolVerifyCommands.keys.forEach {
            fakeTool(
                it,
                """
                |: > "${'$'}HOME/active/${'$'}${'$'}"
                |ls "${'$'}HOME/active" | wc -l >> "${'$'}HOME/concurrency.log"
                |sleep 0.3
                |rm -f "${'$'}HOME/active/${'$'}${'$'}"
                |exit 0
                """.trimMargin(),
            )
        }
        val (bridge, web) = page()
        val events = check(bridge, web)
        assertEquals(BridgeGuard.toolVerifyCommands.size, statuses(events).size)
        val seen = File(home, "concurrency.log").readLines().map { it.trim().toInt() }
        assertEquals(BridgeGuard.toolVerifyCommands.size, seen.size)
        assertTrue(seen.max() <= ProbeLimiter.MAX_CONCURRENT, "up to ${seen.max()} checks ran at once: $seen")
        assertEquals(0, active.list()!!.size)
    }

    // ── never while an install runs ─────────────────────────────────────────

    @Test
    fun `while an install runs nothing is checked and only done is sent, and after it ends the check runs`() {
        fakeTool("ttyd")
        marker()
        blockingScript()
        val (bridge, web) = page()
        bridge.installTool("tmux")
        awaitRealCall()
        assertTrue(ToolInstallGuard.isRunning())

        val during = check(bridge, web, "during")
        assertEquals(1, during.size, "a check ran during an install: $during")
        assertDoneLastAndOnce(during)
        assertEquals(emptyList<String>(), probeCalls(), "a tool was run during an install")

        releaseFile.writeText("go")
        finalEvent(web)
        assertEquals(ToolProbe.OK, statuses(check(bridge, web, "after"))["ttyd"])
        assertEquals(listOf("ttyd --version"), probeCalls())
    }

    // ── the page names nothing but its callback id ──────────────────────────

    @Test
    fun `the bridge method takes only a callback id`() {
        val methods = JsBridge::class.java.methods.filter { it.name == "checkInstalledToolsAsync" }
        assertEquals(1, methods.size)
        assertEquals(listOf(String::class.java), methods.single().parameterTypes.toList())
        assertEquals(Void.TYPE, methods.single().returnType)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "tmux",
            "--help",
            "\$(touch \$HOME/pwned)",
            "`touch pwned`",
            "'; touch pwned; '",
            "../../bin/sh",
            "a\nb",
            "</script><script>x()</script>",
            "",
        ],
    )
    fun `a callback id changes nothing that runs and comes back verbatim`(callbackId: String) {
        fakeTool("tmux")
        fakeTool("claude-code")
        val (bridge, web) = page()
        val events = check(bridge, web, callbackId)
        assertDoneLastAndOnce(events)
        assertEquals(mapOf("tmux" to ToolProbe.OK, "claude-code" to ToolProbe.OK), statuses(events))
        assertEquals(listOf("claude --version", "tmux -V"), probeCalls().sorted())
        events.forEach { assertEquals(callbackId, it.data["callbackId"]) }
        assertFalse(File(home, "pwned").exists() || File(root, "pwned").exists(), "the callback id was run")
    }

    // ── an unexpected error still ends the check ────────────────────────────

    @Test
    fun `an error before the checks still sends done for the callback`() {
        fakeTool("tmux")
        every { EnvironmentBuilder.build(any<Context>()) } throws IllegalStateException("no environment")
        val (bridge, web) = page()
        val events = check(bridge, web, "err-1")
        assertEquals(1, events.size, events.toString())
        assertEquals(true, events.single().data["done"])
        assertEquals("err-1", events.single().data["callbackId"])
        assertFalse(events.single().data.containsKey("target"), "an error claimed a tool status")
        assertEquals(emptyList<String>(), probeCalls())
    }

    private companion object {
        val PREFIX_TOOLS = setOf("tmux", "ttyd", "dufs", "android-tools")
        const val TIMEOUT_WAIT_MS = 25_000L
        const val MIN_TIMEOUT_MS = 9_000L
        const val NANOS_PER_MS = 1_000_000L
    }
}
