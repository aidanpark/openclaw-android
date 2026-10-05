package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

/**
 * [ToolSignal.sendTerm] against a fake `/proc` and a fake lock pid file: the SIGTERM goes only to
 * a pid whose cmdline still names `post-setup.sh` AND whose environ carries this run's exact
 * `OA_APP_RUN_TOKEN=<token>` entry, and the answer says whether it was sent.
 */
internal class ToolSignalTest {
    @TempDir
    lateinit var root: File

    private lateinit var proc: File
    private lateinit var pidFile: File
    private val sent = mutableListOf<Pair<Int, Int>>()
    private val send: (Int, Int) -> Unit = { pid, sig -> sent.add(pid to sig) }

    @BeforeEach
    fun setup() {
        proc = File(root, "proc").apply { mkdirs() }
        pidFile = File(root, "home/.openclaw-android/.tools.lock/pid")
    }

    private fun lockPid(text: String) {
        pidFile.parentFile.mkdirs()
        pidFile.writeText(text)
    }

    /** /proc/<pid>/cmdline as the kernel writes it: arguments separated by NUL. */
    private fun cmdline(
        pid: Int,
        vararg args: String,
    ) {
        val dir = File(proc, "$pid").apply { mkdirs() }
        File(dir, "cmdline").writeBytes(args.joinToString("\u0000", postfix = "\u0000").toByteArray())
    }

    /** /proc/<pid>/environ as the kernel writes it: NAME=value entries separated by NUL. */
    private fun environ(
        pid: Int,
        vararg entries: String,
        trailingNul: Boolean = true,
    ) {
        val dir = File(proc, "$pid").apply { mkdirs() }
        val text = entries.joinToString("\u0000", postfix = if (trailingNul) "\u0000" else "")
        File(dir, "environ").writeBytes(text.toByteArray())
    }

    /** The install script as the app starts it: post-setup.sh with this run's token in its environment. */
    private fun ourScript(pid: Int = PID) {
        cmdline(pid, "bash", "/data/home/.openclaw-android/post-setup.sh", "--tools-only", "tmux")
        environ(pid, "HOME=/data/home", "${ToolSignal.ENV_NAME}=$TOKEN", "PATH=/usr/bin")
    }

    private fun sendTerm(token: String = TOKEN) = ToolSignal.sendTerm(pidFile, token, proc, send)

    private fun assertNotSent(result: Boolean) {
        assertFalse(result)
        assertTrue(sent.isEmpty(), sent.toString())
    }

    @Test
    fun `the install script's pid gets one SIGTERM`() {
        lockPid("4242\n")
        ourScript()
        assertTrue(sendTerm())
        assertEquals(listOf(PID to SIGTERM), sent)
    }

    @Test
    fun `surrounding whitespace in the pid file is accepted`() {
        lockPid("  4242 \r\n")
        ourScript()
        assertTrue(sendTerm())
        assertEquals(listOf(PID to SIGTERM), sent)
    }

