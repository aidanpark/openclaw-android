package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class EnvironmentBuilderTest {
    private lateinit var env: Map<String, String>
    private val filesDir = File("/data/data/com.openclaw.android/files")

    @BeforeEach
    fun setup() {
        env = EnvironmentBuilder.build(filesDir)
    }

    @Test
    fun `PREFIX points to usr directory`() {
        assertEquals("${filesDir.absolutePath}/usr", env["PREFIX"])
    }

    @Test
    fun `HOME points to home directory`() {
        assertEquals("${filesDir.absolutePath}/home", env["HOME"])
    }

    @Test
    fun `TMPDIR points to tmp directory`() {
        assertEquals("${filesDir.absolutePath}/tmp", env["TMPDIR"])
    }

    @Test
    fun `PATH contains node bin and prefix bin`() {
        val path = env["PATH"]!!
        assertTrue(path.contains(".openclaw-android/node/bin"))
        assertTrue(path.contains("/usr/bin"))
    }

    @Test
    fun `LD_LIBRARY_PATH is set`() {
        assertNotNull(env["LD_LIBRARY_PATH"])
        assertTrue(env["LD_LIBRARY_PATH"]!!.contains("/usr/lib"))
    }

    @Test
    fun `APT_CONFIG points to apt conf`() {
        assertTrue(env["APT_CONFIG"]!!.endsWith("/etc/apt/apt.conf"))
    }

    @Test
    fun `GIT_CONFIG_NOSYSTEM is set to 1`() {
        assertEquals("1", env["GIT_CONFIG_NOSYSTEM"])
    }

    @Test
    fun `LANG is en_US UTF-8`() {
        assertEquals("en_US.UTF-8", env["LANG"])
    }

    @Test
    fun `TERM is xterm-256color`() {
        assertEquals("xterm-256color", env["TERM"])
    }

    @Test
    fun `OA_GLIBC is set`() {
        assertEquals("1", env["OA_GLIBC"])
    }

    @Test
    fun `OpenClaw does not update itself from a process the app starts`() {
        assertEquals("1", env["OPENCLAW_NO_AUTO_UPDATE"])
    }
}
