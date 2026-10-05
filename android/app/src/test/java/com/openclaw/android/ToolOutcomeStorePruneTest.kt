package com.openclaw.android

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File

/**
 * [ToolOutcomeStore.prune] drops the records of tools no longer on disk, and a record file that
 * exists but cannot be READ (an I/O error) is never rewritten from nothing by `record` or `prune`
 * — that would erase the other tools' records.
 */
internal class ToolOutcomeStorePruneTest {
    @TempDir
    lateinit var root: File

    private val file get() = File(root, "files/tool-outcomes.conf")
    private val tmp get() = File(file.path + ".tmp")

    private fun store() = ToolOutcomeStore(file)

    private fun seed(text: String) {
        file.parentFile.mkdirs()
        file.writeText(text)
        assertTrue(file.setLastModified(OLD_MTIME))
    }

    /** Make the record unreadable (mode 000); skipped where that does not stop a read (root). */
    private fun unreadable() {
        assumeTrue(file.setReadable(false, false), "cannot drop read permission here")
        assumeTrue(!file.canRead(), "this user reads mode-000 files (root?)")
        assumeTrue(runCatching { file.readText() }.isFailure, "a mode-000 file is still readable")
    }

    @AfterEach
    fun readableAgain() {
        if (file.exists()) file.setReadable(true, false)
    }

    private fun bytesAfterRestore(): ByteArray {
        assertTrue(file.setReadable(true, false))
        return file.readBytes()
    }

    // ── a record that cannot be read is never rewritten ─────────────────────

    @ParameterizedTest
    @EnumSource(value = ToolFailure::class, names = ["INSTALL_FAILED", "VERIFY_FAILED", "FILE_MISMATCH"])
    fun `a broken end while the record cannot be read keeps every other tool's record`(reason: ToolFailure) {
        seed("claude-code=VERIFY_FAILED\ngemini-cli=INSTALL_FAILED")
        val before = file.readBytes()
        unreadable()
        store().record("tmux", ToolVerdict.Failure(reason))
        assertArrayEquals(before, bytesAfterRestore(), "the record was rewritten from an unread file")
        assertFalse(tmp.exists(), "a temp file was left")
        assertEquals(OLD_MTIME, file.lastModified())
    }

    @Test
    fun `a success while the record cannot be read keeps the record`() {
        seed("claude-code=VERIFY_FAILED\ntmux=INSTALL_FAILED")
        val before = file.readBytes()
        unreadable()
        store().record("tmux", ToolVerdict.Success)
        assertArrayEquals(before, bytesAfterRestore())
    }

    @Test
    fun `an unreadable record loads as empty without an exception`() {
        seed("claude-code=VERIFY_FAILED")
        unreadable()
        assertEquals(emptyMap<String, ToolFailure>(), store().load())
    }

    @Test
    fun `prune while the record cannot be read leaves it as it was`() {
        seed("claude-code=VERIFY_FAILED\ntmux=INSTALL_FAILED")
        val before = file.readBytes()
        unreadable()
        store().prune(emptySet())
        assertArrayEquals(before, bytesAfterRestore(), "prune rewrote an unread record")
        assertEquals(OLD_MTIME, file.lastModified())
    }

    // ── prune ───────────────────────────────────────────────────────────────

    @Test
    fun `prune drops only the tools not on disk and keeps the rest`() {
        seed("claude-code=VERIFY_FAILED\ntmux=INSTALL_FAILED\ncodex-cli=FILE_MISMATCH")
        store().prune(setOf("tmux", "codex-cli", "ttyd"))
        assertEquals(
            mapOf("tmux" to ToolFailure.INSTALL_FAILED, "codex-cli" to ToolFailure.FILE_MISMATCH),
            store().load(),
        )
        assertFalse(file.readText().contains("claude-code"), file.readText())
        assertFalse(tmp.exists())
    }

    @Test
    fun `prune with nothing on disk empties the record`() {
        seed("claude-code=VERIFY_FAILED\ntmux=INSTALL_FAILED")
        store().prune(emptySet())
        assertEquals(emptyMap<String, ToolFailure>(), store().load())
        assertEquals("", file.readText())
    }

    @Test
    fun `prune with nothing to drop does not rewrite the file`() {
        seed("claude-code=VERIFY_FAILED\ntmux=INSTALL_FAILED")
        val before = file.readBytes()
        store().prune(setOf("tmux", "claude-code", "ttyd"))
        assertEquals(OLD_MTIME, file.lastModified(), "an unchanged record was rewritten")
        assertArrayEquals(before, file.readBytes())
        assertFalse(tmp.exists())
    }

    @Test
    fun `prune of a record that was never written creates no file`() {
        store().prune(setOf("tmux"))
        store().prune(emptySet())
        assertFalse(file.exists())
        assertFalse(file.parentFile.exists(), "prune created the directory")
    }

    @Test
    fun `prune of an empty record with nothing on disk does not rewrite it`() {
        seed("")
        store().prune(emptySet())
        assertEquals(OLD_MTIME, file.lastModified())
    }

    @Test
    fun `garbage lines are dropped when prune rewrites, kept when it does not`() {
        // Nothing to drop: the file (garbage and all) is left alone
        seed("tmux=INSTALL_FAILED\nnot a line\nclaude-code=NOPE")
        store().prune(setOf("tmux"))
        assertEquals("tmux=INSTALL_FAILED\nnot a line\nclaude-code=NOPE", file.readText())
        // Something to drop: only valid, kept records are written back
        seed("tmux=INSTALL_FAILED\nnot a line\nttyd=VERIFY_FAILED")
        store().prune(setOf("tmux"))
        assertEquals("tmux=INSTALL_FAILED", file.readText())
    }

    @Test
    fun `a record made after a prune is kept and the pruned tool does not come back`() {
        seed("claude-code=VERIFY_FAILED\ntmux=INSTALL_FAILED")
        store().prune(setOf("tmux"))
        store().record("ttyd", ToolVerdict.Failure(ToolFailure.VERIFY_FAILED))
        assertEquals(
            mapOf("tmux" to ToolFailure.INSTALL_FAILED, "ttyd" to ToolFailure.VERIFY_FAILED),
            store().load(),
        )
    }

    private companion object {
        const val OLD_MTIME = 1_000_000_000_000L
    }
}
