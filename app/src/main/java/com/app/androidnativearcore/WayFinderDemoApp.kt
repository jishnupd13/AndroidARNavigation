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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.app.androidnativearcore.ui.designkit.IosRow
import com.app.androidnativearcore.ui.designkit.IosRowDivider
import com.app.androidnativearcore.ui.designkit.IosSection
import com.app.androidnativearcore.wayfinder.ar.NavigationFeedback
import com.app.androidnativearcore.wayfinder.ar.WayFinderCaptureScreen
import com.app.androidnativearcore.wayfinder.ar.WayFinderNavigationListener
import com.app.androidnativearcore.wayfinder.ar.WayFinderNavigationScreen
import com.app.androidnativearcore.wayfinder.core.CustomDestination
import com.app.androidnativearcore.wayfinder.core.ArPath
import com.app.androidnativearcore.wayfinder.core.ArPathStore
import com.app.androidnativearcore.wayfinder.core.Route
import com.app.androidnativearcore.wayfinder.core.RouteProfile
import com.app.androidnativearcore.wayfinder.core.Venue
import com.app.androidnativearcore.wayfinder.core.VenuePackage
import com.app.androidnativearcore.wayfinder.core.withCustomDestinations
import com.google.ar.core.ArCoreApk
import java.io.File
import java.util.UUID

private const val TAG = "WayFinderDemo"

