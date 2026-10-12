package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class WwwSyncTest {
    @TempDir
    lateinit var www: File

    // assets: path -> content; a path that is a prefix of others is a directory
    private class Bundle(
        private val files: Map<String, String>,
        private val failOn: String? = null,
    ) {
        fun list(path: String): Array<String>? {
            val prefix = "$path/"
            val names = files.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).substringBefore('/') }
            return names.distinct().toTypedArray()
        }

        fun open(path: String): InputStream {
            if (path == failOn) throw IOException("asset unreadable: $path")
            return ByteArrayInputStream(files.getValue(path).toByteArray())
        }
    }

    private fun sync(b: Bundle) = WwwSync.copyTree("www", www, b::list, b::open)

    @Test
    fun `copies nested files and replaces existing ones`() {
        File(www, "index.html").writeText("old")
        sync(Bundle(mapOf("www/index.html" to "new", "www/assets/app.js" to "js")))
        assertEquals("new", File(www, "index.html").readText())
        assertEquals("js", File(www, "assets/app.js").readText())
    }

    @Test
    fun `a reader that has the old file open keeps the complete old content while it is replaced`() {
        val index = File(www, "index.html").apply { writeText("old-content") }
        index.inputStream().use { reader ->
            sync(Bundle(mapOf("www/index.html" to "new")))
            // Rename replaces the directory entry; the open file is not truncated underneath the reader
            assertArrayEquals("old-content".toByteArray(), reader.readBytes())
        }
        assertEquals("new", index.readText())
    }

    @Test
    fun `a failed copy leaves the previous file whole and no temp file behind`() {
        File(www, "app.js").writeText("old-app")
        val failing = Bundle(mapOf("www/index.html" to "new-index", "www/app.js" to "new-app"), failOn = "www/app.js")
        assertThrows<IOException> { sync(failing) }
        assertEquals("old-app", File(www, "app.js").readText())
        assertTrue(www.listFiles()!!.none { it.name.endsWith(".tmp") }, "left: ${www.list()?.toList()}")
    }

    @Test
    fun `a one-letter file name is copied`() {
        sync(Bundle(mapOf("www/a" to "x")))
        assertEquals("x", File(www, "a").readText())
    }

    @Test
    fun `a temp file a killed process left is removed and a fresh one is kept`() {
        val stale = File(www, "index.html.123.tmp").apply { writeText("x") }
        stale.setLastModified(System.currentTimeMillis() - 10 * 60 * 1000L)
        val live = File(www, "app.js.456.tmp").apply { writeText("y") }
        sync(Bundle(mapOf("www/index.html" to "new")))
        assertFalse(stale.exists())
        assertTrue(live.exists())
    }
}
