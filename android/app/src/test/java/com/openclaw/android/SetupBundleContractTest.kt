package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The www bundle the APK ships (`assets/www`): `index.html` names a script and a stylesheet that
 * exist, nothing else lies next to them (no stale bundle), and the script was built from the stage B
 * sources — every `setup_*` text of the three locales, the onboarding command id, `getSetupResult`
 * and the resume route are in it.
 */
internal class SetupBundleContractTest {
    private val wwwAssets = File("src/main/assets/www")
    private val index by lazy { File(wwwAssets, "index.html").readText() }

    private fun referenced(pattern: String): String {
        val m = Regex(pattern).find(index)
        assertNotNull(m, "index.html names no $pattern")
        return m!!.groupValues[1]
    }

    private val jsPath by lazy {
        referenced("""<script type="module" crossorigin src="\./(assets/index-[A-Za-z0-9_-]+\.js)"></script>""")
    }
    private val cssPath by lazy {
        referenced("""<link rel="stylesheet" crossorigin href="\./(assets/index-[A-Za-z0-9_-]+\.css)">""")
    }

    /** The bundle with `\uXXXX` / `\u{…}` escapes and escaped quotes turned back into text. */
    private val bundle: String by lazy {
        val raw = File(wwwAssets, jsPath).readText()
        val braces =
            Regex(
                """\\u\{([0-9a-fA-F]+)\}""",
            ).replace(raw) { String(Character.toChars(it.groupValues[1].toInt(16))) }
        Regex("""\\u([0-9a-fA-F]{4})""")
            .replace(braces) {
                it.groupValues[1]
                    .toInt(16)
                    .toChar()
                    .toString()
            }.replace("\\'", "'")
            .replace("\\\"", "\"")
    }

    private fun entries(locale: String): Map<String, String> =
        Regex("""(?m)^\s+([a-z_0-9]+):\s*(['"`])(.*)\2,?\s*$""")
            .findAll(File("../www/src/i18n/$locale.ts").readText())
            .associate { it.groupValues[1] to it.groupValues[3].replace("\\'", "'").replace("\\\"", "\"") }

    @Test
    fun `index html names a script and a stylesheet that exist, and nothing else is in assets`() {
        assertTrue(File(wwwAssets, jsPath).isFile, jsPath)
        assertTrue(File(wwwAssets, cssPath).isFile, cssPath)
        val files = File(wwwAssets, "assets").list()!!.map { "assets/$it" }.toSet()
        assertEquals(setOf(jsPath, cssPath), files, "a stale or missing bundle file in assets/www/assets")
        assertEquals(1, Regex("""<script type="module"""").findAll(index).count())
    }

    @Test
    fun `the bundle has every setup text of the three locales`() {
        for (locale in listOf("en", "ko", "zh")) {
            val missing = entries(locale).filterKeys { it.startsWith("setup_") }.filterValues { it !in bundle }
            assertEquals(emptyMap<String, String>(), missing, "$locale texts missing from the bundle (rebuild www)")
        }
    }

    @Test
    fun `the bundle calls getSetupResult, types openclawOnboard and has the resume route`() {
        val quoted = { s: String -> Regex("""["'`]${Regex.escape(s)}["'`]""") }
        listOf("getSetupResult", "openclawOnboard", "resume", "SETUP", "tools:", "startRun", "cancelRun").forEach {
            assertTrue(quoted(it).containsMatchIn(bundle), "bundle lacks the literal $it")
        }
        // routeFor: the bootstrap without the marker resumes (setupRoute.ts), whatever the minifier named things
        val route = Regex("""bootstrapInstalled\s*\?\s*["']resume["']\s*:\s*["']setup["']""")
        assertTrue(route.containsMatchIn(bundle), "the routeFor logic is not in the bundle")
    }
}
