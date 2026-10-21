package com.app.androidnativearcore

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.app.androidnativearcore.ui.designkit.IosRow
import com.app.androidnativearcore.ui.designkit.IosRowDivider
import com.app.androidnativearcore.ui.designkit.IosSection
import com.app.androidnativearcore.wayfinder.ar.LocatedUser
import com.app.androidnativearcore.wayfinder.ar.NavigationFeedback
import com.app.androidnativearcore.wayfinder.ar.WayFinderCaptureScreen
import com.app.androidnativearcore.wayfinder.ar.WayFinderLocateScreen
import com.app.androidnativearcore.wayfinder.ar.WayFinderNavigationListener
import com.app.androidnativearcore.wayfinder.ar.WayFinderNavigationScreen
import com.app.androidnativearcore.wayfinder.core.ArPath
import com.app.androidnativearcore.wayfinder.core.ArPathStore
import com.app.androidnativearcore.wayfinder.core.CapturedMarkerStore
import com.app.androidnativearcore.wayfinder.core.CustomDestination
import com.app.androidnativearcore.wayfinder.core.LocalizedText
import com.app.androidnativearcore.wayfinder.core.REFERENCE_CATEGORY
import com.app.androidnativearcore.wayfinder.core.Route
import com.app.androidnativearcore.wayfinder.core.RouteProfile
import com.app.androidnativearcore.wayfinder.core.Venue
import com.app.androidnativearcore.wayfinder.core.VenuePackage
import com.app.androidnativearcore.wayfinder.core.withCustomDestinations
import com.app.androidnativearcore.wayfinder.core.withExtraMarkers
import com.app.androidnativearcore.wayfinder.core.withReferenceDestinations
import com.google.ar.core.ArCoreApk
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

private const val TAG = "WayFinderDemo"

