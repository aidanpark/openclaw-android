package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Without a result file the app knows WHY `oa --update` failed only from the sentence after
 * `[FAIL]`. This contract reads the scripts themselves: every `[FAIL]` sentence on the update path
 * is quoted here with the reason the app must give it, so a sentence that changes, disappears or
 * is added in a script fails this test (and makes someone decide its reason) instead of silently
 * turning into UNKNOWN. `§` stands for `$` in the quoted source.
 */
internal class UpdateFailPhraseContractTest {
    private data class Quote(
        val file: String,
        val source: String,
        val reason: UpdateReason,
    )

    private val quotes =
        listOf(
            // oa.sh — cmd_update only
            Quote("oa.sh", "curl not found. Install it with: pkg install curl", UpdateReason.NOT_INSTALLED),
            Quote("oa.sh", "Failed to download update-core.sh", UpdateReason.DOWNLOAD),
            // update-core.sh
            // The shared run lock (R5). Busy exits 2, which the verdict reads as BUSY before any
            // sentence (see the exit-code test below); the sentence alone is not matched.
            Quote(
                "update-core.sh",
                "Another update, setup or tools run is in progress. Try again when it has finished.",
                UpdateReason.UNKNOWN,
            ),
            // exits 1: a lock that cannot be created is not "another run is going"
            Quote(
                "update-core.sh",
                "Could not create the run lock in §PROJECT_DIR (is the storage full?).",
                UpdateReason.UNKNOWN,
            ),
            Quote("update-core.sh", "Not running in Termux (\\§PREFIX not set)", UpdateReason.NOT_INSTALLED),
            Quote("update-core.sh", "curl not found. Install it with: pkg install curl", UpdateReason.NOT_INSTALLED),
            Quote("update-core.sh", "No platform detected", UpdateReason.NOT_INSTALLED),
            Quote("update-core.sh", "Failed to create temp directory", UpdateReason.UNKNOWN),
            Quote("update-core.sh", "Failed to download release", UpdateReason.DOWNLOAD),
            Quote("update-core.sh", "Missing required file: §f", UpdateReason.DOWNLOAD),
            Quote("update-core.sh", "Version pin missing in platforms/§PLATFORM/config.env", UpdateReason.DOWNLOAD),
            Quote(
                "update-core.sh",
                "The installed OpenClaw §{_OC_VER:-?} needs Node.js §{_OC_MIN_NODE} or newer, " +
                    "but this update pins Node.js §{PLATFORM_NODE_VERSION}.",
                UpdateReason.CACHE_STALE,
            ),
            Quote("update-core.sh", "The OpenClaw gateway is running.", UpdateReason.GATEWAY_RUNNING),
            Quote(
                "update-core.sh",
                "Your OpenClaw has saved chat history, and §{_oa_sg_why}.",
                UpdateReason.SESSION_GUARD,
            ),
            Quote(
                "update-core.sh",
                "Node.js v§{PLATFORM_NODE_VERSION} is required (found: §{CURRENT_NODE_VER:-none}) " +
                    "— update stopped before §PLATFORM.",
                UpdateReason.INSTALL_FAILED,
            ),
            // platforms/openclaw/update.sh
            Quote(
                "platforms/openclaw/update.sh",
                "Invalid OpenClaw version pin in config.env: '§{PLATFORM_NPM_PACKAGE_VERSION:-}'",
                UpdateReason.DOWNLOAD,
            ),
            Quote(
                "platforms/openclaw/update.sh",
                "Node.js v§{PLATFORM_NODE_VERSION:-?} is required (found: §NODE_FOUND) — OpenClaw was not changed",
                UpdateReason.INSTALL_FAILED,
            ),
            Quote(
                "platforms/openclaw/update.sh",
                "Installed OpenClaw §CURRENT_VER is newer than the pinned §PIN_VER: not going back.",
                UpdateReason.CACHE_STALE,
            ),
            Quote(
                "platforms/openclaw/update.sh",
                "The updater downloaded an older copy of itself (cache). Nothing was changed.",
                UpdateReason.CACHE_STALE,
            ),
            Quote("platforms/openclaw/update.sh", "Could not install openclaw §PIN_VER", UpdateReason.INSTALL_FAILED),
            Quote(
                "platforms/openclaw/update.sh",
                "OpenClaw §{PIN_VER} is installed, but the patch that moves your chat history is not in place:",
                UpdateReason.MIGRATION_FAILED,
            ),
            Quote(
                "platforms/openclaw/update.sh",
                "The data migration did not finish within 2 minutes.",
                UpdateReason.MIGRATION_FAILED,
            ),
            Quote(
                "platforms/openclaw/update.sh",
                "The data migration did not succeed (exit code §OC_DOCTOR_RC):",
                UpdateReason.MIGRATION_FAILED,
            ),
            Quote(
                "platforms/openclaw/update.sh",
                "The data migration ran, but OpenClaw still cannot use your data:",
                UpdateReason.MIGRATION_FAILED,
            ),
            Quote(
                "platforms/openclaw/update.sh",
                "OpenClaw §{PIN_VER} is installed, but it cannot use your existing data yet:",
                UpdateReason.HEALTH_FAILED,
            ),
            // scripts/install-nodejs.sh
            Quote(
                "scripts/install-nodejs.sh",
                "The updater is out of date (a cached copy was downloaded).",
                UpdateReason.CACHE_STALE,
            ),
            Quote(
                "scripts/install-nodejs.sh",
                "Invalid Node.js version pin: '§{NODE_VERSION}'",
                UpdateReason.DOWNLOAD,
            ),
            Quote("scripts/install-nodejs.sh", "Not running in Termux (\\§PREFIX not set)", UpdateReason.NOT_INSTALLED),
            Quote(
                "scripts/install-nodejs.sh",
                "glibc dynamic linker not found — run install-glibc.sh first",
                UpdateReason.UNKNOWN,
            ),
            Quote(
                "scripts/install-nodejs.sh",
                "The updater downloaded an older copy of itself (cache). Nothing was changed.",
                UpdateReason.CACHE_STALE,
            ),
            Quote("scripts/install-nodejs.sh", "Failed to create temp directory", UpdateReason.UNKNOWN),
            Quote("scripts/install-nodejs.sh", "Failed to download Node.js v§{NODE_VERSION}", UpdateReason.DOWNLOAD),
            Quote(
                "scripts/install-nodejs.sh",
                "Failed to download SHASUMS256.txt for Node.js v§{NODE_VERSION}",
                UpdateReason.DOWNLOAD,
            ),
            Quote("scripts/install-nodejs.sh", "Checksum mismatch for §NODE_TARBALL", UpdateReason.CHECKSUM),
            Quote("scripts/install-nodejs.sh", "Failed to extract Node.js", UpdateReason.INSTALL_FAILED),
            Quote(
                "scripts/install-nodejs.sh",
                "Extracted Node.js does not run (got: '§{STAGED_VER}')",
                UpdateReason.INSTALL_FAILED,
            ),
            Quote(
                "scripts/install-nodejs.sh",
                "Node.js verification failed (node: '§{NODE_VER}', npm: '§{NPM_VER}') — wrapper script may be broken",
                UpdateReason.INSTALL_FAILED,
            ),
            // scripts/lib.sh
            Quote("scripts/lib.sh", "Platform name is empty", UpdateReason.UNKNOWN),
            Quote("scripts/lib.sh", "Invalid platform name: §name", UpdateReason.UNKNOWN),
            Quote(
                "scripts/lib.sh",
                "Not enough free storage to §{what}: §{need} MB needed, §{have} MB available.",
                UpdateReason.NO_SPACE,
            ),
            Quote("scripts/lib.sh", "Platform config not found: §config_path", UpdateReason.UNKNOWN),
        )

