package com.openclaw.android

import com.google.gson.Gson
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * `post-setup-result.conf` read as DATA ([SetupResultParser]: the limits of [ToolResultParser]) and
 * `getSetupResult()` ([ManagedSetup.resultState]): the file's facts, `interrupted` (no `exit` and
 * nothing alive that could still write it — checked against a fake `/proc`), the app reason and
 * `managed`.
 */
internal class SetupResultParserTest {
    @TempDir
    lateinit var root: File

    private lateinit var home: File
    private lateinit var proc: FakeProc

    private val resultFile get() = File(home, ManagedSetup.RESULT)

    @BeforeEach
    fun setUp() {
        ManagedRunWorld.resetShared()
        home = File(root, "home").apply { mkdirs() }
        File(home, ".openclaw-android").mkdirs()
        proc = FakeProc(File(root, "proc"))
    }

    @AfterEach
    fun tearDown() = ManagedRunWorld.resetShared()

    private fun parse(vararg lines: String) = SetupResultParser.parse(lines.joinToString("\n"))

    // ── the file ────────────────────────────────────────────────────────────

    @Test
    fun `a final failure file gives every key`() {
        val r =
            parse(
                "schema=1",
                "run=1700000000",
                "stage=3",
                "error=free-space",
                "need_mb=2000",
                "have_mb=640",
                "warn=tools:tmux,clawdhub",
                "exit=1",
            )
        assertNotNull(r)
        assertEquals(1_700_000_000L, r!!.runEpoch)
        assertEquals("3", r.stage)
        assertEquals("free-space", r.error)
        assertEquals(1, r.exit)
        assertEquals(SetupFacts(2000, 640, listOf("tools:tmux", "clawdhub")), r.facts)
    }

    @Test
    fun `a file of a run that goes on has no exit`() {
        val r = parse("schema=1", "run=5", "stage=4")!!
        assertNull(r.exit)
        assertNull(r.error)
        assertEquals("4", r.stage)
    }

    @Test
    fun `schema must be 1 and run a non-negative number`() {
        assertNull(parse("schema=2", "run=5", "exit=0"))
        assertNull(parse("run=5", "exit=0"))
        assertNull(parse("schema=1", "exit=0"))
        assertNull(parse("schema=1", "run=abc", "exit=0"))
        assertNull(parse("schema=1", "run=-5", "exit=0"))
        assertNull(parse("schema=1", "run=", "exit=0"))
    }

    @Test
    fun `a repeated key rejects the whole file`() {
        assertNull(parse("schema=1", "run=5", "exit=0", "exit=1"))
        assertNull(parse("schema=1", "run=5", "stage=done", "stage=done"))
        assertNull(parse("schema=1", "run=5", "run=6"))
        assertNull(parse("schema=1", "run=5", "error=deb", "error=deb"))
    }

    @Test
    fun `unknown keys are ignored and do not change the verdict keys`() {
        val r = parse("schema=1", "run=5", "later_key=whatever", "stage=done", "exit=0", "x.y-z=1")!!
        assertEquals(SetupResultFile(5, "done", null, 0, SetupFacts()), r)
    }

    @Test
    fun `malformed lines are skipped, not trusted`() {
        val r =
            parse(
                "schema=1",
                "run=5",
                "no equals sign",
                "=value",
                "bad key=1",
                "error=dé\u0007b",
                "stage=done\r",
                "exit=0",
            )!!
        assertEquals("done", r.stage, "CRLF line endings are accepted")
        assertNull(r.error, "a line with a control character is not trusted")
        assertEquals(0, r.exit)
    }

    @Test
    fun `a line longer than 200 characters is dropped, at 200 it is kept`() {
        val at200 = "error=" + "a".repeat(194)
        val over = "error=" + "a".repeat(195)
        assertEquals(200, at200.length)
        assertEquals("a".repeat(194), parse("schema=1", "run=5", at200)!!.error)
        assertNull(parse("schema=1", "run=5", over)!!.error, "a 201-character line is dropped")
        val longRun = "run=" + "1".repeat(197)
        assertNull(parse("schema=1", longRun), "the only run= line is too long, so there is no run")
        val okWarn = "warn=" + (1..40).joinToString(",") { "w$it" }
        assertTrue(okWarn.length <= 200)
        assertEquals(SetupResultParser.MAX_WARN, parse("schema=1", "run=5", okWarn)!!.facts.warn.size)
    }

