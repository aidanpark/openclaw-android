package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

class ArtifactSecurityTest {
    private val abcSha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val emptySha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    @Nested
    inner class RequireSha256 {
        @Test
        fun `accepts a lowercase 64-char hex digest unchanged`() {
            assertEquals(abcSha, ArtifactSecurity.requireSha256(abcSha, "T"))
        }

        @Test
        fun `normalizes uppercase and surrounding whitespace to lowercase`() {
            assertEquals(abcSha, ArtifactSecurity.requireSha256("  ${abcSha.uppercase()}\n", "T"))
        }

        @Test
        fun `rejects null`() {
            assertThrows(IllegalArgumentException::class.java) { ArtifactSecurity.requireSha256(null, "T") }
        }

        @Test
        fun `rejects empty and blank strings`() {
            assertThrows(IllegalArgumentException::class.java) { ArtifactSecurity.requireSha256("", "T") }
            assertThrows(IllegalArgumentException::class.java) { ArtifactSecurity.requireSha256("   ", "T") }
        }

        @Test
        fun `rejects digests that are one char too short or too long`() {
            assertThrows(IllegalArgumentException::class.java) {
                ArtifactSecurity.requireSha256(abcSha.dropLast(1), "T")
            }
            assertThrows(IllegalArgumentException::class.java) { ArtifactSecurity.requireSha256(abcSha + "0", "T") }
        }

        @Test
        fun `rejects non-hex characters, prefixes and inner whitespace`() {
            assertThrows(IllegalArgumentException::class.java) {
                ArtifactSecurity.requireSha256("g" + abcSha.drop(1), "T")
            }
            assertThrows(IllegalArgumentException::class.java) {
                ArtifactSecurity.requireSha256("sha256:$abcSha", "T")
            }
            assertThrows(IllegalArgumentException::class.java) {
                ArtifactSecurity.requireSha256(abcSha.take(32) + " " + abcSha.drop(33), "T")
            }
        }

        @Test
        fun `error message names the label`() {
            val e = assertThrows(IllegalArgumentException::class.java) { ArtifactSecurity.requireSha256("x", "Boot") }
            assertTrue(e.message!!.contains("Boot"))
        }
    }

    @Nested
    inner class Hashing {
        @TempDir
        lateinit var tempDir: File

        @Test
        fun `toHex encodes every byte as two lowercase hex digits`() {
            val bytes = byteArrayOf(0, 0x0f, 0x10, 0x7f, -128, -1)
            assertEquals("000f107f80ff", ArtifactSecurity.toHex(bytes))
        }

        @Test
        fun `toHex of empty array is empty string`() {
            assertEquals("", ArtifactSecurity.toHex(ByteArray(0)))
        }

        @Test
        fun `sha256Hex matches the known digest of abc`() {
            val file = File(tempDir, "abc").apply { writeText("abc") }
            assertEquals(abcSha, ArtifactSecurity.sha256Hex(file))
        }

        @Test
        fun `sha256Hex matches the known digest of an empty file`() {
            val file = File(tempDir, "empty").apply { writeBytes(ByteArray(0)) }
            assertEquals(emptySha, ArtifactSecurity.sha256Hex(file))
        }

        @Test
        fun `sha256Hex handles files larger than one buffer`() {
            val data = ByteArray(DEFAULT_BUFFER_SIZE * 3 + 7) { (it % 251).toByte() }
            val file = File(tempDir, "big").apply { writeBytes(data) }
            val expected =
                ArtifactSecurity.toHex(
                    java.security.MessageDigest
                        .getInstance("SHA-256")
                        .digest(data),
                )
            assertEquals(expected, ArtifactSecurity.sha256Hex(file))
        }
    }