private sealed interface Screen {
    data object Home : Screen
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
    // Every path the user recorded is its own JSON file in `paths/`: the destination, the walk to it and the objects
    // along it. Destinations show up in the list as ordinary POIs.
    val store = remember { ArPathStore(File(context.filesDir, "paths")) }
    var paths by remember { mutableStateOf(store.load(basePackage.venue.id)) }
    val customDestinations = paths.map { it.destination }
    val pkg = remember(basePackage, customDestinations) {
        basePackage.withVenue(basePackage.venue.withCustomDestinations(customDestinations))
    }
    var mode by remember { mutableStateOf(AppMode.USER) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var stepFree by remember { mutableStateOf(false) }

    when (val current = screen) {
        Screen.Home -> HomeScreen(onPick = { picked -> mode = picked; screen = Screen.List })
        Screen.List -> {
            BackHandler { screen = Screen.Home }
            DestinationListScreen(
                pkg = pkg,
                mode = mode,
                stepFree = stepFree,
                onStepFreeChange = { stepFree = it },
                onBack = { screen = Screen.Home },
                onNavigate = { screen = Screen.Navigate(it, if (stepFree) RouteProfile.STEP_FREE else RouteProfile.FASTEST) },
                onOpenSigns = { screen = Screen.Signs },
                onAddDestination = { screen = Screen.Capture },
                onDeleteDestination = { // deletes that path's file: its walk and objects go with it
                    store.delete(it.id)
                    paths = store.load(basePackage.venue.id)
                },
            )
        }
        Screen.Capture -> WayFinderCaptureScreen(
            pkg = pkg,
            onSave = { captured ->
                store.save(
                    ArPath(
                        venueId = basePackage.venue.id,
                        destination = CustomDestination(
                            id = "custom-" + UUID.randomUUID(),
                            name = captured.name, level = captured.level, x = captured.x, z = captured.z,
                            createdAtMillis = System.currentTimeMillis(), floorY = captured.floorY,
                        ),
                        walkedPoints = captured.path,
                        objects = captured.objects,
                        startMarkerId = captured.startMarkerId,
                        alignmentSpreadCm = captured.alignmentSpreadCm,
                        driftCorrectedCm = captured.driftCorrectedCm,
                    ),
                )
                paths = store.load(basePackage.venue.id)
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
                // Routes follow the destination's own path, and only its objects are shown.
                walkedPoints = paths.firstOrNull { it.destination.id == current.poi.id }?.walkedPoints.orEmpty(),
                placedObjects = paths.firstOrNull { it.destination.id == current.poi.id }?.objects.orEmpty(),
                recordedFromMarkerId = paths.firstOrNull { it.destination.id == current.poi.id }?.startMarkerId.orEmpty(),
                debug = true,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DestinationListScreen(
    pkg: VenuePackage,
    mode: AppMode,
    stepFree: Boolean,
    onStepFreeChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onNavigate: (Venue.POI) -> Unit,
    onOpenSigns: () -> Unit,
    onAddDestination: () -> Unit,
    onDeleteDestination: (Venue.POI) -> Unit,
) {
    val context = LocalContext.current
    val venue = pkg.venue
    val admin = mode == AppMode.ADMIN
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
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).statusBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("‹ Home") }
                }
                Text(
                    if (admin) "Edit place" else "Destinations",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.padding(4.dp))
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text("Search") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }

            if (admin) {
                item {
                    IosSection {
                        IosRow(title = "Add destination", glyph = "＋", subtitle = "Scan a sign, walk, save a spot", onClick = onAddDestination)
                    }
                }
            }

            item {
                IosSection(title = "Routing") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Step-free route", style = MaterialTheme.typography.bodyLarge)
                            Text("Avoids stairs & escalators", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = stepFree, onCheckedChange = onStepFreeChange)
                    }
                }
            }

            if (venue.pois.isEmpty()) {
                item {
                    Text(
                        if (admin) "No destinations yet. Tap Add destination." else "No destinations yet.",
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
                    IosSection(title = level.name.resolved()) {
                        pois.forEachIndexed { index, poi ->
                            POIRow(
                                poi = poi,
                                onClick = { start(poi) },
                                onLongClick = if (admin && poi.category == CustomDestination.CATEGORY) ({ deleting = poi }) else null,
                            )
                            if (index < pois.lastIndex) IosRowDivider()
                        }
                    }
                }
            }

            item {
                IosSection(title = "Help") {
                    IosRow(
                        title = "AR signs",
                        glyph = "🏷",
                        subtitle = if (admin) "Print & place markers" else "Where to scan",
                        onClick = onOpenSigns,
                    )
                }
                Spacer(Modifier.padding(8.dp))
            }
        }
    }

    deleting?.let { poi ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${poi.name.resolved()}”?") },
            text = { Text("Removes this saved destination from this phone.") },
            confirmButton = { TextButton(onClick = { onDeleteDestination(poi); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    if (unsupported) {
        AlertDialog(
            onDismissRequest = { unsupported = false },
            title = { Text("AR isn't supported on this device") },
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
        Text(glyph, style = MaterialTheme.typography.titleLarge)
        Column(Modifier.weight(1f)) {
            Text(poi.name.resolved(), style = MaterialTheme.typography.bodyLarge)
            if (poi.category == CustomDestination.CATEGORY && onLongClick != null) {
                Text("Hold to delete", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("AR ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Shows the venue's AR signs with where they belong, and lets testers share an image to print. */
@Composable
private fun MarkerGalleryScreen(pkg: VenuePackage, onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).statusBarsPadding()) {
            item {
                Row(Modifier.padding(start = 8.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("‹ Back") }
                }
                Text("AR signs", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
            }
            items(pkg.venue.markers, key = { it.id }) { marker ->
                val bitmap = remember(marker) { BitmapFactory.decodeFile(pkg.file(marker).path) }
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = marker.name.resolved(),
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                        )
                    }
                    Text(marker.name.resolved(), style = MaterialTheme.typography.titleMedium)
                    Text(placement(pkg, marker), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            }
        }
    }
}

private fun placement(pkg: VenuePackage, marker: Venue.Marker): String {
    val level = pkg.venue.level(marker.level)?.name?.resolved() ?: marker.level
    val mounting = if (marker.mounting == Venue.MarkerMounting.WALL) "wall · ${format(marker.y)} m high" else "floor"
    return "$level · ${format(marker.physicalWidth * 100)} cm · $mounting"
}

private fun format(value: Float): String = if (value.rounded() == value) value.toInt().toString() else "%.1f".format(value)

private fun Float.rounded(): Float = Math.round(this).toFloat()
