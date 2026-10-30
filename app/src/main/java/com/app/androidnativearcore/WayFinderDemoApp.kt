package com.app.androidnativearcore

import android.bluetooth.BluetoothAdapter
import android.provider.Settings
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.app.androidnativearcore.ble.BeaconLocator
import com.app.androidnativearcore.ble.BleScanner
import com.app.androidnativearcore.wayfinder.core.BeaconFix
import kotlinx.coroutines.delay
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.app.androidnativearcore.ui.designkit.IosRow
import com.app.androidnativearcore.ui.designkit.IosRowDivider
import com.app.androidnativearcore.ui.designkit.IosSection
import com.app.androidnativearcore.wayfinder.ar.LocatedUser
import com.app.androidnativearcore.wayfinder.ar.NavigationFeedback
import com.app.androidnativearcore.wayfinder.ar.WayFinderBeaconPathScreen
import com.app.androidnativearcore.wayfinder.ar.WayFinderCaptureScreen
import com.app.androidnativearcore.wayfinder.ar.WayFinderLocateScreen
import com.app.androidnativearcore.wayfinder.ar.WayFinderNavigationListener
import com.app.androidnativearcore.wayfinder.ar.WayFinderNavigationScreen
import com.app.androidnativearcore.wayfinder.core.ArPath
import com.app.androidnativearcore.wayfinder.core.ArPathStore
import com.app.androidnativearcore.wayfinder.core.BeaconStore
import com.app.androidnativearcore.wayfinder.core.RegisteredBeacon
import com.app.androidnativearcore.wayfinder.core.CapturedMarkerStore
import com.app.androidnativearcore.wayfinder.core.CustomDestination
import com.app.androidnativearcore.wayfinder.core.LocalizedText
import com.app.androidnativearcore.wayfinder.core.PlacedObject
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
    /** Admin: registering, editing and deleting Bluetooth beacons. */
    data object Beacons : Screen
    /** Admin: the camera finds where the beacon being registered is on the map. */
    data object PickBeaconPosition : Screen
    data class Capture(val fromRefId: String? = null, val connectTargetId: String? = null) : Screen
    /** User flow, step 1: the camera recognizes a reference point. */
    data object Locate : Screen
    /** User flow, step 2: every other point reachable from where the user stands. */
    data class Nearby(val located: LocatedUser?) : Screen
    /** User flow without a reference point: the camera, with Bluetooth beacons guiding to a chosen beacon. */
    data object BeaconPath : Screen
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

/**
 * The demo app. [incomingBundle] is a saved data bundle (.zip) the app was opened with from a file manager or shared
 * to; the user is asked before it is imported, and [onIncomingHandled] is called once that is settled.
 */
@Composable
fun WayFinderDemoApp(incomingBundle: Uri? = null, onIncomingHandled: () -> Unit = {}) {
    val context = LocalContext.current
    val venue = remember { DemoVenue.load(context) }
    venue.fold(
        onSuccess = { WayFinderHost(it, incomingBundle, onIncomingHandled) },
        onFailure = {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("Venue data can't be loaded", style = MaterialTheme.typography.titleMedium)
                Text(it.message ?: "", style = MaterialTheme.typography.bodyMedium)
            }
        },
    )
}

