package com.openclaw.android

import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Integrity helpers for downloaded archives: SHA-256 comparison and zip-slip-safe paths.
 * The expected digest is pinned in the APK (BuildConfig) — it is the trust root, never a
 * value fetched from the same place as the archive.
 */
internal object ArtifactSecurity {
    private val sha256Pattern = Regex("^[0-9a-f]{64}$")
    private const val HEX_DIGITS = "0123456789abcdef"
    private const val NIBBLE_BITS = 4
    private const val NIBBLE_MASK = 0x0f
    private const val BYTE_MASK = 0xff

    /** Normalize a SHA-256 hex digest to lowercase; reject anything that is not 64 hex chars. */
    fun requireSha256(
        sha256: String?,
        label: String,
    ): String {
        val normalized = sha256?.trim()?.lowercase(Locale.US)
        require(!normalized.isNullOrEmpty()) { "$label SHA-256 is required" }
        require(sha256Pattern.matches(normalized)) { "$label SHA-256 must be a 64-character hex digest" }
        return normalized
    }

    fun toHex(bytes: ByteArray): String {
        val hex = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and BYTE_MASK
            hex.append(HEX_DIGITS[value ushr NIBBLE_BITS]).append(HEX_DIGITS[value and NIBBLE_MASK])
        }
        return hex.toString()
    }

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return toHex(digest.digest())
    }

    /**
     * Resolve a zip entry name under [rootDir], refusing anything that would land outside it.
     *
     * Rejects empty, absolute, backslash, NUL and `..` names (and names that reduce to nothing after
     * dropping `.` segments), then checks that the entry's parent
     * directory — with symlinks resolved — is still inside [rootDir]. The final component is not
     * followed, so an existing symlink at the leaf can be replaced (the caller deletes it before
     * writing) rather than written through.
     */
    fun resolveInsideDirectory(
        rootDir: File,
        entryName: String,
    ): File {
        require(entryName.isNotEmpty()) { "Zip entry name is empty" }
        require(!entryName.startsWith("/") && !entryName.contains('\\') && !entryName.contains('\u0000')) {
            "Unsafe zip entry: $entryName"
        }
        val segments = entryName.split('/')
        require(segments.none { it == ".." }) { "Unsafe zip entry: $entryName" }
        // Termux's SYMLINKS.txt writes every link path as "./etc/..."; "." segments are harmless
        // once dropped, but a name that is nothing but "." / "" would be the root itself.
        val normalized = segments.filter { it.isNotEmpty() && it != "." }.joinToString("/")
        require(normalized.isNotEmpty()) { "Unsafe zip entry: $entryName" }

        val root = rootDir.canonicalFile
        val joined = File(root, normalized)
        val leafName = joined.name
        val parent = (joined.parentFile ?: root).canonicalFile
        require(parent.path == root.path || parent.path.startsWith(root.path + File.separator)) {
            "Unsafe zip entry: $entryName"
        }
        return File(parent, leafName)
    }
}
