package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The warning line of a successful update (v1.2.2), read from the sources themselves.
 *
 * NOT a `[FAIL]` sentence: CLAW's gateway warning is printed with `[WARN]` by a run that goes on and
 * ends with exit 0. The app never classifies it (it is not quoted in [UpdateFailPhraseContractTest]
 * and maps to no reason): it shows it word for word under the success card and the last-update line.
 * It is pinned here because the e2e tests ([ManagedRunWarningEndToEndTest]) print it the way the
 * script does — if CLAW rewords it, this test fails and the fake output is brought back in step.
 *
 * Also pinned: the page's limits and keys for this line, the three translations and the shipped bundle.
 */
internal class UpdateWarnPhraseContractTest {
    private val sentence = ManagedRunWarningEndToEndTest.GATEWAY_WARN_SENTENCE

    private fun repoFile(path: String): File {
        val f = File("../../$path")
        assertTrue(f.isFile, "expected ${f.absolutePath}")
        return f
    }

    private fun wwwFile(path: String): File {
        val f = File("../www/src/$path")
        assertTrue(f.isFile, "expected ${f.absolutePath}")
        return f
    }

    private val status by lazy { wwwFile("screens/SettingsStatus.tsx").readText() }

    /** key → value text (quotes stripped) for every `  key: '…',` line of a locale file. */
    private fun entries(locale: String): Map<String, String> =
        Regex("""(?m)^\s+([a-z_0-9]+):\s*(.*)$""")
            .findAll(wwwFile("i18n/$locale.ts").readText())
            .associate { m ->
                val raw =
                    m.groupValues[2]
                        .trim()
                        .removeSuffix(",")
                        .trim()
                m.groupValues[1] to raw.removeSurrounding("'").removeSurrounding("\"").removeSurrounding("`")
            }

    // ── the script ──────────────────────────────────────────────────────────

    @Test
    fun `platforms openclaw update sh still prints the gateway warning, word for word, as a WARN echo`() {
        val script = repoFile("platforms/openclaw/update.sh").readText()
        val echo = "echo -e \"\${YELLOW}[WARN]\${NC} $sentence\""
        assertTrue(script.contains(echo), "update.sh no longer prints: $echo")
        // The sentence lives in one function (`oc_warn_busy`) with exactly that echo as its body ...
        val fn = script.indexOf("oc_warn_busy() {")
        assertTrue(fn >= 0, "oc_warn_busy() is gone from update.sh")
        val body =
            script
                .substring(fn)
                .lineSequence()
                .drop(1)
                .take(2)
                .toList()
        assertEquals(echo, body.first().trim(), body.toString())
        assertEquals("}", body[1].trim(), "oc_warn_busy grew more than the one echo: $body")
        // ... and is called on the success path: a skipped check, not a failure (no reason, no healthy=false note)
        val at = script.indexOf("elif [ \"\$OC_HEALTH_RC\" -eq 3 ]; then")
        assertTrue(at >= 0, "the gateway branch (health check rc 3) is gone from update.sh")
        val branch = script.substring(at)
        val head = branch.lineSequence().take(4).toList()
        assertTrue(head.any { it.trim() == "oc_warn_busy" }, head.toString())
        assertFalse(
            branch.lineSequence().take(3).any { it.contains("oa_note") || it.contains("exit ") },
            "the gateway branch now notes a result or exits",
        )
        // The same warning follows a migration whose re-check found a gateway on the state (rc 3)
        val recheck = script.indexOf("if [ \"\$OC_RECHECK_RC\" -eq 3 ]; then")
        assertTrue(recheck >= 0, "the re-check branch (rc 3) is gone from update.sh")
        assertTrue(
            script
                .substring(recheck)
                .lineSequence()
                .take(4)
                .any { it.trim() == "oc_warn_busy" },
            "the re-check no longer warns with oc_warn_busy",
        )
        assertEquals(2, Regex("""(?m)^\s+oc_warn_busy$""").findAll(script).count(), "oc_warn_busy callers changed")
    }

    @Test
    fun `the gateway warning is no FAIL sentence and is shown whole - it fits the 200 characters`() {
        val failSentences =
            Regex("""\[FAIL\]\$\{NC\} (.*)"\s*$""", RegexOption.MULTILINE)
                .findAll(repoFile("platforms/openclaw/update.sh").readText())
                .map { it.groupValues[1] }
                .toList()
        assertFalse(sentence in failSentences)
        assertEquals(UpdateReason.UNKNOWN, UpdateReasons.fromFailLine(sentence), "a fail phrase matches the warning")
        assertTrue(sentence.length <= UpdateOutput.MAX_WARNING_CHARS, "${sentence.length} characters")
        // as the shell prints it: the watcher keeps exactly the sentence
        val w = RunOutputWatcher(RunKinds.spec(RunKinds.UPDATE)!!)
        w.accept("\u001B[1;33m[WARN]\u001B[0m $sentence")
        assertEquals(sentence, w.lastWarning())
    }

    // ── the page ────────────────────────────────────────────────────────────

