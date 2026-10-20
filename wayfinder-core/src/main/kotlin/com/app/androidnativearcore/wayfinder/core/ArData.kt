package com.app.androidnativearcore.wayfinder.core

import java.io.File
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One recorded path, complete in itself: the destination it leads to, the points of the walk, and the objects
 * placed along it. Each path is saved as its own JSON file (see [ArPathStore]), so a path can be copied, shared,
 * backed up or deleted on its own.
 */
@Serializable
data class ArPath(
    val formatVersion: Int = 1,
    /** The venue the coordinates belong to; paths of different venues never mix. */
    val venueId: String = "",
    val destination: CustomDestination,
    val walkedPoints: List<WalkedPoint> = emptyList(),
    val objects: List<PlacedObject> = emptyList(),
)

/**
 * One file per path in a directory. A path's file is named after its destination (`gaming-room.json`); if that
 * name is taken, `-2`, `-3`… is added.
 *
 * A file the app can't read is skipped and left untouched, so one damaged path never affects the others and is
 * never overwritten.
 */
class ArPathStore(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private class Entry(val file: File, val path: ArPath)

    /**
     * The saved paths of [venueId] (files from an earlier version with no venue are included). On the first call
     * after an update, data an earlier version saved in `ar_data.json` (or in its three older files) is first split
     * into one file per path.
     */
    fun load(venueId: String): List<ArPath> {
        migrateLegacyData(venueId)
        return entries().map { it.path }.filter { it.venueId == venueId || it.venueId.isEmpty() }
    }

    /** Saves [path] to its own file (named after its destination) and returns that file. */
    fun save(path: ArPath): File {
        directory.mkdirs()
        val id = path.destination.id
        // Everything in a path's file belongs to its destination.
        val tagged = path.copy(
            walkedPoints = path.walkedPoints.map { it.copy(destinationId = id) },
            objects = path.objects.map { it.copy(destinationId = id) },
        )
        val file = entries().firstOrNull { it.path.destination.id == id }?.file ?: uniqueFileFor(path.destination.name)
        writeAtomically(file, json.encodeToString(ArPath.serializer(), tagged))
        return file
    }

    /** Deletes the file of the path that leads to [destinationId]. Returns whether one was found. */
    fun delete(destinationId: String): Boolean {
        val entry = entries().firstOrNull { it.path.destination.id == destinationId } ?: return false
        return entry.file.delete()
    }

    private fun entries(): List<Entry> =
        directory.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }.mapNotNull { file ->
            runCatching { Entry(file, json.decodeFromString(ArPath.serializer(), file.readText())) }.getOrNull()
        }

    private fun uniqueFileFor(name: String): File {
        val slug = slugOf(name)
        var candidate = File(directory, "$slug.json")
        var n = 2
        while (candidate.exists()) candidate = File(directory, "$slug-${n++}.json")
        return candidate
    }

    private fun writeAtomically(file: File, text: String) {
        // Write to a temp file and rename, so a crash mid-write can't leave a half-written path file.
        val temp = File(file.path + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "Can't save ${file.path}" }
        }
    }

    // region Migration from the earlier single-file (and three-file) storage

    private fun migrateLegacyData(venueId: String) {
        val parent = directory.parentFile ?: return
        val legacy = LegacyArData.read(parent) ?: return
        directory.mkdirs()
        for (destination in legacy.destinations) {
            save(
                ArPath(
                    venueId = venueId,
                    destination = destination,
                    walkedPoints = legacy.walkedPoints.filter { it.destinationId == destination.id },
                    objects = legacy.objects.filter { it.destinationId == destination.id },
                ),
            )
        }
        // Points that no destination owns (their destination was deleted) belong to no path and are dropped; the old
        // files are kept with a ".migrated" ending, so nothing is lost.
        LegacyArData.markMigrated(parent)
    }

    // endregion

    companion object {
        /** A file-name-safe version of [name]: lower case letters and digits, separated by single dashes. */
        fun slugOf(name: String): String =
            name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "path" }
    }
}

/**
 * Everything saved in the earlier single-file format (`ar_data.json`): destinations, path points and objects of all
 * paths together. Only read now, to split it into one file per path.
 */
@Serializable
data class ArData(
    val formatVersion: Int = 1,
    val destinations: List<CustomDestination> = emptyList(),
    val walkedPoints: List<WalkedPoint> = emptyList(),
    val objects: List<PlacedObject> = emptyList(),
)

/** Reads what earlier versions of the app saved, and retires those files after a migration. */
object LegacyArData {
    const val SINGLE_FILE = "ar_data.json"
    private val OLD_FILES = listOf("custom_destinations.json", "walked_path.json", "placed_objects.json")
    private const val MIGRATED_SUFFIX = ".migrated"

    private val json = Json { ignoreUnknownKeys = true }

    /** The data saved in [directory] by an earlier version, or null if there is none. */
    fun read(directory: File): ArData? {
        val single = File(directory, SINGLE_FILE)
        if (single.exists()) return runCatching { json.decodeFromString(ArData.serializer(), single.readText()) }.getOrNull()

        val old = OLD_FILES.map { File(directory, it) }
        if (old.none { it.exists() }) return null
        fun <T> list(file: File, serializer: KSerializer<T>): List<T> =
            runCatching { json.decodeFromString(ListSerializer(serializer), file.readText()) }.getOrDefault(emptyList())
        val destinations = list(old[0], CustomDestination.serializer())
        val points = list(old[1], WalkedPoint.serializer())
        val objects = list(old[2], PlacedObject.serializer())
        // The oldest files didn't say which destination a path was for: a run belongs to the destination it ends at.
        return ArData(destinations = destinations, walkedPoints = tagRunsByDestination(points, destinations), objects = objects)
    }

    /** Renames the legacy files to `*.migrated`, keeping them as a backup but out of the way. */
    fun markMigrated(directory: File) {
        (listOf(SINGLE_FILE) + OLD_FILES).map { File(directory, it) }.filter { it.exists() }.forEach {
            val target = File(it.path + MIGRATED_SUFFIX)
            target.delete()
            it.renameTo(target)
        }
    }

    /** Radius (meters) within which a run's last point counts as ending at a destination. */
    private const val ENDS_AT_DESTINATION_M = 0.05f

    /**
     * Sets the `destinationId` of every point of a run to the destination the run ends at. Runs that end nowhere near
     * a destination stay untagged.
     */
    fun tagRunsByDestination(points: List<WalkedPoint>, destinations: List<CustomDestination>): List<WalkedPoint> {
        val owner = HashMap<String, String>()
        for ((run, runPoints) in points.groupBy { it.run }) {
            val end = runPoints.maxByOrNull { it.sequence } ?: continue
            val destination = destinations.firstOrNull {
                kotlin.math.hypot(end.x - it.x, end.z - it.z) <= ENDS_AT_DESTINATION_M
            } ?: continue
            owner[run] = destination.id
        }
        return points.map { point -> owner[point.run]?.let { point.copy(destinationId = it) } ?: point }
    }
}
