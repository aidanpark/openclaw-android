package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * `tools-result.conf` is read as data by the compiled [ToolResultParser]: anything it cannot trust
 * as a whole is null, single bad lines are skipped, values are kept as plain strings.
 */
class ToolResultParserTest {
    private fun parse(vararg lines: String): ToolResultFile? =
        ToolResultParser.parse(lines.joinToString("\n", postfix = "\n"))

    private fun parseOk(vararg lines: String): ToolResultFile {
        val r = parse(*lines)
        assertNotNull(r, lines.toList().toString())
        return r!!
    }

    @Test
    fun `a normal result file is parsed into run error exit and tools`() {
        val r = parseOk("schema=1", "run=1700000000", "tmux=ok", "exit=0")
        assertEquals(ToolResultFile(1_700_000_000L, null, 0, mapOf("tmux" to "ok")), r)
    }

    @Test
    fun `reserved keys never appear as tools`() {
        val r = parseOk("schema=1", "run=5", "error=env", "exit=2", "ttyd=ok")
        assertEquals(setOf("ttyd"), r.tools.keys)
    }

    @Test
    fun `failed outcomes are kept verbatim per tool`() {
        val r = parseOk("schema=1", "run=10", "tmux=failed:install", "dufs=failed:verify", "exit=1")
        assertEquals(mapOf("tmux" to "failed:install", "dufs" to "failed:verify"), r.tools)
        assertEquals(1, r.exit)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "usage", "env", "interrupted", "index-download", "index-mismatch", "index-noentry",
            "index-1", "index-2", "index-12", "something-new",
        ],
    )
    fun `every error value is kept as the raw string`(value: String) {
        assertEquals(value, parseOk("schema=1", "run=1", "error=$value").error)
    }

    @Test
    fun `missing schema is not trusted`() {
        assertNull(parse("run=1", "tmux=ok"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["2", "0", "", "1 ", " 1", "01"])
    fun `any schema other than exactly 1 is not trusted`(schema: String) {
        assertNull(parse("schema=$schema", "run=1", "tmux=ok"))
    }

    @Test
    fun `missing run is not trusted`() {
        assertNull(parse("schema=1", "tmux=ok"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "abc", "-1", "1.5", "0x10", "99999999999999999999", "1 "])
    fun `a non numeric or negative run is not trusted`(run: String) {
        assertNull(parse("schema=1", "run=$run", "tmux=ok"))
    }

    @Test
    fun `run zero is accepted`() {
        assertEquals(0L, parseOk("schema=1", "run=0").runEpoch)
    }

    @Test
    fun `a repeated key makes the whole file untrusted`() {
        assertNull(parse("schema=1", "run=1", "tmux=failed:install", "tmux=ok"))
        assertNull(parse("schema=1", "run=1", "run=2"))
        assertNull(parse("schema=1", "schema=1", "run=1"))
    }

    @Test
    fun `a line longer than the limit is skipped and the rest still counts`() {
        val long = "tmux=" + "x".repeat(ToolResultParser.MAX_LINE)
        val r = parseOk("schema=1", "run=1", long, "ttyd=ok")
        assertEquals(mapOf("ttyd" to "ok"), r.tools)
    }

    @Test
    fun `a line of exactly the limit is kept`() {
        val value = "x".repeat(ToolResultParser.MAX_LINE - "tmux=".length)
        assertEquals(value, parseOk("schema=1", "run=1", "tmux=$value").tools["tmux"])
    }

    @Test
    fun `a skipped over-long line does not count as a duplicate`() {
        val long = "tmux=" + "x".repeat(ToolResultParser.MAX_LINE)
        assertEquals("ok", parseOk("schema=1", "run=1", long, "tmux=ok").tools["tmux"])
    }

    @Test
    fun `lines without an equals sign and empty lines are skipped`() {
        val r = parseOk("", "schema=1", "garbage", "", "run=3", "=ok", "tmux=ok", "")
        assertEquals(mapOf("tmux" to "ok"), r.tools)
        assertEquals(3L, r.runEpoch)
    }

    @Test
    fun `CRLF line endings are tolerated`() {
        val r = ToolResultParser.parse("schema=1\r\nrun=7\r\ntmux=ok\r\nexit=0\r\n")
        assertEquals(ToolResultFile(7L, null, 0, mapOf("tmux" to "ok")), r)
    }

    @Test
    fun `a line with a control character is skipped`() {
        val r = parseOk("schema=1", "run=1", "tmux=o\u0007k", "ttyd=ok\u001b[0m", "dufs=ok")
        assertEquals(mapOf("dufs" to "ok"), r.tools)
    }

    @Test
    fun `a stray CR inside a line is a control character and the line is skipped`() {
        assertEquals(emptyMap<String, String>(), parseOk("schema=1", "run=1", "tmux=o\rk").tools)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "tmux tool=ok", "a/b=ok", "\$(id)=ok", "k;x=ok", " tmux=ok",
            "kkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkk=ok",
        ],
    )
    fun `a line whose key breaks the key format is skipped`(line: String) {
        assertEquals(emptyMap<String, String>(), parseOk("schema=1", "run=1", line).tools, line)
    }

    @Test
    fun `a key of exactly 40 allowed characters is accepted`() {
        val key = "A_b.c-9" + "k".repeat(33)
        assertEquals("ok", parseOk("schema=1", "run=1", "$key=ok").tools[key])
    }

    @Test
    fun `shell metacharacters in a value are kept as text and never interpreted`() {
        val values = listOf("\$(touch /tmp/pwned)", "`id`", "ok; rm -rf /", "a=b=c", "\${HOME}")
        val r = parseOk("schema=1", "run=1", *values.mapIndexed { i, v -> "t$i=$v" }.toTypedArray())
        values.forEachIndexed { i, v -> assertEquals(v, r.tools["t$i"]) }
        assertEquals("\$(reboot)", parseOk("schema=1", "run=1", "error=\$(reboot)").error)
    }

    @Test
    fun `the value is everything after the first equals sign`() {
        assertEquals("failed:a=b", parseOk("schema=1", "run=1", "tmux=failed:a=b").tools["tmux"])
    }

    @Test
    fun `a non numeric exit is kept as null`() {
        assertNull(parseOk("schema=1", "run=1", "exit=x").exit)
    }

    @Test
    fun `an empty file is not trusted`() {
        assertNull(ToolResultParser.parse(""))
    }

    @Test
    fun `a file over the size cap is not trusted even if well formed`() {
        val head = "schema=1\nrun=1\ntmux=ok\n"
        val padded = head + "#".repeat(ToolResultParser.MAX_CHARS - head.length + 1)
        assertEquals(ToolResultParser.MAX_CHARS + 1, padded.length)
        assertNull(ToolResultParser.parse(padded))
    }

    @Test
    fun `a file of exactly the size cap is still read`() {
        val head = "schema=1\nrun=1\ntmux=ok\n"
        val padded = head + "#".repeat(ToolResultParser.MAX_CHARS - head.length)
        assertEquals("ok", ToolResultParser.parse(padded)?.tools?.get("tmux"))
    }
}
