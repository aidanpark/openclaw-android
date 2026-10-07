package com.openclaw.android

import java.io.File
import java.io.InputStream

/**
 * What a SETUP run's result file says besides its verdict: the storage numbers of `error=free-space`
 * (`need_mb`/`have_mb`) and the non-fatal problems of a finished install (`warn=`, e.g. `tools:tmux`,
 * `clawdhub`, `oa-cli`, `backup-scripts`, `compat-js`, `hardlink-patch`, `checkOnStart`).
 */
internal data class SetupFacts(
    val needMb: Int? = null,
    val haveMb: Int? = null,
    val warn: List<String> = emptyList(),
)

/** What `post-setup.sh` (full mode) left in `~/.openclaw-android/post-setup-result.conf`. */
internal data class SetupResultFile(
    val runEpoch: Long,
    /** `1`..`7` (the last stage started) or `done`; null when missing or malformed. */
    val stage: String?,
    val error: String?,
    /** Missing while the run goes on (or after it was killed before its EXIT trap ran). */
    val exit: Int?,
    val facts: SetupFacts,
)

/**
 * Reads `post-setup-result.conf` as DATA with exactly the limits of [ToolResultParser] (16K, 200
 * characters a line, a repeated key rejects the file, `schema=1`, numeric `run`) — it is parsed by
 * it. Writer: `oa_full_result` in `post-setup.sh` (keys schema, run, stage, error, need_mb, have_mb,
 * warn, exit).
 */
internal object SetupResultParser {
    const val MAX_CHARS = ToolResultParser.MAX_CHARS

    /** At most this many `warn=` entries are kept. */
    const val MAX_WARN = 20

    private val stagePattern = Regex("^([1-7]|${ManagedSetup.DONE_STAGE})$")

    /** `tools:<id>` or a plain id; anything else in the list is dropped. */
    private val warnPattern = Regex("^[A-Za-z0-9][A-Za-z0-9:_.-]{0,59}$")

    fun parse(text: String): SetupResultFile? {
        val base = ToolResultParser.parse(text) ?: return null
        val values = base.tools
        return SetupResultFile(
            runEpoch = base.runEpoch,
            stage = values["stage"]?.takeIf { stagePattern.matches(it) },
            error = base.error?.takeIf { it.isNotEmpty() },
            exit = base.exit,
            facts =
                SetupFacts(
                    needMb = megabytes(values["need_mb"]),
                    haveMb = megabytes(values["have_mb"]),
                    warn = warnList(values["warn"]),
                ),
        )
    }

    /** Null when there is no file, it is too big, unreadable or not trusted ([parse]). */
    fun read(file: File): SetupResultFile? =
        try {
            if (file.isFile && file.length() <= MAX_CHARS) parse(file.readText()) else null
        } catch (_: java.io.IOException) {
            null
        }

    private fun megabytes(value: String?): Int? = value?.toIntOrNull()?.takeIf { it >= 0 }

    private fun warnList(value: String?): List<String> =
        value
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { warnPattern.matches(it) }
            .distinct()
            .take(MAX_WARN)
}

/**
 * `error=` codes of `post-setup-result.conf` (full mode) → app reasons. Each code is set by an
 * `OA_TOOLS_ERROR=` assignment in `post-setup.sh` (v1.2.2); the line of each is on the right. A code
 * not listed (a later script's) is UNKNOWN — the output box still shows what the script printed.
 */
internal object SetupReasons {
    private val codePattern = Regex("^[a-z0-9_-]{1,40}$")

    /**
     * `index-<n>`: the signature helper's code (`oa_gpgv_*`: 1 bad signature, 2 unknown key, 3 date,
     * 4 unclear answer, 10-12 gpgv or keyring missing or not runnable) — the list was not verified.
     */
    private val indexHelperCode = Regex("^index-[0-9]{1,3}$")

    val codes: Map<String, UpdateReason> =
        mapOf(
            // :572 — nothing was installed (the script says so)
            "free-space" to UpdateReason.NO_SPACE,
            // :855 via oa_fail_index download (:890, :906)
            "index-download" to UpdateReason.NETWORK,
            // :1703, :1711 — the Node.js archive or its checksum list
            "node-download" to UpdateReason.NETWORK,
            // :1418 — install_deb: download, checksum or unpack; the script says "Check your network connection"
            "deb" to UpdateReason.NETWORK,
            // :855 via oa_fail_index mismatch (:910) / noentry (:903)
            "index-mismatch" to UpdateReason.VERIFY_FAILED,
            "index-noentry" to UpdateReason.VERIFY_FAILED,
            // :1720
            "node-checksum" to UpdateReason.VERIFY_FAILED,
            // :825, :1453, :1463 — download, signature, unpack or linker: one code for all of them
            "glibc" to UpdateReason.INSTALL_FAILED,
            // :1431 (git missing after unpack), :2063 (git could not be downloaded again)
            "git" to UpdateReason.INSTALL_FAILED,
            // :2067 — the git wrapper would not run
            "git-wrapper" to UpdateReason.INSTALL_FAILED,
            // :1738, :1860
            "node-run" to UpdateReason.INSTALL_FAILED,
            "node-verify" to UpdateReason.INSTALL_FAILED,
            // :2173
            "npm-openclaw" to UpdateReason.INSTALL_FAILED,
            // :2534 (v1.2.3) — installed, installed once more, still incomplete (files missing or no start)
            "openclaw-incomplete" to UpdateReason.OPENCLAW_INCOMPLETE,
            // :2544 — ~/.bashrc could not be written
            "env" to UpdateReason.ENV,
            // :219-221 — TERM/HUP/INT (ManagedRunGuard.settle makes it CANCELLED after the user's cancel)
            "interrupted" to UpdateReason.INTERRUPTED,
            // :200 — stopped by `set -e` with no code of its own
            "unknown" to UpdateReason.UNKNOWN,
        )

