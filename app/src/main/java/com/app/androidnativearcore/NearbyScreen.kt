package com.app.androidnativearcore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.app.androidnativearcore.ui.designkit.IosPrimaryButton
import com.app.androidnativearcore.ui.designkit.IosRow
import com.app.androidnativearcore.ui.designkit.IosRowDivider
import com.app.androidnativearcore.ui.designkit.IosSection
import com.app.androidnativearcore.wayfinder.ar.LocatedUser
import com.app.androidnativearcore.wayfinder.core.CustomDestination
import com.app.androidnativearcore.wayfinder.core.REFERENCE_CATEGORY
import com.app.androidnativearcore.wayfinder.core.Vec3
import com.app.androidnativearcore.wayfinder.core.Venue
import com.app.androidnativearcore.wayfinder.core.WalkedPathRouter
import com.app.androidnativearcore.wayfinder.core.WalkedPoint
import com.app.androidnativearcore.wayfinder.core.WalkedRouteResult
import com.app.androidnativearcore.wayfinder.core.referencePoiId

/** A place the user can go from where they stand, with the walking distance along the recorded paths. */
private class Reachable(val poi: Venue.POI, val route: List<Vec3>?, val meters: Float?)

/**
 * "From here": after the camera recognized a reference point, every other point and destination on the level,
 * nearest first, as a list or on the 2D map. Picking one starts AR directions to it.
 */
@Composable
fun NearbyScreen(
    venue: Venue,
    markers: List<Venue.Marker>,
    walked: List<WalkedPoint>,
    located: LocatedUser,
    onNavigate: (Venue.POI) -> Unit,
    onRescan: () -> Unit,
    onBack: () -> Unit,
) {
    val here = remember(located) { Vec3(located.x, 0f, located.z) }
    val places = remember(venue, walked, located) {
        val points = walked.filter { it.level == located.level }
        val router = WalkedPathRouter(destinationJoinDistance = 3f)
        venue.pois
            .filter { it.level == located.level && (it.category == CustomDestination.CATEGORY || it.category == REFERENCE_CATEGORY) }
            .filter { it.id != referencePoiId(located.marker.id) }
            .map { poi ->
                val result = router.route(here, Vec3(poi.x, 0f, poi.z), points)
                val line = (result as? WalkedRouteResult.Success)?.points
                Reachable(poi, line, line?.zipWithNext()?.sumOf { (a, b) -> a.distanceTo(b).toDouble() }?.toFloat())
            }
            .sortedWith(compareBy({ it.meters == null }, { it.meters ?: 0f }))
    }
    var showMap by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Reachable?>(null) }
    val levelName = venue.level(located.level)?.name?.resolved().orEmpty()

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Home") }
                Box(Modifier.weight(1f))
                TextButton(onClick = onRescan) { Text("Scan again") }
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("You're at", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    located.marker.name.resolved(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (levelName.isNotEmpty()) {
                    Text(levelName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Segment("List", !showMap, Modifier.weight(1f)) { showMap = false }
                Segment("Map", showMap, Modifier.weight(1f)) { showMap = true }
            }

            if (!showMap) {
                if (places.isEmpty()) {
                    Text(
                        "No other points saved on this level yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        IosSection(title = "Nearest first") {
                            places.forEachIndexed { index, place ->
                                IosRow(
                                    title = place.poi.name.resolved(),
                                    glyph = if (place.poi.category == REFERENCE_CATEGORY) "📍" else "⭐",
                                    subtitle = if (place.poi.category == REFERENCE_CATEGORY) "Reference point" else "Destination",
                                    trailing = place.meters?.let { "%.0f m".format(it) } ?: "no path",
                                    onClick = { onNavigate(place.poi) },
                                )
                                if (index < places.lastIndex) IosRowDivider()
                            }
                        }
                    }
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    FloorMap(
                        level = located.level,
                        walked = walked,
                        markers = markers,
                        destinations = places.map { it.poi },
                        user = MapUser(located.x, located.z),
                        route = selected?.route,
                        selectedId = selected?.poi?.id,
                        onSelect = { poi -> selected = places.firstOrNull { it.poi.id == poi.id } },
                    )
                    selected?.let { place ->
                        Column(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)
                                .clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(place.poi.name.resolved(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(
                                place.meters?.let { "%.0f m along the recorded path".format(it) } ?: "No recorded path from here",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            IosPrimaryButton("Show directions", onClick = { onNavigate(place.poi) }, glyph = "➤", enabled = place.meters != null)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurface,
        )
    }
}
