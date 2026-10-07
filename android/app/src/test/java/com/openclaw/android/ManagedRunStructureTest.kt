package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Structure of the managed-run path that behavior tests cannot see: no process is ever stopped by
 * NAME (operation-safety §2 — by pid only), checked in the sources and in the compiled classes
 * (lambdas included); the lease is taken before anything starts, in both entry points; the cancel
 * signal is not sent under the guard's monitor.
 */
internal class ManagedRunStructureTest {
    private val sources =
        listOf("ProcScan", "GatewayControl", "ManagedRunner", "ManagedRun", "ManagedRunLogic", "RunLease", "JsBridge")

    private fun source(name: String): String {
        val f = File("src/main/java/com/openclaw/android/$name.kt")
        assertTrue(f.isFile, "expected ${f.absolutePath}")
        return f.readText()
    }

    /** Text of `fun <name>(` up to the next member at the same indentation. */
    private fun body(
        src: String,
        name: String,
    ): String {
        val start = src.indexOf("fun $name(")
        assertTrue(start >= 0, "fun $name not found")
        val next =
            Regex("""\n    (@JavascriptInterface|@Synchronized|private fun|fun|internal fun)\b""").find(
                src,
                start + 1,
            )
        return src.substring(start, next?.range?.first ?: src.length)
    }

    private val byName = Regex("""\b(pkill|killall|pgrep|pidof)\b|"kill |killProcess""")

    @Test
    fun `no source of the managed run or gateway stops a process by name`() {
        for (name in sources) {
            val code =
                source(name)
                    .lineSequence()
                    .filterNot {
                        it.trim().startsWith("*") || it.trim().startsWith("//")
                    }.joinToString("\n")
            assertFalse(
                byName.containsMatchIn(code),
                "$name.kt names a process-by-name kill: ${byName.find(code)?.value}",
            )
        }
        // nothing in the gateway or /proc code starts a command at all
        for (name in listOf("ProcScan", "GatewayControl")) {
            val code = source(name)
            listOf("ProcessBuilder", "Runtime.getRuntime", "CommandRunner").forEach {
                assertFalse(code.contains(it), "$name.kt uses $it")
            }
        }
    }

    @Test
    fun `no compiled class of the managed run or gateway carries a kill-by-name command`() {
        val location =
            File(
                ManagedRunner::class.java.protectionDomain.codeSource.location
                    .toURI(),
            )
        val classes = compiledClasses(location).filterKeys { n -> sources.any { n.startsWith(it) } }
        assertTrue(
            classes.keys.any { it.startsWith("GatewayControl") } &&
                classes.keys.any { it.startsWith("ManagedRunner\$") },
            "${classes.keys}",
        )
        for ((name, bytes) in classes) {
            val text = String(bytes, Charsets.ISO_8859_1)
            listOf(
                "pkill",
                "killall",
                "pgrep",
                "pidof",
            ).forEach { assertFalse(text.contains(it), "$name contains '$it'") }
        }
    }

    /** Simple class name (with `$…` suffixes) → bytes, from a classes directory or jar. */
    private fun compiledClasses(location: File): Map<String, ByteArray> {
        val prefix = "com/openclaw/android/"
        if (location.isDirectory) {
            return File(location, prefix)
                .listFiles { f -> f.name.endsWith(".class") }
                .orEmpty()
                .associate { it.name.removeSuffix(".class") to it.readBytes() }
        }
        java.util.zip.ZipFile(location).use { zip ->
            return zip
                .entries()
                .asSequence()
                .filter {
                    it.name.startsWith(prefix) &&
                        it.name.endsWith(".class") &&
                        '/' !in it.name.removePrefix(prefix)
                }.associate { e ->
                    e.name.removePrefix(prefix).removeSuffix(".class") to
                        zip.getInputStream(e).use { it.readBytes() }
                }
        }
    }

    @Test
    fun `startRun checks the kind, then takes the lease, then launches`() {
        val b = body(source("JsBridge"), "startRun")
        val order =
            listOf(
                "kind !in BridgeGuard.runKinds",
                "RunLease.tryAcquire(kind)",
                "launchWithErrorHandling",
                "runner.run(kind, stopGateway)",
            )
        val at = order.map { b.indexOf(it) }
        order.zip(at).forEach { (s, i) -> assertTrue(i >= 0, "'$s' missing from startRun") }
        assertEquals(at.sorted(), at, "startRun steps out of order: ${order.zip(at)}")
    }

    @Test
    fun `installTool takes the lease before its guard and releases it in the finally`() {
        val b = body(source("JsBridge"), "installTool")
        val lease = b.indexOf("RunLease.tryAcquire(RunLease.TOOLS)")
        val guard = b.indexOf("ToolInstallGuard.tryStart(")
        assertTrue(lease in 0 until guard, "the lease is not taken before the tool guard")
        assertTrue(
            Regex("""finally \{[\s\S]*?RunLease\.release\(RunLease\.TOOLS\)""").containsMatchIn(b),
            "lease not released in finally",
        )
    }

