package sy.safesy.detect

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic-drive tests for the detection engine.
 *
 * These are the seed of the replay harness (SPEC.md §8 step 1). Real recorded
 * drives replace the synthetic generators later; the assertions stay the same.
 *
 * The false-positive tests matter more than the true-positive ones. Under
 * voluntary adoption, one loudly-wrong harsh-braking flag shown to colleagues
 * costs more credibility than five missed events.
 */
class DrivingDetectorTest {

    private val rng = Random(42)

    /** Phone tilted in a cradle: gravity is NOT along a device axis. */
    private fun tiltedGravity(pitchDeg: Float, rollDeg: Float): Triple<Float, Float, Float> {
        val p = Math.toRadians(pitchDeg.toDouble())
        val r = Math.toRadians(rollDeg.toDouble())
        return Triple(
            (-sin(p)).toFloat() * 9.81f,
            (sin(r) * cos(p)).toFloat() * 9.81f,
            (cos(r) * cos(p)).toFloat() * 9.81f,
        )
    }

    private fun noise(scale: Float) = (rng.nextFloat() - 0.5f) * 2f * scale

    /**
     * Drives the detector through a calibration period plus a scripted profile.
     * @param longAccel maps elapsed seconds -> longitudinal accel (m/s²)
     */
    private fun runDrive(
        seconds: Int,
        profile: VehicleProfile = VehicleProfile.BUS,
        pitchDeg: Float = 20f,
        rollDeg: Float = 12f,
        vibration: Float = 0.35f,
        longAccel: (Float) -> Float = { 0f },
        lateralAccel: (Float) -> Float = { 0f },
    ): List<DetectedEvent> {
        val det = DrivingDetector(profile)
        val (gx, gy, gz) = tiltedGravity(pitchDeg, rollDeg)
        var speed = 22f   // ~80 km/h
        var lat = 35.5; val lon = 38.5
        var tMs = 0L
        val dtMs = 20L    // 50 Hz

        while (tMs < seconds * 1000L) {
            val sec = tMs / 1000f
            val la = longAccel(sec)
            val lateral = lateralAccel(sec)

            det.onImu(
                ImuSample(
                    tMs = tMs,
                    // Vehicle acceleration adds on top of the tilted gravity vector.
                    ax = gx + la + noise(vibration),
                    ay = gy + lateral + noise(vibration),
                    az = gz + noise(vibration),
                    gx = noise(0.02f), gy = noise(0.02f), gz = noise(0.02f),
                )
            )

            if (tMs % 1000L == 0L) {
                speed = (speed + la).coerceAtLeast(0f)
                lat += speed / 111_320.0
                det.onGnss(GnssSample(tMs, lat, lon, speed, 0f, 1.2f))
            }
            tMs += dtMs
        }
        return det.drain()
    }

    // --- False positives: the tests that matter most -----------------------

    @Test
    fun `normal driving on a tilted mount produces no events`() {
        val events = runDrive(seconds = 120, pitchDeg = 25f, rollDeg = 15f)
        val spurious = events.filter { it.kind != DetectedEvent.Kind.MOUNT_SHIFTED }
        assertTrue(
            "steady driving must not emit events, got: ${spurious.map { it.kind }}",
            spurious.isEmpty(),
        )
    }

    @Test
    fun `heavy road vibration alone does not trigger events`() {
        // Syrian roads are potholed; suspension is poor. This is the exact
        // scenario that made road-quality mapping unusable, and it must not
        // manufacture safety events either.
        val events = runDrive(seconds = 120, vibration = 1.5f)
        val spurious = events.filter { it.kind != DetectedEvent.Kind.MOUNT_SHIFTED }
        assertTrue("vibration produced ${spurious.size} phantom events", spurious.isEmpty())
    }

    @Test
    fun `a single pothole spike is not a crash`() {
        // A sharp vertical jolt with the vehicle continuing at speed.
        val events = runDrive(seconds = 90, longAccel = { s ->
            if (s > 60f && s < 60.1f) 0f else 0f
        }, vibration = 0.4f)
        assertTrue(
            "pothole must not register as a crash",
            events.none { it.kind == DetectedEvent.Kind.POSSIBLE_CRASH },
        )
    }

    // --- True positives ----------------------------------------------------

    @Test
    fun `sustained hard braking is detected`() {
        val events = runDrive(seconds = 90, longAccel = { s ->
            if (s in 60f..64f) -4.0f else 0f   // well above the BUS 2.5 threshold
        })
        assertTrue(
            "expected HARSH_BRAKE, got ${events.map { it.kind }}",
            events.any { it.kind == DetectedEvent.Kind.HARSH_BRAKE },
        )
    }

