package com.openclaw.android

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/**
 * Multi-terminal session management (§2.6, Phase 1 checklist).
 * Uses TerminalView.attachSession() for session switching — one TerminalView, many sessions.
 */
class TerminalSessionManager(
    private val activity: MainActivity,
    private val sessionClient: TerminalSessionClient,
    private val eventBridge: EventBridge,
) {
    companion object {
        private const val TAG = "SessionManager"
        private const val TRANSCRIPT_ROWS = 2000
    }

    private val sessions = mutableListOf<TerminalSession>()
    private var activeSessionIndex = -1
    private val finishedSessionIds = mutableSetOf<String>()
    var onSessionsChanged: (() -> Unit)? = null

    val activeSession: TerminalSession?
        get() = sessions.getOrNull(activeSessionIndex)

    /**
     * Create a new terminal session. Returns the session handle.
     */
    fun createSession(): TerminalSession {
        val env = EnvironmentBuilder.build(activity)
        val prefix = env["PREFIX"] ?: ""
        val homeDir = env["HOME"] ?: activity.filesDir.absolutePath
        val tmpDir = env["TMPDIR"]

        // Ensure HOME and TMP directories exist before starting the shell.
        // Without this, chdir() fails if bootstrap hasn't been run yet.
        java.io.File(homeDir).mkdirs()
        tmpDir?.let { java.io.File(it).mkdirs() }

        val shell =
            if (java.io.File("$prefix/bin/bash").exists()) {
                "$prefix/bin/bash"
            } else if (java.io.File("$prefix/bin/sh").exists()) {
                "$prefix/bin/sh"
            } else {
                "/system/bin/sh"
            }

        val session =
            TerminalSession(
                shell,
                homeDir,
                arrayOf<String>(),
                env.entries.map { "${it.key}=${it.value}" }.toTypedArray(),
                TRANSCRIPT_ROWS,
                sessionClient,
            )

        sessions.add(session)
        switchSession(sessions.size - 1)

        eventBridge.emit(
            "session_changed",
            mapOf("id" to session.mHandle, "action" to "created"),
        )
        activity.runOnUiThread { onSessionsChanged?.invoke() }

        AppLogger.i(TAG, "Created session ${session.mHandle} (total: ${sessions.size})")
        return session
    }

    /**
     * Switch to session by index.
     */
    fun switchSession(index: Int) {
        if (index < 0 || index >= sessions.size) return
        activeSessionIndex = index
        val session = sessions[index]
        activity.runOnUiThread {
            val terminalView = activity.findViewById<com.termux.view.TerminalView>(R.id.terminalView)
            terminalView.attachSession(session)
            terminalView.invalidate()
        }
        eventBridge.emit(
            "session_changed",
            mapOf("id" to session.mHandle, "action" to "switched"),
        )
        activity.runOnUiThread { onSessionsChanged?.invoke() }
    }

    /**
     * Switch to session by handle ID.
     */
    fun switchSession(handleId: String) {
        val index = sessions.indexOfFirst { it.mHandle == handleId }
        if (index >= 0) switchSession(index)
    }

    /**
     * Find a session by handle ID.
     */
    fun getSessionById(handleId: String): TerminalSession? = sessions.find { it.mHandle == handleId }

    /**
     * Close a session by handle ID.
     */
    fun closeSession(handleId: String) {
        val index = sessions.indexOfFirst { it.mHandle == handleId }
        if (index < 0) return

        finishedSessionIds.remove(handleId)
        val session = sessions.removeAt(index)
        session.finishIfRunning()

        eventBridge.emit(
            "session_changed",
            mapOf("id" to handleId, "action" to "closed"),
        )

        // Switch to another session if available
        if (sessions.isNotEmpty()) {
            val newIndex = (index).coerceAtMost(sessions.size - 1)
            switchSession(newIndex)
        } else {
            activeSessionIndex = -1
        }

        activity.runOnUiThread { onSessionsChanged?.invoke() }
        AppLogger.i(TAG, "Closed session $handleId (remaining: ${sessions.size})")
    }

    /**
     * Called when a session's process exits.
     */
    fun onSessionFinished(session: TerminalSession) {
        finishedSessionIds.add(session.mHandle)
        eventBridge.emit(
            "session_changed",
            mapOf("id" to session.mHandle, "action" to "finished"),
        )
        activity.runOnUiThread { onSessionsChanged?.invoke() }
    }

    /**
     * Get all sessions info for JsBridge.
     */
    fun getSessionsInfo(): List<Map<String, Any>> =
        sessions.mapIndexed { index, session ->
            mapOf(
                "id" to session.mHandle,
                "name" to (session.title ?: "Session ${index + 1}"),
                "active" to (index == activeSessionIndex),
                "finished" to (session.mHandle in finishedSessionIds),
            )
        }

    fun isSessionFinished(handleId: String): Boolean = handleId in finishedSessionIds

    /**
     * Shell pids of the live sessions (an ended one reports -1 and is left out) — the roots below
     * which a gateway counts as the app's own. Read from a worker thread: a copy of the list, never
     * an iteration over it while the UI thread changes it.
     */
    fun sessionPids(): List<Int> =
        sessions
            .toTypedArray<TerminalSession?>()
            .mapNotNull { it?.pid }
            .filter { it > 0 }

    val sessionCount: Int get() = sessions.size
}
