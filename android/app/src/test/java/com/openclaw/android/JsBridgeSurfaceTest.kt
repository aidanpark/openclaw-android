package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * JsBridge needs a live Activity, so its WebView-facing surface is checked against the source:
 * removed dangerous methods must not come back, and every method the page's typed bridge calls
 * must exist natively.
 */
class JsBridgeSurfaceTest {
    private val source: String by lazy {
        val file = File("src/main/java/com/openclaw/android/JsBridge.kt")
        assertTrue(file.isFile, "expected ${file.absolutePath}")
        file.readText()
    }

    private val exposed: List<String> by lazy {
        Regex("""@JavascriptInterface\s+fun\s+(\w+)""").findAll(source).map { it.groupValues[1] }.toList()
    }

    private val wwwBridgeMethods: Set<String> by lazy {
        val file = File("../www/src/lib/bridge.ts")
        assertTrue(file.isFile, "expected ${file.absolutePath}")
        val body =
            Regex("""interface\s+OpenClawBridge\s*\{([^}]*)\}""")
                .find(file.readText())
                ?.groupValues
                ?.get(1)
                .orEmpty()
        Regex("""(?m)^\s*(\w+)\s*\(""").findAll(body).map { it.groupValues[1] }.toSet()
    }

    /** Text of `fun <name>(` up to the next `@JavascriptInterface` (or end of file). */
    private fun methodBody(name: String): String {
        val start = source.indexOf("fun $name(")
        assertTrue(start >= 0, "fun $name not found")
        val end = source.indexOf("@JavascriptInterface", start).takeIf { it >= 0 } ?: source.length
        return source.substring(start, end)
    }

    @Test
    fun `every JavascriptInterface annotation is on a function the regex sees`() {
        val annotations = Regex("""(?m)^\s*@JavascriptInterface\s*$""").findAll(source).count()
        assertEquals(annotations, exposed.size)
        assertEquals(exposed.size, exposed.toSet().size, "duplicate exposed names: $exposed")
    }

    @Test
    fun `removed dangerous or blocking methods are not exposed`() {
        val removed =
            listOf(
                "runCommand",
                "runCommandAsync",
                "getApkUpdateInfo",
                "getInstalledPlatforms",
                "copyToClipboard",
                "writeToTerminal",
                "runInNewSession",
            )
        removed.forEach { assertTrue(it !in exposed, "$it must not be a @JavascriptInterface method") }
    }

    @Test
    fun `new async and id-based methods are exposed`() {
        listOf("runProbeAsync", "getApkUpdateInfoAsync", "copyText", "startSetup").forEach {
            assertTrue(it in exposed, "$it should be a @JavascriptInterface method")
        }
    }

    @Test
    fun `every method the www bridge types declare exists natively`() {
        assertTrue(wwwBridgeMethods.size > 10, wwwBridgeMethods.toString())
        val missing = wwwBridgeMethods - exposed.toSet()
        assertTrue(missing.isEmpty(), "bridge.ts calls methods JsBridge does not expose: $missing")
    }

    @Test
    fun `every exposed native method has a typed www binding`() {
        val untyped = exposed.toSet() - wwwBridgeMethods
        assertTrue(untyped.isEmpty(), "JsBridge exposes methods bridge.ts does not declare: $untyped")
    }

    @Test
    fun `copyText looks the text up by id and never takes raw text`() {
        val body = methodBody("copyText")
        assertTrue(body.startsWith("fun copyText(textId: String)"), body.lineSequence().first())
        assertTrue(Regex("""BridgeGuard\.clipboardTexts\[textId]\s*\?:\s*return""").containsMatchIn(body), body)
    }

    @Test
    fun `runProbeAsync resolves the command through the version allow-list`() {
        val body = methodBody("runProbeAsync")
        assertTrue(body.contains("BridgeGuard.versionCommands[commandId]"), body)
        assertTrue(body.contains("ProbeLimiter.semaphore.withPermit"), body)
        assertTrue(body.contains("\"command_result\""), body)
    }

    @Test
    fun `getApkUpdateInfoAsync answers on the apk_update_info event with its callback id`() {
        val body = methodBody("getApkUpdateInfoAsync")
        assertTrue(body.contains("launchWithErrorHandling"), body)
        assertTrue(
            Regex("""emit\(\s*"apk_update_info",\s*mapOf\("callbackId" to callbackId\)""").containsMatchIn(body),
            body,
        )
        assertTrue(Regex("""errorContext\s*=\s*mapOf\("callbackId" to callbackId\)""").containsMatchIn(body), body)
    }

    @Test
    fun `startSetup refuses a second run before launching and releases the guard in finally`() {
        val body = methodBody("startSetup")
        val guard = body.indexOf("if (!SetupGuard.tryStart())")
        val launch = body.indexOf("launchWithErrorHandling")
        assertTrue(guard >= 0, "SetupGuard.tryStart guard missing")
        assertTrue(launch > guard, "the guard must run before the coroutine is launched")
        assertTrue(
            Regex("""finally\s*\{\s*SetupGuard\.finish\(\)""").containsMatchIn(body),
            "guard not released in finally",
        )
    }

    @Test
    fun `startSetup reports an errorKind for both refusals and unexpected failures`() {
        val body = methodBody("startSetup")
        assertTrue(body.contains("catch (e: BootstrapDownloadException)"), body)
        assertTrue(body.contains("\"errorKind\" to e.kind.name"), body)
        assertTrue(Regex(""""error" to \(e\.message \?: e\.kind\.name\)""").containsMatchIn(body), body)
        assertTrue(
            Regex("""errorContext\s*=\s*mapOf\([^)]*"errorKind" to "UNKNOWN"""").containsMatchIn(body),
            "unexpected failures should carry errorKind UNKNOWN",
        )
    }
}
