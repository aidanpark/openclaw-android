package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class BootstrapSecurityTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `requireSha256 normalizes valid digest`() {
        val digest = "EA2AEBA8819E517DB711F8C32369E89E7C52CEE73E07930FF91185E1AB93F4F3"

        assertEquals(
            "ea2aeba8819e517db711f8c32369e89e7c52cee73e07930ff91185e1ab93f4f3",
            BootstrapSecurity.requireSha256(digest),
        )
    }

    @Test
    fun `requireSha256 rejects missing or malformed digest`() {
        assertThrows(IllegalArgumentException::class.java) {
            BootstrapSecurity.requireSha256(null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BootstrapSecurity.requireSha256("not-a-sha256")
        }
    }

    @Test
    fun `sha256Hex hashes file contents`() {
        val file = File(tempDir, "bootstrap.zip")
        file.writeText("bootstrap")

        assertEquals(
            "333c04dd151a2a6831c039cb9a651df29198be8a04e16ce861d4b6a34a11c954",
            BootstrapSecurity.sha256Hex(file),
        )
    }

    @Test
    fun `requireVersion rejects missing version`() {
        assertThrows(IllegalArgumentException::class.java) {
            BootstrapSecurity.requireVersion(null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BootstrapSecurity.requireVersion(" ")
        }
    }

    @Test
    fun `resolveInsideDirectory accepts nested entry`() {
        val target = BootstrapSecurity.resolveInsideDirectory(tempDir, "bin/sh")

        assertTrue(target.path.startsWith(tempDir.canonicalPath + File.separator))
        assertEquals("sh", target.name)
    }

    @Test
    fun `resolveInsideDirectory rejects traversal entry`() {
        assertThrows(IllegalArgumentException::class.java) {
            BootstrapSecurity.resolveInsideDirectory(tempDir, "../escaped-proof.txt")
        }
    }

    @Test
    fun `resolveInsideDirectory rejects absolute entry`() {
        assertThrows(IllegalArgumentException::class.java) {
            BootstrapSecurity.resolveInsideDirectory(tempDir, "/tmp/escaped-proof.txt")
        }
    }
}