    @Test
    fun `more than 16K characters rejects the file, exactly 16K is read`() {
        val head = "schema=1\nrun=5\nstage=done\nexit=0\n"
        val filler = StringBuilder(head)
        var i = 0
        while (filler.length < ToolResultParser.MAX_CHARS - 20) filler.append("k${i++}=v\n")
        while (filler.length < ToolResultParser.MAX_CHARS) filler.append('\n')
        assertEquals(ToolResultParser.MAX_CHARS, filler.length)
        assertNotNull(SetupResultParser.parse(filler.toString()))
        assertNull(SetupResultParser.parse(filler.toString() + "\n"))
    }

    @Test
    fun `stage is 1 to 7 or done, anything else is null`() {
        for (good in listOf(
            "1",
            "4",
            "7",
            "done",
        )) {
            assertEquals(good, parse("schema=1", "run=5", "stage=$good")!!.stage)
        }
        for (bad in listOf("0", "8", "07", "DONE", "", "3a")) {
            assertNull(parse("schema=1", "run=5", "stage=$bad")!!.stage, bad)
        }
    }

    @Test
    fun `exit and sizes that are not numbers are absent`() {
        val r = parse("schema=1", "run=5", "exit=abc", "need_mb=-5", "have_mb=12x")!!
        assertNull(r.exit)
        assertEquals(SetupFacts(), r.facts)
    }

    @Test
    fun `an empty error is no error`() {
        assertNull(parse("schema=1", "run=5", "error=", "exit=0")!!.error)
    }

    @Test
    fun `warn keeps well formed ids once each, in order, at most 20`() {
        val r = parse("schema=1", "run=5", "warn=tools:tmux, clawdhub ,bad id,x/y,tools:tmux,,oa-cli,-lead")!!
        assertEquals(listOf("tools:tmux", "clawdhub", "oa-cli"), r.facts.warn)
        val many = parse("schema=1", "run=5", "warn=" + (1..25).joinToString(",") { "w$it" })!!
        assertEquals((1..20).map { "w$it" }, many.facts.warn)
    }

    @Test
    fun `read gives null for no file, a folder, an oversized file`() {
        assertNull(SetupResultParser.read(resultFile))
        resultFile.mkdirs()
        assertNull(SetupResultParser.read(resultFile))
        resultFile.delete()
        resultFile.writeText("schema=1\nrun=5\nexit=0\n" + "x".repeat(ToolResultParser.MAX_CHARS))
        assertNull(SetupResultParser.read(resultFile))
        resultFile.writeText("schema=1\nrun=5\nexit=0\n")
        assertEquals(0, SetupResultParser.read(resultFile)!!.exit)
    }

    // ── getSetupResult() ────────────────────────────────────────────────────

    private fun state(managed: Boolean = true): Map<String, Any?> =
        ManagedSetup.resultState(home, ProcScan(proc.dir), managed)

    @Suppress("UNCHECKED_CAST")
    private fun json(map: Map<String, Any?>): Map<String, Any?> =
        Gson().fromJson(Gson().toJson(map), Map::class.java) as Map<String, Any?>

    private val allKeys =
        setOf("present", "stage", "error", "reason", "needMb", "haveMb", "warn", "exit", "interrupted", "managed")

    @Test
    fun `no file - present false, nothing interrupted, managed passed through, the same key set`() {
        val s = state(managed = true)
        assertEquals(allKeys, s.keys)
        assertEquals(false, s["present"])
        assertEquals(false, s["interrupted"])
        assertNull(s["reason"])
        assertEquals(true, s["managed"])
        assertEquals(false, state(managed = false)["managed"])
        // as the page receives it (Gson drops nulls)
        assertEquals(setOf("present", "warn", "interrupted", "managed"), json(s).keys)
    }

    @Test
    fun `a finished failure gives its reason and sizes and is not interrupted`() {
        resultFile.writeText("schema=1\nrun=5\nstage=1\nerror=free-space\nneed_mb=2000\nhave_mb=10\nexit=1\n")
        val s = state()
        assertEquals(true, s["present"])
        assertEquals("NO_SPACE", s["reason"])
        assertEquals("free-space", s["error"])
        assertEquals(2000, s["needMb"])
        assertEquals(10, s["haveMb"])
        assertEquals(1, s["exit"])
        assertEquals("1", s["stage"])
        assertEquals(false, s["interrupted"])
    }

