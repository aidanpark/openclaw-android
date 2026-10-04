package com.openclaw.android

import android.util.Log
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.security.MessageDigest

class BootstrapDownloaderTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var cacheDir: File
    private lateinit var srcDir: File
    private var server: HttpServer? = null
    private val errorLogs = mutableListOf<String>()

    private val goodBytes = "real bootstrap archive bytes".toByteArray()
    private val goodSha = sha(goodBytes)
    private val badBytes = "tampered archive".toByteArray()

    @BeforeEach
    fun setup() {
        cacheDir = File(tempDir, "cache").apply { mkdirs() }
        srcDir = File(tempDir, "src").apply { mkdirs() }
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } answers {
            errorLogs.add(secondArg())
            0
        }
    }

    @AfterEach
    fun teardown() {
        server?.stop(0)
        unmockkStatic(Log::class)
    }

    private fun sha(bytes: ByteArray) = ArtifactSecurity.toHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun fileUrl(
        name: String,
        bytes: ByteArray,
    ): String =
        File(srcDir, name)
            .apply { writeBytes(bytes) }
            .toURI()
            .toURL()
            .toString()

    private fun missingFileUrl(): String = File(srcDir, "does-not-exist.zip").toURI().toURL().toString()

    private fun downloader(
        vararg urls: String,
        digestUrl: String? = null,
    ) = BootstrapDownloader(cacheDir, urls.toList(), goodSha, upstreamDigestUrl = digestUrl)

    private fun cachedArchive() = File(cacheDir, "bootstrap-aarch64.zip")

    /** Starts a local server; each path maps to (status, body). */
    private fun startServer(routes: Map<String, Pair<Int, ByteArray>>): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        for ((path, response) in routes) {
            s.createContext(path) { ex ->
                val (status, body) = response
                ex.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    private fun failKind(d: BootstrapDownloader): BootstrapDownloadException.Kind =
        assertThrows(BootstrapDownloadException::class.java) { d.download() }.kind

    @Test
    fun `returns the archive when the digest matches and leaves it in the cache`() {
        val file = downloader(fileUrl("good.zip", goodBytes)).download()
        assertEquals(cachedArchive().canonicalPath, file.canonicalPath)
        assertTrue(file.exists())
        assertArrayEquals(goodBytes, file.readBytes())
    }

    @Test
    fun `accepts an uppercase pinned digest`() {
        val d = BootstrapDownloader(cacheDir, listOf(fileUrl("good.zip", goodBytes)), goodSha.uppercase())
        assertArrayEquals(goodBytes, d.download().readBytes())
    }

    @Test
    fun `digest mismatch throws HASH_MISMATCH and deletes the cached file`() {
        val d = downloader(fileUrl("bad.zip", badBytes))
        val e = assertThrows(BootstrapDownloadException::class.java) { d.download() }
        assertEquals(BootstrapDownloadException.Kind.HASH_MISMATCH, e.kind)
        assertFalse(cachedArchive().exists())
    }

    @Test
    fun `a stale cached archive is removed before a failing download`() {
        cachedArchive().writeBytes(goodBytes)
        failKind(downloader(missingFileUrl()))
        assertFalse(cachedArchive().exists())
    }

    @Test
    fun `missing file URL is reported as NETWORK`() {
        assertEquals(BootstrapDownloadException.Kind.NETWORK, failKind(downloader(missingFileUrl())))
        assertFalse(cachedArchive().exists())
    }

    @Test
    fun `falls back to the second candidate when the first is unreachable`() {
        val file = downloader(missingFileUrl(), fileUrl("good.zip", goodBytes)).download()
        assertArrayEquals(goodBytes, file.readBytes())
    }

    @Test
    fun `falls back to the second candidate when the first has the wrong digest`() {
        val file = downloader(fileUrl("bad.zip", badBytes), fileUrl("good.zip", goodBytes)).download()
        assertArrayEquals(goodBytes, file.readBytes())
        assertTrue(cachedArchive().exists())
    }

    @Test
    fun `stops at the first good candidate`() {
        val file = downloader(fileUrl("good.zip", goodBytes), fileUrl("bad.zip", badBytes)).download()
        assertArrayEquals(goodBytes, file.readBytes())
    }

    @Test
    fun `when all fail HASH_MISMATCH outranks UPSTREAM_MISSING and NETWORK`() {
        val base = startServer(mapOf("/gone" to (404 to ByteArray(0))))
        val d = downloader(missingFileUrl(), "$base/gone", fileUrl("bad.zip", badBytes))
        assertEquals(BootstrapDownloadException.Kind.HASH_MISMATCH, failKind(d))
        assertFalse(cachedArchive().exists())
    }

    @Test
    fun `when all fail UPSTREAM_MISSING outranks NETWORK regardless of order`() {
        val base = startServer(mapOf("/gone" to (404 to ByteArray(0))))
        assertEquals(
            BootstrapDownloadException.Kind.UPSTREAM_MISSING,
            failKind(downloader("$base/gone", missingFileUrl())),
        )
        assertEquals(
            BootstrapDownloadException.Kind.UPSTREAM_MISSING,
            failKind(downloader(missingFileUrl(), "$base/gone")),
        )
    }

    @Test
    fun `when all candidates are unreachable the kind is NETWORK`() {
        assertEquals(
            BootstrapDownloadException.Kind.NETWORK,
            failKind(downloader(missingFileUrl(), missingFileUrl())),
        )
    }

    @Test
    fun `final exception carries the plain user message for its kind`() {
        val d = downloader(fileUrl("bad.zip", badBytes))
        val e = assertThrows(BootstrapDownloadException::class.java) { d.download() }
        assertTrue(e.message!!.contains("checksum"), e.message)
        assertFalse(e.message!!.contains("file:"), "user message must not leak the URL")
    }

    @Test
    fun `malformed pinned digest is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            BootstrapDownloader(cacheDir, listOf("https://example.invalid/x"), "not-a-digest")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BootstrapDownloader(cacheDir, listOf("https://example.invalid/x"), "")
        }
    }

    @Test
    fun `empty URL list is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { downloader().download() }
    }

    @Test
    fun `HTTP 404 403 and 410 are reported as UPSTREAM_MISSING`() {
        val base =
            startServer(
                mapOf(
                    "/404" to (404 to "nope".toByteArray()),
                    "/403" to (403 to "nope".toByteArray()),
                    "/410" to (410 to "nope".toByteArray()),
                ),
            )
        for (code in listOf("404", "403", "410")) {
            assertEquals(
                BootstrapDownloadException.Kind.UPSTREAM_MISSING,
                failKind(downloader("$base/$code")),
                "HTTP $code",
            )
            assertFalse(cachedArchive().exists())
        }
    }

    @Test
    fun `HTTP 500 is reported as NETWORK`() {
        val base = startServer(mapOf("/err" to (500 to "boom".toByteArray())))
        assertEquals(BootstrapDownloadException.Kind.NETWORK, failKind(downloader("$base/err")))
    }

    @Test
    fun `HTTP 200 with matching bytes succeeds`() {
        val base = startServer(mapOf("/ok" to (200 to goodBytes)))
        assertArrayEquals(goodBytes, downloader("$base/ok").download().readBytes())
    }

    @Test
    fun `HTTP 200 with wrong bytes is HASH_MISMATCH`() {
        val base = startServer(mapOf("/ok" to (200 to badBytes)))
        assertEquals(BootstrapDownloadException.Kind.HASH_MISMATCH, failKind(downloader("$base/ok")))
    }

    @Test
    fun `mirror fallback after an upstream 404`() {
        val base = startServer(mapOf("/gone" to (404 to ByteArray(0)), "/mirror" to (200 to goodBytes)))
        assertArrayEquals(goodBytes, downloader("$base/gone", "$base/mirror").download().readBytes())
    }

    @Test
    fun `archive larger than the size limit is refused and not left behind`() {
        val huge = File(srcDir, "huge.zip")
        RandomAccessFile(huge, "rw").use { it.setLength(200L * 1024 * 1024 + 1) }
        val kind = failKind(downloader(huge.toURI().toURL().toString()))
        assertEquals(BootstrapDownloadException.Kind.LOCAL_IO, kind)
        assertFalse(cachedArchive().exists())
    }

    @Test
    fun `mismatch diagnosis reports UPSTREAM_REPLACED when the published digest equals the received bytes`() {
        val digestJson =
            """{"assets":[{"name":"bootstrap-aarch64.zip","digest":"sha256:${sha(badBytes)}"}]}"""
        val digestUrl = fileUrl("release.json", digestJson.toByteArray())
        failKind(downloader(fileUrl("bad.zip", badBytes), digestUrl = digestUrl))
        assertTrue(errorLogs.any { it.contains("[UPSTREAM_REPLACED]") }, errorLogs.toString())
    }

    @Test
    fun `mismatch diagnosis reports PATH_TAMPERED_OR_CORRUPT when the published digest equals the pin`() {
        val digestJson = """{"assets":[{"name":"bootstrap-aarch64.zip","digest":"sha256:$goodSha"}]}"""
        val digestUrl = fileUrl("release.json", digestJson.toByteArray())
        failKind(downloader(fileUrl("bad.zip", badBytes), digestUrl = digestUrl))
        assertTrue(errorLogs.any { it.contains("[PATH_TAMPERED_OR_CORRUPT]") }, errorLogs.toString())
    }

    @Test
    fun `diagnosis never turns a mismatch into success even if the digest endpoint vouches for the bytes`() {
        val digestJson =
            """{"assets":[{"name":"bootstrap-aarch64.zip","digest":"sha256:${sha(badBytes)}"}]}"""
        val digestUrl = fileUrl("release.json", digestJson.toByteArray())
        assertEquals(
            BootstrapDownloadException.Kind.HASH_MISMATCH,
            failKind(downloader(fileUrl("bad.zip", badBytes), digestUrl = digestUrl)),
        )
        assertFalse(cachedArchive().exists())
    }

    @Test
    fun `broken diagnosis endpoint yields UNDETERMINED without masking the mismatch`() {
        val d = downloader(fileUrl("bad.zip", badBytes), digestUrl = missingFileUrl())
        assertEquals(BootstrapDownloadException.Kind.HASH_MISMATCH, failKind(d))
        assertTrue(errorLogs.any { it.contains("[UNDETERMINED]") }, errorLogs.toString())
    }
}
