package com.openclaw.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Shell command execution via ProcessBuilder (§2.2.5).
 * Uses Termux bootstrap environment for all commands.
 */
object CommandRunner {
    data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    /**
     * Run a fixed executable with explicit arguments — no shell parsing, so a value that came
     * from the WebView can never be interpreted as a command. Use this for anything that is not a
     * native-owned command string.
     */
    fun runExecutable(
        executable: String,
        args: List<String>,
        env: Map<String, String>,
        workDir: File,
        timeoutMs: Long = 5_000,
    ): CommandResult =
        try {
            runProcess(executableBuilder(executable, args, env, workDir, mergeErrors = false), timeoutMs)
        } catch (e: Exception) {
            CommandResult(-1, "", e.message ?: "Unknown error")
        }

    /**
     * Run a command string through the shell, synchronously with timeout.
     * Only for native-owned fixed command strings — WebView-provided input goes through
     * [runExecutable].
     */
    fun runSync(
        command: String,
        env: Map<String, String>,
        workDir: File,
        timeoutMs: Long = 5_000,
    ): CommandResult =
        try {
            runProcess(shellBuilder(command, env, workDir, mergeErrors = false), timeoutMs)
        } catch (e: Exception) {
            CommandResult(-1, "", e.message ?: "Unknown error")
        }

    /**
     * Run a native-owned shell command string asynchronously, streaming output line-by-line.
     */
    suspend fun runStreaming(
        command: String,
        env: Map<String, String>,
        workDir: File,
        onOutput: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        try {
            stream(shellBuilder(command, env, workDir, mergeErrors = true), onOutput)
        } catch (e: Exception) {
            onOutput("Error: ${e.message}")
        }
    }

    /**
     * Run a fixed executable asynchronously (no shell), streaming output line-by-line. Probes
     * are short, so the whole run is bounded by [STREAM_EXECUTABLE_TIMEOUT_MS].
     */
    suspend fun streamExecutable(
        executable: String,
        args: List<String>,
        env: Map<String, String>,
        workDir: File,
        onOutput: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        try {
            stream(
                executableBuilder(executable, args, env, workDir, mergeErrors = true),
                onOutput,
                STREAM_EXECUTABLE_TIMEOUT_MS,
            )
        } catch (e: Exception) {
            onOutput("Error: ${e.message}")
        }
    }

    private fun shellBuilder(
        command: String,
        env: Map<String, String>,
        workDir: File,
        mergeErrors: Boolean,
    ): ProcessBuilder = configure(ProcessBuilder(shellPath(env), "-c", command), env, workDir, mergeErrors)

    /**
     * Run [executable] with [args] through the shell's `exec "$0" "$@"`. The arguments are
     * positional parameters, so nothing is parsed; going through the Termux shell (which has the
     * exec hook) is what lets scripts with a `#!/usr/bin/env` shebang start — the app's own JVM
     * process cannot execve those directly.
     */
    private fun executableBuilder(
        executable: String,
        args: List<String>,
        env: Map<String, String>,
        workDir: File,
        mergeErrors: Boolean,
    ): ProcessBuilder =
        configure(
            ProcessBuilder(
                listOf(shellPath(env), "-c", "exec \"\$0\" \"\$@\"", resolveExecutable(executable, env)) + args,
            ),
            env,
            workDir,
            mergeErrors,
        )

    private fun configure(
        pb: ProcessBuilder,
        env: Map<String, String>,
        workDir: File,
        mergeErrors: Boolean,
    ): ProcessBuilder {
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(workDir)
        pb.redirectErrorStream(mergeErrors)
        return pb
    }

    /**
     * Drain both pipes on their own threads so a hung process cannot defeat the timeout.
     * The reader threads must never throw: destroying a hung process closes the pipes they are
     * blocked on, and on Android that surfaces as an IOException in the reader — an uncaught
     * exception in any thread kills the whole app.
     */
    private fun runProcess(
        pb: ProcessBuilder,
        timeoutMs: Long,
    ): CommandResult {
        val process = pb.start()
        closeStdin(process)
        val stdout = StringBuffer()
        val stderr = StringBuffer()
        val outReader = Thread { drainQuietly(process.inputStream, stdout) }
        val errReader = Thread { drainQuietly(process.errorStream, stderr) }
        outReader.start()
        errReader.start()
        val exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!exited) {
            process.destroyForcibly()
            outReader.join(READER_JOIN_MS)
            errReader.join(READER_JOIN_MS)
            return CommandResult(-1, stdout.toString(), "Command timed out after ${timeoutMs}ms")
        }
        // A grandchild can keep a pipe open after the child exits — never wait on it unbounded
        outReader.join(READER_JOIN_MS)
        errReader.join(READER_JOIN_MS)
        return CommandResult(process.exitValue(), stdout.toString(), stderr.toString())
    }

    /** Copy [input] into [sink] until EOF or until the pipe is closed under us; never throws I/O errors. */
    internal fun drainQuietly(
        input: java.io.InputStream,
        sink: StringBuffer,
    ) {
        val reader = input.bufferedReader()
        val buffer = CharArray(DRAIN_BUFFER_CHARS)
        try {
            var read = reader.read(buffer)
            while (read >= 0) {
                sink.append(buffer, 0, read)
                read = reader.read(buffer)
            }
        } catch (_: Exception) {
            // Pipe closed by destroy()/process exit mid-read — keep whatever was read so far.
            // Deliberately broad: nothing thrown on this thread may reach the app's crash handler.
        }
    }

    private fun stream(
        pb: ProcessBuilder,
        onOutput: (String) -> Unit,
        timeoutMs: Long? = null,
    ) {
        val process = pb.start()
        closeStdin(process)
        val watchdog = timeoutMs?.let { Executors.newSingleThreadScheduledExecutor() }
        watchdog?.schedule({ process.destroyForcibly() }, timeoutMs, TimeUnit.MILLISECONDS)
        try {
            process.inputStream.bufferedReader().forEachLine { line -> onOutput(line) }
            process.waitFor()
        } finally {
            watchdog?.shutdownNow()
            // An error or cancellation must not leave the child running
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /** Nothing here ever writes to a child: close stdin so commands that read it see EOF, not a hang. */
    private fun closeStdin(process: Process) {
        try {
            process.outputStream.close()
        } catch (_: java.io.IOException) {
            // Already closed — nothing to do
        }
    }

    /** The Termux shell when present, else the system one (also keeps JVM unit tests portable). */
    private fun shellPath(env: Map<String, String>): String {
        val prefixShell = env["PREFIX"]?.let { "$it/bin/sh" }
        return listOfNotNull(prefixShell, "/system/bin/sh", "/bin/sh").firstOrNull { File(it).exists() }
            ?: prefixShell
            ?: "/system/bin/sh"
    }

    /** Find [executable] on the PATH we were given — never on the app process's own PATH. */
    private fun resolveExecutable(
        executable: String,
        env: Map<String, String>,
    ): String {
        val found =
            if (executable.contains(File.separatorChar)) {
                File(executable).takeIf { it.exists() }
            } else {
                env["PATH"]
                    ?.split(File.pathSeparator)
                    ?.asSequence()
                    ?.map { File(it, executable) }
                    ?.firstOrNull { it.exists() && it.canExecute() }
            }
        return found?.path ?: throw java.io.IOException("Executable not found: $executable")
    }

    private const val READER_JOIN_MS = 2_000L
    private const val DRAIN_BUFFER_CHARS = 4096
    private const val STREAM_EXECUTABLE_TIMEOUT_MS = 30_000L
}
