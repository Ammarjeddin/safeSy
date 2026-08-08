package sy.safesy.detect

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Infers where the phone is from sensor behaviour, so the driver never has to
 * tell us.
 *
 * WHY THIS MATTERS: the detector's thresholds assume a stable mount. A phone in
 * a shirt pocket rotates with the driver's torso on every lean and mirror
 * check; a loose phone on a dashboard slides on every corner. Those need
 * different handling — but asking a bus driver to select a mode before every
 * trip is a step that will simply not happen, and a wrong answer is worse than
 * no answer.
 *
 * So we classify from behaviour instead, using signals already being measured:
 *
 *  1. **Orientation variance** — how much the gravity direction wanders. A
 *     cradle holds near-constant; a pocket swings continuously.
 *  2. **Vibration coupling** — a phone on a hard dashboard couples engine and
 *     road vibration directly; cloth damps the high-frequency content.
 *  3. **Proximity** — a pocket usually keeps the sensor covered.
 *
 * The verdict is formed over an observation window and then held for the trip:
 * placement rarely changes mid-journey, and a classifier that flips back and
 * forth would be worse than useless — thresholds would move under the
 * detector's feet.
 */
class PlacementClassifier(
    private val windowMs: Long = 60_000,
) {
    enum class Placement {
        /** Not enough data yet. Treat as UNSTABLE — the safe default. */
        UNKNOWN,
        /** Cradle or otherwise rigidly fixed. IMU-derived metrics trustworthy. */
        STABLE_MOUNT,
        /** Loose on a dashboard or seat: mostly flat, slides occasionally. */
        LOOSE_SURFACE,
        /** Pocket or bag: continuous re-orientation, damped vibration. */
        CARRIED,
    }

    private var startMs = 0L
    private var samples = 0

    // Running orientation statistics (Welford, so no sample buffer is retained).
    private var meanX = 0.0; private var meanY = 0.0; private var meanZ = 0.0
    private var sumSqDev = 0.0

    // High-frequency energy proxy: mean |Δa| between consecutive samples.
    private var lastMag = Float.NaN
    private var jerkSum = 0.0

    private var proximityCoveredSamples = 0
    private var proximitySamples = 0

    /** Held for the whole trip once decided. */
    var placement: Placement = Placement.UNKNOWN
        private set

    val isDecided: Boolean get() = placement != Placement.UNKNOWN

    /** 0f..1f progress through the observation window. */
    fun progress(nowMs: Long): Float =
        if (startMs == 0L) 0f else ((nowMs - startMs).toFloat() / windowMs).coerceIn(0f, 1f)

    fun onImu(s: ImuSample) {
        if (isDecided) return
        if (startMs == 0L) startMs = s.tMs

        val mag = sqrt(s.ax * s.ax + s.ay * s.ay + s.az * s.az)
        if (mag < 1e-3f) return

        // Direction of gravity (plus motion noise) as a unit vector.
        val ux = s.ax / mag; val uy = s.ay / mag; val uz = s.az / mag

        samples++
        val n = samples.toDouble()
        val dx = ux - meanX; val dy = uy - meanY; val dz = uz - meanZ
        meanX += dx / n; meanY += dy / n; meanZ += dz / n
        // Accumulated squared deviation from the running mean direction.
        sumSqDev += dx * (ux - meanX) + dy * (uy - meanY) + dz * (uz - meanZ)

        if (!lastMag.isNaN()) jerkSum += abs(mag - lastMag).toDouble()
        lastMag = mag

        if (s.tMs - startMs >= windowMs && samples > 100) decide()
    }

    fun onProximity(covered: Boolean) {
        if (isDecided) return
        proximitySamples++
        if (covered) proximityCoveredSamples++
    }

    private fun decide() {
        val orientationVariance = sqrt(sumSqDev / samples)   // ~0 = rock steady
        val meanJerk = jerkSum / samples                     // m/s² per sample
        val coveredFraction =
            if (proximitySamples == 0) 0f
            else proximityCoveredSamples.toFloat() / proximitySamples

        // Thresholds are PLACEHOLDERS. They have never been calibrated against
        // labelled pocket/cradle sessions — that is exactly what the session
        // page's placement field now collects. Treat these as a starting point
        // to be tuned from real data, not as tuned values.
        // Order matters. Orientation variance is the primary discriminator for
        // CARRIED, but among *steady* placements the separator is vibration
        // coupling — so vibration must be tested BEFORE falling back to
        // LOOSE_SURFACE, or a rigid mount with ordinary sensor noise is
        // misclassified as loose.
        placement = when {
            // A covered proximity sensor for most of the window is a strong,
            // low-ambiguity signal for a pocket or bag.
            coveredFraction > 0.7f -> Placement.CARRIED
            // Continuous re-orientation: the defining property of being carried.
            orientationVariance > 0.12 -> Placement.CARRIED
            // Steady orientation. Now separate rigid from resting by how much
            // engine/road vibration reaches the phone: a hard mount couples it
            // directly, cloth and foam damp the high-frequency content.
            meanJerk > 0.05 -> Placement.STABLE_MOUNT
            else -> Placement.LOOSE_SURFACE
        }
    }

    /**
     * Whether IMU-derived events should be trusted for this placement.
     *
     * GPS-derived metrics (speed, variance, duration, braking from the speed
     * derivative) are mount-independent and remain valid regardless — which is
     * why they are rated High confidence in SPEC §S3.3 and IMU cornering is not.
     */
    fun imuEventsTrustworthy(): Boolean = when (placement) {
        Placement.STABLE_MOUNT -> true
        Placement.LOOSE_SURFACE -> true      // degraded, but usable
        Placement.CARRIED -> false           // orientation is meaningless
        Placement.UNKNOWN -> false           // safe default while observing
    }

    fun reset() {
        startMs = 0; samples = 0
        meanX = 0.0; meanY = 0.0; meanZ = 0.0; sumSqDev = 0.0
        lastMag = Float.NaN; jerkSum = 0.0
        proximityCoveredSamples = 0; proximitySamples = 0
        placement = Placement.UNKNOWN
    }
}
