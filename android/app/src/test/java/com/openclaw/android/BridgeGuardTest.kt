package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File

class BridgeGuardTest {
    @Nested
    inner class ParseToolSelections {
        @Test
        fun `parses a map of known tool ids to booleans`() {
            val result = BridgeGuard.parseToolSelections("""{"tmux": true, "claude-code": false, "ttyd": true}""")
            assertEquals(mapOf("tmux" to true, "claude-code" to false, "ttyd" to true), result)
        }

        @Test
        fun `accepts every selection id`() {
            val json = BridgeGuard.toolSelectionIds.joinToString(",", "{", "}") { "\"$it\": true" }
            assertEquals(BridgeGuard.toolSelectionIds, BridgeGuard.parseToolSelections(json)!!.keys)
        }

        @Test
        fun `rejects tools the setup script does not read even though they are installable`() {
            assertNull(BridgeGuard.parseToolSelections("""{"openssh-server": true}"""))
            assertNull(BridgeGuard.parseToolSelections("""{"chromium": false}"""))
            assertNull(BridgeGuard.parseToolSelections("""{"opencode": true}"""))
        }

        @Test
        fun `playwright is accepted even without a UI toggle`() {
            assertEquals(mapOf("playwright" to true), BridgeGuard.parseToolSelections("""{"playwright": true}"""))
        }

        @Test
        fun `empty object yields an empty map`() {
            assertEquals(emptyMap<String, Boolean>(), BridgeGuard.parseToolSelections("{}"))
        }

        @Test
        fun `rejects unknown keys`() {
            assertNull(BridgeGuard.parseToolSelections("""{"tmux": true, "evil": true}"""))
        }

        @Test
        fun `rejects the string true`() {
            assertNull(BridgeGuard.parseToolSelections("""{"tmux": "true"}"""))
        }

        @Test
        fun `rejects numeric and null values`() {
            assertNull(BridgeGuard.parseToolSelections("""{"tmux": 1}"""))
            assertNull(BridgeGuard.parseToolSelections("""{"tmux": null}"""))
        }

        @Test
        fun `rejects nested objects and arrays as values`() {
            assertNull(BridgeGuard.parseToolSelections("""{"tmux": {"a": true}}"""))
            assertNull(BridgeGuard.parseToolSelections("""{"tmux": [true]}"""))
        }

        @Test
        fun `rejects a top-level array`() {
            assertNull(BridgeGuard.parseToolSelections("""[{"tmux": true}]"""))
        }

        @Test
        fun `rejects malformed JSON`() {
            assertNull(BridgeGuard.parseToolSelections("""{"tmux": true"""))
            assertNull(BridgeGuard.parseToolSelections("not json"))
            assertNull(BridgeGuard.parseToolSelections("""{"tmux": true} {"ttyd": true}"""))
        }

        @Test
        fun `rejects empty input and JSON null`() {
            assertNull(BridgeGuard.parseToolSelections(""))
            assertNull(BridgeGuard.parseToolSelections("null"))
        }

        @Test
        fun `rejects keys carrying shell injection`() {
            listOf(
                "tmux\\nrm -rf ~",
                "tmux; rm -rf ~",
                "\$(rm -rf ~)",
                "tmux\$(id)",
                "tmux=1\\nINSTALL_EVIL=true",
                " tmux",
            ).forEach { key ->
                assertNull(BridgeGuard.parseToolSelections("""{"$key": true}"""), key)
            }
        }

        @Test
        fun `rejects values carrying shell injection`() {
            listOf("true\\nrm -rf ~", "true; rm -rf ~", "\$(rm -rf ~)").forEach { value ->
                assertNull(BridgeGuard.parseToolSelections("""{"tmux": "$value"}"""), value)
            }
        }

        @Test
        fun `rejects duplicated keys`() {
            assertNull(BridgeGuard.parseToolSelections("""{"tmux": true, "tmux": false}"""))
        }
    }

    @Nested
    inner class IsSafeExternalUrl {
        @Test
        fun `allows plain https links`() {
            assertTrue(BridgeGuard.isSafeExternalUrl("https://github.com/AidanPark/openclaw-android"))
            assertTrue(BridgeGuard.isSafeExternalUrl("HTTPS://example.com/path?q=1#x"))
        }

        @Test
        fun `rejects non-https schemes`() {
            listOf(
                "http://example.com",
                "file:///data/data/com.openclaw.android/files/x",
                "content://com.android.contacts/contacts",
                "intent://scan/#Intent;scheme=zxing;end",
                "javascript:alert(1)",
                "ftp://example.com",
                "market://details?id=x",
            ).forEach { assertFalse(BridgeGuard.isSafeExternalUrl(it), it) }
        }

        @Test
        fun `rejects scheme-relative and host-less urls`() {
            assertFalse(BridgeGuard.isSafeExternalUrl("//example.com/x"))
            assertFalse(BridgeGuard.isSafeExternalUrl("https:///path"))
            assertFalse(BridgeGuard.isSafeExternalUrl("https:example.com"))
            assertFalse(BridgeGuard.isSafeExternalUrl("/relative/path"))
        }

        @Test
        fun `rejects urls containing whitespace or empty input`() {
            assertFalse(BridgeGuard.isSafeExternalUrl("https://exa mple.com"))
            assertFalse(BridgeGuard.isSafeExternalUrl("https://example.com/a b"))
            assertFalse(BridgeGuard.isSafeExternalUrl(" https://example.com"))
            assertFalse(BridgeGuard.isSafeExternalUrl(""))
        }
    }

