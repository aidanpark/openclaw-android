package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ServerSocket

/**
 * [GatewayControl] against a fake `/proc`, an injected port probe, signal, sleep and clock: the
 * gateway is a PROCESS (the port alone is not one — it may be another app's), only a gateway below
 * the app (its process or one of its terminal sessions) is ever signalled, by pid; a forced stop
 * (SIGKILL) is allowed only for pids a plain stop left running in the last five minutes.
 */
internal class GatewayControlTest {
    @TempDir
    lateinit var root: File

    private val proc by lazy { FakeProc(File(root, "proc")) }
    private var port = false
    private var sessions = listOf(SESSION)
    private val sent = mutableListOf<Pair<Int, Int>>()
    private var slept = 0L
    private var sleeps = 0
    private var nowMs = T0
    private var onSend: (Int, Int) -> Unit = { _, _ -> }
    private var beforeSessionRead: () -> Unit = {}
    private var onSleep: () -> Unit = {}

    private fun control() =
        GatewayControl(
            sessionPids = {
                beforeSessionRead()
                sessions
            },
            scan = ProcScan(proc.dir),
            portOpen = { port },
            send = { pid, sig ->
                sent += pid to sig
                onSend(pid, sig)
            },
            sleep = {
                slept += it
                sleeps++
                onSleep()
            },
            clockMs = { nowMs },
        )

    /**
     * The app's terminal: session shell [SESSION] → bash → the gateway as it really runs after the
     * `openclaw` shim and the node wrapper exec'd into the glibc loader (see [LaunchChainContractTest]).
     */
    private fun ourGateway(pid: Int = OURS) {
        proc.add(SESSION, APP, listOf("/data/data/com.openclaw.android/files/usr/bin/bash"))
        proc.add(SESSION + 1, SESSION, listOf("bash", "-l"))
        proc.add(pid, SESSION + 1, LOADER_CHAIN)
        port = true
    }

    /** A gateway started in Termux (same uid, visible): not below the app. */
    private fun theirGateway() {
        proc.add(TERMUX_SHELL, 1, listOf("bash"))
        proc.add(THEIRS, TERMUX_SHELL, listOf("openclaw", "gateway"))
        port = true
    }

    /** The signal really ends the process and the port closes. */
    private fun signalEndsIt() {
        onSend = { pid, _ ->
            proc.zombie(pid)
            port = false
        }
    }

    // ── what counts as the gateway: the truth table ─────────────────────────

    @Test
    fun `isGatewayCommand is true for every real shape of the gateway`() {
        listOf(
            LOADER_CHAIN,
            listOf("ld-linux-aarch64.so.1", "--library-path", "/x/glibc/lib", "openclaw-gateway"),
            listOf("openclaw-gateway"),
            listOf("openclaw", "gateway"),
            listOf("/usr/bin/openclaw", "gateway", "--verbose"),
            listOf("node", "--max-old-space-size=4096", "openclaw.mjs", "gateway"),
            listOf("node", "/usr/lib/node_modules/openclaw/openclaw.mjs", "gateway"),
            listOf("/data/data/com.openclaw.android/files/usr/bin/bash", "$PREFIX/bin/openclaw", "gateway"),
            listOf("bash", "$PREFIX/bin/openclaw", "gateway"),
            // the node wrapper is a bash script: before its exec, bash runs it with the shim's arguments
            listOf("$PREFIX/bin/bash", "$HOME/.openclaw-android/bin/node", "$PREFIX/$MJS", "gateway"),
        ).forEach { assertTrue(GatewayControl.isGatewayCommand(it), "$it") }
    }

    /** Documented limits, pinned so that a change to them is a decision, not an accident. */
    @Test
    fun `known limits - a gateway CLI subcommand and a sh -c string read as the gateway`() {
        assertTrue(GatewayControl.isGatewayCommand(listOf("openclaw", "gateway", "status")))
        assertTrue(GatewayControl.isGatewayCommand(listOf("sh", "-c", "openclaw gateway")))
    }

    @Test
    fun `known limit - a node option with a separate value hides the gateway`() {
        // `-r <file>` takes a value: the value is read as the program. The node wrapper passes
        // glibc-compat.js through NODE_OPTIONS, not argv, so the real chain never looks like this.
        assertFalse(
            GatewayControl.isGatewayCommand(
                listOf("node", "-r", "$HOME/.openclaw-android/lib/glibc-compat.js", "openclaw.mjs", "gateway"),
            ),
        )
    }

