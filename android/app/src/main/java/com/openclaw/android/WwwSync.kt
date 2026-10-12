package com.openclaw.android

import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Copies the bundled UI (assets/www) into the app's own www directory. Every file is written to a
 * temp file next to it and renamed over the target, so a reader (the WebView loading index.html
 * and its script) sees the old file or the new one, never one that was truncated and is still
 * being written. Asset access is passed in as functions so the copy runs without a Context.
 */
internal object WwwSync {
    // A killed process leaves its temp file behind; anything younger than this may be a live copy
    private const val STALE_TEMP_AGE_MS = 60_000L
    private const val TEMP_SUFFIX = ".tmp"

    fun copyTree(
        assetPath: String,
        targetDir: File,
        list: (String) -> Array<String>?,
        open: (String) -> InputStream,
    ) {
        if (!targetDir.isDirectory && !targetDir.mkdirs()) throw IOException("Could not create $targetDir")
        removeStaleTemps(targetDir)
        for (entry in list(assetPath).orEmpty()) {
            val child = "$assetPath/$entry"
            if (list(child).isNullOrEmpty()) {
                replaceFile(File(targetDir, entry)) { out -> open(child).use { it.copyTo(out) } }
            } else {
                copyTree(child, File(targetDir, entry), list, open)
            }
        }
    }

    private fun replaceFile(
        target: File,
        write: (java.io.OutputStream) -> Unit,
    ) {
        // The leading dot keeps the prefix at the three characters createTempFile needs for a one-letter name
        val tmp = File.createTempFile(".${target.name}.", TEMP_SUFFIX, target.parentFile)
        try {
            tmp.outputStream().use(write)
            if (!tmp.renameTo(target)) throw IOException("rename failed for ${target.name}")
        } finally {
            tmp.delete()
        }
    }

    private fun removeStaleTemps(dir: File) {
        dir
            .listFiles { f -> f.isFile && f.name.endsWith(TEMP_SUFFIX) }
            ?.filter { System.currentTimeMillis() - it.lastModified() > STALE_TEMP_AGE_MS }
            ?.forEach { it.delete() }
    }
}
