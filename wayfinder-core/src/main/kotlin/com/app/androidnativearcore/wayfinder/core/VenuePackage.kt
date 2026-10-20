package com.app.androidnativearcore.wayfinder.core

import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json

/**
 * A venue's data files: `venue.json` plus the images it references (markers, icons), in one folder.
 * Packages can be bundled with an app, downloaded and cached for offline use, or shared as a zip.
 *
 *     DemoMall.venue/
 *       venue.json
 *       markers/entrance.png
 *       markers/lift-l0.png
 */
class VenuePackage(
    val venue: Venue,
    /** Folder containing `venue.json`; image paths in the venue are relative to it. */
    val baseDir: File,
) {
    class LoadError(message: String) : Exception(message)

    fun file(forMarker: Venue.Marker): File = File(baseDir, forMarker.image)

    /** The same package files with different venue data, e.g. with the user's own destinations added. */
    fun withVenue(venue: Venue) = VenuePackage(venue, baseDir)

    companion object {
        const val VENUE_FILE_NAME = "venue.json"

        private val json = Json { ignoreUnknownKeys = true }

        /** Loads a package folder. Throws if `venue.json` is missing, malformed or references unknown ids. */
        fun load(folder: File): VenuePackage {
            val jsonFile = File(folder, VENUE_FILE_NAME)
            if (!jsonFile.exists()) throw LoadError("No $VENUE_FILE_NAME in ${folder.name}.")
            val venue = try {
                json.decodeFromString(Venue.serializer(), jsonFile.readText())
            } catch (e: Exception) {
                throw LoadError("$VENUE_FILE_NAME can't be read: ${e.message}")
            }
            val issues = venue.validationIssues()
            if (issues.isNotEmpty()) throw LoadError("The venue data has problems:\n" + issues.joinToString("\n"))
            val pkg = VenuePackage(venue, folder)
            val missing = venue.markers.filter { !pkg.file(it).exists() }
            if (missing.isNotEmpty()) throw LoadError("Missing marker images: " + missing.joinToString(", ") { it.image })
            return pkg
        }

        /**
         * Extracts a zipped package into `cacheDirectory/<zip name>` and loads it. The zip may contain the
         * files at its root or inside one folder.
         */
        fun load(zip: File, cacheDirectory: File): VenuePackage {
            val destination = File(cacheDirectory, zip.nameWithoutExtension)
            destination.deleteRecursively()
            extract(zip, destination)

            if (File(destination, VENUE_FILE_NAME).exists()) return load(destination)
            val folder = destination.listFiles().orEmpty().firstOrNull { File(it, VENUE_FILE_NAME).exists() }
                ?: throw LoadError("The zip doesn't contain a $VENUE_FILE_NAME.")
            return load(folder)
        }

        private fun extract(zip: File, destination: File) {
            destination.mkdirs()
            val root = destination.canonicalFile
            ZipInputStream(zip.inputStream().buffered()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val target = File(root, entry.name).canonicalFile
                    // Reject entries that would escape the destination ("zip slip").
                    if (target != root && !target.path.startsWith(root.path + File.separator)) {
                        throw LoadError("The zip contains an unsafe path: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { input.copyTo(it) }
                    }
                }
            }
        }
    }
}
