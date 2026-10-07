package com.openclaw.android

/**
 * Is the OpenClaw gateway running, and may the app stop it? The app does not own the gateway
 * (it is started by typing `openclaw gateway` into a terminal session), so it stops only a gateway
 * that is a descendant of the app — its process or one of its terminal sessions ([sessionPids]) —
 * by pid, never by a name pattern. One started anywhere else is reported ([StopResult.NOT_OURS])
 * and left alone.
 *
 * Every outside effect is injected so the decisions can be tested: [scan] (/proc), [portOpen]
 * (a connect to 127.0.0.1:18789), [send] (a signal), [sessionPids] (the roots: the app's process
 * and the shells of its terminal sessions), [sleep] (the wait between polls) and [clockMs] (a
 * monotonic clock for how long a [StopResult.STILL_RUNNING] answer stays valid for a forced stop).
 */
@Suppress("LongParameterList") // every outside effect is a parameter so the decisions can be tested
internal class GatewayControl(
    private val sessionPids: () -> List<Int>,
    private val scan: ProcScan = ProcScan(),
    private val portOpen: () -> Boolean = { probePort() },
    private val send: (Int, Int) -> Unit = { pid, sig -> SafeSignal.send(pid, sig) },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val clockMs: () -> Long = { System.nanoTime() / NANOS_PER_MS },
) {
    /**
     * [running]: a gateway process exists. The port alone does not count: like the scripts (`pgrep
     * -f 'openclaw.*gateway'` is trusted over the port — scripts/backup.sh `_gateway_running`,
     * update-core.sh), a port answer may be another app's gateway, which /proc does not show.
     * [pids]: every gateway process; [ourPids]: those below the app.
     */
    data class Status(
        val running: Boolean,
        val pids: List<Int>,
        val ourPids: List<Int>,
    ) {
        val ours: Boolean get() = ourPids.isNotEmpty()
    }

    enum class StopResult { STOPPED, STILL_RUNNING, NOT_OURS, NOT_RUNNING }

    /** The pids a plain stop left running, and when: a forced stop is allowed only for them. */
    private data class StillRunning(
        val pids: Set<Int>,
        val atMs: Long,
    )

    @Volatile
    private var stillRunning: StillRunning? = null

    fun status(): Status {
        val gateways = gatewayPids()
        val roots = sessionPids().filter { it > 0 }
        val below = if (gateways.isEmpty()) emptySet() else scan.descendantsOf(roots)
        val ours = gateways.filter { it in below || it in roots }
        return Status(running = gateways.isNotEmpty(), pids = gateways, ourPids = ours)
    }

    /**
     * SIGTERM to the app's own gateway processes, then up to [STOP_WAIT_MS] for them to be gone.
     * SIGKILL ([force], after the page asked the user a second time) only for pids that a plain
     * stop left running within the last [FORCE_VALID_MS]; otherwise [force] is ignored and this is
     * a plain stop.
     */
    fun stop(force: Boolean): StopResult {
        val now = status()
        // Nothing (of ours) left to stop: a remembered STILL_RUNNING must not outlive the pids it was about
        if (!now.running || !now.ours) stillRunning = null
        if (!now.running) return StopResult.NOT_RUNNING
        if (!now.ours) return StopResult.NOT_OURS
        val kill = force && mayForce(now.ourPids)
        val signal = if (kill) RunSignal.SIGKILL else RunSignal.SIGTERM
        // Checked again right before the signal: a pid recycled since the scan is not a gateway any more
        now.ourPids.filter { isGateway(it) }.forEach { send(it, signal) }
        val result = waitStopped(now.ourPids)
        stillRunning =
            if (result == StopResult.STILL_RUNNING && !kill) StillRunning(now.ourPids.toSet(), clockMs()) else null
        return result
    }

    /** Up to [STOP_WAIT_MS] for [stopped]; after that, the signalled pids being gone is enough. */
    private fun waitStopped(signalled: List<Int>): StopResult {
        repeat(MAX_POLLS) {
            if (stopped(signalled)) return StopResult.STOPPED
            sleep(POLL_MS)
        }
        // The port may still answer for a gateway that is not ours, which must not keep this stop
        // from ever succeeding
        return if (gone(signalled)) StopResult.STOPPED else StopResult.STILL_RUNNING
    }

    /** Every pid to be killed was left running by a plain stop not long ago. */
    private fun mayForce(pids: List<Int>): Boolean {
        val last = stillRunning ?: return false
        return clockMs() - last.atMs in 0..FORCE_VALID_MS && pids.isNotEmpty() && last.pids.containsAll(pids)
    }

    private fun gone(signalled: List<Int>): Boolean = signalled.none { scan.alive(it) }

    /**
     * The signalled pids are gone and the port is closed — or it answers for no gateway this app can
     * see (another app's: its processes are hidden in /proc), so there is nothing left to wait for.
     */
    private fun stopped(signalled: List<Int>): Boolean = gone(signalled) && (!portOpen() || gatewayPids().isEmpty())

    private fun gatewayPids(): List<Int> = scan.pids().filter { isGateway(it) }

    private fun isGateway(pid: Int): Boolean = scan.cmdline(pid)?.let { isGatewayCommand(it) } ?: false

    companion object {
        const val PORT = 18789
        const val CONNECT_TIMEOUT_MS = 500
        const val STOP_WAIT_MS = 10_000L
        const val POLL_MS = 200L

        /** How long a [StopResult.STILL_RUNNING] answer allows a forced stop of the same pids. */
        const val FORCE_VALID_MS = 5L * 60 * 1000
        private const val MAX_POLLS = (STOP_WAIT_MS / POLL_MS).toInt()
        private const val NANOS_PER_MS = 1_000_000L
        private const val GATEWAY = "gateway"
        private const val OPENCLAW = "openclaw"

        /**
         * The gateway as it really runs: the program ([CommandLine.programAt] — past the glibc loader,
         * `node`/`node.real` and options) has a file name starting with `openclaw` and either is
         * itself the gateway's title (`openclaw-gateway`: contains `gateway`, no `.`) or is followed
         * by the argument `gateway` (`…/openclaw.mjs gateway`, `openclaw gateway`). Narrower than the
         * scripts' `pgrep -f 'openclaw.*gateway'`: `less …/openclaw-gateway.log`, `tail … gateway.log`
         * and that `pgrep` itself run another program. Known limit: CLI subcommands such as
         * `openclaw gateway status` match too.
         */
        fun isGatewayCommand(args: List<String>): Boolean {
            val at = CommandLine.programAt(args)
            if (at < 0) return false
            val name = CommandLine.baseName(args[at])
            if (!name.startsWith(OPENCLAW)) return false
            val titled = name.contains(GATEWAY) && '.' !in name
            return titled || args.drop(at + 1).any { it.trim() == GATEWAY }
        }

        /** True when something accepts a connection on 127.0.0.1:[port] within [timeoutMs]. */
        fun probePort(
            port: Int = PORT,
            timeoutMs: Int = CONNECT_TIMEOUT_MS,
        ): Boolean =
            try {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), timeoutMs) }
                true
            } catch (_: java.io.IOException) {
                false
            } catch (_: SecurityException) {
                false
            }
    }
}
