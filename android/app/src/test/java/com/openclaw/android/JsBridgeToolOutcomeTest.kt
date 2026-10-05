package com.openclaw.android

import com.google.gson.Gson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File

/**
 * A tool whose files are on disk but whose last install ended "does not work" is reported by
 * `getInstalledTools` as `broken: true` — also by a NEW JsBridge (the app was restarted), because
 * the outcome is kept in `<filesDir>/tool-outcomes.conf`. Only a success clears it; a cancel, a
 * busy installer or a setup that is not done change nothing. Runs against the fake script of
 * [JsBridgeToolInstallFixture].
 */
internal class JsBridgeToolOutcomeTest : JsBridgeToolInstallFixture() {
    private fun JsBridge.installed(): List<Map<String, Any?>> {
        @Suppress("UNCHECKED_CAST")
        return Gson().fromJson(getInstalledTools(), List::class.java) as List<Map<String, Any?>>
    }

    /** id → the `broken` value as sent to the page (a JSON boolean, never a string). */
    private fun JsBridge.brokenById(): Map<String, Any?> = installed().associate { it["id"] as String to it["broken"] }

    private fun tmuxOnDisk() {
        File(prefix, "bin").mkdirs()
        File(prefix, "bin/tmux").writeText("")
    }

    private fun seedRecord(text: String) {
        appFilesDir.mkdirs()
        outcomesFile.writeText(text)
    }

    /** The script leaves the tool's files and reports [outcome] for this run. */
    private fun resultScript(
        outcome: String,
        createBinary: Boolean = true,
    ) = fakeScript(
        (if (createBinary) "mkdir -p \"${'$'}PREFIX/bin\" && : > \"${'$'}PREFIX/bin/tmux\"\n" else "") +
            """
            |printf 'schema=1\nrun=%s\ntmux=$outcome\nexit=0\n' "${'$'}(date +%s)" > "${'$'}R"
            |exit 0
            """.trimMargin(),
    )

    private fun runTmux(): EmittedEvent {
        val (bridge, web) = page()
        bridge.installTool("tmux")
        return finalEvent(web)
    }

    // ── the shape the page reads ────────────────────────────────────────────

    @Test
    fun `every getInstalledTools entry carries id, name, version and a boolean broken`() {
        tmuxOnDisk()
        val (bridge, _) = page()
        val entries = bridge.installed()
        assertEquals(listOf("tmux"), entries.map { it["id"] })
        entries.forEach {
            assertEquals(setOf("id", "name", "version", "broken"), it.keys)
            assertEquals(false, it["broken"], "broken is not the JSON boolean false: ${bridge.getInstalledTools()}")
        }
        assertTrue(bridge.getInstalledTools().contains("\"broken\":false"), bridge.getInstalledTools())
    }

    @Test
    fun `a record for a tool that is not on disk does not list the tool`() {
        seedRecord("tmux=INSTALL_FAILED\nclaude-code=VERIFY_FAILED")
        val (bridge, _) = page()
        assertEquals(emptyList<Map<String, Any?>>(), bridge.installed())
    }

    @Test
    fun `a record is shown as broken only while the tool is on disk, and is dropped once the tool is gone`() {
        tmuxOnDisk()
        seedRecord("tmux=INSTALL_FAILED")
        val (bridge, _) = page()
        assertEquals(mapOf("tmux" to true), bridge.brokenById())

        // The tool is removed (or the system re-installed): it is not listed and its record is pruned
        File(prefix, "bin/tmux").delete()
        assertFalse("tmux" in bridge.brokenById())
        assertEquals("", outcomesFile.readText(), "the record of a tool no longer on disk was kept")

        // The files come back by some other route: the old "broken" is not inherited
        tmuxOnDisk()
        assertEquals(mapOf("tmux" to false), bridge.brokenById())
    }

