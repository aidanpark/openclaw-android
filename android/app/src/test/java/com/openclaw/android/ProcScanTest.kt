package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** [ProcScan] and [RunSignal] against a fake `/proc` tree. */
internal class ProcScanTest {
    @TempDir
    lateinit var root: File

    private val proc by lazy { FakeProc(File(root, "proc")) }
    private val scan by lazy { ProcScan(proc.dir) }
    private val token = "tok-1"
    private val tokenEnv = "OA_APP_RUN_TOKEN=$token"

    // ── reading ─────────────────────────────────────────────────────────────

    @Test
    fun `pids are the positive numeric entries only`() {
        proc.add(10, 1, listOf("a")).add(20, 1, listOf("b"))
        File(proc.dir, "self").mkdirs()
        File(proc.dir, "0").mkdirs()
        File(proc.dir, "-3").mkdirs()
        File(proc.dir, "net").mkdirs()
        assertEquals(setOf(10, 20), scan.pids().toSet())
        assertEquals(emptyList<Int>(), ProcScan(File(root, "missing")).pids())
    }

    @Test
    fun `cmdline and environ are NUL-separated and an unreadable entry is null`() {
        proc.add(10, 1, listOf("bash", "/x/oa", "--update"), environ = listOf("A=1", "B=two words"))
        assertEquals(listOf("bash", "/x/oa", "--update"), scan.cmdline(10))
        assertEquals(listOf("A=1", "B=two words"), scan.environ(10))
        assertNull(scan.cmdline(99))
        assertNull(scan.environ(99))
    }

    @Test
    fun `ppid is read after the last parenthesis, whatever the command name holds`() {
        proc.add(10, 77, listOf("x"), comm = "my prog")
        proc.add(11, 78, listOf("x"), comm = "a) S 999 (b")
        proc.add(12, 79, listOf("x"), comm = "))")
        assertEquals(77, scan.ppid(10))
        assertEquals(78, scan.ppid(11))
        assertEquals(79, scan.ppid(12))
        File(proc.dir, "13").mkdirs()
        File(proc.dir, "13/stat").writeText("garbage without parens")
        assertNull(scan.ppid(13))
        assertNull(scan.ppid(404))
    }

    @Test
    fun `a zombie or a missing process is not alive`() {
        proc.add(10, 1, listOf("x")).add(11, 1, listOf("y"), comm = "z) Z (z")
        assertTrue(scan.alive(10))
        assertTrue(scan.alive(11), "a ') Z (' inside the name read as a zombie")
        proc.zombie(10)
        assertFalse(scan.alive(10))
        assertFalse(scan.alive(404))
    }

    // ── token ───────────────────────────────────────────────────────────────

    @Test
    fun `pidsWithToken matches the exact entry only`() {
        proc.add(10, 1, listOf("bash"), environ = listOf("HOME=/h", tokenEnv))
        proc.add(11, 1, listOf("bash"), environ = listOf("${tokenEnv}x"))
        proc.add(12, 1, listOf("bash"), environ = listOf("X_$tokenEnv"))
        proc.add(13, 1, listOf("bash"), environ = listOf("OA_APP_RUN_TOKEN="))
        proc.add(14, 1, listOf("bash", tokenEnv)) // in the arguments, not the environment
        assertEquals(setOf(10), scan.pidsWithToken(token))
        assertEquals(emptySet<Int>(), scan.pidsWithToken(""), "an empty token matched")
    }

    // ── updaters running elsewhere ──────────────────────────────────────────

    @Test
    fun `externalRunners finds updaters and installers by their arguments, not lookalikes`() {
        val runners =
            mapOf(
                10 to listOf("bash", "/data/data/com.termux/files/usr/tmp/update-core.Ab12Cd.sh"),
                11 to listOf("bash", "update-core.sh"),
                12 to listOf("bash", "/home/.openclaw-android/post-setup.sh", "--tools-only", "tmux"),
                13 to listOf("/usr/bin/bash", "/usr/bin/oa", "--update"),
                14 to listOf("oaupdate"),
            )
        val lookalikes =
            mapOf(
                20 to listOf("oa", "--install"),
                21 to listOf("tail", "-f", "/home/.openclaw-android/update.log"),
                22 to listOf("vim", "update-core.sh.bak"),
                23 to listOf("grep", "oa --update"),
                24 to listOf("bash", "/x/update-core/notes.txt"),
                25 to listOf("--update", "oa"),
            )
        (runners + lookalikes).forEach { (pid, args) -> proc.add(pid, 1, args) }
        assertEquals(runners.keys, scan.externalRunners("own").toSet())
    }

    @Test
    fun `isUpdateRunner is true when the program is an updater or installer`() {
        listOf(
            listOf("bash", "/home/.openclaw-android/post-setup.sh"),
            listOf("/data/data/com.openclaw.android/files/usr/bin/bash", "post-setup.sh", "--tools-only", "tmux"),
            listOf("bash", "update-core.sh"),
            listOf("bash", "/tmp/update-core.Ab12Cd.sh"),
            listOf("bash", "-e", "/tmp/update-core.Ab12Cd.sh"),
            listOf("oaupdate"),
            listOf("oa", "--update"),
            listOf("/usr/bin/env", "bash", "/usr/bin/oa", "--update"),
            listOf("bash", "/usr/bin/oa", "--update", "--verbose"),
        ).forEach { assertTrue(ProcScan.isUpdateRunner(it), "$it") }
    }

