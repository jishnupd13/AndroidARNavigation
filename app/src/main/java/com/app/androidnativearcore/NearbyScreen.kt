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
import androidx.compose.runtime.LaunchedEffect
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
import com.app.androidnativearcore.wayfinder.core.BeaconFix
import com.app.androidnativearcore.wayfinder.core.ConnectionRoutePlanner
import com.app.androidnativearcore.wayfinder.core.ConnectionRouteResult
import com.app.androidnativearcore.wayfinder.core.RegisteredBeacon
import com.app.androidnativearcore.wayfinder.core.RouteEndpoint
import com.app.androidnativearcore.wayfinder.core.position
import com.app.androidnativearcore.wayfinder.core.routeEndpoint
import com.app.androidnativearcore.wayfinder.core.routeStart
import com.app.androidnativearcore.wayfinder.core.WalkedPoint
import com.app.androidnativearcore.wayfinder.core.referencePoiId

/** How far (meters) a scanned starting point, or a reference point as a destination, may be from the walks. */
private const val START_JOIN_M = 3f

/** How far (meters) a saved destination may be from the walk recorded to it. */
private const val DESTINATION_JOIN_M = 1.5f

/** A place the user can go from where they stand, with the walking distance along the recorded paths. */
private class Reachable(val poi: Venue.POI, val route: List<Vec3>?, val meters: Float?)

/** A registered beacon as somewhere to go on the map, with the route to it along the recorded paths (if any). */
private class BeaconTarget(val beacon: RegisteredBeacon, val route: List<Vec3>?, val meters: Float?)

/**
 * "From here": every other point and destination on the level, nearest first along the recorded walks, as a list or
 * on the 2D map. Picking one starts AR directions to it.
 *
 * "Here" is where the user was found: by the camera recognizing a reference point ([located]), or, without that, by
 * the nearest Bluetooth beacon ([beaconFix]). The beacon estimate is coarse (the user is somewhere around the
 * beacon), and it can change while the user walks: the distances and routes are worked out again whenever the
 * nearest beacon changes. If the beacon goes quiet, the last one heard is kept and marked as such.
 *
 * The map also shows the registered [beacons]; tapping one shows the route to it. Where no recorded walk leads
 * there, a dashed straight line gives the direction only. Once that beacon is the nearest one, the user has arrived.
 */
