package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

/**
 * [ToolOutcomeStore] remembers, across app restarts, which tools last ended "files there but the
 * tool does not work". A success clears the tool; every other end leaves the record as it was.
 * What it reads back is DATA: anything that is not a valid `id=REASON` line is ignored.
 */
internal class ToolOutcomeStoreTest {
    @TempDir
    lateinit var root: File

    private val file get() = File(root, "files/tool-outcomes.conf")

    private fun store() = ToolOutcomeStore(file)

    private fun seed(text: String) {
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    private fun failure(reason: ToolFailure) = ToolVerdict.Failure(reason)

    // ── what counts as broken ───────────────────────────────────────────────

    @Test
    fun `the broken ends are exactly VERIFY_FAILED, INSTALL_FAILED and FILE_MISMATCH`() {
        assertEquals(
            setOf(ToolFailure.VERIFY_FAILED, ToolFailure.INSTALL_FAILED, ToolFailure.FILE_MISMATCH),
            brokenFailures,
        )
    }

    @Test
    fun `load of a store that was never written is empty`() {
        assertEquals(emptyMap<String, ToolFailure>(), store().load())
        assertFalse(file.exists())
    }

    // ── record ──────────────────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(value = ToolFailure::class, names = ["VERIFY_FAILED", "INSTALL_FAILED", "FILE_MISMATCH"])
    fun `a broken end is recorded for the tool`(reason: ToolFailure) {
        store().record("claude-code", failure(reason))
        assertEquals(mapOf("claude-code" to reason), store().load())
    }

    @Test
    fun `a success removes the tool's record`() {
        seed("claude-code=VERIFY_FAILED\n")
        store().record("claude-code", ToolVerdict.Success)
        assertEquals(emptyMap<String, ToolFailure>(), store().load())
    }

    @Test
    fun `a success for a tool with no record leaves nothing recorded`() {
        store().record("tmux", ToolVerdict.Success)
        assertEquals(emptyMap<String, ToolFailure>(), store().load())
    }

    @Test
    fun `a later broken end replaces the earlier reason of the same tool`() {
        val s = store()
        s.record("tmux", failure(ToolFailure.INSTALL_FAILED))
        s.record("tmux", failure(ToolFailure.VERIFY_FAILED))
        assertEquals(mapOf("tmux" to ToolFailure.VERIFY_FAILED), store().load())
    }

    @ParameterizedTest
    @EnumSource(
        value = ToolFailure::class,
        mode = EnumSource.Mode.EXCLUDE,
        names = ["VERIFY_FAILED", "INSTALL_FAILED", "FILE_MISMATCH"],
    )
    fun `any other end leaves an existing record byte for byte as it was`(reason: ToolFailure) {
        seed("claude-code=VERIFY_FAILED\ntmux=INSTALL_FAILED")
        val before = file.readBytes()
        val modified = file.lastModified()
        store().record("claude-code", failure(reason))
        store().record("ttyd", failure(reason))
        assertArrayEquals(before, file.readBytes(), "a $reason end changed the record")
        assertEquals(modified, file.lastModified())
        assertFalse(File(file.path + ".tmp").exists())
    }

    @ParameterizedTest
    @EnumSource(
        value = ToolFailure::class,
        mode = EnumSource.Mode.EXCLUDE,
        names = ["VERIFY_FAILED", "INSTALL_FAILED", "FILE_MISMATCH"],
    )
    fun `any other end creates no record and no file`(reason: ToolFailure) {
        store().record("claude-code", failure(reason))
        assertFalse(file.exists(), "a $reason end wrote the store")
        assertFalse(file.parentFile.exists(), "a $reason end created the directory")
    }

    @Test
    fun `the cancel, busy and not-run ends the brief names are among the ends that change nothing`() {
        val unchanged = ToolFailure.entries.toSet() - brokenFailures
        listOf("INTERRUPTED", "BUSY", "NOT_RUN", "SETUP_INCOMPLETE", "SCRIPT_OUTDATED").forEach {
            assertTrue(ToolFailure.valueOf(it) in unchanged, it)
        }
    }

    @Test
    fun `recording one tool keeps every other tool's record`() {
        seed("tmux=INSTALL_FAILED\ncodex-cli=FILE_MISMATCH\n")
        val s = store()
        s.record("claude-code", failure(ToolFailure.VERIFY_FAILED))
        assertEquals(
            mapOf(
                "tmux" to ToolFailure.INSTALL_FAILED,
                "codex-cli" to ToolFailure.FILE_MISMATCH,
                "claude-code" to ToolFailure.VERIFY_FAILED,
            ),
            store().load(),
        )
        s.record("tmux", ToolVerdict.Success)
        assertEquals(
            mapOf("codex-cli" to ToolFailure.FILE_MISMATCH, "claude-code" to ToolFailure.VERIFY_FAILED),
            store().load(),
        )
    }

    @Test
    fun `a missing directory is created on the first record and no temp file is left`() {
        assertFalse(file.parentFile.exists())
        store().record("tmux", failure(ToolFailure.INSTALL_FAILED))
        assertTrue(file.isFile)
        assertFalse(File(file.path + ".tmp").exists(), "the temp file was left behind")
        assertEquals(mapOf("tmux" to ToolFailure.INSTALL_FAILED), store().load())
    }

    @Test
    fun `a new store on the same file sees what an earlier one recorded, as after an app restart`() {
        ToolOutcomeStore(file).record("claude-code", failure(ToolFailure.VERIFY_FAILED))
        ToolOutcomeStore(file).record("tmux", failure(ToolFailure.INSTALL_FAILED))
        assertEquals(
            mapOf("claude-code" to ToolFailure.VERIFY_FAILED, "tmux" to ToolFailure.INSTALL_FAILED),
            ToolOutcomeStore(File(file.path)).load(),
        )
    }

    // ── what is read back is data ───────────────────────────────────────────

    @Test
    fun `garbage lines are ignored and the valid lines among them are kept`() {
        seed(
            listOf(
                "",
                "# a comment",
                "no equals sign",
                "=INSTALL_FAILED",
                "tmux=",
                "tmux=INSTALL_FAILED=extra",
                " tmux=INSTALL_FAILED",
                "ttyd=VERIFY_FAILED",
                "dufs=FILE_MISMATCH",
            ).joinToString("\n"),
        )
        assertEquals(mapOf("ttyd" to ToolFailure.VERIFY_FAILED, "dufs" to ToolFailure.FILE_MISMATCH), store().load())
    }

    @ParameterizedTest
    @ValueSource(strings = ["BUSY", "NOT_RUN", "UNKNOWN", "INTERRUPTED", "install_failed", "OK", "ok", "BROKEN"])
    fun `a reason that is not a broken end is ignored`(reason: String) {
        seed("tmux=$reason\nttyd=INSTALL_FAILED")
        assertEquals(mapOf("ttyd" to ToolFailure.INSTALL_FAILED), store().load())
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Tmux",
            "1tmux",
            "-tmux",
            "tm ux",
            "tmux_x",
            "../tmux",
            "tmux\u0000",
            "abcdefghijklmnopqrstuvwxyzabcdef", // 32 characters: one over the limit
        ],
    )
    fun `an id that is not a tool id shape is ignored`(id: String) {
        seed("$id=INSTALL_FAILED\nttyd=INSTALL_FAILED")
        assertEquals(mapOf("ttyd" to ToolFailure.INSTALL_FAILED), store().load())
    }

