package com.openclaw.android

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The managed SETUP run end to end: the real [ManagedRunner], [ManagedRunGuard], [RunLease],
 * [CommandRunner], [ProcScan], [RunSignal], [RunOutcomeStore] and verdict, against a FAKE
 * `post-setup.sh` run by the real bash ([SetupWorld]). What the page receives (`run_progress`),
 * what is kept across restarts (`last-run.conf`) and what the script was given (argv, environment)
 * are checked.
 */
internal class ManagedSetupEndToEndTest {
    @TempDir
    lateinit var root: File

    private lateinit var s: SetupWorld

    @BeforeEach
    fun setUp() {
        ManagedRunWorld.resetShared()
        s = SetupWorld(root)
    }

    @AfterEach
    fun tearDown() {
        val ended = s.cleanup()
        ManagedRunWorld.resetShared()
        assertTrue(ended, "a SETUP run outlived its test")
    }

    private fun run(runner: ManagedRunner = s.runner()): EmittedEvent {
        s.w.runToEnd(runner, RunKinds.SETUP)
        return s.w.finalEvent()
    }

    private fun assertFailed(
        end: EmittedEvent,
        reason: String,
    ) {
        assertEquals("failed", end.data["phase"], end.toString())
        assertEquals(reason, end.data["reason"], end.toString())
        assertEquals(RunKinds.SETUP, end.data["kind"])
    }

    private fun lastSetup(runner: ManagedRunner = s.runner()): Map<String, Any?>? = runner.lastRuns()[RunKinds.SETUP]

    private val setupKeys = ManagedRunWorld.STATE_KEYS + setOf("needMb", "haveMb", "warn")

    // ── success ─────────────────────────────────────────────────────────────

    @Test
    fun `a full setup is done - seven stages, the marker, the final file, OA_NO_ONBOARD and the token`() {
        s.fakeSetup(s.successBody)
        val end = run()
        assertEquals("done", end.data["phase"], end.toString())
        assertEquals(RunKinds.SETUP, end.data["kind"])
        assertEquals(7, end.data["stageTotal"])
        assertEquals(0, end.data["exit"])
        assertNull(end.data["reason"])
        assertEquals(emptyList<String>(), end.data["warn"])
        assertNull(end.data["needMb"])
        // every stage reached the page, in order
        val stages =
            s.w
                .runEvents()
                .map { it.data["stage"] as Int }
                .distinct()
        assertEquals((0..7).toList(), stages)
        s.w.runEvents().forEach { assertEquals(setupKeys, it.data.keys, it.toString()) }
        // the script: the home copy run as `bash <script>`, no arguments
        assertEquals(listOf(""), s.calls())
        val env = s.envLines().single()
        assertNotEquals("<unset>", env[0], "no run token")
        assertEquals("1", env[1], "OA_NO_ONBOARD")
        assertEquals("<unset>", env[2], "OA_ASSUME_YES is not given to the first install")
        assertEquals(s.script.absolutePath, env[3])
        assertFalse(File(s.home, "onboard-ran").exists(), "the onboard part ran")
        assertTrue(s.marker.exists())
        val result = SetupResultParser.read(s.result)!!
        assertEquals(0, result.exit)
        assertEquals("done", result.stage)
        assertEquals(1, s.refreshes.get(), "the script is refreshed once before the run")
        val last = lastSetup()!!
        assertEquals("success", last["verdict"])
        assertEquals(0, last["exit"])
    }

    @Test
    fun `the run token is the one the cancel signal looks for`() {
        s.fakeSetup("result\nstage 1 \"x\"\nhold go\n${s.successBody}")
        s.w.startInBackground(s.runner(), RunKinds.SETUP)
        assertTrue(TestWait.until { s.envLines().isNotEmpty() })
        assertEquals(ManagedRunGuard.runToken, s.envLines().single()[0])
        s.release("go")
        assertEquals("done", s.w.finalEvent().data["phase"])
    }

