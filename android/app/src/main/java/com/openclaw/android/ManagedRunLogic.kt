package com.openclaw.android

import java.io.File

/** Output lines as the page and the parsers see them. */
internal object RunText {
    private val ansiPattern = Regex("\u001B\\[[0-9;?]*[A-Za-z]")

    /** ANSI colors removed; of a line redrawn with `\r` (curl's progress) only its last piece is kept. */
    fun clean(raw: String): String {
        val plain = ansiPattern.replace(raw, "")
        if ('\r' !in plain) return plain
        return plain.split('\r').lastOrNull { it.isNotBlank() } ?: ""
    }
}

/** `[N/5] <title>` lines of `update-core.sh` (its `step()`, `update-core.sh:24-28`). */
internal object UpdateStageParser {
    val pattern = Regex("""^\[([1-5])/5\] """)

    /** The stage a line starts, or null. ANSI colors are removed first. */
    fun stage(
        line: String,
        stagePattern: Regex = pattern,
    ): Int? =
        stagePattern
            .find(RunText.clean(line))
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
}

/**
 * Keeps the LAST `[FAIL]` line of a run and the indented lines that explain it (the scripts print
 * the reason on `[FAIL] …` and the advice on lines indented below it). The last one is the cause:
 * the scripts stop right after a fatal `[FAIL]`, while earlier ones belong to steps they went on
 * without (the pre-update backup, a git repair). Each new `[FAIL]` line drops the block before it.
 * The marker itself is dropped: the first entry is the sentence after `[FAIL]`. At most
 * [MAX_FOLLOW] explaining lines, each cut to [MAX_CHARS].
 *
 * One exception: the Node.js gate (`Node.js v… is required (found: …)`) always follows a failed
 * Node.js step as its consequence — the step's own `[FAIL]` (download, checksum, cached updater)
 * is the cause, so a gate block does not replace a block already kept.
 */
internal class FailLineCollector {
    private val lines = mutableListOf<String>()
    private var collecting = false

    fun accept(raw: String) {
        val line = RunText.clean(raw)
        val text = line.trim()
        if (text.startsWith(MARKER)) {
            val sentence = text.removePrefix(MARKER).trim()
            if (lines.isNotEmpty() && GATE_PHRASE in sentence) {
                collecting = false
                return
            }
            lines.clear()
            lines += sentence.take(MAX_CHARS)
            collecting = true
            return
        }
        if (!collecting) return
        if (line.isNotBlank() && line.first().isWhitespace() && lines.size <= MAX_FOLLOW) {
            lines += line.trim().take(MAX_CHARS)
        } else {
            collecting = false
        }
        if (lines.size > MAX_FOLLOW) collecting = false
    }

    /** The last `[FAIL]` block; empty when the run printed no `[FAIL]` line. */
    fun lines(): List<String> = lines.toList()

    companion object {
        const val MARKER = "[FAIL]"

        /** `update-core.sh:398` and `platforms/openclaw/update.sh:31`: "Node.js v<pin> is required (found: <x>)". */
        const val GATE_PHRASE = "is required (found: "
        const val MAX_FOLLOW = 6
        const val MAX_CHARS = 200
    }
}

/**
 * What one run printed that the verdict needs: stage, the last failure, warnings (and the last
 * warning's sentence), the end banner.
 */
internal class RunOutputWatcher(
    private val spec: RunSpec,
) {
    private val fails = FailLineCollector()
    private var lastWarning = ""
    private var lastAdvice = ""

    var stage = 0
        private set
    var warnings = 0
        private set
    var sawComplete = false
        private set

    /** Takes one raw output line and returns it cleaned (no ANSI, last `\r` piece). */
    fun accept(raw: String): String {
        val line = RunText.clean(raw)
        UpdateStageParser.stage(line, spec.stagePattern)?.let { stage = maxOf(stage, it) }
        fails.accept(line)
        val text = line.trim()
        if (text.startsWith(UpdateOutput.WARN_MARKER)) {
            warnings++
            val sentence = text.removePrefix(UpdateOutput.WARN_MARKER).trim()
            // A bare marker keeps the sentence before it
            if (sentence.isNotEmpty()) {
                lastWarning = sentence.take(UpdateOutput.MAX_WARNING_CHARS)
                // A warning that tells the user what to do must not be hidden by a noisy one printed after it
                if (UpdateOutput.ADVICE_PATTERN.containsMatchIn(sentence)) lastAdvice = lastWarning
            }
        }
        if (text == UpdateOutput.COMPLETE_LINE) sawComplete = true
        return line
    }

    fun failLines(): List<String> = fails.lines()

    /**
     * The warning to show (marker removed, at most [UpdateOutput.MAX_WARNING_CHARS]); empty when the
     * run printed none. The last warning that carries advice ([UpdateOutput.ADVICE_PATTERN]: "Stop the
     * gateway, then run: …") wins even when ordinary warnings ("… update failed (non-critical)")
     * follow it; without any advice it is simply the last warning.
     */
    fun lastWarning(): String = lastAdvice.ifEmpty { lastWarning }
}

