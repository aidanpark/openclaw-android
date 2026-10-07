package com.openclaw.android

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The cancel token race (L7): a cancel is claimed for one run and its SIGTERM is sent for THAT
 * run's token; if the run ends and a new one starts while the signal is on its way, neither the
 * rest of the old run's processes nor any process of the new run is signalled. [ManagedRunner] is
 * real; `/proc` is a fake tree with the token in each process's environment, the signal is recorded.
 */
internal class ManagedRunCancelTokenTest {
    @TempDir
    lateinit var root: File

    private val proc by lazy { FakeProc(File(root, "proc")) }
    private val sent = mutableListOf<Pair<Int, Int>>()
    private var onSend: (Int, Int) -> Unit = { _, _ -> }

    @BeforeEach
    fun setup() = ManagedRunWorld.resetShared()

    @AfterEach
    fun teardown() = ManagedRunWorld.resetShared()

    private fun runner() =
        ManagedRunner(
            homeDir = File(root, "home").apply { mkdirs() },
            environment = { emptyMap() },
            outcomes = RunOutcomeStore(File(root, "last-run.conf")),
            gateway =
                GatewayControl(
                    sessionPids = { emptyList() },
                    scan = ProcScan(File(root, "gw")),
                    portOpen = { false },
                ),
            emit = { _, _ -> },
            processes =
                RunProcesses(ProcScan(proc.dir)) { pid, sig ->
                    sent += pid to sig
                    onSend(pid, sig)
                },
        )

    /** One run's pipeline: `bash update-core` [top] with `tee` [top]+1 below it, both carrying [token]. */
    private fun runProcesses(
        top: Int,
        token: String,
    ) {
        proc.add(top, 1, listOf("bash", "/usr/bin/oa", "--update"), environ = listOf("OA_APP_RUN_TOKEN=$token"))
        proc.add(top + 1, top, listOf("tee", "/h/update.log"), environ = listOf("OA_APP_RUN_TOKEN=$token"))
    }

    /** The private signal the guard is given, called as the guard calls it: with a claimed token. */
    private fun sendCancelSignal(
        r: ManagedRunner,
        token: String,
    ): Boolean {
        val m = ManagedRunner::class.java.getDeclaredMethod("sendCancelSignal", String::class.java)
        m.isAccessible = true
        return m.invoke(r, token) as Boolean
    }

    @Test
    fun `a cancel signals every process of the current run, parents first, and nothing of another token`() {
        runProcesses(OLD, "run-a")
        runProcesses(NEW, "terminal")
        assertTrue(ManagedRunGuard.tryStart(RunKinds.UPDATE, 1L, "run-a"))
        runner().cancel()
        assertEquals(listOf(OLD to RunSignal.SIGTERM, OLD + 1 to RunSignal.SIGTERM), sent)
        assertTrue(ManagedRunGuard.snapshot().cancelRequested)
    }

    /**
     * The old run ends and the next one starts while the old run's cancel is half sent (after its
     * first process): the rest of the old run is not signalled, and the new run is untouched and
     * still cancelable by its own cancel.
     */
    @Test
    fun `a run replaced while its cancel is being sent gets no further signal and the new run none`() {
        runProcesses(OLD, "run-a")
        runProcesses(NEW, "run-b")
        assertTrue(ManagedRunGuard.tryStart(RunKinds.UPDATE, 1L, "run-a"))
        onSend = { _, _ ->
            onSend = { _, _ -> }
            ManagedRunGuard.finish(RunVerdict.Failure(UpdateReason.INTERRUPTED, 143, "", 0))
            assertTrue(ManagedRunGuard.tryStart(RunKinds.UPDATE, 2L, "run-b"))
        }
        val r = runner()
        r.cancel()
        assertEquals(listOf(OLD to RunSignal.SIGTERM), sent, "signals went on after the run was replaced")
        val now = ManagedRunGuard.snapshot()
        assertEquals(ManagedRunGuard.RUNNING, now.phase, "the old run's delivery marked the new run")
        assertTrue(now.cancelable)
        sent.clear()
        r.cancel()
        assertEquals(listOf(NEW to RunSignal.SIGTERM, NEW + 1 to RunSignal.SIGTERM), sent)
    }

    /**
     * A signal claimed for run A that only gets to run once run B is the current run (a slow
     * delivery, the retry pump) sends nothing: not to A's processes and not to B's.
     */
    @Test
    fun `a signal claimed for an earlier run sends nothing once another run is current`() {
        runProcesses(OLD, "run-a")
        runProcesses(NEW, "run-b")
        assertTrue(ManagedRunGuard.tryStart(RunKinds.UPDATE, 2L, "run-b"))
        assertFalse(sendCancelSignal(runner(), "run-a"))
        assertEquals(emptyList<Pair<Int, Int>>(), sent)
    }

    @Test
    fun `a signal for the current run's token reaches its processes`() {
        runProcesses(NEW, "run-b")
        assertTrue(ManagedRunGuard.tryStart(RunKinds.UPDATE, 2L, "run-b"))
        assertTrue(sendCancelSignal(runner(), "run-b"))
        assertEquals(listOf(NEW to RunSignal.SIGTERM, NEW + 1 to RunSignal.SIGTERM), sent)
    }

    private companion object {
        const val OLD = 500
        const val NEW = 600
    }
}
