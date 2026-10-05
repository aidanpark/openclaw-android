package com.openclaw.android

import android.webkit.JavascriptInterface
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Structural contract between the www sources and the native bridge for M3-3c-0. The www side is
 * only read as text (it is not executed here), so this proves names line up — not behavior. The
 * native side is checked on the compiled class.
 */
class Www3c0ContractTest {
    private fun www(path: String): String {
        val file = File("../www/src/$path")
        assertTrue(file.isFile, "expected ${file.absolutePath}")
        return file.readText()
    }

    @Test
    fun `bridge ts declares getSetupState on OpenClawBridge`() {
        val ts = www("lib/bridge.ts")
        val body =
            Regex("""interface\s+OpenClawBridge\s*\{(.*?)\n\}""", RegexOption.DOT_MATCHES_ALL)
                .find(ts)
                ?.groupValues
                ?.get(1)
        assertTrue(body != null, "interface OpenClawBridge not found")
        assertTrue(Regex("""\bgetSetupState\s*\(\s*\)\s*:\s*string""").containsMatchIn(body!!), body)
    }

    @Test
    fun `compiled JsBridge exposes getSetupState to JavaScript`() {
        val method = JsBridge::class.java.getMethod("getSetupState")
        assertTrue(method.isAnnotationPresent(JavascriptInterface::class.java), "missing @JavascriptInterface")
        assertTrue(method.returnType == String::class.java)
    }

    @Test
    fun `Setup tsx restores from getSetupState for running failed and done`() {
        val tsx = www("screens/Setup.tsx")
        assertTrue(tsx.contains("'getSetupState'"), "Setup.tsx does not call getSetupState")
        for (phase in listOf("running", "failed", "done")) {
            assertTrue(
                Regex("""restored\.phase\s*===\s*'$phase'""").containsMatchIn(tsx),
                "Setup.tsx does not handle restored phase '$phase'",
            )
        }
    }

    @Test
    fun `phases Setup tsx expects are the ones SetupGuard produces`() {
        val tsx = www("screens/Setup.tsx")
        val declared =
            Regex("""phase\?\s*:\s*([^\n]+)""")
                .find(tsx)
                ?.groupValues
                ?.get(1)
                ?.let { Regex("""'([a-z]+)'""").findAll(it).map { m -> m.groupValues[1] }.toSet() }
        val native =
            setOf(SetupGuard.PHASE_IDLE, SetupGuard.PHASE_RUNNING, SetupGuard.PHASE_DONE, SetupGuard.PHASE_FAILED)
        assertTrue(declared == native, "Setup.tsx phases $declared vs SetupGuard $native")
    }

    @Test
    fun `SettingsTools tsx handles TERMINAL_ONLY with the tool_terminal_only text`() {
        val tsx = www("screens/SettingsTools.tsx")
        assertTrue(tsx.contains("'TERMINAL_ONLY'"), "SettingsTools.tsx does not check TERMINAL_ONLY")
        assertTrue(tsx.contains("t('tool_terminal_only')"), "SettingsTools.tsx does not show tool_terminal_only")
    }

    @Test
    fun `every locale defines tool_terminal_only`() {
        for (locale in listOf("en", "ko", "zh")) {
            val src = www("i18n/$locale.ts")
            assertTrue(
                Regex("""\btool_terminal_only\s*:\s*['"`]\S""").containsMatchIn(src),
                "i18n/$locale.ts lacks a non-empty tool_terminal_only",
            )
        }
    }
}
