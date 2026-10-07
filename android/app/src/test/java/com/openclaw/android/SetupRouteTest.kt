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
 * Which screen the app opens on (`lib/setupRoute.ts`, `routeFor`) as it BEHAVES: bundled with the
 * esbuild of `android/www/node_modules` and run in node over every combination of `getSetupStatus()`
 * and `getSetupState()`. MASTER condition 1 (B5): every row where the first install is complete
 * (marker present) opens exactly where the App.tsx before stage B opened — that logic is written out
 * below as it was at v0.4.4 and run next to `routeFor` — and a device with the bootstrap but no
 * marker gets the resume screen, never the first-install screen.
 *
 * Skipped (not failed) when node or `www/node_modules/esbuild` is missing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class SetupRouteTest {
    private val www = File("../www").absoluteFile
    private val work: File by lazy { Files.createTempDirectory("setup-route").toFile() }
    private val results by lazy { StatusScreenBehaviorTest.runHarness(www, work, HARNESS) }

    @AfterAll
    fun cleanup() {
        work.deleteRecursively()
    }

    @Suppress("UNCHECKED_CAST")
    private fun rows(): List<Map<String, Any?>> = results.getValue("table")["rows"] as List<Map<String, Any?>>

    /**
     * The expected route of every row, written out (status label × setupState label). Status labels:
     * `null` (no bridge), `{}`, `ff` (no bootstrap, no marker), `ft` (marker without bootstrap),
     * `tf` (bootstrap without marker), `tt` (both).
     */
    private val expected: Map<Pair<String, String>, String> =
        buildMap {
            for (state in listOf("null", "{}", "notRunning", "running")) {
                put("null" to state, "main")
                val running = state == "running"
                put("{}" to state, "setup")
                put("ff" to state, "setup")
                put("ft" to state, "setup")
                put("tf" to state, if (running) "setup" else "resume")
                put("tt" to state, if (running) "setup" else "main")
            }
        }

    @Test
    fun `routeFor gives the expected route for every row`() {
        val rows = rows()
        assertEquals(expected.size, rows.size)
        rows.forEach { r ->
            val key = r["status"] as String to r["state"] as String
            assertEquals(expected[key], r["route"], "row $key")
        }
    }

    @Test
    fun `every row opens the main screen exactly when the App before stage B did`() {
        rows().forEach { r ->
            assertEquals(r["oldDone"], r["route"] == "main", "row ${r["status"]} × ${r["state"]}")
        }
    }

    @Test
    fun `marker present rows - the full truth table of bootstrap and install state - are unchanged`() {
        val markerRows = rows().filter { r -> r["status"] in setOf("ft", "tt") }
        assertEquals(8, markerRows.size)
        markerRows.forEach { r ->
            val old = if (r["oldDone"] == true) "main" else "setup"
            assertEquals(old, r["route"], "row ${r["status"]} × ${r["state"]}")
        }
    }

    @Test
    fun `bootstrap without marker resumes instead of starting the first install over, unless an install runs`() {
        rows().filter { it["status"] == "tf" }.forEach { r ->
            assertEquals(if (r["state"] == "running") "setup" else "resume", r["route"], r.toString())
        }
        rows().filter { it["route"] == "resume" }.forEach { r ->
            assertEquals("tf", r["status"], "resume only for bootstrap without marker: $r")
        }
    }

    @Test
    fun `App tsx decides with routeFor and no longer has its own inline condition`() {
        val app = File(www, "src/App.tsx").readText()
        assertTrue(
            app.contains("setSetupDone(routeFor(status, setupState) === 'main')"),
            "App.tsx does not route with routeFor",
        )
        assertFalse(
            app.contains("!!status.bootstrapInstalled && !!status.platformInstalled"),
            "the old inline condition is back",
        )
        assertFalse(
            Regex("""setSetupDone\((true|false)\)""").containsMatchIn(app.substringBefore("const onSetupProgress")),
        )
        // the setup page reads the same function for its own first screen
        val setup = File(www, "src/screens/Setup.tsx").readText()
        assertTrue(
            setup.contains(
                "routeFor(bridge.callJson<SetupStatus>('getSetupStatus'), " +
                    "bridge.callJson<BootstrapState>('getSetupState'))",
            ),
        )
        assertTrue(setup.contains("return readRoute() === 'resume' ? 'resume' : 'platform-select'"))
    }

    private companion object {
        val HARNESS =
            """
            |import path from 'node:path'
            |import { createRequire } from 'node:module'
            |import { pathToFileURL } from 'node:url'
            |const [esbuildMain, wwwSrc, work] = process.argv.slice(2)
            |const esbuild = createRequire(import.meta.url)(esbuildMain)
            |const out = path.join(work, 'route.bundle.mjs')
            |await esbuild.build({ entryPoints: [path.join(wwwSrc, 'lib/setupRoute.ts')], bundle: true, format: 'esm', platform: 'node', outfile: out, logLevel: 'error', tsconfigRaw: {} })
            |const { routeFor } = await import(pathToFileURL(out).href)
            |// App.tsx at v0.4.4 (before stage B), its decision only: true = setup done (main screen)
            |function oldDone(status, setupState) {
            |  if (status && setupState?.running) return false
            |  else if (status) return !!status.bootstrapInstalled && !!status.platformInstalled
            |  else return true
            |}
            |const statuses = { null: null, '{}': {}, ff: { bootstrapInstalled: false, platformInstalled: false }, ft: { bootstrapInstalled: false, platformInstalled: true }, tf: { bootstrapInstalled: true, platformInstalled: false }, tt: { bootstrapInstalled: true, platformInstalled: true } }
            |const states = { null: null, '{}': {}, notRunning: { running: false }, running: { running: true } }
            |const rows = []
            |for (const [s, status] of Object.entries(statuses)) for (const [k, state] of Object.entries(states)) {
            |  rows.push({ status: s, state: k, route: routeFor(status, state), oldDone: oldDone(status, state) })
            |}
            |console.log(JSON.stringify({ table: { rows } }))
            |
            """.trimMargin()
    }
}
