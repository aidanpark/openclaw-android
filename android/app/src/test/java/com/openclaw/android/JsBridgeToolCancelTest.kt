package com.openclaw.android

import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

/**
 * Cancelling a tool install: a SIGTERM by the pid the script records (never Process.destroy),
 * retried until it can be delivered, and a cancel during the preparation steps stops the run
 * before `--tools-only <id>` is ever called.
 */
internal class JsBridgeToolCancelTest : JsBridgeToolInstallFixture() {
    // ── cancel ──────────────────────────────────────────────────────────────

    @Test
    fun `cancelToolInstall with nothing running emits nothing`() {
        val (bridge, web) = page()
        bridge.cancelToolInstall()
        assertTrue(web.scripts.isEmpty(), web.scripts.toString())
        assertFalse(ToolInstallGuard.isRunning())
    }

    @Test
    fun `cancel emits cancelling at once, keeps the guard until the script ends, then reports cancelled`() {
        marker()
        blockingScript()
        fakeSignal()
        val (bridge, web) = page()
        bridge.installTool("tmux")
        awaitRealCall(web)
        assertTrue(TestWait.until { lockPidFile.isFile })
        web.clear()

        bridge.cancelToolInstall()
        val first = web.events("tool_progress").first()
        assertEquals("cancelling", first.data["phase"])
        assertEquals(true, first.data["cancelRequested"])

        val end = finalEvent(web)
        assertEquals("cancelled", end.data["phase"], web.scripts.toString())
        assertEquals("INTERRUPTED", end.data["reason"])
        assertTrue(File(oca, "tools-result.conf").readText().contains("error=interrupted"), "the TERM trap did not run")
        assertEquals(1, signalCalls.get(), "a delivered SIGTERM was sent again")
        assertEquals(setOf(lockPidFile.absoluteFile), signalFiles.map { it.absoluteFile }.toSet())
        assertEquals(listOf(ToolInstallGuard.runToken), signalTokens.toList(), "the cancel did not name this run")
        assertEquals(1, signalledPids.size, signalledPids.toString())
    }

    @Test
    fun `a second cancel while cancelling re-emits the same cancelling state`() {
        marker()
        blockingScript()
        fakeSignal { false } // never delivered: the state stays cancelling
        val (bridge, web) = page()
        bridge.installTool("tmux")
        awaitRealCall(web)
        web.clear()
        bridge.cancelToolInstall()
        bridge.cancelToolInstall()
        val events = web.events("tool_progress")
        assertEquals(2, events.size, web.scripts.toString())
        assertTrue(events.all { it.data["phase"] == "cancelling" })
        assertTrue(ToolInstallGuard.isRunning(), "cancel released the guard before the script ended")
        assertTrue(signalCalls.get() >= 2, "an undelivered signal was not retried by the second cancel")

        releaseFile.writeText("go")
        // The script was never signalled and finished normally: success beats the cancel request
        assertEquals("done", finalEvent(web).data["phase"])
    }

    @Test
    fun `a cancel asked before the script wrote its pid is delivered once the pid exists`() {
        marker()
        File(home, "hold-pid").writeText("")
        blockingScript()
        fakeSignal()
        val (bridge, web) = page()
        bridge.installTool("tmux")
        awaitRealCall()
        bridge.cancelToolInstall()
        assertEquals("cancelling", bridge.toolState()["phase"])
        assertFalse(lockPidFile.exists())
        val before = signalCalls.get()

        // The script now records its pid and prints nothing more: only the 1 s retry can deliver
        File(home, "hold-pid").delete()
        val end = finalEvent(web)
        assertEquals("cancelled", end.data["phase"], "calls=${calls()} events=${web.scripts}")
        assertTrue(signalCalls.get() > before, "the cancel was never retried")
        assertTrue(File(oca, "tools-result.conf").readText().contains("error=interrupted"))
    }

    @Test
    fun `with the real signal path a cancel that cannot be delivered leaves the run going without an error`() {
        // The real ToolSignal on the JVM: no /proc entry on macOS (not ours), a stub sendSignal elsewhere
        marker()
        blockingScript()
        val (bridge, web) = page()
        bridge.installTool("tmux")
        awaitRealCall()
        assertTrue(TestWait.until { lockPidFile.isFile })
        bridge.cancelToolInstall()
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertTrue(ToolInstallGuard.isRunning())
        assertEquals("cancelling", bridge.toolState()["phase"])
        releaseFile.writeText("go")
        assertEquals("done", finalEvent(web).data["phase"])
    }