    @Test
    fun `gentle braking below threshold is ignored`() {
        val events = runDrive(seconds = 90, longAccel = { s ->
            if (s in 60f..64f) -1.5f else 0f   // below BUS 2.5
        })
        assertTrue(
            "gentle braking must not be flagged",
            events.none { it.kind == DetectedEvent.Kind.HARSH_BRAKE },
        )
    }

    @Test
    fun `vehicle class changes what counts as harsh`() {
        val decel = { s: Float -> if (s in 60f..64f) -2.9f else 0f }

        val busEvents = runDrive(90, VehicleProfile.BUS, longAccel = decel)
        val miniEvents = runDrive(90, VehicleProfile.MINIBUS, longAccel = decel)

        assertTrue(
            "2.9 m/s² exceeds the BUS threshold (2.5) and should flag",
            busEvents.any { it.kind == DetectedEvent.Kind.HARSH_BRAKE },
        )
        assertTrue(
            "2.9 m/s² is under the MINIBUS threshold (3.2) and should not",
            miniEvents.none { it.kind == DetectedEvent.Kind.HARSH_BRAKE },
        )
    }

    @Test
    fun `a single-sample deceleration blip is not a harsh brake`() {
        // The sustained-duration filter's real job. GNSS arrives at 1 Hz, so a
        // one-fix dip in reported speed — GPS noise, a momentary bad fix — must
        // not become an event. Only a decel sustained across fixes counts.
        val det = DrivingDetector(VehicleProfile.BUS)
        var t = 0L
        while (t < 35_000) {
            det.onImu(ImuSample(t, 0f, 0f, 9.81f, 0f, 0f, 0f))
            t += 20
        }
        det.drain()

        // Steady 20 m/s, one bad fix reading 14 m/s, then steady again.
        det.onGnss(GnssSample(35_000, 35.500, 38.5, 20f, 0f, 1.0f))
        det.onGnss(GnssSample(36_000, 35.5002, 38.5, 14f, 0f, 1.0f))  // -6 m/s² blip
        det.onGnss(GnssSample(37_000, 35.5004, 38.5, 20f, 0f, 1.0f))  // recovered

        assertTrue(
            "a one-fix speed blip must not be flagged as harsh braking",
            det.drain().none { it.kind == DetectedEvent.Kind.HARSH_BRAKE },
        )
    }

    // --- GPS quality gating ------------------------------------------------

    @Test
    fun `a GPS teleport does not manufacture acceleration`() {
        val det = DrivingDetector(VehicleProfile.BUS)
        // Calibrate first.
        var t = 0L
        while (t < 31_000) {
            det.onImu(ImuSample(t, 0f, 0f, 9.81f, 0f, 0f, 0f))
            t += 20
        }
        // A SUSTAINED apparent deceleration — enough to emit if it were believed —
        // but delivered via physically impossible position jumps.
        det.onGnss(GnssSample(31_000, 35.500, 38.5, 20f, 0f, 1.0f))
        det.onGnss(GnssSample(32_000, 35.545, 38.5, 14f, 0f, 1.0f))  // ~5 km in 1 s
        det.onGnss(GnssSample(33_000, 35.590, 38.5, 8f, 0f, 1.0f))
        det.onGnss(GnssSample(34_000, 35.635, 38.5, 2f, 0f, 1.0f))

        assertTrue(
            "an implausible jump must be discarded, not turned into an event",
            det.drain().none {
                it.kind == DetectedEvent.Kind.HARSH_BRAKE || it.kind == DetectedEvent.Kind.HARSH_ACCEL
            },
        )
    }

    @Test
    fun `high hdop suppresses detection`() {
        val det = DrivingDetector(VehicleProfile.BUS)
        var t = 0L
        while (t < 31_000) {
            det.onImu(ImuSample(t, 0f, 0f, 9.81f, 0f, 0f, 0f))
            t += 20
        }
        // Urban canyon: poor geometry, with a SUSTAINED apparent deceleration
        // that would certainly emit if the fixes were trusted.
        det.onGnss(GnssSample(31_000, 35.5000, 38.5, 20f, 0f, 12.0f))
        det.onGnss(GnssSample(32_000, 35.5002, 38.5, 14f, 0f, 12.0f))
        det.onGnss(GnssSample(33_000, 35.5003, 38.5, 8f, 0f, 12.0f))
        det.onGnss(GnssSample(34_000, 35.5004, 38.5, 2f, 0f, 12.0f))

        assertTrue(
            "hdop above the usable limit must gate detection",
            det.drain().none { it.kind == DetectedEvent.Kind.HARSH_BRAKE },
        )
    }