    fun fromError(code: String): UpdateReason =
        when {
            !codePattern.matches(code) -> UpdateReason.UNKNOWN
            indexHelperCode.matches(code) -> UpdateReason.VERIFY_FAILED
            else -> codes[code] ?: UpdateReason.UNKNOWN
        }
}

/**
 * How a SETUP run ended — never a false success. Success needs ALL of: the result file is this
 * run's (`run` ≥ the start, `exit` present and equal to the process's exit code), `exit=0`,
 * `stage=done`, no `error`, and the marker `.post-setup-done`. Anything short of that is a failure:
 * - this run's file: its `error=` ([SetupReasons]); a signal exit without one is INTERRUPTED; else UNKNOWN
 *   (e.g. exit 0 but no marker);
 * - no file of this run: exit 2 is BUSY (the shared lock — the script writes nothing then), a signal
 *   exit (129..192, also SIGKILL's 137 from the system) is INTERRUPTED, the storage refusal printed
 *   before the lock could be made is NO_SPACE, anything else — exit 0 included — is UNKNOWN.
 * The output's last failure block ([FailLineCollector]) is the failure's `detail`; a success's
 * `detail` is the last `[WARN]` sentence when `warn=` lists anything.
 */
internal object SetupVerdict {
    private val signalExits = 129..192
    private const val BUSY_EXIT = 2

    /**
     * `oa_full_nospace_message` (post-setup.sh): "Not enough free storage to install OpenClaw:
     * 2000 MB needed, <n> MB available."
     */
    private val noSpacePattern = Regex("""Not enough free storage to .*?(\d+) MB needed, (\d+) MB available""")

    @Suppress("LongParameterList") // the inputs of one decision, named so a call reads as a table row
    fun decide(
        result: SetupResultFile?,
        exitCode: Int,
        startedAtSec: Long,
        markerExists: Boolean,
        failLines: List<String>,
        lastWarning: String = "",
    ): RunVerdict {
        val own = result?.takeIf { it.runEpoch >= startedAtSec && it.exit != null && it.exit == exitCode }
        val detail = failLines.joinToString("\n")
        if (own == null) {
            val noSpace = noSpace(exitCode, failLines)
            val reason = if (noSpace != null) UpdateReason.NO_SPACE else fromExit(exitCode)
            return RunVerdict.Failure(reason, exitCode, detail, 0, noSpace ?: SetupFacts())
        }
        val facts = own.facts
        val warnings = facts.warn.size
        val complete =
            exitCode == 0 && own.stage == ManagedSetup.DONE_STAGE && own.error == null && markerExists
        return if (complete) {
            RunVerdict.Success(0, warnings, if (warnings > 0) lastWarning else "", facts)
        } else {
            RunVerdict.Failure(fromResult(own, exitCode), exitCode, detail, warnings, facts)
        }
    }

    private fun fromResult(
        own: SetupResultFile,
        exitCode: Int,
    ): UpdateReason =
        when {
            own.error != null -> SetupReasons.fromError(own.error)
            exitCode in signalExits -> UpdateReason.INTERRUPTED
            else -> UpdateReason.UNKNOWN
        }

    private fun fromExit(exitCode: Int): UpdateReason =
        when (exitCode) {
            BUSY_EXIT -> UpdateReason.BUSY
            in signalExits -> UpdateReason.INTERRUPTED
            else -> UpdateReason.UNKNOWN
        }

    /**
     * On a full disk the lock folder cannot be made either: the script then prints the storage
     * message and ends with 1 without a result file (`oa_full_begin`). The numbers come from that line.
     */
    private fun noSpace(
        exitCode: Int,
        failLines: List<String>,
    ): SetupFacts? {
        if (exitCode == 0 || exitCode == BUSY_EXIT || exitCode in signalExits) return null
        val match = failLines.firstOrNull()?.let { noSpacePattern.find(it) } ?: return null
        return SetupFacts(
            needMb = match.groupValues[1].toIntOrNull(),
            haveMb = match.groupValues[2].toIntOrNull(),
        )
    }
}

