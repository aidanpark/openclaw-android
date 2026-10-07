package com.openclaw.android

import android.content.Context
import android.content.res.AssetManager
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/**
 * When the app runs `post-setup.sh` itself (managed SETUP) and when the terminal still gets
 * `bash <script>` typed: [ManagedSetup.capable] (both literals), the script it would run (home copy,
 * else the APK-bundled one — also after a failed public download), [ManagedSetup.shouldRunInTerminal]
 * (all eight rows), [ManagedSetup.managedRunActive], and the bundled asset being the repository's
 * script byte for byte.
 */
internal class ManagedSetupTest {
    @TempDir
    lateinit var root: File

    private val repoScript = File("../../post-setup.sh")
    private val bundledScript = File("src/main/assets/post-setup.sh")

    @BeforeEach
    @AfterEach
    fun free() = ManagedRunWorld.resetShared()

    // ── capable ─────────────────────────────────────────────────────────────

    @Test
    fun `capable needs both literals - one alone or none is not enough`() {
        assertTrue(ManagedSetup.capable("x OA_NO_ONBOARD y \$OCA_DIR/post-setup-result.conf z"))
        assertFalse(ManagedSetup.capable("only OA_NO_ONBOARD here"))
        assertFalse(ManagedSetup.capable("only post-setup-result.conf here"))
        assertFalse(ManagedSetup.capable("neither"))
        assertFalse(ManagedSetup.capable(""))
        // near misses are not the literals
        assertFalse(ManagedSetup.capable("OA_NO_ONBOAR post-setup-result.conf"))
        assertFalse(ManagedSetup.capable("OA_NO_ONBOARD post-setup-result.con"))
    }

    @Test
    fun `capable of a file - missing, empty, a folder or over 4 MB is false`() {
        val f = File(root, "post-setup.sh")
        assertFalse(ManagedSetup.capable(f))
        f.writeText("")
        assertFalse(ManagedSetup.capable(f))
        f.writeText("OA_NO_ONBOARD post-setup-result.conf")
        assertTrue(ManagedSetup.capable(f))
        f.writeText("OA_NO_ONBOARD post-setup-result.conf\n" + "#".repeat(4 * 1024 * 1024))
        assertFalse(ManagedSetup.capable(f))
        f.delete()
        f.mkdirs()
        assertFalse(ManagedSetup.capable(f))
    }

    @Test
    fun `capableScript reads the home copy when there is one, never the bundle`() {
        val home = File(root, "post-setup.sh")
        val bundleOpened = AtomicInteger()
        val bundle = {
            bundleOpened.incrementAndGet()
            ByteArrayInputStream("OA_NO_ONBOARD post-setup-result.conf".toByteArray())
        }
        home.writeText("#!/bin/bash\nOA_NO_ONBOARD post-setup-result.conf\n")
        assertTrue(ManagedSetup.capableScript(home, bundle))
        // an older home copy decides too (the run refreshes it first; the page then falls back to the terminal)
        home.writeText("#!/bin/bash\necho old\n")
        assertFalse(ManagedSetup.capableScript(home, bundle))
        assertEquals(0, bundleOpened.get())
    }

    @Test
    fun `capableScript reads the bundle while there is no home copy (missing or empty)`() {
        val home = File(root, "post-setup.sh")
        assertTrue(
            ManagedSetup.capableScript(home) {
                ByteArrayInputStream("OA_NO_ONBOARD post-setup-result.conf".toByteArray())
            },
        )
        assertFalse(ManagedSetup.capableScript(home) { ByteArrayInputStream("OA_NO_ONBOARD".toByteArray()) })
        home.writeText("")
        assertTrue(
            ManagedSetup.capableScript(home) {
                ByteArrayInputStream("OA_NO_ONBOARD post-setup-result.conf".toByteArray())
            },
        )
        assertFalse(ManagedSetup.capableScript(home) { throw IOException("no asset") })
    }

    // ── the bundled asset ───────────────────────────────────────────────────