    @Test
    fun `the page cuts the line to the same 200 characters as native and the record`() {
        val max = Regex("""const WARNING_MAX_CHARS = (\d+)""").find(status)?.groupValues?.get(1)
        assertEquals(UpdateOutput.MAX_WARNING_CHARS.toString(), max)
        assertEquals(UpdateOutput.MAX_WARNING_CHARS, RunOutcomeStore.MAX_DETAIL)
    }

    @Test
    fun `the warning line is chosen only for a success, from the card's own event and from the record`() {
        assertTrue(
            status.contains(
                "const resultWarning = result ? " +
                    "warningLine(result.phase === 'done', result.warnings, result.detail) : ''",
            ),
            "the card's warning line changed",
        )
        assertTrue(
            Regex(
                """const lastWarning =\s+!busyUi && !resultShown \? warningLine\(lastRun\?\.verdict === 'success', """ +
                    """lastRun\?\.warnings, lastRun\?\.detail\) : ''""",
            ).containsMatchIn(status),
            "the last-update line's warning changed",
        )
        // never matched against the state pattern: that is for MIGRATION/HEALTH failures only
        assertFalse(Regex("""STATE_BUSY_PATTERN\.test\([^)]*(resultWarning|lastWarning)""").containsMatchIn(status))
        assertFalse(Regex("""repairHintKey\([^)]*(resultWarning|lastWarning)""").containsMatchIn(status))
    }

    @Test
    fun `the new summary key is used by the page`() {
        assertTrue(
            status.contains("t(resultWarning ? 'status_result_warnings_detail' : 'status_result_warnings',"),
            "the summary no longer switches to status_result_warnings_detail",
        )
    }

    // ── translations ────────────────────────────────────────────────────────

    /** Words that say the line is BELOW (the output box under the summary), per locale. */
    private val below = mapOf("en" to "below", "ko" to "아래", "zh" to "下方")

    @Test
    fun `status_result_warnings_detail is in every locale with the count, and points at the script output below`() {
        for ((locale, word) in below) {
            val map = entries(locale)
            val text = map["status_result_warnings_detail"]
            assertNotNull(text, "$locale.ts lacks status_result_warnings_detail")
            assertTrue(text!!.isNotBlank(), locale)
            assertTrue(text.contains("{n}"), "$locale: no {n}: $text")
            assertTrue(text.contains(word), "$locale: does not point at the line below: $text")
            assertTrue(text.contains("update.log"), "$locale: no pointer to all warnings: $text")
            assertTrue(map["status_output"]?.isNotBlank() == true, "$locale: status_output missing")
            assertTrue(map["status_result_warnings"]?.isNotBlank() == true, "$locale: status_result_warnings missing")
            assertTrue(text != map["status_result_warnings"], "$locale: the two summaries are the same text")
        }
    }

    @Test
    fun `the detail summary claims nothing about changes or starts, in any language`() {
        // "nothing was changed" is status_unchanged's alone; a success with warnings did change things
        val claims =
            Regex(
                "not changed|nothing was changed|no changes|not started|변경하지 않|변경되지 않|시작하지 않|未更改|没有更改|未开始|未启动",
                RegexOption.IGNORE_CASE,
            )
        for (locale in below.keys) {
            val text = entries(locale).getValue("status_result_warnings_detail")
            assertFalse(claims.containsMatchIn(text), "$locale: $text")
        }
    }

    // ── the shipped bundle ──────────────────────────────────────────────────

    @Test
    fun `the shipped bundle has the three translations, the summary switch and the 200-character cut`() {
        val index = File("src/main/assets/www/index.html")
        val js = Regex("""src="\./(assets/index-[A-Za-z0-9_-]+\.js)"""").find(index.readText())
        assertNotNull(js, "index.html names no bundle")
        val bundle = File("src/main/assets/www/${js!!.groupValues[1]}").readText()
        for (locale in below.keys) {
            val text = entries(locale).getValue("status_result_warnings_detail")
            assertTrue(bundle.contains(text), "bundle lacks the $locale status_result_warnings_detail (rebuild www)")
        }
        val switch = "\"status_result_warnings_detail\":\"status_result_warnings\""
        assertTrue(bundle.contains(switch), "summary switch not bundled")
        // warningLine: `.trim().split(`\n`)[0].slice(0, WARNING_MAX_CHARS)`, the constant inlined or named
        val cut = Regex("""\.trim\(\)\.split\(`\n`\)\[0]\.slice\(0,([A-Za-z_$][\w$]*|\d+)\)""").find(bundle)
        assertNotNull(cut, "warningLine's first-line cut is not bundled")
        val limit = cut!!.groupValues[1]
        val max = UpdateOutput.MAX_WARNING_CHARS.toString()
        assertTrue(
            limit == max || Regex("""[,;{\s]${Regex.escape(limit)}=$max[,;]""").containsMatchIn(bundle),
            "the bundled cut is not $max characters ($limit)",
        )
        assertTrue(bundle.contains(".verdict)===\"success\""), "the last-update line's success check is not bundled")
    }
}