    @Test
    fun `ManagedRunner releases the lease in a finally around the whole run`() {
        val b = body(source("ManagedRunner"), "run")
        assertTrue(Regex("""\} finally \{\s*RunLease\.release\(kind\)""").containsMatchIn(b), b)
    }

    /** From the `{` of the first [opener] at or after [from] to its matching `}` (strings ignored). */
    private fun block(
        src: String,
        opener: String,
        from: Int = 0,
    ): IntRange {
        val at = src.indexOf(opener, from)
        assertTrue(at >= 0, "'$opener' not found")
        val open = src.indexOf('{', at)
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return open..i
            }
        }
        error("unbalanced braces after '$opener'")
    }

    /**
     * The signal (a /proc read and a kill) runs OUTSIDE the monitor so a slow /proc never blocks the
     * output reader; the token it is given is read UNDER the monitor, at the claim (L7), so a run
     * started meanwhile is never the one signalled.
     */
    @Test
    fun `the cancel signal is never sent under the guard's monitor and gets the token claimed under it`() {
        val src = source("ManagedRun")
        listOf("requestCancel", "retryCancel", "deliver", "claimSignal").forEach {
            assertFalse(Regex("""@Synchronized\s+(private )?fun $it\(""").containsMatchIn(src), "$it is synchronized")
        }
        val deliver = body(src, "deliver")
        val call = deliver.indexOf("delivered = signal(token)")
        assertTrue(call in 0 until deliver.indexOf("synchronized(this)"), deliver)
        assertEquals(1, Regex("""\bsignal\(""").findAll(deliver).count(), "deliver calls the signal more than once")
        // the claim returns the run's token and is made only under the monitor
        val claim = body(src, "claimSignal")
        assertTrue(Regex("""return runToken\b""").containsMatchIn(claim), claim)
        for (name in listOf("requestCancel", "retryCancel")) {
            val b = body(src, name)
            assertFalse(Regex("""\bsignal\(""").containsMatchIn(b), "$name calls the signal itself")
            val monitor = block(b, "synchronized(this)")
            val claimAt = b.indexOf("claimSignal()")
            assertTrue(claimAt in monitor, "$name claims outside the monitor")
            val deliverAt = b.indexOf("deliver(token, signal)")
            assertTrue(deliverAt > monitor.last, "$name delivers inside the monitor or not at all: $b")
            assertFalse(b.contains("runToken"), "$name reads the token outside the claim")
        }
        assertEquals(2, Regex("""\bclaimSignal\(\)""").findAll(src).count() - 1, "claimSignal is called elsewhere")
    }

    /** The runner signals with the token it is given and stops the moment that run is no longer the current one. */
    @Test
    fun `the runner's cancel signal uses the claimed token and checks it before each signal`() {
        val b = body(source("ManagedRunner"), "sendCancelSignal")
        assertTrue(b.startsWith("fun sendCancelSignal(token: String): Boolean"), b)
        assertTrue(
            Regex("""RunSignal\.sendTerm\(\s*token,""").containsMatchIn(b),
            "the signal is not sent for the given token: $b",
        )
        assertTrue(
            b.contains("ManagedRunGuard.runToken == token"),
            "allowed does not check the run is still the claimed one",
        )
        // the only read of the current token is that comparison
        assertEquals(1, Regex("""ManagedRunGuard\.runToken""").findAll(b).count(), b)
    }

    /** A runner that cannot be built must not leave the lease held: it is built before the lease is taken. */
    @Test
    fun `startRun builds the runner before it takes the lease and refuses without the lease when that fails`() {
        val b = body(source("JsBridge"), "startRun")
        val build = b.indexOf("val runner =")
        val lease = b.indexOf("RunLease.tryAcquire(kind)")
        assertTrue(build in 0 until lease, "the runner is built after the lease is taken")
        val failure = b.substring(b.indexOf("} catch (e: Exception) {", build), lease)
        assertTrue(failure.contains("ManagedRunner.REFUSED"), failure)
        assertTrue(failure.contains("UpdateReason.UNKNOWN.name"), failure)
        assertTrue(Regex("""\breturn\b""").containsMatchIn(failure), "the failed build does not end startRun")
        assertFalse(failure.contains("RunLease"), failure)
    }

    @Test
    fun `the gateway's roots are the terminal sessions and the app process itself`() {
        val bridge = source("JsBridge")
        val roots = "GatewayControl(sessionPids = { sessionManager.sessionPids() + android.os.Process.myPid() })"
        assertTrue(
            bridge.contains(roots),
            "JsBridge no longer passes the app process as a root of its gateways",
        )
    }
}
