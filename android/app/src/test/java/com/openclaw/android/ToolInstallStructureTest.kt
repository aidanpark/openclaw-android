package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * Structure of the tool-install path: the app builds no install command of its own (checked in
 * the source AND in the compiled constant pools, including coroutine/lambda classes), the guard
 * cannot be shadowed by an instance field, and the id lists agree with the script and the page.
 * Behavior is covered by JsBridgeToolInstallTest; these catch a dangerous path coming back.
 */
internal class ToolInstallStructureTest {
    private val forbidden =
        listOf(
            "apt-get",
            "AllowUnauthenticated",
            "AllowInsecureRepositories",
            "npm install",
            "npm uninstall",
            "curl -fsSL",
            "install-opencode",
        )

    private fun file(path: String): File {
        val f = File(path)
        assertTrue(f.isFile, "expected ${f.absolutePath}")
        return f
    }

    private val jsBridgeSource by lazy { file("src/main/java/com/openclaw/android/JsBridge.kt").readText() }
    private val bootstrapSource by lazy { file("src/main/java/com/openclaw/android/BootstrapManager.kt").readText() }
    private val settingsTools by lazy { file("../www/src/screens/SettingsTools.tsx").readText() }

    /** Text of `fun <name>(` up to the next top-level member annotation or `fun`. */
    private fun body(
        source: String,
        name: String,
    ): String {
        val start = source.indexOf("fun $name(")
        assertTrue(start >= 0, "fun $name not found")
        val next = Regex("""\n    (@JavascriptInterface|private fun|fun|internal fun)\b""").find(source, start + 1)
        return source.substring(start, next?.range?.first ?: source.length)
    }

    // ── no app-built install command ────────────────────────────────────────

    @Test
    fun `JsBridge source names no install command`() {
        forbidden.forEach { assertFalse(jsBridgeSource.contains(it), "JsBridge.kt contains '$it'") }
    }

    @Test
    fun `compiled JsBridge classes carry no install command`() {
        val texts = compiledTexts("JsBridge")
        assertTrue(
            texts.keys.any {
                it.startsWith("JsBridge\$installTool")
            },
            "installTool's coroutine class missing: ${texts.keys}",
        )
        for ((name, text) in texts) {
            forbidden.forEach { assertFalse(text.contains(it), "$name contains '$it'") }
        }
    }

    @Test
    fun `compiled JsBridge calls the script entry with tools-only and list`() {
        val all = compiledTexts("JsBridge").values.joinToString("\n")
        assertTrue(all.contains("--tools-only"), "no --tools-only constant")
        assertTrue(all.contains("--list"), "no --list constant")
        assertTrue(all.contains("getToolInstallIds"), "installTool does not read BridgeGuard.toolInstallIds")
        assertTrue(all.contains("com/openclaw/android/ToolInstallGuard"), "JsBridge does not use ToolInstallGuard")
        assertTrue(
            all.contains("com/openclaw/android/ToolInstallVerdict"),
            "JsBridge does not decide through ToolInstallVerdict",
        )
        assertTrue(all.contains("com/openclaw/android/ToolListCheck"), "JsBridge does not check --list output")
    }

    @Test
    fun `no compiled class in the app package runs apt-get or npm or curl for tools`() {
        val texts = compiledTexts("")
        assertTrue(texts.size > 20, "too few classes found: ${texts.size}")
        for ((name, text) in texts) {
            listOf("apt-get", "npm install", "npm uninstall", "curl -fsSL", "install-opencode").forEach {
                assertFalse(text.contains(it), "$name contains '$it'")
            }
        }
    }

