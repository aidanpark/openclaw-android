package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.stream.Stream

/**
 * [ToolInstallVerdict.decide] claims success only when this run's result file says `ok` for the
 * tool AND the tool is on disk. The exit code alone is never believed.
 */
internal class ToolInstallVerdictTest {
    private fun result(
        run: Long = START,
        error: String? = null,
        exit: Int? = 0,
        vararg tools: Pair<String, String>,
    ) = ToolResultFile(run, error, exit, tools.toMap())

    private fun facts(
        exitCode: Int = 0,
        onDisk: Boolean = true,
        hint: ToolFailure? = null,
    ) = ToolRunFacts(START, ID, exitCode, onDisk, hint)

    private fun failure(reason: ToolFailure) = ToolVerdict.Failure(reason)

    @ParameterizedTest(name = "{0}")
    @MethodSource("allCombinations")
    fun `success iff a fresh result says ok and the files are on disk`(
        label: String,
        result: ToolResultFile?,
        facts: ToolRunFacts,
        expectSuccess: Boolean,
    ) {
        val verdict = ToolInstallVerdict.decide(result, facts)
        if (expectSuccess) {
            assertEquals(ToolVerdict.Success, verdict, label)
        } else {
            assertEquals(ToolVerdict.Failure::class, verdict::class, "$label -> $verdict")
        }
    }

    @Test
    fun `an earlier run's ok is not this run's success`() {
        val stale = result(run = START - 1, tools = arrayOf(ID to "ok"))
        assertEquals(failure(ToolFailure.NOT_RUN), ToolInstallVerdict.decide(stale, facts()))
    }

    @Test
    fun `a run that started in the same second counts`() {
        val same = result(run = START, tools = arrayOf(ID to "ok"))
        assertEquals(ToolVerdict.Success, ToolInstallVerdict.decide(same, facts()))
    }

    @Test
    fun `a much older run is not this run even with exit code 0 and the files present`() {
        val old = result(run = 0, tools = arrayOf(ID to "ok"))
        assertEquals(failure(ToolFailure.NOT_RUN), ToolInstallVerdict.decide(old, facts(exitCode = 0, onDisk = true)))
    }

    @Test
    fun `a stale result with exit code 2 is busy, not that result`() {
        val old = result(run = START - 1, tools = arrayOf(ID to "failed:install"))
        assertEquals(failure(ToolFailure.BUSY), ToolInstallVerdict.decide(old, facts(exitCode = 2)))
    }

    @Test
    fun `ok without the files on disk is a mismatch`() {
        val r = result(tools = arrayOf(ID to "ok"))
        assertEquals(failure(ToolFailure.FILE_MISMATCH), ToolInstallVerdict.decide(r, facts(onDisk = false)))
    }

    @Test
    fun `no result file and exit 0 is an older script that did not run`() {
        assertEquals(failure(ToolFailure.NOT_RUN), ToolInstallVerdict.decide(null, facts(exitCode = 0, onDisk = true)))
    }

    @Test
    fun `no result file and exit 2 is busy`() {
        assertEquals(failure(ToolFailure.BUSY), ToolInstallVerdict.decide(null, facts(exitCode = 2)))
    }

    @ParameterizedTest
    @CsvSource("ENV", "LOCK")
    fun `no result file and exit 2 takes the stderr hint`(hint: ToolFailure) {
        assertEquals(failure(hint), ToolInstallVerdict.decide(null, facts(exitCode = 2, hint = hint)))
    }

    @ParameterizedTest
    @CsvSource("0", "1", "143", "-1")
    fun `a hint is ignored unless the exit code is 2`(exit: Int) {
        assertEquals(
            failure(ToolFailure.NOT_RUN),
            ToolInstallVerdict.decide(null, facts(exitCode = exit, hint = ToolFailure.ENV)),
        )
    }

    @ParameterizedTest
    @CsvSource(
        "interrupted, INTERRUPTED",
        "index-download, INDEX_NETWORK",
        "index-mismatch, INDEX_VERIFY",
        "index-noentry, INDEX_VERIFY",
        "index-1, INDEX_VERIFY",
        "index-12, INDEX_VERIFY",
        "index-, INDEX_VERIFY",
        "env, ENV",
        "lock, LOCK",
        "usage, UNKNOWN",
        "busy, UNKNOWN",
        "Interrupted, UNKNOWN",
        "something-new, UNKNOWN",
    )
    fun `error values map to failure reasons`(
        error: String,
        expected: ToolFailure,
    ) {
        assertEquals(expected, ToolInstallVerdict.errorToFailure(error))
    }

    @Test
    fun `an empty error value is unknown`() {
        assertEquals(ToolFailure.UNKNOWN, ToolInstallVerdict.errorToFailure(""))
    }

    @Test
    fun `a run-level error wins over this tool's ok`() {
        val r = result(error = "interrupted", exit = 143, tools = arrayOf(ID to "ok"))
        assertEquals(failure(ToolFailure.INTERRUPTED), ToolInstallVerdict.decide(r, facts(exitCode = 143)))
    }

    @ParameterizedTest
    @CsvSource(
        "failed:install, INSTALL_FAILED",
        "failed:verify, VERIFY_FAILED",
        "failed:other, UNKNOWN",
        "OK, UNKNOWN",
        "'ok ', UNKNOWN",
        "'', UNKNOWN",
    )
    fun `per tool outcomes map to failure reasons`(
        outcome: String,
        expected: ToolFailure,
    ) {
        val r = result(exit = 1, tools = arrayOf(ID to outcome))
        assertEquals(failure(expected), ToolInstallVerdict.decide(r, facts(exitCode = 1)))
    }