/**
 * Every piece of `oa --update` output the app depends on when there is no result file (the
 * fallback). Changing any of these in the scripts changes what the app shows: RUNTIME/CLAW were
 * asked to report such changes (R3).
 */
internal object UpdateOutput {
    /** `echo -e "${YELLOW}[WARN]${NC} …"` — counted as warnings (update-core.sh, platforms/openclaw/update.sh). */
    const val WARN_MARKER = "[WARN]"

    /**
     * The last `[WARN]` sentence of a successful run is shown as printed (e.g. platforms/openclaw/update.sh,
     * v1.2.2: "The gateway is using the OpenClaw state, so the data check was skipped. Stop the gateway,
     * then run: openclaw doctor") — cut to this many characters.
     */
    const val MAX_WARNING_CHARS = 200

    /**
     * A warning that tells the user to act: `update.sh:231` ("Stop the gateway, then run: openclaw
     * doctor"), `update.sh:366` ("… check manually"). The ordinary ones say "… failed (non-critical)" or
     * "Could not check …" and ask for nothing.
     */
    val ADVICE_PATTERN = Regex("""Stop the |\brun:|check manually""", RegexOption.IGNORE_CASE)

    /** `update-core.sh:550` — printed only after every stage ran. */
    const val COMPLETE_LINE = "Update Complete!"

    /**
     * Sentence (after `[FAIL]`) → reason, first match wins. Source of each sentence on the right.
     * Sentences not listed (e.g. "Failed to create temp directory") are UNKNOWN.
     */
    val failPhrases: List<Pair<String, UpdateReason>> =
        listOf(
            // update-core.sh:207
            "The OpenClaw gateway is running." to UpdateReason.GATEWAY_RUNNING,
            // scripts/lib.sh:172 (oa_check_free_space: update-core.sh:196, platforms/openclaw/update.sh:79)
            "Not enough free storage to " to UpdateReason.NO_SPACE,
            // platforms/openclaw/update.sh:70, scripts/install-nodejs.sh:189
            "The updater downloaded an older copy of itself (cache)." to UpdateReason.CACHE_STALE,
            // scripts/install-nodejs.sh:41
            "The updater is out of date (a cached copy was downloaded)." to UpdateReason.CACHE_STALE,
            // update-core.sh:169 ("This usually means the download was an older cached copy")
            "but this update pins Node.js" to UpdateReason.CACHE_STALE,
            // platforms/openclaw/update.sh:54 ("an old cached copy of the scripts was used")
            "is newer than the pinned" to UpdateReason.CACHE_STALE,
            // update-core.sh:252
            "Your OpenClaw has saved chat history, and " to UpdateReason.SESSION_GUARD,
            // oa.sh:100, update-core.sh:120, scripts/install-nodejs.sh:562, scripts/install-nodejs.sh:570
            "Failed to download " to UpdateReason.DOWNLOAD,
            // update-core.sh:134
            "Missing required file: " to UpdateReason.DOWNLOAD,
            // update-core.sh:148
            "Version pin missing in platforms/" to UpdateReason.DOWNLOAD,
            // scripts/install-nodejs.sh:46
            "Invalid Node.js version pin" to UpdateReason.DOWNLOAD,
            // platforms/openclaw/update.sh:21
            "Invalid OpenClaw version pin" to UpdateReason.DOWNLOAD,
            // scripts/install-nodejs.sh:576
            "Checksum mismatch for " to UpdateReason.CHECKSUM,
            // platforms/openclaw/update.sh:99
            "Could not install openclaw " to UpdateReason.INSTALL_FAILED,
            // scripts/install-nodejs.sh:588
            "Failed to extract Node.js" to UpdateReason.INSTALL_FAILED,
            // scripts/install-nodejs.sh:599
            "Extracted Node.js does not run" to UpdateReason.INSTALL_FAILED,
            // scripts/install-nodejs.sh:647
            "Node.js verification failed" to UpdateReason.INSTALL_FAILED,
            // update-core.sh:398, platforms/openclaw/update.sh:31 (the Node.js gate)
            "is required (found: " to UpdateReason.INSTALL_FAILED,
            // platforms/openclaw/update.sh:218
            "the patch that moves your chat history is not in place" to UpdateReason.MIGRATION_FAILED,
            // platforms/openclaw/update.sh:246, :248, :250
            "The data migration " to UpdateReason.MIGRATION_FAILED,
            // platforms/openclaw/update.sh:261
            "but it cannot use your existing data yet" to UpdateReason.HEALTH_FAILED,
            // update-core.sh:81, :85
            "No platform detected" to UpdateReason.NOT_INSTALLED,
            // update-core.sh:33, scripts/install-nodejs.sh:68
            "Not running in Termux" to UpdateReason.NOT_INSTALLED,
            // oa.sh:87, update-core.sh:39
            "curl not found." to UpdateReason.NOT_INSTALLED,
        )
}