@Composable
private fun WayFinderHost(basePackage: VenuePackage, incomingBundle: Uri?, onIncomingHandled: () -> Unit) {
    val context = LocalContext.current
    // Every path the user recorded is its own JSON file in `paths/`: the destination, the walk to it and the objects
    // along it. Destinations show up in the list as ordinary POIs.
    val store = remember { ArPathStore(File(context.filesDir, "paths")) }
    // Reference points the admin captured are kept separately so they survive the bundled venue being re-copied from
    // assets, then merged back in as ordinary markers.
    val markerStore = remember { CapturedMarkerStore(File(context.filesDir, "captured-markers")) }
    // Bluetooth beacons the admin registered, one JSON file per beacon in `beacons/`.
    val beaconStore = remember { BeaconStore(File(context.filesDir, "beacons")) }
    var beacons by remember { mutableStateOf(beaconStore.load(basePackage.venue.id)) }
    // The beacon form waiting while the camera finds the beacon's position, and whether it is a new beacon.
    var beaconDraft by remember { mutableStateOf<Pair<RegisteredBeacon, Boolean>?>(null) }
    var paths by remember { mutableStateOf(store.load(basePackage.venue.id)) }
    var extraMarkers by remember { mutableStateOf(markerStore.load(basePackage.venue.id)) }
    val customDestinations = paths.map { it.destination }
    val pkg = remember(basePackage, customDestinations, extraMarkers, paths) {
        // The bundled demo signs are placed in the demo mall's own map. Once this phone has its own map (reference
        // points captured here or imported), a demo sign that no saved path was recorded from would put the user at
        // a demo-mall position, unrelated to their paths ("your starting point isn't on any recorded path"), so
        // only the demo signs that a path actually starts from are kept.
        val usedSigns = paths.map { it.startMarkerId }.toSet()
        val signs = if (extraMarkers.isEmpty()) basePackage.venue.markers else basePackage.venue.markers.filter { it.id in usedSigns }
        basePackage.withVenue(
            basePackage.venue.copy(markers = signs).withCustomDestinations(customDestinations).withExtraMarkers(extraMarkers)
                .withReferenceDestinations(extraMarkers),
        )
    }
    // A bundle opened from a file manager or shared to the app: imported only after the user agrees, since it merges
    // into (and can replace) what is saved on this phone.
    var importResult by remember { mutableStateOf<String?>(null) }
    if (incomingBundle != null) {
        AlertDialog(
            onDismissRequest = onIncomingHandled,
            title = { Text("Import data?") },
            text = { Text("Add the paths and reference points in this file to this phone? Ones with the same name are replaced.") },
            confirmButton = {
                TextButton(onClick = {
                    importResult = DataTransfer.importWithSummary(context, incomingBundle, basePackage.venue.id)
                    paths = store.load(basePackage.venue.id)
                    extraMarkers = markerStore.load(basePackage.venue.id)
                    onIncomingHandled()
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = onIncomingHandled) { Text("Cancel") } },
        )
    }
    importResult?.let { message ->
        AlertDialog(
            onDismissRequest = { importResult = null },
            title = { Text("Data") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { importResult = null }) { Text("OK") } },
        )
    }

    // Every walk joined together: from any reference point, routes can follow any path walked from the entry.
    val allWalkedPoints = remember(paths) { paths.flatMap { it.walkedPoints } }
    val allPlacedObjects = remember(paths) { paths.flatMap { it.objects } }
    var mode by remember { mutableStateOf(AppMode.USER) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var stepFree by remember { mutableStateOf(false) }

    /** The camera button: the scan screen, which recognizes a reference point first and then lists what is near. */
    fun openLiveCamera() {
        screen = Screen.Locate
    }

    // A reference point saved without a size (as the iPhone app exports them) gets the size ARCore measured for it,
    // so next time it is recognized at once instead of only while the phone moves. Written to disk only: reloading it
    // now would restart the camera; it is picked up when the list is shown again.
    fun saveMeasuredSize(markerId: String, width: Float) {
        markerStore.load(basePackage.venue.id).firstOrNull { it.id == markerId && it.physicalWidth <= 0f }?.let {
            markerStore.save(basePackage.venue.id, it.copy(physicalWidth = width))
            Log.i(TAG, "Saved the measured width %.2f m for reference point %s".format(width, markerId))
        }
    }
    LaunchedEffect(screen) {
        if (screen == Screen.List || screen == Screen.Home) extraMarkers = markerStore.load(basePackage.venue.id)
    }

    // User mode: find the visitor by the registered Bluetooth beacons, automatically while a user screen is open and
    // the app is in front (scanning stops in the background and in Admin, to save battery).
    val beaconLocator = remember { BeaconLocator(BleScanner(context)) }
    val hostView = LocalView.current
    var appVisible by remember { mutableStateOf(true) }
    DisposableEffect(hostView) {
        val owner = hostView.findViewTreeLifecycleOwner()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) appVisible = true
            if (event == Lifecycle.Event.ON_STOP) appVisible = false
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose {
            owner?.lifecycle?.removeObserver(observer)
            beaconLocator.stop()
        }
    }
    val onUserScreen = mode == AppMode.USER &&
        (screen == Screen.List || screen == Screen.Locate || screen is Screen.Nearby || screen is Screen.Navigate || screen == Screen.BeaconPath)
    val locating = onUserScreen && appVisible && beacons.isNotEmpty()
    val registeredBeacons by rememberUpdatedState(beacons)
    LaunchedEffect(locating) {
        if (!locating) {
            beaconLocator.stop()
            return@LaunchedEffect
        }
        beaconLocator.recheck()
        if (beaconLocator.problem == null) beaconLocator.start()
        while (true) {
            beaconLocator.update(registeredBeacons)
            delay(500)
        }
    }
    // Asking for what scanning needs, when the visitor taps the beacon card.
    val beaconPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        beaconLocator.recheck()
        if (beaconLocator.problem == null) beaconLocator.start()
    }
    val beaconSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        beaconLocator.recheck()
        if (beaconLocator.problem == null) beaconLocator.start()
    }
    fun fixBeaconAccess() {
        beaconLocator.recheck()
        when (beaconLocator.problem) {
            BleScanner.Problem.PERMISSION_MISSING -> beaconPermissionLauncher.launch(beaconLocator.scanner.requiredPermissions())
            BleScanner.Problem.BLUETOOTH_OFF -> beaconSettingsLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            BleScanner.Problem.LOCATION_OFF -> beaconSettingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            BleScanner.Problem.NO_BLUETOOTH_LE -> Unit
            null -> beaconLocator.start()
        }
    }
    /** Why the beacons can't be heard, for the Beacon path screen, or null. */
    fun beaconProblemText(): String? = when {
        beacons.isEmpty() -> "No beacons are registered yet. The admin registers them in Bluetooth Beacon Management."
        else -> when (beaconLocator.problem) {
            BleScanner.Problem.BLUETOOTH_OFF -> "Turn on Bluetooth to find where you are."
            BleScanner.Problem.LOCATION_OFF -> "Turn on location: Android needs it to scan for beacons."
            BleScanner.Problem.PERMISSION_MISSING -> "Allow the app to find nearby Bluetooth devices."
            BleScanner.Problem.NO_BLUETOOTH_LE -> "This phone can't use Bluetooth beacons."
            null -> null
        }
    }

    /** "Near <beacon> · <floor>" for the scan screen, or null while no registered beacon is heard. */
    fun beaconHint(): String? = beaconLocator.fix?.let { fix ->
        val floor = pkg.venue.level(fix.beacon.floorId)?.name?.resolved() ?: fix.beacon.floorId
        "📶 Near ${fix.beacon.name} · $floor"
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
            onReferenceSizeMeasured = ::saveMeasuredSize,
            beaconHint = beaconHint(),
        )
        Screen.BeaconPath -> WayFinderBeaconPathScreen(
            venue = pkg.venue,
            beacons = beacons,
            fix = beaconLocator.fix,
            position = beaconLocator.position,
            heard = beaconLocator.heard,
            listenToReadings = beaconLocator::listenToReadings,
            problem = beaconProblemText(),
            onFixProblem = ::fixBeaconAccess.takeIf {
                beacons.isNotEmpty() && beaconLocator.problem != BleScanner.Problem.NO_BLUETOOTH_LE
            },
            onClose = { screen = Screen.List },
            // The rule tests are for checking on a device during development, not for visitors.
            testPanel = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0,
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
                // Without a scanned reference point, the nearest Bluetooth beacon is the starting point.
                beaconFix = if (current.located == null) beaconLocator.fix else null,
                beaconPosition = if (current.located == null) beaconLocator.position else null,
                beacons = beacons,
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
                onOpenBeacons = { screen = Screen.Beacons },
                beaconCount = beacons.size,
                beaconFix = beaconLocator.fix,
                beaconProblem = if (mode == AppMode.USER && beacons.isNotEmpty()) beaconLocator.problem else null,
                hasBeacons = beacons.isNotEmpty(),
                onFixBeaconAccess = ::fixBeaconAccess,
                onOpenBeaconNearby = { screen = Screen.Nearby(null) },
                onOpenBeaconPath = { screen = Screen.BeaconPath },
                onAddDestination = { screen = Screen.Capture() },
                onWalkAndConnect = { fromRefId, targetId ->
                    screen = Screen.Capture(fromRefId = fromRefId, connectTargetId = targetId)
                },
                placedObjects = allPlacedObjects,
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
        is Screen.Capture -> WayFinderCaptureScreen(
            pkg = pkg,
            onReferenceSizeMeasured = ::saveMeasuredSize,
            existingWalkedPoints = allWalkedPoints,
            existingObjects = allPlacedObjects,
            beacons = beacons,
            initialFromRefId = current.fromRefId,
            initialConnectTargetId = current.connectTargetId,
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
            onUpdateReferencePointCoords = { id, x, y, z ->
                markerStore.load(basePackage.venue.id).firstOrNull { it.id == id }?.let { existing ->
                    markerStore.save(basePackage.venue.id, existing.copy(x = x, y = y, z = z))
                }
            },
            onDeleteReferencePoint = { id, name ->
                if (id.isNotEmpty()) {
                    markerStore.delete(id)
                } else {
                    markerStore.load(basePackage.venue.id)
                        .filter { it.name.resolved() == name }
                        .forEach { markerStore.delete(it.id) }
                }
            },
            onDeleteObject = { objId ->
                store.load(basePackage.venue.id).forEach { path ->
                    if (path.objects.any { it.id == objId }) {
                        store.save(path.copy(objects = path.objects.filterNot { it.id == objId }))
                    }
                }
            },
            onOpenSigns = {
                paths = store.load(basePackage.venue.id)
                extraMarkers = markerStore.load(basePackage.venue.id)
                screen = Screen.Signs
            },
        )
        Screen.Beacons -> {
            BackHandler { screen = Screen.List }
            BeaconManagementScreen(
                venue = pkg.venue,
                beacons = beacons,
                onSave = { beacon ->
                    beaconStore.save(beacon)
                    beacons = beaconStore.load(basePackage.venue.id)
                },
                onDelete = { beacon ->
                    beaconStore.delete(beacon.beaconId)
                    beacons = beaconStore.load(basePackage.venue.id)
                },
                onBack = { screen = Screen.List },
                onPickPosition = { draft, isNew ->
                    beaconDraft = draft to isNew
                    screen = Screen.PickBeaconPosition
                },
                reopen = beaconDraft?.takeIf { screen == Screen.Beacons },
                onReopened = { beaconDraft = null },
            )
        }
        Screen.PickBeaconPosition -> WayFinderLocateScreen(
            pkg = pkg,
            pickPosition = true,
            pickTarget = "the beacon",
            onLocated = { here ->
                // The beacon is where the phone was held: its floor, and X / Y on the floor plan (the map's z).
                beaconDraft = beaconDraft?.let { (beacon, isNew) ->
                    beacon.copy(floorId = here.level, x = here.x, y = here.z) to isNew
                }
                screen = Screen.Beacons
            },
            onCancel = { screen = Screen.Beacons },
            onReferenceSizeMeasured = ::saveMeasuredSize,
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
                onReferenceSizeMeasured = ::saveMeasuredSize,
                destinationId = current.poi.id,
                profile = current.profile,
                listener = listener,
                walkedPoints = allWalkedPoints,
                placedObjects = allPlacedObjects,
                // The reference point or sign this destination's path was recorded from: navigation then trusts only
                // that one (and other reference points of the same map), not an unrelated sign.
                recordedFromMarkerId = paths.firstOrNull { path ->
                    path.destination.id == current.poi.id || path.objects.any { "objpoi-${it.id}" == current.poi.id }
                }?.startMarkerId.orEmpty(),
                guideMarkerId = current.from?.marker?.id.orEmpty(),
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
    onOpenBeacons: () -> Unit = {},
    beaconCount: Int = 0,
    /** User mode: the registered beacon the visitor is nearest to, if any is heard. */
    beaconFix: BeaconFix? = null,
    /** User mode: what stops beacon scanning, if anything. */
    beaconProblem: BleScanner.Problem? = null,
    hasBeacons: Boolean = false,
    onFixBeaconAccess: () -> Unit = {},
    /** User mode: routes from the nearest beacon to everything reachable ("From here"). */
    onOpenBeaconNearby: () -> Unit = {},
    /** User mode: the camera guided by Bluetooth beacons alone, without a reference point. */
    onOpenBeaconPath: () -> Unit = {},
    onAddDestination: () -> Unit,
    onWalkAndConnect: (fromRefId: String?, targetId: String?) -> Unit = { _, _ -> },
    placedObjects: List<PlacedObject> = emptyList(),
    onDeleteDestination: (Venue.POI) -> Unit,
    onRenameDestination: (Venue.POI, String) -> Unit = { _, _ -> },
    onDataChanged: () -> Unit = {},
    onClearMarkers: () -> Unit = {},
    onOpenCamera: () -> Unit = {},
) {
    val context = LocalContext.current
    val venue = pkg.venue
    val admin = mode == AppMode.ADMIN
    val refMarkers = remember(venue.markers) { venue.markers.filter { it.id.startsWith("ref-") } }
    val customPois = remember(venue.pois) { venue.pois.filter { it.category == CustomDestination.CATEGORY } }
    val capturedCount = refMarkers.size
    var search by remember { mutableStateOf("") }
    var unsupported by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Venue.POI?>(null) }
    var deleting by remember { mutableStateOf<Venue.POI?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var transferMsg by remember { mutableStateOf<String?>(null) }
    var showConnectDialog by remember { mutableStateOf(false) }
    var selectedFromRefId by remember(refMarkers) { mutableStateOf(refMarkers.firstOrNull()?.id) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            transferMsg = DataTransfer.importWithSummary(context, uri, venue.id)
            onDataChanged()
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

            if (!admin && hasBeacons) {
                item(key = "beacon-location") {
                    // Where the visitor is, from the nearest registered Bluetooth beacon: a coarse first estimate.
                    IosSection(title = "Your location (Bluetooth)") {
                        when {
                            beaconFix != null -> {
                                val floor = venue.level(beaconFix.beacon.floorId)?.name?.resolved() ?: beaconFix.beacon.floorId
                                val howClose = beaconFix.howNear
                                // Routes and the 2D map need the beacon's position; without one, Beacon path guides.
                                val placed = beaconFix.beacon.hasPosition
                                IosRow(
                                    title = "You're near ${beaconFix.beacon.name}",
                                    glyph = "📶",
                                    subtitle = "$floor · $howClose · " + if (placed) "tap for routes from here" else "tap for Beacon path",
                                    trailing = "${beaconFix.rssi} dBm",
                                    onClick = if (placed) onOpenBeaconNearby else onOpenBeaconPath,
                                )
                            }
                            beaconProblem == BleScanner.Problem.NO_BLUETOOTH_LE -> IosRow(
                                title = "This phone can't use Bluetooth beacons",
                                glyph = "📶",
                                subtitle = "Scan a reference point instead",
                                showChevron = false,
                            )
                            beaconProblem != null -> IosRow(
                                title = when (beaconProblem) {
                                    BleScanner.Problem.BLUETOOTH_OFF -> "Turn on Bluetooth to find where you are"
                                    BleScanner.Problem.LOCATION_OFF -> "Turn on location to find where you are"
                                    else -> "Allow Bluetooth to find where you are"
                                },
                                glyph = "📶",
                                subtitle = "Uses the Bluetooth beacons in this place",
                                onClick = onFixBeaconAccess,
                            )
                            else -> IosRow(
                                title = "Looking for beacons…",
                                glyph = "📶",
                                subtitle = "Keep Bluetooth on; this takes a few seconds",
                                showChevron = false,
                            )
                        }
                    }
                }
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
                        IosRowDivider()
                        IosRow(
                            title = "Beacon path",
                            glyph = "📡",
                            subtitle = "Open the camera: Bluetooth beacons find where you are, no reference point needed",
                            onClick = onOpenBeaconPath,
                        )
                    }
                }
            }

            if (admin) {
                item {
                    IosSection {
                        IosRow(
                            title = "Connect to a destination",
                            glyph = "🔗",
                            subtitle = "Walk from a reference point to a destination ($capturedCount ref pts · ${customPois.size} destinations)",
                            onClick = { showConnectDialog = true },
                        )
                        IosRowDivider()
                        IosRow(title = "Add destination", glyph = "＋", subtitle = "Scan a sign, walk, save a spot", onClick = onAddDestination)
                        IosRowDivider()
                        IosRow(
                            title = "Scan reference point & 2D Map",
                            glyph = "🗺",
                            subtitle = "Test matching & view the floor map",
                            onClick = onOpenCamera,
                        )
                        IosRowDivider()
                        IosRow(
                            title = "Bluetooth Beacon Management",
                            glyph = "📶",
                            subtitle = if (beaconCount == 0) "Register beacons and their positions"
                            else "$beaconCount registered · register, edit, delete",
                            onClick = onOpenBeacons,
                        )
                    }
                }
                // Admin sees only the destinations here; reference points and objects are not listed.
                if (customPois.isNotEmpty()) {
                    item {
                        IosSection(title = "Destinations (walk & connect)") {
                            val fromMarker = refMarkers.firstOrNull { it.id == selectedFromRefId } ?: refMarkers.firstOrNull()
                            val fromLabel = fromMarker?.name?.resolved() ?: "Start point"
                            customPois.forEachIndexed { idx, poi ->
                                IosRow(
                                    title = "⭐ ${poi.name.resolved()}",
                                    glyph = "🔗",
                                    subtitle = "Tap to walk & connect from 📍 $fromLabel → ⭐ ${poi.name.resolved()}",
                                    onClick = {
                                        onWalkAndConnect(fromMarker?.id, poi.id)
                                    },
                                )
                                if (idx < customPois.lastIndex) IosRowDivider()
                            }
                        }
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
                // Reference points are not listed here (only real destinations); a floor with none left is hidden.
                val pois = venue.pois
                    .filter { it.level == level.id && it.category != REFERENCE_CATEGORY }
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

    if (showConnectDialog) {
        AlertDialog(
            onDismissRequest = { showConnectDialog = false },
            title = { Text("Walk & Connect to a Destination") },
            text = {
                Column(
                    Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "1. Pick Starting Reference Point (From):",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                    if (refMarkers.isEmpty()) {
                        Text(
                            "No reference points captured yet — open the camera and tap ◎ to capture reference points first.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        refMarkers.forEach { m ->
                            val isSelected = m.id == selectedFromRefId
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Color(0x220A84FF) else Color.Transparent)
                                    .clickable { selectedFromRefId = m.id }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "📍 ${m.name.resolved()}",
                                    color = Color(0xFF0A84FF),
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                )
                                Text(
                                    if (isSelected) "✓ Start From" else "Select as Start",
                                    fontSize = 12.sp,
                                    color = if (isSelected) Color(0xFF0A84FF) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }

                    Text(
                        "2. Tap the Destination to Walk & Connect To:",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    val fromName = refMarkers.firstOrNull { it.id == selectedFromRefId }?.name?.resolved() ?: "Start point"
                    if (customPois.isEmpty()) {
                        Text(
                            "No destinations yet. Tap Add destination to record one.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        customPois.forEach { poi ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0x1834C759))
                                    .clickable {
                                        showConnectDialog = false
                                        onWalkAndConnect(selectedFromRefId, poi.id)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "⭐ ${poi.name.resolved()}",
                                        color = Color(0xFFFF9500),
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        "Walk from 📍 $fromName → ⭐ ${poi.name.resolved()}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text("🔗 Walk & Connect", color = Color(0xFF34C759), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConnectDialog = false
                        val defaultTarget = customPois.firstOrNull()?.id
                            ?: refMarkers.firstOrNull { it.id != selectedFromRefId }?.id
                        onWalkAndConnect(selectedFromRefId, defaultTarget)
                    },
                ) { Text("Open Camera") }
            },
            dismissButton = {
                TextButton(onClick = { showConnectDialog = false }) { Text("Close") }
            },
        )
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
                        val ratio = remember(bitmap) {
                            (bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)).coerceIn(0.4f, 2.5f)
                        }
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = marker.name.resolved(),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp)
                                .aspectRatio(ratio)
                                .clip(RoundedCornerShape(12.dp)),
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
        val ratio = remember(bitmap) {
            (bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)).coerceIn(0.4f, 2.5f)
        }
        AlertDialog(
            onDismissRequest = { picked = null },
            title = { Text("Add AR sign") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .heightIn(max = 180.dp)
                                .aspectRatio(ratio)
                                .clip(RoundedCornerShape(10.dp)),
                        )
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text("Name (required)") },
                        placeholder = { Text("e.g. Room door sign") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = widthCm,
                        onValueChange = { widthCm = it.filter(Char::isDigit).take(4) },
                        singleLine = true,
                        label = { Text("Physical width (cm)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Position isn't set — scan this sign in capture to place it accurately.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                val cm = (widthCm.toFloatOrNull() ?: 20f).coerceAtLeast(1f)
                val resolvedName = name.trim().ifEmpty { "Sign ${pkg.venue.markers.size + 1}" }
                TextButton(
                    enabled = onAddSign != null,
                    onClick = { onAddSign?.invoke(resolvedName, cm / 100f, bitmap); picked = null },
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
