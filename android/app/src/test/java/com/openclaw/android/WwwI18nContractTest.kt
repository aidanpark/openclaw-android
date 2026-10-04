package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Locale files must stay in step, and the setup failure screen must know every download error kind. */
class WwwI18nContractTest {
    private val locales = listOf("en", "ko", "zh")

    private fun wwwFile(path: String): File {
        val file = File("../www/src/$path")
        assertTrue(file.isFile, "expected ${file.absolutePath}")
        return file
    }

    /** key → raw value text (quotes stripped) for every `  key: '…',` line. */
    private fun entries(locale: String): Map<String, String> =
        Regex("""(?m)^\s+([a-z_0-9]+):\s*(.*)$""")
            .findAll(wwwFile("i18n/$locale.ts").readText())
            .associate { m ->
                val raw =
                    m.groupValues[2]
                        .trim()
                        .removeSuffix(",")
                        .trim()
                m.groupValues[1] to raw.removeSurrounding("'").removeSurrounding("\"").removeSurrounding("`")
            }

    private val newKeys =
        listOf(
            "setup_failed_title",
            "setup_err_network",
            "setup_err_missing",
            "setup_err_hash",
            "setup_err_local",
            "setup_err_unknown",
            "setup_retry",
            "error_boundary_title",
            "error_boundary_reload",
        )

    @Test
    fun `all locales define the same keys`() {
        val en = entries("en").keys
        assertTrue(en.size > 50, "too few keys parsed from en.ts: ${en.size}")
        locales.drop(1).forEach { locale ->
            val other = entries(locale).keys
            assertEquals(emptySet<String>(), en - other, "keys missing from $locale.ts")
            assertEquals(emptySet<String>(), other - en, "keys only in $locale.ts")
        }
    }

    @Test
    fun `new setup and error boundary keys exist with non-empty values in every locale`() {
        locales.forEach { locale ->
            val map = entries(locale)
            newKeys.forEach { key ->
                val value = map[key]
                assertTrue(value != null, "$key missing from $locale.ts")
                assertTrue(value!!.isNotBlank(), "$key is empty in $locale.ts")
            }
        }
    }

    @Test
    fun `every literal t key used in www sources is defined in en`() {
        val defined = entries("en").keys
        val used =
            File("../www/src")
                .walkTopDown()
                .filter { it.isFile && (it.extension == "ts" || it.extension == "tsx") && "i18n" !in it.path }
                .flatMap { f -> Regex("""\bt\('([a-z_0-9]+)'\)""").findAll(f.readText()).map { it.groupValues[1] } }
                .toSet()
        assertTrue(used.containsAll(newKeys), "new keys not referenced: ${newKeys - used}")
        assertEquals(emptySet<String>(), used - defined, "t() keys not in en.ts")
    }

    @Test
    fun `Setup failure screen maps every BootstrapDownloadException kind`() {
        val kinds =
            BootstrapDownloadException.Kind.entries
                .map { it.name }
                .toSet()
        val tsx = wwwFile("screens/Setup.tsx").readText()
        val block =
            Regex("""const\s+messages\s*:\s*Record<string,\s*string>\s*=\s*\{([^}]*)\}""")
                .find(tsx)
                ?.groupValues
                ?.get(1)
        assertTrue(block != null, "messages map not found in Setup.tsx")
        val mapped =
            Regex("""([A-Z_]+):\s*t\('(setup_err_[a-z_]+)'\)""")
                .findAll(block!!)
                .associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(kinds, mapped.keys, "Setup.tsx error kinds vs BootstrapDownloadException.Kind")
        assertEquals(mapped.size, mapped.values.toSet().size, "each kind should have its own message")
    }

    @Test
    fun `Setup falls back to the unknown message for an unmapped kind`() {
        val tsx = wwwFile("screens/Setup.tsx").readText()
        assertTrue(Regex("""messages\[errorKind]\s*\|\|\s*t\('setup_err_unknown'\)""").containsMatchIn(tsx))
    }
}