    @Test
    fun `every app reason of an error code is named by getSetupResult`() {
        val table =
            mapOf(
                "index-download" to "NETWORK",
                "index-mismatch" to "VERIFY_FAILED",
                "index-12" to "VERIFY_FAILED",
                "node-checksum" to "VERIFY_FAILED",
                "npm-openclaw" to "INSTALL_FAILED",
                "env" to "ENV",
                "interrupted" to "INTERRUPTED",
                "unknown" to "UNKNOWN",
                "new-code" to "UNKNOWN",
            )
        table.forEach { (code, reason) ->
            resultFile.writeText("schema=1\nrun=5\nstage=2\nerror=$code\nexit=1\n")
            assertEquals(reason, state()["reason"], code)
        }
    }

    @Test
    fun `a file without exit and nothing alive is interrupted with reason INTERRUPTED`() {
        resultFile.writeText("schema=1\nrun=5\nstage=4\n")
        val s = state()
        assertEquals(true, s["interrupted"])
        assertEquals("INTERRUPTED", s["reason"])
        assertNull(s["exit"])
        assertEquals("4", s["stage"])
    }

    @Test
    fun `a file without exit while a post-setup process runs is not interrupted (a run goes on)`() {
        resultFile.writeText("schema=1\nrun=5\nstage=4\n")
        proc.add(4242, 1, listOf("bash", "/data/home/.openclaw-android/post-setup.sh"))
        val s = state()
        assertEquals(false, s["interrupted"])
        assertNull(s["reason"])
    }

    @Test
    fun `an app run's own post-setup process (with the token) also counts as alive`() {
        resultFile.writeText("schema=1\nrun=5\nstage=2\n")
        proc.add(4243, 1, listOf("bash", "/h/.openclaw-android/post-setup.sh"), listOf("OA_APP_RUN_TOKEN=abc"))
        assertEquals(false, state()["interrupted"])
    }

    @Test
    fun `a file without exit while an update or tools run is alive is not interrupted`() {
        resultFile.writeText("schema=1\nrun=5\nstage=2\n")
        proc.add(51, 1, listOf("bash", "/h/.openclaw-android/oa", "--update"))
        assertEquals(false, state()["interrupted"])
        proc.remove(51)
        proc.add(52, 1, listOf("bash", "/h/.openclaw-android/post-setup.sh", "--tools-only", "tmux"))
        assertEquals(false, state()["interrupted"])
    }

    @Test
    fun `a viewer of the script or a zombie is not a live run`() {
        resultFile.writeText("schema=1\nrun=5\nstage=2\n")
        proc.add(61, 1, listOf("less", "/h/.openclaw-android/post-setup.sh"))
        proc.add(62, 1, listOf("bash", "/h/.openclaw-android/post-setup.sh"))
        proc.zombie(62)
        assertEquals(true, state()["interrupted"])
    }

    @Test
    fun `a file without exit while the app's own SETUP run holds the guard is not interrupted`() {
        resultFile.writeText("schema=1\nrun=5\nstage=2\n")
        assertTrue(ManagedRunGuard.tryStart(RunKinds.SETUP, 5, "own"))
        assertEquals(false, state()["interrupted"])
        ManagedRunGuard.finish(RunVerdict.Success(0, 0))
        assertEquals(true, state()["interrupted"], "after the run ended the file is an interrupted one")
    }

    @Test
    fun `an untrusted file (repeated key) reads as no file`() {
        resultFile.writeText("schema=1\nrun=5\nexit=0\nexit=0\n")
        val s = state()
        assertEquals(false, s["present"])
        assertEquals(false, s["interrupted"])
    }

    @Test
    fun `warn reaches the page as a list`() {
        resultFile.writeText("schema=1\nrun=5\nstage=done\nwarn=tools:tmux,compat-js\nexit=0\n")
        val s = json(state())
        assertEquals(listOf("tools:tmux", "compat-js"), s["warn"])
        assertFalse(s.containsKey("reason"))
        assertEquals(0.0, s["exit"])
    }
}
