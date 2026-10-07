package com.openclaw.android

import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [ManagedRunWorld] for the first install's managed SETUP run: a FAKE `post-setup.sh` (a real
 * bash script in the home directory, run by the real [ManagedRunner] as `bash <script>`) that plays
 * the full-mode contract of the real one — `▸ [N/7]` stage lines, `post-setup-result.conf` (no
 * `exit` key while it runs), the marker `.post-setup-done`, the TERM trap (`error=interrupted`,
 * exit 143), `OA_NO_ONBOARD` and the shared lock (exit 2, nothing written). It registers itself in
 * the world's fake `/proc` with its real environment, so the cancel's SIGTERM really reaches it.
 * The refresh of the script before a run ([ManagedRunner]'s `refreshSetupScript`) is counted and
 * can be replaced ([onRefresh]).
 */
internal class SetupWorld(
    root: File,
) {
    val w = ManagedRunWorld(root)
    val home get() = w.home
    val oca get() = w.oca
    val script = File(w.oca, "post-setup.sh")
    val result = File(w.oca, "post-setup-result.conf")
    val marker = File(w.oca, ".post-setup-done")
    val lock = File(w.oca, ".tools.lock")
    val refreshes = AtomicInteger()
    val signals: MutableList<Pair<Int, Int>> = Collections.synchronizedList(mutableListOf())

    @Volatile var onRefresh: () -> Unit = {}

    private val rootPath = root.path

    fun runner(
        outcomes: RunOutcomeStore = RunOutcomeStore(w.lastRunFile),
        emit: (String, Map<String, Any?>) -> Unit = w::emit,
    ) = ManagedRunner(
        homeDir = w.home,
        environment = w::environment,
        outcomes = outcomes,
        gateway = w.gateway(),
        emit = emit,
        processes = RunProcesses(ProcScan(w.runProc.dir), ::sendSignal),
        refreshSetupScript = {
            refreshes.incrementAndGet()
            onRefresh()
        },
    )

    private fun sendSignal(
        pid: Int,
        sig: Int,
    ) {
        signals += pid to sig
        if (ownScript(pid)) ProcessBuilder("kill", "-$sig", "$pid").start().waitFor()
    }

    /** True only for a live process running a script of this world (never a stranger's pid). */
    fun ownScript(pid: Int): Boolean {
        val ps = ProcessBuilder("ps", "-o", "command=", "-p", "$pid").redirectErrorStream(true).start()
        val out = ps.inputStream.bufferedReader().readText()
        ps.waitFor()
        return out.contains(rootPath)
    }

    /**
     * Writes the fake script: the prelude (logs, `reg`, `hold`, `stage`, `fail`, `finish`, the TERM
     * trap, the lock check), then [body]. Both capability literals are in it ([capable] = true),
     * else neither, or only [onlyLiteral].
     */
    fun fakeSetup(
        body: String,
        register: Boolean = true,
        capable: Boolean = true,
        onlyLiteral: String? = null,
    ) {
        val literals =
            when {
                onlyLiteral != null -> "# $onlyLiteral"
                capable -> "# capability: OA_NO_ONBOARD post-setup-result.conf"
                else -> "# an older script: no capability literals"
            }
        val resultPath =
            if (capable && onlyLiteral == null) "\$OCA/post-setup-result.conf" else "\$OCA/post-setup-res\"\"ult.conf"
        script.writeText(
            """
            |#!/bin/bash
            |$literals
            |echo "${'$'}*" >> "${'$'}HOME/setup-calls.log"
            |NOB=${'$'}(printenv "OA_NO_ONB""OARD" || echo '<unset>')
            |printf '%s|%s|%s|%s\n' "${'$'}{OA_APP_RUN_TOKEN-<unset>}" "${'$'}NOB" "${'$'}{OA_ASSUME_YES-<unset>}" "${'$'}0" >> "${'$'}HOME/setup-env.log"
            |OCA="${'$'}HOME/.openclaw-android"
            |R="$resultPath"
            |MARKER="${'$'}OCA/.post-setup-done"
            |RED='\033[0;31m'; YELLOW='\033[1;33m'; GREEN='\033[0;32m'; NC='\033[0m'
            |reg() {
            |  d="${'$'}FAKEPROC/${'$'}${'$'}"; mkdir -p "${'$'}d"
            |  printf 'bash\0%s\0' "${'$'}0" > "${'$'}d/cmdline"
            |  env | tr '\n' '\0' > "${'$'}d/environ"
            |  echo "${'$'}${'$'} (bash) S ${'$'}PPID 1 1" > "${'$'}d/stat"
            |}
            |hold() { while [ ! -f "${'$'}HOME/${'$'}1" ]; do sleep 0.02; done; }
            |RUN=${'$'}(date +%s); STAGE=""; ERR=""; WARN=""; NEED=""; HAVE=""
            |result() {
            |  { printf 'schema=1\nrun=%s\n' "${'$'}RUN"
            |    [ -z "${'$'}STAGE" ] || printf 'stage=%s\n' "${'$'}STAGE"
            |    [ -z "${'$'}ERR" ] || printf 'error=%s\n' "${'$'}ERR"
            |    [ -z "${'$'}NEED" ] || printf 'need_mb=%s\n' "${'$'}NEED"
            |    [ -z "${'$'}HAVE" ] || printf 'have_mb=%s\n' "${'$'}HAVE"
            |    [ -z "${'$'}WARN" ] || printf 'warn=%s\n' "${'$'}WARN"
            |    [ -z "${'$'}{1:-}" ] || printf 'exit=%s\n' "${'$'}1"
            |  } > "${'$'}R.tmp" && mv -f "${'$'}R.tmp" "${'$'}R"
            |}
            |stage() { STAGE="${'$'}1"; result; echo -e "▸ ${'$'}{YELLOW}[${'$'}1/7]${'$'}{NC} ${'$'}2"; }
            |fail() { ERR="${'$'}1"; echo -e "  ${'$'}{RED}✗${'$'}{NC} ${'$'}2"; echo "    ${'$'}3"; result 1; exit 1; }
            |finish() { touch "${'$'}MARKER"; STAGE=done; result 0; }
            |busy() {
            |  if [ -d "${'$'}OCA/.tools.lock" ]; then
            |    echo "Another setup, update or tools run is in progress. Try again when it has finished." >&2
            |    exit 2
            |  fi
            |}
            |${if (register) "reg" else ":"}
            |trap 'ERR=interrupted; result 143; exit 143' TERM
            |$body
            |
            """.trimMargin(),
        )
        script.setExecutable(true)
    }

    /** Seven stages, the marker and the final result, then the end the real script has with `OA_NO_ONBOARD=1`. */
    val successBody =
        """
        |busy
        |result
        |stage 1 "Installing essential packages..."
        |stage 2 "Installing glibc runtime..."
        |stage 3 "Installing Node.js v22..."
        |stage 4 "Installing OpenClaw..."
        |stage 5 "Applying patches..."
        |stage 6 "Configuring environment..."
        |stage 7 "No optional tools selected"
        |finish
        |if [ "${'$'}{OA_NO_ONBOARD:-}" = "1" ]; then
        |  echo "  OpenClaw onboard skipped (OA_NO_ONBOARD=1)."
        |  exit 0
        |fi
        |touch "${'$'}HOME/onboard-ran"
        |exit 0
        """.trimMargin()

    /** A body without either capability literal (for an older script). */
    val oldBody = "echo ran\ntouch \"\$HOME/old-script-ran\"\nexit 0"

    /** Stages up to [failAt], then the failure that stops the real script with [code]. */
    fun failBody(
        code: String,
        failAt: Int = 3,
        sentence: String = "Something stopped the install",
        extra: String = "",
    ): String =
        buildString {
            appendLine("busy")
            appendLine("result")
            for (n in 1..failAt) appendLine("stage $n \"Stage $n\"")
            if (extra.isNotEmpty()) appendLine(extra)
            appendLine("fail $code \"$sentence\" \"Check your network connection\"")
        }

    fun calls(): List<String> = File(home, "setup-calls.log").let { if (it.isFile) it.readLines() else emptyList() }

    /** `token|noOnboard|assumeYes|$0` per call. */
    fun envLines(): List<List<String>> =
        File(home, "setup-env.log").let { f -> if (f.isFile) f.readLines().map { it.split('|') } else emptyList() }

    fun release(name: String) = w.release(name)

    /**
     * Drops what ended runs left in the fake `/proc` (a real kernel removes an ended process): a
     * stale entry would read as a terminal's run and refuse the next one as BUSY.
     */
    fun forgetProcs() {
        assertTrueNoRun()
        w.runProc.dir
            .listFiles()
            ?.forEach { it.deleteRecursively() }
    }

    private fun assertTrueNoRun() = check(!ManagedRunGuard.isRunning()) { "a run is still going" }

    fun cleanup(): Boolean = w.cleanup()
}