    @Test
    fun `a running gateway neither refuses a SETUP run nor is stopped`() {
        s.w.portOpen = true
        s.w.gwProc.add(900, 1, listOf("node", "/x/openclaw.mjs", "gateway"))
        s.w.sessionPids = listOf(1)
        s.fakeSetup(s.successBody)
        assertEquals("done", run().data["phase"])
        assertEquals(emptyList<Pair<Int, Int>>(), s.w.gwSignals.toList())
        assertEquals(emptyList<EmittedEvent>(), s.w.gatewayEvents())
    }

    // ── failures ────────────────────────────────────────────────────────────

    @Test
    fun `each error code of the script ends the run with its reason and the script's words`() {
        val table =
            mapOf(
                "free-space" to "NO_SPACE",
                "index-download" to "NETWORK",
                "node-download" to "NETWORK",
                "deb" to "NETWORK",
                "index-mismatch" to "VERIFY_FAILED",
                "index-noentry" to "VERIFY_FAILED",
                "index-2" to "VERIFY_FAILED",
                "node-checksum" to "VERIFY_FAILED",
                "glibc" to "INSTALL_FAILED",
                "git" to "INSTALL_FAILED",
                "git-wrapper" to "INSTALL_FAILED",
                "node-run" to "INSTALL_FAILED",
                "node-verify" to "INSTALL_FAILED",
                "npm-openclaw" to "INSTALL_FAILED",
                "openclaw-incomplete" to "OPENCLAW_INCOMPLETE",
                "env" to "ENV",
                "unknown" to "UNKNOWN",
                "from-a-later-script" to "UNKNOWN",
            )
        table.forEach { (code, reason) ->
            s.w.events.clear()
            s.forgetProcs()
            s.fakeSetup(s.failBody(code, failAt = 3, sentence = "Stopped by $code"))
            val end = run()
            assertFailed(end, reason)
            assertEquals(1, end.data["exit"], code)
            assertEquals(3, end.data["stage"], code)
            assertEquals("Stopped by $code\nCheck your network connection", end.data["detail"], code)
            assertEquals(reason, lastSetup()!!["reason"], code)
            assertFalse(s.marker.exists(), code)
        }
    }

    @Test
    fun `free-space with the lock gives NO_SPACE with the sizes from the file`() {
        s.fakeSetup(
            "busy\nresult\nNEED=2000; HAVE=321; ERR=free-space\n" +
                "echo -e \"\${RED}[FAIL]\${NC} Not enough free storage to install OpenClaw: " +
                "2000 MB needed, 321 MB available.\"\n" +
                "result 1\nexit 1",
        )
        val end = run()
        assertFailed(end, "NO_SPACE")
        assertEquals(2000, end.data["needMb"])
        assertEquals(321, end.data["haveMb"])
    }

    @Test
    fun `no space before the lock (no result file) is NO_SPACE with the sizes from the printed line`() {
        s.fakeSetup(
            """
            |echo -e "${'$'}{RED}[FAIL]${'$'}{NC} Not enough free storage to install OpenClaw: 2000 MB needed, 123 MB available."
            |echo "       Nothing was changed. Free some space (clear other apps' caches, delete unused files) and open the app again."
            |exit 1
            """.trimMargin(),
        )
        val end = run()
        assertFailed(end, "NO_SPACE")
        assertEquals(2000, end.data["needMb"])
        assertEquals(123, end.data["haveMb"])
        assertFalse(s.result.exists())
    }

    @Test
    fun `an earlier run's file does not decide a run that wrote nothing - exit 1 is UNKNOWN, exit 0 is UNKNOWN`() {
        s.result.writeText("schema=1\nrun=1\nstage=done\nexit=0\n")
        s.marker.writeText("")
        s.fakeSetup("echo nothing written\nexit 0")
        assertFailed(run(), "UNKNOWN")
        s.w.events.clear()
        s.forgetProcs()
        s.fakeSetup("echo nothing written\nexit 1")
        assertFailed(run(), "UNKNOWN")
    }

