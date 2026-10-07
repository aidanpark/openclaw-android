package com.openclaw.android

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.nio.file.Files

/**
 * Settings → Status shown again (`useVisibleAgain` in SettingsStatus.tsx), on the page harness of
 * [StatusScreenBehaviorTest] with every native read counted: the gateway is read again every
 * time; the last result only while no run is shown (a run, or a start waiting for its answer); the
 * run state never — it keeps coming from its events.
 *
 * Skipped (not failed) when node or `www/node_modules/esbuild` is missing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class StatusScreenVisibleAgainTest {
    private val www = File("../www").absoluteFile
    private val work: File by lazy { Files.createTempDirectory("status-visible").toFile() }
    private val results by lazy {
        val prefix = StatusScreenBehaviorTest.HARNESS.substringBefore("const scenarios = {")
        assertTrue(prefix.length < StatusScreenBehaviorTest.HARNESS.length, "the status harness changed shape")
        StatusScreenBehaviorTest.runHarness(www, work, prefix + SCENARIOS)
    }

    @AfterAll
    fun cleanup() {
        work.deleteRecursively()
    }

    @Suppress("UNCHECKED_CAST")
    private fun fact(
        name: String,
        key: String,
    ): Map<String, Any?> {
        val r = results[name] ?: error("no scenario $name: ${results.keys}")
        assertFalse(r.containsKey("error"), "scenario $name failed: ${r["error"]}")
        return r[key] as Map<String, Any?>
    }

    private fun Map<String, Any?>.n(k: String) = (this[k] as Number).toInt()

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.keys() = this["keys"] as List<String>

    @Test
    fun `idle - the gateway and the last result are read again, the run state is not`() {
        val f = fact("shown", "idle")
        assertEquals(1, f.n("gateway"), f.toString())
        assertEquals(1, f.n("lastRun"), f.toString())
        assertEquals(0, f.n("runState"), f.toString())
        assertTrue("status_gw_running" in f.keys() && "status_last_success" in f.keys(), f.toString())
    }

    @Test
    fun `during a run - only the gateway is read again, and the run on screen is exactly as before`() {
        val f = fact("shown", "busy")
        assertEquals(1, f.n("gateway"), f.toString())
        assertEquals(0, f.n("lastRun"), "the last result was read again during a run: $f")
        assertEquals(0, f.n("runState"), f.toString())
        assertEquals(f["before"], f["afterKeys"], "the run on screen changed")
        assertEquals(emptyList<Any?>(), f["calls"])
    }

    @Test
    fun `while a start waits for its answer - the last result is not read again`() {
        val f = fact("shown", "preparing")
        assertEquals(0, f.n("lastRun"), f.toString())
        assertEquals(0, f.n("runState"), f.toString())
        assertEquals(f["before"], f["afterKeys"], f.toString())
    }

    @Test
    fun `after the run ended the last result is read again`() {
        val f = fact("shown", "afterEnd")
        assertEquals(1, f.n("lastRun"), f.toString())
    }

    @Test
    fun `a gateway stopped in the terminal shows stopped once the page is shown again`() {
        val f = fact("shown", "stopped")
        assertTrue("status_gw_not_running" in f.keys(), f.toString())
        assertFalse("status_gw_running" in f.keys(), f.toString())
    }

    private companion object {
        val SCENARIOS =
            """
            |// counts the native reads made from now on
            |function counting(p) {
            |  const c = { gateway: 0, lastRun: 0, runState: 0 }
            |  const o = window.OpenClaw
            |  const g = o.getGatewayStatus, l = o.getLastRun, s = o.getRunState
            |  o.getGatewayStatus = () => { c.gateway++; return g() }
            |  o.getLastRun = () => { c.lastRun++; return l() }
            |  o.getRunState = () => { c.runState++; return s() }
            |  return c
            |}
            |const scenarios = {
            |  shown() {
            |    const out = {}
            |    let p = mount({})
            |    let c = counting(p)
            |    p.native.gateway = { running: true, ours: true, pids: [7] }
            |    p.native.lastRun = { UPDATE: { at: 1700000000, verdict: 'success', warnings: 0 } }
            |    p.emit('visible-again')
            |    out.idle = Object.assign({ keys: p.keys() }, c)
            |
            |    p = mount({ runState: busy(3) })
            |    const before = p.keys()
            |    c = counting(p)
            |    p.native.lastRun = { UPDATE: { at: 1700000000, verdict: 'failure', reason: 'MIGRATION_FAILED', exit: 1, warnings: 0 } }
            |    p.emit('visible-again')
            |    out.busy = Object.assign({ before, afterKeys: p.keys(), calls: p.native.calls.slice() }, c)
            |
            |    p = startFromIdle({})
            |    const beforePrep = p.keys()
            |    c = counting(p)
            |    p.emit('visible-again')
            |    out.preparing = Object.assign({ before: beforePrep, afterKeys: p.keys() }, c)
            |
            |    p = mount({ runState: busy(4) })
            |    const end = doneEvent({})
            |    p.native.runState = end
            |    p.emit('run_progress', end)
            |    c = counting(p)
            |    p.emit('visible-again')
            |    out.afterEnd = Object.assign({ keys: p.keys() }, c)
            |
            |    p = mount({ gateway: { running: true, ours: true, pids: [7] } })
            |    p.native.gateway = { running: false, ours: false, pids: [] }
            |    p.emit('visible-again')
            |    out.stopped = { keys: p.keys() }
            |    return out
            |  },
            |}
            |const results = {}
            |for (const [name, run] of Object.entries(scenarios)) {
            |  try { results[name] = run() } catch (e) { results[name] = { error: String((e && e.stack) || e) } }
            |}
            |console.log(JSON.stringify(results))
            |
            """.trimMargin()
    }
}
