package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * `last-run.conf`: one line per kind, replaced atomically, never rewritten from nothing after a read
 * error, and a line that does not validate is ignored (it is data the app wrote, but it is read back
 * after app updates and could be damaged).
 */
internal class RunOutcomeStoreTest {
    @TempDir
    lateinit var dir: File

    private val file get() = File(dir, "files/last-run.conf")

    private fun store() = RunOutcomeStore(file)

    private val fail = RunVerdict.Failure(UpdateReason.NO_SPACE, 1, "Not enough free storage\n  free 2 GB", 2)

    @Test
    fun `nothing recorded loads as empty`() {
        assertEquals(emptyMap<String, RunOutcome>(), store().load())
    }

    @Test
    fun `a success and a failure round-trip, the later one of a kind replacing the earlier`() {
        val s = store()
        s.record(RunKinds.UPDATE, 100, RunVerdict.Success(0, 3))
        assertEquals(RunOutcome("UPDATE", 100, true, null, 0, 3, ""), s.load()["UPDATE"])
        s.record(RunKinds.UPDATE, 200, fail)
        assertEquals(
            RunOutcome("UPDATE", 200, false, UpdateReason.NO_SPACE, 1, 2, "Not enough free storage /   free 2 GB"),
            store().load()["UPDATE"],
        )
        assertEquals(1, file.readLines().size, "one line per kind")
    }

    @Test
    fun `an unknown kind is not recorded`() {
        store().record("INSTALL", 1, RunVerdict.Success(0, 0))
        store().record("", 1, RunVerdict.Success(0, 0))
        assertFalse(file.exists())
    }

    @Test
    fun `a failure that never ran keeps an empty exit`() {
        store().record(RunKinds.UPDATE, 5, RunVerdict.Failure(UpdateReason.UNKNOWN, null, "", 0))
        val o = store().load().getValue("UPDATE")
        assertNull(o.exit)
        assertEquals(UpdateReason.UNKNOWN, o.reason)
    }

    @Test
    fun `detail is one line, control characters blanked, cut to 200, and a pipe survives`() {
        val detail = "a|b\nc\u0007d\te" + "x".repeat(300)
        store().record(RunKinds.UPDATE, 5, RunVerdict.Failure(UpdateReason.DOWNLOAD, 1, detail, 0))
        val o = store().load().getValue("UPDATE")
        assertEquals(RunOutcomeStore.MAX_DETAIL, o.detail.length)
        assertTrue(o.detail.startsWith("a|b / c d e"), o.detail)
        assertTrue(o.detail.none { it.isISOControl() })
    }

    @Test
    fun `the file is replaced through a temp file and no temp file is left`() {
        store().record(RunKinds.UPDATE, 1, RunVerdict.Success(0, 0))
        assertFalse(File(file.path + ".tmp").exists())
        assertEquals(listOf("UPDATE|1|success||0|0|"), file.readLines())
    }

    @Test
    fun `a write that cannot complete leaves the previous record whole`() {
        store().record(RunKinds.UPDATE, 1, RunVerdict.Success(0, 0))
        val before = file.readText()
        // the temp file cannot be created: the write fails before the record is touched
        File(file.path + ".tmp").mkdirs()
        store().record(RunKinds.UPDATE, 2, fail)
        assertEquals(before, file.readText())
        assertEquals(1L, store().load().getValue("UPDATE").atSec)
    }

    @Test
    fun `a read error never leads to a rewrite from nothing`() {
        file.parentFile.mkdirs()
        file.writeText("UPDATE|1|success||0|0|\n")
        assertTrue(file.setReadable(false), "cannot make the record unreadable here")
        try {
            store().record(RunKinds.UPDATE, 2, fail)
        } finally {
            file.setReadable(true)
        }
        assertEquals("UPDATE|1|success||0|0|\n", file.readText())
    }

    @Test
    fun `garbage lines are ignored and a valid line among them is kept`() {
        file.parentFile.mkdirs()
        file.writeText(
            listOf(
                "",
                "garbage",
                "UPDATE|1|success",
                "INSTALL|1|success||0|0|",
                "UPDATE|x|success||0|0|",
                "UPDATE|-1|success||0|0|",
                "UPDATE|1|maybe||0|0|",
                "UPDATE|1|success|NO_SPACE|0|0|",
                "UPDATE|1|success||1|0|",
                "UPDATE|1|failure||1|0|",
                "UPDATE|1|failure|NOT_A_REASON|1|0|",
                "UPDATE|1|failure|DOWNLOAD|one|0|",
                "UPDATE|1|failure|DOWNLOAD|1|-2|",
                "UPDATE|1|failure|DOWNLOAD|1|0|" + "d".repeat(201),
                "UPDATE|1|failure|DOWNLOAD|1|0|bell\u0007",
            ).joinToString("\n"),
        )
        assertEquals(emptyMap<String, RunOutcome>(), store().load())
        file.appendText("\nUPDATE|9|failure|CHECKSUM|1|0|bad sum\n")
        assertEquals(RunOutcome("UPDATE", 9, false, UpdateReason.CHECKSUM, 1, 0, "bad sum"), store().load()["UPDATE"])
    }

    @Test
    fun `a file over 4096 bytes is not read`() {
        file.parentFile.mkdirs()
        file.writeText("UPDATE|9|failure|CHECKSUM|1|0|bad sum\n" + "#".repeat(4_100))
        assertEquals(emptyMap<String, RunOutcome>(), store().load())
    }

    @Test
    fun `a record is readable by a new store - it survives an app restart`() {
        store().record(RunKinds.UPDATE, 42, RunVerdict.Success(0, 1))
        assertEquals(42L, RunOutcomeStore(File(file.path)).load().getValue("UPDATE").atSec)
    }
}