    @Test
    fun `exit 0 with a final file but without the marker is UNKNOWN`() {
        s.fakeSetup("busy\nresult\nstage 7 x\nSTAGE=done; result 0\nexit 0")
        val end = run()
        assertFailed(end, "UNKNOWN")
        assertEquals(0, end.data["exit"])
        assertEquals("failure", lastSetup()!!["verdict"])
    }

    // ── busy: exit 2 and the other run's file ───────────────────────────────

    @Test
    fun `exit 2 under another run's lock is BUSY and the other run's file is left exactly as it was`() {
        s.lock.mkdirs()
        val other = "schema=1\nrun=4000000000\nstage=4\n"
        s.result.writeText(other)
        s.result.setLastModified(1_000_000_000_000L)
        s.fakeSetup(s.successBody)
        val end = run()
        assertFailed(end, "BUSY")
        assertEquals(2, end.data["exit"])
        assertEquals(other, s.result.readText())
        assertEquals(1_000_000_000_000L, s.result.lastModified(), "the file was rewritten")
        assertFalse(s.marker.exists())
    }

    @Test
    fun `exit 2 leaves a finished run's file too`() {
        s.lock.mkdirs()
        val other = "schema=1\nrun=7\nstage=3\nerror=deb\nexit=1\n"
        s.result.writeText(other)
        s.fakeSetup(s.successBody)
        assertFailed(run(), "BUSY")
        assertEquals(other, s.result.readText())
    }

    @Test
    fun `a terminal's post-setup run is BUSY before anything, the script is not even refreshed`() {
        s.w.runProc.add(31337, 1, listOf("bash", "/data/home/.openclaw-android/post-setup.sh"))
        s.fakeSetup(s.successBody)
        val end = run()
        assertEquals("refused", end.data["phase"])
        assertEquals("BUSY", end.data["reason"])
        assertEquals(0, s.refreshes.get())
        assertEquals(emptyList<String>(), s.calls())
        assertNull(lastSetup(), "a refusal is not recorded")
    }

    // ── cancel and signals ──────────────────────────────────────────────────

    @Test
    fun `the user's cancel mid-run reaches the script, the trap writes interrupted, the end is CANCELLED`() {
        s.fakeSetup("busy\nresult\nstage 1 a\nstage 2 b\nhold never\n${s.successBody}")
        val runner = s.runner()
        s.w.startInBackground(runner, RunKinds.SETUP)
        assertTrue(TestWait.until { ManagedRunGuard.snapshot().stage == 2 && s.w.registeredPids().isNotEmpty() })
        assertTrue(ManagedRunGuard.snapshot().cancelable)
        runner.cancel()
        val end = s.w.finalEvent()
        assertEquals("cancelled", end.data["phase"], end.toString())
        assertEquals("CANCELLED", end.data["reason"])
        assertEquals(143, end.data["exit"])
        val file = SetupResultParser.read(s.result)!!
        assertEquals("interrupted", file.error)
        assertEquals(143, file.exit)
        assertEquals("CANCELLED", lastSetup()!!["reason"])
        assertTrue(s.signals.any { it.second == RunSignal.SIGTERM }, "${s.signals}")
        assertFalse(s.marker.exists())
    }

    @Test
    fun `a cancel at stage 7 is still delivered`() {
        s.fakeSetup("busy\nresult\nfor n in 1 2 3 4 5 6 7; do stage ${'$'}n x; done\nhold never\nfinish\nexit 0")
        val runner = s.runner()
        s.w.startInBackground(runner, RunKinds.SETUP)
        assertTrue(TestWait.until { ManagedRunGuard.snapshot().stage == 7 && s.w.registeredPids().isNotEmpty() })
        assertTrue(
            s.w
                .runEvents()
                .last()
                .data["cancelable"] == true ||
                ManagedRunGuard.snapshot().cancelable,
        )
        runner.cancel()
        assertEquals("cancelled", s.w.finalEvent().data["phase"])
    }

