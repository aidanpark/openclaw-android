package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CommandRunnerTest {
    @TempDir
    lateinit var tempDir: File

    // PREFIX="" makes shell path "/bin/sh" — works on both macOS and Linux
    private val env = mapOf("PREFIX" to "", "PATH" to "/usr/bin:/bin")

    @Test
    fun `runSync returns stdout for echo command`() {
        val result = CommandRunner.runSync("echo hello", env, tempDir)
        assertEquals(0, result.exitCode)
        assertEquals("hello", result.stdout.trim())
    }

    @Test
    fun `runExecutable does not interpret shell metacharacters`() {
        val marker = File(tempDir, "marker")
        val result =
            CommandRunner.runExecutable(
                "printf",
                listOf("hello; touch ${marker.absolutePath}"),
                env,
                tempDir,
            )

        assertEquals(0, result.exitCode)
        assertEquals("hello; touch ${marker.absolutePath}", result.stdout)
        assertTrue(!marker.exists())
    }

    @Test
    fun `runSync returns non-zero exit code for failing command`() {
        val result = CommandRunner.runSync("exit 42", env, tempDir)
        assertEquals(42, result.exitCode)
    }

    @Test
    fun `runSync captures stderr`() {
        val result = CommandRunner.runSync("echo error >&2", env, tempDir)
        assertEquals(0, result.exitCode)
        assertEquals("error", result.stderr.trim())
    }

    @Test
    fun `runSync handles invalid command`() {
        val result = CommandRunner.runSync("nonexistent_command_xyz", env, tempDir)
        assertTrue(result.exitCode != 0)
    }

    @Test
    fun `CommandResult data class holds values correctly`() {
        val result = CommandRunner.CommandResult(0, "out", "err")
        assertEquals(0, result.exitCode)
        assertEquals("out", result.stdout)
        assertEquals("err", result.stderr)
    }
}
