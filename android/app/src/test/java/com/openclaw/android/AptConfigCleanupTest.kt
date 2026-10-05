package com.openclaw.android

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.InvocationTargetException

/**
 * The private apt.conf writers of a compiled BootstrapManager, reached by reflection on an instance
 * whose Context only provides a temp `filesDir` (same approach as BootstrapManagerScriptReplaceTest).
 * No network: applyScriptUpdate itself would download post-setup.sh, so it is only called when it
 * must return early.
 */
class AptConfigCleanupTest {
    @TempDir
    lateinit var filesDir: File

    private lateinit var manager: BootstrapManager
    private lateinit var aptConf: File

    @BeforeEach
    fun setUp() {
        val context = mockk<Context>()
        every { context.filesDir } returns filesDir
        every { context.packageName } returns "com.openclaw.android"
        manager = BootstrapManager(context)
        aptConf = File(manager.prefixDir, "etc/apt/apt.conf")
        aptConf.parentFile.mkdirs()
    }

    private fun invoke(
        name: String,
        vararg args: Any,
    ) {
        val types = args.map { it::class.java }.toTypedArray()
        val method = BootstrapManager::class.java.getDeclaredMethod(name, *types)
        method.isAccessible = true
        try {
            method.invoke(manager, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun clean() = invoke("removeInsecureAptOptions")

    private val otherLines =
        listOf(
            "Dir \"/\";",
            "Dir::State \"/data/x/usr/var/lib/apt/\";",
            "  Dpkg::Options:: \"--force-configure-any\";",
            "// Acquire::AllowInsecureRepositories is a comment here and stays",
            "APT::Get::Assume-Yes \"true\";",
        )

    @Test
    fun `only the two insecure lines are removed and every other byte stays`() {
        val old =
            listOf(
                otherLines[0],
                "Acquire::AllowInsecureRepositories \"true\";",
                otherLines[1],
                otherLines[2],
                "    APT::Get::AllowUnauthenticated \"true\";",
                otherLines[3],
                otherLines[4],
            ).joinToString("\n", postfix = "\n")
        aptConf.writeText(old)
        clean()
        assertEquals(otherLines.joinToString("\n", postfix = "\n"), aptConf.readText())
    }

    @Test
    fun `a clean file is not rewritten`() {
        val text = otherLines.joinToString("\n", postfix = "\n")
        aptConf.writeText(text)
        val past = System.currentTimeMillis() - HOUR_MS
        assertTrue(aptConf.setLastModified(past))
        val stamp = aptConf.lastModified()
        clean()
        assertEquals(text, aptConf.readText())
        assertEquals(stamp, aptConf.lastModified(), "a clean apt.conf was rewritten")
    }

    @Test
    fun `cleaning twice gives the same result as cleaning once`() {
        aptConf.writeText("A \"1\";\nAcquire::AllowInsecureRepositories \"true\";\nB \"2\";")
        clean()
        val once = aptConf.readBytes()
        assertTrue(aptConf.setLastModified(System.currentTimeMillis() - HOUR_MS))
        val stamp = aptConf.lastModified()
        clean()
        assertArrayEquals(once, aptConf.readBytes())
        assertEquals("A \"1\";\nB \"2\";", aptConf.readText())
        assertEquals(stamp, aptConf.lastModified(), "the second clean rewrote the file")
    }

    @Test
    fun `a missing apt conf is not an error and is not created`() {
        aptConf.delete()
        clean()
        assertFalse(aptConf.exists())
    }

    @Test
    fun `a directory in place of apt conf is left alone`() {
        aptConf.mkdirs()
        clean()
        assertTrue(aptConf.isDirectory)
    }

    @Test
    fun `a read-only apt conf does not throw into the app`() {
        val old = "Acquire::AllowInsecureRepositories \"true\";\nDir \"/\";\n"
        aptConf.writeText(old)
        assertTrue(aptConf.setWritable(false, false))
        try {
            assumeFalse(aptConf.canWrite(), "running as a user that can write read-only files (root)")
            clean() // must not throw
            assertEquals(old, aptConf.readText())
        } finally {
            aptConf.setWritable(true, true)
        }
    }

    @Test
    fun `an unreadable apt conf does not throw into the app`() {
        aptConf.writeText("Acquire::AllowInsecureRepositories \"true\";\n")
        assertTrue(aptConf.setReadable(false, false))
        try {
            assumeFalse(aptConf.canRead(), "running as a user that can read unreadable files (root)")
            clean()
        } finally {
            aptConf.setReadable(true, true)
        }
    }

    @Test
    fun `applyScriptUpdate on a prefix without bin sh touches nothing`() {
        val old = "Acquire::AllowInsecureRepositories \"true\";\n"
        aptConf.writeText(old)
        manager.applyScriptUpdate()
        assertEquals(old, aptConf.readText())
    }

    @Test
    fun `configureApt writes an apt conf with no Allow option and no http downgrade`() {
        val dir = File(filesDir, "staging").apply { mkdirs() }
        val sources = File(dir, "etc/apt/sources.list")
        sources.parentFile.mkdirs()
        sources.writeText("deb https://packages-cf.termux.dev/apt/termux-main/ stable main # com.termux\n")
        invoke("configureApt", dir)

        val conf = File(dir, "etc/apt/apt.conf").readText()
        assertFalse(conf.contains("Allow"), conf)
        assertTrue(conf.contains("Dir::State::status \"${manager.prefixDir.absolutePath}/var/lib/dpkg/status\";"), conf)
        assertEquals(
            "deb https://packages-cf.termux.dev/apt/termux-main/ stable main # com.openclaw.android\n",
            sources.readText(),
        )
    }

    @Test
    fun `an apt conf written by configureApt is already clean`() {
        val dir = manager.prefixDir.apply { mkdirs() }
        invoke("configureApt", dir)
        val before = aptConf.readText()
        aptConf.setLastModified(System.currentTimeMillis() - HOUR_MS)
        val stamp = aptConf.lastModified()
        clean()
        assertEquals(before, aptConf.readText())
        assertEquals(stamp, aptConf.lastModified())
    }

    private companion object {
        const val HOUR_MS = 3_600_000L
    }
}
