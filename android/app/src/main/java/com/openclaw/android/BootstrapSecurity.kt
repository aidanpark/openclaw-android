package com.openclaw.android

import java.io.File
import java.security.MessageDigest
import java.util.Locale

internal object BootstrapSecurity {
    private val sha256Pattern = Regex("^[A-Fa-f0-9]{64}$")

    fun requireVersion(version: String?): String {
        val normalized = version?.trim()
        require(!normalized.isNullOrEmpty()) { "Bootstrap version is required" }
        return normalized
    }

    fun requireSha256(sha256: String?): String {
        val normalized = sha256?.trim()
        require(!normalized.isNullOrEmpty()) { "Bootstrap SHA-256 is required" }
        require(sha256Pattern.matches(normalized)) {
            "Bootstrap SHA-256 must be a 64-character hex digest"
        }
        return normalized.lowercase(Locale.US)
    }

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHexString()
    }

    fun resolveInsideDirectory(
        rootDir: File,
        entryName: String,
    ): File {
        require(entryName.isNotBlank()) { "Bootstrap zip entry name is empty" }
        val root = rootDir.canonicalFile
        val target = File(root, entryName).canonicalFile
        require(target.path.startsWith(root.path + File.separator)) {
            "Unsafe bootstrap zip entry: $entryName"
        }
        return target
    }

    private fun ByteArray.toHexString(): String {
        val hex = CharArray(size * HEX_CHARS_PER_BYTE)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and BYTE_MASK
            hex[index * HEX_CHARS_PER_BYTE] = HEX_CHARS[value ushr NIBBLE_BITS]
            hex[index * HEX_CHARS_PER_BYTE + 1] = HEX_CHARS[value and NIBBLE_MASK]
        }
        return String(hex)
    }

    private const val BYTE_MASK = 0xff
    private const val NIBBLE_MASK = 0x0f
    private const val NIBBLE_BITS = 4
    private const val HEX_CHARS_PER_BYTE = 2
    private val HEX_CHARS = "0123456789abcdef".toCharArray()
}