    @Test
    fun `a garbage outcomes file shows every tool on disk as not broken`() {
        tmuxOnDisk()
        seedRecord("tmux=BROKEN\n\u0000\u0001garbage\ntmux INSTALL_FAILED")
        val (bridge, _) = page()
        assertEquals(mapOf("tmux" to false), bridge.brokenById())
    }

    // ── a broken end is remembered, across a restart ────────────────────────

    @ParameterizedTest
    @CsvSource("failed:install, INSTALL_FAILED", "failed:verify, VERIFY_FAILED")
    fun `a failed install that leaves the files is broken, also for a new bridge after a restart`(
        outcome: String,
        reason: ToolFailure,
    ) {
        marker()
        resultScript(outcome)
        val (bridge, web) = page()
        bridge.installTool("tmux")
        assertFailed(finalEvent(web), reason)

        assertTrue(File(prefix, "bin/tmux").exists(), "the fake did not leave the files")
        assertEquals(mapOf("tmux" to true), bridge.brokenById())
        assertEquals("tmux=${reason.name}", outcomesFile.readText())

        // The app is restarted: a new JsBridge, no event history, the same files dir
        val (restarted, _) = page()
        assertEquals(mapOf("tmux" to true), restarted.brokenById())
    }

    @Test
    fun `ok without the files on disk is recorded as FILE_MISMATCH, not listed, and pruned by the next listing`() {
        marker()
        resultScript("ok", createBinary = false)
        val (bridge, web) = page()
        bridge.installTool("tmux")
        assertFailed(finalEvent(web), ToolFailure.FILE_MISMATCH)
        assertEquals("tmux=FILE_MISMATCH", outcomesFile.readText())
        assertEquals(emptyMap<String, Any?>(), bridge.brokenById())
        // The listing found no tmux on disk, so the record went with it
        assertFalse(outcomesFile.readText().contains("tmux"), outcomesFile.readText())
        tmuxOnDisk()
        assertEquals(mapOf("tmux" to false), page().first.brokenById())
    }

    @Test
    fun `a FILE_MISMATCH record is shown as broken when the files are on disk at the next listing`() {
        marker()
        resultScript("ok", createBinary = false)
        val (bridge, web) = page()
        bridge.installTool("tmux")
        assertFailed(finalEvent(web), ToolFailure.FILE_MISMATCH)
        tmuxOnDisk()
        assertEquals(mapOf("tmux" to true), page().first.brokenById())
        assertEquals("tmux=FILE_MISMATCH", outcomesFile.readText())
    }

    @Test
    fun `getInstalledTools prunes only the records of tools not on disk, and leaves the file alone otherwise`() {
        tmuxOnDisk()
        seedRecord("tmux=INSTALL_FAILED\nclaude-code=VERIFY_FAILED")
        val (bridge, _) = page()
        assertEquals(mapOf("tmux" to true), bridge.brokenById())
        assertEquals("tmux=INSTALL_FAILED", outcomesFile.readText())

        // Nothing left to prune: the file is not rewritten
        outcomesFile.setLastModified(OLD_MTIME)
        assertEquals(mapOf("tmux" to true), bridge.brokenById())
        assertEquals(OLD_MTIME, outcomesFile.lastModified(), "a listing with nothing to prune rewrote the record")
    }

    private companion object {
        const val OLD_MTIME = 1_000_000_000_000L
    }

    @Test
    fun `a later successful install clears broken, for this bridge and after a restart`() {
        marker()
        resultScript("failed:install")
        assertFailed(runTmux(), ToolFailure.INSTALL_FAILED)
        assertEquals(mapOf("tmux" to true), page().first.brokenById())

        resultScript("ok")
        val (bridge, web) = page()
        bridge.installTool("tmux")
        assertEquals("done", finalEvent(web).data["phase"], web.scripts.toString())

        assertEquals(mapOf("tmux" to false), bridge.brokenById())
        assertEquals(mapOf("tmux" to false), page().first.brokenById())
        assertFalse(outcomesFile.readText().contains("tmux"), outcomesFile.readText())
    }