    /**
     * Another post-setup.sh run (`oa` in the terminal, with no token, or a run an earlier app
     * process started, with that run's token) holds `.tools.lock` and has recorded ITS pid there.
     * This app's script has not taken the lock (it has not even started: the installer is being
     * refreshed), so a cancel must not signal the pid in that file — it names a run this page never
     * started.
     */
    @ParameterizedTest(name = "other run token: {0}")
    @ValueSource(strings = [UNSET, "9d2b7e10-1c3a-4f55-8e60-2a7b9c4d5e6f"])
    fun `a cancel during the refresh never signals another run that holds the tools lock`(otherToken: String) {
        marker()
        fakeScript("echo 'Another tools run is in progress. Try again when it has finished.' >&2; exit 2")
        val other =
            File(root, "other/post-setup.sh").apply {
                parentFile.mkdirs()
                // Like post-setup.sh: take the lock and record its own pid in it (and its environment)
                writeText(
                    """
                    |trap 'exit 143' TERM
                    |mkdir -p "${'$'}2" && env > "${'$'}2/${'$'}${'$'}"
                    |mkdir -p "${'$'}1" && echo ${'$'}${'$'} > "${'$'}1/pid"
                    |while :; do sleep 0.05; done
                    |
                    """.trimMargin(),
                )
            }
        val otherBuilder =
            ProcessBuilder("bash", other.absolutePath, lockPidFile.parentFile.absolutePath, environDir.absolutePath)
        otherBuilder.environment().remove(ToolSignal.ENV_NAME)
        if (otherToken != UNSET) otherBuilder.environment()[ToolSignal.ENV_NAME] = otherToken
        val otherRun = otherBuilder.start()
        try {
            assertTrue(TestWait.until { lockPidFile.isFile && lockPidFile.readText().trim().isNotEmpty() })
            val otherPid = lockPidFile.readText().trim().toInt()
            fakeSignal()
            val refreshing = java.util.concurrent.CountDownLatch(1)
            val proceed = java.util.concurrent.CountDownLatch(1)
            val (bridge, web) = page()
            every { bootstraps.single().refreshPostSetupScript() } answers {
                refreshing.countDown()
                proceed.await(TestWait.WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                Unit
            }
            bridge.installTool("tmux")
            assertTrue(refreshing.await(TestWait.WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS))
            bridge.cancelToolInstall()
            proceed.countDown()

            assertEquals("cancelled", finalEvent(web).data["phase"])
            assertTrue(signalCalls.get() >= 1, "the cancel never tried to signal")
            assertTrue(signalTokens.all { it.isNotEmpty() && it != otherToken }, signalTokens.toString())
            assertFalse(otherPid in signalledPids, "SIGTERM sent to another run ($otherPid): $signalledPids")
            assertTrue(otherRun.isAlive, "another run was ended by this page's cancel")
        } finally {
            otherRun.destroyForcibly()
        }
    }

    /**
     * A cancel pressed while the installer is still being refreshed (up to ~12 s download) has no
     * process to signal; it must be re-checked before the real call, or the full install still
     * runs and ends as done while the page said "cancel requested" all along.
     */
    @Test
    fun `a cancel requested while the installer is being refreshed stops the install before it runs`() {
        marker()
        fakeScript(
            """
            |mkdir -p "${'$'}PREFIX/bin" && : > "${'$'}PREFIX/bin/tmux"
            |printf 'schema=1\nrun=%s\ntmux=ok\nexit=0\n' "${'$'}(date +%s)" > "${'$'}R"
            |exit 0
            """.trimMargin(),
        )
        val refreshing = java.util.concurrent.CountDownLatch(1)
        val proceed = java.util.concurrent.CountDownLatch(1)
        val (bridge, web) = page()
        every { bootstraps.single().refreshPostSetupScript() } answers {
            refreshing.countDown()
            proceed.await(TestWait.WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            Unit
        }
        bridge.installTool("tmux")
        assertTrue(refreshing.await(TestWait.WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS))
        bridge.cancelToolInstall()
        assertEquals("cancelling", bridge.toolState()["phase"])
        proceed.countDown()

        val end = finalEvent(web)
        assertEquals("cancelled", end.data["phase"], "calls=${calls()} events=${web.scripts}")
        assertFalse("--tools-only tmux" in calls(), "the install ran after the cancel request")
        assertEquals(emptyList<String>(), calls(), "the installer was still asked for its list after the cancel")
    }

    @Test
    fun `a cancel requested while the installer lists its tools stops the install before it runs`() {
        marker()
        val listing = File(home, "listing")
        val proceed = File(home, "proceed")
        fakeScript(
            body = "mkdir -p \"${'$'}PREFIX/bin\" && : > \"${'$'}PREFIX/bin/tmux\"; exit 0",
            list =
                """
                |: > "${'$'}HOME/listing"
                |while [ ! -f "${'$'}HOME/proceed" ]; do sleep 0.05; done
                |printf 'tmux\n'; exit 0
                """.trimMargin(),
        )
        val (bridge, web) = page()
        bridge.installTool("tmux")
        assertTrue(TestWait.until { listing.exists() }, "the list call never started")
        bridge.cancelToolInstall()
        proceed.writeText("")

        val end = finalEvent(web)
        assertEquals("cancelled", end.data["phase"], "calls=${calls()} events=${web.scripts}")
        assertEquals("INTERRUPTED", end.data["reason"])
        assertEquals(listOf("--tools-only --list"), calls(), "the install ran after the cancel request")
    }

    @Test
    fun `a cancel requested before the refresh starts never refreshes or lists`() {
        marker()
        fakeScript("exit 0")
        val firstEmit = java.util.concurrent.CountDownLatch(1)
        val proceed = java.util.concurrent.CountDownLatch(1)
        val (bridge, web) = page()
        // The first emit (running) happens before the marker check: hold it there and cancel
        every { bootstraps.single().refreshPostSetupScript() } answers { Unit }
        val view = pages.single().view
        every { view.post(any()) } answers {
            firstEmit.countDown()
            proceed.await(TestWait.WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            firstArg<Runnable>().run()
            true
        }
        bridge.installTool("tmux")
        assertTrue(firstEmit.await(TestWait.WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(ToolInstallGuard.requestCancel { false })
        every { view.post(any()) } answers {
            firstArg<Runnable>().run()
            true
        }
        proceed.countDown()

        val end = finalEvent(web)
        assertEquals("cancelled", end.data["phase"], "calls=${calls()} events=${web.scripts}")
        verify(exactly = 0) { bootstraps.single().refreshPostSetupScript() }
        assertEquals(emptyList<String>(), calls())
    }

    private companion object {
        /** The other run's environment has no OA_APP_RUN_TOKEN at all (a terminal `oa`). */
        const val UNSET = "<unset>"
    }
}
