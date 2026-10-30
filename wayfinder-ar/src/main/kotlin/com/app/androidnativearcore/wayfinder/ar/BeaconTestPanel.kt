package com.app.androidnativearcore.wayfinder.ar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.androidnativearcore.wayfinder.core.HeardBeacon
import com.app.androidnativearcore.wayfinder.core.RegisteredBeacon
import com.app.androidnativearcore.wayfinder.core.Vec3
import com.app.androidnativearcore.wayfinder.core.detectRssi
import com.app.androidnativearcore.wayfinder.core.signalLevel
import kotlin.math.hypot

/**
 * The real-device checks of the beacon rules (debug builds only): where to stand, and what must happen. The tester
 * stands in place and taps the case; a few seconds later the screen's state is recorded and judged.
 */
internal enum class BeaconTestCase(val label: String, val instruction: String) {
    AT_2M("2 m", "2 m from the beacon, facing it: no icon"),
    AT_1M("1 m", "1 m away, facing it: no icon"),
    AT_05M("0.5 m", "0.5 m away, facing it: the icon on the beacon"),
    AT_03M("0.3 m", "0.3 m away, facing it: the icon on the beacon"),
    FACING_AWAY("Facing away", "At 0.5 m, back turned to the beacon: still detected, icon unmoved"),
    TURNED_BACK("180° rescan", "Turned back to face it: the icon where it was"),
    REPEAT("Repeat scan", "Not moving, again: one icon, same place"),
    SEVERAL("Several beacons", "At one beacon with others nearby: only that one detected"),
    OBSTRUCTED("Wall / body", "2 m away with a wall or a person between: no icon"),
}

/** One recorded check. */
internal data class BeaconTestResult(val case: BeaconTestCase, val passed: Boolean, val detail: String)

/** What the screen showed when a check was recorded. */
internal class BeaconTestSnapshot(
    /** The beacon under test: the one walked to, else the nearest heard. */
    val beacon: RegisteredBeacon?,
    val signal: HeardBeacon?,
    val detected: Set<String>,
    /** Where its icon is, if shown. */
    val icon: Vec3?,
    /** Where its icon was at the last check that showed it at 0.5 m or closer, to compare with. */
    val reference: Vec3?,
)

/** An icon this close (meters) to where it was counts as unmoved. */
private const val UNMOVED_M = 0.1f

/** Judges [snap] for [case]; also returns the reference icon position for the next checks. */
internal fun judge(case: BeaconTestCase, snap: BeaconTestSnapshot): Pair<BeaconTestResult, Vec3?> {
    val beacon = snap.beacon ?: return BeaconTestResult(case, false, "no beacon heard") to snap.reference
    val isDetected = beacon.beaconId in snap.detected
    val signal = snap.signal?.let { "${it.rssi} dBm (threshold ${it.detectRssi}, ${it.signalLevel.name.lowercase()})" } ?: "not heard"
    val moved = if (snap.icon != null && snap.reference != null) hypot(snap.icon.x - snap.reference.x, snap.icon.z - snap.reference.z) else null
    val state = if (isDetected) "detected" else "not detected"
    val iconText = when {
        snap.icon == null -> "no icon"
        moved != null -> "icon %.2f m from where it was".format(moved)
        else -> "icon shown"
    }
    var reference = snap.reference
    val passed = when (case) {
        BeaconTestCase.AT_2M, BeaconTestCase.AT_1M, BeaconTestCase.OBSTRUCTED -> !isDetected && snap.icon == null
        BeaconTestCase.AT_05M, BeaconTestCase.AT_03M -> (isDetected && snap.icon != null).also { if (it) reference = snap.icon }
        BeaconTestCase.FACING_AWAY, BeaconTestCase.TURNED_BACK, BeaconTestCase.REPEAT ->
            isDetected && snap.icon != null && (moved == null || moved <= UNMOVED_M)
        BeaconTestCase.SEVERAL -> snap.detected.size <= 1 && (snap.detected.isEmpty() || isDetected)
    }
    val others = (snap.detected - beacon.beaconId).size.takeIf { it > 0 }?.let { " · $it other beacon(s) detected" } ?: ""
    return BeaconTestResult(case, passed, "${beacon.name}: $signal · $state · $iconText$others") to reference
}

/** The test panel: a button per case, the one being recorded, and the results so far. */
@Composable
internal fun BeaconTestPanel(
    results: List<BeaconTestResult>,
    recording: BeaconTestCase?,
    onRun: (BeaconTestCase) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().background(Color(0xE61C1C1E), RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🧪 Beacon rule tests", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onClear) { Text("Clear") }
        }
        Text(
            recording?.let { "Recording “${it.label}”… keep still" } ?: "Stand in place, then tap the case (recorded 3 s later).",
            color = if (recording != null) Color(0xFFFFD60A) else Color.White.copy(alpha = 0.75f),
            fontSize = 13.sp,
        )
        BeaconTestCase.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { case ->
                    Text(
                        case.label,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .weight(1f)
                            .background(if (recording == case) Color(0xFF0A84FF) else Color(0x33FFFFFF), RoundedCornerShape(8.dp))
                            .clickable(enabled = recording == null) { onRun(case) }
                            .padding(vertical = 8.dp, horizontal = 6.dp),
                    )
                }
            }
        }
        LazyColumn(Modifier.heightIn(max = 180.dp)) {
            items(results.asReversed()) { result ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(
                        (if (result.passed) "✅ " else "❌ ") + result.case.label + " — " + result.case.instruction,
                        color = if (result.passed) Color(0xFF34C759) else Color(0xFFFF453A),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(result.detail, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                }
            }
        }
    }
}