    @Test
    fun `a broken end of one tool keeps another tool's record`() {
        seedRecord("claude-code=VERIFY_FAILED")
        marker()
        resultScript("failed:install")
        assertFailed(runTmux(), ToolFailure.INSTALL_FAILED)
        assertEquals(
            setOf("claude-code=VERIFY_FAILED", "tmux=INSTALL_FAILED"),
            outcomesFile.readLines().toSet(),
        )
    }

    // ── ends that change nothing ────────────────────────────────────────────

    @Test
    fun `a cancelled install keeps the broken record as it was`() {
        tmuxOnDisk()
        seedRecord("tmux=INSTALL_FAILED")
        val before = outcomesFile.readText()
        marker()
        blockingScript()
        fakeSignal()
        val (bridge, web) = page()
        bridge.installTool("tmux")
        awaitRealCall(web)
        assertTrue(TestWait.until { lockPidFile.isFile })
        bridge.cancelToolInstall()
        val end = finalEvent(web)
        assertEquals("cancelled", end.data["phase"], web.scripts.toString())

        assertEquals(before, outcomesFile.readText(), "a cancel changed the record")
        assertEquals(mapOf("tmux" to true), bridge.brokenById())
        assertEquals(mapOf("tmux" to true), page().first.brokenById())
    }

    @Test
    fun `a cancelled install creates no record`() {
        tmuxOnDisk()
        marker()
        blockingScript()
        fakeSignal()
        val (bridge, web) = page()
        bridge.installTool("tmux")
        awaitRealCall(web)
        assertTrue(TestWait.until { lockPidFile.isFile })
        bridge.cancelToolInstall()
        assertEquals("cancelled", finalEvent(web).data["phase"], web.scripts.toString())

        assertFalse(outcomesFile.exists(), "a cancel wrote a record")
        assertEquals(mapOf("tmux" to false), bridge.brokenById())
    }

    @Test
    fun `a busy installer keeps the broken record as it was`() {
        tmuxOnDisk()
        seedRecord("tmux=VERIFY_FAILED")
        val before = outcomesFile.readText()
        marker()
        fakeScript("exit 2")
        assertFailed(runTmux(), ToolFailure.BUSY)
        assertEquals(before, outcomesFile.readText())
        assertEquals(mapOf("tmux" to true), page().first.brokenById())
    }

    @Test
    fun `a busy installer with no earlier record creates no record`() {
        tmuxOnDisk()
        marker()
        fakeScript("exit 2")
        assertFailed(runTmux(), ToolFailure.BUSY)
        assertFalse(outcomesFile.exists(), "a busy end wrote a record")
    }

    @Test
    fun `an install refused for an unfinished setup keeps the record as it was`() {
        tmuxOnDisk()
        seedRecord("tmux=INSTALL_FAILED")
        fakeScript("exit 0")
        assertFailed(runTmux(), ToolFailure.SETUP_INCOMPLETE)
        assertEquals("tmux=INSTALL_FAILED", outcomesFile.readText())
        assertEquals(mapOf("tmux" to true), page().first.brokenById())
    }

    @Test
    fun `an older script is SCRIPT_OUTDATED and keeps the record as it was`() {
        tmuxOnDisk()
        seedRecord("tmux=INSTALL_FAILED")
        marker()
        val old = "echo 'Post-setup already completed.'; exit 0"
        fakeScript(body = old, list = old)
        assertFailed(runTmux(), ToolFailure.SCRIPT_OUTDATED)
        assertEquals("tmux=INSTALL_FAILED", outcomesFile.readText())
    }

    @Test
    fun `an exit 0 with no result is NOT_RUN and keeps the record as it was`() {
        tmuxOnDisk()
        seedRecord("tmux=INSTALL_FAILED")
        marker()
        fakeScript("exit 0")
        assertFailed(runTmux(), ToolFailure.NOT_RUN)
        assertEquals("tmux=INSTALL_FAILED", outcomesFile.readText())
    }
}