    @Test
    fun `a fresh result with no line for this tool is not run`() {
        val r = result(tools = arrayOf("ttyd" to "ok"))
        assertEquals(failure(ToolFailure.NOT_RUN), ToolInstallVerdict.decide(r, facts()))
    }

    @Test
    fun `the exit code in the result file does not decide success`() {
        val r = result(exit = 1, tools = arrayOf(ID to "ok"))
        assertEquals(ToolVerdict.Success, ToolInstallVerdict.decide(r, facts(exitCode = 1)))
    }

    // ── exit= in the result file must be this process's exit code ───────────

    @Test
    fun `a fresh ok whose recorded exit differs from this process's exit is not this run`() {
        // Another run (e.g. a concurrent oa call) wrote the file; this process exited otherwise
        val r = result(exit = 0, tools = arrayOf(ID to "ok"))
        assertEquals(failure(ToolFailure.NOT_RUN), ToolInstallVerdict.decide(r, facts(exitCode = 1)))
    }

    @Test
    fun `a recorded exit that differs while this process exited 2 is busy`() {
        val r = result(exit = 0, tools = arrayOf(ID to "ok"))
        assertEquals(failure(ToolFailure.BUSY), ToolInstallVerdict.decide(r, facts(exitCode = 2)))
    }

    @ParameterizedTest
    @CsvSource("ENV", "LOCK")
    fun `a recorded exit that differs while this process exited 2 takes the stderr hint`(hint: ToolFailure) {
        val r = result(exit = 0, tools = arrayOf(ID to "ok"))
        assertEquals(failure(hint), ToolInstallVerdict.decide(r, facts(exitCode = 2, hint = hint)))
    }

    @Test
    fun `another run's failure is not reported as this run's failure`() {
        val r = result(exit = 1, tools = arrayOf(ID to "failed:install"))
        assertEquals(failure(ToolFailure.NOT_RUN), ToolInstallVerdict.decide(r, facts(exitCode = 0)))
    }

    @Test
    fun `a result without an exit line is judged by its run time alone`() {
        val r = result(exit = null, tools = arrayOf(ID to "ok"))
        assertEquals(ToolVerdict.Success, ToolInstallVerdict.decide(r, facts(exitCode = 0)))
        assertEquals(ToolVerdict.Success, ToolInstallVerdict.decide(r, facts(exitCode = 1)))
    }

    @Test
    fun `an interrupted result whose exit matches is this run's interruption`() {
        val r = result(error = "interrupted", exit = 143)
        assertEquals(failure(ToolFailure.INTERRUPTED), ToolInstallVerdict.decide(r, facts(exitCode = 143)))
    }

    // ── stderr hints ────────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource("error=env, ENV", "error=lock, LOCK", "'  error=env  ', ENV", "'\terror=lock\r', LOCK")
    fun `hintFromOutput recognizes the two bare hint lines`(
        line: String,
        expected: ToolFailure,
    ) {
        assertEquals(expected, ToolInstallVerdict.hintFromOutput(line))
    }

    @ParameterizedTest
    @CsvSource(
        "'Cannot create /h/.openclaw-android (error=env).', ENV",
        "'Could not create the tools lock in /h/.openclaw-android (error=lock).', LOCK",
        "'[WARN] error=lock while waiting', LOCK",
        "'x (error=env)', ENV",
    )
    fun `hintFromOutput finds the hint inside a sentence`(
        line: String,
        expected: ToolFailure,
    ) {
        assertEquals(expected, ToolInstallVerdict.hintFromOutput(line))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "error=", "error=busy", "error=interrupted", "ERROR=env", "error = env", "TOOL_RESULT tmux ok",
            "xerror=env", "myerror=lock", "error=envy", "error=lockfile", "error=env_x",
        ],
    )
    fun `hintFromOutput ignores every other line`(line: String) {
        assertNull(ToolInstallVerdict.hintFromOutput(line))
    }

    private companion object {
        const val START = 1_700_000_000L
        const val ID = "tmux"

        /** result null/present × run fresh/stale/equal × outcome × on disk × exit code. */
        @JvmStatic
        fun allCombinations(): Stream<Arguments> {
            val runs = listOf("equal" to START, "later" to START + 5, "stale" to START - 1)
            val outcomes = listOf("ok", "failed:install", "failed:verify", null)
            val errors = listOf(null, "interrupted")
            val factsList =
                listOf(true, false).flatMap { onDisk ->
                    listOf(0, 1, 2).map { ToolRunFacts(START, ID, it, onDisk) }
                }
            val withoutResult =
                factsList.map { f ->
                    Arguments.of("no result, disk=${f.installedOnDisk}, exit=${f.exitCode}", null, f, false)
                }
            val withResult =
                factsList.flatMap { f ->
                    runs.flatMap { (runLabel, run) ->
                        outcomes.flatMap { outcome ->
                            errors.map { error -> combination(f, runLabel, run, outcome, error) }
                        }
                    }
                }
            return (withoutResult + withResult).stream()
        }

        private fun combination(
            f: ToolRunFacts,
            runLabel: String,
            run: Long,
            outcome: String?,
            error: String?,
        ): Arguments {
            val tools = outcome?.let { mapOf(ID to it) } ?: emptyMap()
            val r = ToolResultFile(run, error, f.exitCode, tools)
            val success = run >= START && outcome == "ok" && f.installedOnDisk && error == null
            val label = "run=$runLabel outcome=$outcome error=$error disk=${f.installedOnDisk} exit=${f.exitCode}"
            return Arguments.of(label, r, f, success)
        }
    }
}
