package com.openclaw.android

import com.termux.terminal.TerminalSession

/**
 * A REAL [TerminalSession] on the JVM, where no shell process can start: [startShell] sets the pid
 * the way `TerminalSession.initializeEmulator` does, and [typed] reads what `write` handed to the
 * pty writer thread (the session's own input queue), without waiting.
 */
internal object TerminalSessionFakes {
    private const val BUFFER = 8192

    fun startShell(
        session: TerminalSession,
        pid: Int,
    ) {
        val f = TerminalSession::class.java.getDeclaredField("mShellPid")
        f.isAccessible = true
        f.setInt(session, pid)
    }

    fun typed(session: TerminalSession): String = String(drain(session), Charsets.UTF_8)

    /** The raw bytes waiting in the session's input queue, taken out (as the pty writer thread would). */
    fun drain(session: TerminalSession): ByteArray {
        val f = TerminalSession::class.java.getDeclaredField("mTerminalToProcessIOQueue")
        f.isAccessible = true
        val queue = f.get(session)
        val read = queue.javaClass.getDeclaredMethod("read", ByteArray::class.java, Boolean::class.javaPrimitiveType)
        read.isAccessible = true
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (true) {
            val n = read.invoke(queue, buffer, false) as Int
            if (n <= 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
