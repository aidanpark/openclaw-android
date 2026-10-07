package com.openclaw.android

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.TimeUnit

/**
 * [SafeTree.deleteNoFollow] with REAL files and REAL symlinks (`Files.createSymbolicLink`): it
 * deletes the tree and never follows a link at any depth — a link to a directory, to a file,
 * dangling, relative, looping back to a parent, the root itself a link, links deep down, links to
 * places outside and inside the tree. The target of every link outside the tree is compared by a
 * snapshot that does not follow links. Return values, read-only parts (the rest is still deleted),
 * never throwing, a large tree, and `JsBridge.clearCache` on a cache dir holding a link.
 */
internal class SafeTreeTest {
    @TempDir
    lateinit var root: File

    private val readOnly = mutableListOf<File>()

    @AfterEach
    fun writableAgain() {
        readOnly.forEach { it.setWritable(true) }
    }

    private val tree get() = File(root, "tree")
    private val outside get() = File(root, "outside")

    private fun write(
        base: File,
        files: Map<String, String>,
    ) = files.forEach { (path, text) -> File(base, path).apply { parentFile!!.mkdirs() }.writeText(text) }

    private fun link(
        at: File,
        target: File,
    ) {
        at.parentFile!!.mkdirs()
        Files.createSymbolicLink(at.toPath(), target.toPath())
    }

