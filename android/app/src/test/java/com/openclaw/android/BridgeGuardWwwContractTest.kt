package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The WebView names IDs; native owns what is behind them. These tests read the www sources so an
 * ID the page sends but the allow-list does not know (a silent `—` or a no-op copy) fails here.
 */
class BridgeGuardWwwContractTest {
    private fun wwwFile(path: String): File {
        val file = File("../www/src/$path")
        assertTrue(file.isFile, "expected ${file.absolutePath}")
        return file
    }

    @Nested
    inner class ClipboardTexts {
        @Test
        fun `only ppkCommand is copyable`() {
            assertEquals(setOf("ppkCommand"), BridgeGuard.clipboardTexts.keys)
        }

        @Test
        fun `ppkCommand text is exactly the one SettingsKeepAlive shows`() {
            val tsx = wwwFile("screens/SettingsKeepAlive.tsx").readText()
            val shown =
                Regex("""const\s+ppkCommand\s*=\s*'([^']*)'""")
                    .find(tsx)
                    ?.groupValues
                    ?.get(1)
            assertTrue(shown != null, "ppkCommand constant not found in SettingsKeepAlive.tsx")
            assertEquals(shown, BridgeGuard.clipboardTexts["ppkCommand"])
        }

        @Test
        fun `every id the page copies is known natively`() {
            val ids =
                Regex("""call\(\s*'copyText'\s*,\s*'([^']+)'""")
                    .findAll(wwwFile("screens/SettingsKeepAlive.tsx").readText())
                    .map { it.groupValues[1] }
                    .toSet()
            assertTrue(ids.isNotEmpty())
            assertTrue(BridgeGuard.clipboardTexts.keys.containsAll(ids), ids.toString())
        }

        @Test
        fun `unknown, empty or text-like ids have no clipboard text`() {
            listOf("", "unknown", "PPKCOMMAND", " ppkCommand", "ppkCommand ", "rm -rf ~").forEach {
                assertNull(BridgeGuard.clipboardTexts[it], it)
            }
        }

        @Test
        fun `clipboard texts are single-line`() {
            BridgeGuard.clipboardTexts.values.forEach { text ->
                assertFalse(text.contains('\n') || text.contains('\r'), text)
                assertTrue(text.isNotBlank())
            }
        }
    }

    @Nested
    inner class VersionProbes {
        /** `{ label: '…', commandId: '…' … }` entries without a `cmd:` are probe specs (ProbeSpec). */
        private fun probeIds(path: String): Set<String> =
            Regex("""\{\s*label:\s*'[^']*',\s*commandId:\s*'([^']+)'([^}\n]*)\}""")
                .findAll(wwwFile(path).readText())
                .filterNot { it.groupValues[2].contains("cmd:") }
                .map { it.groupValues[1] }
                .toSet()

        @Test
        fun `Dashboard probe ids are all allowed natively`() {
            val ids = probeIds("screens/Dashboard.tsx")
            assertEquals(setOf("nodeVersion", "gitVersion", "openclawVersion"), ids)
            assertTrue(BridgeGuard.versionCommands.keys.containsAll(ids), ids.toString())
        }

        @Test
        fun `SettingsAbout probe ids are all allowed natively`() {
            val ids = probeIds("screens/SettingsAbout.tsx")
            assertEquals(setOf("nodeVersion", "gitVersion", "oaVersion"), ids)
            assertTrue(BridgeGuard.versionCommands.keys.containsAll(ids), ids.toString())
        }

        @Test
        fun `Dashboard terminal command ids are all allowed natively`() {
            val ids =
                Regex("""commandId:\s*'([^']+)',\s*cmd:\s*'([^']+)'""")
                    .findAll(wwwFile("screens/Dashboard.tsx").readText())
                    .associate { it.groupValues[1] to it.groupValues[2] }
            assertTrue(ids.isNotEmpty())
            ids.forEach { (id, cmd) ->
                // The label the page displays must be the command native actually types
                assertEquals(cmd, BridgeGuard.terminalCommands[id], id)
            }
        }

        @Test
        fun `probe and terminal id namespaces do not overlap`() {
            assertTrue(
                BridgeGuard.versionCommands.keys
                    .intersect(BridgeGuard.terminalCommands.keys)
                    .isEmpty(),
            )
        }
    }
}
