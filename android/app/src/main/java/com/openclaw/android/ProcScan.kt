package com.openclaw.android

import java.io.File

/**
 * Reads the app's own processes from /proc (Android hides other apps' processes). Every read that
 * fails — the process ended, the entry is not readable — is skipped quietly: a scan is a snapshot
 * of something that keeps changing. [procDir] is injectable so a fake tree can be tested.
 */
internal class ProcScan(
    private val procDir: File = File("/proc"),
) {
    /** Every numeric entry of /proc. */
    fun pids(): List<Int> = procDir.list()?.mapNotNull { name -> name.toIntOrNull()?.takeIf { it > 0 } } ?: emptyList()

    /** The arguments of [pid] (NUL-separated in /proc), or null when unreadable. */
    fun cmdline(pid: Int): List<String>? = readNulSeparated(File(procDir, "$pid/cmdline"))

    /** The `NAME=value` entries of [pid]'s environment, or null when unreadable. */
    fun environ(pid: Int): List<String>? = readNulSeparated(File(procDir, "$pid/environ"))

    /**
     * Parent pid from `/proc/<pid>/stat`. The command name in parentheses may itself contain spaces
     * and `)`, so the fields are read after the LAST `)`: `<state> <ppid> …`.
     */
    fun ppid(pid: Int): Int? = statFields(pid)?.getOrNull(1)?.toIntOrNull()

    /** True while [pid] exists and is not a zombie (an ended child its parent has not reaped yet). */
    fun alive(pid: Int): Boolean = statFields(pid)?.firstOrNull()?.let { it != ZOMBIE } ?: false

    /** Pids whose environment has exactly `OA_APP_RUN_TOKEN=<token>`; empty for an empty token. */
    fun pidsWithToken(token: String): Set<Int> {
        if (token.isEmpty()) return emptySet()
        val entry = "${ToolSignal.ENV_NAME}=$token"
        return pids().filter { environ(it)?.contains(entry) == true }.toSet()
    }

    /**
     * Pids that run an install or update started outside the app's managed run (a terminal's
     * `oa --update`, `update-core.sh`, `post-setup.sh` — [isUpdateRunner]) — except those carrying
     * [ownToken]. A false positive only delays a run (BUSY), which is the safe direction, but a viewer
     * or editor of such a script is not a runner (it would refuse every run while it stays open).
     */
    fun externalRunners(ownToken: String): List<Int> {
        val ownEntry = "${ToolSignal.ENV_NAME}=$ownToken"
        return pids().filter { pid ->
            val args = cmdline(pid)
            args != null &&
                isUpdateRunner(args) &&
                (ownToken.isEmpty() || environ(pid)?.contains(ownEntry) != true)
        }
    }

    /** Every pid below [rootPids] in the parent chain (the roots themselves not included). */
    fun descendantsOf(rootPids: Collection<Int>): Set<Int> {
        if (rootPids.isEmpty()) return emptySet()
        val children = HashMap<Int, MutableList<Int>>()
        for (pid in pids()) {
            val parent = ppid(pid) ?: continue
            children.getOrPut(parent) { mutableListOf() } += pid
        }
        val found = LinkedHashSet<Int>()
        val queue = ArrayDeque(rootPids.toSet())
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            for (child in children[next].orEmpty()) {
                if (child !in rootPids && found.add(child)) queue.addLast(child)
            }
        }
        return found
    }

    private fun statFields(pid: Int): List<String>? {
        val stat = readText(File(procDir, "$pid/stat")) ?: return null
        val close = stat.lastIndexOf(')')
        if (close < 0) return null
        return stat
            .substring(close + 1)
            .trim()
            .split(' ')
            .filter { it.isNotEmpty() }
    }

    private fun readNulSeparated(file: File): List<String>? =
        readText(file)?.split('\u0000')?.dropLastWhile { it.isEmpty() }

    private fun readText(file: File): String? =
        try {
            file.readBytes().toString(Charsets.UTF_8)
        } catch (_: java.io.IOException) {
            null
        } catch (_: SecurityException) {
            null
        }

    companion object {
        private const val ZOMBIE = "Z"
        private val runnerScripts = setOf("post-setup.sh", "oaupdate")
        private const val OA = "oa"
        private const val UPDATE_FLAG = "--update"

        /** `oa --update` downloads its updater to `update-core.XXXXXX.sh` (oa.sh:93-95). */
        private val updateCore = Regex("""^update-core(\.[^/]*)?\.sh$""")

        /**
         * The arguments of an updater or installer: the program the process runs ([CommandLine.programAt])
         * is `update-core*.sh`, `post-setup.sh`, `oaupdate`, or `oa` directly followed by `--update`.
         * Only the program counts: `less …/post-setup.sh` or `vim update-core.sh` runs `less`/`vim`.
         */
        fun isUpdateRunner(args: List<String>): Boolean {
            val at = CommandLine.programAt(args)
            if (at < 0) return false
            val name = CommandLine.baseName(args[at])
            return name in runnerScripts ||
                updateCore.matches(name) ||
                (name == OA && args.getOrNull(at + 1)?.trim() == UPDATE_FLAG)
        }
    }
}

