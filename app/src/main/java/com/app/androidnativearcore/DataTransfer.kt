package com.app.androidnativearcore

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.app.androidnativearcore.wayfinder.core.ArPathStore
import com.app.androidnativearcore.wayfinder.core.CapturedMarkerStore
import com.app.androidnativearcore.wayfinder.core.DataBundle
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Exports and imports the captured data (recorded paths + reference-point markers and their images) as a single
 * `.zip` with readable JSON inside, so a place mapped on one phone can be moved to another or inspected on a computer.
 */
object DataTransfer {

    /** Bundles [dirs] (each becomes a top-level folder in the zip) into a zip in the cache dir and returns it. */
    fun export(context: Context, dirs: List<File>): File {
        val out = File(context.cacheDir, "wayfinder-export-${System.currentTimeMillis()}.zip")
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            for (dir in dirs) {
                if (!dir.exists()) continue
                dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { file ->
                    zos.putNextEntry(ZipEntry("${dir.name}/${file.relativeTo(dir).invariantPath()}"))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
        return out
    }

    /** Opens the Android share sheet for [file]. */
    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share captured data").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Extracts a zip picked by the user into [targetParent] (merging with what's there), so its `paths/` and
     * `captured-markers/` folders land back in the app's files dir, wherever they are inside the zip (see
     * [DataBundle]). Returns the number of files written.
     */
    fun import(context: Context, uri: Uri, targetParent: File): Int {
        val root = targetParent.canonicalFile
        var written = 0
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (entry.isDirectory) continue
                    // Only the folders we export, found at any depth (a bundle compressed again on a computer is
                    // wrapped in a folder); a computer's extra files and anything else are ignored.
                    val relative = DataBundle.targetPath(entry.name) ?: continue
                    val target = File(root, relative).canonicalFile
                    // Reject paths escaping the destination ("zip slip").
                    if (target != root && !target.path.startsWith(root.path + File.separator)) continue
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zis.copyTo(it) }
                    written++
                }
            }
        }
        return written
    }

    /**
     * Imports the bundle at [uri] into the app's files and says what can actually be used now, not just how many
     * files were copied: a file the app couldn't read used to count as "loaded" and then silently not show up.
     */
    fun importWithSummary(context: Context, uri: Uri, venueId: String): String {
        val count = runCatching { import(context, uri, context.filesDir) }.getOrElse { -1 }
        val usablePaths = ArPathStore(File(context.filesDir, "paths")).load(venueId).size
        val usablePoints = CapturedMarkerStore(File(context.filesDir, "captured-markers")).load(venueId).size
        return when {
            count < 0 -> "Couldn't read that file."
            count == 0 -> "That file has no paths or reference points in it."
            else -> "Imported $count file(s). Now saved: $usablePaths destination(s) and $usablePoints reference point(s)."
        }
    }

    private fun File.invariantPath(): String = path.replace(File.separatorChar, '/')
}
