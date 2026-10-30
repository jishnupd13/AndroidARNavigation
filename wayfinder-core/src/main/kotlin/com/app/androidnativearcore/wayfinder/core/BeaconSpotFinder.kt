package com.app.androidnativearcore.wayfinder.core

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Finds where a beacon is from its signal heard along the visitor's walk, without a reference point or a registered
 * position. Signal strength alone only says "about this far", never in which direction; but each reading is paired
 * with where the phone was (from the camera's motion tracking, accurate to centimeters), and readings from different
 * places cross near the beacon, the way GPS combines satellites. The spot whose distances best explain all the
 * signal strengths wins.
 *
 * Expect 1–2 m after 5–10 steps around or past the beacon; people and walls in between make it worse. Walking along
 * one straight line can't tell which side of the line the beacon is on, so the estimate waits for a few steps
 * sideways. Height can't be told from the signal: the beacon is taken to be [heightBelowPhone] below the phone (a
 * desk, with the phone held at chest height).
 *
 * Positions are in the camera session's coordinates (y up). Thread-safe: readings may come from one thread and
 * [estimate] run on another.
 */
class BeaconSpotFinder(
    /** The beacon's signal 1 m away as the admin calibrated it (dBm), or null: then it is fitted from the walk. */
    strengthAt1m: Int? = null,
    private val pathLossExponent: Float = PATH_LOSS_EXPONENT,
    private val heightBelowPhone: Float = HEIGHT_BELOW_PHONE_M,
) {
    /** What the beacon's strength at 1 m is expected to be (dBm), and how far off that may be. */
    private val expectedStrength = strengthAt1m?.toDouble() ?: TYPICAL_STRENGTH_DB
    private val strengthSpread = if (strengthAt1m != null) CALIBRATED_SPREAD_DB else STRENGTH_SPREAD_DB

    /** What is known so far. */
    sealed interface Estimate {
        /** Not enough to go on yet: [samples] of [MIN_SAMPLES] readings, the phone moved [movedMeters]. */
        data class Gathering(val samples: Int, val movedMeters: Float) : Estimate

        /**
         * Enough readings, but spots far apart fit them about as well: [guess] is the best of them, off by
         * [uncertaintyMeters] or more. [sideways] when walking a few steps sideways would settle it.
         */
        data class Unclear(val sideways: Boolean, val guess: Vec3, val uncertaintyMeters: Float) : Estimate

        /** The beacon is probably at [spot], give or take [uncertaintyMeters]. */
        data class Found(val spot: Vec3, val uncertaintyMeters: Float, val samples: Int) : Estimate
    }

    /** Readings pooled over [BIN_MILLIS] from [time]: the median signal and the mean phone position. */
    private class Sample(val x: Float, val y: Float, val z: Float, val rssi: Float, val time: Long)

    private val samples = ArrayDeque<Sample>()
    private var binStart = Long.MIN_VALUE
    private val binRssi = ArrayList<Int>()
    private var binX = 0.0
    private var binY = 0.0
    private var binZ = 0.0
    private var hint: Vec3? = null
    private var hintMeters = HINT_METERS

    /**
     * Where the beacon probably is before the walk says more (for example where the camera pointed while the visitor
     * stood right by it), give or take [withinMeters]. The estimate starts there and moves as readings add up: a few
     * readings barely move it, a long walk outweighs it.
     */
    @Synchronized
    fun hint(spot: Vec3, withinMeters: Float = HINT_METERS) {
        hint = spot
        hintMeters = withinMeters
    }

    /** Adds one advertisement heard with [rssi] dBm while the phone was at [phone]. */
    @Synchronized
    fun add(phone: Vec3, rssi: Int, timeMillis: Long) {
        if (binRssi.isNotEmpty() && timeMillis - binStart >= BIN_MILLIS) closeBin()
        if (binRssi.isEmpty()) binStart = timeMillis
        binRssi += rssi
        binX += phone.x
        binY += phone.y
        binZ += phone.z
    }

    @Synchronized
    fun clear() {
        samples.clear()
        binRssi.clear()
        binX = 0.0; binY = 0.0; binZ = 0.0
    }

    private fun closeBin() {
        val n = binRssi.size
        val sorted = binRssi.sorted()
        samples.addLast(Sample((binX / n).toFloat(), (binY / n).toFloat(), (binZ / n).toFloat(), sorted[n / 2].toFloat(), binStart))
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
        binRssi.clear()
        binX = 0.0; binY = 0.0; binZ = 0.0
    }

    /** Where the beacon probably is, from the readings so far. Takes a few milliseconds: call it off the main thread. */
    fun estimate(): Estimate {
        val (kept, hinted) = synchronized(this) { samples.toList() to hint }
        // Only recent readings: a beacon may have been moved since the older ones.
        val newest = kept.maxOfOrNull { it.time } ?: 0L
        val all = kept.filter { newest - it.time <= MAX_SAMPLE_AGE_MS }
        val n = all.size
        // How far, and in how many directions, the phone has moved: the spread of its positions.
        val mx = all.sumOf { it.x.toDouble() } / max(n, 1)
        val mz = all.sumOf { it.z.toDouble() } / max(n, 1)
        var sxx = 0.0; var szz = 0.0; var sxz = 0.0
        for (s in all) {
            val dx = s.x - mx; val dz = s.z - mz
            sxx += dx * dx; szz += dz * dz; sxz += dx * dz
        }
        if (n > 0) { sxx /= n; szz /= n; sxz /= n }
        val mean = (sxx + szz) / 2
        val gap = sqrt(max(0.0, mean * mean - (sxx * szz - sxz * sxz)))
        val along = sqrt(max(0.0, mean + gap)).toFloat() // spread along the main direction walked
        val across = sqrt(max(0.0, mean - gap)).toFloat() // and across it
        val moved = 4f * along // a spread of 1 m is a walk of about 4 m back and forth
        if (n < MIN_SAMPLES || along < MIN_SPREAD_M) return Estimate.Gathering(n, moved)

        // Search the area around the walk: a coarse grid, then a fine one around the best spot.
        val minX = all.minOf { it.x } - SEARCH_MARGIN_M
        val maxX = all.maxOf { it.x } + SEARCH_MARGIN_M
        val minZ = all.minOf { it.z } - SEARCH_MARGIN_M
        val maxZ = all.maxOf { it.z } + SEARCH_MARGIN_M
        val coarse = ArrayList<Triple<Float, Float, Double>>()
        var x = minX
        while (x <= maxX) {
            var z = minZ
            while (z <= maxZ) {
                coarse += Triple(x, z, misfit(all, x, z, hinted))
                z += COARSE_STEP_M
            }
            x += COARSE_STEP_M
        }
        val bestCoarse = coarse.minBy { it.third }
        var best = bestCoarse
        x = bestCoarse.first - COARSE_STEP_M
        while (x <= bestCoarse.first + COARSE_STEP_M) {
            var z = bestCoarse.second - COARSE_STEP_M
            while (z <= bestCoarse.second + COARSE_STEP_M) {
                val m = misfit(all, x, z, hinted)
                if (m < best.third) best = Triple(x, z, m)
                z += FINE_STEP_M
            }
            x += FINE_STEP_M
        }

        // How sure: every spot could be it, the better it fits the likelier (given how noisy the signal is). The spread
        // of those likelihoods around the best spot is the uncertainty; much likelihood at the edge of the search
        // means the beacon could be far off in some direction.
        val noise = max(MIN_NOISE_DB2, best.third / (n - 3))
        var weightSum = 0.0
        var spreadSum = 0.0
        var edgeWeight = 0.0
        for ((cx, cz, m) in coarse) {
            val w = exp(-(m - best.third) / (2 * noise))
            if (w < 1e-6) continue
            weightSum += w
            val dx = cx - best.first; val dz = cz - best.second
            spreadSum += w * (dx * dx + dz * dz)
            if (cx - minX < COARSE_STEP_M || maxX - cx < COARSE_STEP_M || cz - minZ < COARSE_STEP_M || maxZ - cz < COARSE_STEP_M) edgeWeight += w
        }
        val uncertainty = sqrt(spreadSum / weightSum).toFloat().coerceAtLeast(MIN_UNCERTAINTY_M)
        val phoneHeight = all.map { it.y }.sorted()[n / 2]
        val spot = Vec3(best.first, phoneHeight - heightBelowPhone, best.second)
        if (edgeWeight / weightSum > MAX_EDGE_SHARE || uncertainty > MAX_UNCERTAINTY_M) {
            return Estimate.Unclear(sideways = across < SIDEWAYS_SPREAD_M, spot, uncertainty)
        }
        return Estimate.Found(spot, uncertainty, n)
    }

    /**
     * How badly the beacon being at ([bx], [bz]) explains the readings (dB²): signals follow rssi = A − 10·n·log10(d)
     * with the beacon's own strength at 1 m, A, unknown (beacons differ). A is fitted for each spot, but held to what
     * the beacon was calibrated to (± [CALIBRATED_SPREAD_DB]), or loosely to what beacons usually send
     * ([TYPICAL_STRENGTH_DB] ± [STRENGTH_SPREAD_DB]); otherwise a spot far away would fit almost as well as the right
     * one by assuming a very strong beacon. Knowing A turns each reading into a distance: far fewer spots fit. A [hinted] spot weighs in like one more
     * reading: the farther from it, the worse.
     */
    private fun misfit(all: List<Sample>, bx: Float, bz: Float, hinted: Vec3?): Double {
        val h2 = (heightBelowPhone * heightBelowPhone).toDouble()
        var sum = 0.0
        var sumSq = 0.0
        for (s in all) {
            val dx = (bx - s.x).toDouble(); val dz = (bz - s.z).toDouble()
            val d = sqrt(dx * dx + dz * dz + h2)
            val a = s.rssi + 10.0 * pathLossExponent * log10(d) // the A this reading implies
            sum += a
            sumSq += a * a
        }
        val n = all.size
        // The prior counts like k extra readings of the typical strength.
        val k = (READING_NOISE_DB * READING_NOISE_DB) / (strengthSpread * strengthSpread)
        val a = (sum + k * expectedStrength) / (n + k)
        val fromHint = hinted?.let {
            val hx = (bx - it.x).toDouble(); val hz = (bz - it.z).toDouble()
            READING_NOISE_DB * READING_NOISE_DB * (hx * hx + hz * hz) / (hintMeters * hintMeters)
        } ?: 0.0
        return sumSq - 2 * a * sum + n * a * a + k * (a - expectedStrength) * (a - expectedStrength) + fromHint
    }

    companion object {
        /** How fast a signal weakens with distance indoors (as for [NearbyDevice.roughDistanceMeters]). */
        const val PATH_LOSS_EXPONENT = 2.5f

        /** The beacon is assumed this far (meters) below the phone. */
        const val HEIGHT_BELOW_PHONE_M = 0.55f

        /** Readings older than this (ms) are left out: the beacon may have been moved since. */
        const val MAX_SAMPLE_AGE_MS = 90_000L

        /** Readings are pooled over this long (ms): enough to tame single readings, short enough not to lag a walk. */
        const val BIN_MILLIS = 500L

        /** Pooled readings needed before guessing (about 5 seconds of walking). */
        const val MIN_SAMPLES = 10

        /** The phone must have moved this much (spread, meters) before guessing: about 1.5 m of walking. */
        const val MIN_SPREAD_M = 0.35f

        /** Across the main direction walked, less spread than this (meters) leaves a mirror image of the spot. */
        const val SIDEWAYS_SPREAD_M = 0.3f

        /** Found only when the spot is known within this (meters, the typical miss). */
        const val MAX_UNCERTAINTY_M = 1.5f

        const val MIN_UNCERTAINTY_M = 0.5f

        /** How far (meters) a hint may be off, by default. */
        const val HINT_METERS = 1f
        private const val MAX_SAMPLES = 400
        private const val SEARCH_MARGIN_M = 6f
        private const val COARSE_STEP_M = 0.4f
        private const val FINE_STEP_M = 0.1f

        /** Unclear when more of the likelihood than this lies at the edge of the search area. */
        private const val MAX_EDGE_SHARE = 0.05

        /** What beacons typically measure 1 m away (dBm), and how much they differ from it. */
        private const val TYPICAL_STRENGTH_DB = -62.0
        private const val STRENGTH_SPREAD_DB = 10.0

        /** How far a calibrated strength may be off (dB): another phone, or held differently, measures a bit differently. */
        private const val CALIBRATED_SPREAD_DB = 3.0

        /** The usual noise of a pooled reading (dB), to weigh that expectation against the readings. */
        private const val READING_NOISE_DB = 4.0

        /** The least noise assumed (dB²), so a lucky near-perfect fit doesn't look certain. */
        private const val MIN_NOISE_DB2 = 4.0
    }
}