/**
 * What a process runs, read from its arguments (`/proc/<pid>/cmdline`, one entry per argument, never
 * split at spaces). The launchers in front of the program are passed over: the glibc loader
 * (`ld-linux-aarch64.so.1 --library-path <dir> …/node.real …`, scripts/install-nodejs.sh), an
 * interpreter (`node`, `bash`, `sh`, `env`) and every option (`-…`). A kernel-started script reads
 * `bash /path/to/script …`, so the script is the program.
 */
internal object CommandLine {
    private val launchers = setOf("node", "node.real", "bash", "sh", "env")
    private const val LOADER_PREFIX = "ld-linux"
    private const val LIBRARY_PATH = "--library-path"

    /** Index of the first argument that is not a launcher, an option or the loader's library path; -1 if none. */
    fun programAt(args: List<String>): Int =
        args.indices.firstOrNull { i ->
            val arg = args[i].trim()
            val name = baseName(arg)
            val skip =
                arg.startsWith('-') ||
                    (i > 0 && args[i - 1].trim() == LIBRARY_PATH) ||
                    name.startsWith(LOADER_PREFIX) ||
                    name in launchers
            !skip
        } ?: -1

    /** The file name of an argument (`/a/b/openclaw.mjs` → `openclaw.mjs`). */
    fun baseName(arg: String): String = arg.trim().substringAfterLast('/')
}

/** How a managed run reaches processes: the /proc reader and the signal (both replaced in tests). */
internal class RunProcesses(
    val scan: ProcScan = ProcScan(),
    val send: (Int, Int) -> Unit = { pid, sig -> SafeSignal.send(pid, sig) },
)

/**
 * Sends the cancel SIGTERM to every process of one managed run: each pid whose environment carries
 * the run's token ([ProcScan.pidsWithToken]), parents first (a pid whose parent is not in the set is
 * a top). The token comes from the app, so neither a recycled pid nor a terminal's run is touched.
 * `oa --update` is a pipeline (`bash update-core | tee`) whose bash does not pass TERM on, which is
 * why every process of the run is signalled, not only the top one.
 */
internal object RunSignal {
    const val SIGTERM = 15
    const val SIGKILL = 9

    /**
     * True when at least one process was signalled. [allowed] is asked before each signal: it is
     * false once the run has left its cancelable stages, so a late cancel never interrupts them.
     */
    fun sendTerm(
        token: String,
        scan: ProcScan = ProcScan(),
        allowed: () -> Boolean = { true },
        send: (Int, Int) -> Unit = { pid, sig -> SafeSignal.send(pid, sig) },
    ): Boolean {
        val pids = scan.pidsWithToken(token)
        var delivered = false
        for (pid in topDown(pids, scan)) {
            if (!allowed()) break
            send(pid, SIGTERM)
            delivered = true
        }
        return delivered
    }

    /** [pids] ordered by depth inside the set: tops first. */
    fun topDown(
        pids: Set<Int>,
        scan: ProcScan,
    ): List<Int> {
        val parent = pids.associateWith { scan.ppid(it) }

        fun depth(pid: Int): Int {
            var d = 0
            var at = parent[pid]
            // bounded by the set's size: a parent loop (a recycled pid) cannot spin
            while (at != null && at in pids && d < pids.size) {
                d++
                at = parent[at]
            }
            return d
        }
        return pids.sortedWith(compareBy<Int>({ depth(it) }, { it }))
    }
}
