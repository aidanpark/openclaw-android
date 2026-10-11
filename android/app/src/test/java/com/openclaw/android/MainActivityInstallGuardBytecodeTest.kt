package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Checks the compiled MainActivity, not the source text: onCreate must ask SetupGuard.isRunning so
 * an Activity recreated mid-install does not take the installed-state branch (terminal start,
 * script update, www sync). Same class-file reading approach as EventBridgeCompiledShapeTest.
 */
class MainActivityInstallGuardBytecodeTest {
    @Test
    fun `MainActivity onCreate asks SetupGuard whether an install is running`() {
        val calls = invokedMethods("MainActivity.class", "onCreate")
        assertTrue(
            calls.any { it.owner == "com/openclaw/android/SetupGuard" && it.name.startsWith("isRunning") },
            "onCreate calls: $calls",
        )
    }

    @Test
    fun `MainActivity onCreate still asks BootstrapManager whether it is installed`() {
        val calls = invokedMethods("MainActivity.class", "onCreate")
        assertTrue(
            calls.any { it.owner == "com/openclaw/android/BootstrapManager" && it.name == "isInstalled" },
            "onCreate calls: $calls",
        )
    }

    @Test
    fun `MainActivity onDestroy stops the events, takes the WebView out, removes the bridge, then destroys`() {
        val calls = invokedMethods("MainActivity.class", "onDestroy")
        val steps =
            listOf(
                "detach" to calls.indexOfFirst { it.owner.contains("EventBridge") && it.name == "detach" },
                "removeView" to calls.indexOfFirst { it.owner == "android/view/ViewGroup" && it.name == "removeView" },
                "removeJavascriptInterface" to indexOf(calls, "removeJavascriptInterface"),
                "destroy" to indexOf(calls, "destroy"),
            )
        steps.forEach { (name, at) -> assertTrue(at >= 0, "onDestroy does not call $name: $calls") }
        // A WebView destroyed while still attached logs an error, and the page must not be able to call
        // the bridge between the steps
        steps.zipWithNext().forEach { (before, after) ->
            assertTrue(before.second < after.second, "${before.first} must come before ${after.first}: $calls")
        }
    }

    private fun indexOf(
        calls: List<ClassFileCalls.Ref>,
        name: String,
    ) = calls.indexOfFirst { it.owner == "android/webkit/WebView" && it.name == name }

    private fun invokedMethods(
        classFile: String,
        method: String,
    ): List<ClassFileCalls.Ref> {
        assertTrue(classFile == "MainActivity.class", "unexpected class $classFile")
        return ClassFileCalls.invoked(MainActivity::class.java, method)
    }
}
