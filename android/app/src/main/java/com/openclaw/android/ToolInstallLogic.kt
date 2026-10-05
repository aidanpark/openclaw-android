package com.openclaw.android

import java.io.File

/**
 * What `post-setup.sh --tools-only` left in `~/.openclaw-android/tools-result.conf`.
 * [tools] maps a tool id to `ok` or `failed:<reason>`.
 */
internal data class ToolResultFile(
    val runEpoch: Long,
    val error: String?,
    val exit: Int?,
    val tools: Map<String, String>,
)

/**
 * Reads `tools-result.conf` as DATA: one `KEY=VALUE` per line, never executed, every limit
 * enforced here (the file is written by a script the app downloads, so it is not trusted to be
 * well-formed).
 */
internal object ToolResultParser {
    const val SCHEMA = "1"
    const val MAX_CHARS = 16 * 1024
    const val MAX_LINE = 200
    private val keyPattern = Regex("^[A-Za-z0-9_.-]{1,40}$")

    /** Null when the file cannot be trusted at all (wrong schema, no run time, duplicate key, too big). */
    fun parse(text: String): ToolResultFile? {
        val values = if (text.length > MAX_CHARS) null else readPairs(text)
        return values?.let { toResult(it) }
    }

    private fun readPairs(text: String): Map<String, String>? {
        val values = LinkedHashMap<String, String>()
        for (raw in text.split('\n')) {
            val pair = parseLine(raw) ?: continue
            if (values.put(pair.first, pair.second) != null) return null // a repeated key: do not guess
        }
        return values
    }

    private fun parseLine(raw: String): Pair<String, String>? {
        val line = raw.trimEnd('\r')
        val eq = line.indexOf('=')
        val valid =
            line.isNotEmpty() &&
                line.length <= MAX_LINE &&
                line.none { it.isISOControl() } &&
                eq > 0 &&
                keyPattern.matches(line.substring(0, eq))
        return if (valid) line.substring(0, eq) to line.substring(eq + 1) else null
    }

    private fun toResult(values: Map<String, String>): ToolResultFile? {
        val run = values["run"]?.toLongOrNull()?.takeIf { it >= 0 }
        val reserved = setOf("schema", "run", "error", "exit")
        return if (values["schema"] == SCHEMA && run != null) {
            ToolResultFile(
                runEpoch = run,
                error = values["error"],
                exit = values["exit"]?.toIntOrNull(),
                tools = values.filterKeys { it !in reserved },
            )
        } else {
            null
        }
    }
}

/** Why an install did not succeed. The page maps each to a translated message. */
internal enum class ToolFailure {
    SETUP_INCOMPLETE,
    SCRIPT_OUTDATED,
    NOT_RUN,
    BUSY,
    INTERRUPTED,
    INDEX_NETWORK,
    INDEX_VERIFY,
    INSTALL_FAILED,
    VERIFY_FAILED,
    FILE_MISMATCH,
    ENV,
    LOCK,
    UNKNOWN,
}

/** What is known about one finished run besides its result file. */
internal data class ToolRunFacts(
    val startedAtSec: Long,
    val id: String,
    val exitCode: Int,
    val installedOnDisk: Boolean,
    val outputHint: ToolFailure? = null,
)

internal sealed interface ToolVerdict {
    data object Success : ToolVerdict

    data class Failure(
        val reason: ToolFailure,
    ) : ToolVerdict
}

/**
 * Success is claimed only when the script's own result file says `ok` for THIS run AND the
 * tool's files are really there. The exit code alone is never believed: an older script that does
 * not know `--tools-only` exits 0 with "already complete".
 */
internal object ToolInstallVerdict {
    private const val EXIT_USAGE_OR_BUSY = 2