/** Maps what the scripts say to an [UpdateReason]. */
internal object UpdateReasons {
    private val codePattern = Regex("^[a-z0-9_-]{1,40}$")

    /**
     * `reason=` codes of `update-result.conf` (R3, sent to RUNTIME 2026-10-06), plus `busy`
     * (the R5 lock). An unknown or malformed code is UNKNOWN — still a failure.
     */
    private val codes: Map<String, UpdateReason> =
        mapOf(
            "no_curl" to UpdateReason.NOT_INSTALLED,
            "no_prefix" to UpdateReason.NOT_INSTALLED,
            "no_platform" to UpdateReason.NOT_INSTALLED,
            "download_failed" to UpdateReason.DOWNLOAD,
            "missing_file" to UpdateReason.DOWNLOAD,
            "pin_missing" to UpdateReason.DOWNLOAD,
            "node_download" to UpdateReason.DOWNLOAD,
            "no_space" to UpdateReason.NO_SPACE,
            "gateway_running" to UpdateReason.GATEWAY_RUNNING,
            "session_guard" to UpdateReason.SESSION_GUARD,
            "checksum" to UpdateReason.CHECKSUM,
            "node_gate" to UpdateReason.INSTALL_FAILED,
            "npm_install" to UpdateReason.INSTALL_FAILED,
            "patch_missing" to UpdateReason.MIGRATION_FAILED,
            "migration_failed" to UpdateReason.MIGRATION_FAILED,
            "migration_timeout" to UpdateReason.MIGRATION_FAILED,
            "health_failed" to UpdateReason.HEALTH_FAILED,
            // The scripts write `interrupted` for any signal; ManagedRunGuard.settle makes it CANCELLED
            // when the app asked for the cancel. `cancelled` is not written by the scripts today.
            "interrupted" to UpdateReason.INTERRUPTED,
            "cancelled" to UpdateReason.CANCELLED,
            "busy" to UpdateReason.BUSY,
        )

    /** `cache_old_updater`, `cache_node_downgrade`, `cache_openclaw_downgrade` and any later `cache_*`. */
    private const val CACHE_PREFIX = "cache_"

    fun fromCode(code: String): UpdateReason =
        when {
            !codePattern.matches(code) -> UpdateReason.UNKNOWN
            code.startsWith(CACHE_PREFIX) -> UpdateReason.CACHE_STALE
            else -> codes[code] ?: UpdateReason.UNKNOWN
        }

    /** [sentence] is the text after `[FAIL]` ([FailLineCollector.lines] first entry). */
    fun fromFailLine(sentence: String): UpdateReason =
        UpdateOutput.failPhrases.firstOrNull { sentence.contains(it.first) }?.second ?: UpdateReason.UNKNOWN
}

/** What `oa --update` left in `~/.openclaw-android/update-result.conf` (R3). */
internal data class RunResultFile(
    val runEpoch: Long,
    val phase: String?,
    val reason: String?,
    val exit: Int?,
    val changed: Boolean?,
    val healthy: Boolean?,
    val backup: String?,
)

/**
 * Reads `update-result.conf` as DATA with exactly the limits of [ToolResultParser] (16K, 200
 * characters a line, a repeated key rejects the file, `schema=1`, numeric `run`) — it is parsed by
 * it. `reason=` is the failure code; `error=` (the tool-mode spelling, e.g. the R5 lock's
 * `error=busy`) is read as one when there is no `reason=`.
 */
internal object RunResultParser {
    const val MAX_CHARS = ToolResultParser.MAX_CHARS

