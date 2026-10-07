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
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * `BootstrapManager.cleanStaleStaging` — the startup removal of a `usr-staging` left by a bootstrap
 * killed mid-extraction — on the JVM with REAL files and REAL symlinks
 * (`Files.createSymbolicLink`): what it removes and returns, that it never follows a symlink out of
 * the staging dir (into `usr` or home), that an install running (or starting while it holds
 * [STAGING_LOCK]) keeps the staging dir, that nothing it calls can throw out of it, and that
 * MainActivity calls it off the UI thread with `SetupGuard::isRunning`. Then what `startSetup`'s own
 * deletes (its staging cleanup, the swap's delete of the old `usr`) do with the same symlinks.
 */
internal class BootstrapStagingCleanupTest {
    @TempDir
    lateinit var filesDir: File

    private lateinit var manager: BootstrapManager
    private var archive: () -> InputStream = { ByteArrayInputStream(zip(mapOf("bin/sh" to "#!new\n"))) }
    private val readOnly = mutableListOf<File>()

    private val usr get() = File(filesDir, "usr")
    private val home get() = File(filesDir, "home")
    private val staging get() = File(filesDir, "usr-staging")

    @BeforeEach
    fun setUp() {
        val assets = mockk<AssetManager>()
        every { assets.open("bootstrap-aarch64.zip") } answers { archive() }
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.assets } returns assets
        every { context.packageName } returns "com.openclaw.android"
        manager = spyk(BootstrapManager(context), recordPrivateCalls = true)
        every { manager["copyAssetScripts"]() } returns Unit
        every { manager["setupTermuxExec"]() } returns Unit
        every { manager.syncWwwFromAssets() } returns Unit
        every { manager.saveCurrentVersionCode() } returns Unit
        // A live install and a home with the user's data, which no cleanup may touch
        write(usr, mapOf("bin/sh" to "#!old\n", "lib/libc.so" to "libc", "lib/node_modules/g/x.js" to "g"))
        write(home, mapOf(".bashrc" to "mine", ".openclaw/projects/a.txt" to "data"))
    }

    @AfterEach
    fun writableAgain() {
        readOnly.forEach { it.setWritable(true) }
    }

    private fun write(
        root: File,
        files: Map<String, String>,
    ) = files.forEach { (path, text) -> File(root, path).apply { parentFile!!.mkdirs() }.writeText(text) }

    /** Every entry under [dir] without following links (a link is listed, not entered). */
    private fun snapshot(dir: File): Map<String, String> {
        val out = sortedMapOf<String, String>()

        fun visit(f: File) {
            val p = f.toPath()
            val rel = f.relativeTo(dir).path
            when {
                Files.isSymbolicLink(p) -> out[rel] = "-> " + Files.readSymbolicLink(p)
                f.isDirectory -> {
                    if (f != dir) out["$rel/"] = ""
                    f.listFiles()?.forEach(::visit)
                }
                else -> out[rel] = f.readText()
            }
        }
        visit(dir)
        return out
    }

    private fun link(
        at: File,
        target: File,
    ) {
        at.parentFile!!.mkdirs()
        Files.createSymbolicLink(at.toPath(), target.toPath())
    }

    private fun clean(running: () -> Boolean = { false }) = manager.cleanStaleStaging(running)

    // ── what it removes and returns ─────────────────────────────────────────

    @Test
    fun `no staging dir - false, nothing done, the predicate is not even needed`() {
        val usrBefore = snapshot(usr)
        assertFalse(clean { error("asked without a staging dir") })
        assertFalse(staging.exists())
        assertEquals(usrBefore, snapshot(usr))
    }

    @Test
    fun `a stale staging dir with no install running is removed - true`() {
        write(staging, mapOf("bin/sh" to "half", "lib/deep/a/b.so" to "x", "share/empty/.keep" to ""))
        File(staging, "var/empty").mkdirs()
        val usrBefore = snapshot(usr)
        val homeBefore = snapshot(home)
        assertTrue(clean())
        assertFalse(staging.exists())
        assertEquals(usrBefore, snapshot(usr))
        assertEquals(homeBefore, snapshot(home))
    }

    @Test
    fun `an install running keeps the staging dir - false`() {
        write(staging, mapOf("bin/sh" to "half"))
        val before = snapshot(staging)
        assertFalse(clean { true })
        assertEquals(before, snapshot(staging))
    }

    @Test
    fun `a predicate that throws is a no, nothing is thrown and the staging dir stays`() {
        write(staging, mapOf("bin/sh" to "half"))
        val before = snapshot(staging)
        assertFalse(clean { throw IllegalStateException("guard broken") })
        assertEquals(before, snapshot(staging))
    }

    @Test
    fun `a staging dir that cannot be removed completely is false, without throwing, and the rest is removed`() {
        val probe = File(filesDir, "probe").apply { mkdirs() }
        File(probe, "f").writeText("x")
        probe.setWritable(false)
        readOnly.add(probe)
        assumeTrue(!File(probe, "f").delete(), "files in a read-only dir can be deleted here (root?)")

        write(staging, mapOf("loose.txt" to "x", "locked/pinned.txt" to "y"))
        val locked = File(staging, "locked")
        assertTrue(locked.setWritable(false))
        readOnly.add(locked)
        assertFalse(clean())
        assertTrue(File(locked, "pinned.txt").exists())
        assertFalse(File(staging, "loose.txt").exists())
        locked.setWritable(true)
        assertTrue(clean(), "removed once it can be")
        assertFalse(staging.exists())
    }

    // ── symlinks: removed, never followed ───────────────────────────────────

    @Test
    fun `links inside the staging dir into usr and home are removed, their targets stay untouched`() {
        write(staging, mapOf("bin/real" to "r"))
        link(File(staging, "lib"), File(usr, "lib")) // a directory link, absolute, into the live usr
        link(File(staging, "bin/sh"), File(usr, "bin/sh")) // a file link
        link(File(staging, "share/projects"), File(home, ".openclaw")) // a directory link into home
        link(File(staging, "etc/rel"), File("../../usr/lib")) // relative, resolving into usr
        link(File(staging, "dangling"), File(filesDir, "nowhere"))
        val usrBefore = snapshot(usr)
        val homeBefore = snapshot(home)
        assertTrue(clean())
        assertFalse(Files.exists(staging.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals(usrBefore, snapshot(usr), "a link was followed into usr")
        assertEquals(homeBefore, snapshot(home), "a link was followed into home")
        assertEquals("libc", File(usr, "lib/libc.so").readText())
        assertEquals("data", File(home, ".openclaw/projects/a.txt").readText())
    }

    @Test
    fun `a staging dir that is itself a link (to usr) is unlinked, usr stays`() {
        link(staging, usr)
        val usrBefore = snapshot(usr)
        assertTrue(clean())
        assertFalse(Files.exists(staging.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals(usrBefore, snapshot(usr))
    }

    // ── the lock: an install that starts during a cleanup ───────────────────

    private fun stagingLock(): Any {
        val f = BootstrapManager::class.java.getDeclaredField("STAGING_LOCK")
        f.isAccessible = true
        return f.get(null) ?: error("STAGING_LOCK is null")
    }

    @Test
    fun `a cleanup waits for STAGING_LOCK and then sees the install that started meanwhile`() {
        write(staging, mapOf("bin/sh" to "being extracted"))
        val before = snapshot(staging)
        val running = AtomicBoolean(false)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        // Stands in for startSetup: SetupGuard is taken, then the lock around its staging work
        val install =
            Thread {
                synchronized(stagingLock()) {
                    running.set(true)
                    held.countDown()
                    release.await(TestWait.WAIT_SECONDS, TimeUnit.SECONDS)
                }
            }.apply { start() }
        assertTrue(held.await(TestWait.WAIT_SECONDS, TimeUnit.SECONDS))
        val result = AtomicReference<Boolean?>(null)
        val cleanup = Thread { result.set(clean { running.get() }) }.apply { start() }
        assertTrue(TestWait.until { cleanup.state == Thread.State.BLOCKED }, "the cleanup did not wait for the lock")
        assertEquals(null, result.get())
        release.countDown()
        install.join(TestWait.WAIT_SECONDS * 1000)
        cleanup.join(TestWait.WAIT_SECONDS * 1000)
        assertEquals(false, result.get())
        assertEquals(before, snapshot(staging), "the cleanup deleted the running install's staging dir")
    }

    @Test
    fun `startSetup waits for a cleanup that holds the lock before it touches the staging dir`() {
        write(staging, mapOf("stale/file" to "x"))
        val events = Collections.synchronizedList(mutableListOf<String>())
        val asked = CountDownLatch(1)
        val answer = CountDownLatch(1)
        val cleanup =
            Thread {
                clean {
                    asked.countDown()
                    answer.await(TestWait.WAIT_SECONDS, TimeUnit.SECONDS)
                    events.add("cleanup decided")
                    false
                }
                events.add("cleanup done")
            }.apply { start() }
        assertTrue(asked.await(TestWait.WAIT_SECONDS, TimeUnit.SECONDS))
        val setupFailure = AtomicReference<Throwable?>(null)
        val setup =
            Thread {
                try {
                    runBlocking {
                        manager.startSetup {
                            _,
                            m,
                            ->
                            if (m.startsWith("Extracting")) events.add("extracting")
                        }
                    }
                } catch (e: Throwable) {
                    setupFailure.set(e)
                }
            }.apply { start() }
        assertTrue(
            TestWait.until {
                Thread.getAllStackTraces().any { (t, st) ->
                    t.state == Thread.State.BLOCKED && st.any { it.className.contains("BootstrapManager\$startSetup") }
                }
            },
            "startSetup did not wait for the cleanup's lock",
        )
        answer.countDown()
        cleanup.join(TestWait.WAIT_SECONDS * 1000)
        setup.join(TestWait.WAIT_SECONDS * 1000)
        assertEquals(null, setupFailure.get())
        // "cleanup decided" is recorded inside the lock; "cleanup done" after it is released, so it
        // may land on either side of "extracting"
        val seen = events.toList()
        assertEquals(setOf("cleanup decided", "cleanup done", "extracting"), seen.toSet(), seen.toString())
        assertTrue(
            seen.indexOf("cleanup decided") < seen.indexOf("extracting"),
            "extracted before the cleanup decided: $seen",
        )
        assertEquals("#!new\n", File(usr, "bin/sh").readText())
    }

    // ── MainActivity ────────────────────────────────────────────────────────

    @Test
    fun `MainActivity onCreate runs the cleanup on a background thread with SetupGuard isRunning`() {
        val src = File("src/main/java/com/openclaw/android/MainActivity.kt").readText()
        val onCreate = src.substringAfter("override fun onCreate(", "").substringBefore("\n    private fun ")
        val calls = Regex("""cleanStaleStaging\(""").findAll(src).count()
        assertEquals(1, calls, "cleanStaleStaging is called elsewhere too")
        assertTrue(
            onCreate.contains("Thread { bootstrapManager.cleanStaleStaging(SetupGuard::isRunning) }.start()"),
            "not on its own thread with SetupGuard::isRunning: $onCreate",
        )
        assertFalse(onCreate.contains("runOnUiThread { bootstrapManager.cleanStaleStaging"))
        // The guard it asks is the one JsBridge.startSetup takes before startSetup touches the staging dir
        val bridge = File("src/main/java/com/openclaw/android/JsBridge.kt").readText()
        val start = bridge.substringAfter("fun startSetup() {")
        assertTrue(start.indexOf("SetupGuard.tryStart()") in 0 until start.indexOf("bootstrapManager.startSetup"))
    }

    // ── startSetup's own deletes and symlinks ───────────────────────────────

    /**
     * Was a characterization of a defect: startSetup's own staging cleanup used Kotlin
     * `deleteRecursively`, which followed a link to a directory. A staging dir left by a killed
     * extraction holds the archive's symlinks (SYMLINKS.txt targets point into the app's `usr`).
     * The extraction then fails: `usr` must be exactly as it was, the link alone gone.
     */
    @Test
    fun `startSetup's staging cleanup removes a link into usr unentered - a failed install leaves usr intact`() {
        link(File(staging, "lib"), File(usr, "lib"))
        link(File(staging, "deep/a/b/sh"), File(usr, "bin/sh"))
        val usrBefore = snapshot(usr)
        val good = zip(mapOf("bin/sh" to "#!new\n"))
        archive = { BrokenStream(good, good.size / 2) }
        assertThrows<Exception> { runBlocking { manager.startSetup { _, _ -> } } }
        assertEquals(usrBefore, snapshot(usr), "a link in the staging dir was followed into usr")
        assertEquals("libc", File(usr, "lib/libc.so").readText())
    }

    @Test
    fun `startSetup's staging cleanup does not enter a link even when the install then succeeds`() {
        link(File(staging, "projects"), File(home, ".openclaw"))
        val homeBefore = snapshot(home)
        runBlocking { manager.startSetup { _, _ -> } }
        assertHomeKept(homeBefore)
    }

    /** setupDirectories may add directories under home; every entry that was there stays as it was. */
    private fun assertHomeKept(before: Map<String, String>) {
        val after = snapshot(home)
        before.forEach { (k, v) -> assertEquals(v, after[k], "home/$k changed or was deleted") }
    }

    /**
     * Was a characterization of a defect: the swap's delete of the old `usr` followed a link to a
     * directory inside it. A link from `usr` into home (made by the user or a package) must leave the
     * home files behind it — the reinstall text says the home folder is not deleted.
     */
    @Test
    fun `the swap's delete of the old usr removes a link into home and leaves home untouched`() {
        link(File(usr, "share/projects"), File(home, ".openclaw"))
        link(File(usr, "etc/profile.d/rc"), File(home, ".bashrc"))
        val homeBefore = snapshot(home)
        runBlocking { manager.startSetup { _, _ -> } }
        assertEquals("#!new\n", File(usr, "bin/sh").readText())
        assertFalse(Files.exists(File(usr, "share/projects").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertHomeKept(homeBefore)
        assertEquals("data", File(home, ".openclaw/projects/a.txt").readText())
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
