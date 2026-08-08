package sy.safesy.detect

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Separates gravity from vehicle acceleration.
 *
 * THIS IS THE MOST UNDER-APPRECIATED SOURCE OF FALSE POSITIVES in phone-based
 * telematics (SPEC.md §S3.2). The accelerometer reports ~9.81 m/s² of gravity
 * in an unknown direction, because the phone sits in whatever cradle at
 * whatever angle the driver chose. Get this wrong and every trip is full of
 * phantom harsh-braking events — which, under voluntary adoption, costs more
 * credibility than missing five real ones.
 *
 * Approach: a complementary filter. The accelerometer's low-frequency content
 * is gravity (correct on average, noisy under vehicle motion); the gyroscope
 * integrates accurately short-term but drifts. Blend: trust the gyro over
 * milliseconds, correct toward the accelerometer over seconds.
 *
 * A full Madgwick/Mahony quaternion filter would also estimate heading, but we
 * get heading from GNSS for free and only need the gravity direction here.
 * Less machinery, less to go wrong, and easier to match in the future Rust
 * implementation.
 */
class OrientationEstimator(private val config: DetectionConfig) {

    /** Unit gravity direction in device frame. */
    private var gx = 0f
    private var gy = 0f
    private var gz = 1f
    private var initialized = false
    private var lastTMs = 0L

    /** Gravity direction at the end of calibration; MOUNT_SHIFTED compares to it. */
    private var referenceX = 0f
    private var referenceY = 0f
    private var referenceZ = 1f
    private var referenceSet = false

    /** Set when a mount shift is detected; IMU events stay suppressed until this passes. */
    var suppressedUntilMs: Long = 0L
        private set

    /** True between detecting a shift and the phone holding still again. */
    private var pendingReanchor = false
    private var stableSinceMs = 0L

    val isConverged: Boolean get() = initialized && referenceSet