    @Test
    fun `lookalike processes are not the gateway`() {
        listOf(
            listOf("less", "/home/.openclaw/logs/openclaw-gateway.log"),
            listOf("vim", "openclaw-gateway.md"),
            listOf("pgrep", "-f", "openclaw.*gateway"),
            listOf("tail", "-f", "/data/data/com.openclaw.android/files/home/.openclaw/gateway.log"),
            listOf("openclaw-gateway.log"),
            listOf("openclaw", "status"),
            listOf("openclaw", "--version"),
            listOf("gateway", "openclaw"),
            listOf("node", "/x/gateway.js"),
            listOf("/data/app/com.openclaw.android-1/lib/arm64/libterm.so"),
            listOf("cat", "/data/data/com.openclaw.android/gateway"),
            listOf("bash"),
            listOf("ld-linux-aarch64.so.1", "--library-path", "/x/glibc/lib"),
            emptyList(),
        ).forEach { assertFalse(GatewayControl.isGatewayCommand(it), "$it") }
    }

    @Test
    fun `programAt passes over the loader, its library path, interpreters and options`() {
        assertEquals(4, CommandLine.programAt(LOADER_CHAIN))
        assertEquals(0, CommandLine.programAt(listOf("openclaw-gateway")))
        assertEquals(2, CommandLine.programAt(listOf("node", "--max-old-space-size=4096", "openclaw.mjs")))
        assertEquals(2, CommandLine.programAt(listOf("env", "-i", "oa", "--update")))
        // only the argument right after --library-path is its value
        assertEquals(3, CommandLine.programAt(listOf("ld-linux-aarch64.so.1", "--library-path", "/lib", "app", "/x")))
        assertEquals(0, CommandLine.programAt(listOf("less", "openclaw-gateway.log")))
        assertEquals(-1, CommandLine.programAt(listOf("bash", "-l")))
        assertEquals(-1, CommandLine.programAt(emptyList()))
        assertEquals("openclaw.mjs", CommandLine.baseName(" /a/b/openclaw.mjs "))
    }

    // ── status ──────────────────────────────────────────────────────────────

    @Test
    fun `status reports our gateway in its real loader shape as running and ours`() {
        ourGateway()
        assertEquals(GatewayControl.Status(true, listOf(OURS), listOf(OURS)), control().status())
    }

    @Test
    fun `the gateway after it set its process title is still found and still ours`() {
        ourGateway()
        proc.cmdline(OURS, listOf("openclaw-gateway"))
        assertEquals(GatewayControl.Status(true, listOf(OURS), listOf(OURS)), control().status())
    }

    @Test
    fun `a gateway started elsewhere is running but not ours`() {
        theirGateway()
        val s = control().status()
        assertTrue(s.running)
        assertEquals(listOf(THEIRS), s.pids)
        assertFalse(s.ours)
    }

    @Test
    fun `an open port alone is not running - it may be another app's gateway`() {
        port = true
        assertEquals(GatewayControl.Status(false, emptyList(), emptyList()), control().status())
    }

    @Test
    fun `a gateway process with the port closed is running`() {
        proc.add(THEIRS, 1, listOf("openclaw", "gateway"))
        port = false
        assertTrue(control().status().running)
    }

    @Test
    fun `a pager on the gateway log in the app terminal is neither running nor ours`() {
        proc.add(SESSION, APP, listOf("bash"))
        proc.add(OURS, SESSION, listOf("less", "/home/.openclaw/logs/openclaw-gateway.log"))
        assertEquals(GatewayControl.Status(false, emptyList(), emptyList()), control().status())
    }

    @Test
    fun `the session shell itself running the gateway (exec) is ours`() {
        proc.add(SESSION, 1, listOf("openclaw", "gateway"))
        assertEquals(listOf(SESSION), control().status().ourPids)
    }

