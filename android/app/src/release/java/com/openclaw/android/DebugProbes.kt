package com.openclaw.android

/** The release build has no debug-only probes (see `src/debug/.../DebugProbes.kt`). */
internal object DebugProbes {
    val versionCommands: Map<String, BridgeGuard.Executable> = emptyMap()
}