    private fun script(path: String): String {
        val f = File("../../$path")
        assertTrue(f.isFile, "expected ${f.absolutePath}")
        return f.readText()
    }

    /** The part of a script whose `[FAIL]` lines belong to `oa --update`. */
    private fun updatePart(path: String): String {
        val text = script(path)
        if (path != "oa.sh") return text
        val start = text.indexOf("cmd_update() {")
        assertTrue(start >= 0, "cmd_update not found in oa.sh")
        val end = text.indexOf("\n}\n", start)
        return text.substring(start, end)
    }

    /** Every sentence after `[FAIL]${NC} ` in an `echo -e "…"` line of [text]. */
    private fun failSentences(text: String): List<String> =
        Regex("""\[FAIL\]\$\{NC\} (.*)"\s*$""", RegexOption.MULTILINE)
            .findAll(text)
            .map { it.groupValues[1] }
            .toList()

    /** What the shell prints for [source]: every expansion becomes a sample value, `\$` a dollar. */
    private fun rendered(source: String): String =
        source
            .replace(Regex("""\$\{[^}]*}"""), "v1")
            .replace(Regex("""(?<!\\)\$[A-Za-z_][A-Za-z0-9_]*"""), "v1")
            .replace("\\$", "$")

    private val files = quotes.map { it.file }.distinct()

    @Test
    fun `every quoted FAIL sentence is still in its script, word for word`() {
        for (q in quotes) {
            val source = q.source.replace('§', '$')
            assertTrue(source in failSentences(updatePart(q.file)), "${q.file} no longer prints: [FAIL] $source")
        }
    }

    @Test
    fun `every FAIL sentence on the update path is quoted here - a new one needs a reason decided`() {
        for (file in files) {
            val quoted = quotes.filter { it.file == file }.map { it.source.replace('§', '$') }.toSet()
            val part = updatePart(file)
            val printed = failSentences(part).toSet()
            assertTrue(printed.isNotEmpty(), "no [FAIL] lines found in $file")
            // a FAIL echo shaped differently (e.g. `>&2` after the quote) must not slip past the parser
            val markers = Regex("""\[FAIL\]\$\{NC\}""").findAll(part).count()
            assertEquals(markers, failSentences(part).size, "$file has [FAIL] lines this contract cannot read")
            assertEquals(
                emptySet<String>(),
                printed - quoted,
                "$file prints [FAIL] sentences this contract does not know",
            )
        }
    }