    @Nested
    inner class ResolveInsideDirectory {
        @TempDir
        lateinit var tempDir: File

        private lateinit var root: File
        private lateinit var outside: File

        @BeforeEach
        fun setup() {
            root = File(tempDir, "root").apply { mkdirs() }
            outside = File(tempDir, "outside").apply { mkdirs() }
        }

        private fun rejects(name: String) {
            assertThrows(IllegalArgumentException::class.java) {
                ArtifactSecurity.resolveInsideDirectory(root, name)
            }
        }

        @Test
        fun `resolves a simple name directly under root`() {
            val f = ArtifactSecurity.resolveInsideDirectory(root, "file.txt")
            assertEquals(File(root.canonicalFile, "file.txt"), f)
        }

        @Test
        fun `resolves a nested path even when intermediate dirs do not exist yet`() {
            val f = ArtifactSecurity.resolveInsideDirectory(root, "a/b/c.txt")
            assertEquals(File(root.canonicalFile, "a/b/c.txt"), f)
        }

        @Test
        fun `rejects parent traversal at the start`() = rejects("../x")

        @Test
        fun `rejects traversal that climbs out through a subdirectory`() = rejects("a/../../x")

        @Test
        fun `rejects a dotdot component even if it would stay inside`() = rejects("a/../x")

        @Test
        fun `accepts the dot-slash prefix Termux SYMLINKS txt uses`() {
            // Real lines look like ".../thunder-coding.gpg←./etc/apt/trusted.gpg.d/thunder-coding.gpg"
            val target = ArtifactSecurity.resolveInsideDirectory(tempDir, "./etc/apt/trusted.gpg.d/thunder-coding.gpg")
            assertEquals(File(tempDir.canonicalFile, "etc/apt/trusted.gpg.d/thunder-coding.gpg"), target)
        }

        @Test
        fun `dot segments are dropped and the result stays inside`() {
            assertEquals(File(tempDir.canonicalFile, "a/b"), ArtifactSecurity.resolveInsideDirectory(tempDir, "a/./b"))
            assertEquals(File(tempDir.canonicalFile, "a"), ArtifactSecurity.resolveInsideDirectory(tempDir, "./a/."))
        }

        @Test
        fun `rejects names that reduce to the root itself`() {
            rejects(".")
            rejects("./")
            rejects("./.")
            rejects("//")
        }

        @Test
        fun `dot segments do not hide a traversal`() {
            rejects("./../x")
            rejects("a/./../../x")
        }

        @Test
        fun `rejects absolute paths`() = rejects("/etc/passwd")

        @Test
        fun `rejects the empty name`() = rejects("")

        @Test
        fun `rejects backslashes`() = rejects("a\\..\\..\\x")

        @Test
        fun `rejects NUL bytes`() = rejects("a\u0000.txt")

        @Test
        fun `a trailing slash name resolves to the directory itself inside root`() {
            val f = ArtifactSecurity.resolveInsideDirectory(root, "dir/")
            assertEquals(File(root.canonicalFile, "dir"), f)
        }

        @Test
        fun `rejects an entry whose parent is a symlink pointing outside root`() {
            Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
            rejects("link/evil.txt")
        }

        @Test
        fun `rejects an entry under a nested symlinked parent pointing outside root`() {
            File(root, "a").mkdirs()
            Files.createSymbolicLink(File(root, "a/link").toPath(), outside.toPath())
            rejects("a/link/b/evil.txt")
        }

        @Test
        fun `rejects a symlink to a sibling directory sharing the root name as prefix`() {
            val sibling = File(tempDir, "rootEvil").apply { mkdirs() }
            Files.createSymbolicLink(File(root, "link").toPath(), sibling.toPath())
            rejects("link/x")
        }

        @Test
        fun `accepts a parent symlink that points to another directory inside root`() {
            val real = File(root, "real").apply { mkdirs() }
            Files.createSymbolicLink(File(root, "alias").toPath(), real.toPath())
            val f = ArtifactSecurity.resolveInsideDirectory(root, "alias/x.txt")
            assertEquals(File(real.canonicalFile, "x.txt"), f)
        }

        @Test
        fun `does not follow a symlink at the leaf and returns its own path`() {
            val target = File(outside, "target.txt").apply { writeText("secret") }
            val leaf = File(root, "leaf.txt")
            Files.createSymbolicLink(leaf.toPath(), target.toPath())

            val f = ArtifactSecurity.resolveInsideDirectory(root, "leaf.txt")

            assertEquals(File(root.canonicalFile, "leaf.txt").path, f.path)
            assertTrue(Files.isSymbolicLink(f.toPath()))
            assertFalse(f.path.startsWith(outside.canonicalPath))
        }

        @Test
        fun `works when root itself is given through a symlink`() {
            val rootLink = File(tempDir, "rootLink")
            Files.createSymbolicLink(rootLink.toPath(), root.toPath())
            val f = ArtifactSecurity.resolveInsideDirectory(rootLink, "a/b.txt")
            assertEquals(File(root.canonicalFile, "a/b.txt"), f)
        }
    }
}
