package sy.safesy.detect

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The classifier decides whether IMU-derived events can be trusted at all, so
 * a wrong verdict is worse than no verdict. These tests pin the two decisions
 * that matter: a carried phone must never be called STABLE_MOUNT, and the
 * default before deciding must be conservative.
 */
class PlacementClassifierTest {

    private val rng = Random(7)
    private fun n(s: Float) = (rng.nextFloat() - 0.5f) * 2f * s

    /** Feeds `seconds` of 50 Hz samples from a supplied generator. */
    private fun feed(
        c: PlacementClassifier,
        seconds: Int,
        gen: (Long) -> Triple<Float, Float, Float>,
    ) {
        var t = 0L
        while (t < seconds * 1000L) {
            val (x, y, z) = gen(t)
            c.onImu(ImuSample(t, x, y, z, 0f, 0f, 0f))
            t += 20
        }
    }

    @Test
    fun `a rigid mount with engine vibration is STABLE_MOUNT`() {
        val c = PlacementClassifier()
        feed(c, 70) { _ ->
            // Fixed tilt, strong high-frequency coupling.
            Triple(-3.3f + n(0.6f), 1.2f + n(0.6f), 9.1f + n(0.6f))
        }
        assertEquals(PlacementClassifier.Placement.STABLE_MOUNT, c.placement)
        assertTrue(c.imuEventsTrustworthy())
    }

    @Test
    fun `a pocket that swings with the torso is CARRIED`() {
        val c = PlacementClassifier()
        feed(c, 70) { t ->
            // Orientation sweeps continuously — the defining pocket signature.
            val a = (t / 1000.0) * 0.9
            Triple(
                (sin(a) * 9.81).toFloat() + n(0.2f),
                (cos(a) * 3.0).toFloat() + n(0.2f),
                (cos(a) * 9.0).toFloat() + n(0.2f),
            )
        }
        assertEquals(PlacementClassifier.Placement.CARRIED, c.placement)
        assertFalse("IMU events must not be trusted in a pocket", c.imuEventsTrustworthy())
    }

    @Test
    fun `a covered proximity sensor alone is enough to say CARRIED`() {
        val c = PlacementClassifier()
        repeat(100) { c.onProximity(covered = true) }
        // Otherwise perfectly steady — without proximity this would look mounted.
        feed(c, 70) { _ -> Triple(0f + n(0.5f), 0f + n(0.5f), 9.81f + n(0.5f)) }
        assertEquals(
            "a pocket keeps the sensor covered; that outweighs a steady orientation",
            PlacementClassifier.Placement.CARRIED,
            c.placement,
        )
    }

    @Test
    fun `verdict is withheld until the observation window completes`() {
        val c = PlacementClassifier()
        feed(c, 10) { _ -> Triple(0f, 0f, 9.81f + n(0.5f)) }
        assertEquals(PlacementClassifier.Placement.UNKNOWN, c.placement)
        assertFalse("must not trust IMU before deciding", c.imuEventsTrustworthy())
    }

    @Test
    fun `verdict is held for the trip once decided`() {
        val c = PlacementClassifier()
        feed(c, 70) { _ -> Triple(-3.3f + n(0.6f), 1.2f + n(0.6f), 9.1f + n(0.6f)) }
        val decided = c.placement
        // Wild motion afterwards must not flip it — thresholds cannot move
        // under the detector's feet mid-trip.
        feed(c, 70) { t ->
            val a = (t / 1000.0) * 2.0
            Triple((sin(a) * 9.81).toFloat(), 0f, (cos(a) * 9.81).toFloat())
        }
        assertEquals(decided, c.placement)
    }

    @Test
    fun `progress reports observation completeness`() {
        val c = PlacementClassifier()
        feed(c, 30) { _ -> Triple(0f, 0f, 9.81f + n(0.3f)) }
        val p = c.progress(30_000)
        assertTrue("expected ~0.5 progress, got $p", p in 0.4f..0.6f)
    }
}
