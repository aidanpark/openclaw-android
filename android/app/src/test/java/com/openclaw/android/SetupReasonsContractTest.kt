package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The SETUP contract read from the script itself (`post-setup.sh` at the repository root): every
 * `error=` code a full run can write is known to [SetupReasons] (a new code in the script fails
 * here), the seven `oa_stage` calls and their `▸ [N/7]` lines match [ManagedSetup.stagePattern] and
 * `stageTotal`, the storage line is what [SetupVerdict] reads, the capability literals are there,
 * and every stage has its name in the three locales.
 */
internal class SetupReasonsContractTest {
    private val script: String by lazy {
        val f = File("../../post-setup.sh")
        assertTrue(f.isFile, "expected ${f.absolutePath}")
        f.readText()
    }

    /** The expected reason of each code, written out (not recomputed): a changed mapping fails here. */
    private val expected =
        mapOf(
            "free-space" to UpdateReason.NO_SPACE,
            "index-download" to UpdateReason.NETWORK,
            "node-download" to UpdateReason.NETWORK,
            "deb" to UpdateReason.NETWORK,
            "index-mismatch" to UpdateReason.VERIFY_FAILED,
            "index-noentry" to UpdateReason.VERIFY_FAILED,
            "node-checksum" to UpdateReason.VERIFY_FAILED,
            "glibc" to UpdateReason.INSTALL_FAILED,
            "git" to UpdateReason.INSTALL_FAILED,
            "git-wrapper" to UpdateReason.INSTALL_FAILED,
            "node-run" to UpdateReason.INSTALL_FAILED,
            "node-verify" to UpdateReason.INSTALL_FAILED,
            "npm-openclaw" to UpdateReason.INSTALL_FAILED,
            // v1.2.3: installed, installed once more, still incomplete — not a download or a plain failure
            "openclaw-incomplete" to UpdateReason.OPENCLAW_INCOMPLETE,
            "env" to UpdateReason.ENV,
            "interrupted" to UpdateReason.INTERRUPTED,
            "unknown" to UpdateReason.UNKNOWN,
        )

    /** One `OA_TOOLS_ERROR=<value>` assignment: its 1-based line and its value (quotes removed). */
    data class Assignment(
        val line: Int,
        val value: String,
    )

    companion object {
        private val assignment = Regex("""OA_TOOLS_ERROR=("([^"]*)"|([^\s;"]*))""")

        fun assignments(text: String): List<Assignment> =
            text.lines().flatMapIndexed { i, line ->
                assignment
                    .findAll(line)
                    .map { m ->
                        Assignment(i + 1, m.groupValues[2].ifEmpty { m.groupValues[3] })
                    }.toList()
            }

        /** First and last line (1-based) of `run_tools_only() { … }`: the `--tools-only` mode's own codes. */
        fun toolsOnlyLines(text: String): IntRange {
            val lines = text.lines()
            val start = lines.indexOfFirst { it.startsWith("run_tools_only() {") }
            check(start >= 0) { "run_tools_only() is gone from post-setup.sh" }
            val end = (start + 1 until lines.size).first { lines[it] == "}" }
            return (start + 1)..(end + 1)
        }

        /** The arguments `oa_fail_index` is called with (a variable = the signature helper's number). */
        fun failIndexArgs(text: String): Set<String> =
            Regex("""oa_fail_index ("?[^\s;|}"]+"?)""")
                .findAll(text)
                .map { it.groupValues[1].trim('"') }
                .filter { !it.startsWith("<") }
                .toSet()

        /**
         * The full-run codes [codes] does not know: every literal value outside `run_tools_only`,
         * `index-$1` expanded to its literal `oa_fail_index` arguments (a numeric helper code is
         * matched by `index-<n>`).
         */
        fun uncovered(
            text: String,
            codes: Set<String>,
        ): Set<String> {
            val toolsOnly = toolsOnlyLines(text)
            val values =
                assignments(text)
                    .filter { it.line !in toolsOnly && it.value.isNotEmpty() }
                    .flatMap { a ->
                        if (a.value == "index-\$1") {
                            failIndexArgs(text).filter { !it.startsWith("\$") }.map { "index-$it" }
                        } else {
                            listOf(a.value)
                        }
                    }.toSet()
            return values
                .filter {
                    it !in codes &&
                        SetupReasons.fromError(
                            it,
                        ) == UpdateReason.UNKNOWN &&
                        it != "unknown"
                }.toSet()
        }
    }

