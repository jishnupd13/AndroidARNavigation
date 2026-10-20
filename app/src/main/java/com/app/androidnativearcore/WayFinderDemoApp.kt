package com.app.androidnativearcore

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.app.androidnativearcore.wayfinder.ar.NavigationFeedback
import com.app.androidnativearcore.wayfinder.ar.WayFinderCaptureScreen
import com.app.androidnativearcore.wayfinder.ar.WayFinderNavigationListener
import com.app.androidnativearcore.wayfinder.ar.WayFinderNavigationScreen
import com.app.androidnativearcore.wayfinder.core.CustomDestination
import com.app.androidnativearcore.wayfinder.core.CustomDestinationStore
import com.app.androidnativearcore.wayfinder.core.Route
import com.app.androidnativearcore.wayfinder.core.RouteProfile
import com.app.androidnativearcore.wayfinder.core.Venue
import com.app.androidnativearcore.wayfinder.core.VenuePackage
import com.app.androidnativearcore.wayfinder.core.PlacedObjectStore
import com.app.androidnativearcore.wayfinder.core.WalkedPathStore
import com.app.androidnativearcore.wayfinder.core.withCustomDestinations
import com.google.ar.core.ArCoreApk
import java.io.File
import java.util.UUID

private const val TAG = "WayFinderDemo"

private sealed interface Screen {
    data object List : Screen
    data object Signs : Screen
    data object Capture : Screen
    data class Navigate(val poi: Venue.POI, val profile: RouteProfile) : Screen
}

/** The venue package bundled with the demo app. A production app would download and cache packages per venue. */
private object DemoVenue {
    private const val ASSET_DIR = "DemoMall.venue"

    fun load(context: Context): Result<VenuePackage> = runCatching {
        val target = File(context.filesDir, ASSET_DIR)
        target.deleteRecursively()
        copyAsset(context, ASSET_DIR, target)
        VenuePackage.load(target)
    }

    private fun copyAsset(context: Context, path: String, target: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
        } else {
            target.mkdirs()
            children.forEach { copyAsset(context, "$path/$it", File(target, it)) }
        }
    }
}

@Composable
fun WayFinderDemoApp() {
    val context = LocalContext.current
    val venue = remember { DemoVenue.load(context) }
    venue.fold(
        onSuccess = { WayFinderHost(it) },
        onFailure = {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("Venue data can't be loaded", style = MaterialTheme.typography.titleMedium)
                Text(it.message ?: "", style = MaterialTheme.typography.bodyMedium)
            }
        },
    )
}