    @Test
    fun `no pid file yet sends nothing and reports not sent`() {
        ourScript()
        assertFalse(sendTerm())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a pid path that is a directory sends nothing`() {
        pidFile.mkdirs()
        assertFalse(sendTerm())
        assertTrue(sent.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "abc", "42abc", "4242 4243", "4.2", "99999999999", "-4242", "0x10"])
    fun `a pid file that is not a single pid sends nothing`(text: String) {
        lockPid(text)
        ourScript()
        assertFalse(sendTerm(), "'$text'")
        assertTrue(sent.isEmpty(), "'$text' -> $sent")
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 0, -1])
    fun `pid 1 or lower is never signalled`(pid: Int) {
        lockPid("$pid")
        ourScript(pid) // even if /proc claimed it was this run's script
        assertFalse(sendTerm())
        assertTrue(sent.isEmpty(), sent.toString())
    }

    @Test
    fun `a recycled pid whose cmdline is another program is not signalled`() {
        lockPid("$PID")
        cmdline(PID, "/system/bin/app_process", "com.example.other")
        environ(PID, "${ToolSignal.ENV_NAME}=$TOKEN") // even with the token: not the script
        assertFalse(sendTerm())
        assertTrue(sent.isEmpty(), sent.toString())
    }

    @Test
    fun `a pid with no proc entry (the script is gone) is not signalled`() {
        lockPid("$PID")
        assertFalse(sendTerm())
        assertTrue(sent.isEmpty(), sent.toString())
    }

    @Test
    fun `a proc entry without a cmdline is not signalled`() {
        lockPid("$PID")
        environ(PID, "${ToolSignal.ENV_NAME}=$TOKEN")
        assertFalse(sendTerm())
        assertTrue(sent.isEmpty(), sent.toString())
    }

    @Test
    fun `an empty cmdline (a zombie) is not signalled`() {
        lockPid("$PID")
        environ(PID, "${ToolSignal.ENV_NAME}=$TOKEN")
        File(proc, "$PID/cmdline").writeBytes(ByteArray(0))
        assertFalse(sendTerm())
        assertTrue(sent.isEmpty(), sent.toString())
    }

    @Test
    fun `only the pid named in the lock is checked, not another script process`() {
        lockPid("$PID")
        cmdline(PID, "npm", "install")
        environ(PID, "${ToolSignal.ENV_NAME}=$TOKEN") // npm inherits the token, but is not the script
        ourScript(PID + 1)
        assertFalse(sendTerm())
        assertTrue(sent.isEmpty(), sent.toString())
    }

    @Test
    fun `each call re-reads the pid file and proc`() {
        assertFalse(sendTerm())
        lockPid("$PID")
        assertFalse(sendTerm())
        cmdline(PID, "bash", "post-setup.sh")
        assertFalse(sendTerm(), "no environ yet")
        environ(PID, "${ToolSignal.ENV_NAME}=$TOKEN")
        assertTrue(sendTerm())
        assertEquals(listOf(PID to SIGTERM), sent)
    }

    // ── the run token in /proc/<pid>/environ ────────────────────────────────

    @Test
    fun `the environment variable is OA_APP_RUN_TOKEN`() {
        assertEquals("OA_APP_RUN_TOKEN", ToolSignal.ENV_NAME)
    }

    /**
     * post-setup.sh keeps its own `OA_TOOLS_RUN` (the run id it writes to the result file). The
     * app's variable must be a name the script never assigns or reads, or the script would
     * overwrite the token the app marks the process with (or treat the app's token as its run id).
     */
    @ParameterizedTest
    @ValueSource(strings = ["../../post-setup.sh", "src/main/assets/post-setup.sh"])
    fun `post-setup sh never uses the app's variable name`(path: String) {
        val script = File(path)
        assertTrue(script.isFile, "missing ${script.absolutePath}")
        val text = script.readText()
        assertFalse(text.contains(ToolSignal.ENV_NAME), "$path mentions ${ToolSignal.ENV_NAME}")
        assertTrue(text.contains("OA_TOOLS_RUN="), "$path no longer has its own run variable: update this test")
        assertFalse(ToolSignal.ENV_NAME == "OA_TOOLS_RUN")
    }

    @ParameterizedTest
    @ValueSource(strings = ["first", "middle", "last-no-trailing-nul", "only"])
    fun `the token entry is found wherever it sits in environ`(where: String) {
        lockPid("$PID")
        cmdline(PID, "bash", "post-setup.sh", "--tools-only", "tmux")
        val mine = "${ToolSignal.ENV_NAME}=$TOKEN"
        when (where) {
            "first" -> environ(PID, mine, "HOME=/h", "PATH=/p")
            "middle" -> environ(PID, "HOME=/h", mine, "PATH=/p")
            "last-no-trailing-nul" -> environ(PID, "HOME=/h", "PATH=/p", mine, trailingNul = false)
            else -> environ(PID, mine)
        }
        assertTrue(sendTerm(), where)
        assertEquals(listOf(PID to SIGTERM), sent)
    }

    @Test
    fun `a post-setup sh with no environ entry (another run, e g a terminal oa) is not signalled`() {
        lockPid("$PID")
        cmdline(PID, "bash", "post-setup.sh", "--tools-only", "tmux")
        assertNotSent(sendTerm())
    }

    @Test
    fun `a post-setup sh whose environment has no token (a terminal oa) is not signalled`() {
        lockPid("$PID")
        cmdline(PID, "bash", "post-setup.sh", "--tools-only", "tmux")
        environ(PID, "HOME=/data/home", "PATH=/usr/bin")
        assertNotSent(sendTerm())
    }