    @Test
    fun `a TERM from elsewhere (no cancel asked) is INTERRUPTED`() {
        s.fakeSetup("busy\nresult\nstage 1 a\nhold never\n${s.successBody}")
        s.w.startInBackground(s.runner(), RunKinds.SETUP)
        assertTrue(TestWait.until { s.w.registeredPids().isNotEmpty() && ManagedRunGuard.snapshot().stage == 1 })
        val pid = s.w.registeredPids().single()
        assertTrue(s.ownScript(pid))
        ProcessBuilder("kill", "-TERM", "$pid").start().waitFor()
        val end = s.w.finalEvent()
        assertFailed(end, "INTERRUPTED")
        assertEquals(143, end.data["exit"])
        assertEquals("INTERRUPTED", lastSetup()!!["reason"])
    }

    @Test
    fun `a SIGKILL (no trap, no final file) is INTERRUPTED`() {
        s.fakeSetup("busy\nresult\nstage 3 a\nhold never\n${s.successBody}")
        s.w.startInBackground(s.runner(), RunKinds.SETUP)
        assertTrue(TestWait.until { s.w.registeredPids().isNotEmpty() && ManagedRunGuard.snapshot().stage == 3 })
        ProcessBuilder("kill", "-9", "${s.w.registeredPids().single()}").start().waitFor()
        val end = s.w.finalEvent()
        assertFailed(end, "INTERRUPTED")
        assertEquals(137, end.data["exit"])
        assertNull(SetupResultParser.read(s.result)!!.exit, "the file of a killed run has no exit")
    }

    // ── warnings ────────────────────────────────────────────────────────────

    @Test
    fun `warnings - the run is done, warn lists them, the detail is the last WARN sentence`() {
        val body =
            s.successBody.replace(
                "stage 7 \"No optional tools selected\"",
                "stage 7 \"Installing optional tools...\"\n" +
                    "echo -e \"  \${YELLOW}[WARN]\${NC} tmux could not be installed (non-critical)\"\n" +
                    "WARN=tools:tmux,clawdhub; result",
            )
        s.fakeSetup(body)
        val end = run()
        assertEquals("done", end.data["phase"], end.toString())
        assertEquals(listOf("tools:tmux", "clawdhub"), end.data["warn"])
        assertEquals(2, end.data["warnings"])
        assertEquals("tmux could not be installed (non-critical)", end.data["detail"])
        assertEquals(2, lastSetup()!!["warnings"])
    }

    // ── restore after the app died ──────────────────────────────────────────

    @Test
    fun `the app dies mid-run - a new process reads INTERRUPTED and the file as interrupted, then continues`() {
        s.fakeSetup("busy\nresult\nstage 1 a\nstage 2 b\nhold never\n${s.successBody}")
        s.w.startInBackground(s.runner(), RunKinds.SETUP)
        assertTrue(TestWait.until { s.w.registeredPids().isNotEmpty() && ManagedRunGuard.snapshot().stage == 2 })
        // what a new process would read while (or after) the old one died: the start record
        val start = RunOutcomeStore(s.w.lastRunFile).load()[RunKinds.SETUP]!!
        assertFalse(start.success)
        assertEquals(UpdateReason.INTERRUPTED, start.reason)
        // the app process dies: its child is killed with it (no trap ran), the guard is gone
        val pid = s.w.registeredPids().single()
        ProcessBuilder("kill", "-9", "$pid").start().waitFor()
        assertTrue(TestWait.until { !ManagedRunGuard.isRunning() })
        s.w.runProc.remove(pid)
        ManagedRunWorld.resetShared()
        // the new process: a fresh runner over the same files, its own record lines up
        val restarted = s.runner()
        assertEquals("failure", restarted.lastRuns()[RunKinds.SETUP]!!["verdict"])
        val state = ManagedSetup.resultState(s.home, ProcScan(s.w.runProc.dir), managed = true)
        assertEquals(true, state["interrupted"])
        assertEquals("INTERRUPTED", state["reason"])
        assertEquals("2", state["stage"])
        // and "Continue installation" runs it again to the end
        s.w.events.clear()
        s.forgetProcs()
        s.fakeSetup(s.successBody)
        assertEquals("done", run(restarted).data["phase"])
        assertEquals("success", restarted.lastRuns()[RunKinds.SETUP]!!["verdict"])
    }

