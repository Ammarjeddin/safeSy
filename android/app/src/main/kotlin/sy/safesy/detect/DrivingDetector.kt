package sy.safesy.detect

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The detection engine. See SPEC.md §S3.2.
 *
 * Consumes 50 Hz IMU and 1 Hz GNSS; emits sparse events. **The 50 Hz stream
 * never leaves the device** — that is what makes ~11.9 MB/month possible
 * instead of ~894 MB, and what makes a 3-hour desert dead zone survivable
 * (the events survive the gap, not just the raw points).
 *
 * No scoring here (§S5.3). The device emits observations; the server decides
 * what they are worth.
 *
 * DESIGN BIAS: thresholds run LOW. Over-detection is recoverable server-side
 * from the ±5 s snippets; a missed event leaves no trace anywhere and is
 * permanent. Prefer a false positive we can delete to a real event we never saw.
 */
class DrivingDetector(
    private val profile: VehicleProfile,
    private val config: DetectionConfig = DetectionConfig(),
) {
    private val orientation = OrientationEstimator(config)

    private var firstSampleMs: Long? = null
    private var calibrated = false

    private var lastGnss: GnssSample? = null
    private var gnssAccelMps2 = 0f          // GPS-derived longitudinal accel
    private var gnssUsable = false

    // Sustained-event tracking, per kind.
    private val active = mutableMapOf<DetectedEvent.Kind, Excursion>()
    private val lastEmitted = mutableMapOf<DetectedEvent.Kind, Long>()

    private data class Excursion(val startMs: Long, var peak: Float)

    private val pending = mutableListOf<DetectedEvent>()

    // --- Observability. Read-only; for the debug/drive-test screen. ---
    val isCalibrated: Boolean get() = calibrated
    /** 0f..1f progress through the calibration window. */
    val calibrationProgress: Float
        get() = firstSampleMs?.let { first ->
            if (calibrated) 1f
            else ((lastImuMs - first) / 1000f / config.calibrationSeconds).coerceIn(0f, 1f)
        } ?: 0f
    fun isMountSuppressed(tMs: Long) = orientation.isSuppressed(tMs)
    var lastLinear: LinearAccel? = null
        private set
    val lastGpsAccel: Float get() = gnssAccelMps2
    private var lastImuMs = 0L

    /** Feed one IMU sample (50 Hz). */
    fun onImu(s: ImuSample) {
        if (firstSampleMs == null) firstSampleMs = s.tMs
        lastImuMs = s.tMs

        orientation.update(s)?.let { pending += it }

        // Publish the accel breakdown even during calibration — a tester
        // watching the debug screen needs to see the sensor is alive.
        lastLinear = orientation.linearAccel(s)

        // Calibration: learn the mounting orientation before trusting IMU events.
        if (!calibrated) {
            val elapsed = (s.tMs - firstSampleMs!!) / 1000f
            if (elapsed >= config.calibrationSeconds) {
                orientation.lockReference()
                calibrated = true
            }
            return
        }
        if (orientation.isSuppressed(s.tMs)) return

        val la = orientation.linearAccel(s)
        lastLinear = la

        // --- Crash: total magnitude, checked BEFORE the sustained-duration rule.
        // An impact lasts ~120 ms, so requiring a sustained excursion would miss
        // it entirely. Server-side confirmation (speed -> 0, no motion for 60 s)
        // is what separates a crash from a pothole (§S5 / original plan §5.5).
        val totalG = la.totalMag / 9.81f
        if (totalG >= profile.crashG) {
            emit(DetectedEvent.Kind.POSSIBLE_CRASH, s.tMs, totalG, 0,
                severity = ((totalG / (profile.crashG * 3f)) * 1000f).toInt())
            return
        }

        // --- Cornering: horizontal acceleration NOT explained by speed change.
        // GPS gives us longitudinal accel directly; whatever horizontal
        // acceleration remains beyond that is lateral, i.e. cornering.
        if (gnssUsable) {
            val longitudinal = abs(gnssAccelMps2)
            val lateralSq = la.horizontalMag * la.horizontalMag - longitudinal * longitudinal
            val lateral = if (lateralSq > 0f) sqrt(lateralSq) else 0f
            track(DetectedEvent.Kind.HARSH_CORNER, s.tMs, lateral, profile.harshCornerMps2)
        }
    }

    /** Feed one GNSS fix (1 Hz). */
    fun onGnss(s: GnssSample) {
        val prev = lastGnss

        // --- GPS quality gating. An hdop spike plus an implausible jump must
        // SUPPRESS detection rather than emit a phantom event. Urban canyons in
        // Damascus and Aleppo will do this constantly.
        gnssUsable = s.hdop <= config.maxUsableHdop
        if (prev != null) {
            val dt = (s.tMs - prev.tMs) / 1000f
            if (dt > 0f) {
                val implied = haversineM(prev.lat, prev.lon, s.lat, s.lon) / dt
                if (implied > config.implausibleSpeedMps) {
                    gnssUsable = false   // teleport: discard, do not derive accel
                    lastGnss = s
                    return
                }
                // GPS-derived longitudinal acceleration. Mount-independent, which
                // is why braking is rated Medium confidence and cornering lower.
                gnssAccelMps2 = (s.speedMps - prev.speedMps) / dt
            }
        }
        lastGnss = s

        if (!gnssUsable || !calibrated) return

        // Braking and acceleration come from the GPS speed derivative, not the
        // IMU — no orientation dependency, so these survive a sliding phone.
        //
        // BOTH kinds are tracked on every fix, including with a value of 0.
        // Tracking only the active sign would leave the opposite kind's
        // excursion open forever, so a later unrelated excursion of that kind
        // would appear to have started minutes earlier and emit immediately.
        track(DetectedEvent.Kind.HARSH_BRAKE, s.tMs,
            if (gnssAccelMps2 < 0) -gnssAccelMps2 else 0f, profile.harshBrakeMps2)
        track(DetectedEvent.Kind.HARSH_ACCEL, s.tMs,
            if (gnssAccelMps2 > 0) gnssAccelMps2 else 0f, profile.harshAccelMps2)
    }

    /** Drain detected events. Caller owns the result; the engine keeps no history. */
    fun drain(): List<DetectedEvent> {
        val out = pending.toList()
        pending.clear()
        return out
    }

    // --- Sustained-excursion tracking -------------------------------------
    //
    // A momentary spike is noise — a pothole, a door slam, a phone knocked.
    // Requiring the threshold to be exceeded for minEventDurationMs is the
    // cheapest and most effective false-positive filter available.

    private fun track(kind: DetectedEvent.Kind, tMs: Long, value: Float, threshold: Float) {
        if (value < threshold) {
            active.remove(kind)
            return
        }
        val ex = active.getOrPut(kind) { Excursion(tMs, value) }
        if (value > ex.peak) ex.peak = value
        // Duration is measured from the first over-threshold sample to now. A
        // momentary spike (pothole, door slam, one bad GPS fix) never reaches
        // minEventDurationMs and is discarded — the cheapest and most effective
        // false-positive filter available.
        if (tMs - ex.startMs >= config.minEventDurationMs) {
            emit(kind, tMs, ex.peak, tMs - ex.startMs,
                severity = ((ex.peak / (threshold * 2f)) * 1000f).toInt())
            active.remove(kind)
        }
    }

    private fun emit(kind: DetectedEvent.Kind, tMs: Long, peak: Float, durationMs: Long, severity: Int) {
        val last = lastEmitted[kind]
        if (last != null && tMs - last < config.eventCooldownMs) return
        lastEmitted[kind] = tMs
        pending += DetectedEvent(kind, tMs, severity.coerceIn(0, 1000), peak, durationMs)
    }

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(sqrt(a), sqrt(1 - a))
    }
}
