package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The app recognises the gateway and an updater by their `/proc/<pid>/cmdline`
 * ([GatewayControl.isGatewayCommand], [ProcScan.isUpdateRunner]). Those argument lists are made by
 * the scripts: `openclaw` is the shim of `platforms/openclaw/openclaw-shim.sh`, which execs the node
 * wrapper of `scripts/install-nodejs.sh`, which execs the glibc loader; `oa --update` runs the
 * downloaded `update-core.XXXXXX.sh` with bash (`oa.sh`). This contract reads those lines from the
 * scripts and builds every argument list the process shows on its way — so a script change that
 * reshapes them fails here and makes someone look at the app's recognition again.
 */
internal class LaunchChainContractTest {
    private fun script(path: String): String {
        val f = File("../../$path")
        assertTrue(f.isFile, "expected ${f.absolutePath}")
        return f.readText()
    }

    private val shim by lazy { script("platforms/openclaw/openclaw-shim.sh") }
    private val nodeInstaller by lazy { script("scripts/install-nodejs.sh") }
    private val oa by lazy { script("oa.sh") }

    /** The body of the `<< WRAPPER` heredoc (the node wrapper as written to `$BIN_DIR/node`). */
    private val wrapper by lazy {
        val start = nodeInstaller.indexOf("cat > \"\$BIN_DIR/node.tmp\" << WRAPPER\n")
        assertTrue(start >= 0, "the node wrapper heredoc is gone from install-nodejs.sh")
        val bodyStart = nodeInstaller.indexOf('\n', start) + 1
        val end = nodeInstaller.indexOf("\nWRAPPER\n", bodyStart)
        assertTrue(end > bodyStart, "the node wrapper heredoc has no end")
        nodeInstaller.substring(bodyStart, end)
    }

    /** The body of the `<< SHIM` heredoc (`$PREFIX/bin/openclaw`). */
    private val shimBody by lazy {
        val start = shim.indexOf("cat > \"\$OC_TMP\" << SHIM\n")
        assertTrue(start >= 0, "the shim heredoc is gone from openclaw-shim.sh")
        val bodyStart = shim.indexOf('\n', start) + 1
        val end = shim.indexOf("\nSHIM\n", bodyStart)
        assertTrue(end > bodyStart, "the shim heredoc has no end")
        shim.substring(bodyStart, end)
    }

    private fun assertLine(
        text: String,
        line: String,
        where: String,
    ) = assertTrue(text.lines().any { it.trim() == line }, "$where no longer has the line: $line")

    /** The last non-blank line of a script body: the exec that replaces the process. */
    private fun lastLine(body: String): String = body.lines().last { it.isNotBlank() }.trim()

    // ── the gateway: `openclaw gateway` typed into the app's terminal ───────

    @Test
    fun `the shim execs the node wrapper with openclaw mjs and passes every argument on`() {
        assertLine(shim, "OC_MJS=\"\$PREFIX/lib/node_modules/openclaw/openclaw.mjs\"", "openclaw-shim.sh")
        assertLine(shim, "OC_NODE=\"\$HOME/.openclaw-android/bin/node\"", "openclaw-shim.sh")
        assertEquals("#!\$PREFIX/bin/bash", shimBody.lines().first(), "the shim is not a bash script any more")
        assertEquals("exec \"\$OC_NODE\" \"\$OC_MJS\" \"\\\$@\"", lastLine(shimBody))
    }

    @Test
    fun `the node wrapper execs the glibc loader with node real and passes every argument on`() {
        assertLine(nodeInstaller, "OPENCLAW_DIR=\"\$HOME/.openclaw-android\"", "install-nodejs.sh")
        assertLine(nodeInstaller, "NODE_DIR=\"\$OPENCLAW_DIR/node\"", "install-nodejs.sh")
        assertLine(nodeInstaller, "BIN_DIR=\"\$OPENCLAW_DIR/bin\"", "install-nodejs.sh")
        assertLine(nodeInstaller, "GLIBC_LDSO=\"\$PREFIX/glibc/lib/ld-linux-aarch64.so.1\"", "install-nodejs.sh")
        assertEquals("#!\${PREFIX}/bin/bash", wrapper.lines().first(), "the node wrapper is not a bash script any more")
        assertEquals(
            "exec \"\$GLIBC_LDSO\" --library-path \"\$PREFIX/glibc/lib\" \"\$NODE_DIR/bin/node.real\" \"\\\$@\"",
            lastLine(wrapper),
        )
        // the glibc-compat preload goes through NODE_OPTIONS, never through argv
        assertTrue(wrapper.contains("export NODE_OPTIONS=\"\\\${NODE_OPTIONS:+\\\$NODE_OPTIONS }-r \\\$_OA_COMPAT\""))
    }

