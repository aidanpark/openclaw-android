package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The debug build's extra probe (src/debug/.../DebugProbes.kt). This file is in `src/testDebug`: a
 * release build has no [DebugProbes.SELFTEST_HANG] at all — not in the allow-list and not in its code.
 */
class DebugProbesTest {
    @Test
    fun `the debug allow-list is the fixed probes plus the hang self-test`() {
        assertEquals(
            BridgeGuard.fixedVersionCommands.keys + DebugProbes.SELFTEST_HANG,
            BridgeGuard.versionCommands.keys,
        )
    }

    @Test
    fun `the self-test command is fixed here, nothing the page sends is part of it`() {
        assertEquals(
            BridgeGuard.Executable("sh", listOf("-c", "sleep 600 & wait")),
            BridgeGuard.versionCommands.getValue(DebugProbes.SELFTEST_HANG),
        )
    }
}
