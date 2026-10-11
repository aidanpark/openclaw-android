package com.openclaw.android

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * The time limit that reaches descendants ([TimeoutWrap]): how a run is wrapped, how its end is read,
 * and — where the machine has coreutils `timeout` — that a grandchild really is gone afterwards.
 */
class CommandRunnerTimeoutWrapTest {
    private lateinit var root: File
    private lateinit var prefix: File
    private lateinit var home: File

    private val env get() = mapOf("PREFIX" to prefix.path, "HOME" to home.path, "PATH" to "/usr/bin:/bin")

    @BeforeEach
    fun setup() {
        root = Files.createTempDirectory("timeout-wrap").toFile()
        prefix = File(root, "usr").apply { File(this, "bin").mkdirs() }
        home = File(root, "home").apply { mkdirs() }
    }

    @AfterEach
    fun teardown() {
        root.deleteRecursively()
    }

    /** A stand-in `timeout`: logs its arguments, then `sleep`s [afterSec] and exits [exitCode], or runs the program. */
    private fun fakeTimeout(
        exitCode: Int? = null,
        afterSec: Int = 0,
    ): File {
        val log = File(home, "timeout-args.log")
        val body =
            if (exitCode == null) {
                // timeout -k 2 SEC program args...
                "shift 3\nexec \"\$@\"\n"
            } else {
                "sleep $afterSec\nexit $exitCode\n"
            }
        return File(prefix, "bin/timeout").apply {
            writeText("#!/bin/sh\necho \"\$@\" >> \"${log.path}\"\n$body")
            setExecutable(true)
        }
    }

    private val timeoutArgs get() = File(home, "timeout-args.log").takeIf { it.isFile }?.readLines().orEmpty()

    // ── the limit ───────────────────────────────────────────────────────────

    @Test
    fun `no timeout binary in the prefix means no limit of its own`() {
        assertNull(TimeoutWrap.forRun(env, 5_000))
    }

    @Test
    fun `a timeout binary gives whole seconds rounded up, and a net after its own kill`() {
        fakeTimeout()
        val limit = TimeoutWrap.forRun(env, 5_200)
        assertNotNull(limit)
        assertEquals(6, limit!!.seconds)
        assertEquals(listOf(File(prefix, "bin/timeout").path, "-k", "2", "6"), limit.prefix)
        // later than timeout's TERM at 6 s and its KILL 2 s after
        assertTrue(limit.safetyNetMs > (6 + 2) * 1000, "net ${limit.safetyNetMs}")
        assertEquals(1, TimeoutWrap.forRun(env, 1)!!.seconds)
    }

    @Test
    fun `124 or 137 after the limit is a timeout, the same codes before it are the program's own`() {
        fakeTimeout()
        val limit = TimeoutWrap.forRun(env, 3_000)!!
        assertTrue(limit.isTimeout(124, 3_000))
        assertTrue(limit.isTimeout(137, 3_100))
        assertFalse(limit.isTimeout(124, 200), "an early 124 is the program's own exit")
        assertFalse(limit.isTimeout(137, 200), "an early 137 was killed by something else")
        assertFalse(limit.isTimeout(0, 3_000))
        assertFalse(limit.isTimeout(1, 3_000))
    }

    // ── the run ─────────────────────────────────────────────────────────────

    @Test
    fun `the program runs under timeout with the limit and its own arguments`() {
        fakeTimeout()
        val result = CommandRunner.runExecutable("echo", listOf("hello", "world"), env, home, timeoutMs = 4_000)
        assertEquals(0, result.exitCode)
        assertEquals("hello world", result.stdout.trim())
        val args = timeoutArgs.single().split(' ')
        assertEquals(listOf("-k", "2", "4"), args.take(3))
        assertTrue(args[3].endsWith("/echo"), "the program is resolved on the PATH: $args")
        assertEquals(listOf("hello", "world"), args.drop(4))
    }

    @Test
    fun `without timeout the program runs as before`() {
        val result = CommandRunner.runExecutable("echo", listOf("plain"), env, home, timeoutMs = 4_000)
        assertEquals(0, result.exitCode)
        assertEquals("plain", result.stdout.trim())
    }

    @Test
    fun `an exit 124 after the limit reads as a timeout like the plain wait did`() {
        fakeTimeout(exitCode = 124, afterSec = 1)
        val result = CommandRunner.runExecutable("echo", emptyList(), env, home, timeoutMs = 1_000)
        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.startsWith(CommandRunner.TIMEOUT_PREFIX), result.stderr)
    }

    @Test
    fun `an exit 124 right away is the program's own`() {
        fakeTimeout(exitCode = 124, afterSec = 0)
        val result = CommandRunner.runExecutable("echo", emptyList(), env, home, timeoutMs = 5_000)
        assertEquals(124, result.exitCode)
        assertFalse(result.stderr.startsWith(CommandRunner.TIMEOUT_PREFIX), result.stderr)
    }

    // ── a real timeout (Linux, or anywhere coreutils is) ────────────────────

    @Test
    fun `a grandchild that outlives the shell is gone after the time passed`() {
        val real = listOf("/usr/bin/timeout", "/bin/timeout").map(::File).firstOrNull { it.isFile && it.canExecute() }
        assumeTrue(real != null, "no coreutils timeout on this machine")
        File(prefix, "bin/timeout").toPath().let { Files.createSymbolicLink(it, real!!.toPath()) }
        val pidFile = File(home, "grandchild.pid")
        val started = System.nanoTime()
        val result =
            CommandRunner.runExecutable(
                "sh",
                listOf("-c", "sleep 600 & echo \$! > '${pidFile.path}'; wait"),
                env,
                home,
                timeoutMs = 1_000,
            )
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(-1, result.exitCode, result.toString())
        assertTrue(result.stderr.startsWith(CommandRunner.TIMEOUT_PREFIX), result.stderr)
        assertTrue(tookMs < 8_000, "the run took ${tookMs}ms")
        val pid = pidFile.readText().trim().toLong()
        val gone = TestWait.until(5_000) { !ProcessHandle.of(pid).map { it.isAlive }.orElse(false) }
        assertTrue(gone, "the grandchild $pid is still running")
    }
}
