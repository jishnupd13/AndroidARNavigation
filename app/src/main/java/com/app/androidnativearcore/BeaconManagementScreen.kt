package com.app.androidnativearcore

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Switch
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.app.androidnativearcore.ble.BleScanner
import com.app.androidnativearcore.ble.NearbyScan
import com.app.androidnativearcore.wayfinder.core.BeaconIdentity
import com.app.androidnativearcore.wayfinder.core.NearbyDevice
import com.app.androidnativearcore.wayfinder.core.matches
import kotlinx.coroutines.delay
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.app.androidnativearcore.ui.designkit.IosRow
import com.app.androidnativearcore.ui.designkit.IosRowDivider
import com.app.androidnativearcore.ui.designkit.IosSection
import com.app.androidnativearcore.wayfinder.core.Beacons
import com.app.androidnativearcore.wayfinder.core.RegisteredBeacon
import com.app.androidnativearcore.wayfinder.core.Venue

/**
 * Admin: Bluetooth beacon management. Lists the registered beacons by floor; "Register beacon" adds one, tapping a
 * beacon edits or deletes it. A beacon is registered with its identifiers (iBeacon UUID, Major, Minor, or a Bluetooth
 * address), the floor it is on and its fixed X/Y position on the floor plan in meters.
 *
 * "Scan nearby" lists the Bluetooth devices around, strongest signal first, with a smoothed signal strength and a
 * rough distance; tapping one registers it with its identifiers filled in (or edits it, if it is registered already).
 */