    fun decide(
        result: ToolResultFile?,
        facts: ToolRunFacts,
    ): ToolVerdict =
        if (result == null || !isThisRun(result, facts)) {
            // No file, one left by an earlier run, or one from another run: this run left no result of its own
            ToolVerdict.Failure(
                if (facts.exitCode == EXIT_USAGE_OR_BUSY) facts.outputHint ?: ToolFailure.BUSY else ToolFailure.NOT_RUN,
            )
        } else {
            decideFromResult(result, facts)
        }

    /** Written at or after this run started, and its exit status (if recorded) is this process's. */
    private fun isThisRun(
        result: ToolResultFile,
        facts: ToolRunFacts,
    ): Boolean = result.runEpoch >= facts.startedAtSec && (result.exit == null || result.exit == facts.exitCode)

    private fun decideFromResult(
        result: ToolResultFile,
        facts: ToolRunFacts,
    ): ToolVerdict {
        val outcome = result.tools[facts.id]
        val failure =
            when {
                result.error != null -> errorToFailure(result.error)
                outcome == null -> ToolFailure.NOT_RUN
                outcome == "ok" -> if (facts.installedOnDisk) null else ToolFailure.FILE_MISMATCH
                outcome == "failed:install" -> ToolFailure.INSTALL_FAILED
                outcome == "failed:verify" -> ToolFailure.VERIFY_FAILED
                else -> ToolFailure.UNKNOWN
            }
        return if (failure == null) ToolVerdict.Success else ToolVerdict.Failure(failure)
    }

    fun errorToFailure(error: String): ToolFailure =
        when {
            error == "interrupted" -> ToolFailure.INTERRUPTED
            error == "index-download" -> ToolFailure.INDEX_NETWORK
            error.startsWith("index-") -> ToolFailure.INDEX_VERIFY
            error == "env" -> ToolFailure.ENV
            error == "lock" -> ToolFailure.LOCK
            else -> ToolFailure.UNKNOWN
        }

    private val hintPattern = Regex("""\berror=(env|lock)\b""")

    /**
     * When no result file could be written the script says so inside a sentence on stderr, e.g.
     * `Cannot create <dir> (error=env).` / `Could not create the tools lock in <dir> (error=lock).`
     */
    fun hintFromOutput(line: String): ToolFailure? =
        when (hintPattern.find(line)?.groupValues?.get(1)) {
            "env" -> ToolFailure.ENV
            "lock" -> ToolFailure.LOCK
            else -> null
        }
}

/**
 * Does `post-setup.sh --tools-only --list` look like the new entry point? An older script prints
 * "already complete" (or, with no marker, starts a full install) — anything that is not a plain
 * list of ids means "old script": the real call must not be made.
 */
internal object ToolListCheck {
    private const val MAX_LINES = 50
    private const val MAX_CHARS = 2_000
    private val idPattern = Regex("^[a-z][a-z0-9-]{0,30}$")

    fun supports(
        output: String,
        exitCode: Int,
        id: String,
    ): Boolean {
        if (exitCode != 0 || output.length > MAX_CHARS) return false
        val lines = output.split('\n').map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
        if (lines.isEmpty() || lines.size > MAX_LINES) return false
        if (!lines.all { idPattern.matches(it) }) return false
        return id in lines
    }
}

/** Is a tool's program (or library) really on disk? One place, so the list, the check and the verdict agree. */
internal object ToolDetection {
    private val prefixBinaries =
        mapOf(
            "tmux" to "tmux",
            "ttyd" to "ttyd",
            "dufs" to "dufs",
            "android-tools" to "adb",
            "openssh-server" to "sshd",
        )

    /** npm tools: launcher name in any directory that is on the PATH. */
    private val launchers =
        mapOf(
            "code-server" to "code-server",
            "claude-code" to "claude",
            "gemini-cli" to "gemini",
            "codex-cli" to "codex",
            "opencode" to "opencode",
        )

