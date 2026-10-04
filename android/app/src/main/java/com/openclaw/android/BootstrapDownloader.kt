package com.openclaw.android

import com.google.gson.Gson
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest

/** A failure on this device (disk, size limit), not on the network. */
private class LocalIoException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Why a bootstrap download was refused. Shown to the user, so the messages stay plain. */
internal class BootstrapDownloadException(
    val kind: Kind,
    message: String,
) : IOException(message) {
    enum class Kind { NETWORK, UPSTREAM_MISSING, HASH_MISMATCH, LOCAL_IO }
}

/**
 * Downloads the bootstrap archive from the first candidate URL whose bytes match the
 * APK-pinned SHA-256. Candidates are the upstream URL plus mirrors; the hash makes the origin
 * irrelevant, so a mirror can never serve something the upstream would not. On any failure
 * nothing is left behind and the caller has not touched the installed prefix yet.
 */
internal class BootstrapDownloader(
    private val cacheDir: File,
    private val urls: List<String>,
    expectedSha256: String,
    private val upstreamDigestUrl: String? = null,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
) {
    companion object {
        private const val TAG = "BootstrapDownloader"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 10_000
        private const val DIAGNOSTIC_TIMEOUT_MS = 5_000
        private const val MAX_ARCHIVE_BYTES = 200L * 1024 * 1024
        private const val CANDIDATE_TOTAL_TIMEOUT_MS = 10L * 60 * 1000
        private const val ARCHIVE_NAME = "bootstrap-aarch64.zip"
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_GONE = 410
    }

    private val expected = ArtifactSecurity.requireSha256(expectedSha256, "Bootstrap")

    /** Returns the verified archive file; the caller deletes it after extraction. */
    fun download(): File {
        require(urls.isNotEmpty()) { "No bootstrap URL configured" }
        val failures = mutableListOf<BootstrapDownloadException>()
        for (url in urls) {
            try {
                return fetchVerified(url)
            } catch (e: BootstrapDownloadException) {
                AppLogger.w(TAG, "Bootstrap candidate failed: ${e.kind} — ${e.message}")
                failures.add(e)
            } catch (e: LocalIoException) {
                AppLogger.w(TAG, "Bootstrap candidate failed locally: $url", e)
                failures.add(BootstrapDownloadException(BootstrapDownloadException.Kind.LOCAL_IO, e.message ?: url))
            } catch (e: IOException) {
                AppLogger.w(TAG, "Bootstrap candidate unreachable: $url", e)
                failures.add(BootstrapDownloadException(BootstrapDownloadException.Kind.NETWORK, e.message ?: url))
            }
        }
        // Report the most serious cause: a wrong digest beats a missing file beats a dead network.
        val worst =
            failures.firstOrNull { it.kind == BootstrapDownloadException.Kind.HASH_MISMATCH }
                ?: failures.firstOrNull { it.kind == BootstrapDownloadException.Kind.UPSTREAM_MISSING }
                ?: failures.firstOrNull { it.kind == BootstrapDownloadException.Kind.LOCAL_IO }
                ?: failures.first()
        throw BootstrapDownloadException(worst.kind, userMessage(worst.kind))
    }

    private fun userMessage(kind: BootstrapDownloadException.Kind): String =
        when (kind) {
            BootstrapDownloadException.Kind.NETWORK ->
                "Could not reach the bootstrap download servers. Check the connection and try again."
            BootstrapDownloadException.Kind.UPSTREAM_MISSING ->
                "The bootstrap file is no longer available at its download address. Update the app and try again."
            BootstrapDownloadException.Kind.LOCAL_IO ->
                "The bootstrap download could not be saved (storage full or unexpectedly large). " +
                    "Free some space and try again."
            BootstrapDownloadException.Kind.HASH_MISMATCH ->
                "The downloaded bootstrap did not match the expected checksum (tampered or replaced upstream). " +
                    "Installation was stopped."
        }

    private fun fetchVerified(url: String): File {
        val archive = File(cacheDir, ARCHIVE_NAME)
        archive.delete()
        var verified = false
        var conn: java.net.URLConnection? = null
        try {
            conn = URL(url).openConnection()
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            val status = (conn as? HttpURLConnection)?.responseCode ?: 0
            if (status == HTTP_FORBIDDEN || status == HTTP_NOT_FOUND || status == HTTP_GONE) {
                throw BootstrapDownloadException(
                    BootstrapDownloadException.Kind.UPSTREAM_MISSING,
                    "HTTP $status for $url",
                )
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val total = copyWithDigest(conn.getInputStream(), archive, digest)
            val actual = ArtifactSecurity.toHex(digest.digest())
            if (actual != expected) {
                logMismatch(url, conn, status, total, actual)
                throw BootstrapDownloadException(
                    BootstrapDownloadException.Kind.HASH_MISMATCH,
                    "SHA-256 mismatch for $url",
                )
            }
            verified = true
            return archive
        } finally {
            (conn as? HttpURLConnection)?.disconnect()
            if (!verified) archive.delete()
        }
    }

    private fun copyWithDigest(
        input: java.io.InputStream,
        target: File,
        digest: MessageDigest,
    ): Long {
        val counted = DigestInputStream(input, digest)
        val written =
            counted.use { stream ->
                target.outputStream().use { out -> copyLimited(stream, out) }
            }
        return written
    }

    private fun copyLimited(
        input: java.io.InputStream,
        out: java.io.OutputStream,
    ): Long {
        var total = 0L
        val deadline = System.currentTimeMillis() + CANDIDATE_TOTAL_TIMEOUT_MS
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var read = input.read(buffer)
        while (read >= 0) {
            total += read
            if (total > MAX_ARCHIVE_BYTES) throw LocalIoException("Bootstrap download exceeds size limit")
            if (System.currentTimeMillis() > deadline) throw IOException("Bootstrap download too slow")
            out.write(buffer, 0, read)
            read = input.read(buffer)
        }
        return total
    }

    private fun logMismatch(
        url: String,
        conn: java.net.URLConnection,
        status: Int,
        size: Long,
        actual: String,
    ) {
        val verdict = diagnose(actual)
        AppLogger.e(
            TAG,
            "Bootstrap hash mismatch [$verdict]: expected=$expected actual=$actual url=$url " +
                "finalUrl=${conn.url} http=$status contentLength=${conn.contentLengthLong} size=$size",
        )
    }

    /**
     * Diagnostic only — never used to accept an archive. Compares what we got with the digest
     * GitHub currently publishes for the upstream asset: if they agree, the pinned value is stale
     * (upstream replaced the file); if GitHub still publishes the pinned digest, the bytes were
     * altered on the way (mirror, proxy, corruption).
     */
    private fun diagnose(actual: String): String {
        val apiUrl = upstreamDigestUrl ?: return "UNDETERMINED"
        return try {
            val conn = URL(apiUrl).openConnection()
            conn.connectTimeout = DIAGNOSTIC_TIMEOUT_MS
            conn.readTimeout = DIAGNOSTIC_TIMEOUT_MS
            val body = conn.getInputStream().bufferedReader().use { it.readText() }
            val release = Gson().fromJson(body, Map::class.java)
            val assets = release["assets"] as? List<*> ?: return "UNDETERMINED"
            val published =
                assets
                    .filterIsInstance<Map<*, *>>()
                    .firstOrNull { it["name"] == ARCHIVE_NAME }
                    ?.get("digest") as? String
            val publishedHex = published?.removePrefix("sha256:")?.lowercase()
            when {
                publishedHex == null -> "UNDETERMINED"
                publishedHex == actual -> "UPSTREAM_REPLACED"
                publishedHex == expected -> "PATH_TAMPERED_OR_CORRUPT"
                else -> "UNDETERMINED"
            }
        } catch (_: Exception) {
            "UNDETERMINED"
        }
    }
}