@Composable
fun BeaconManagementScreen(
    venue: Venue,
    beacons: List<RegisteredBeacon>,
    onSave: (RegisteredBeacon) -> Unit,
    onDelete: (RegisteredBeacon) -> Unit,
    onBack: () -> Unit,
    /** Opens the camera to find the beacon's position, with the form as typed so far and whether it is new. */
    onPickPosition: (RegisteredBeacon, Boolean) -> Unit = { _, _ -> },
    /** A form to open again (coming back from the camera, with the position filled in), and whether it is new. */
    reopen: Pair<RegisteredBeacon, Boolean>? = null,
    onReopened: () -> Unit = {},
) {
    // The beacon being edited, or a new blank one while registering; null when no editor is open.
    var editing by remember { mutableStateOf<RegisteredBeacon?>(null) }
    var isNew by remember { mutableStateOf(false) }
    LaunchedEffect(reopen) {
        reopen?.let { (beacon, new) ->
            editing = beacon
            isNew = new
            onReopened()
        }
    }

    // Scanning for nearby devices: the scanner lives outside the UI; this screen only starts, stops and shows it.
    val context = LocalContext.current
    val scanner = remember { BleScanner(context) }
    val scan = remember { NearbyScan(scanner) }
    var beaconsOnly by remember { mutableStateOf(true) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        scan.recheck()
        if (scan.problem == null) scan.start()
    }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        scan.recheck()
        if (scan.problem == null) scan.start()
    }
    fun startScan() {
        scan.recheck()
        when (scan.problem) {
            BleScanner.Problem.PERMISSION_MISSING -> permissionLauncher.launch(scanner.requiredPermissions())
            BleScanner.Problem.BLUETOOTH_OFF -> settingsLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            BleScanner.Problem.LOCATION_OFF -> settingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            BleScanner.Problem.NO_BLUETOOTH_LE -> Unit
            null -> scan.start()
        }
    }
    // Refresh the smoothed list twice a second while scanning.
    LaunchedEffect(scan.scanning) {
        while (scan.scanning) {
            scan.refresh()
            delay(500)
        }
        scan.refresh()
    }
    // Stop scanning when the screen closes or the app goes to the background (scanning drains the battery).
    val view = LocalView.current
    DisposableEffect(view) {
        val owner = view.findViewTreeLifecycleOwner()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) scan.stop() }
        owner?.lifecycle?.addObserver(observer)
        onDispose {
            owner?.lifecycle?.removeObserver(observer)
            scan.stop()
        }
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("Register beacon") },
                icon = { Text("＋") },
                onClick = {
                    isNew = true
                    editing = RegisteredBeacon(
                        beaconId = Beacons.nextId(beacons), name = "", buildingId = venue.id,
                        floorId = venue.levels.firstOrNull()?.id.orEmpty(), x = Float.NaN, y = Float.NaN,
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).statusBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(Modifier.padding(start = 8.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("‹ Back") }
                }
                Text(
                    "Bluetooth beacons",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Text(
                    "Register each beacon with its identifiers, its floor and where it is on the floor plan.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            item(key = "nearby") {
                NearbySection(
                    scan = scan,
                    beaconsOnly = beaconsOnly,
                    onBeaconsOnlyChange = { beaconsOnly = it },
                    registered = beacons,
                    onStart = ::startScan,
                    onStop = scan::stop,
                    onPick = { device ->
                        val known = beacons.firstOrNull { it.matches(device) }
                        if (known != null) {
                            isNew = false
                            editing = known
                        } else {
                            isNew = true
                            val iBeacon = device.identity as? BeaconIdentity.IBeacon
                            editing = RegisteredBeacon(
                                beaconId = Beacons.nextId(beacons),
                                name = device.name.orEmpty(),
                                uuid = iBeacon?.uuid.orEmpty(),
                                major = iBeacon?.major,
                                minor = iBeacon?.minor,
                                macAddress = device.address,
                                buildingId = venue.id,
                                floorId = venue.levels.firstOrNull()?.id.orEmpty(),
                                x = Float.NaN, y = Float.NaN,
                            )
                        }
                    },
                )
            }
            if (beacons.isEmpty()) {
                item {
                    Text(
                        "No beacons registered yet. Tap Register beacon.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            for (level in venue.levels) {
                val onFloor = beacons.filter { it.floorId == level.id }.sortedBy { it.name.lowercase() }
                if (onFloor.isEmpty()) continue
                item(key = "floor-${level.id}") {
                    IosSection(title = "${level.name.resolved()} · ${onFloor.size} beacon${if (onFloor.size == 1) "" else "s"}") {
                        onFloor.forEachIndexed { index, beacon ->
                            IosRow(
                                title = beacon.name,
                                glyph = "📶",
                                subtitle = "${beacon.identityLabel} · X %.1f, Y %.1f m".format(beacon.x, beacon.y),
                                onClick = { isNew = false; editing = beacon },
                            )
                            if (index < onFloor.lastIndex) IosRowDivider()
                        }
                    }
                }
            }
            // Beacons whose floor no longer exists in the venue are still listed, so they can be fixed or deleted.
            val orphans = beacons.filter { b -> venue.levels.none { it.id == b.floorId } }
            if (orphans.isNotEmpty()) {
                item(key = "floor-unknown") {
                    IosSection(title = "Unknown floor") {
                        orphans.forEachIndexed { index, beacon ->
                            IosRow(
                                title = beacon.name,
                                glyph = "⚠",
                                subtitle = "Floor “${beacon.floorId}” isn't in this place · ${beacon.identityLabel}",
                                onClick = { isNew = false; editing = beacon },
                            )
                            if (index < orphans.lastIndex) IosRowDivider()
                        }
                    }
                }
            }
            item { Spacer(Modifier.padding(40.dp)) }
        }
    }

    editing?.let { beacon ->
        BeaconEditor(
            initial = beacon,
            isNew = isNew,
            levels = venue.levels,
            others = beacons,
            onSave = { onSave(it); editing = null },
            onDelete = { onDelete(it); editing = null },
            onUsePosition = { draft -> editing = null; onPickPosition(draft, isNew) },
            onDismiss = { editing = null },
        )
    }
}

/**
 * "Nearby Bluetooth devices": start/stop, what blocks scanning (with the fix), and the devices heard, strongest first.
 */
@Composable
private fun NearbySection(
    scan: NearbyScan,
    beaconsOnly: Boolean,
    onBeaconsOnlyChange: (Boolean) -> Unit,
    registered: List<RegisteredBeacon>,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPick: (NearbyDevice) -> Unit,
) {
    val shown = scan.devices.filter { !beaconsOnly || it.isBeacon }.take(MAX_SHOWN_DEVICES)
    IosSection(title = "Nearby Bluetooth devices") {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (scan.scanning) {
                TextButton(onClick = onStop) { Text("Stop scanning") }
            } else {
                TextButton(onClick = onStart) { Text("Scan nearby") }
            }
            Spacer(Modifier.weight(1f))
            Text("Beacons only", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = beaconsOnly, onCheckedChange = onBeaconsOnlyChange)
        }
        val blocked = when (scan.problem) {
            BleScanner.Problem.NO_BLUETOOTH_LE -> "This phone has no Bluetooth Low Energy, so it can't find beacons."
            BleScanner.Problem.PERMISSION_MISSING -> "Allow Nearby devices and Location to find beacons. Tap Scan nearby to ask again."
            BleScanner.Problem.BLUETOOTH_OFF -> "Bluetooth is off. Tap Scan nearby to turn it on."
            BleScanner.Problem.LOCATION_OFF -> "Location is off. On this Android version beacons can only be found with location on."
            null -> scan.error
        }
        if (blocked != null) {
            Text(
                "⚠ $blocked",
                color = Color(0xFFD32F2F),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (scan.scanning && shown.isEmpty()) {
            Text(
                if (beaconsOnly) "Listening… no beacons heard yet. Bring the phone close to a beacon, or switch off Beacons only."
                else "Listening… no Bluetooth devices heard yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        shown.forEach { device ->
            IosRowDivider()
            val known = registered.firstOrNull { it.matches(device) }
            IosRow(
                title = known?.let { "✓ ${it.name}" } ?: device.title(),
                glyph = if (device.isBeacon) "📶" else "📱",
                subtitle = listOfNotNull(
                    device.details(),
                    "${device.rssi} dBm",
                    device.roughDistanceMeters?.let { "≈%.1f m".format(it) },
                    if (device.isStable) null else "steadying…",
                ).joinToString(" · "),
                trailing = if (known != null) "registered" else null,
                onClick = { onPick(device) },
            )
        }
    }
}

/** A short name for a device heard: what kind of beacon it is, or its advertised name. */
private fun NearbyDevice.title(): String = when (val id = identity) {
    is BeaconIdentity.IBeacon -> "iBeacon · Major ${id.major} · Minor ${id.minor}"
    is BeaconIdentity.EddystoneUid -> "Eddystone beacon"
    is BeaconIdentity.OtherDevice -> name ?: "Bluetooth device"
}

/** The identifiers of a device heard, as far as it reveals them. */
private fun NearbyDevice.details(): String = when (val id = identity) {
    is BeaconIdentity.IBeacon -> id.uuid
    is BeaconIdentity.EddystoneUid -> "${id.namespace} / ${id.instance}"
    is BeaconIdentity.OtherDevice -> address
}

/** At most this many devices are listed (a busy place can have hundreds of phones and watches around). */
private const val MAX_SHOWN_DEVICES = 30

/**
 * The register / edit form. Fields are kept as typed text and checked with [Beacons.problems] when saving, so a
 * half-typed number never jumps around while the admin types.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BeaconEditor(
    initial: RegisteredBeacon,
    isNew: Boolean,
    levels: List<Venue.Level>,
    others: List<RegisteredBeacon>,
    onSave: (RegisteredBeacon) -> Unit,
    onDelete: (RegisteredBeacon) -> Unit,
    onUsePosition: (RegisteredBeacon) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var uuid by remember { mutableStateOf(initial.uuid) }
    var major by remember { mutableStateOf(initial.major?.toString().orEmpty()) }
    var minor by remember { mutableStateOf(initial.minor?.toString().orEmpty()) }
    var mac by remember { mutableStateOf(initial.macAddress) }
    var floorId by remember { mutableStateOf(initial.floorId) }
    // A position not set yet (NaN) shows as empty fields.
    var x by remember { mutableStateOf(if (initial.x.isNaN()) "" else "%.2f".format(java.util.Locale.US, initial.x)) }
    var y by remember { mutableStateOf(if (initial.y.isNaN()) "" else "%.2f".format(java.util.Locale.US, initial.y)) }
    var problems by remember { mutableStateOf(emptyList<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }

    /** The form as typed, unchecked, to keep while the camera finds the position. */
    fun draft() = initial.copy(
        name = name, uuid = Beacons.normalizeUuid(uuid) ?: uuid, major = major.trim().toIntOrNull(),
        minor = minor.trim().toIntOrNull(), macAddress = Beacons.normalizeMac(mac) ?: mac, floorId = floorId,
        x = x.trim().replace(',', '.').toFloatOrNull() ?: Float.NaN, y = y.trim().replace(',', '.').toFloatOrNull() ?: Float.NaN,
    )

    /** The form as a beacon, or the reasons it can't be one yet. */
    fun build(): Pair<RegisteredBeacon?, List<String>> {
        val formatProblems = mutableListOf<String>()
        val normalizedUuid = Beacons.normalizeUuid(uuid) ?: "".also { formatProblems += "The UUID should be 32 hex digits, e.g. E2C56DB5-DFFB-48D2-B060-D0F5A71096E0." }
        val normalizedMac = Beacons.normalizeMac(mac) ?: "".also { formatProblems += "The Bluetooth address should look like AA:BB:CC:DD:EE:FF." }
        val majorValue = major.trim().ifEmpty { null }?.let { it.toIntOrNull() ?: (-1).also { formatProblems += "Major must be a whole number." } }
        val minorValue = minor.trim().ifEmpty { null }?.let { it.toIntOrNull() ?: (-1).also { formatProblems += "Minor must be a whole number." } }
        val xValue = x.trim().replace(',', '.').toFloatOrNull() ?: Float.NaN
        val yValue = y.trim().replace(',', '.').toFloatOrNull() ?: Float.NaN
        val beacon = initial.copy(
            name = name.trim(), uuid = normalizedUuid, major = majorValue, minor = minorValue, macAddress = normalizedMac,
            floorId = floorId, x = xValue, y = yValue,
            createdAtMillis = if (isNew) System.currentTimeMillis() else initial.createdAtMillis,
        )
        val all = formatProblems + Beacons.problems(beacon, levels.map { it.id }.toSet(), others)
        return (if (all.isEmpty()) beacon else null) to all.distinct()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Register beacon" else "Edit beacon") },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("ID ${initial.beaconId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(name, { name = it }, label = { Text("Name, e.g. Main Entrance") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(uuid, { uuid = it }, label = { Text("UUID (iBeacon)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        major, { major = it }, label = { Text("Major") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        minor, { minor = it }, label = { Text("Minor") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                OutlinedTextField(
                    mac, { mac = it }, label = { Text("Bluetooth address (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Floor", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (level in levels) {
                        FilterChip(
                            selected = floorId == level.id,
                            onClick = { floorId = level.id },
                            label = { Text(level.name.resolved()) },
                        )
                    }
                }
                Text("Position on the floor plan (meters)", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        x, { x = it }, label = { Text("X") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    OutlinedTextField(
                        y, { y = it }, label = { Text("Y") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
                // Instead of measuring: stand next to the beacon and let the camera say where that is on the map.
                TextButton(onClick = { onUsePosition(draft()) }) { Text("📍 Use my current position") }
                for (problem in problems) Text("⚠ $problem", color = Color(0xFFD32F2F), style = MaterialTheme.typography.bodySmall)
                if (!isNew) {
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete beacon", color = Color(0xFFD32F2F)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val (beacon, found) = build()
                problems = found
                if (beacon != null) onSave(beacon)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${initial.name}”?") },
            text = { Text("The beacon is removed from this phone. The physical beacon is not affected.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(initial) }) { Text("Delete", color = Color(0xFFD32F2F)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