    @Test
    fun `the bundled post-setup sh equals the repository's byte for byte (doc-map pair) and is capable`() {
        assertTrue(repoScript.isFile, repoScript.absolutePath)
        assertTrue(bundledScript.isFile, bundledScript.absolutePath)
        assertArrayEquals(
            repoScript.readBytes(),
            bundledScript.readBytes(),
            "assets/post-setup.sh differs from post-setup.sh",
        )
        assertTrue(ManagedSetup.capable(bundledScript))
        assertTrue(ManagedSetup.capableScript(File(root, "none")) { bundledScript.inputStream() })
    }

    @Test
    fun `the doc-map lists the bundled copy as a pair of the repository's script`() {
        val docMap = File("../../.agent/doc-map.md").readText()
        assertTrue(docMap.contains("- `post-setup.sh` (루트) ↔ `android/app/src/main/assets/post-setup.sh`"))
    }

    /**
     * MASTER condition 3: the public download fails and the APK's bundled copy is used. With no home
     * copy, [BootstrapManager.refreshPostSetupScript] falls back to the bundle; the capability the
     * page was told before the refresh ([BootstrapManager.setupScriptCapable], read from the bundle)
     * is the one of the copy the run then executes. The download is made to fail without the
     * network: HTTPS goes through a proxy on a closed local port. The bundle is the real asset with
     * one marker line added, so the copy can only have come from it.
     */
    @Test
    fun `a failed download with no home copy runs the bundled script, and capable gives the same answer`() {
        val bundled = bundledScript.readBytes() + "\n# test: from the bundle\n".toByteArray()
        val manager = manager(bundled)
        val target = manager.postSetupScript
        assertFalse(target.exists())
        val before = manager.setupScriptCapable()
        assertTrue(before)
        withDownloadsFailing { manager.refreshPostSetupScript() }
        assertArrayEquals(bundled, target.readBytes(), "the bundle was not copied in")
        assertEquals(before, manager.setupScriptCapable())
        assertEquals(before, ManagedSetup.capable(target))
    }

    @Test
    fun `a failed download keeps an older home copy, which is not capable (the terminal flow)`() {
        val manager = manager(bundledScript.readBytes())
        val target = manager.postSetupScript
        requireNotNull(target.parentFile).mkdirs()
        target.writeText("#!/bin/bash\necho old script\n")
        assertFalse(manager.setupScriptCapable())
        withDownloadsFailing { manager.refreshPostSetupScript() }
        assertEquals("#!/bin/bash\necho old script\n", target.readText())
        assertFalse(manager.setupScriptCapable())
    }

    @Test
    fun `a bundle without the literals is not capable either before or after the fallback`() {
        val old = "#!/bin/bash\necho bundled but old\n".toByteArray()
        val manager = manager(old)
        assertFalse(manager.setupScriptCapable())
        withDownloadsFailing { manager.refreshPostSetupScript() }
        assertArrayEquals(old, manager.postSetupScript.readBytes())
        assertFalse(manager.setupScriptCapable())
    }

    @Test
    fun `setupMarkerPresent reads the marker the script writes`() {
        val manager = manager(bundledScript.readBytes())
        assertFalse(manager.setupMarkerPresent())
        File(manager.homeDir, ManagedSetup.MARKER).apply { requireNotNull(parentFile).mkdirs() }.writeText("")
        assertTrue(manager.setupMarkerPresent())
        assertTrue(manager.getStatus().platformInstalled, "getSetupStatus and the marker agree")
    }

    private fun manager(bundle: ByteArray): BootstrapManager {
        val context = mockk<Context>()
        val assets = mockk<AssetManager>()
        every { context.filesDir } returns File(root, "files").apply { mkdirs() }
        every { context.assets } returns assets
        every { assets.open("post-setup.sh") } answers { ByteArrayInputStream(bundle) }
        return BootstrapManager(context)
    }

    private fun withDownloadsFailing(block: () -> Unit) {
        val port = ServerSocket(0).use { it.localPort }
        val keys = listOf("https.proxyHost", "https.proxyPort", "http.proxyHost", "http.proxyPort")
        val saved = keys.associateWith { System.getProperty(it) }
        try {
            System.setProperty("https.proxyHost", "127.0.0.1")
            System.setProperty("https.proxyPort", "$port")
            System.setProperty("http.proxyHost", "127.0.0.1")
            System.setProperty("http.proxyPort", "$port")
            block()
        } finally {
            saved.forEach { (k, v) -> if (v == null) System.clearProperty(k) else System.setProperty(k, v) }
        }
    }