    // ── an older script ─────────────────────────────────────────────────────

    @Test
    fun `an older script without the literals is refused NOT_INSTALLED and never runs`() {
        s.fakeSetup(s.oldBody, capable = false)
        val end = run()
        assertEquals("refused", end.data["phase"])
        assertEquals("NOT_INSTALLED", end.data["reason"])
        assertEquals(RunKinds.SETUP, end.data["kind"])
        assertEquals(emptyList<String>(), s.calls())
        assertFalse(File(s.home, "old-script-ran").exists())
        assertNull(lastSetup())
        assertNull(RunLease.owner())
        assertEquals(1, s.refreshes.get())
    }

    @Test
    fun `a script with only one of the literals is refused too`() {
        for (literal in listOf("OA_NO_ONBOARD", "post-setup-result.conf")) {
            s.w.events.clear()
            s.forgetProcs()
            s.fakeSetup(s.oldBody, onlyLiteral = literal)
            assertFalse(ManagedSetup.capable(s.script), literal)
            val end = run()
            assertEquals("NOT_INSTALLED", end.data["reason"], literal)
            assertFalse(File(s.home, "old-script-ran").exists(), literal)
        }
    }

    @Test
    fun `the refresh decides - an older copy replaced by a capable one runs, a refresh that throws keeps the copy`() {
        s.fakeSetup(s.oldBody, capable = false)
        val newer = File(root, "newer.sh")
        s.onRefresh = {
            s.fakeSetup(s.successBody)
            newer.writeText("refreshed")
        }
        assertEquals("done", run().data["phase"])
        assertTrue(newer.exists())
        s.w.events.clear()
        s.forgetProcs()
        s.marker.delete()
        s.onRefresh = { throw IllegalStateException("network") }
        assertEquals("done", run().data["phase"], "the capable copy in place is used")
    }

    @Test
    fun `no script at all is refused NOT_INSTALLED`() {
        assertFalse(s.script.exists())
        assertEquals("NOT_INSTALLED", run().data["reason"])
    }

    // ── one run that changes the install at a time ──────────────────────────

    @Test
    fun `while SETUP runs the lease is held - a tool install or an update cannot start`() {
        s.fakeSetup("busy\nresult\nstage 1 a\nhold go\n${s.successBody}")
        s.w.startInBackground(s.runner(), RunKinds.SETUP)
        assertTrue(TestWait.until { ManagedRunGuard.snapshot().stage == 1 })
        assertEquals(RunKinds.SETUP, RunLease.owner())
        assertFalse(RunLease.tryAcquire(RunLease.TOOLS))
        assertFalse(RunLease.tryAcquire(RunKinds.UPDATE))
        assertTrue(ManagedSetup.managedRunActive())
        s.release("go")
        assertEquals("done", s.w.finalEvent().data["phase"])
        assertTrue(TestWait.until { RunLease.owner() == null })
        assertFalse(ManagedSetup.managedRunActive())
    }

    @Test
    fun `an UPDATE run's events keep their own shape (no SETUP keys)`() {
        s.w.fakeOa(s.w.successBody)
        s.w.runToEnd(s.w.runner(), RunKinds.UPDATE)
        val end = s.w.finalEvent()
        assertEquals("done", end.data["phase"])
        s.w.runEvents().forEach { assertEquals(ManagedRunWorld.STATE_KEYS, it.data.keys) }
        // the update still answers yes/no itself and keeps its own result file handling
        assertEquals(
            "1",
            s.w
                .oaEnv()
                .single()
                .second,
        )
    }
}
