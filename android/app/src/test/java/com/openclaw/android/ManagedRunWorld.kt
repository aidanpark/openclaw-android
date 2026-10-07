package com.openclaw.android

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Everything a managed run touches, faked at the system boundary only: a FAKE `oa` (a real bash
 * script on the PATH of the run's environment), a fake `/proc` the script registers itself in (the
 * JVM on macOS has no `/proc`), a real SIGTERM for the cancel (`kill` by the pid the scan found),
 * and a gateway made of a second fake `/proc`, an injected port probe, signal and sleep. The real
 * [ManagedRunner], [ManagedRunGuard], [RunLease], [CommandRunner], [ProcScan], [RunSignal],
 * [GatewayControl] and [RunOutcomeStore] run.
 */
internal class ManagedRunWorld(
    val root: File,
) {
    val home = File(root, "home").apply { mkdirs() }
    val oca = File(home, ".openclaw-android").apply { mkdirs() }
    val bin = File(root, "oabin").apply { mkdirs() }
    val prefix = File(root, "usr").apply { mkdirs() }
    val runProc = FakeProc(File(root, "proc"))
    val gwProc = FakeProc(File(root, "gwproc"))
    val files = File(root, "files")
    val oa = File(bin, "oa")

    val resultFile get() = File(oca, "update-result.conf")
    val lastRunFile get() = File(files, "last-run.conf")

    val events: MutableList<EmittedEvent> = Collections.synchronizedList(mutableListOf())
    val runSignals: MutableList<Pair<Int, Int>> = Collections.synchronizedList(mutableListOf())
    val gwSignals: MutableList<Pair<Int, Int>> = Collections.synchronizedList(mutableListOf())

    @Volatile var portOpen = false

    @Volatile var sessionPids: List<Int> = emptyList()

    @Volatile var onGatewaySignal: (Int, Int) -> Unit = { _, _ -> }

    val gwSlept = AtomicLong()

    /** The gateway's monotonic clock (how long a STILL_RUNNING answer allows a forced stop). */
    val gwClockMs = AtomicLong(1_000_000L)

    fun environment(): Map<String, String> =
        mapOf(
            "PATH" to "${bin.path}:/bin:/usr/bin",
            "HOME" to home.path,
            "PREFIX" to prefix.path,
            "FAKEPROC" to runProc.dir.path,
        )

    fun gateway() =
        GatewayControl(
            sessionPids = { sessionPids },
            scan = ProcScan(gwProc.dir),
            portOpen = { portOpen },
            send = { pid, sig ->
                gwSignals += pid to sig
                onGatewaySignal(pid, sig)
            },
            sleep = { gwSlept.addAndGet(it) },
            clockMs = { gwClockMs.get() },
        )

    fun emit(
        type: String,
        data: Map<String, Any?>,
    ) {
        events += EmittedEvent(type, data)
    }

    fun runner(
        outcomes: RunOutcomeStore = RunOutcomeStore(lastRunFile),
        emit: (String, Map<String, Any?>) -> Unit = ::emit,
    ) = ManagedRunner(
        homeDir = home,
        environment = ::environment,
        outcomes = outcomes,
        gateway = gateway(),
        emit = emit,
        processes = RunProcesses(ProcScan(runProc.dir), ::sendRunSignal),
    )

    /** The cancel's signal: recorded, then really sent to the pid the scan of the fake /proc found. */
    private fun sendRunSignal(
        pid: Int,
        sig: Int,
    ) {
        runSignals += pid to sig
        if (ownScript(pid)) ProcessBuilder("kill", "-$sig", "$pid").start().waitFor()
    }

    /** True only for a live process running a script of this world (never a stranger's pid). */
    private fun ownScript(pid: Int): Boolean {
        val ps = ProcessBuilder("ps", "-o", "command=", "-p", "$pid").redirectErrorStream(true).start()
        val out = ps.inputStream.bufferedReader().readText()
        ps.waitFor()
        return out.contains(root.path)
    }

    /**
     * The fake `oa`: logs its arguments and environment, registers itself in the fake /proc (unless
     * [register] is false; then `reg` can be called from [body]), then runs [body]. `hold NAME`
     * waits for `$HOME/NAME` to exist.
     */
    fun fakeOa(
        body: String,
        register: Boolean = true,
    ) {
        oa.writeText(
            """
            |#!/bin/bash
            |echo "${'$'}*" >> "${'$'}HOME/oa-calls.log"
            |printf '%s|%s\n' "${'$'}{OA_APP_RUN_TOKEN-<unset>}" "${'$'}{OA_ASSUME_YES-<unset>}" >> "${'$'}HOME/oa-env.log"
            |[ -e "${'$'}HOME/.openclaw-android/update-result.conf" ] && echo present >> "${'$'}HOME/result-at-start.log"
            |R="${'$'}HOME/.openclaw-android/update-result.conf"
            |RED='\033[0;31m'; YELLOW='\033[1;33m'; GREEN='\033[0;32m'; BOLD='\033[1m'; NC='\033[0m'
            |reg() {
            |  d="${'$'}FAKEPROC/${'$'}${'$'}"; mkdir -p "${'$'}d"
            |  printf 'bash\0%s\0--update\0' "${'$'}0" > "${'$'}d/cmdline"
            |  env | tr '\n' '\0' > "${'$'}d/environ"
            |  echo "${'$'}${'$'} (bash) S ${'$'}PPID 1 1" > "${'$'}d/stat"
            |}
            |hold() { while [ ! -f "${'$'}HOME/${'$'}1" ]; do sleep 0.02; done; }
            |step() { echo ""; echo -e "${'$'}{BOLD}[${'$'}1/5] ${'$'}2${'$'}{NC}"; echo "----------------------------------------"; }
            |${if (register) "reg" else ":"}
            |$body
            |
            """.trimMargin(),
        )
        oa.setExecutable(true)
    }

    /** The five steps and the banner exactly as `update-core.sh` prints them. */
    val successBody =
        """
        |step 1 "Pre-flight Check"
        |echo -e "${'$'}{GREEN}[OK]${'$'}{NC}   Termux detected"
        |step 2 "Download Latest Release (tarball)"
        |step 3 "Update Core Infrastructure"
        |step 4 "Update Platform"
        |step 5 "Update Optional Tools"
        |echo ""
        |echo -e "${'$'}{GREEN}${'$'}{BOLD}  Update Complete!${'$'}{NC}"
        |exit 0
        """.trimMargin()

    fun release(name: String) = File(home, name).writeText("go")

    fun mark(name: String) = File(home, name).exists()

    fun oaCalls(): List<String> = File(home, "oa-calls.log").let { if (it.isFile) it.readLines() else emptyList() }

    /** `token|assumeYes` per call. */
    fun oaEnv(): List<Pair<String, String>> =
        File(home, "oa-env.log").let { f ->
            if (f.isFile) f.readLines().map { it.substringBefore('|') to it.substringAfter('|') } else emptyList()
        }

    /** The pids the fake oa registered. */
    fun registeredPids(): List<Int> = runProc.dir.list()?.mapNotNull { it.toIntOrNull() } ?: emptyList()

    fun runEvents(): List<EmittedEvent> = synchronized(events) { events.filter { it.type == ManagedRunner.RUN_EVENT } }

    fun gatewayEvents(): List<EmittedEvent> =
        synchronized(events) {
            events.filter {
                it.type ==
                    ManagedRunner.GATEWAY_EVENT
            }
        }

    fun finalEvent(): EmittedEvent {
        assertTrue(TestWait.until(END_WAIT_MS) { !ManagedRunGuard.isRunning() }, "run did not end: ${oaCalls()}")
        assertTrue(
            TestWait.until { runEvents().any { it.data["phase"] in END_PHASES } },
            "no end event: ${runEvents()}",
        )
        return runEvents().last()
    }

    /** As JsBridge.startRun does: take the lease on the caller thread, then run (which releases it). */
    fun startInBackground(
        runner: ManagedRunner,
        kind: String = RunKinds.UPDATE,
        stopGateway: Boolean = false,
    ): Thread {
        assertTrue(RunLease.tryAcquire(kind), "lease held by ${RunLease.owner()}")
        return Thread { runBlocking { runner.run(kind, stopGateway) } }.also {
            threads += it
            it.start()
        }
    }

    fun runToEnd(
        runner: ManagedRunner,
        kind: String = RunKinds.UPDATE,
        stopGateway: Boolean = false,
    ) {
        startInBackground(runner, kind, stopGateway).join(TimeUnit.SECONDS.toMillis(END_WAIT_S))
    }

    private val threads = mutableListOf<Thread>()

    /** Ends whatever a test left running: its own scripts only (by pid), then the guard and lease. */
    fun cleanup(): Boolean {
        listOf("release", "go", "reg-now", "to3", "end").forEach { release(it) }
        val ended = TestWait.until(END_WAIT_MS) { !ManagedRunGuard.isRunning() }
        if (!ended) ManagedRunGuard.process.get()?.destroyForcibly()
        registeredPids().filter { ownScript(it) }.forEach { ProcessBuilder("kill", "-9", "$it").start().waitFor() }
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(END_WAIT_S)) }
        return ended && threads.none { it.isAlive }
    }

    companion object {
        val END_PHASES = setOf("done", "failed", "cancelled", ManagedRunner.REFUSED)
        const val END_WAIT_S = 15L
        const val END_WAIT_MS = END_WAIT_S * 1000

        /** The guard and the lease are process-wide: every test starts and ends with both free. */
        fun resetShared() {
            ManagedRunGuard.process.set(null)
            if (ManagedRunGuard.isRunning()) ManagedRunGuard.finish(RunVerdict.Success(0, 0))
            assertTrue(ManagedRunGuard.tryStart(RunKinds.UPDATE, 0L, "reset"), "guard held")
            ManagedRunGuard.finish(RunVerdict.Success(0, 0))
            RunLease.owner()?.let { RunLease.release(it) }
        }

        /** The keys of every `run_progress` event and of `getRunState()`. */
        val STATE_KEYS =
            setOf(
                "kind",
                "phase",
                "stage",
                "stageTotal",
                "progress",
                "message",
                "cancelable",
                "cancelRequested",
                "longRunning",
                "reason",
                "exit",
                "detail",
                "warnings",
                "gatewayStopped",
            )
    }
}
