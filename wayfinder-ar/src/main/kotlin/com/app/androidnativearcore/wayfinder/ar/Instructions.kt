package com.app.androidnativearcore.wayfinder.ar

import com.app.androidnativearcore.wayfinder.core.Maneuver
import com.app.androidnativearcore.wayfinder.core.Venue
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** User-facing text and icons for navigation. Kept in one place so it can be localized. */
object Instructions {
    fun text(maneuver: Maneuver, venue: Venue, languages: List<String>? = null): String {
        fun resolve(text: com.app.androidnativearcore.wayfinder.core.LocalizedText) =
            if (languages == null) text.resolved() else text.resolved(languages)
        return when (val kind = maneuver.kind) {
            Maneuver.Kind.Start -> "Head along the path"
            Maneuver.Kind.SlightLeft -> "Bear left"
            Maneuver.Kind.TurnLeft -> "Turn left"
            Maneuver.Kind.SlightRight -> "Bear right"
            Maneuver.Kind.TurnRight -> "Turn right"
            Maneuver.Kind.UTurn -> "Turn around"
            is Maneuver.Kind.ChangeLevel -> {
                val level = venue.level(kind.toLevel)?.let { resolve(it.name) } ?: kind.toLevel
                when (kind.type) {
                    Venue.EdgeType.ELEVATOR -> "Take the elevator to $level"
                    Venue.EdgeType.ESCALATOR -> "Take the escalator to $level"
                    Venue.EdgeType.STAIRS -> "Take the stairs to $level"
                    else -> "Continue to $level"
                }
            }
            Maneuver.Kind.Arrive -> "Arrive at your destination"
        }
    }

    /** A text glyph standing in for the iOS SF Symbol of each maneuver. */
    fun glyph(maneuver: Maneuver): String = when (val kind = maneuver.kind) {
        Maneuver.Kind.Start -> "↑"
        Maneuver.Kind.SlightLeft -> "↖"
        Maneuver.Kind.TurnLeft -> "↰"
        Maneuver.Kind.SlightRight -> "↗"
        Maneuver.Kind.TurnRight -> "↱"
        Maneuver.Kind.UTurn -> "↩"
        is Maneuver.Kind.ChangeLevel -> when (kind.type) {
            Venue.EdgeType.ELEVATOR -> "⇕"
            Venue.EdgeType.ESCALATOR -> "⤴"
            Venue.EdgeType.STAIRS -> "⤴"
            else -> "↕"
        }
        Maneuver.Kind.Arrive -> "⚑"
    }

    fun distance(meters: Float): String =
        if (meters < 10) "${max(meters, 1f).roundToInt()} m" else "${((meters / 5).roundToInt() * 5)} m"

    /** Walking time at about 1.2 m/s. */
    fun walkingTime(meters: Float): String {
        val minutes = ceil(meters / 1.2f / 60f).toInt()
        return if (minutes <= 1) "1 min" else "$minutes min"
    }
}
