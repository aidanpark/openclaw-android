package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * [CommandRunner.TIMEOUT_PREFIX] is how a caller tells "the time limit passed" from "the process
 * could not be started" (both are exit -1): the tool check reads the first as "unknown" and the
 * second as "failed". The constant must be exactly how a real timed-out result's stderr begins,
 * and a result that did not time out must not begin with it.
 */
internal class CommandRunnerTimeoutPrefixTest {
    @TempDir
    lateinit var tempDir: File

    // PREFIX="" makes the shell path "/bin/sh" — works on both macOS and Linux
    private val env = mapOf("PREFIX" to "", "PATH" to "/usr/bin:/bin")

    @Test
    fun `a runExecutable that passes its time limit returns -1 and a stderr starting with the prefix`() {
        val started = System.nanoTime()
        val result = CommandRunner.runExecutable("sleep", listOf("30"), env, tempDir, SHORT_MS)
        val tookMs = (System.nanoTime() - started) / NANOS_PER_MS
        assertEquals(-1, result.exitCode, result.toString())
        assertTrue(result.stderr.startsWith(CommandRunner.TIMEOUT_PREFIX), "stderr=${result.stderr}")
        assertEquals("${CommandRunner.TIMEOUT_PREFIX}${SHORT_MS}ms", result.stderr)
        assertTrue(tookMs < MAX_WAIT_MS, "the timeout did not stop the sleep: ${tookMs}ms")
    }

    @Test
    fun `a runSync that passes its time limit returns a stderr starting with the prefix`() {
        val result = CommandRunner.runSync("exec sleep 30", env, tempDir, SHORT_MS)
        assertEquals(-1, result.exitCode, result.toString())
        assertTrue(result.stderr.startsWith(CommandRunner.TIMEOUT_PREFIX), "stderr=${result.stderr}")
    }

    @Test
    fun `a program that cannot be started returns -1 without the prefix`() {
        val notExecutable = File(tempDir, "tool").apply { writeText("#!/bin/sh\nexit 0\n") }
        assertFalse(notExecutable.canExecute())
        // Looked up by name on the PATH, as the tool check does: the runner finds nothing it can start
        val result =
            CommandRunner.runExecutable("tool", emptyList(), env + ("PATH" to tempDir.path), tempDir, LONG_MS)
        assertEquals(-1, result.exitCode, result.toString())
        assertFalse(result.stderr.startsWith(CommandRunner.TIMEOUT_PREFIX), "stderr=${result.stderr}")
        val timedOut = result.stderr.startsWith(CommandRunner.TIMEOUT_PREFIX)
        assertEquals(ToolProbe.FAILED, ToolProbe.status(result.exitCode, timedOut))
    }

    @Test
    fun `a program that ends in time does not report the prefix even if it prints it`() {
        val result =
            CommandRunner.runSync("echo '${CommandRunner.TIMEOUT_PREFIX}' >&2; exit 3", env, tempDir, LONG_MS)
        assertEquals(3, result.exitCode)
        // the prefix alone does not make a timeout: the exit code is not -1
        assertEquals(ToolProbe.FAILED, ToolProbe.status(result.exitCode, true))
    }

    private companion object {
        const val SHORT_MS = 300L
        const val LONG_MS = 5_000L
        const val MAX_WAIT_MS = 5_000L
        const val NANOS_PER_MS = 1_000_000L
    }
}
