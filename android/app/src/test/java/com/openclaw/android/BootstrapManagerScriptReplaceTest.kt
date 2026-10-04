package com.openclaw.android

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException

/**
 * `replaceAtomically` / `startsWithShebang` / `cleanStaleTemps` are private; they are reached by
 * reflection on a BootstrapManager whose Context only provides a temp `filesDir`. No network.
 */
class BootstrapManagerScriptReplaceTest {
    @TempDir
    lateinit var filesDir: File

    private lateinit var manager: BootstrapManager
    private lateinit var binDir: File

    @BeforeEach
    fun setUp() {
        val context = mockk<Context>()
        every { context.filesDir } returns filesDir
        manager = BootstrapManager(context)
        binDir = File(manager.prefixDir, "bin").apply { mkdirs() }
    }

    private fun replace(
        target: File,
        requireShebang: Boolean,
        body: ByteArray,
    ) {
        val method =
            BootstrapManager::class.java.getDeclaredMethod(
                "replaceAtomically",
                File::class.java,
                Boolean::class.javaPrimitiveType,
                Function1::class.java,
            )
        method.isAccessible = true
        val write: (OutputStream) -> Unit = { it.write(body) }
        try {
            method.invoke(manager, target, requireShebang, write)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun startsWithShebang(file: File): Boolean {
        val method = BootstrapManager::class.java.getDeclaredMethod("startsWithShebang", File::class.java)
        method.isAccessible = true
        return method.invoke(manager, file) as Boolean
    }

    private fun cleanStaleTemps(target: File) {
        val method = BootstrapManager::class.java.getDeclaredMethod("cleanStaleTemps", File::class.java)
        method.isAccessible = true
        method.invoke(manager, target)
    }

    private fun tempsNextTo(target: File): List<File> =
        target.parentFile
            ?.listFiles()
            .orEmpty()
            .filter { it.name.startsWith("${target.name}.") && it.name.endsWith(".tmp") }

    @Nested
    inner class ReplaceAtomically {
        private val existing = "#!/bin/sh\necho old\n".toByteArray()

        private fun existingTarget(): File = File(binDir, "oa").apply { writeBytes(existing) }

        @Test
        fun `a script starting with a shebang replaces the target and is executable`() {
            val target = existingTarget()
            val script = "#!/data/data/com.openclaw.android/files/usr/bin/bash\necho new\n".toByteArray()

            replace(target, requireShebang = true, body = script)

            assertArrayEquals(script, target.readBytes())
            assertTrue(target.canExecute())
            assertTrue(tempsNextTo(target).isEmpty())
        }

        @Test
        fun `an HTML page is refused and the existing script is kept`() {
            val target = existingTarget()
            val html = "<!DOCTYPE html><html><body>Sign in to Wi-Fi</body></html>".toByteArray()

            val e = assertThrows<IOException> { replace(target, requireShebang = true, body = html) }

            assertTrue(e.message!!.contains("not a script"), e.message)
            assertArrayEquals(existing, target.readBytes())
            assertTrue(tempsNextTo(target).isEmpty(), "refused temp file must be removed")
        }

        @Test
        fun `a body with leading whitespace before the shebang is refused`() {
            val target = existingTarget()
            assertThrows<IOException> { replace(target, requireShebang = true, body = " #!/bin/sh\n".toByteArray()) }
            assertArrayEquals(existing, target.readBytes())
        }

        @Test
        fun `a one-byte body is refused`() {
            val target = existingTarget()
            assertThrows<IOException> { replace(target, requireShebang = true, body = "#".toByteArray()) }
            assertArrayEquals(existing, target.readBytes())
        }

        @Test
        fun `an empty body is refused even without the shebang check`() {
            val target = existingTarget()

            val e = assertThrows<IOException> { replace(target, requireShebang = false, body = ByteArray(0)) }

            assertTrue(e.message!!.contains("empty"), e.message)
            assertArrayEquals(existing, target.readBytes())
            assertTrue(tempsNextTo(target).isEmpty())
        }

        @Test
        fun `an empty body is refused with the shebang check`() {
            val target = existingTarget()
            assertThrows<IOException> { replace(target, requireShebang = true, body = ByteArray(0)) }
            assertArrayEquals(existing, target.readBytes())
        }

        @Test
        fun `without the shebang requirement non-script content is accepted`() {
            val target = existingTarget()
            val body = "plain data".toByteArray()

            replace(target, requireShebang = false, body = body)

            assertArrayEquals(body, target.readBytes())
        }

        @Test
        fun `creates the target when it did not exist`() {
            val target = File(binDir, "fresh")
            replace(target, requireShebang = true, body = "#!/bin/sh\n".toByteArray())
            assertTrue(target.isFile)
            assertTrue(target.canExecute())
        }

        @Test
        fun `a writer that throws leaves the existing script and no temp file`() {
            val target = existingTarget()
            val method =
                BootstrapManager::class.java.getDeclaredMethod(
                    "replaceAtomically",
                    File::class.java,
                    Boolean::class.javaPrimitiveType,
                    Function1::class.java,
                )
            method.isAccessible = true
            val write: (OutputStream) -> Unit = {
                it.write("#!/bin/sh\npartial".toByteArray())
                throw IOException("connection reset")
            }

            val e = assertThrows<InvocationTargetException> { method.invoke(manager, target, true, write) }

            assertEquals("connection reset", e.targetException.message)
            assertArrayEquals(existing, target.readBytes())
            assertTrue(tempsNextTo(target).isEmpty())
        }
    }

    @Nested
    inner class StartsWithShebang {
        private fun file(bytes: ByteArray): File = File(filesDir, "probe").apply { writeBytes(bytes) }

        @Test
        fun `true for files starting with hash-bang`() {
            assertTrue(startsWithShebang(file("#!/bin/sh\n".toByteArray())))
            assertTrue(startsWithShebang(file("#!".toByteArray())))
        }

        @Test
        fun `false for html, bom-prefixed, short and empty files`() {
            assertFalse(startsWithShebang(file("<html>".toByteArray())))
            assertFalse(
                startsWithShebang(
                    file(
                        byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "#!".toByteArray(),
                    ),
                ),
            )
            assertFalse(startsWithShebang(file("#".toByteArray())))
            assertFalse(startsWithShebang(file("!#".toByteArray())))
            assertFalse(startsWithShebang(file(ByteArray(0))))
        }
    }

    @Nested
    inner class CleanStaleTemps {
        private val elevenMinutesMs = 11L * 60 * 1000
        private val nineMinutesMs = 9L * 60 * 1000

        private fun temp(
            name: String,
            ageMs: Long,
        ): File =
            File(binDir, name).apply {
                writeText("x")
                setLastModified(System.currentTimeMillis() - ageMs)
            }

        @Test
        fun `removes temps older than ten minutes and keeps recent ones`() {
            val target = File(binDir, "oa")
            val stale = temp("oa.123456.tmp", elevenMinutesMs)
            val live = temp("oa.789012.tmp", nineMinutesMs)

            cleanStaleTemps(target)

            assertFalse(stale.exists(), "stale temp should be deleted")
            assertTrue(live.exists(), "a temp from a download that may still be running must stay")
        }

        @Test
        fun `leaves the target and unrelated files alone however old they are`() {
            val target = temp("oa", elevenMinutesMs)
            val others =
                listOf(
                    temp("oa.bak", elevenMinutesMs),
                    temp("node.123.tmp", elevenMinutesMs),
                    temp("oatmp.tmp", elevenMinutesMs),
                    temp("oa.123.tmp.keep", elevenMinutesMs),
                )

            cleanStaleTemps(target)

            assertTrue(target.exists())
            others.forEach { assertTrue(it.exists(), it.name) }
        }

        @Test
        fun `does nothing when the parent directory does not exist`() {
            cleanStaleTemps(File(filesDir, "missing/dir/oa"))
            assertFalse(File(filesDir, "missing").exists())
        }
    }
}
