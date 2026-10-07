package com.openclaw.android

import android.content.Context
import android.content.res.AssetManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * `BootstrapManager.startSetup` step 4 — swapping the new prefix in — on the JVM. The REAL
 * download-free path runs: the archive comes from the APK assets (a zip built here), it is
 * extracted into the staging dir, paths are fixed and apt configured, then the old `usr` (complete
 * or a partial leftover of an attempt that died mid-delete) is removed and the staging dir renamed.
 * Only what follows the swap and needs a device or the network (the post-setup.sh download, the www
 * sync, the dpkg wrapper, preferences) is stubbed. A failure before the swap must leave the
 * existing `usr` exactly as it was.
 */
internal class BootstrapPrefixSwapTest {
    @TempDir
    lateinit var filesDir: File

    private lateinit var assets: AssetManager
    private lateinit var manager: BootstrapManager
    private var archive: () -> InputStream = { ByteArrayInputStream(zip(NEW_FILES)) }
    private val readOnly = mutableListOf<File>()

    private val usr get() = File(filesDir, "usr")
    private val staging get() = File(filesDir, "usr-staging")

    @BeforeEach
    fun setUp() {
        assets = mockk()
        every { assets.open("bootstrap-aarch64.zip") } answers { archive() }
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.assets } returns assets
        every { context.packageName } returns "com.openclaw.android"
        manager = spyk(BootstrapManager(context), recordPrivateCalls = true)
        // After the swap: network, device and preference work, not under test here
        every { manager["copyAssetScripts"]() } returns Unit
        every { manager["setupTermuxExec"]() } returns Unit
        every { manager.syncWwwFromAssets() } returns Unit
        every { manager.saveCurrentVersionCode() } returns Unit
    }

    @AfterEach
    fun writableAgain() {
        readOnly.forEach { it.setWritable(true) }
    }

    private fun setup(): List<Float> {
        val progress = mutableListOf<Float>()
        runBlocking { manager.startSetup { p, _ -> progress.add(p) } }
        return progress
    }

    /** Every file under [dir] (relative path → content); directories as "path/". */
    private fun snapshot(dir: File): Map<String, String> =
        dir
            .walkTopDown()
            .filter { it != dir }
            .associate { f ->
                val rel = f.relativeTo(dir).path
                if (f.isDirectory) "$rel/" to "" else rel to f.readText()
            }

    private fun write(
        root: File,
        files: Map<String, String>,
    ) = files.forEach { (path, text) -> File(root, path).apply { parentFile.mkdirs() }.writeText(text) }

    private fun assertNewPrefix() {
        assertEquals("#!new sh\n", File(usr, "bin/sh").readText())
        assertEquals("new lib\n", File(usr, "lib/libnew.so").readText())
        assertTrue(File(usr, "etc/apt/apt.conf").readText().contains(usr.absolutePath), "apt was not configured")
        assertTrue(manager.isInstalled())
        assertFalse(staging.exists(), "the staging dir was left behind")
    }

    // ── (a)(b)(c) what is in place before the install ───────────────────────

    @Test
    fun `an incomplete leftover usr (no bin-sh, other files) is replaced - its files are gone`() {
        write(usr, mapOf("lib/stale.so" to "half-deleted", "share/doc/old.txt" to "old", "var/lib/dpkg/status" to "x"))
        assertFalse(manager.isInstalled(), "the leftover counts as installed")
        val progress = setup()
        assertNewPrefix()
        assertFalse(File(usr, "lib/stale.so").exists())
        assertFalse(File(usr, "share/doc/old.txt").exists())
        assertEquals(1f, progress.last())
    }

    @Test
    fun `a complete old usr is replaced, nothing of it is kept`() {
        write(usr, mapOf("bin/sh" to "#!old sh\n", "bin/node" to "old node", "lib/node_modules/npm-global/x.js" to "g"))
        assertTrue(manager.isInstalled())
        setup()
        assertNewPrefix()
        assertFalse(File(usr, "bin/node").exists(), "an old binary survived the reinstall")
        assertFalse(File(usr, "lib/node_modules").exists(), "npm globals survived the reinstall")
    }

    @Test
    fun `with no usr at all the archive is installed fresh`() {
        assertFalse(usr.exists())
        setup()
        assertNewPrefix()
        assertEquals(snapshot(usr).keys.filter { !it.endsWith("/") }.toSet(), EXPECTED_FILES)
    }

    @Test
    fun `an empty usr directory left behind is replaced too`() {
        usr.mkdirs()
        setup()
        assertNewPrefix()
    }

    @Test
    fun `the home folder is not touched by the swap`() {
        write(File(filesDir, "home"), mapOf(".bashrc" to "mine", ".openclaw/projects/a.txt" to "data"))
        write(usr, mapOf("bin/sh" to "#!old sh\n"))
        val before = snapshot(File(filesDir, "home"))
        setup()
        // setupDirectories may add directories under home, never remove or change a file
        val after = snapshot(File(filesDir, "home"))
        before.forEach { (k, v) -> assertEquals(v, after[k], "home/$k changed") }
    }

    // ── (d) a failure before the swap leaves usr exactly as it was ──────────

    private val complete = mapOf("bin/sh" to "#!old sh\n", "bin/node" to "old node", "lib/a.so" to "a")
    private val incomplete = mapOf("lib/stale.so" to "half", "share/x" to "x")

    private fun assertUntouchedAfter(
        what: String,
        existing: Map<String, String>,
        failure: () -> Unit,
    ) {
        if (usr.exists()) usr.deleteRecursively()
        write(usr, existing)
        val before = snapshot(usr)
        failure()
        assertThrows<Exception>(what) { setup() }
        assertEquals(before, snapshot(usr), "$what changed the existing usr")
    }

    @Test
    fun `a failed or refused download leaves the existing usr untouched, complete or incomplete`() {
        for (existing in listOf(complete, incomplete)) {
            assertUntouchedAfter("download", existing) {
                every { manager["getBootstrapArchive"](any<Function2<Float, String, Unit>>()) } throws
                    IOException("SHA-256 mismatch")
            }
        }
    }

    @Test
    fun `an extraction that fails part way leaves the existing usr untouched`() {
        for (existing in listOf(complete, incomplete)) {
            assertUntouchedAfter("extract", existing) {
                val good = zip(NEW_FILES)
                // The archive breaks after its first bytes: the stream throws in the middle of the zip
                archive = { BrokenStream(good, good.size / 2) }
            }
        }
    }

    @Test
    fun `an archive entry that escapes the staging dir fails the extraction and leaves usr untouched`() {
        for (existing in listOf(complete, incomplete)) {
            assertUntouchedAfter("zip slip", existing) {
                archive = { ByteArrayInputStream(zip(mapOf("bin/sh" to "x", "../escape" to "evil"))) }
            }
            assertFalse(File(filesDir, "escape").exists())
        }
    }

    @Test
    fun `a configuration step that fails leaves the existing usr untouched - the delete comes only after it`() {
        for (existing in listOf(complete, incomplete)) {
            assertUntouchedAfter("configure", existing) {
                every { manager["configureApt"](any<File>()) } throws IOException("disk full")
            }
        }
    }

    // ── (e) a leftover that cannot be deleted ───────────────────────────────

    @Test
    fun `a leftover that cannot be deleted fails with IOException, and the next attempt succeeds once it can be`() {
        // A read-only dir must really keep its file here (it does not for root): else the case cannot be built
        val probe = File(filesDir, "probe").apply { mkdirs() }
        File(probe, "f").writeText("x")
        probe.setWritable(false)
        readOnly.add(probe)
        assumeTrue(!File(probe, "f").delete(), "files in a read-only dir can be deleted here (root?)")

        val locked = File(usr, "locked").apply { mkdirs() }
        File(locked, "pinned.txt").writeText("cannot go")
        assertTrue(locked.setWritable(false), "could not make the dir read-only")
        readOnly.add(locked)
        val e = assertThrows<IOException> { setup() }
        assertTrue(e.message!!.contains("Could not move the new bootstrap into place"), e.message)
        assertTrue(File(locked, "pinned.txt").exists())
        assertFalse(manager.isInstalled())

        locked.setWritable(true)
        setup()
        assertNewPrefix()
        assertFalse(File(usr, "locked").exists())
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private class BrokenStream(
        bytes: ByteArray,
        private val failAt: Int,
    ) : InputStream() {
        private val inner = ByteArrayInputStream(bytes)
        private var read = 0

        override fun read(): Int {
            if (read >= failAt) throw IOException("connection reset")
            read++
            return inner.read()
        }
    }

    private companion object {
        val NEW_FILES =
            mapOf(
                "bin/sh" to "#!new sh\n",
                "lib/libnew.so" to "new lib\n",
                "etc/apt/sources.list" to "deb https://packages.termux.dev/apt/termux-main stable main # com.termux\n",
            )
        val EXPECTED_FILES = setOf("bin/sh", "lib/libnew.so", "etc/apt/sources.list", "etc/apt/apt.conf")

        fun zip(files: Map<String, String>): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { z ->
                files.forEach { (name, text) ->
                    z.putNextEntry(ZipEntry(name))
                    z.write(text.toByteArray())
                    z.closeEntry()
                }
            }
            return out.toByteArray()
        }
    }
}
