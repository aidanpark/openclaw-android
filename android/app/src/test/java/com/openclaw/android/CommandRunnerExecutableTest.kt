package com.openclaw.android

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CommandRunnerExecutableTest {
    @TempDir
    lateinit var tempDir: File

    private val env = mapOf("PATH" to "/usr/bin:/bin")

    private fun marker() = File(tempDir, "pwned")

    private fun injectionArgs(): List<String> {
        val m = marker().absolutePath
        return listOf(
            "x; touch $m",
            "\$(touch $m)",
            "`touch $m`",
            "x && touch $m",
            "x || touch $m",
            "x | touch $m",
            "x\ntouch $m",
        )
    }

    private fun script(
        dir: File,
        name: String,
        body: String,
        executable: Boolean = true,
    ): File {
        dir.mkdirs()
        return File(dir, name).apply {
            writeText("#!/bin/sh\n$body\n")
            setExecutable(executable)
        }
    }

    @Test
    fun `runExecutable passes shell metacharacters as literal arguments`() {
        injectionArgs().forEach { arg ->
            val result = CommandRunner.runExecutable("echo", listOf(arg), env, tempDir)
            assertEquals(0, result.exitCode, arg)
            assertEquals(arg, result.stdout.removeSuffix("\n"), arg)
            assertFalse(marker().exists(), "marker created by: $arg")
        }
    }

    @Test
    fun `runExecutable delivers each argument unsplit`() {
        val printer = script(File(tempDir, "bin"), "argc", "echo \"\$#\"; for a in \"\$@\"; do echo \"[\$a]\"; done")
        val result = CommandRunner.runExecutable(printer.absolutePath, listOf("a b", "", "c;d"), env, tempDir)
        assertEquals("3\n[a b]\n[]\n[c;d]\n", result.stdout)
    }

    @Test
    fun `runExecutable does not interpret metacharacters in the executable name`() {
        val result = CommandRunner.runExecutable("echo; touch ${marker().absolutePath}", emptyList(), env, tempDir)
        assertEquals(-1, result.exitCode)
        assertFalse(marker().exists())
    }

    @Test
    fun `streamExecutable passes shell metacharacters as literal arguments`() {
        injectionArgs().forEach { arg ->
            val lines = mutableListOf<String>()
            runBlocking { CommandRunner.streamExecutable("echo", listOf(arg), env, tempDir) { lines.add(it) } }
            assertEquals(arg.split("\n"), lines, arg)
            assertFalse(marker().exists(), "marker created by: $arg")
        }
    }

    @Test
    fun `runExecutable resolves the executable through the env PATH`() {
        val bin = File(tempDir, "bin")
        script(bin, "mytool", "echo found-\$1")
        val pathEnv = mapOf("PATH" to "${bin.path}:/usr/bin:/bin")
        val result = CommandRunner.runExecutable("mytool", listOf("ok"), pathEnv, tempDir)
        assertEquals(0, result.exitCode, result.stderr)
        assertEquals("found-ok", result.stdout.trim())
    }

    @Test
    fun `PATH lookup skips non-executable files and uses the first executable match`() {
        val first = File(tempDir, "first")
        val second = File(tempDir, "second")
        script(first, "mytool", "echo first", executable = false)
        script(second, "mytool", "echo second")
        val path = "${first.path}:${second.path}:/usr/bin:/bin"
        val result = CommandRunner.runExecutable("mytool", emptyList(), mapOf("PATH" to path), tempDir)
        assertEquals("second", result.stdout.trim())
    }

    @Test
    fun `PATH lookup honours directory order`() {
        val first = File(tempDir, "first")
        val second = File(tempDir, "second")
        script(first, "mytool", "echo first")
        script(second, "mytool", "echo second")
        val path = "${first.path}:${second.path}"
        val result = CommandRunner.runExecutable("mytool", emptyList(), mapOf("PATH" to path), tempDir)
        assertEquals("first", result.stdout.trim())
    }

    @Test
    fun `streamExecutable resolves the executable through the env PATH`() {
        val bin = File(tempDir, "bin")
        script(bin, "mytool", "echo streamed")
        val pathEnv = mapOf("PATH" to "${bin.path}:/usr/bin:/bin")
        val lines = mutableListOf<String>()
        runBlocking { CommandRunner.streamExecutable("mytool", emptyList(), pathEnv, tempDir) { lines.add(it) } }
        assertEquals(listOf("streamed"), lines)
    }

    @Test
    fun `runExecutable runs in the given working directory with only the given env`() {
        val result =
            CommandRunner.runExecutable(
                "sh",
                listOf("-c", "pwd; echo \"[\$FOO]\" \"[\$HOME]\""),
                mapOf("PATH" to "/usr/bin:/bin", "FOO" to "bar"),
                tempDir,
            )
        val lines = result.stdout.lines()
        assertEquals(tempDir.canonicalPath, File(lines[0]).canonicalPath)
        assertEquals("[bar] []", lines[1])
    }

    @Test
    fun `runExecutable times out on a silent hung process`() {
        val start = System.nanoTime()
        val result = CommandRunner.runExecutable("sleep", listOf("30"), env, tempDir, timeoutMs = 500)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.contains("timed out"), result.stderr)
        assertTrue(elapsedMs < 5_000, "took ${elapsedMs}ms")
    }

    @Test
    fun `drainQuietly survives a pipe closed mid-read and keeps what it read`() {
        // Android throws InterruptedIOException("read interrupted by close() on another thread")
        // in the reader when a hung process is destroyed; an uncaught throw there kills the app.
        val broken =
            object : java.io.InputStream() {
                private var sent = false

                override fun read(): Int = throw java.io.InterruptedIOException("read interrupted by close()")

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    if (!sent) {
                        sent = true
                        "partial".toByteArray().copyInto(b, off)
                        return "partial".length
                    }
                    throw java.io.InterruptedIOException("read interrupted by close()")
                }
            }
        val sink = StringBuffer()
        CommandRunner.drainQuietly(broken, sink)
        assertEquals("partial", sink.toString())
    }

    @Test
    fun `drainQuietly reads a normal stream to the end`() {
        val sink = StringBuffer()
        CommandRunner.drainQuietly("hello\nworld".byteInputStream(), sink)
        assertEquals("hello\nworld", sink.toString())
    }

    @Test
    fun `a command that reads stdin sees EOF instead of hanging`() {
        // The openclaw probe took 12s with stdin left open (M3-2 QA); the child must get EOF
        val start = System.nanoTime()
        val result =
            CommandRunner.runExecutable(
                "sh",
                listOf("-c", "read x; echo done"),
                env,
                tempDir,
                timeoutMs = 5_000,
            )
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(0, result.exitCode)
        assertEquals("done", result.stdout.trim())
        assertTrue(elapsedMs < 2_000, "took ${elapsedMs}ms")
    }

    @Test
    fun `a grandchild holding the pipe cannot stall a finished command`() {
        val start = System.nanoTime()
        val result =
            CommandRunner.runExecutable(
                "sh",
                listOf("-c", "echo hi; sleep 6 & exit 0"),
                env,
                tempDir,
                timeoutMs = 5_000,
            )
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("hi"))
        assertTrue(elapsedMs < 4_500, "took ${elapsedMs}ms")
    }

    @Test
    fun `drainQuietly also swallows unexpected runtime errors`() {
        val broken =
            object : java.io.InputStream() {
                override fun read(): Int = error("boom")

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int = error("boom")
            }
        CommandRunner.drainQuietly(broken, StringBuffer()) // must not throw
    }

    @Test
    fun `runExecutable timeout keeps output produced before the hang`() {
        val result =
            CommandRunner.runExecutable("sh", listOf("-c", "echo early; exec sleep 30"), env, tempDir, timeoutMs = 500)
        assertEquals(-1, result.exitCode)
        assertEquals("early", result.stdout.trim())
    }

    @Test
    fun `runExecutable passes through the exit code`() {
        assertEquals(0, CommandRunner.runExecutable("true", emptyList(), env, tempDir).exitCode)
        assertEquals(1, CommandRunner.runExecutable("false", emptyList(), env, tempDir).exitCode)
        assertEquals(42, CommandRunner.runExecutable("sh", listOf("-c", "exit 42"), env, tempDir).exitCode)
    }

    @Test
    fun `runExecutable keeps stderr separate from stdout`() {
        val result = CommandRunner.runExecutable("sh", listOf("-c", "echo out; echo err >&2"), env, tempDir)
        assertEquals("out", result.stdout.trim())
        assertEquals("err", result.stderr.trim())
    }

    @Test
    fun `runExecutable reports a missing executable as exit -1 with an error message`() {
        val result =
            CommandRunner.runExecutable(
                File(tempDir, "no-such-binary").absolutePath,
                emptyList(),
                env,
                tempDir,
            )
        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.isNotBlank())
    }

    @Test
    fun `streamExecutable merges stderr into the stream`() {
        val lines = mutableListOf<String>()
        runBlocking {
            CommandRunner.streamExecutable("sh", listOf("-c", "echo out; echo err >&2"), env, tempDir) { lines.add(it) }
        }
        assertEquals(setOf("out", "err"), lines.toSet())
    }

    @Test
    fun `streamExecutable reports a missing executable through onOutput`() {
        val lines = mutableListOf<String>()
        runBlocking {
            CommandRunner.streamExecutable(File(tempDir, "nope").absolutePath, emptyList(), env, tempDir) {
                lines.add(it)
            }
        }
        assertEquals(1, lines.size)
        assertTrue(lines[0].startsWith("Error:"), lines[0])
    }
}