    // --- Mount stability ---------------------------------------------------

    @Test
    fun `phone knocked in the cradle emits MOUNT_SHIFTED`() {
        val det = DrivingDetector(VehicleProfile.BUS)
        var t = 0L
        // Calibrate at one orientation.
        val (ax, ay, az) = tiltedGravity(15f, 5f)
        while (t < 35_000) {
            det.onImu(ImuSample(t, ax, ay, az, 0f, 0f, 0f))
            t += 20
        }
        det.drain()

        // Phone slides: gravity direction changes sharply.
        val (bx, by, bz) = tiltedGravity(60f, 40f)
        while (t < 45_000) {
            det.onImu(ImuSample(t, bx, by, bz, 0f, 0f, 0f))
            t += 20
        }

        assertTrue(
            "a large orientation change must be reported, not silently absorbed",
            det.drain().any { it.kind == DetectedEvent.Kind.MOUNT_SHIFTED },
        )
    }

    @Test
    fun `no cornering events are emitted while the mount is re-converging`() {
        // After the phone slides, the gravity estimate is wrong — every
        // subsequent sample would look like violent lateral acceleration.
        // Suppression is what stops a burst of phantom events.
        val det = DrivingDetector(VehicleProfile.BUS)
        var t = 0L
        val (ax, ay, az) = tiltedGravity(10f, 0f)
        while (t < 35_000) {
            det.onImu(ImuSample(t, ax, ay, az, 0f, 0f, 0f))
            if (t % 1000L == 0L) det.onGnss(GnssSample(t, 35.5, 38.5, 20f, 0f, 1.0f))
            t += 20
        }
        det.drain()

        // Violent re-orientation, then hold — a large constant apparent lateral g.
        val (bx, by, bz) = tiltedGravity(10f, 70f)
        val shiftEnd = t + 3_000
        while (t < shiftEnd) {
            det.onImu(ImuSample(t, bx, by, bz, 0f, 0f, 0f))
            if (t % 1000L == 0L) det.onGnss(GnssSample(t, 35.5, 38.5, 20f, 0f, 1.0f))
            t += 20
        }

        val events = det.drain()
        assertTrue(
            "cornering must be suppressed while the gravity estimate re-converges, got ${events.map { it.kind }}",
            events.none { it.kind == DetectedEvent.Kind.HARSH_CORNER },
        )
    }

    // --- Crash detection ---------------------------------------------------

    @Test
    fun `a high-g impact is flagged as POSSIBLE_CRASH`() {
        val det = DrivingDetector(VehicleProfile.BUS)
        var t = 0L
        while (t < 35_000) {
            det.onImu(ImuSample(t, 0f, 0f, 9.81f, 0f, 0f, 0f))
            t += 20
        }
        det.drain()

        // ~120 ms impact at ~4g — above the 3.0g threshold.
        val impactEnd = t + 120
        while (t < impactEnd) {
            det.onImu(ImuSample(t, 39f, 0f, 9.81f, 0f, 0f, 0f))
            t += 20
        }

        val events = det.drain()
        assertTrue(
            "impact must be flagged immediately, without a sustained-duration wait",
            events.any { it.kind == DetectedEvent.Kind.POSSIBLE_CRASH },
        )
        assertTrue(
            "crash severity should be high",
            events.first { it.kind == DetectedEvent.Kind.POSSIBLE_CRASH }.severity > 300,
        )
    }

    @Test
    fun `events are not emitted during the calibration window`() {
        val det = DrivingDetector(VehicleProfile.BUS)
        var t = 0L
        // Hard braking within the first 10 s, before orientation is known.
        while (t < 10_000) {
            det.onImu(ImuSample(t, 5f, 0f, 9.81f, 0f, 0f, 0f))
            if (t % 1000L == 0L) det.onGnss(GnssSample(t, 35.5, 38.5, 20f - t / 1000f * 4f, 0f, 1.0f))
            t += 20
        }
        assertEquals(
            "nothing may be emitted before the mount orientation is learned",
            emptyList<DetectedEvent>(),
            det.drain().filter { it.kind != DetectedEvent.Kind.MOUNT_SHIFTED },
        )
    }
}