    /**
     * After the Activity is recreated the new session manager lists no shell, but the shells are
     * still children of the app process — JsBridge passes `Process.myPid()` as a root as well.
     */
    @Test
    fun `a gateway below the app process is ours even when no session shell is listed`() {
        proc.add(APP, ZYGOTE, listOf("com.openclaw.android"))
        ourGateway()
        sessions = listOf(APP)
        assertEquals(listOf(OURS), control().status().ourPids)
        sessions = emptyList()
        assertFalse(control().status().ours, "without the app root the same gateway was ours")
    }

    @Test
    fun `with both roots a gateway below a session and one below Termux are told apart`() {
        proc.add(APP, ZYGOTE, listOf("com.openclaw.android"))
        ourGateway()
        theirGateway()
        sessions = listOf(SESSION, APP)
        val s = control().status()
        assertEquals(setOf(OURS, THEIRS), s.pids.toSet())
        assertEquals(listOf(OURS), s.ourPids)
    }

    @Test
    fun `no live session means nothing is ours`() {
        ourGateway()
        sessions = emptyList()
        assertFalse(control().status().ours)
        sessions = listOf(-1, 0)
        assertFalse(control().status().ours)
    }

    // ── stop ────────────────────────────────────────────────────────────────

    @Test
    fun `nothing running is NOT_RUNNING and nothing is signalled`() {
        assertEquals(GatewayControl.StopResult.NOT_RUNNING, control().stop(force = false))
        assertEquals(GatewayControl.StopResult.NOT_RUNNING, control().stop(force = true))
        assertEquals(emptyList<Pair<Int, Int>>(), sent)
    }

    @Test
    fun `an open port with no gateway process is NOT_RUNNING and nothing is signalled`() {
        port = true
        assertEquals(GatewayControl.StopResult.NOT_RUNNING, control().stop(force = false))
        assertEquals(GatewayControl.StopResult.NOT_RUNNING, control().stop(force = true))
        assertEquals(emptyList<Pair<Int, Int>>(), sent)
        assertEquals(0, sleeps)
    }

    @Test
    fun `a gateway not below the app is NOT_OURS and never signalled, even forced`() {
        theirGateway()
        assertEquals(GatewayControl.StopResult.NOT_OURS, control().stop(force = false))
        assertEquals(GatewayControl.StopResult.NOT_OURS, control().stop(force = true))
        assertEquals(emptyList<Pair<Int, Int>>(), sent)
    }

    @Test
    fun `our gateway gets SIGTERM, only it, and STOPPED once it is gone`() {
        ourGateway()
        theirGateway()
        // the app session's other processes look nothing like a gateway
        proc.add(OURS + 1, SESSION + 1, listOf("tail", "-f", "/data/data/com.openclaw.android/gateway.log"))
        proc.add(OURS + 2, SESSION + 1, listOf("less", "/home/.openclaw/logs/openclaw-gateway.log"))
        signalEndsIt()
        assertEquals(GatewayControl.StopResult.STOPPED, control().stop(force = false))
        assertEquals(listOf(OURS to RunSignal.SIGTERM), sent)
    }

    @Test
    fun `our gateway gone and the port closed is STOPPED without waiting`() {
        ourGateway()
        signalEndsIt()
        assertEquals(GatewayControl.StopResult.STOPPED, control().stop(force = false))
        assertEquals(0, sleeps)
    }

    /** Another app's gateway answers on the port; its process is hidden in /proc (another uid). */
    @Test
    fun `our gateway gone while another app's gateway keeps the port open is STOPPED without waiting`() {
        ourGateway()
        onSend = { pid, _ -> proc.zombie(pid) } // the port stays open
        assertEquals(GatewayControl.StopResult.STOPPED, control().stop(force = false))
        assertEquals(0, sleeps, "waited for a port no gateway of this app holds")
    }

    @Test
    fun `our gateway gone while a visible gateway of another shell holds the port is STOPPED after the wait`() {
        ourGateway()
        theirGateway()
        onSend = { pid, _ -> proc.zombie(pid) } // THEIRS keeps the port
        assertEquals(GatewayControl.StopResult.STOPPED, control().stop(force = false))
        assertEquals(GatewayControl.STOP_WAIT_MS, slept, "the port of a gateway we did not signal kept the wait open")
        assertEquals(listOf(OURS to RunSignal.SIGTERM), sent)
    }

    @Test
    fun `our gateway alive with the port closed is STILL_RUNNING - the pid decides, not the port`() {
        ourGateway()
        onSend = { _, _ -> port = false }
        assertEquals(GatewayControl.StopResult.STILL_RUNNING, control().stop(force = false))
    }

