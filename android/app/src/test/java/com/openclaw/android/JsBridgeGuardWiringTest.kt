package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * Checks the compiled classes. A process-wide guard kept as an instance field (or an instance
 * field that shadows a companion one) is invisible to source-text tests but shows up here.
 */
class JsBridgeGuardWiringTest {
    private val guardTypes =
        setOf(
            java.util.concurrent.atomic.AtomicBoolean::class.java,
            kotlinx.coroutines.sync.Semaphore::class.java,
        )

    private val checkedClasses = listOf(JsBridge::class.java, BootstrapManager::class.java, MainActivity::class.java)

    @Test
    fun `no checked class keeps a guard in an instance field`() {
        for (cls in checkedClasses) {
            val offenders = instanceGuardFields(cls)
            assertTrue(offenders.isEmpty(), "${cls.simpleName} has instance guard fields: $offenders")
        }
    }

    @Test
    fun `no checked class declares two fields with the same name`() {
        for (cls in checkedClasses) {
            val duplicates = shadowedFieldNames(cls)
            assertTrue(duplicates.isEmpty(), "${cls.simpleName} declares shadowing fields: $duplicates")
        }
    }

    @Test
    fun `the detectors flag the instance-shadows-companion pattern that broke the release`() {
        // Kotlin keeps the companion field static as `setupRunning` and renames the instance one
        // to `setupRunning$1`; both detectors must see through that or they guard nothing.
        val fixture = ShadowedGuardFixture::class.java
        assertTrue(
            fixture.declaredFields.any {
                it.name == "setupRunning\$1"
            },
            fixture.declaredFields.map { it.name }.toString(),
        )
        assertTrue(instanceGuardFields(fixture).isNotEmpty())
        assertTrue(shadowedFieldNames(fixture).contains("setupRunning"))
        assertTrue(ShadowedGuardFixture().instanceRunning() == ShadowedGuardFixture.companionRunning())
    }

    @Test
    fun `compiled JsBridge takes the setup guard itself before launching`() {
        // tryStart() must run on the caller thread in JsBridge.startSetup, not inside the coroutine
        val text = classText("JsBridge.class")
        assertTrue(text.contains("com/openclaw/android/SetupGuard"), "JsBridge.class does not use SetupGuard")
    }

    @Test
    fun `compiled JsBridge and its coroutine bodies reference SetupGuard and ProbeLimiter`() {
        // withPermit is inline, so the ProbeLimiter reference lands in the runProbeAsync lambda class
        val texts = jsBridgeClassTexts()
        assertTrue(texts.keys.any { it.startsWith("JsBridge$") }, "no JsBridge inner classes found: ${texts.keys}")
        val all = texts.values.joinToString("\n")
        assertTrue(all.contains("com/openclaw/android/SetupGuard"), "JsBridge does not use SetupGuard")
        assertTrue(all.contains("com/openclaw/android/ProbeLimiter"), "JsBridge does not use ProbeLimiter")
    }

    @Test
    fun `compiled JsBridge classes never name setupRunning or probeLimiter`() {
        for ((name, text) in jsBridgeClassTexts()) {
            assertFalse(text.contains("setupRunning"), "$name still names setupRunning")
            assertFalse(text.contains("probeLimiter"), "$name still names probeLimiter")
        }
    }

    @Test
    fun `the guards live in objects whose state is static`() {
        val running = SetupGuard::class.java.getDeclaredField("running")
        assertTrue(Modifier.isStatic(running.modifiers), "SetupGuard.running is not static")
        val semaphore = ProbeLimiter::class.java.getDeclaredField("semaphore")
        assertTrue(Modifier.isStatic(semaphore.modifiers), "ProbeLimiter.semaphore is not static")
    }

    private fun instanceGuardFields(cls: Class<*>): List<String> =
        cls.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) }
            .filter { field -> guardTypes.any { it.isAssignableFrom(field.type) } }
            .map { "${it.name}: ${it.type.name}" }

    /** Field base names declared more than once, counting Kotlin's `name$1` clash renaming. */
    private fun shadowedFieldNames(cls: Class<*>): Set<String> =
        cls.declaredFields
            .filter { it.name != "Companion" && !it.isSynthetic && !it.name.startsWith("$") }
            .groupBy { it.name.replace(CLASH_SUFFIX, "") }
            .filterValues { it.size > 1 }
            .keys

    /** Class-file constant pool keeps names as (modified) UTF-8; ISO-8859-1 maps bytes 1:1. */
    private fun classText(resource: String): String {
        val stream = JsBridge::class.java.getResourceAsStream(resource)
        assertNotNull(stream, "$resource not found on the test classpath")
        return stream!!.use { String(it.readBytes(), Charsets.ISO_8859_1) }
    }

    /** JsBridge.class plus every JsBridge$*.class next to it (lambdas, coroutine bodies, companion). */
    private fun jsBridgeClassTexts(): Map<String, String> {
        val url = JsBridge::class.java.getResource("JsBridge.class")
        assertNotNull(url, "JsBridge.class not found on the test classpath")
        val names =
            when (url!!.protocol) {
                "file" ->
                    java.io
                        .File(url.toURI())
                        .parentFile
                        ?.list()
                        .orEmpty()
                        .filter { it == "JsBridge.class" || (it.startsWith("JsBridge$") && it.endsWith(".class")) }
                "jar" -> {
                    val conn = url.openConnection() as java.net.JarURLConnection
                    val prefix = conn.entryName.substringBeforeLast('/') + "/"
                    conn.jarFile
                        .entries()
                        .asSequence()
                        .map { it.name }
                        .filter { it.startsWith(prefix) }
                        .map { it.removePrefix(prefix) }
                        .filter { it == "JsBridge.class" || (it.startsWith("JsBridge$") && it.endsWith(".class")) }
                        .toList()
                }
                else -> error("unsupported class location: $url")
            }
        return names.associateWith { classText(it) }
    }

    private companion object {
        val CLASH_SUFFIX = Regex("""\$\d+$""")
    }
}

/** Reproduces the pre-fix JsBridge layout: an instance guard that shadows the companion guard. */
private class ShadowedGuardFixture {
    private val setupRunning =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    fun instanceRunning() = setupRunning.get()

    companion object {
        private val setupRunning =
            java.util.concurrent.atomic
                .AtomicBoolean(false)

        fun companionRunning() = setupRunning.get()
    }
}