@Composable
fun NearbyScreen(
    venue: Venue,
    markers: List<Venue.Marker>,
    walked: List<WalkedPoint>,
    located: LocatedUser?,
    onNavigate: (Venue.POI) -> Unit,
    onRescan: () -> Unit,
    onBack: () -> Unit,
    beaconFix: BeaconFix? = null,
    beacons: List<RegisteredBeacon> = emptyList(),
) {
    // The last beacon heard, kept when it goes quiet so the list doesn't vanish.
    var lastFix by remember { mutableStateOf(beaconFix) }
    LaunchedEffect(beaconFix?.beacon?.beaconId) { if (beaconFix != null) lastFix = beaconFix }
    val fix = beaconFix ?: lastFix
    val start: RouteEndpoint? = located?.let { RouteEndpoint(it.marker.name.resolved(), Vec3(it.x, 0f, it.z), it.level, START_JOIN_M) }
        ?: fix?.routeStart()
    // Worked out again only when the starting point itself changes (another reference point or another beacon), not
    // on every new signal reading.
    val startKey = located?.let { "ref:${it.marker.id}:${it.x}:${it.z}" } ?: fix?.let { "beacon:${it.beacon.beaconId}" }
    val places = remember(venue, walked, startKey) {
        val from = start ?: return@remember emptyList()
        val connections = ConnectionRoutePlanner(walked)
        venue.pois
            .filter { it.level == from.level && (it.category == CustomDestination.CATEGORY || it.category == REFERENCE_CATEGORY) }
            .filter { located == null || it.id != referencePoiId(located.marker.id) }
            .map { poi ->
                // A saved destination is reached along its own walk; a reference point along any walk near it.
                val goal = if (poi.category == CustomDestination.CATEGORY) {
                    RouteEndpoint(poi.name.resolved(), Vec3(poi.x, 0f, poi.z), poi.level, DESTINATION_JOIN_M, poi.id.takeIf { id -> walked.any { it.destinationId == id } })
                } else {
                    RouteEndpoint(poi.name.resolved(), Vec3(poi.x, 0f, poi.z), poi.level, START_JOIN_M)
                }
                when (val result = connections.route(from, goal)) {
                    is ConnectionRouteResult.Success -> Reachable(poi, result.points, result.lengthMeters)
                    is ConnectionRouteResult.Failure -> Reachable(poi, null, null)
                }
            }
            .sortedWith(compareBy({ it.meters == null }, { it.meters ?: 0f }))
    }
    // The other beacons on the level, each with its route from here.
    val beaconTargets = remember(walked, startKey, beacons) {
        val from = start ?: return@remember emptyList()
        val connections = ConnectionRoutePlanner(walked)
        beacons.filter { it.floorId == from.level }.map { beacon ->
            when (val result = connections.route(from, beacon.routeEndpoint())) {
                is ConnectionRouteResult.Success -> BeaconTarget(beacon, result.points, result.lengthMeters)
                is ConnectionRouteResult.Failure -> BeaconTarget(beacon, null, null)
            }
        }
    }
    var showMap by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Reachable?>(null) }
    var selectedBeaconId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(places) { selected = selected?.let { old -> places.firstOrNull { it.poi.id == old.poi.id } } }
    val selectedBeacon = beaconTargets.firstOrNull { it.beacon.beaconId == selectedBeaconId }
    val nearBeaconId = if (located == null) fix?.beacon?.beaconId else null
    val level = start?.level ?: located?.level ?: fix?.beacon?.floorId.orEmpty()
    val levelName = venue.level(level)?.name?.resolved().orEmpty()

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Home") }
                Box(Modifier.weight(1f))
                TextButton(onClick = onRescan) { Text(if (located != null) "Scan again" else "Scan a reference point") }
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    if (located != null) "You're at" else "You're near (Bluetooth)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    located?.marker?.name?.resolved() ?: fix?.beacon?.name ?: "Looking for beacons…",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                val detail = listOfNotNull(
                    levelName.ifEmpty { null },
                    if (located == null && fix != null) {
                        if (fix.isClose) "right by the beacon" else "within about %.0f m of it".format(fix.uncertaintyMeters)
                    } else null,
                    if (located == null && fix != null && beaconFix == null) "last heard" else null,
                ).joinToString(" · ")
                if (detail.isNotEmpty()) {
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    val arrived = selectedBeacon != null && selectedBeacon.beacon.beaconId == nearBeaconId
                    FloorMap(
                        level = level,
                        walked = walked,
                        markers = markers,
                        destinations = places.map { it.poi },
                        user = start?.let { MapUser(it.position.x, it.position.z) },
                        route = selected?.route ?: selectedBeacon?.route?.takeUnless { arrived },
                        selectedId = selected?.poi?.id ?: selectedBeacon?.beacon?.beaconId,
                        onSelect = { poi ->
                            selected = places.firstOrNull { it.poi.id == poi.id }
                            selectedBeaconId = null
                        },
                        beacons = beacons,
                        nearBeaconId = nearBeaconId,
                        onSelectBeacon = { beacon ->
                            selectedBeaconId = beacon.beaconId
                            selected = null
                        },
                        userUncertaintyMeters = if (located == null) fix?.uncertaintyMeters else null,
                        straightLine = selectedBeacon?.takeIf { it.route == null && !arrived }?.let { target ->
                            start?.let { listOf(it.position, target.beacon.position) }
                        },
                    )
                    selectedBeacon?.let { target ->
                        Column(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)
                                .clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(target.beacon.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(
                                "Bluetooth beacon" + (levelName.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val straight = start?.position?.let { "%.1f m".format(it.distanceTo(target.beacon.position)) }
                            Text(
                                when {
                                    arrived -> "You've arrived: this is the beacon you're nearest to now."
                                    target.meters != null -> "%.0f m along the recorded path".format(target.meters)
                                    else -> "No recorded path to it yet. The dashed line is the straight line ($straight), not a walking route."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (arrived) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (arrived) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
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