    @Test
    fun `a gateway that ignores SIGTERM is STILL_RUNNING after the bounded wait, without a real sleep`() {
        ourGateway()
        val started = System.nanoTime()
        assertEquals(GatewayControl.StopResult.STILL_RUNNING, control().stop(force = false))
        assertTrue(System.nanoTime() - started < 2_000_000_000L, "the wait was a real sleep")
        assertEquals(GatewayControl.STOP_WAIT_MS, slept)
        assertEquals((GatewayControl.STOP_WAIT_MS / GatewayControl.POLL_MS).toInt(), sleeps)
        assertEquals(listOf(OURS to 15), sent, "signalled again while waiting")
    }

    @Test
    fun `a process that ends a few polls later is STOPPED`() {
        ourGateway()
        onSleep = {
            if (sleeps == 3) {
                proc.zombie(OURS)
                port = false
            }
        }
        assertEquals(GatewayControl.StopResult.STOPPED, control().stop(force = false))
        assertEquals(3, sleeps)
    }

    @Test
    fun `a pid recycled between the scan and the signal is not signalled`() {
        ourGateway()
        // status() reads the roots after scanning: by then the pid is some other program
        beforeSessionRead = { proc.cmdline(OURS, listOf("vim", "notes.txt")) }
        control().stop(force = false)
        assertEquals(emptyList<Pair<Int, Int>>(), sent)
    }

    // ── force: only after a plain stop left the same pids running ──────────

    /** A plain stop that leaves [OURS] running, on [c]. */
    private fun plainStopLeavesItRunning(c: GatewayControl) {
        assertEquals(GatewayControl.StopResult.STILL_RUNNING, c.stop(force = false))
        sent.clear()
    }

    @Test
    fun `force without a plain stop before it is a plain stop - SIGTERM only`() {
        ourGateway()
        signalEndsIt()
        assertEquals(GatewayControl.StopResult.STOPPED, control().stop(force = true))
        assertEquals(listOf(OURS to RunSignal.SIGTERM), sent)
    }

    @Test
    fun `force right after a plain stop answered STILL_RUNNING sends SIGKILL to those pids`() {
        ourGateway()
        val c = control()
        plainStopLeavesItRunning(c)
        signalEndsIt()
        assertEquals(GatewayControl.StopResult.STOPPED, c.stop(force = true))
        assertEquals(listOf(OURS to RunSignal.SIGKILL), sent)
    }

    @Test
    fun `the plain stop never sends SIGKILL, also when repeated`() {
        ourGateway()
        val c = control()
        repeat(3) { c.stop(force = false) }
        assertTrue(sent.all { it.second == RunSignal.SIGTERM }, "$sent")
    }

    @Test
    fun `force is allowed up to exactly five minutes after the STILL_RUNNING answer`() {
        ourGateway()
        val c = control()
        plainStopLeavesItRunning(c)
        nowMs = T0 + GatewayControl.FORCE_VALID_MS
        c.stop(force = true)
        assertEquals(listOf(OURS to RunSignal.SIGKILL), sent)
    }

    @Test
    fun `force one millisecond after the five minutes is a plain stop`() {
        ourGateway()
        val c = control()
        plainStopLeavesItRunning(c)
        nowMs = T0 + GatewayControl.FORCE_VALID_MS + 1
        c.stop(force = true)
        assertEquals(listOf(OURS to RunSignal.SIGTERM), sent)
    }

    @Test
    fun `force with a clock that went back is a plain stop`() {
        ourGateway()
        val c = control()
        plainStopLeavesItRunning(c)
        nowMs = T0 - 1
        c.stop(force = true)
        assertEquals(listOf(OURS to RunSignal.SIGTERM), sent)
    }

    @Test
    fun `force on a gateway the plain stop never saw is a plain stop, for every pid`() {
        ourGateway()
        val c = control()
        plainStopLeavesItRunning(c)
        proc.add(OURS + 10, SESSION + 1, listOf("openclaw", "gateway"))
        assertEquals(GatewayControl.StopResult.STILL_RUNNING, c.stop(force = true))
        assertEquals(setOf(OURS to RunSignal.SIGTERM, OURS + 10 to RunSignal.SIGTERM), sent.toSet())
    }

