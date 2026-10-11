package com.openclaw.android

/**
 * Probes that exist in the debug build only (this file is in `src/debug`; the release build has the
 * empty counterpart in `src/release`, so neither the id nor the command is in a release APK).
 */
internal object DebugProbes {
    /**
     * QA's check that a probe ending on its time limit leaves no descendant: a shell whose background
     * `sleep` outlives it. After the 5 s probe limit, `ps` must show neither the shell nor the `sleep`.
     */
    const val SELFTEST_HANG = "selftestHang"

    val versionCommands: Map<String, BridgeGuard.Executable> =
        mapOf(SELFTEST_HANG to BridgeGuard.Executable("sh", listOf("-c", "sleep 600 & wait")))
}
