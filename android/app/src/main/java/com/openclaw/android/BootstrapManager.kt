package com.openclaw.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Manages Termux bootstrap download, extraction, and configuration.
 * Phase 0: extracts from assets. Phase 1+: downloads from network.
 * Based on AnyClaw BootstrapInstaller.kt pattern (§2.2.1).
 */
class BootstrapManager(
    private val context: Context,
) {
    companion object {
        private const val TAG = "BootstrapManager"
        private const val PROGRESS_PREPARING = 0.05f
        private const val PROGRESS_DOWNLOADING = 0.10f
        private const val PROGRESS_EXTRACTING = 0.30f
        private const val PROGRESS_CONFIGURING = 0.60f
        private const val ELF_MAGIC_SIZE = 4
        private val ELF_SIGNATURE = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
        private const val SYMLINK_SEPARATOR = "←"
        private const val SYMLINK_PARTS_COUNT = 2
        private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 5_000
        private const val DOWNLOAD_READ_TIMEOUT_MS = 10_000
        private const val DOWNLOAD_TOTAL_TIMEOUT_SEC = 12L
        private const val PREFS_NAME = "openclaw"
        private const val PREF_VERSION_CODE = "versionCode"
    }

    val prefixDir = File(context.filesDir, "usr")
    val homeDir = File(context.filesDir, "home")
    val tmpDir = File(context.filesDir, "tmp")
    val wwwDir = File(prefixDir, "share/openclaw-app/www")
    private val stagingDir = File(context.filesDir, "usr-staging")

    fun isInstalled(): Boolean = prefixDir.resolve("bin/sh").exists()

    fun needsPostSetup(): Boolean {
        val marker = File(homeDir, ".openclaw-android/.post-setup-done")
        return isInstalled() && !marker.exists()
    }

    val postSetupScript: File
        get() = File(homeDir, ".openclaw-android/post-setup.sh")

    data class SetupStatus(
        val bootstrapInstalled: Boolean,
        val runtimeInstalled: Boolean,
        val wwwInstalled: Boolean,
        val platformInstalled: Boolean,
    )

    fun getStatus(): SetupStatus =
        SetupStatus(
            bootstrapInstalled = isInstalled(),
            runtimeInstalled = prefixDir.resolve("bin/node").exists(),
            wwwInstalled = wwwDir.resolve("index.html").exists(),
            platformInstalled = File(homeDir, ".openclaw-android/.post-setup-done").exists(),
        )

    /**
     * Full setup flow. Reports progress via callback (0.0–1.0).
     */
    suspend fun startSetup(onProgress: (Float, String) -> Unit) =
        withContext(Dispatchers.IO) {
            // Step 1: Get a verified bootstrap archive. This comes first so a failed or refused
            // download leaves any installed prefix exactly as it was.
            onProgress(PROGRESS_PREPARING, "Preparing bootstrap...")
            val bootstrapArchive = getBootstrapArchive(onProgress)

            // Clean up any incomplete previous attempt
            if (stagingDir.exists()) {
                AppLogger.i(TAG, "Removing incomplete staging dir from previous attempt")
                stagingDir.deleteRecursively()
            }
            // Step 2: Extract bootstrap
            onProgress(PROGRESS_EXTRACTING, "Extracting bootstrap...")
            bootstrapArchive.use { archive -> extractBootstrap(archive.inputStream) }

            // Step 3: Fix paths and configure
            onProgress(PROGRESS_CONFIGURING, "Configuring environment...")
            fixTermuxPaths(stagingDir)
            configureApt(stagingDir)

            // Step 4: Swap in the new prefix. The old one is only removed now that the new one has
            // been downloaded, verified, extracted and configured — a failure above leaves it intact.
            if (isInstalled()) {
                AppLogger.i(TAG, "Incomplete bootstrap detected, reinstalling...")
                prefixDir.deleteRecursively()
            }
            stagingDir.renameTo(prefixDir)
            setupDirectories()
            copyAssetScripts()
            syncWwwFromAssets()
            setupTermuxExec()
            // Without this the first relaunch looks like an APK upgrade (0 -> N)
            saveCurrentVersionCode()

            onProgress(1f, "Setup complete")
        }

    // --- Bootstrap source ---

    /** The archive to extract, plus the temp file to delete once it has been read. */
    private class BootstrapArchive(
        val inputStream: InputStream,
        private val temporaryFile: File? = null,
    ) : java.io.Closeable {
        override fun close() {
            inputStream.close()
            temporaryFile?.delete()
        }
    }

    private fun getBootstrapArchive(onProgress: (Float, String) -> Unit): BootstrapArchive {
        // Phase 0: an archive bundled in the APK is covered by the APK signature
        try {
            return BootstrapArchive(context.assets.open("bootstrap-aarch64.zip"))
        } catch (_: Exception) {
            // Phase 1: download from network
        }

        onProgress(PROGRESS_DOWNLOADING, "Downloading bootstrap...")
        // URL, mirrors and SHA-256 are all pinned in the APK; nothing remote can change them.
        val mirrors = BuildConfig.BOOTSTRAP_MIRROR_PREFIXES.split(',').filter { it.isNotBlank() }
        val archive =
            BootstrapDownloader(
                cacheDir = context.cacheDir,
                urls = listOf(BuildConfig.BOOTSTRAP_URL) + mirrors.map { it + BuildConfig.BOOTSTRAP_URL },
                expectedSha256 = BuildConfig.BOOTSTRAP_SHA256,
                upstreamDigestUrl = BuildConfig.BOOTSTRAP_DIGEST_API_URL,
            ).download()
        return BootstrapArchive(archive.inputStream(), archive)
    }

    // --- Extraction ---

    private fun extractBootstrap(inputStream: InputStream) {
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()

        ZipInputStream(inputStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                processZipEntry(zip, entry)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun processZipEntry(
        zip: ZipInputStream,
        entry: java.util.zip.ZipEntry,
    ) {
        if (entry.name == "SYMLINKS.txt") {
            processSymlinks(zip, stagingDir)
        } else if (!entry.isDirectory) {
            val file = ArtifactSecurity.resolveInsideDirectory(stagingDir, entry.name)
            file.parentFile?.mkdirs()
            file.delete() // never write through a symlink an earlier entry planted
            file.outputStream().use { out -> zip.copyTo(out) }
            markExecutableIfNeeded(file, entry.name)
        }
    }

    private fun markExecutableIfNeeded(
        file: File,
        name: String,
    ) {
        val knownExecutable =
            name.startsWith("bin/") ||
                name.startsWith("libexec/") ||
                name.startsWith("lib/apt/") ||
                name.startsWith("lib/bash/") ||
                name.endsWith(".so") ||
                name.contains(".so.")
        if (knownExecutable) {
            file.setExecutable(true)
        } else if (file.length() > ELF_MAGIC_SIZE && isElfBinary(file)) {
            file.setExecutable(true)
        }
    }

    private fun isElfBinary(file: File): Boolean =
        try {
            file.inputStream().use { fis ->
                val magic = ByteArray(ELF_MAGIC_SIZE)
                fis.read(magic) == ELF_MAGIC_SIZE &&
                    magic.contentEquals(ELF_SIGNATURE)
            }
        } catch (_: Exception) {
            false
        }

    /**
     * Process SYMLINKS.txt: each line is "target←linkpath".
     * Replace com.termux paths with our package name.
     */
    private fun processSymlinks(
        zip: ZipInputStream,
        targetDir: File,
    ) {
        val content = zip.bufferedReader().readText()
        val ourPackage = context.packageName
        content
            .lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split(SYMLINK_SEPARATOR)
                if (parts.size == SYMLINK_PARTS_COUNT) parts else null
            }.forEach { parts ->
                val symlinkTarget = parts[0].trim().replace("com.termux", ourPackage)
                val symlinkPath = parts[1].trim()
                val linkFile = ArtifactSecurity.resolveInsideDirectory(targetDir, symlinkPath)
                linkFile.parentFile?.mkdirs()
                try {
                    Os.symlink(symlinkTarget, linkFile.absolutePath)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed to create symlink: $symlinkPath -> $symlinkTarget", e)
                }
            }
    }

    // --- Path fixing (§2.2.2) ---

    private fun fixTermuxPaths(dir: File) {
        val ourPackage = context.packageName
        val oldPrefix = "/data/data/com.termux/files/usr"
        val newPrefix = prefixDir.absolutePath

        // Fix dpkg status database
        fixTextFile(dir.resolve("var/lib/dpkg/status"), oldPrefix, newPrefix)

        // Fix dpkg info files
        val dpkgInfoDir = dir.resolve("var/lib/dpkg/info")
        if (dpkgInfoDir.isDirectory) {
            dpkgInfoDir.listFiles()?.filter { it.name.endsWith(".list") }?.forEach { file ->
                fixTextFile(file, "com.termux", ourPackage)
            }
        }

        // Fix git scripts shebangs
        val gitCoreDir = dir.resolve("libexec/git-core")
        if (gitCoreDir.isDirectory) {
            gitCoreDir.listFiles()?.forEach { file ->
                if (file.isFile && !file.name.contains(".")) {
                    fixTextFile(file, oldPrefix, newPrefix)
                }
            }
        }
    }

    private fun fixTextFile(
        file: File,
        oldText: String,
        newText: String,
    ) {
        if (!file.exists() || !file.isFile) return
        try {
            val content = file.readText()
            if (content.contains(oldText)) {
                file.writeText(content.replace(oldText, newText))
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to fix paths in ${file.name}", e)
        }
    }

    // --- apt configuration (§2.2.3) ---

    private fun configureApt(dir: File) {
        val prefix = prefixDir.absolutePath
        val ourPackage = context.packageName

        // sources.list: HTTPS→HTTP downgrade + package name fix
        val sourcesList = dir.resolve("etc/apt/sources.list")
        if (sourcesList.exists()) {
            sourcesList.writeText(
                sourcesList
                    .readText()
                    .replace("https://", "http://")
                    .replace("com.termux", ourPackage),
            )
        }

        // apt.conf: full rewrite with correct paths
        val aptConf = dir.resolve("etc/apt/apt.conf")
        aptConf.parentFile?.mkdirs()
        // Create directories needed by apt and dpkg
        dir.resolve("etc/apt/apt.conf.d").mkdirs()
        dir.resolve("etc/apt/preferences.d").mkdirs()
        dir.resolve("etc/dpkg/dpkg.cfg.d").mkdirs()
        dir.resolve("var/cache/apt").mkdirs()
        dir.resolve("var/log/apt").mkdirs()
        aptConf.writeText(
            """
            Dir "/";
            Dir::State "$prefix/var/lib/apt/";
            Dir::State::status "$prefix/var/lib/dpkg/status";
            Dir::Cache "$prefix/var/cache/apt/";
            Dir::Log "$prefix/var/log/apt/";
            Dir::Etc "$prefix/etc/apt/";
            Dir::Etc::SourceList "$prefix/etc/apt/sources.list";
            Dir::Etc::SourceParts "";
            Dir::Bin::dpkg "$prefix/bin/dpkg";
            Dir::Bin::Methods "$prefix/lib/apt/methods/";
            Dir::Bin::apt-key "$prefix/bin/apt-key";
            Dpkg::Options:: "--force-configure-any";
            Dpkg::Options:: "--force-bad-path";
            Dpkg::Options:: "--instdir=$prefix";
            Dpkg::Options:: "--admindir=$prefix/var/lib/dpkg";
            Acquire::AllowInsecureRepositories "true";
            APT::Get::AllowUnauthenticated "true";
            """.trimIndent(),
        )
    }

    // --- Setup helpers ---

    private fun setupDirectories() {
        homeDir.mkdirs()
        tmpDir.mkdirs()
        wwwDir.mkdirs()
        File(homeDir, ".openclaw-android/patches").mkdirs()
    }

    private fun setupTermuxExec() {
        // libtermux-exec.so is included in bootstrap.
        // It intercepts execve() to rewrite /data/data/com.termux paths (§2.2.4).
        // However, it does NOT intercept open()/opendir() calls, so binaries with
        // hardcoded config paths (dpkg, bash) need wrapper scripts.
        AppLogger.i(TAG, "Bootstrap installed at ${prefixDir.absolutePath}")

        // Create dpkg wrapper that handles confdir permission errors.
        // The bootstrap dpkg has /data/data/com.termux/.../etc/dpkg/ hardcoded.
        // Since libtermux-exec only rewrites execve() paths, not open() paths,
        // dpkg fails on opendir() of the old com.termux config directory.
        // The wrapper captures stderr and returns success if confdir is the only error.
        val dpkgBin = File(prefixDir, "bin/dpkg")
        val dpkgReal = File(prefixDir, "bin/dpkg.real")
        if (dpkgBin.exists() && (!dpkgReal.exists() || !dpkgBin.readText().contains("export PATH"))) {
            if (!dpkgReal.exists()) dpkgBin.renameTo(dpkgReal)
            val d = "$" // dollar sign for shell script
            val realPath = dpkgReal.absolutePath
            val wrapperContent = """#!/bin/bash
# dpkg wrapper: set PATH so dpkg can find sh, tar, rm, dpkg-deb etc.
# Also suppresses confdir errors from hardcoded com.termux paths.
export PATH="$realPath/../:$realPath/../applets:${d}PATH"
"$realPath" "$d@"
_rc=$d?
if [ ${d}_rc -eq 2 ]; then exit 0; fi
exit ${d}_rc
"""
            dpkgBin.writeText(wrapperContent)
            dpkgBin.setExecutable(true)
        }
    }

    /**
     * Copy post-setup.sh and glibc-compat.js to home dir.
     * post-setup.sh: try GitHub first (background threads only), fall back to bundled asset.
     * glibc-compat.js: the node wrapper reads lib/glibc-compat.js first (v1.1.1+); patches/ is
     * kept for older wrappers, so the bundled copy goes in only when patches/ has none yet or
     * lib/ has none (patches/ is then what the wrapper loads). lib/ is never touched here.
     */
    private fun copyAssetScripts() {
        val ocaDir = File(homeDir, ".openclaw-android")
        ocaDir.mkdirs()
        val patchesDir = File(ocaDir, "patches")
        patchesDir.mkdirs()

        copyPostSetupScript(File(ocaDir, "post-setup.sh"), keepExistingOnFailure = false)

        val patchesCompat = File(patchesDir, "glibc-compat.js")
        val libCompat = File(ocaDir, "lib/glibc-compat.js")
        if (!patchesCompat.exists() || !libCompat.exists()) {
            copyBundledAsset("glibc-compat.js", patchesCompat)
        } else {
            AppLogger.i(TAG, "glibc-compat.js kept (lib/ copy present)")
        }
    }

    /**
     * Re-fetch post-setup.sh before the terminal re-runs an unfinished setup, so a user whose
     * first run failed does not keep re-running the same stale copy. Blocking, bounded by
     * DOWNLOAD_TOTAL_TIMEOUT_SEC — call from a background thread. If the download fails, the
     * copy already in place is kept (it came from GitHub or a newer APK); the bundle is used
     * only when there is none.
     */
    fun refreshPostSetupScript() {
        File(homeDir, ".openclaw-android").mkdirs()
        copyPostSetupScript(postSetupScript, keepExistingOnFailure = true)
    }

    private fun copyPostSetupScript(
        target: File,
        keepExistingOnFailure: Boolean,
    ) {
        if (downloadPostSetupScript(target)) return
        if (keepExistingOnFailure && target.length() > 0L) {
            AppLogger.w(TAG, "post-setup.sh download failed, keeping existing copy")
            return
        }
        try {
            replaceAtomically(target) { out -> context.assets.open("post-setup.sh").use { it.copyTo(out) } }
            AppLogger.i(TAG, "post-setup.sh copied from bundled assets")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to copy bundled post-setup.sh", e)
        }
    }

    /**
     * Download post-setup.sh from GitHub with an overall deadline. HttpURLConnection timeouts
     * are per address / per read, so a blocked network could otherwise stall far past them.
     */
    private fun downloadPostSetupScript(target: File): Boolean {
        val url = "https://raw.githubusercontent.com/AidanPark/openclaw-android/main/post-setup.sh"
        val conn = URL(url).openConnection()
        conn.connectTimeout = DOWNLOAD_CONNECT_TIMEOUT_MS
        conn.readTimeout = DOWNLOAD_READ_TIMEOUT_MS
        val executor = Executors.newSingleThreadExecutor()
        return try {
            val task =
                executor.submit {
                    replaceAtomically(target) { out -> conn.getInputStream().use { it.copyTo(out) } }
                }
            task.get(DOWNLOAD_TOTAL_TIMEOUT_SEC, TimeUnit.SECONDS)
            AppLogger.i(TAG, "post-setup.sh downloaded from GitHub")
            true
        } catch (e: Exception) {
            (conn as? java.net.HttpURLConnection)?.disconnect()
            AppLogger.w(TAG, "Failed to download post-setup.sh", e)
            false
        } finally {
            executor.shutdownNow()
        }
    }

    /** Write via a unique temp file and rename so a failed copy never leaves a truncated script. */
    private fun replaceAtomically(
        target: File,
        write: (java.io.OutputStream) -> Unit,
    ) {
        val tmp = File.createTempFile("${target.name}.", ".tmp", target.parentFile)
        try {
            tmp.outputStream().use { write(it) }
            if (tmp.length() == 0L) throw java.io.IOException("empty download for ${target.name}")
            tmp.setExecutable(true)
            if (!tmp.renameTo(target)) throw java.io.IOException("rename failed for ${target.name}")
        } finally {
            tmp.delete()
        }
    }

    private fun copyBundledAsset(
        assetName: String,
        target: File,
    ) {
        try {
            context.assets.open(assetName).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            AppLogger.i(TAG, "$assetName copied from bundled assets")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to copy $assetName", e)
        }
    }

    // Runtime packages are installed by post-setup.sh in the terminal

    /**
     * Apply script update on APK version upgrade (blocking — background thread only).
     * www sync and the oa CLI install are handled by MainActivity.onCreate, not repeated here.
     */
    fun applyScriptUpdate() {
        if (!isInstalled()) return
        copyAssetScripts()
        AppLogger.i(TAG, "Script update applied")
    }

    fun savedVersionCode(): Int = prefs().getInt(PREF_VERSION_CODE, 0)

    @Suppress("DEPRECATION")
    fun currentVersionCode(): Int = context.packageManager.getPackageInfo(context.packageName, 0).versionCode

    fun saveCurrentVersionCode() = prefs().edit().putInt(PREF_VERSION_CODE, currentVersionCode()).apply()

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, 0)

    /**
     * Copy bundled assets/www into wwwDir, overwriting any existing files.
     * Called on first install and on APK version upgrade to ensure the UI is always current.
     */
    fun syncWwwFromAssets() {
        try {
            wwwDir.mkdirs()
            copyAssetDir("www", wwwDir)
            AppLogger.i(TAG, "www synced from assets to ${wwwDir.absolutePath}")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to sync www from assets", e)
        }
    }

    private fun copyAssetDir(
        assetPath: String,
        targetDir: File,
    ) {
        val entries = context.assets.list(assetPath) ?: return
        targetDir.mkdirs()
        for (entry in entries) {
            copyAssetEntry("$assetPath/$entry", File(targetDir, entry))
        }
    }

    private fun copyAssetEntry(
        assetPath: String,
        targetFile: File,
    ) {
        val children = context.assets.list(assetPath)
        if (!children.isNullOrEmpty()) {
            copyAssetDir(assetPath, targetFile)
        } else {
            context.assets.open(assetPath).use { input ->
                targetFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    /**
     * Fetch the oa CLI from GitHub (background thread only). Written through a temp file and
     * renamed, executable bit set before the rename, so a failed or interrupted download can
     * never leave a truncated or non-executable `oa` behind (and the old copy stays usable).
     */
    fun installOaCli() {
        val oaBin = File(prefixDir, "bin/oa")
        val oaUrl = "https://raw.githubusercontent.com/AidanPark/openclaw-android/main/oa.sh"
        try {
            val conn = URL(oaUrl).openConnection()
            conn.connectTimeout = DOWNLOAD_CONNECT_TIMEOUT_MS
            conn.readTimeout = DOWNLOAD_READ_TIMEOUT_MS
            replaceAtomically(oaBin) { out -> conn.getInputStream().use { it.copyTo(out) } }
            AppLogger.i(TAG, "oa CLI installed at ${oaBin.absolutePath}")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to install oa CLI", e)
        }
    }
}

private object Os {
    @JvmStatic
    fun symlink(
        target: String,
        path: String,
    ) {
        android.system.Os.symlink(target, path)
    }
}