    @Test
    fun `the script's assignments are found (the reader still works)`() {
        val values = assignments(script).map { it.value }.toSet()
        assertTrue(
            values.containsAll(listOf("free-space", "interrupted", "unknown", "npm-openclaw", "index-\$1")),
            "$values",
        )
        assertTrue(assignments(script).size >= 20, assignments(script).toString())
    }

    @Test
    fun `every error code a full run can write is known to SetupReasons`() {
        assertEquals(emptySet<String>(), uncovered(script, SetupReasons.codes.keys))
    }

    @Test
    fun `a new code in the script fails the check`() {
        val toolsOnly = toolsOnlyLines(script)
        val lines = script.lines().toMutableList()
        // a new failure in the full run (after run_tools_only), e.g. in the patches stage
        lines.add(toolsOnly.last + 2, "    OA_TOOLS_ERROR=disk-quota")
        assertEquals(setOf("disk-quota"), uncovered(lines.joinToString("\n"), SetupReasons.codes.keys))
        // and a new oa_fail_index word
        val withIndex = script + "\n    curl x || oa_fail_index proxy\n"
        assertEquals(setOf("index-proxy"), uncovered(withIndex, SetupReasons.codes.keys))
    }

    @Test
    fun `the codes only the tools-only mode writes are inside run_tools_only`() {
        val toolsOnly = toolsOnlyLines(script)
        val usage = assignments(script).filter { it.value == "usage" }
        assertTrue(usage.isNotEmpty())
        usage.forEach { assertTrue(it.line in toolsOnly, "usage outside run_tools_only at line ${it.line}") }
        assertEquals(UpdateReason.UNKNOWN, SetupReasons.fromError("usage"))
    }

    @Test
    fun `SetupReasons has no code the script no longer writes`() {
        val written = assignments(script).map { it.value }.toSet()
        val indexWords = failIndexArgs(script).map { "index-$it" }.toSet()
        SetupReasons.codes.keys.forEach { code ->
            assertTrue(
                code in written || code in indexWords,
                "SetupReasons.codes has $code, which post-setup.sh never writes",
            )
        }
    }

    @Test
    fun `the mapping is the one in the plan's table`() {
        assertEquals(expected, SetupReasons.codes)
    }

    @Test
    fun `oa_fail_index words and helper numbers map as the plan says`() {
        val args = failIndexArgs(script)
        assertTrue(args.containsAll(listOf("download", "mismatch", "noentry")), "$args")
        assertTrue(args.any { it.startsWith("\$") }, "the helper's number is passed through: $args")
        assertEquals(UpdateReason.NETWORK, SetupReasons.fromError("index-download"))
        // the case labels of oa_fail_index: every numeric one is a verification failure
        val body = script.substringAfter("oa_fail_index() {").substringBefore("\n}\n")
        val numbers = Regex("""(?m)^\s+(\d+)\)""").findAll(body).map { it.groupValues[1] }.toList()
        assertEquals(listOf("1", "2", "3", "10", "11", "12"), numbers)
        numbers.forEach { assertEquals(UpdateReason.VERIFY_FAILED, SetupReasons.fromError("index-$it"), it) }
        // the catch-all branch (another helper number) too
        assertEquals(UpdateReason.VERIFY_FAILED, SetupReasons.fromError("index-4"))
        assertTrue(body.contains("Installation was stopped for safety"))
    }

    @Test
    fun `the TERM HUP INT traps of the full run write interrupted with 143 129 130`() {
        val begin = script.substringAfter("oa_full_begin() {").substringBefore("\n}\n")
        assertTrue(begin.contains("trap 'OA_TOOLS_ERROR=interrupted; OA_FULL_SIGNAL_CODE=143; exit 143' TERM"))
        assertTrue(begin.contains("trap 'OA_TOOLS_ERROR=interrupted; OA_FULL_SIGNAL_CODE=129; exit 129' HUP"))
        assertTrue(begin.contains("trap 'OA_TOOLS_ERROR=interrupted; OA_FULL_SIGNAL_CODE=130; exit 130' INT"))
        listOf(129, 130, 143).forEach {
            assertEquals(
                UpdateReason.INTERRUPTED,
                SetupVerdict.decide(null, it, 0, false, emptyList()).let { v ->
                    (v as RunVerdict.Failure).reason
                },
            )
        }
    }