    // ── shouldRunInTerminal ─────────────────────────────────────────────────

    @Test
    fun `shouldRunInTerminal - all eight rows, true only for an older script, no marker and nothing running`() {
        val rows =
            listOf(
                Triple(false, false, false) to true,
                Triple(false, false, true) to false,
                Triple(false, true, false) to false,
                Triple(false, true, true) to false,
                Triple(true, false, false) to false,
                Triple(true, false, true) to false,
                Triple(true, true, false) to false,
                Triple(true, true, true) to false,
            )
        rows.forEach { (input, expected) ->
            val (capable, marker, active) = input
            assertEquals(
                expected,
                ManagedSetup.shouldRunInTerminal(
                    scriptCapable = capable,
                    markerPresent = marker,
                    managedRunActive = active,
                ),
                "capable=$capable marker=$marker active=$active",
            )
        }
    }

    @Test
    fun `managedRunActive is true for any lease holder and for the guard alone`() {
        assertFalse(ManagedSetup.managedRunActive())
        for (owner in listOf(RunLease.TOOLS, RunKinds.UPDATE, RunKinds.SETUP)) {
            assertTrue(RunLease.tryAcquire(owner))
            assertTrue(ManagedSetup.managedRunActive(), owner)
            RunLease.release(owner)
            assertFalse(ManagedSetup.managedRunActive(), "after $owner")
        }
        assertTrue(ManagedRunGuard.tryStart(RunKinds.SETUP, 0, "t"))
        assertTrue(ManagedSetup.managedRunActive(), "the guard without a lease")
        ManagedRunGuard.finish(RunVerdict.Success(0, 0))
        assertFalse(ManagedSetup.managedRunActive())
    }

    // ── MainActivity (not unit-testable: an Activity) — its decision is the shared one ──

    private val activity by lazy { File("src/main/java/com/openclaw/android/MainActivity.kt").readText() }

    private fun body(name: String): String {
        val start = activity.indexOf("fun $name(")
        assertTrue(start >= 0, "fun $name not found")
        val next = Regex("""\n    (override fun|private fun|fun|internal fun)\b""").find(activity, start + 1)
        return activity.substring(start, next?.range?.first ?: activity.length)
    }

    @Test
    fun `MainActivity types bash script only inside continueUnfinishedSetup, behind shouldRunInTerminal`() {
        val writes = Regex("""writeWhenReady\(session, "bash """).findAll(activity).count()
        assertEquals(1, writes, "MainActivity types the setup script in more than one place")
        val cont = body("continueUnfinishedSetup")
        val decide = cont.indexOf("ManagedSetup.shouldRunInTerminal(")
        assertTrue(decide >= 0, cont)
        assertTrue(cont.contains("markerPresent = markerPresent"), cont)
        assertTrue(cont.contains("managedRunActive = ManagedSetup.managedRunActive()"), cont)
        assertTrue(cont.contains("scriptCapable = capable"), cont)
        val guard = cont.indexOf("if (typeIt) {")
        val write = cont.indexOf("writeWhenReady(session, \"bash ")
        assertTrue(decide < guard && guard < write, "the write is not behind the decision: $cont")
        assertFalse(body("startInstalledTerminal").contains("writeWhenReady(session, \"bash "))
    }

    @Test
    fun `MainActivity asks the capability again after the refresh, on the refresh thread`() {
        val start = body("startInstalledTerminal")
        val refresh = start.indexOf("refreshPostSetupScript()")
        val again = start.indexOf("val capable = bootstrapManager.setupScriptCapable()")
        assertTrue(refresh in 0 until again, start)
        assertTrue(start.contains("continueUnfinishedSetup(session, capable, terminalFirst)"), start)
        assertTrue(
            start.contains("val terminalFirst = !needsPostSetup || !bootstrapManager.setupScriptCapable()"),
            start,
        )
    }
}