    /**
     * Feed one IMU sample. Returns a MOUNT_SHIFTED event if the phone appears
     * to have physically moved in its cradle.
     */
    fun update(s: ImuSample): DetectedEvent? {
        val mag = sqrt(s.ax * s.ax + s.ay * s.ay + s.az * s.az)
        if (mag < 1e-3f) return null

        // Normalized accelerometer direction — gravity plus motion noise.
        val ax = s.ax / mag
        val ay = s.ay / mag
        val az = s.az / mag

        if (!initialized) {
            gx = ax; gy = ay; gz = az
            initialized = true
            lastTMs = s.tMs
            return null
        }

        val dt = ((s.tMs - lastTMs).coerceAtLeast(0L)).toFloat() / 1000f
        lastTMs = s.tMs

        // Rotate the current gravity estimate by the measured angular velocity.
        // Small-angle approximation: g' = g - (ω × g)·dt. Gravity is fixed in
        // the world frame, so in the *device* frame it rotates opposite to the
        // device's own rotation.
        var px = gx - (s.gy * gz - s.gz * gy) * dt
        var py = gy - (s.gz * gx - s.gx * gz) * dt
        var pz = gz - (s.gx * gy - s.gy * gx) * dt

        // Correct toward the accelerometer. Weight the correction down when the
        // measured magnitude is far from 1g — that means the vehicle is
        // accelerating hard, so the accelerometer is a poor gravity reference
        // exactly when we most need not to be misled by it.
        val gMag = mag / 9.81f
        val trust = (1f - abs(gMag - 1f) * 2f).coerceIn(0f, 1f)
        val alpha = config.gravityAlpha + (1f - config.gravityAlpha) * (1f - trust)

        px = alpha * px + (1f - alpha) * ax
        py = alpha * py + (1f - alpha) * ay
        pz = alpha * pz + (1f - alpha) * az

        val n = sqrt(px * px + py * py + pz * pz)
        if (n < 1e-6f) return null
        gx = px / n; gy = py / n; gz = pz / n

        if (!referenceSet) return null

        // Deviation is measured against the INSTANTANEOUS accelerometer direction,
        // not the filtered estimate.
        //
        // The filter is deliberately slow (alpha 0.98), so after a physical slide
        // the estimate still points the old way for a second or more — during
        // which linearAccel() reports a large bogus horizontal component and
        // would emit phantom cornering events BEFORE the shift is ever noticed.
        // The raw direction reacts immediately, which is exactly what is needed
        // to suppress detection in time.
        //
        // Under hard vehicle acceleration the raw direction also deviates, so
        // this is gated on the measured magnitude being near 1g — i.e. the
        // vehicle is not the explanation.
        // Re-anchor only once the phone has held a steady orientation for a
        // while — that is what makes the new reference trustworthy.
        val steady = abs(gMag - 1f) < 0.05f
        if (pendingReanchor && s.tMs >= suppressedUntilMs) {
            if (steady) {
                if (stableSinceMs == 0L) stableSinceMs = s.tMs
                if (s.tMs - stableSinceMs >= STABLE_REQUIRED_MS) {
                    referenceX = gx; referenceY = gy; referenceZ = gz
                    pendingReanchor = false
                    stableSinceMs = 0L
                }
            } else {
                stableSinceMs = 0L
                // Still moving: hold suppression rather than emitting again.
                suppressedUntilMs = s.tMs + config.mountReconvergeMs
            }
            return null
        }

        val nearOneG = abs(gMag - 1f) < 0.15f
        val dot = (ax * referenceX + ay * referenceY + az * referenceZ).coerceIn(-1f, 1f)
        val deviationDeg = if (nearOneG) {
            Math.toDegrees(acos(dot).toDouble()).toFloat()
        } else 0f

        if (deviationDeg > config.mountShiftDeg && s.tMs > suppressedUntilMs) {
            // The phone moved. Suppress IMU events until it settles.
            //
            // ⚠️ Do NOT re-anchor the reference here. A real drive trace showed
            // 24 MOUNT_SHIFTED events in 3 minutes: re-anchoring to a MOVING
            // orientation means the next sample already deviates from the
            // reference just set, so the moment suppression expires it fires
            // again — a loop, at exactly mountReconvergeMs intervals.
            //
            // Instead, defer re-anchoring to stabilisation (see below), so the
            // reference is only ever learned from a phone that is holding still.
            pendingReanchor = true
            suppressedUntilMs = s.tMs + config.mountReconvergeMs
            return DetectedEvent(
                kind = DetectedEvent.Kind.MOUNT_SHIFTED,
                tMs = s.tMs,
                severity = ((deviationDeg / 90f) * 1000f).toInt().coerceIn(0, 1000),
                peak = deviationDeg,
            )
        }
        return null
    }

    /** Called once the calibration window has elapsed with the vehicle in steady motion. */
    fun lockReference() {
        if (!initialized) return
        referenceX = gx; referenceY = gy; referenceZ = gz
        referenceSet = true
    }

    fun isSuppressed(tMs: Long) = tMs < suppressedUntilMs

    /**
     * Vehicle-frame acceleration with gravity removed, in m/s².
     *
     * `vertical` is along gravity; `lateral` and `longitudinal` span the plane
     * perpendicular to it. Without GNSS heading we cannot tell lateral from
     * longitudinal apart in that plane — [LinearAccel.horizontalMag] is the
     * honest combined magnitude, and [DrivingDetector] uses GPS speed change to
     * separate braking from cornering.
     */
    fun linearAccel(s: ImuSample): LinearAccel {
        val gravX = gx * 9.81f
        val gravY = gy * 9.81f
        val gravZ = gz * 9.81f
        val lx = s.ax - gravX
        val ly = s.ay - gravY
        val lz = s.az - gravZ
        // Component along gravity (vertical), and the remainder (horizontal).
        val vertical = lx * gx + ly * gy + lz * gz
        val hx = lx - vertical * gx
        val hy = ly - vertical * gy
        val hz = lz - vertical * gz
        return LinearAccel(
            vertical = vertical,
            horizontalMag = sqrt(hx * hx + hy * hy + hz * hz),
            totalMag = sqrt(lx * lx + ly * ly + lz * lz),
        )
    }
}

private const val STABLE_REQUIRED_MS = 2_000L

data class LinearAccel(
    val vertical: Float,
    val horizontalMag: Float,
    val totalMag: Float,
)