    @Test
    fun `BootstrapManager names the insecure apt options only to remove them`() {
        // The literals exist exactly once each, inside removeInsecureAptOptions' filter
        val filter = body(bootstrapSource, "removeInsecureAptOptions")
        for (opt in listOf("Acquire::AllowInsecureRepositories", "APT::Get::AllowUnauthenticated")) {
            assertEquals(1, Regex(Regex.escape(opt)).findAll(bootstrapSource).count(), opt)
            assertTrue(filter.contains("startsWith(\"$opt\")"), "$opt is not a removal filter")
        }
        assertFalse(
            Regex("""Allow\w+\s+"true"""").containsMatchIn(bootstrapSource),
            "an Allow* setting line is written",
        )
        val configure = body(bootstrapSource, "configureApt")
        assertFalse(configure.contains("Allow"), "configureApt mentions Allow*")
        assertFalse(configure.contains("http://"), "configureApt downgrades https")
    }

    @Test
    fun `compiled BootstrapManager writes no Allow setting line`() {
        val text = compiledTexts("BootstrapManager").values.joinToString("\n")
        assertFalse(Regex("""Allow\w+\s*"true"""").containsMatchIn(text))
        assertFalse(text.contains("AllowInsecureRepositories \"true\""))
    }

    // ── the guard cannot be shadowed ────────────────────────────────────────

    @Test
    fun `JsBridge and BootstrapManager keep no guard state in instance fields`() {
        val guardTypes =
            listOf(
                java.util.concurrent.atomic.AtomicBoolean::class.java,
                java.util.concurrent.atomic.AtomicReference::class.java,
                kotlinx.coroutines.sync.Semaphore::class.java,
                ToolInstallGuard::class.java,
                ToolInstallGuard.State::class.java,
            )
        for (cls in listOf(JsBridge::class.java, BootstrapManager::class.java)) {
            val offenders =
                cls.declaredFields
                    .filter { !Modifier.isStatic(it.modifiers) }
                    .filter { f -> guardTypes.any { it.isAssignableFrom(f.type) } }
            assertTrue(offenders.isEmpty(), "${cls.simpleName}: $offenders")
            val dup =
                cls.declaredFields
                    .filter { it.name != "Companion" && !it.isSynthetic && !it.name.startsWith("$") }
                    .groupBy { it.name.replace(Regex("""\$\d+$"""), "") }
                    .filterValues { it.size > 1 }
            assertTrue(dup.isEmpty(), "${cls.simpleName} shadows: ${dup.keys}")
            assertFalse(
                cls.declaredFields.any {
                    it.name.contains("toolInstall", ignoreCase = true)
                },
                cls.declaredFields.map { it.name }.toString(),
            )
        }
    }

    @Test
    fun `ToolInstallGuard's state is static and declared once`() {
        val fields = ToolInstallGuard::class.java.declaredFields
        for (name in listOf("running", "process", "state")) {
            val f = fields.filter { it.name.replace(Regex("""\$\d+$"""), "") == name }
            assertEquals(1, f.size, "$name: ${fields.map { it.name }}")
            assertTrue(Modifier.isStatic(f.single().modifiers), "$name is not static")
        }
        assertTrue(Modifier.isVolatile(fields.single { it.name == "state" }.modifiers), "state is not volatile")
    }

    // ── installTool ordering (source; behavior in JsBridgeToolInstallTest) ──

    @Test
    fun `installTool checks the allow-list, then terminal-only, then the mapping, then the guard, then launches`() {
        val b = body(jsBridgeSource, "installTool")
        val order =
            listOf(
                "id !in BridgeGuard.toolIds",
                "id in BridgeGuard.terminalOnlyTools",
                "BridgeGuard.toolInstallIds[id] ?: return",
                "ToolInstallGuard.tryStart(",
                "launchWithErrorHandling(",
                "runToolInstall(id, scriptId, startedAtSec)",
                "toolOutcomes.record(id, verdict)",
                "ToolInstallGuard.finish(verdict)",
            )
        val at = order.map { b.indexOf(it) }
        order.zip(at).forEach { (s, i) -> assertTrue(i >= 0, "'$s' missing from installTool") }
        assertEquals(at.sorted(), at, "installTool steps out of order: ${order.zip(at)}")
        // Exactly three: the outer finally (after the run), the end emit's finally around the record,
        // and the record's own finally (which releases the guard), each nested in the one before
        val finallys = Regex("""\bfinally\s*\{""").findAll(b).toList()
        assertEquals(3, finallys.size, "installTool's finally blocks are not outer > end emit > record: $finallys")
        val outer = blockAfter(b, finallys[0].range.last)
        val nested = Regex("""\bfinally\s*\{""").findAll(outer).count()
        assertEquals(2, nested, "the other finallys are not in the outer one")
    }

    /**
     * The outer finally's text and, inside it, `try { try { record } catch (…) { … } finally { … } }
     * finally { … }`: the wrapping try's body, the record's try body, its catch type, the record's
     * own finally body, and the wrapping try's finally body (the end emit).
     */
    private data class FinallyShape(
        val outer: String,
        val wrapBody: String,
        val tryBody: String,
        val catchType: String,
        val innerFinally: String,
        val endFinally: String,
    )

    private fun finallyShape(): FinallyShape {
        val b = body(jsBridgeSource, "installTool")
        val outer = blockAfter(b, Regex("""\bfinally\s*\{""").find(b)!!.range.last)
        // the try that wraps the record and its guard release; its finally sends the end
        val wrapAt = Regex("""\btry\s*\{""").find(outer)
        assertNotNull(wrapAt, "no try in the outer finally")
        val wrapEnd = closingBrace(outer, wrapAt!!.range.last)
        val wrapBody = outer.substring(wrapAt.range.last + 1, wrapEnd)
        val endAt = Regex("""^\s*finally\s*\{""").find(outer.substring(wrapEnd + 1))
        assertNotNull(endAt, "the try around the record has no finally: ${outer.substring(wrapEnd + 1).take(SHOW)}")
        val endFinally = blockAfter(outer, wrapEnd + 1 + endAt!!.range.last)
        // inside it: try { record } catch (…) { … } finally { finish }
        val tryAt = Regex("""\btry\s*\{""").find(wrapBody)
        assertNotNull(tryAt, "no try around record inside the wrapping try")
        val tryEnd = closingBrace(wrapBody, tryAt!!.range.last)
        val tryBody = wrapBody.substring(tryAt.range.last + 1, tryEnd)
        val catchAt =
            Regex("""^\s*catch\s*\(\s*\w+\s*:\s*(\w+)\s*\)\s*\{""").find(wrapBody.substring(tryEnd + 1))
        assertNotNull(catchAt, "record's try has no catch: ${wrapBody.substring(tryEnd + 1).take(SHOW)}")
        val catchOpen = tryEnd + 1 + catchAt!!.range.last
        val catchEnd = closingBrace(wrapBody, catchOpen)
        val finAt = Regex("""^\s*finally\s*\{""").find(wrapBody.substring(catchEnd + 1))
        assertNotNull(finAt, "record's try/catch has no finally of its own")
        val innerFinally = blockAfter(wrapBody, catchEnd + 1 + finAt!!.range.last)
        return FinallyShape(outer, wrapBody, tryBody, catchAt.groupValues[1], innerFinally, endFinally)
    }

    @Test
    fun `installTool's finally records inside its own catch and its own finally releases the guard`() {
        val shape = finallyShape()
        // record sits alone in a try whose catch takes every Exception (a narrower catch lets an NPE through)
        assertEquals("toolOutcomes.record(id, verdict)", shape.tryBody.trim())
        assertTrue(shape.catchType in setOf("Exception", "Throwable"), "record's catch is ${shape.catchType}")
        // finish is the whole inner finally: it runs even when record throws an Error the catch lets through
        assertEquals("ToolInstallGuard.finish(verdict)", shape.innerFinally.trim())
        // the wrapping try holds only the record's try/catch/finally: nothing else may throw before finish
        val wrapStatements =
            topLevel(shape.wrapBody)
                .replace(Regex("""//[^\n]*"""), "")
                .replace(Regex("""\b(try|catch\s*\([^)]*\)|finally)"""), "")
                .trim()
        assertEquals("", wrapStatements, "the try around the record does more than record and release")
        // record and finish appear nowhere else in the outer finally (not unguarded, not twice)
        val top = topLevel(shape.outer)
        assertFalse(top.contains("toolOutcomes.record("), "record runs unguarded in finally")
        assertFalse(top.contains("ToolInstallGuard.finish("), "finish also runs outside the record's finally")
        assertEquals(1, Regex("""ToolInstallGuard\.finish\(""").findAll(shape.outer).count())
        assertEquals(1, Regex("""toolOutcomes\.record\(""").findAll(shape.outer).count())
    }

    @Test
    fun `installTool's finally emits the end in a finally of its own, after the guard is released`() {
        val shape = finallyShape()
        // the end emit is the whole finally around the record: it runs even if record throws an Error
        assertEquals("emitToolState()", shape.endFinally.replace(Regex("""//[^\n]*"""), "").trim())
        assertFalse(shape.wrapBody.contains("emitToolState()"), "the end is emitted inside the record's try")
        assertEquals(1, Regex("""emitToolState\(\)""").findAll(shape.outer).count(), "the end is emitted twice")
        assertTrue(shape.outer.lastIndexOf("emitToolState()") > shape.outer.indexOf("ToolInstallGuard.finish(verdict)"))
        // the timers are stopped before anything else can throw
        val top = topLevel(shape.outer)
        assertTrue(top.indexOf("longRunning.cancel()") in 0 until top.indexOf("cancelPump.cancel()"))
        assertTrue(top.indexOf("cancelPump.cancel()") < top.indexOf("try"), "a timer is stopped after the record")
        assertTrue(shape.outer.indexOf("cancelPump.cancel()") < shape.outer.indexOf("toolOutcomes.record("))
    }

    /** Index of the `}` matching the `{` at [openAt]. */
    private fun closingBrace(
        source: String,
        openAt: Int,
    ): Int = openAt + 1 + blockAfter(source, openAt).length

    /** The text between the `{` at [openAt] and its matching `}`. */
    private fun blockAfter(
        source: String,
        openAt: Int,
    ): String {
        assertEquals('{', source[openAt])
        var depth = 0
        for (i in openAt until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(openAt + 1, i)
            }
        }
        error("unbalanced braces after $openAt")
    }

    /** [block] with every nested `{…}` removed: only its own top-level statements remain. */
    private fun topLevel(block: String): String {
        val out = StringBuilder()
        var depth = 0
        for (c in block) {
            when {
                c == '{' -> depth++
                c == '}' -> depth--
                depth == 0 -> out.append(c)
            }
        }
        return out.toString()
    }

    @Test
    fun `runToolInstall prepares the installer and only then runs the install`() {
        val b = body(jsBridgeSource, "runToolInstall")
        val order =
            listOf(
                "prepareInstaller(",
                "if (notReady != null) return notReady",
                "CommandRunner.streamLong(",
                "ToolInstallVerdict.decide(",
            )
        val at = order.map { b.indexOf(it) }
        order.zip(at).forEach { (s, i) -> assertTrue(i >= 0, "'$s' missing from runToolInstall") }
        assertEquals(at.sorted(), at, "runToolInstall steps out of order: ${order.zip(at)}")
        assertTrue(
            b.contains("listOf(\"bash\", script, \"--tools-only\", scriptId)"),
            "the install is not a positional argv",
        )
    }

    @Test
    fun `prepareInstaller checks the marker, a cancel, refreshes, then lists`() {
        val b = body(jsBridgeSource, "prepareInstaller")
        val order = listOf("markerFailure(ocaDir)", "cancelFailure()", "refreshInstaller()", "checkListing(")
        val at = order.map { b.indexOf(it) }
        order.zip(at).forEach { (s, i) -> assertTrue(i >= 0, "'$s' missing from prepareInstaller") }
        assertEquals(at.sorted(), at, "prepareInstaller steps out of order: ${order.zip(at)}")
        assertTrue(body(jsBridgeSource, "markerFailure").contains("\".post-setup-done\""))
        val refresh = body(jsBridgeSource, "refreshInstaller")
        assertTrue(
            refresh.indexOf("refreshPostSetupScript()") in 0 until refresh.indexOf("cancelFailure()"),
            "a cancel during the refresh is not checked after it",
        )
        val list = body(jsBridgeSource, "checkListing")
        val steps = listOf("\"--list\"", "cancelFailure()", "ToolListCheck.supports(")
        val listAt = steps.map { list.indexOf(it) }
        steps.zip(listAt).forEach { (s, i) -> assertTrue(i >= 0, "'$s' missing from checkListing") }
        assertEquals(listAt.sorted(), listAt, "checkListing steps out of order: ${steps.zip(listAt)}")
    }

    @Test
    fun `tool events use their own event name, and the OpenClaw platform install keeps install_progress`() {
        assertTrue(jsBridgeSource.contains("TOOL_EVENT = \"tool_progress\""))
        val toolFunctions =
            listOf("installTool", "emitNotSupported", "emitToolState", "runToolInstall", "cancelToolInstall")
        for (name in toolFunctions) {
            assertFalse(body(jsBridgeSource, name).contains("\"install_progress\""), "$name emits install_progress")
        }
        assertTrue(body(jsBridgeSource, "installPlatform").contains("\"install_progress\""))
    }

    // ── id lists agree ──────────────────────────────────────────────────────

    private fun oaToolIds(path: String): Set<String> {
        val m = Regex("""(?m)^OA_TOOL_IDS="([^"]*)"""").find(file(path).readText())
        assertNotNull(m, "OA_TOOL_IDS not found in $path")
        return m!!
            .groupValues[1]
            .split(' ')
            .filter { it.isNotBlank() }
            .toSet()
    }

    @Test
    fun `every script id the app sends is in OA_TOOL_IDS of the root and bundled post-setup sh`() {
        for (path in listOf("../../post-setup.sh", "src/main/assets/post-setup.sh")) {
            val ids = oaToolIds(path)
            // code-server left the chain on both sides (its npm install fails here): the lists are the same eight
            assertEquals(8, ids.size, "$path: $ids")
            assertFalse("code-server" in ids, "$path still installs code-server through --tools-only")
            assertEquals(BridgeGuard.toolInstallIds.values.toSet(), ids, path)
        }
    }

    @Test
    fun `installable and terminal-only tools partition the app's tool list`() {
        val installable = BridgeGuard.toolInstallIds.keys
        val terminalOnly = BridgeGuard.terminalOnlyTools
        assertEquals(BridgeGuard.toolIds, installable + terminalOnly)
        assertEquals(emptySet<String>(), installable intersect terminalOnly)
        assertEquals(12, BridgeGuard.toolIds.size)
    }

    @Test
    fun `chromium is not mapped to playwright`() {
        // playwright installs only the library; mapping the browser to it would be a false success
        assertFalse("chromium" in BridgeGuard.toolInstallIds)
        assertFalse(BridgeGuard.toolInstallIds.any { (k, v) -> k != v })
    }

    @Test
    fun `the app tool ids are exactly the ids in SettingsTools tsx`() {
        val ids = Regex("""\{\s*id:\s*'([^']+)',\s*name:""").findAll(settingsTools).map { it.groupValues[1] }.toList()
        assertEquals(12, ids.size, ids.toString())
        assertEquals(BridgeGuard.toolIds, ids.toSet())
    }

    @Test
    fun `NOT_INSTALLABLE_YET in SettingsTools tsx equals terminalOnlyTools`() {
        val m = Regex("""const\s+NOT_INSTALLABLE_YET\s*=\s*\[([^\]]*)]""").find(settingsTools)
        assertNotNull(m, "NOT_INSTALLABLE_YET not found")
        val ids = Regex("""'([^']+)'""").findAll(m!!.groupValues[1]).map { it.groupValues[1] }.toSet()
        assertEquals(BridgeGuard.terminalOnlyTools, ids)
    }

    // ── stderr hint contract ────────────────────────────────────────────────

    /**
     * post-setup.sh prints its env/lock failures as a sentence, e.g.
     * `Could not create the tools lock in … (error=lock).` — the hint must be found inside it, or
     * ENV/LOCK never reach the page and the run is reported as BUSY.
     */
    @Test
    fun `the env and lock lines post-setup sh prints are recognized as hints`() {
        val lines =
            file("../../post-setup.sh")
                .readLines()
                .filter { it.contains("(error=env)") || it.contains("(error=lock)") }
                .map { Regex("""echo "([^"]*)"""").find(it)!!.groupValues[1] }
        assertEquals(2, lines.size, lines.toString())
        val env = lines.single { it.contains("error=env") }.replace("\$OCA_DIR", "/h/.openclaw-android")
        val lock = lines.single { it.contains("error=lock") }.replace("\$OCA_DIR", "/h/.openclaw-android")
        assertEquals(ToolFailure.ENV, ToolInstallVerdict.hintFromOutput(env), env)
        assertEquals(ToolFailure.LOCK, ToolInstallVerdict.hintFromOutput(lock), lock)
    }

    // ── compiled class access ───────────────────────────────────────────────

    /** Constant pools of compiled classes in this package whose name is [prefix] or starts with `[prefix]$`. */
    private fun compiledTexts(prefix: String): Map<String, String> {
        val url = JsBridge::class.java.getResource("JsBridge.class")
        assertNotNull(url, "JsBridge.class not on the test classpath")
        val wanted = { n: String ->
            !n.contains('/') &&
                n.endsWith(".class") &&
                (prefix.isEmpty() || n == "$prefix.class" || n.startsWith("$prefix\$"))
        }
        val texts =
            when (url!!.protocol) {
                "file" -> {
                    val dir = File(url.toURI()).parentFile
                    dir
                        .list()
                        .orEmpty()
                        .filter(wanted)
                        .associateWith { File(dir, it).readBytes() }
                }
                "jar" -> {
                    val conn = url.openConnection() as java.net.JarURLConnection
                    val pkg = conn.entryName.substringBeforeLast('/') + "/"
                    val jar = conn.jarFile
                    jar
                        .entries()
                        .asSequence()
                        .filter { it.name.startsWith(pkg) && wanted(it.name.removePrefix(pkg)) }
                        .associate { e -> e.name.removePrefix(pkg) to jar.getInputStream(e).use { it.readBytes() } }
                }
                else -> error("unsupported class location: $url")
            }
        assertTrue(texts.isNotEmpty(), "no classes for '$prefix' at $url")
        return texts.mapValues { String(it.value, Charsets.ISO_8859_1) }
    }

    private companion object {
        const val SHOW = 80
    }
}