    @Nested
    inner class SanitizePlatformId {
        @Test
        fun `keeps a known platform id`() {
            assertEquals("openclaw", BridgeGuard.sanitizePlatformId("openclaw"))
            assertEquals("openclaw", BridgeGuard.sanitizePlatformId("  openclaw\n"))
        }

        @Test
        fun `falls back to the default for null, empty, unknown or injected ids`() {
            listOf(null, "", "   ", "unknown", "openclaw; rm -rf /", "../openclaw", "OPENCLAW").forEach {
                assertEquals(BridgeGuard.DEFAULT_PLATFORM, BridgeGuard.sanitizePlatformId(it), it.toString())
            }
        }
    }

    @Nested
    inner class AllowListInvariants {
        private val safeToken = Regex("^[a-z0-9][a-z0-9._-]*$")

        @Test
        fun `toolIds match the ids listed in SettingsTools tsx`() {
            val tsx = File("../www/src/screens/SettingsTools.tsx")
            assertTrue(tsx.isFile, "expected ${tsx.absolutePath}")
            val ids =
                Regex("""\{\s*id:\s*'([^']+)'""")
                    .findAll(tsx.readText())
                    .map { it.groupValues[1] }
                    .toList()
            assertEquals(12, ids.size, ids.toString())
            assertEquals(ids.toSet(), BridgeGuard.toolIds)
        }

        @Test
        fun `toolSelectionIds match the INSTALL variables post-setup reads`() {
            val script = File("../../post-setup.sh")
            assertTrue(script.isFile, "expected ${script.absolutePath}")
            val allowed =
                Regex("""INSTALL_TMUX\|[A-Z_|]+\)""")
                    .find(script.readText())!!
                    .value
                    .removeSuffix(")")
                    .split('|')
                    .map { it.removePrefix("INSTALL_").lowercase().replace('_', '-') }
                    .toSet()
            assertEquals(allowed, BridgeGuard.toolSelectionIds)
        }

        @Test
        fun `selection ids sent by the setup wizard are accepted`() {
            val tsx = File("../www/src/screens/Setup.tsx")
            assertTrue(tsx.isFile, "expected ${tsx.absolutePath}")
            val sent =
                Regex("""\{\s*id:\s*'([a-z-]+)',\s*name:""")
                    .findAll(tsx.readText().substringBefore("openclaw"))
                    .map { it.groupValues[1] }
                    .toSet()
            assertTrue(sent.isNotEmpty())
            assertTrue(BridgeGuard.toolSelectionIds.containsAll(sent), sent.toString())
        }

        @Test
        fun `toolIds are plain tokens`() {
            BridgeGuard.toolIds.forEach { assertTrue(safeToken.matches(it), it) }
        }

        @Test
        fun `terminal commands contain no line breaks or chaining metacharacters`() {
            assertTrue(BridgeGuard.terminalCommands.isNotEmpty())
            BridgeGuard.terminalCommands.values.forEach { cmd ->
                assertFalse(cmd.contains('\n') || cmd.contains('\r'), cmd)
                assertFalse(Regex("[;&|`$<>]").containsMatchIn(cmd), cmd)
            }
        }

        @Test
        fun `version commands are bare executables with no shell metacharacters`() {
            BridgeGuard.versionCommands.values.forEach { cmd ->
                assertTrue(safeToken.matches(cmd.executable), cmd.executable)
                cmd.args.forEach { assertFalse(Regex("[;&|`$<>\\s]").containsMatchIn(it), it) }
            }
        }

        @Test
        fun `oaVersion keeps only the first line and other probes do not`() {
            assertTrue(BridgeGuard.versionCommands.getValue("oaVersion").firstLineOnly)
            BridgeGuard.versionCommands
                .filterKeys { it != "oaVersion" }
                .values
                .forEach { assertFalse(it.firstLineOnly, it.executable) }
        }

        @Test
        fun `version probes cover the expected ids`() {
            assertEquals(
                setOf("nodeVersion", "gitVersion", "openclawVersion", "oaVersion"),
                BridgeGuard.versionCommands.keys,
            )
        }

        @Test
        fun `default platform is registered with a plain package name`() {
            assertTrue(BridgeGuard.isPlatform(BridgeGuard.DEFAULT_PLATFORM))
            BridgeGuard.platformPackages.forEach { (id, pkg) ->
                assertTrue(safeToken.matches(id), id)
                assertTrue(Regex("^(@[a-z0-9._-]+/)?[a-z0-9._-]+$").matches(pkg), pkg)
            }
        }
    }
}