    @Test
    fun `exit 2 is busy only - the lock refusal writes nothing and other codes are normalized to 1`() {
        val begin = script.substringAfter("oa_full_begin() {").substringBefore("\n}\n")
        val busy = begin.substringAfter("if [ \"\$_lock_rc\" -eq 1 ]; then").substringBefore("elif")
        assertTrue(busy.contains("Another setup, update or tools run is in progress"), busy)
        assertTrue(busy.trim().endsWith("exit 2"), busy)
        // the result file is first written after the lock is ours
        assertTrue(begin.indexOf("OA_FULL_LOCK_OWNED=true") > begin.indexOf("exit 2"))
        assertTrue(script.contains("case \"\$code\" in 0|1|129|130|143) ;; *) code=1 ;; esac"))
        assertTrue(
            script.contains("[ \"\$OA_FULL_LOCK_OWNED\" = true ] || return 0"),
            "oa_full_result writes only for the lock's owner",
        )
    }

    // ── stages ──────────────────────────────────────────────────────────────

    @Test
    fun `seven oa_stage calls, 1 to 7 in order, equal stageTotal`() {
        val calls = Regex("""(?m)^oa_stage (\d+)\s*$""").findAll(script).map { it.groupValues[1].toInt() }.toList()
        assertEquals((1..7).toList(), calls)
        assertEquals(calls.size, ManagedSetup.STAGE_TOTAL)
        assertEquals(ManagedSetup.STAGE_TOTAL, RunKinds.spec(RunKinds.SETUP)!!.stageTotal)
    }

    /** `echo -e "▸ ${YELLOW}[N/7]${NC} …"` as a terminal (and the app) receives it. */
    private fun rendered(echoLine: String): String {
        val text = echoLine.substringAfter("echo -e \"").substringBeforeLast("\"")
        return text
            .replace("\${YELLOW}", "\u001B[1;33m")
            .replace("\${NC}", "\u001B[0m")
            .replace("\${NODE_VERSION}", "22.22.0")
    }

    @Test
    fun `every stage line of the script is read as its stage, by the parser and by the watcher`() {
        val stageLines = script.lines().map { it.trim() }.filter { it.startsWith("echo -e \"▸ \${YELLOW}[") }
        assertTrue(stageLines.size >= 7, stageLines.toString())
        val seen = mutableSetOf<Int>()
        stageLines.forEach { line ->
            val out = rendered(line)
            val n = UpdateStageParser.stage(out, ManagedSetup.stagePattern)
            assertTrue(n != null, "not a stage for the app: $line")
            val watcher = RunOutputWatcher(RunKinds.spec(RunKinds.SETUP)!!)
            watcher.accept(out)
            assertEquals(n, watcher.stage, line)
            seen += n!!
        }
        assertEquals((1..7).toSet(), seen)
    }

    @Test
    fun `the tools-only progress line and update steps are not setup stages`() {
        val tools = script.lines().map { it.trim() }.first { it.contains("Installing tools\${NC}") }
        assertEquals(null, UpdateStageParser.stage(rendered(tools), ManagedSetup.stagePattern))
        assertEquals(null, UpdateStageParser.stage("[3/5] Update Core", ManagedSetup.stagePattern))
        assertEquals(null, UpdateStageParser.stage("▸ [8/7] later", ManagedSetup.stagePattern))
        assertEquals(null, UpdateStageParser.stage(" ▸ [1/7] indented", ManagedSetup.stagePattern))
    }

    // ── the storage message, the failure marker, the capability ─────────────

