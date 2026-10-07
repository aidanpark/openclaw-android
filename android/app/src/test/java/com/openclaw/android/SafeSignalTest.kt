package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * [SafeSignal]: the last check before every real signal of the app. A pid of 1 or below (init, a
 * process group, every process) and the app's own pid are never signalled; any other pid is, once.
 * The structural half checks that the real `android.os.Process.sendSignal` is reached only through
 * it: the default `send` of every signalling class goes through [SafeSignal.send].
 */
internal class SafeSignalTest {
    private val sig = RunSignal.SIGTERM
    private val me = 4321

    private class Recorder {
        val delivered = mutableListOf<Pair<Int, Int>>()

        fun deliver(
            pid: Int,
            sig: Int,
        ) {
            delivered += pid to sig
        }
    }

    // ── allowed ─────────────────────────────────────────────────────────────

    @Test
    fun `allowed refuses init, zero, every negative pid and the app's own pid`() {
        val refused = listOf(1, 0, -1, -2, -me, Int.MIN_VALUE, me)
        refused.forEach { assertFalse(SafeSignal.allowed(it, me), "pid $it allowed") }
    }

    @Test
    fun `allowed accepts 2, the pids next to the app's own and the largest pid`() {
        listOf(2, 3, me - 1, me + 1, 32_768, 4_194_304, Int.MAX_VALUE).forEach {
            assertTrue(SafeSignal.allowed(it, me), "pid $it refused")
        }
    }

    @Test
    fun `allowed compares with the pid it is given, not a fixed one`() {
        assertTrue(SafeSignal.allowed(me, me + 1))
        assertFalse(SafeSignal.allowed(me + 1, me + 1))
        // an app pid at or below 1 (never real) does not open pid 1 or 0
        assertFalse(SafeSignal.allowed(1, 0))
        assertFalse(SafeSignal.allowed(0, 1))
    }

    // ── send ────────────────────────────────────────────────────────────────

    @Test
    fun `send refuses a blocked pid without delivering anything and answers false`() {
        for (pid in listOf(1, 0, -1, Int.MIN_VALUE, me)) {
            val r = Recorder()
            assertFalse(SafeSignal.send(pid, sig, myPid = { me }, deliver = r::deliver), "pid $pid")
            assertEquals(emptyList<Pair<Int, Int>>(), r.delivered, "pid $pid was delivered")
        }
    }

    @Test
    fun `send delivers an allowed pid exactly once, with its signal, and answers true`() {
        for (pid in listOf(2, me + 1, Int.MAX_VALUE)) {
            for (s in listOf(RunSignal.SIGTERM, RunSignal.SIGKILL)) {
                val r = Recorder()
                assertTrue(SafeSignal.send(pid, s, myPid = { me }, deliver = r::deliver), "pid $pid sig $s")
                assertEquals(listOf(pid to s), r.delivered)
            }
        }
    }

    @Test
    fun `send asks for the app's pid on every call`() {
        var asked = 0
        val r = Recorder()
        val pids = listOf(me, me + 1)
        val myPid = {
            asked++
            me + 1
        }
        pids.forEach { SafeSignal.send(it, sig, myPid = myPid, deliver = r::deliver) }
        assertEquals(2, asked)
        // the second call's pid is the app's own now: only the first was delivered
        assertEquals(listOf(me to sig), r.delivered)
    }

    /**
     * The defaults are android.os.Process (unit tests: `isReturnDefaultValues`, so `myPid()` is 0 and
     * `sendSignal` does nothing): the filter still decides with them.
     */
    @Test
    fun `with the default pid and deliver the filter still refuses pid 1 and 0 and passes others`() {
        assertFalse(SafeSignal.send(1, sig))
        assertFalse(SafeSignal.send(0, sig))
        assertFalse(SafeSignal.send(-1, sig))
        assertTrue(SafeSignal.send(Int.MAX_VALUE, sig))
    }

    // ── every real signal goes through it ───────────────────────────────────

    private val mainSources: Map<String, String> by lazy {
        val dir = File("src/main/java/com/openclaw/android")
        assertTrue(dir.isDirectory, dir.absolutePath)
        dir
            .walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }
            .associate { it.name to it.readText() }
    }

    /** Code only: KDoc and line comments may name the real call. */
    private fun code(src: String): String =
        src
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `the real sendSignal is called in SafeSignal only, once`() {
        assertTrue(mainSources.size > 20, mainSources.keys.toString())
        val calls = mainSources.mapValues { (_, src) -> Regex("""\bsendSignal\s*\(""").findAll(code(src)).count() }
        assertEquals(mapOf("SafeSignal.kt" to 1), calls.filterValues { it > 0 })
        // no import that would let a file call it unqualified, and no killProcess around the filter either
        mainSources.forEach { (name, src) ->
            assertFalse(Regex("""import\s+android\.os\.Process\.sendSignal""").containsMatchIn(src), name)
            assertFalse(Regex("""\bkillProcess\s*\(""").containsMatchIn(code(src)), "$name: killProcess")
        }
    }

    /** `send: (Int, Int) -> Unit = { … }`: the default of every injectable signal. */
    private val defaultSend = Regex("""\bsend:\s*\(Int,\s*Int\)\s*->\s*Unit\s*=\s*\{([^}]*)}""")

    @Test
    fun `every default send of the app goes through SafeSignal send`() {
        val defaults =
            mainSources
                .filterKeys { it != "SafeSignal.kt" }
                .flatMap { (name, src) ->
                    defaultSend.findAll(code(src)).map { name to it.groupValues[1].trim() }.toList()
                }
        // GatewayControl, RunProcesses, RunSignal.sendTerm (ProcScan.kt) and ToolSignal.sendTerm
        assertEquals(
            listOf("GatewayControl.kt", "ProcScan.kt", "ProcScan.kt", "ToolInstallGuard.kt"),
            defaults.map { it.first }.sorted(),
            defaults.toString(),
        )
        defaults.forEach { (name, body) ->
            assertEquals("pid, sig -> SafeSignal.send(pid, sig)", body, name)
        }
    }

    @Test
    fun `no default send is written in another form the check above would miss`() {
        // any `send` parameter of a function type with a default must have been matched above
        val loose = Regex("""\bsend:\s*\([^)]*\)\s*->\s*[A-Za-z]+\s*=""")
        val found = mainSources.mapValues { (_, src) -> loose.findAll(code(src)).count() }.filterValues { it > 0 }
        val strict =
            mainSources.mapValues { (_, src) -> defaultSend.findAll(code(src)).count() }.filterValues { it > 0 }
        assertEquals(strict, found)
    }

    @Test
    fun `SafeSignal's own default deliver is the real sendSignal and its default pid the app's`() {
        val src = code(mainSources.getValue("SafeSignal.kt"))
        assertTrue(src.contains("deliver: (Int, Int) -> Unit = { p, s -> android.os.Process.sendSignal(p, s) }"), src)
        assertTrue(src.contains("myPid: () -> Int = { android.os.Process.myPid() }"), src)
    }
}
