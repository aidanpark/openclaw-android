package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * `post-setup.sh --tools-only --list` must look like a plain id list (exit 0, containing the
 * requested id) before the real call is made. Anything else means "an older script".
 */
class ToolListCheckTest {
    private val ids = "tmux\nttyd\ndufs\nandroid-tools\ncode-server\nplaywright\nclaude-code\ngemini-cli\ncodex-cli\n"

    @Test
    fun `the new script's id list with the requested id is accepted`() {
        assertTrue(ToolListCheck.supports(ids, 0, "tmux"))
        assertTrue(ToolListCheck.supports(ids, 0, "codex-cli"))
    }

    @Test
    fun `CRLF and a missing final newline are tolerated`() {
        assertTrue(ToolListCheck.supports("tmux\r\nttyd\r\n", 0, "ttyd"))
        assertTrue(ToolListCheck.supports("tmux", 0, "tmux"))
    }

    @Test
    fun `the older script's already-complete line is rejected`() {
        assertFalse(ToolListCheck.supports("Post-setup already completed.\n", 0, "tmux"))
    }

    @Test
    fun `a full install log is rejected even if it mentions the id`() {
        val log =
            "══════════════\n  OpenClaw Android — Installing components\n[1/7] tmux\n" + "tmux\n"
        assertFalse(ToolListCheck.supports(log, 0, "tmux"))
    }

    @Test
    fun `empty or blank output is rejected`() {
        assertFalse(ToolListCheck.supports("", 0, "tmux"))
        assertFalse(ToolListCheck.supports("\n\n\r\n", 0, "tmux"))
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 127, -1, 143])
    fun `a non zero exit code is rejected even with a valid list`(exit: Int) {
        assertFalse(ToolListCheck.supports(ids, exit, "tmux"))
    }

    @Test
    fun `id lines mixed with any other line are rejected`() {
        assertFalse(ToolListCheck.supports("tmux\nWarning: something\nttyd\n", 0, "tmux"))
        assertFalse(ToolListCheck.supports("tmux\nttyd\nDone.\n", 0, "tmux"))
    }

    @Test
    fun `a list without the requested id is rejected`() {
        assertFalse(ToolListCheck.supports(ids, 0, "opencode"))
        assertFalse(ToolListCheck.supports(ids, 0, "tmu"))
        assertFalse(ToolListCheck.supports(ids, 0, ""))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "tmux ", " tmux", "tm ux", "tmux\t", "Tmux", "1tmux", "-tmux", "tmux_x", "tmux\u0007", "tmux\u001b[0m",
        ],
    )
    fun `a line with spaces control characters or other shapes is rejected`(line: String) {
        assertFalse(ToolListCheck.supports("$line\ntmux\n", 0, "tmux"), line)
    }

    @Test
    fun `an id line longer than 31 characters is rejected`() {
        val long = "a" + "b".repeat(31)
        assertFalse(ToolListCheck.supports("$long\ntmux\n", 0, "tmux"))
        val longest = "a" + "b".repeat(30)
        assertTrue(ToolListCheck.supports("$longest\ntmux\n", 0, "tmux"))
    }

    @Test
    fun `more than 50 lines are rejected and exactly 50 are accepted`() {
        val fifty = (1..49).joinToString("\n") { "t$it" } + "\ntmux\n"
        assertTrue(ToolListCheck.supports(fifty, 0, "tmux"))
        val fiftyOne = (1..50).joinToString("\n") { "t$it" } + "\ntmux\n"
        assertFalse(ToolListCheck.supports(fiftyOne, 0, "tmux"))
    }

    @Test
    fun `output over 2000 characters is rejected even if every line is an id`() {
        val big = "tmux\n" + "\n".repeat(2_000)
        assertFalse(ToolListCheck.supports(big, 0, "tmux"))
        val atLimit = "tmux\n" + "\n".repeat(2_000 - 5)
        assertTrue(ToolListCheck.supports(atLimit, 0, "tmux"))
    }
}
