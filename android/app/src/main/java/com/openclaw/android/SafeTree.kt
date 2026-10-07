package com.openclaw.android

import java.io.File
import java.io.IOException

/**
 * Recursive delete that never follows a symlink. Kotlin's `File.deleteRecursively()` enters a
 * symlink to a directory and deletes what is inside the target: a link left in `usr-staging`
 * (SYMLINKS.txt targets point into the app's `usr`), or a link in `usr` into home, would take
 * those files with it.
 *
 * java.io cannot lstat (java.nio.file needs API 26) and `android.system.Os` does not run in JVM
 * tests, so a directory counts as real only when its path inside its canonical parent is already
 * canonical. Every directory entered is real, so a child's parent is canonical by construction and
 * only directories need one canonicalization each — files and links to files are just delete()d,
 * which removes the entry itself, never its target. A real directory misjudged as a link is only
 * delete()d, which fails while it has contents: the safe direction. Depth is bounded by the real
 * directory depth, since no link is ever entered.
 */
internal object SafeTree {
    /**
     * Delete [file] and, if it is a real directory, everything under it. Same result as
     * `deleteRecursively()`: true when [file] no longer exists (also when it never did), false when
     * something could not be removed — the rest is still removed.
     */
    fun deleteNoFollow(file: File): Boolean {
        val entry =
            try {
                file.absoluteFile.parentFile
                    ?.canonicalFile
                    ?.let { File(it, file.name) }
            } catch (_: IOException) {
                null
            }
        if (entry != null && isRealDirectory(entry)) entry.listFiles()?.forEach(::deleteEntry)
        return file.delete() || !file.exists()
    }

    /** [file]'s parent path is canonical (a real directory with no link above it). */
    private fun deleteEntry(file: File) {
        if (isRealDirectory(file)) file.listFiles()?.forEach(::deleteEntry)
        file.delete()
    }

    /** isDirectory follows links; the canonical path tells a real directory from a link to one. */
    private fun isRealDirectory(file: File): Boolean =
        file.isDirectory &&
            try {
                file.canonicalFile.path == file.path
            } catch (_: IOException) {
                false
            }
}