    fun isInstalled(
        id: String,
        prefixDir: File,
        homeDir: File,
    ): Boolean {
        val binDirs =
            listOf(
                File(prefixDir, "bin"),
                File(homeDir, ".local/bin"),
                File(homeDir, ".openclaw-android/node/bin"),
            )
        return when (id) {
            "playwright" -> File(prefixDir, "lib/node_modules/playwright-core/package.json").isFile
            "chromium" -> listOf("chromium-browser", "chromium").any { File(prefixDir, "bin/$it").exists() }
            in prefixBinaries -> File(prefixDir, "bin/${prefixBinaries.getValue(id)}").exists()
            in launchers -> binDirs.any { File(it, launchers.getValue(id)).exists() }
            else -> false
        }
    }
}

/** Ends that leave the tool's files in place although the tool does not work: it must not read as "installed". */
internal val brokenFailures = setOf(ToolFailure.VERIFY_FAILED, ToolFailure.INSTALL_FAILED, ToolFailure.FILE_MISMATCH)

/**
 * Remembers, across app restarts, which tools last ended in a [brokenFailures] outcome — the
 * guard's state lives only as long as the process. A success clears the tool; any other end
 * (cancelled, busy, offline) leaves the record as it was. One `id=REASON` line per tool.
 */
internal class ToolOutcomeStore(
    private val file: java.io.File,
) {
    private val idPattern = Regex("^[a-z][a-z0-9-]{0,30}$")

    fun load(): Map<String, ToolFailure> = read() ?: emptyMap()

    /** Null when the record could not be read (an I/O error): then it must not be rewritten from nothing. */
    private fun read(): Map<String, ToolFailure>? =
        try {
            if (!file.isFile || file.length() > MAX_BYTES) {
                emptyMap()
            } else {
                file
                    .readLines()
                    .mapNotNull { line ->
                        val (id, name) = line.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                        val reason = brokenFailures.firstOrNull { it.name == name }
                        if (reason != null && idPattern.matches(id)) id to reason else null
                    }.toMap()
            }
        } catch (_: java.io.IOException) {
            null
        }

    /** Drops records of tools that are no longer on disk (a re-installed system must not inherit them). */
    @Synchronized
    fun prune(onDisk: Set<String>) {
        val current = read() ?: return
        if (current.keys.all { it in onDisk }) return
        write(current.filterKeys { it in onDisk })
    }

    @Synchronized
    fun record(
        id: String,
        verdict: ToolVerdict,
    ) {
        val current = (read() ?: return).toMutableMap()
        when {
            verdict is ToolVerdict.Success -> current.remove(id)
            verdict is ToolVerdict.Failure && verdict.reason in brokenFailures -> current[id] = verdict.reason
            else -> return
        }
        write(current)
    }

    private fun write(current: Map<String, ToolFailure>) {
        try {
            file.parentFile?.mkdirs()
            val tmp = java.io.File(file.path + ".tmp")
            tmp.writeText(current.entries.joinToString("\n") { "${it.key}=${it.value.name}" })
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (_: java.io.IOException) {
            // The record is a convenience for the display; losing it never changes what is installed
        }
    }

    private companion object {
        const val MAX_BYTES = 4_096L
    }
}

/** Turns the exit code of a tool's `--version` check into a verdict the page can show. */
internal object ToolProbe {
    const val OK = "ok"
    const val FAILED = "failed"
    const val UNKNOWN = "unknown"
    private const val LAST_PLAIN_EXIT = 127
    private const val LAUNCH_FAILED = -1

    /**
     * 0 = it runs. 1..127 = it ran or tried to and failed (126/127: not executable / not found,
     * e.g. a link error). Anything else — timeout or launch failure (-1), killed by a signal (>=128)
     * — says nothing about the tool: "unknown", never a false "broken".
     */
    fun status(
        exitCode: Int,
        timedOut: Boolean = false,
    ): String =
        when {
            exitCode == 0 -> OK
            exitCode in 1..LAST_PLAIN_EXIT -> FAILED
            // The files are on disk yet the process could not even be started (no execute
            // permission, a broken link at exec): the tool does not run. A timeout says nothing.
            exitCode == LAUNCH_FAILED && !timedOut -> FAILED
            else -> UNKNOWN
        }
}