    @Test
    fun `every quoted sentence, as the shell prints it, maps to its reason`() {
        for (q in quotes) {
            val printed = rendered(q.source.replace('§', '$'))
            assertEquals(q.reason, UpdateReasons.fromFailLine(printed), "${q.file}: $printed")
        }
    }

    @Test
    fun `the collector and the verdict agree with the table on the real echo lines`() {
        val red = "\u001B[0;31m"
        val nc = "\u001B[0m"
        for (q in quotes) {
            val printed = rendered(q.source.replace('§', '$'))
            val c = FailLineCollector()
            c.accept("$red[FAIL]$nc $printed")
            c.accept("       Advice line under it")
            val v = UpdateVerdict.decide(null, 1, c.lines(), 0, sawComplete = false, warnings = 0)
            assertEquals(q.reason, (v as RunVerdict.Failure).reason, printed)
            assertEquals(listOf(printed, "Advice line under it"), c.lines())
        }
    }

    /**
     * The run lock's two sentences are told apart by their exit code, not by the sentence: busy is
     * exit 2 (BUSY), a lock that cannot be created exit 1 — "(is the storage full?)" is a question, not a
     * finding, so it stays a general failure (UNKNOWN), not NO_SPACE. It ends before any result file is
     * written, which is why the app reads this case by the sentence alone.
     */
    @Test
    fun `the run lock's busy sentence exits 2 and reads as BUSY, its creation failure exits 1`() {
        val text = script("update-core.sh")

        fun exitAfter(sentence: String): String {
            val at = text.indexOf("[FAIL]\${NC} $sentence")
            assertTrue(at >= 0, "update-core.sh no longer prints: $sentence")
            val next =
                text
                    .substring(text.indexOf('\n', at) + 1)
                    .lineSequence()
                    .first()
                    .trim()
            return next
        }
        val busy = "Another update, setup or tools run is in progress. Try again when it has finished."
        assertEquals("exit 2", exitAfter(busy))
        assertEquals("exit 1", exitAfter("Could not create the run lock in \$PROJECT_DIR (is the storage full?)."))
        val c = FailLineCollector().apply { accept("\u001B[0;31m[FAIL]\u001B[0m $busy") }
        val v = UpdateVerdict.decide(null, 2, c.lines(), 0, sawComplete = false, warnings = 0)
        assertEquals(UpdateReason.BUSY, (v as RunVerdict.Failure).reason)
    }

    @Test
    fun `every phrase the app matches is printed by some script - no dead or misspelled phrase`() {
        val printed = quotes.map { rendered(it.source.replace('§', '$')) }
        for ((phrase, reason) in UpdateOutput.failPhrases) {
            val users = printed.filter { it.contains(phrase) }
            assertTrue(users.isNotEmpty(), "no script prints a [FAIL] sentence containing '$phrase' ($reason)")
        }
    }

    @Test
    fun `the banner and step lines the fallback depends on are still printed by update-core sh`() {
        val core = script("update-core.sh")
        assertTrue(
            core.contains("echo -e \"\${GREEN}\${BOLD}  ${UpdateOutput.COMPLETE_LINE}\${NC}\""),
            "banner changed",
        )
        assertTrue(core.contains("echo -e \"\${BOLD}[\$1/5] \$2\${NC}\""), "step() line changed")
        val steps =
            Regex(
                """(?m)^step (\d) "([^"]+)"""",
            ).findAll(core).map { it.groupValues[1].toInt() to it.groupValues[2] }.toList()
        assertEquals((1..5).toList(), steps.map { it.first })
        assertEquals("Update Core Infrastructure", steps[2].second, "the cancel boundary [3/5] moved")
        for ((n, title) in steps) {
            assertEquals(n, UpdateStageParser.stage("\u001B[1m[$n/5] $title\u001B[0m"))
        }
        assertTrue(Regex("""\[WARN]""").containsMatchIn(core), "no [WARN] lines left to count")
    }

    @Test
    fun `ask_yn honours OA_ASSUME_YES, which the app sets`() {
        val lib = script("scripts/lib.sh")
        val body = lib.substring(lib.indexOf("ask_yn() {"))
        assertTrue(body.contains("case \"\${OA_ASSUME_YES:-}\" in"), "ask_yn no longer reads OA_ASSUME_YES")
        assertTrue(body.contains("1) echo \"\$prompt [Y/n] y (OA_ASSUME_YES=1)\"; return 0 ;;"))
        assertEquals("OA_ASSUME_YES", ManagedRunner.ASSUME_YES)
    }
}