    @Test
    fun `the storage message of the script is what the verdict reads before the lock`() {
        val fn = script.substringAfter("oa_full_nospace_message() {").substringBefore("\n}\n")
        val echo = fn.lines().map { it.trim() }.first { it.startsWith("echo -e \"\${RED}[FAIL]") }
        val out =
            echo
                .substringAfter("echo -e \"")
                .substringBeforeLast("\"")
                .replace("\${RED}", "\u001B[0;31m")
                .replace("\${NC}", "\u001B[0m")
                .replace("\${OA_FULL_HAVE_MB}", "321")
        val follow = fn.lines().map { it.trim() }.first { it.startsWith("echo \"       Nothing was changed") }
        val watcher = RunOutputWatcher(RunKinds.spec(RunKinds.SETUP)!!)
        watcher.accept(out)
        watcher.accept(follow.substringAfter("echo \"").substringBeforeLast("\""))
        val v = SetupVerdict.decide(null, 1, 100, false, watcher.failLines())
        assertEquals(UpdateReason.NO_SPACE, (v as RunVerdict.Failure).reason)
        assertEquals(SetupFacts(needMb = 2000, haveMb = 321), v.setup)
        assertEquals(2, watcher.failLines().size, "the 'Nothing was changed' line belongs to the block")
        // the same 2000 is written as need_mb when the lock is ours
        assertTrue(script.contains("OA_TOOLS_ERROR=free-space; OA_FULL_NEED_MB=2000"))
    }

    @Test
    fun `the cross lines that stop the script are collected as the failure`() {
        val cross =
            script
                .lines()
                .map {
                    it.trim()
                }.first { it.contains("\${RED}✗\${NC} Could not verify the Termux package list.") }
        val out =
            cross
                .substringAfter(
                    "echo -e \"",
                ).substringBeforeLast("\"")
                .replace("\${RED}", "\u001B[0;31m")
                .replace("\${NC}", "\u001B[0m")
        val watcher = RunOutputWatcher(RunKinds.spec(RunKinds.SETUP)!!)
        watcher.accept(out)
        watcher.accept(
            "    The package list could not be downloaded. Check your network connection and restart the app to retry.",
        )
        assertEquals("Could not verify the Termux package list.", watcher.failLines().first())
        assertEquals(2, watcher.failLines().size)
        // an update does not read ✗ (its own markers only)
        val update = RunOutputWatcher(RunKinds.spec(RunKinds.UPDATE)!!)
        update.accept(out)
        assertEquals(emptyList<String>(), update.failLines())
    }

    @Test
    fun `the script has both capability literals and ends with exit 0 under OA_NO_ONBOARD`() {
        assertTrue(ManagedSetup.capable(script))
        val tail = script.substringAfter("if [ \"\${OA_NO_ONBOARD:-}\" = \"1\" ]; then")
        assertTrue(tail.lines().take(4).any { it.trim() == "exit 0" }, tail.lines().take(4).toString())
        // the install is final (marker, exit=0, lock freed) before that point
        assertTrue(
            script.indexOf("oa_full_finalize\n") in 0 until script.indexOf("if [ \"\${OA_NO_ONBOARD:-}\" = \"1\" ]"),
        )
    }

    @Test
    fun `the SETUP run spec is bash, the home script, OA_NO_ONBOARD only, no gateway check`() {
        val spec = RunKinds.spec(RunKinds.SETUP)!!
        assertEquals(listOf("bash"), spec.command)
        assertEquals(ManagedSetup.SCRIPT, spec.homeScript)
        assertEquals(listOf("bash", File("/h", ManagedSetup.SCRIPT).absolutePath), spec.argv(File("/h")))
        assertEquals(mapOf("OA_NO_ONBOARD" to "1"), spec.env)
        assertFalse(spec.gatewayCheck)
        assertEquals(ManagedSetup.RESULT, spec.resultFile)
        assertTrue(spec.failMarkers.containsAll(listOf("[FAIL]", "✗")))
        val update = RunKinds.spec(RunKinds.UPDATE)!!
        assertEquals(mapOf(ManagedRunner.ASSUME_YES to "1"), update.env)
        assertEquals(null, update.homeScript)
        assertTrue(update.gatewayCheck)
        assertEquals(ManagedRunner.RESULT_FILE, update.resultFile)
    }

    // ── stage names in every locale ─────────────────────────────────────────

    @Test
    fun `setup_stage_1 to 7 exist in every locale, no other stage key`() {
        for (locale in listOf("en", "ko", "zh")) {
            val text = File("../www/src/i18n/$locale.ts").readText()
            val keys =
                Regex(
                    """(?m)^\s+(setup_stage_\d+):\s*'([^']+)'""",
                ).findAll(text).map { it.groupValues[1] }.toList()
            assertEquals((1..7).map { "setup_stage_$it" }, keys, locale)
        }
    }
}