    fun parse(text: String): RunResultFile? {
        val base = ToolResultParser.parse(text) ?: return null
        val values = base.tools
        return RunResultFile(
            runEpoch = base.runEpoch,
            phase = values["phase"],
            reason = (values["reason"] ?: base.error)?.takeIf { it.isNotEmpty() },
            exit = base.exit,
            changed = flag(values["changed"]),
            healthy = flag(values["healthy"]),
            backup = values["backup"],
        )
    }

    private fun flag(value: String?): Boolean? =
        when (value) {
            "true" -> true
            "false" -> false
            else -> null
        }
}

/**
 * Success needs exit code 0 in every case. The script's own result file for THIS run wins; without
 * one (an older script, or one without `exit` yet) the output decides, conservatively: exit 0 is
 * success only when the end banner was seen (a `[FAIL]` before it belongs to a step the updater
 * went on without and counts as a warning) — otherwise "could not tell" (UNKNOWN), never success.
 * Exit 2 is the shared run lock's "another run holds it" (BUSY).
 *
 * [lastWarning] ([RunOutputWatcher.lastWarning]) never changes the verdict — a run with warnings
 * is still a success. It only becomes the success's `detail` when there is at least one warning.
 */
internal object UpdateVerdict {
    /** 128 + signal number: the process was killed, not refused. */
    private val signalExits = 129..192

    private const val BUSY_EXIT = 2

    @Suppress("LongParameterList") // the inputs of one decision, named so a call reads as a table row
    fun decide(
        result: RunResultFile?,
        exitCode: Int,
        failLines: List<String>,
        startedAtSec: Long,
        sawComplete: Boolean,
        warnings: Int,
        lastWarning: String = "",
    ): RunVerdict {
        val own = result?.takeIf { isThisRun(it, exitCode, startedAtSec) }
        val reason =
            if (own != null) {
                fromResult(own, exitCode, failLines)
            } else {
                fromOutput(exitCode, failLines, sawComplete)
            }
        // A step that failed and was skipped is one more thing the user should hear about
        val skipped = if (reason == null && own == null && failLines.isNotEmpty()) 1 else 0
        return if (reason == null) {
            // The script's own advice (e.g. "Stop the gateway, then run: openclaw doctor") is shown as printed
            val detail = if (warnings > 0) lastWarning else ""
            RunVerdict.Success(exitCode, warnings + skipped, detail)
        } else {
            RunVerdict.Failure(reason, exitCode, failLines.joinToString("\n"), warnings)
        }
    }

    /**
     * Written at or after this run started and FINISHED with this process's exit status. A file
     * without `exit` is a run still going (or killed before its trap ran): it proves nothing yet.
     */
    private fun isThisRun(
        result: RunResultFile,
        exitCode: Int,
        startedAtSec: Long,
    ): Boolean = result.runEpoch >= startedAtSec && result.exit != null && result.exit == exitCode

    /** Null = success. */
    private fun fromResult(
        result: RunResultFile,
        exitCode: Int,
        failLines: List<String>,
    ): UpdateReason? =
        when {
            result.reason != null -> UpdateReasons.fromCode(result.reason)
            result.healthy == false -> UpdateReason.HEALTH_FAILED
            exitCode == 0 -> null
            else -> classify(exitCode, failLines)
        }

    /** Null = success. */
    private fun fromOutput(
        exitCode: Int,
        failLines: List<String>,
        sawComplete: Boolean,
    ): UpdateReason? =
        when {
            // 2 is the shared run lock's "another run holds it" (the script says so and writes no file)
            exitCode == BUSY_EXIT -> UpdateReason.BUSY
            exitCode != 0 -> classify(exitCode, failLines)
            // The banner is printed only at the very end of a run that did not stop (a `[FAIL]` line
            // before it belongs to a step the updater went on without, e.g. the pre-update backup)
            sawComplete -> null
            else -> UpdateReason.UNKNOWN
        }

    private fun classify(
        exitCode: Int,
        failLines: List<String>,
    ): UpdateReason =
        when {
            // Killed by a signal (the cancel, or the system): that is the end, whatever was printed before
            exitCode in signalExits -> UpdateReason.INTERRUPTED
            failLines.isNotEmpty() -> UpdateReasons.fromFailLine(failLines.first())
            else -> UpdateReason.UNKNOWN
        }
}

/** The last end of one kind of managed run, as kept across app restarts. */
internal data class RunOutcome(
    val kind: String,
    val atSec: Long,
    val success: Boolean,
    val reason: UpdateReason?,
    val exit: Int?,
    val warnings: Int,
    val detail: String,
)

