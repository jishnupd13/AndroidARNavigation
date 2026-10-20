package com.app.androidnativearcore.wayfinder.core

import java.io.File
import kotlin.math.max
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** The kinds of 3D object a user can drop along a path while recording it. */
@Serializable
enum class PlacedObjectKind(val label: String, val glyph: String) {
    @SerialName("cup") CUP("Mug", "☕"),
    @SerialName("gift") GIFT("Gift", "🎁"),
    @SerialName("star") STAR("Star", "⭐"),
    @SerialName("balloon") BALLOON("Balloon", "🎈"),
    @SerialName("message") MESSAGE("Message", "💬"),
}

/**
 * A 3D object the user placed while recording a path ("Add object"), in venue coordinates, so it shows up
 * again at the same real-world spot whenever the route is walked after scanning a sign.
 */
@Serializable
data class PlacedObject(
    val id: String,
    val kind: PlacedObjectKind,
    val level: String,
    val x: Float,
    /** Height of the surface the object stands on (venue coordinates). */
    val y: Float,
    val z: Float,
    /** Rotation about the vertical axis, in degrees. */
    val yawDegrees: Float = 0f,
    /** Text of a [PlacedObjectKind.MESSAGE]; unused by the other kinds. */
    val text: String = "",
    /** The saved destination whose route this object belongs to ("" until the recording is saved). */
    val destinationId: String = "",
    val createdAtMillis: Long = 0L,
    /** The path's reference point: the first object placed, marking where the path starts (drawn with a START beacon). */
    val isReference: Boolean = false,
) {
    val position: Vec3 get() = Vec3(x, y, z)
}

/** The objects to show while navigating to [destinationId]. */
fun List<PlacedObject>.forDestination(destinationId: String): List<PlacedObject> =
    filter { it.destinationId == destinationId }

/**
 * Where to put an object when the user taps "add" but the crosshair isn't on a surface: on the floor, [distance]
 * meters ahead of them along their heading ([headingX], [headingZ]), so it never ends up under their feet.
 */
fun fallbackObjectPosition(user: Vec3, headingX: Float, headingZ: Float, floorY: Float, distance: Float = 1f): Vec3 {
    val length = max(Vec2(headingX, headingZ).length, 1e-4f)
    return Vec3(user.x + headingX / length * distance, floorY, user.z + headingZ / length * distance)
}

/**
 * A spot for a new object that doesn't sit on top of [existing] ones (which would hide them): [wanted] if it is
 * clear, otherwise the nearest free place on rings of 0.3 m and 0.6 m around it.
 */
fun spreadObjectPosition(wanted: Vec3, existing: List<Vec3>, minSeparation: Float = 0.3f): Vec3 {
    fun clear(p: Vec3) = existing.none { horizontalDistance(it, p) < minSeparation }
    if (clear(wanted)) return wanted
    for (ring in 1..3) for (step in 0 until 8) {
        val angle = Math.toRadians(step * 45.0)
        val candidate = Vec3(
            wanted.x + (kotlin.math.cos(angle) * minSeparation * ring).toFloat(), wanted.y,
            wanted.z + (kotlin.math.sin(angle) * minSeparation * ring).toFloat(),
        )
        if (clear(candidate)) return candidate
    }
    return wanted
}

/**
 * An object placed in the AR session's frame, expressed in venue coordinates with [venueFromSession] (the inverse
 * of the final alignment); [alignmentYawDegrees] is that alignment's rotation about the vertical axis.
 */
fun PlacedObject.toVenue(venue: Venue, venueFromSession: Mat4, alignmentYawDegrees: Float, fallbackLevel: String): PlacedObject {
    val v = venueFromSession.transformPoint(position)
    val level = venue.level(forDeviceHeight = v.y + 1.3f)?.id ?: fallbackLevel
    return copy(level = level, x = v.x, y = v.y, z = v.z, yawDegrees = yawDegrees - alignmentYawDegrees)
}

/** Keeps every placed object in a JSON file, so they come back after a restart. */
class PlacedObjectStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(PlacedObject.serializer())

    fun load(): List<PlacedObject> = runCatching {
        if (file.exists()) json.decodeFromString(serializer, file.readText()) else emptyList()
    }.getOrDefault(emptyList())

    fun addAll(objects: List<PlacedObject>): List<PlacedObject> = save(load() + objects)

    /** Removes everything that belongs to a deleted destination. */
    fun removeForDestination(destinationId: String): List<PlacedObject> =
        save(load().filterNot { it.destinationId == destinationId })

    private fun save(list: List<PlacedObject>): List<PlacedObject> {
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(json.encodeToString(serializer, list))
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "Can't save objects to ${file.path}" }
        }
        return list
    }
}
