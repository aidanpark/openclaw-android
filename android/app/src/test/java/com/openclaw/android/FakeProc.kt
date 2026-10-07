package com.openclaw.android

import java.io.File

/**
 * A fake `/proc` for [ProcScan]: one directory per pid with `cmdline` and `environ` (NUL-separated,
 * NUL-terminated like the kernel's) and `stat` (`pid (comm) state ppid …`).
 */
internal class FakeProc(
    val dir: File,
) {
    init {
        dir.mkdirs()
    }

    fun add(
        pid: Int,
        ppid: Int,
        cmdline: List<String>,
        environ: List<String> = emptyList(),
        comm: String = cmdline.firstOrNull()?.substringAfterLast('/') ?: "x",
    ): FakeProc {
        val d = File(dir, "$pid").apply { mkdirs() }
        File(d, "cmdline").writeBytes(nul(cmdline))
        if (environ.isNotEmpty()) File(d, "environ").writeBytes(nul(environ))
        File(d, "stat").writeText("$pid ($comm) S $ppid $pid $pid 0 -1 4194560 0 0 0\n")
        return this
    }

    fun cmdline(
        pid: Int,
        cmdline: List<String>,
    ) = File(dir, "$pid/cmdline").writeBytes(nul(cmdline))

    /** The process ended but its parent has not reaped it yet (the kernel then shows an empty cmdline). */
    fun zombie(pid: Int) {
        val stat = File(dir, "$pid/stat")
        stat.writeText(stat.readText().replace(Regex("""\) [A-Z] (?=-?\d+ \d+ \d+ 0 -1)"""), ") Z "))
        File(dir, "$pid/cmdline").writeBytes(ByteArray(0))
    }

    fun remove(pid: Int) = File(dir, "$pid").deleteRecursively()

    private fun nul(parts: List<String>): ByteArray = parts.joinToString("") { it + "\u0000" }.toByteArray()
}