private sealed interface Screen {
    data object Home : Screen
    data object List : Screen
    data object Signs : Screen
    data object Capture : Screen
    /** User flow, step 1: the camera recognizes a reference point. */
    data object Locate : Screen
    /** User flow, step 2: every other point reachable from where the user stands. */
    data class Nearby(val located: LocatedUser) : Screen
    /** [from] is where the user was found, when they came through the user flow; they return there afterwards. */
    data class Navigate(val poi: Venue.POI, val profile: RouteProfile, val from: LocatedUser? = null) : Screen
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
    // Reference points the admin captured are kept separately so they survive the bundled venue being re-copied from
    // assets, then merged back in as ordinary markers.
    val markerStore = remember { CapturedMarkerStore(File(context.filesDir, "captured-markers")) }
    var paths by remember { mutableStateOf(store.load(basePackage.venue.id)) }
    var extraMarkers by remember { mutableStateOf(markerStore.load(basePackage.venue.id)) }
    val customDestinations = paths.map { it.destination }
    val pkg = remember(basePackage, customDestinations, extraMarkers) {
        basePackage.withVenue(
            basePackage.venue.withCustomDestinations(customDestinations).withExtraMarkers(extraMarkers)
                .withReferenceDestinations(extraMarkers),
        )
    }
    // Every walk joined together: from any reference point, routes can follow any path walked from the entry.
    val allWalkedPoints = remember(paths) { paths.flatMap { it.walkedPoints } }
    val allPlacedObjects = remember(paths) { paths.flatMap { it.objects } }
    var mode by remember { mutableStateOf(AppMode.USER) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var stepFree by remember { mutableStateOf(false) }

    fun openLiveCamera() {
        val firstPoi = pkg.venue.pois.firstOrNull { it.category == CustomDestination.CATEGORY }
            ?: pkg.venue.pois.firstOrNull { it.category == REFERENCE_CATEGORY }
            ?: pkg.venue.pois.firstOrNull()
        if (firstPoi != null) {
            screen = Screen.Navigate(firstPoi, if (stepFree) RouteProfile.STEP_FREE else RouteProfile.FASTEST)
        } else {
            screen = Screen.Locate
        }
    }

    when (val current = screen) {
        Screen.Home -> HomeScreen(onPick = { picked ->
            mode = picked
            screen = Screen.List
        })
        Screen.Locate -> WayFinderLocateScreen(
            pkg = pkg,
            onLocated = { screen = Screen.Nearby(it) },
            onCancel = { screen = Screen.List },
        )
        is Screen.Nearby -> {
            BackHandler { screen = Screen.List }
            NearbyScreen(
                venue = pkg.venue,
                markers = pkg.venue.markers,
                walked = allWalkedPoints,
                located = current.located,
                onNavigate = { poi ->
                    screen = Screen.Navigate(poi, if (stepFree) RouteProfile.STEP_FREE else RouteProfile.FASTEST, current.located)
                },
                onRescan = { screen = Screen.Locate },
                onBack = { screen = Screen.List },
            )
        }
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
                onRenameDestination = { poi, newName ->
                    paths.firstOrNull { it.destination.id == poi.id }?.let { path ->
                        store.delete(poi.id)
                        store.save(path.copy(destination = path.destination.copy(name = newName)))
                        paths = store.load(basePackage.venue.id)
                    }
                },
                onDataChanged = {
                    paths = store.load(basePackage.venue.id)
                    extraMarkers = markerStore.load(basePackage.venue.id)
                },
                onClearMarkers = {
                    markerStore.clear()
                    extraMarkers = markerStore.load(basePackage.venue.id)
                },
                onOpenCamera = {
                    openLiveCamera()
                },
            )
        }
        Screen.Capture -> WayFinderCaptureScreen(
            pkg = pkg,
            existingWalkedPoints = allWalkedPoints,
            existingObjects = allPlacedObjects,
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
                // Keep the admin in the live camera view! Reload paths on exit so ARSceneView is not torn down mid-session.
            },
            onCancel = {
                // Reload saved destinations and reference points captured during this session when closing camera.
                paths = store.load(basePackage.venue.id)
                extraMarkers = markerStore.load(basePackage.venue.id)
                screen = Screen.List
            },
            onSaveReferencePoint = { ref ->
                val id = "ref-" + UUID.randomUUID()
                val imageFile = markerStore.imageFile(id)
                imageFile.outputStream().use { ref.image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                markerStore.save(
                    basePackage.venue.id,
                    Venue.Marker(
                        id = id,
                        name = LocalizedText(ref.name),
                        image = imageFile.absolutePath,
                        physicalWidth = ref.physicalWidth,
                        level = ref.level,
                        x = ref.x, y = ref.y, z = ref.z,
                        facing = ref.facing,
                        mounting = ref.mounting,
                    ),
                )
                // Don't refresh `extraMarkers` now: it would rebuild the AR session mid-capture. It reloads on exit.
            },
        )
        Screen.Signs -> {
            BackHandler { screen = Screen.List }
            MarkerGalleryScreen(
                pkg = pkg,
                onBack = { screen = Screen.List },
                onDeleteMarker = if (mode == AppMode.ADMIN) ({ id ->
                    markerStore.delete(id)
                    extraMarkers = markerStore.load(basePackage.venue.id)
                }) else null,
                onAddSign = if (mode == AppMode.ADMIN) ({ name, width, bmp ->
                    val id = "ref-" + UUID.randomUUID()
                    val imageFile = markerStore.imageFile(id)
                    imageFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    markerStore.save(
                        basePackage.venue.id,
                        Venue.Marker(
                            id = id, name = LocalizedText(name), image = imageFile.absolutePath,
                            physicalWidth = width,
                            level = basePackage.venue.levels.firstOrNull()?.id ?: "",
                            x = 0f, y = 1.4f, z = 0f, facing = 0f, mounting = Venue.MarkerMounting.WALL,
                        ),
                    )
                    extraMarkers = markerStore.load(basePackage.venue.id)
                }) else null,
            )
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
                        screen = current.from?.let { Screen.Nearby(it) } ?: Screen.List
                    }
                }
            }
            WayFinderNavigationScreen(
                pkg = pkg,
                destinationId = current.poi.id,
                profile = current.profile,
                listener = listener,
                walkedPoints = allWalkedPoints,
                placedObjects = allPlacedObjects,
                recordedFromMarkerId = "",
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
    onRenameDestination: (Venue.POI, String) -> Unit = { _, _ -> },
    onDataChanged: () -> Unit = {},
    onClearMarkers: () -> Unit = {},
    onOpenCamera: () -> Unit = {},
) {
    val context = LocalContext.current
    val venue = pkg.venue
    val admin = mode == AppMode.ADMIN
    val capturedCount = venue.markers.count { it.id.startsWith("ref-") }
    var search by remember { mutableStateOf("") }
    var unsupported by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Venue.POI?>(null) }
    var deleting by remember { mutableStateOf<Venue.POI?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var transferMsg by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val count = runCatching { DataTransfer.import(context, uri, context.filesDir) }.getOrElse { -1 }
            onDataChanged()
            transferMsg = if (count >= 0) "Loaded $count file(s) from the selected bundle." else "Couldn't read that file."
        }
    }

    fun exportData() {
        val dirs = listOf(File(context.filesDir, "paths"), File(context.filesDir, "captured-markers"))
        if (dirs.none { it.exists() && it.listFiles().orEmpty().any { f -> f.isFile } }) {
            transferMsg = "Nothing captured yet to save."
            return
        }
        val zip = runCatching { DataTransfer.export(context, dirs) }.getOrNull()
        if (zip == null) { transferMsg = "Couldn't create the export."; return }
        DataTransfer.share(context, zip)
    }

    fun start(poi: Venue.POI) {
        if (ArCoreApk.getInstance().checkAvailability(context) == ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE) {
            unsupported = true
        } else {
            onNavigate(poi)
        }
    }

    Scaffold(
        floatingActionButton = {
            if (!admin) {
                ExtendedFloatingActionButton(
                    text = { Text("Scan & Map") },
                    icon = { Text("📷") },
                    onClick = onOpenCamera,
                )
            }
        },
    ) { padding ->
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

            if (!admin) {
                item {
                    IosSection {
                        IosRow(
                            title = "Scan reference point",
                            glyph = "📷",
                            subtitle = "Match a reference point to see nearby list & 2D map",
                            onClick = onOpenCamera,
                        )
                    }
                }
            }

            if (admin) {
                item {
                    IosSection {
                        IosRow(title = "Add destination", glyph = "＋", subtitle = "Scan a sign, walk, save a spot", onClick = onAddDestination)
                        IosRowDivider()
                        IosRow(
                            title = "Scan reference point & 2D Map",
                            glyph = "🗺",
                            subtitle = "Test matching & view the floor map",
                            onClick = onOpenCamera,
                        )
                    }
                }
                item {
                    IosSection(title = "Data") {
                        IosRow(title = "Save data", glyph = "⬆", subtitle = "Export points & paths as a file", onClick = { exportData() })
                        IosRowDivider()
                        IosRow(title = "Load data", glyph = "⬇", subtitle = "Import a saved bundle", onClick = { importLauncher.launch(arrayOf("application/zip", "*/*")) })
                        IosRowDivider()
                        IosRow(
                            title = "Clear reference points",
                            glyph = "🗑",
                            subtitle = if (capturedCount > 0) "$capturedCount captured · removes all" else "none captured",
                            onClick = { if (capturedCount > 0) clearing = true },
                        )
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
                                onLongClick = if (admin && poi.category == CustomDestination.CATEGORY) ({ editing = poi }) else null,
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
                        subtitle = if (admin) "Print, add & manage markers" else "Where to scan",
                        onClick = onOpenSigns,
                    )
                }
                Spacer(Modifier.padding(8.dp))
            }
        }
    }

    editing?.let { poi ->
        var name by remember(poi) { mutableStateOf(poi.name.resolved()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit destination") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("Name") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && name.trim() != poi.name.resolved(),
                    onClick = { onRenameDestination(poi, name.trim()); editing = null },
                ) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { editing = null; deleting = poi }) { Text("Delete") }
                    TextButton(onClick = { editing = null }) { Text("Cancel") }
                }
            },
        )
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

    transferMsg?.let { msg ->
        AlertDialog(
            onDismissRequest = { transferMsg = null },
            title = { Text("Data") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { transferMsg = null }) { Text("OK") } },
        )
    }

    if (clearing) {
        AlertDialog(
            onDismissRequest = { clearing = false },
            title = { Text("Clear all reference points?") },
            text = { Text("Removes all $capturedCount captured reference images from this phone. Recorded paths are kept.") },
            confirmButton = { TextButton(onClick = { onClearMarkers(); clearing = false }) { Text("Clear all") } },
            dismissButton = { TextButton(onClick = { clearing = false }) { Text("Cancel") } },
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
                Text("Hold to edit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("AR ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Shows the venue's AR signs with where they belong, and lets testers share an image to print. */
@Composable
private fun MarkerGalleryScreen(
    pkg: VenuePackage,
    onBack: () -> Unit,
    onDeleteMarker: ((String) -> Unit)? = null,
    onAddSign: ((name: String, widthMeters: Float, image: Bitmap) -> Unit)? = null,
) {
    val context = LocalContext.current
    var picked by remember { mutableStateOf<Bitmap?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    fun decode(uri: Uri?): Bitmap? {
        uri ?: return null
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        // Camera/gallery images carry an EXIF orientation that BitmapFactory doesn't apply, so correct it here —
        // otherwise the sign would be stored and compared sideways.
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        return if (degrees == 0f) bmp
        else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
    }
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) picked = decode(uri)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) picked = decode(cameraUri)
    }
    fun launchCamera() {
        val file = File(context.cacheDir, "sign-capture-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        cameraUri = uri
        cameraLauncher.launch(uri)
    }
    Scaffold(
        floatingActionButton = {
            if (onAddSign != null) {
                ExtendedFloatingActionButton(
                    text = { Text("Add sign") },
                    icon = { Text("＋") },
                    onClick = { choosing = true },
                )
            }
        },
    ) { padding ->
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pkg.file(marker))
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "image/png"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, marker.name.resolved()))
                        }) { Text("Share image") }
                        if (onDeleteMarker != null && marker.id.startsWith("ref-")) {
                            TextButton(onClick = { onDeleteMarker(marker.id) }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }

    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Add AR sign") },
            text = { Text("Capture a photo now, or pick an existing image.") },
            confirmButton = { TextButton(onClick = { choosing = false; launchCamera() }) { Text("Camera") } },
            dismissButton = { TextButton(onClick = { choosing = false; pickLauncher.launch("image/*") }) { Text("Gallery") } },
        )
    }

    picked?.let { bitmap ->
        var name by remember(bitmap) { mutableStateOf("") }
        var widthCm by remember(bitmap) { mutableStateOf("20") }
        AlertDialog(
            onDismissRequest = { picked = null },
            title = { Text("Add AR sign") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Image(
                        bitmap = bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)),
                    )
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
                    OutlinedTextField(
                        value = widthCm, onValueChange = { widthCm = it.filter(Char::isDigit).take(4) },
                        singleLine = true, label = { Text("Printed width (cm)") },
                    )
                    Text("Position isn't set — scan this sign in capture to place it accurately.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                val cm = widthCm.toFloatOrNull() ?: 0f
                TextButton(
                    enabled = name.isNotBlank() && cm > 0f && onAddSign != null,
                    onClick = { onAddSign?.invoke(name.trim(), cm / 100f, bitmap); picked = null },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { picked = null }) { Text("Cancel") } },
        )
    }
}

private fun placement(pkg: VenuePackage, marker: Venue.Marker): String {
    val level = pkg.venue.level(marker.level)?.name?.resolved() ?: marker.level
    val mounting = if (marker.mounting == Venue.MarkerMounting.WALL) "wall · ${format(marker.y)} m high" else "floor"
    return "$level · ${format(marker.physicalWidth * 100)} cm · $mounting"
}

private fun format(value: Float): String = if (value.rounded() == value) value.toInt().toString() else "%.1f".format(value)

private fun Float.rounded(): Float = Math.round(this).toFloat()