    private fun gone(f: File) = !Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)

    /** Every entry under [dir], links listed (with their target) and never entered. */
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

    private fun outsideFixture() =
        write(outside, mapOf("keep.txt" to "k", "dir/a.txt" to "a", "dir/sub/b.txt" to "b", ".hidden" to "h"))

    // ── return values ───────────────────────────────────────────────────────

    @Test
    fun `a path that does not exist is true and nothing happens`() {
        assertTrue(SafeTree.deleteNoFollow(File(root, "nope")))
        assertTrue(SafeTree.deleteNoFollow(File(root, "nope/deeper/still")))
    }

    @Test
    fun `a plain file and a real tree are deleted - true`() {
        val f = File(root, "f.txt").apply { writeText("x") }
        assertTrue(SafeTree.deleteNoFollow(f))
        assertFalse(f.exists())
        write(tree, mapOf("a" to "1", "b/c" to "2", "b/d/e/f" to "3"))
        File(tree, "empty/dir").mkdirs()
        assertTrue(SafeTree.deleteNoFollow(tree))
        assertTrue(gone(tree))
    }

    @Test
    fun `a relative path with dot-dot segments is resolved like an absolute one`() {
        write(tree, mapOf("a/b" to "x"))
        outsideFixture()
        link(File(tree, "a/out"), outside)
        val before = snapshot(outside)
        // From the working directory, through `..`, to the temp tree
        val rel = File(tree.canonicalFile.relativeTo(File("").canonicalFile).path)
        assertFalse(rel.isAbsolute)
        assertEquals(tree.canonicalFile, rel.canonicalFile)
        assertTrue(SafeTree.deleteNoFollow(rel))
        assertTrue(gone(tree))
        assertEquals(before, snapshot(outside))
    }

    // ── links are removed, never followed ───────────────────────────────────

    @Test
    fun `links of every kind inside the tree are removed and nothing outside is touched`() {
        outsideFixture()
        write(tree, mapOf("real/file" to "r", "x/y/z/deep.txt" to "d"))
        link(File(tree, "dirlink"), File(outside, "dir")) // a directory, absolute
        link(File(tree, "filelink"), File(outside, "keep.txt")) // a file
        link(File(tree, "dangling"), File(outside, "nowhere")) // dangling
        link(File(tree, "x/rel"), File("../../outside/dir")) // relative, resolving outside
        link(File(tree, "x/y/loop"), File("../..")) // back to a parent inside the tree (a loop)
        link(File(tree, "x/y/z/deeplink"), File(outside, "dir/sub")) // at depth 3
        link(File(tree, "x/y/z/w/v/deeper"), outside) // at depth 5, to the whole outside dir
        link(File(tree, "inside"), File(tree, "real")) // to a dir inside the tree
        val before = snapshot(outside)
        assertTrue(SafeTree.deleteNoFollow(tree))
        assertTrue(gone(tree))
        assertEquals(before, snapshot(outside), "a link was followed out of the tree")
    }

    @Test
    fun `the root itself a link - only the link goes, its target stays`() {
        outsideFixture()
        link(tree, outside)
        val before = snapshot(outside)
        assertTrue(SafeTree.deleteNoFollow(tree))
        assertTrue(gone(tree))
        assertEquals(before, snapshot(outside))
    }

    @Test
    fun `a root reached through a linked parent is still a real tree, deleted - and the link above stays`() {
        write(tree, mapOf("a/b" to "x"))
        val via = File(root, "via").also { link(it, root) }
        val throughLink = File(via, "tree")
        assertTrue(SafeTree.deleteNoFollow(throughLink))
        assertTrue(gone(tree))
        assertTrue(Files.isSymbolicLink(via.toPath()), "the link above the root was removed")
    }

    @Test
    fun `a link into the tree from outside is left alone, the tree behind it is deleted`() {
        write(tree, mapOf("a/b" to "x"))
        val pointer = File(outside, "to-tree").also { link(it, tree) }
        assertTrue(SafeTree.deleteNoFollow(tree))
        assertTrue(gone(tree))
        assertTrue(Files.isSymbolicLink(pointer.toPath()), "a link outside the tree was removed")
    }

    // ── what cannot be deleted ──────────────────────────────────────────────

    @Test
    fun `a read-only part is false, never an exception, and the siblings are still deleted`() {
        val probe = File(root, "probe").apply { mkdirs() }
        File(probe, "f").writeText("x")
        probe.setWritable(false)
        readOnly.add(probe)
        assumeTrue(!File(probe, "f").delete(), "files in a read-only dir can be deleted here (root?)")

        write(tree, mapOf("a1/x" to "1", "locked/pinned" to "p", "z9/y" to "2", "top.txt" to "t"))
        val locked = File(tree, "locked")
        assertTrue(locked.setWritable(false))
        readOnly.add(locked)
        val result = assertDoesNotThrow<Boolean> { SafeTree.deleteNoFollow(tree) }
        assertFalse(result)
        assertTrue(File(locked, "pinned").exists())
        for (sibling in listOf("a1", "z9", "top.txt")) assertFalse(File(tree, sibling).exists(), "$sibling was kept")
        locked.setWritable(true)
        assertTrue(SafeTree.deleteNoFollow(tree))
        assertTrue(gone(tree))
    }

    @Test
    fun `a directory that cannot be listed is false, never an exception`() {
        write(tree, mapOf("closed/inside" to "x", "open/f" to "y"))
        val closed = File(tree, "closed")
        assertTrue(closed.setReadable(false) && closed.setExecutable(false))
        readOnly.add(closed)
        try {
            assumeTrue(closed.listFiles() == null, "an unreadable dir can still be listed here (root?)")
            val result = assertDoesNotThrow<Boolean> { SafeTree.deleteNoFollow(tree) }
            assertFalse(result)
            assertFalse(File(tree, "open").exists())
        } finally {
            closed.setReadable(true)
            closed.setExecutable(true)
        }
    }

    // ── a large tree ────────────────────────────────────────────────────────

    @Test
    fun `about 5000 entries with links among them are deleted quickly`() {
        outsideFixture()
        var entries = 0
        for (i in 0 until 50) {
            for (j in 0 until 20) {
                val d = File(tree, "d$i/e$j").apply { mkdirs() }
                entries += 1
                for (k in 0 until 4) {
                    File(d, "f$k").writeText("x")
                    entries++
                }
                if (j % 5 == 0) {
                    link(File(d, "l"), File(outside, "dir"))
                    entries++
                }
            }
        }
        assertTrue(entries >= 5_000, "only $entries entries")
        val before = snapshot(outside)
        val start = System.nanoTime()
        assertTrue(SafeTree.deleteNoFollow(tree))
        val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue(gone(tree))
        assertEquals(before, snapshot(outside))
        assertTrue(ms < BIG_TREE_LIMIT_MS, "took $ms ms")
    }

    // ── JsBridge.clearCache ─────────────────────────────────────────────────

    @Test
    fun `clearCache empties the cache dir, keeps the dir itself and never follows a link out of it`() {
        val files = File(root, "files").apply { mkdirs() }
        write(files, mapOf("usr/bin/sh" to "#!sh", "home/.bashrc" to "mine"))
        val cache = File(root, "cache")
        write(cache, mapOf("webview/a.bin" to "c", "http/b" to "d"))
        link(File(cache, "to-files"), files)
        link(File(cache, "webview/to-home"), File(files, "home"))
        val filesBefore = snapshot(files)
        val activity = mockk<MainActivity>(relaxed = true)
        every { activity.cacheDir } returns cache
        val web = RecordingWebView()
        try {
            val bridge = JsBridge(activity, mockk(relaxed = true), mockk(relaxed = true), EventBridge(web.view))
            bridge.clearCache()
            assertTrue(cache.isDirectory, "the cache dir is not there after clearCache")
            assertEquals(emptyList<String>(), cache.list()!!.toList())
            assertEquals(filesBefore, snapshot(files), "clearCache followed a link out of the cache dir")
            // A cache dir that is gone is made again
            cache.delete()
            bridge.clearCache()
            assertTrue(cache.isDirectory)
        } finally {
            EventBridge.detach(web.view)
        }
    }

    private companion object {
        const val BIG_TREE_LIMIT_MS = 10_000L
    }
}