/**
 * The first install's second half as a managed run (SETUP), and when the terminal still runs it.
 * The app runs `post-setup.sh` itself only when the script it would run can be run that way
 * ([capable]): it honours `OA_NO_ONBOARD` and writes `post-setup-result.conf`. An older script
 * keeps the terminal flow (the app types `bash <script>`), so an older app with a newer script and a
 * newer app with an older script both stay safe.
 */
internal object ManagedSetup {
    /** Paths relative to the home directory. */
    const val SCRIPT = ".openclaw-android/post-setup.sh"
    const val RESULT = ".openclaw-android/post-setup-result.conf"
    const val MARKER = ".openclaw-android/.post-setup-done"

    /** The script ends after the install (exit 0) instead of starting the interactive `openclaw onboard`. */
    const val NO_ONBOARD = "OA_NO_ONBOARD"
    private const val RESULT_NAME = "post-setup-result.conf"

    const val STAGE_TOTAL = 7
    const val DONE_STAGE = "done"

    /** `echo -e "▸ ${YELLOW}[N/7]${NC} …"` once the colors are removed. */
    val stagePattern = Regex("""^▸ \[([1-7])/7\] """)

    /** `echo -e "  ${RED}✗${NC} …"`: how `post-setup.sh` says what stopped it (besides `[FAIL]`). */
    const val CROSS_MARKER = "✗"

    /** A script larger than this is not read (the real one is about 130 KB). */
    private const val MAX_SCRIPT_BYTES = 4L * 1024 * 1024

    /** Both literals must be there: one alone is a script between versions. */
    fun capable(text: String): Boolean = NO_ONBOARD in text && RESULT_NAME in text

    /** False when [file] is missing, empty, too big or unreadable. */
    fun capable(file: File): Boolean = readText(file)?.let(::capable) == true

    /**
     * The script a SETUP run would use: the home copy ([script]); the bundled one ([bundled]) only
     * while there is no home copy — the run's own refresh copies it in then
     * ([BootstrapManager.refreshPostSetupScript]).
     */
    fun capableScript(
        script: File,
        bundled: () -> InputStream,
    ): Boolean =
        if (script.length() > 0L) {
            capable(script)
        } else {
            try {
                bundled().use { capable(String(it.readBytes(), Charsets.UTF_8)) }
            } catch (_: java.io.IOException) {
                false
            }
        }

    /**
     * May the app type `bash <script>` into the terminal? Only for an unfinished install whose
     * script cannot be run as a managed run, and never while a run that changes the install is going
     * (a managed run, an update or a tool install — two runs of the script would fight over the lock).
     */
    fun shouldRunInTerminal(
        scriptCapable: Boolean,
        markerPresent: Boolean,
        managedRunActive: Boolean,
    ): Boolean = !markerPresent && !managedRunActive && !scriptCapable

    /** True while any run that changes the install holds the lease or the managed-run guard. */
    fun managedRunActive(): Boolean = RunLease.owner() != null || ManagedRunGuard.isRunning()

    /**
     * `getSetupResult()`: the last full setup's result file, for the page that offers to continue it.
     * `interrupted` = the file has no `exit` (the run did not end through its trap) and no setup,
     * update or tool run is alive — neither the app's own SETUP nor a `post-setup.sh` process
     * ([ProcScan.isUpdateRunner]; the lock's owner is one of them). `reason` is the app reason of
     * `error=`, INTERRUPTED for an interrupted run, else absent. `managed` = [capableScript].
     */
    fun resultState(
        homeDir: File,
        scan: ProcScan,
        managed: Boolean,
    ): Map<String, Any?> {
        val result = SetupResultParser.read(File(homeDir, RESULT))
        val interrupted = result != null && result.exit == null && !runAlive(scan)
        val reason =
            when {
                result?.error != null -> SetupReasons.fromError(result.error)
                interrupted -> UpdateReason.INTERRUPTED
                else -> null
            }
        val facts = result?.facts ?: SetupFacts()
        return mapOf(
            "present" to (result != null),
            "stage" to result?.stage,
            "error" to result?.error,
            "reason" to reason?.name,
            "needMb" to facts.needMb,
            "haveMb" to facts.haveMb,
            "warn" to facts.warn,
            "exit" to result?.exit,
            "interrupted" to interrupted,
            "managed" to managed,
        )
    }

    private fun runAlive(scan: ProcScan): Boolean {
        val own = ManagedRunGuard.snapshot()
        return (own.busy && own.kind == RunKinds.SETUP) || scan.externalRunners("").isNotEmpty()
    }

    private fun readText(file: File): String? =
        try {
            if (file.isFile && file.length() in 1..MAX_SCRIPT_BYTES) file.readText() else null
        } catch (_: java.io.IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
}