    @Test
    fun `a force lowered to a plain stop that answers STILL_RUNNING allows the next force`() {
        ourGateway()
        val c = control()
        c.stop(force = true)
        assertEquals(listOf(OURS to RunSignal.SIGTERM), sent, "the first force was not lowered")
        sent.clear()
        c.stop(force = true)
        assertEquals(listOf(OURS to RunSignal.SIGKILL), sent)
    }

    @Test
    fun `force on a subset of the pids left running is allowed`() {
        ourGateway()
        proc.add(OURS + 10, SESSION + 1, listOf("openclaw", "gateway"))
        val c = control()
        assertEquals(GatewayControl.StopResult.STILL_RUNNING, c.stop(force = false))
        sent.clear()
        proc.remove(OURS + 10)
        c.stop(force = true)
        assertEquals(listOf(OURS to RunSignal.SIGKILL), sent)
    }

    @Test
    fun `after a forced stop the next force must be earned again`() {
        ourGateway()
        val c = control()
        plainStopLeavesItRunning(c)
        assertEquals(GatewayControl.StopResult.STILL_RUNNING, c.stop(force = true)) // SIGKILL ignored here
        assertEquals(listOf(OURS to RunSignal.SIGKILL), sent)
        sent.clear()
        c.stop(force = true)
        assertEquals(listOf(OURS to RunSignal.SIGTERM), sent)
    }

    @Test
    fun `a plain stop that succeeds forgets the pids - a recycled pid is not killed by force`() {
        ourGateway()
        val c = control()
        plainStopLeavesItRunning(c)
        signalEndsIt()
        assertEquals(GatewayControl.StopResult.STOPPED, c.stop(force = false))
        sent.clear()
        // a new gateway gets the same pid
        proc.remove(OURS)
        ourGateway(OURS)
        onSend = { _, _ -> }
        c.stop(force = true)
        assertEquals(listOf(OURS to RunSignal.SIGTERM), sent)
    }

    @Test
    fun `the memory is per instance - a new control does not force`() {
        ourGateway()
        plainStopLeavesItRunning(control())
        control().stop(force = true)
        assertEquals(listOf(OURS to RunSignal.SIGTERM), sent)
    }

    /**
     * The design says a plain stop that is not STILL_RUNNING forgets the pids. A plain stop that
     * answers NOT_RUNNING (the gateway ended on its own) returns before the memory is touched, so a
     * gateway started later on a recycled pid within the five minutes is SIGKILLed by the first
     * force, without ever getting a SIGTERM.
     */
    @Test
    fun `a plain stop answering NOT_RUNNING forgets the pids too`() {
        ourGateway()
        val c = control()
        plainStopLeavesItRunning(c)
        proc.remove(OURS)
        assertEquals(GatewayControl.StopResult.NOT_RUNNING, c.stop(force = false))
        ourGateway(OURS)
        c.stop(force = true)
        assertEquals(listOf(OURS to RunSignal.SIGTERM), sent)
    }

    @Test
    fun `probePort sees a listening socket and a closed port`() {
        ServerSocket(0).use { server ->
            assertTrue(GatewayControl.probePort(server.localPort, 500))
        }
        val closed = ServerSocket(0).use { it.localPort }
        assertFalse(GatewayControl.probePort(closed, 200))
    }

    private companion object {
        const val ZYGOTE = 1
        const val APP = 900
        const val SESSION = 1000
        const val OURS = 1500
        const val TERMUX_SHELL = 3000
        const val THEIRS = 3001
        const val T0 = 1_000_000L
        const val PREFIX = "/data/data/com.openclaw.android/files/usr"
        const val HOME = "/data/data/com.openclaw.android/files/home"
        const val MJS = "lib/node_modules/openclaw/openclaw.mjs"

        /** `openclaw gateway` after the shim and the node wrapper exec'd into the glibc loader. */
        val LOADER_CHAIN =
            listOf(
                "$PREFIX/glibc/lib/ld-linux-aarch64.so.1",
                "--library-path",
                "$PREFIX/glibc/lib",
                "$HOME/.openclaw-android/node/bin/node.real",
                "$PREFIX/$MJS",
                "gateway",
            )
    }
}