    @Test
    fun `the fallback openclaw launcher written by the npm wrapper execs the node wrapper too`() {
        val launcher =
            "printf '#!__PREFIX__/bin/bash\\nexec \"__BIN_DIR__/node\" \"%s\" \"\$@\"\\n' \"\$_oc_mjs\""
        assertTrue(
            nodeInstaller.contains(launcher),
            "install-nodejs.sh's fallback openclaw launcher changed shape",
        )
    }

    /**
     * Every cmdline the gateway process shows from the moment bash starts the shim to the running
     * gateway (the pid stays the same through each exec), built from the lines checked above.
     */
    @Test
    fun `every cmdline on the way from openclaw gateway to the running gateway is recognised`() {
        val mjs = "$PREFIX/lib/node_modules/openclaw/openclaw.mjs"
        val nodeWrapper = "$HOME/.openclaw-android/bin/node"
        val stages =
            listOf(
                // the kernel runs the shim (`#!$PREFIX/bin/bash`)
                listOf("$PREFIX/bin/bash", "$PREFIX/bin/openclaw", "gateway"),
                // the shim's exec: the node wrapper is a bash script as well
                listOf("$PREFIX/bin/bash", nodeWrapper, mjs, "gateway"),
                // the wrapper's exec
                listOf(
                    "$PREFIX/glibc/lib/ld-linux-aarch64.so.1",
                    "--library-path",
                    "$PREFIX/glibc/lib",
                    "$HOME/.openclaw-android/node/bin/node.real",
                    mjs,
                    "gateway",
                ),
                // with options before the subcommand, as a user may type them
                listOf(
                    "$PREFIX/glibc/lib/ld-linux-aarch64.so.1",
                    "--library-path",
                    "$PREFIX/glibc/lib",
                    "$HOME/.openclaw-android/node/bin/node.real",
                    mjs,
                    "--log-level",
                    "debug",
                    "gateway",
                ),
                // the gateway's own process title
                listOf("openclaw-gateway"),
            )
        stages.forEach { assertTrue(GatewayControl.isGatewayCommand(it), "not recognised as the gateway: $it") }
        // the same chain for another subcommand is not the gateway
        stages.dropLast(1).forEach { stage ->
            val other = stage.map { if (it == "gateway") "status" else it }
            assertTrue(!GatewayControl.isGatewayCommand(other), "read as the gateway: $other")
        }
    }

    // ── the updater: `oa --update` ──────────────────────────────────────────

    @Test
    fun `oa --update runs the downloaded update-core script with bash`() {
        val update = oa.substring(oa.indexOf("cmd_update() {"))
        assertTrue(
            update.contains("mktemp \"\${TMPDIR:-\${PREFIX:-/tmp}/tmp}/update-core.XXXXXX.sh\""),
            "oa.sh names its downloaded updater differently",
        )
        assertTrue(update.contains("bash \"\$TMPFILE\" 2>&1 | tee \"\$LOGFILE\""), "oa.sh runs its updater differently")
        assertEquals("#!/usr/bin/env bash", oa.lines().first())
    }

    @Test
    fun `every cmdline of oa --update is recognised as an updater`() {
        listOf(
            // the kernel runs `oa` (`#!/usr/bin/env bash`): env, then bash
            listOf("$PREFIX/bin/env", "bash", "$PREFIX/bin/oa", "--update"),
            listOf("bash", "$PREFIX/bin/oa", "--update"),
            // the downloaded updater, mktemp's six random characters filled in
            listOf("bash", "$PREFIX/tmp/update-core.Ab12Cd.sh"),
            listOf("bash", "/tmp/update-core.zZ09_x.sh"),
        ).forEach { assertTrue(ProcScan.isUpdateRunner(it), "not recognised as an updater: $it") }
    }

    private companion object {
        const val PREFIX = "/data/data/com.openclaw.android/files/usr"
        const val HOME = "/data/data/com.openclaw.android/files/home"
    }
}