    @Test
    fun `an id of exactly 31 characters is accepted`() {
        val id = "a" + "b".repeat(30)
        seed("$id=VERIFY_FAILED")
        assertEquals(mapOf(id to ToolFailure.VERIFY_FAILED), store().load())
    }

    @Test
    fun `a corrupted binary file reads as empty without an exception`() {
        file.parentFile.mkdirs()
        file.writeBytes(ByteArray(512) { (it * 37 + 11).toByte() })
        assertEquals(emptyMap<String, ToolFailure>(), store().load())
        // and a record over it still works
        store().record("tmux", failure(ToolFailure.INSTALL_FAILED))
        assertEquals(mapOf("tmux" to ToolFailure.INSTALL_FAILED), store().load())
    }

    @Test
    fun `a file over 4096 bytes is not read at all, even with valid lines in it`() {
        val valid = "tmux=INSTALL_FAILED\n"
        seed(valid + "x".repeat(MAX_BYTES - valid.length + 1))
        assertEquals(MAX_BYTES + 1L, file.length())
        assertEquals(emptyMap<String, ToolFailure>(), store().load())
    }

    @Test
    fun `a file of exactly 4096 bytes is still read`() {
        val valid = "tmux=INSTALL_FAILED\n"
        seed(valid + "x".repeat(MAX_BYTES - valid.length))
        assertEquals(MAX_BYTES.toLong(), file.length())
        assertEquals(mapOf("tmux" to ToolFailure.INSTALL_FAILED), store().load())
    }

    @Test
    fun `a directory where the file should be reads as empty and a record does not throw`() {
        file.mkdirs()
        assertEquals(emptyMap<String, ToolFailure>(), store().load())
        store().record("tmux", failure(ToolFailure.INSTALL_FAILED))
        assertTrue(file.isDirectory)
        assertFalse(File(file.path + ".tmp").exists(), "the temp file was left behind")
    }

    @Test
    fun `a read-only location never throws and keeps what was there`() {
        assumeFalse(System.getProperty("user.name") == "root", "root can write a read-only directory")
        seed("tmux=INSTALL_FAILED")
        val dir = file.parentFile
        assertTrue(dir.setWritable(false))
        try {
            store().record("ttyd", failure(ToolFailure.VERIFY_FAILED))
            store().record("tmux", ToolVerdict.Success)
            assertEquals(mapOf("tmux" to ToolFailure.INSTALL_FAILED), store().load())
        } finally {
            dir.setWritable(true)
        }
    }

    @Test
    fun `a read-only parent with no directory yet never throws`() {
        assumeFalse(System.getProperty("user.name") == "root", "root can write a read-only directory")
        assertTrue(root.setWritable(false))
        try {
            store().record("tmux", failure(ToolFailure.INSTALL_FAILED))
            assertEquals(emptyMap<String, ToolFailure>(), store().load())
        } finally {
            root.setWritable(true)
        }
    }

    private companion object {
        const val MAX_BYTES = 4096
    }
}