    @Test
    fun `isUpdateRunner is false when the script is only an argument of another program`() {
        listOf(
            listOf("less", "/home/.openclaw-android/post-setup.sh"),
            listOf("vim", "update-core.sh"),
            listOf("cat", "/tmp/update-core.Ab12Cd.sh"),
            listOf("--update", "oa"),
            listOf("grep", "oa --update"),
            listOf("oa", "--install"),
            listOf("oa", "--status", "--update"),
            listOf("bash", "/x/update-core/notes.txt"),
            listOf("bash", "update-core.sh.bak"),
            listOf("bash"),
            emptyList(),
        ).forEach { assertFalse(ProcScan.isUpdateRunner(it), "$it") }
    }

    /** Documented limit: an `env NAME=value` assignment is read as the program. */
    @Test
    fun `known limit - an env assignment before oa hides the updater`() {
        assertFalse(ProcScan.isUpdateRunner(listOf("env", "A=b", "oa", "--update")))
    }

    @Test
    fun `a pager on post-setup sh in a terminal does not make the app's update BUSY`() {
        proc.add(30, 1, listOf("less", "/home/.openclaw-android/post-setup.sh"))
        proc.add(31, 1, listOf("vim", "update-core.sh"))
        assertEquals(emptyList<Int>(), scan.externalRunners("own"))
    }

    @Test
    fun `externalRunners leaves out the app's own run by its token`() {
        proc.add(10, 1, listOf("bash", "/usr/bin/oa", "--update"), environ = listOf(tokenEnv))
        proc.add(11, 1, listOf("bash", "/usr/bin/oa", "--update"), environ = listOf("OA_APP_RUN_TOKEN=other"))
        proc.add(12, 1, listOf("bash", "/usr/bin/oa", "--update"))
        assertEquals(setOf(11, 12), scan.externalRunners(token).toSet())
        assertEquals(setOf(10, 11, 12), scan.externalRunners("").toSet())
    }

    // ── trees ───────────────────────────────────────────────────────────────

    @Test
    fun `descendantsOf follows the parent chain, roots excluded`() {
        proc.add(100, 1, listOf("sh"))
        proc.add(101, 100, listOf("bash"))
        proc.add(102, 101, listOf("node"))
        proc.add(103, 102, listOf("worker"))
        proc.add(200, 1, listOf("other"))
        proc.add(201, 200, listOf("child-of-other"))
        assertEquals(setOf(101, 102, 103), scan.descendantsOf(listOf(100)))
        assertEquals(setOf(102, 103, 201), scan.descendantsOf(listOf(101, 200)))
        assertEquals(emptySet<Int>(), scan.descendantsOf(emptyList()))
        assertEquals(emptySet<Int>(), scan.descendantsOf(listOf(404)))
    }

    @Test
    fun `a parent loop (recycled pids) ends`() {
        proc.add(10, 11, listOf("a")).add(11, 10, listOf("b")).add(12, 11, listOf("c"))
        assertEquals(setOf(11, 12), scan.descendantsOf(listOf(10)))
    }

    // ── RunSignal ───────────────────────────────────────────────────────────

    @Test
    fun `topDown orders the set parents first, ties by pid`() {
        proc.add(50, 1, listOf("oa")) // top
        proc.add(40, 50, listOf("tee")) // child of top
        proc.add(60, 50, listOf("bash")) // child of top
        proc.add(30, 60, listOf("curl")) // grandchild
        proc.add(70, 999, listOf("stray")) // parent outside the set: also a top
        assertEquals(listOf(50, 70, 40, 60, 30), RunSignal.topDown(setOf(30, 40, 50, 60, 70), scan))
    }

    @Test
    fun `topDown terminates on a parent loop`() {
        proc.add(10, 11, listOf("a")).add(11, 10, listOf("b"))
        assertEquals(setOf(10, 11), RunSignal.topDown(setOf(10, 11), scan).toSet())
    }

    @Test
    fun `sendTerm signals every process of the run top down with SIGTERM and nothing else`() {
        proc.add(50, 1, listOf("bash", "/x/oa", "--update"), environ = listOf(tokenEnv))
        proc.add(51, 50, listOf("bash", "update-core.X.sh"), environ = listOf(tokenEnv))
        proc.add(52, 51, listOf("curl"), environ = listOf(tokenEnv))
        proc.add(60, 1, listOf("bash", "/x/oa", "--update"), environ = listOf("OA_APP_RUN_TOKEN=terminal"))
        proc.add(61, 50, listOf("cleared-env")) // a child that dropped the environment: known limit
        val sent = mutableListOf<Pair<Int, Int>>()
        assertTrue(RunSignal.sendTerm(token, scan) { pid, sig -> sent += pid to sig })
        assertEquals(listOf(50 to 15, 51 to 15, 52 to 15), sent)
    }

    @Test
    fun `sendTerm stops as soon as the run is no longer cancelable`() {
        proc.add(50, 1, listOf("oa"), environ = listOf(tokenEnv))
        proc.add(51, 50, listOf("bash"), environ = listOf(tokenEnv))
        val sent = mutableListOf<Int>()
        var allowed = 1
        assertTrue(RunSignal.sendTerm(token, scan, allowed = { allowed-- > 0 }) { pid, _ -> sent += pid })
        assertEquals(listOf(50), sent)
        sent.clear()
        assertFalse(RunSignal.sendTerm(token, scan, allowed = { false }) { pid, _ -> sent += pid })
        assertEquals(emptyList<Int>(), sent)
    }

    @Test
    fun `sendTerm with no process of the run reports nothing delivered`() {
        proc.add(60, 1, listOf("oa"), environ = listOf("OA_APP_RUN_TOKEN=terminal"))
        val sent = mutableListOf<Int>()
        assertFalse(RunSignal.sendTerm(token, scan) { pid, _ -> sent += pid })
        assertFalse(RunSignal.sendTerm("", scan) { pid, _ -> sent += pid })
        assertEquals(emptyList<Int>(), sent)
    }
}
