package sy.safesy.detect

/**
 * Detection engine inputs and configuration. See SPEC.md §S3.2.
 *
 * This package is a PURE FUNCTION of its inputs: no Android APIs, no I/O, no
 * clocks. That is deliberate — it makes the whole engine testable against
 * recorded drives on a laptop, and it is what the future Rust implementation
 * must match behaviourally (conformance class B, §S1.3).
 */

/** Raw accelerometer + gyroscope sample. Device frame, 50 Hz. */
data class ImuSample(
    val tMs: Long,
    /** Acceleration including gravity, m/s². Device axes, not vehicle axes. */
    val ax: Float, val ay: Float, val az: Float,
    /** Angular velocity, rad/s. */
    val gx: Float, val gy: Float, val gz: Float,
)

/** GNSS fix, 1 Hz. */
data class GnssSample(
    val tMs: Long,
    val lat: Double,
    val lon: Double,
    val speedMps: Float,
    val headingDeg: Float,
    val hdop: Float,
    /**
     * True only for genuine GPS_PROVIDER fixes. Network/fused fixes carry the
     * untrusted system clock (§S3.1), so they must never populate gnss_t_ms.
     */
    val fromGpsProvider: Boolean = true,
)

enum class VehicleClass { BUS, MINIBUS, VAN }

/**
 * Per-vehicle thresholds. A loaded bus and an empty minibus are different
 * vehicles physically — thresholds come from here, never hardcoded.
 *
 * Distributed from the server via the signed config channel (§S5.4) so tuning
 * during the pilot does not require an app update.
 */
data class VehicleProfile(
    val vehicleClass: VehicleClass,
    /** Longitudinal deceleration, m/s². Positive magnitude. */
    val harshBrakeMps2: Float,
    val harshAccelMps2: Float,
    /** Lateral acceleration, m/s². */
    val harshCornerMps2: Float,
    /** Total acceleration magnitude in g that suggests an impact. */
    val crashG: Float,
    /** Roll rate, deg/s. Buses are top-heavy. */
    val rolloverDegS: Float,
) {
    companion object {
        val BUS = VehicleProfile(VehicleClass.BUS, 2.5f, 2.0f, 2.5f, 3.0f, 45f)
        val MINIBUS = VehicleProfile(VehicleClass.MINIBUS, 3.2f, 2.8f, 3.0f, 3.0f, 60f)
        val VAN = VehicleProfile(VehicleClass.VAN, 3.2f, 2.8f, 3.2f, 3.0f, 70f)

        fun forClass(c: VehicleClass) = when (c) {
            VehicleClass.BUS -> BUS
            VehicleClass.MINIBUS -> MINIBUS
            VehicleClass.VAN -> VAN
        }
    }
}

data class DetectionConfig(
    /** Complementary-filter coefficient: how much to trust the gyro short-term. */
    val gravityAlpha: Float = 0.98f,
    /** Seconds of steady driving before IMU events are trusted at trip start. */
    val calibrationSeconds: Float = 30f,
    /** hdop above this suppresses GPS-derived event detection. */
    val maxUsableHdop: Float = 5.0f,
    /** A position jump implying more than this speed is discarded as a glitch. */
    val implausibleSpeedMps: Float = 70f,      // 252 km/h
    /** Sustained event must exceed threshold for at least this long. */
    val minEventDurationMs: Long = 250,
    /** Re-arm window: one event of a kind per this interval. */
    val eventCooldownMs: Long = 3_000,
    /**
     * Gravity-vector angular change that signals the phone physically moved
     * in its cradle. Emits MOUNT_SHIFTED and suppresses IMU events until the
     * estimate re-converges.
     */
    val mountShiftDeg: Float = 25f,
    val mountReconvergeMs: Long = 5_000,
)

/** Detected event. Mirrors safesy.v1.Event — deliberately, so mapping is trivial. */
data class DetectedEvent(
    val kind: Kind,
    val tMs: Long,
    /** 0–1000, normalized. */
    val severity: Int,
    /** Peak magnitude that triggered it, in the event's natural unit. */
    val peak: Float,
    val durationMs: Long = 0,
) {
    enum class Kind {
        HARSH_BRAKE, HARSH_ACCEL, HARSH_CORNER,
        POSSIBLE_CRASH, MOUNT_SHIFTED,
    }
}