/**
 * Remembers, across app restarts, how the last run of each kind ended — the guard's state lives
 * only as long as the process. One `KIND|at|verdict|reason|exit|warnings|detail` line per kind in
 * `filesDir/last-run.conf`. Same safety as [ToolOutcomeStore]: the file is replaced atomically, a
 * read error never leads to a rewrite from nothing, and a line that does not validate is ignored.
 */
internal class RunOutcomeStore(
    private val file: File,
) {
    fun load(): Map<String, RunOutcome> = read() ?: emptyMap()

    /** Null when the record could not be read (an I/O error): then it must not be rewritten. */
    private fun read(): Map<String, RunOutcome>? =
        try {
            if (!file.isFile || file.length() > MAX_BYTES) {
                emptyMap()
            } else {
                file.readLines().mapNotNull(::parseLine).associateBy { it.kind }
            }
        } catch (_: java.io.IOException) {
            null
        }

    @Synchronized
    fun record(
        kind: String,
        atSec: Long,
        verdict: RunVerdict,
    ) {
        if (RunKinds.spec(kind) == null) return
        val current = (read() ?: return).toMutableMap()
        current[kind] =
            RunOutcome(
                kind = kind,
                atSec = atSec,
                success = verdict is RunVerdict.Success,
                reason = (verdict as? RunVerdict.Failure)?.reason,
                exit = verdict.exit,
                warnings = verdict.warnings,
                // A failure's last `[FAIL]` block, or a success's last `[WARN]` sentence
                detail = singleLine(verdict.detail),
            )
        write(current.values)
    }

    private fun write(outcomes: Collection<RunOutcome>) {
        val text = outcomes.joinToString("\n") { format(it) }
        if (text.toByteArray().size > MAX_BYTES) return
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (_: java.io.IOException) {
            // The record is a convenience for the display; losing it never changes what is installed
        }
    }

    private fun format(o: RunOutcome): String =
        listOf(
            o.kind,
            o.atSec.toString(),
            if (o.success) SUCCESS else FAILURE,
            o.reason?.name.orEmpty(),
            o.exit?.toString().orEmpty(),
            o.warnings.toString(),
            o.detail,
        ).joinToString("|")

    private fun parseLine(line: String): RunOutcome? {
        val fields = line.split('|', limit = FIELDS)
        if (fields.size != FIELDS) return null
        val verdict = fields[VERDICT_FIELD]
        val reasonName = fields[REASON_FIELD]
        val exitText = fields[EXIT_FIELD]
        val exit = exitText.toIntOrNull()
        val outcome =
            RunOutcome(
                kind = fields[KIND_FIELD],
                atSec = fields[AT_FIELD].toLongOrNull() ?: -1,
                success = verdict == SUCCESS,
                reason = UpdateReason.entries.firstOrNull { it.name == reasonName },
                exit = exit,
                warnings = fields[WARNINGS_FIELD].toIntOrNull() ?: -1,
                detail = fields[DETAIL_FIELD],
            )
        val shapeOk = (verdict == SUCCESS || verdict == FAILURE) && (exitText.isEmpty() || exit != null)
        return outcome.takeIf { shapeOk && isValid(it, reasonName) }
    }

    /** A known kind, sane numbers, a success with exit 0 and no reason or a failure with a known one. */
    private fun isValid(
        o: RunOutcome,
        reasonName: String,
    ): Boolean {
        val consistent = if (o.success) reasonName.isEmpty() && o.exit == 0 else o.reason != null
        return RunKinds.spec(o.kind) != null &&
            consistent &&
            o.atSec >= 0 &&
            o.warnings >= 0 &&
            o.detail.length <= MAX_DETAIL &&
            o.detail.none { it.isISOControl() }
    }

    /** Lines joined with " / ", other control characters blanked, cut to [MAX_DETAIL]. */
    private fun singleLine(detail: String): String =
        detail
            .replace("\n", " / ")
            .map { if (it.isISOControl()) ' ' else it }
            .joinToString("")
            .take(MAX_DETAIL)

    companion object {
        const val SUCCESS = "success"
        const val FAILURE = "failure"
        const val MAX_DETAIL = 200
        private const val MAX_BYTES = 4_096L
        private const val FIELDS = 7
        private const val KIND_FIELD = 0
        private const val AT_FIELD = 1
        private const val VERDICT_FIELD = 2
        private const val REASON_FIELD = 3
        private const val EXIT_FIELD = 4
        private const val WARNINGS_FIELD = 5
        private const val DETAIL_FIELD = 6
    }
}
