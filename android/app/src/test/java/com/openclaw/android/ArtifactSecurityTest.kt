package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ArtifactSecurityTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `requireSha256 normalizes valid digest`() {
        val digest = "EA2AEBA8819E517DB711F8C32369E89E7C52CEE73E07930FF91185E1AB93F4F3"

        assertEquals(
            "ea2aeba8819e517db711f8c32369e89e7c52cee73e07930ff91185e1ab93f4f3",
            ArtifactSecurity.requireSha256(digest, "www"),
        )
    }

    @Test
    fun `requireSha256 rejects missing or malformed digest`() {
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactSecurity.requireSha256(null, "www")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactSecurity.requireSha256("not-a-sha256", "www")
        }
    }

    @Test
    fun `sha256Hex hashes file contents`() {
        val file = File(tempDir, "www.zip")
        file.writeText("www")

        assertEquals(
            "7c2ecd07f155648431e0f94b89247d713c5786e1e73e953f2fe7eca39534cd6d",
            ArtifactSecurity.sha256Hex(file),
        )
    }

    @Test
    fun `requireVersion rejects missing version`() {
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactSecurity.requireVersion(null, "www")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactSecurity.requireVersion(" ", "www")
        }
    }

    @Test
    fun `resolveInsideDirectory accepts nested entry`() {
        val target = ArtifactSecurity.resolveInsideDirectory(tempDir, "assets/app.js")

        assertTrue(target.path.startsWith(tempDir.canonicalPath + File.separator))
        assertEquals("app.js", target.name)
    }

    @Test
    fun `resolveInsideDirectory rejects traversal entry`() {
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactSecurity.resolveInsideDirectory(tempDir, "../escaped-proof.txt")
        }
    }

    @Test
    fun `resolveInsideDirectory rejects empty entry`() {
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactSecurity.resolveInsideDirectory(tempDir, "")
        }
    }

    @Test
    fun `resolveInsideDirectory rejects absolute entry`() {
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactSecurity.resolveInsideDirectory(tempDir, "/tmp/escaped-proof.txt")
        }
    }
}
