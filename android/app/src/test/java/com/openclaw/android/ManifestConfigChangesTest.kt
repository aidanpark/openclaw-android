package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * MainActivity must handle the window size changes itself. Split-screen entry and exit are a
 * `screenSize` change: without it in `configChanges` the Activity is recreated, the page reloads
 * and an installed app opens its terminal instead (MainActivity.startInstalledTerminal).
 */
class ManifestConfigChangesTest {
    private val manifest = File("src/main/AndroidManifest.xml")

    private fun mainActivityConfigChanges(): Set<String> {
        assertTrue(manifest.isFile, "manifest not found from ${File(".").absoluteFile}")
        val text = manifest.readText()
        val activity =
            Regex("""<activity\b[^>]*android:name="\.MainActivity"[^>]*>""", RegexOption.DOT_MATCHES_ALL)
                .find(text)
                ?.value
                ?: error("MainActivity <activity> not found")
        val value =
            Regex("""android:configChanges="([^"]*)"""").find(activity)?.groupValues?.get(1)
                ?: error("MainActivity has no configChanges")
        return value.split('|').toSet()
    }

    @Test
    fun `MainActivity handles window size changes without being recreated`() {
        val changes = mainActivityConfigChanges()
        listOf("screenSize", "smallestScreenSize", "screenLayout", "orientation").forEach {
            assertTrue(it in changes, "configChanges lacks $it: $changes")
        }
    }

    @Test
    fun `the earlier configChanges are kept`() {
        val changes = mainActivityConfigChanges()
        listOf("keyboard", "keyboardHidden", "navigation", "uiMode").forEach {
            assertTrue(it in changes, "configChanges lost $it: $changes")
        }
        assertEquals(8, changes.size, "unexpected configChanges: $changes")
    }
}
