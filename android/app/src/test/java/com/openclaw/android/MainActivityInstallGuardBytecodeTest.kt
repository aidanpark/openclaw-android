package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.DataInputStream

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

    private data class MethodRef(
        val owner: String,
        val name: String,
    )

    /**
     * Minimal class-file reader: resolves every invoke* instruction in [method]'s bytecode to
     * owner.name. Operands are scanned byte-wise, so a stray operand byte could in theory alias an
     * invoke opcode; the asserted references are specific enough that this does not matter.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "MagicNumber")
    private fun invokedMethods(
        classFile: String,
        method: String,
    ): List<MethodRef> {
        val stream = MainActivity::class.java.getResourceAsStream(classFile)
        assertTrue(stream != null, "$classFile not on the test classpath")
        DataInputStream(stream!!.buffered()).use { input ->
            assertEquals(0xCAFEBABE.toInt(), input.readInt())
            input.readUnsignedShort()
            input.readUnsignedShort()
            val count = input.readUnsignedShort()
            val utf8 = arrayOfNulls<String>(count)
            val classIdx = IntArray(count)
            val refClass = IntArray(count)
            val refNat = IntArray(count)
            val natName = IntArray(count)
            var i = 1
            while (i < count) {
                when (input.readUnsignedByte()) {
                    1 -> utf8[i] = input.readUTF()
                    7 -> classIdx[i] = input.readUnsignedShort()
                    8, 16, 19, 20 -> input.readUnsignedShort()
                    9, 10, 11 -> {
                        refClass[i] = input.readUnsignedShort()
                        refNat[i] = input.readUnsignedShort()
                    }
                    12 -> {
                        natName[i] = input.readUnsignedShort()
                        input.readUnsignedShort()
                    }
                    3, 4, 17, 18 -> input.readInt()
                    5, 6 -> {
                        input.readLong()
                        i++
                    }
                    15 -> {
                        input.readUnsignedByte()
                        input.readUnsignedShort()
                    }
                    else -> error("unknown constant pool tag at $i")
                }
                i++
            }
            input.readUnsignedShort()
            input.readUnsignedShort()
            input.readUnsignedShort()
            repeat(input.readUnsignedShort()) { input.readUnsignedShort() }

            fun skipAttributes() =
                repeat(input.readUnsignedShort()) {
                    input.readUnsignedShort()
                    input.skipNBytes(input.readInt().toLong())
                }
            repeat(input.readUnsignedShort()) {
                input.skipNBytes(6)
                skipAttributes()
            }
            val result = mutableListOf<MethodRef>()
            var found = false
            repeat(input.readUnsignedShort()) {
                input.readUnsignedShort()
                val name = utf8[input.readUnsignedShort()]
                input.readUnsignedShort()
                repeat(input.readUnsignedShort()) {
                    val attrName = utf8[input.readUnsignedShort()]
                    val len = input.readInt()
                    val bytes = ByteArray(len).also { input.readFully(it) }
                    if (name == method && attrName == "Code") {
                        found = true
                        val codeLen =
                            ((bytes[4].toInt() and 0xFF) shl 24) or ((bytes[5].toInt() and 0xFF) shl 16) or
                                ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
                        val code = bytes.copyOfRange(8, 8 + codeLen)
                        for (p in 0 until code.size - 2) {
                            val op = code[p].toInt() and 0xFF
                            if (op in 0xB6..0xB9) {
                                val idx = ((code[p + 1].toInt() and 0xFF) shl 8) or (code[p + 2].toInt() and 0xFF)
                                val isRef = idx in 1 until count && refClass[idx] != 0
                                val owner = if (isRef) utf8[classIdx[refClass[idx]]] else null
                                val ref = if (owner != null) utf8[natName[refNat[idx]]] else null
                                if (owner != null && ref != null) result.add(MethodRef(owner, ref))
                            }
                        }
                    }
                }
            }
            assertTrue(found, "$method not found in $classFile")
            return result
        }
    }
}
