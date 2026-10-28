package com.app.androidnativearcore.wayfinder.core

/**
 * Where each entry of a saved data bundle (.zip) goes in the app's files.
 *
 * The app exports `paths/` and `captured-markers/` at the top of the zip. A bundle that was unpacked and compressed
 * again on a computer often isn't laid out like that any more: macOS Finder wraps everything in a folder named after
 * the zip (`wayfinder-export-…/paths/…`) and adds `__MACOSX/` copies and `.DS_Store` files. Those bundles hold the
 * same data, so the two folders are found at any depth, and the computer's extras are ignored.
 */
object DataBundle {
    /** The folders a bundle carries; nothing else in a zip is imported. */
    val FOLDERS = setOf("paths", "captured-markers")

    /**
     * The path under the app's files folder for the zip entry [entryName] (for example `paths/Obj1.json`), or null
     * if the entry isn't part of the saved data (a folder, a computer's extra file, or anything outside the two
     * folders).
     */
    fun targetPath(entryName: String): String? {
        val parts = entryName.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || entryName.endsWith("/")) return null
        // macOS extras: resource-fork copies under __MACOSX, "._name" companions, and Finder's folder settings.
        if (parts.any { it == "__MACOSX" } || parts.last().startsWith("._") || parts.last() == ".DS_Store") return null
        // Anything that tries to climb out of the folder ("..") is never written.
        if (parts.any { it == ".." || it == "." }) return null
        val start = parts.indexOfFirst { it in FOLDERS }
        if (start < 0 || start == parts.lastIndex) return null
        return parts.drop(start).joinToString("/")
    }
}