@Composable
private fun WayFinderHost(basePackage: VenuePackage) {
    val context = LocalContext.current
    // Destinations the user added are saved on the phone and show up as ordinary POIs.
    val store = remember { CustomDestinationStore(File(context.filesDir, "custom_destinations.json")) }
    var customDestinations by remember { mutableStateOf(store.load()) }
    // The walks recorded while adding destinations; routes to those destinations follow them.
    val walkStore = remember { WalkedPathStore(File(context.filesDir, "walked_path.json")) }
    var walkedPoints by remember { mutableStateOf(walkStore.load()) }
    // Objects placed along the walked paths, shown when navigating to their destination.
    val objectStore = remember { PlacedObjectStore(File(context.filesDir, "placed_objects.json")) }
    var placedObjects by remember { mutableStateOf(objectStore.load()) }
    val pkg = remember(basePackage, customDestinations) {
        basePackage.withVenue(basePackage.venue.withCustomDestinations(customDestinations))
    }
    var screen by remember { mutableStateOf<Screen>(Screen.List) }
    var stepFree by remember { mutableStateOf(false) }

    when (val current = screen) {
        Screen.List -> DestinationListScreen(
            pkg = pkg,
            stepFree = stepFree,
            onStepFreeChange = { stepFree = it },
            onNavigate = { screen = Screen.Navigate(it, if (stepFree) RouteProfile.STEP_FREE else RouteProfile.FASTEST) },
            onOpenSigns = { screen = Screen.Signs },
            onAddDestination = { screen = Screen.Capture },
            onDeleteDestination = {
                customDestinations = store.remove(it.id)
                placedObjects = objectStore.removeForDestination(it.id)
            },
        )
        Screen.Capture -> WayFinderCaptureScreen(
            pkg = pkg,
            onSave = { captured ->
                walkedPoints = walkStore.addAll(captured.path)
                val destinationId = "custom-" + UUID.randomUUID()
                placedObjects = objectStore.addAll(captured.objects.map { it.copy(destinationId = destinationId) })
                customDestinations = store.add(
                    CustomDestination(
                        id = destinationId,
                        name = captured.name, level = captured.level, x = captured.x, z = captured.z,
                        createdAtMillis = System.currentTimeMillis(),
                    ),
                )
                screen = Screen.List
            },
            onCancel = { screen = Screen.List },
        )
        Screen.Signs -> {
            BackHandler { screen = Screen.List }
            MarkerGalleryScreen(pkg, onBack = { screen = Screen.List })
        }
        is Screen.Navigate -> {
            val listener = remember {
                object : WayFinderNavigationListener {
                    override fun onRoutePlanned(route: Route, rerouted: Boolean) {
                        Log.i(TAG, "${if (rerouted) "Rerouted" else "Planned"} route: ${route.nodeIDs.joinToString(" → ")}, ${route.length.toInt()} m")
                    }

                    override fun onFeedback(feedback: NavigationFeedback) {
                        // A production app would send this to the backend for the venue team.
                        Log.i(TAG, "Feedback: ${feedback.reason.label} at ${feedback.position} on ${feedback.level ?: "?"}")
                    }

                    override fun onFinished() {
                        screen = Screen.List
                    }
                }
            }
            WayFinderNavigationScreen(
                pkg = pkg,
                destinationId = current.poi.id,
                profile = current.profile,
                listener = listener,
                walkedPoints = walkedPoints,
                placedObjects = placedObjects,
                debug = true,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DestinationListScreen(
    pkg: VenuePackage,
    stepFree: Boolean,
    onStepFreeChange: (Boolean) -> Unit,
    onNavigate: (Venue.POI) -> Unit,
    onOpenSigns: () -> Unit,
    onAddDestination: () -> Unit,
    onDeleteDestination: (Venue.POI) -> Unit,
) {
    val context = LocalContext.current
    val venue = pkg.venue
    var search by remember { mutableStateOf("") }
    var unsupported by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Venue.POI?>(null) }

    fun start(poi: Venue.POI) {
        // Like ARWorldTrackingConfiguration.isSupported on iOS: only a device that can't run ARCore is refused.
        if (ArCoreApk.getInstance().checkAvailability(context) == ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE) {
            unsupported = true
        } else {
            onNavigate(poi)
        }
    }

    Scaffold { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).statusBarsPadding()) {
            item {
                Text(
                    stringResource(R.string.list_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(16.dp),
                )
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("Search shops and services") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                OutlinedButton(
                    onClick = onAddDestination,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) { Text("＋ Add destination") }
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Step-free route", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Avoids stairs and escalators — for wheelchairs, strollers and mobility assistance.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = stepFree, onCheckedChange = onStepFreeChange)
                }
            }
            if (venue.pois.isEmpty()) {
                item {
                    Text(
                        "No destinations yet. Tap “Add destination”, scan a sign, walk to a spot and save it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            for (level in venue.levels) {
                val pois = venue.pois
                    .filter { it.level == level.id }
                    .filter {
                        search.isEmpty() || it.name.resolved().contains(search, ignoreCase = true) ||
                            it.category.contains(search, ignoreCase = true)
                    }
                    .sortedBy { it.name.resolved() }
                if (pois.isEmpty()) continue
                item(key = "level-${level.id}") {
                    Text(
                        level.name.resolved(),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(pois, key = { it.id }) { poi ->
                    POIRow(poi, onClick = { start(poi) }, onLongClick = if (poi.category == CustomDestination.CATEGORY) ({ deleting = poi }) else null)
                    HorizontalDivider()
                }
            }
            item {
                Column(Modifier.clickable(onClick = onOpenSigns).padding(16.dp)) {
                    Text("AR signs ›", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "To try navigation, print the Main Entrance sign at 20 cm wide and tape it to a wall at about 1.4 m height.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    deleting?.let { poi ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${poi.name.resolved()}”?") },
            text = { Text("This removes the saved destination from this phone.") },
            confirmButton = { TextButton(onClick = { onDeleteDestination(poi); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    if (unsupported) {
        AlertDialog(
            onDismissRequest = { unsupported = false },
            title = { Text("AR navigation isn't supported on this device") },
            confirmButton = { TextButton(onClick = { unsupported = false }) { Text("OK") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun POIRow(poi: Venue.POI, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val glyph = when (poi.category) {
        "shop" -> "🛍"
        "restroom" -> "🚻"
        "food" -> "🍴"
        "entertainment" -> "🎬"
        CustomDestination.CATEGORY -> "⭐"
        else -> "📍"
    }
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(glyph, style = MaterialTheme.typography.titleLarge, modifier = Modifier.width(32.dp))
        Column(Modifier.weight(1f)) {
            Text(poi.name.resolved(), style = MaterialTheme.typography.bodyLarge)
            if (poi.category == CustomDestination.CATEGORY) {
                Text("Saved by you · hold to delete", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            poi.description?.resolved()?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text("AR", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Shows the venue's AR signs with where they belong, and lets testers share an image to print. */
@Composable
private fun MarkerGalleryScreen(pkg: VenuePackage, onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).statusBarsPadding()) {
            item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("‹ Back") }
                    Text("AR signs", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
            }
            items(pkg.venue.markers, key = { it.id }) { marker ->
                val bitmap = remember(marker) { BitmapFactory.decodeFile(pkg.file(marker).path) }
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = marker.name.resolved(),
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
                        )
                    }
                    Text(marker.name.resolved(), style = MaterialTheme.typography.titleMedium)
                    Text(placement(pkg, marker), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pkg.file(marker))
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, marker.name.resolved()))
                    }) { Text("Share image") }
                }
                Spacer(Modifier.padding(2.dp))
            }
        }
    }
}

private fun placement(pkg: VenuePackage, marker: Venue.Marker): String {
    val level = pkg.venue.level(marker.level)?.name?.resolved() ?: marker.level
    val mounting = if (marker.mounting == Venue.MarkerMounting.WALL) "on a wall, center ${format(marker.y)} m high" else "on the floor"
    return "$level · print ${format(marker.physicalWidth * 100)} cm wide · $mounting, facing ${marker.facing.toInt()}° at x ${format(marker.x)}, z ${format(marker.z)}"
}

private fun format(value: Float): String = if (value.rounded() == value) value.toInt().toString() else "%.1f".format(value)

private fun Float.rounded(): Float = Math.round(this).toFloat()