    @Test
    fun `a post-setup sh started with another run's token (an earlier app run) is not signalled`() {
        lockPid("$PID")
        cmdline(PID, "bash", "post-setup.sh", "--tools-only", "tmux")
        environ(PID, "HOME=/data/home", "${ToolSignal.ENV_NAME}=$OTHER_TOKEN")
        assertNotSent(sendTerm())
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "OA_APP_RUN_TOKEN=abcX", // the token is only a prefix of the value
            "XOA_APP_RUN_TOKEN=abc", // the entry is only a suffix of another name
            "OA_APP_RUN_TOKEN=ab", // the value is only a prefix of the token
            "OA_APP_RUN_TOKEN=xabc", // the token is only a suffix of the value
            "OA_APP_RUN_TOKEN=abc ", // trailing blank
            " OA_APP_RUN_TOKEN=abc", // leading blank
            "oa_app_run_token=abc", // another case
            "OA_APP_RUN_TOKEN:abc", // not NAME=value
            "PREFIX=/usr\nOA_APP_RUN_TOKEN=abc", // inside another value, separated by a newline, not NUL
            "OA_TOOLS_RUN=abc", // the old name, which the script itself assigns
        ],
    )
    fun `an environ entry that only partly matches the token is not this run`(entry: String) {
        lockPid("$PID")
        cmdline(PID, "bash", "post-setup.sh", "--tools-only", "tmux")
        environ(PID, "HOME=/data/home", entry, "PATH=/usr/bin")
        assertFalse(ToolSignal.sendTerm(pidFile, "abc", proc, send), "'$entry'")
        assertTrue(sent.isEmpty(), "'$entry' -> $sent")
    }

    @Test
    fun `the token in the command line but not in environ is not this run`() {
        lockPid("$PID")
        cmdline(PID, "bash", "post-setup.sh", "${ToolSignal.ENV_NAME}=$TOKEN")
        environ(PID, "HOME=/data/home")
        assertNotSent(sendTerm())
    }

    @Test
    fun `an empty token never signals, not even a script whose entry is empty`() {
        lockPid("$PID")
        cmdline(PID, "bash", "post-setup.sh", "--tools-only", "tmux")
        environ(PID, "HOME=/data/home", "${ToolSignal.ENV_NAME}=")
        assertNotSent(sendTerm(token = ""))
    }

    @Test
    fun `an empty token never signals this run's script either`() {
        lockPid("$PID")
        ourScript()
        assertNotSent(sendTerm(token = ""))
    }

    @Test
    fun `an environ that is a directory (cannot be read) is not signalled`() {
        lockPid("$PID")
        cmdline(PID, "bash", "post-setup.sh", "--tools-only", "tmux")
        File(proc, "$PID/environ").mkdirs()
        assertNotSent(sendTerm())
    }

    @Test
    fun `an environ without read permission (another uid's process) is not signalled`() {
        lockPid("$PID")
        ourScript()
        val environ = File(proc, "$PID/environ")
        assertTrue(environ.setReadable(false, false))
        try {
            assumeFalse(environ.canRead(), "running as a user that reads any file")
            assertNotSent(sendTerm())
        } finally {
            environ.setReadable(true, false)
        }
    }

    @Test
    fun `an empty environ (a zombie) is not signalled`() {
        lockPid("$PID")
        cmdline(PID, "bash", "post-setup.sh", "--tools-only", "tmux")
        File(proc, "$PID/environ").writeBytes(ByteArray(0))
        assertNotSent(sendTerm())
    }

    @Test
    fun `the token of a pid other than the one in the lock does not count`() {
        lockPid("$PID")
        cmdline(PID, "bash", "post-setup.sh", "--tools-only", "tmux")
        environ(PID, "HOME=/data/home")
        environ(PID + 1, "${ToolSignal.ENV_NAME}=$TOKEN")
        assertNotSent(sendTerm())
    }

    private companion object {
        const val PID = 4242
        const val SIGTERM = 15
        const val TOKEN = "3f1c9a2e-7b44-4d0e-9a51-0c6f2d8e1b7a"
        const val OTHER_TOKEN = "9d2b7e10-1c3a-4f55-8e60-2a7b9c4d5e6f"
    }
}
