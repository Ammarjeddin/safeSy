package sy.safesy.detect

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gravity separation is the single most under-appreciated source of false
 * positives in phone-based telematics (SPEC.md §S3.2). These tests target it
 * directly rather than through the detector, so a regression here is
 * unambiguous.
 */
class OrientationEstimatorTest {

    private fun gravityAt(pitchDeg: Float, rollDeg: Float): Triple<Float, Float, Float> {
        val p = Math.toRadians(pitchDeg.toDouble())
        val r = Math.toRadians(rollDeg.toDouble())
        return Triple(
            (-sin(p)).toFloat() * 9.81f,
            (sin(r) * cos(p)).toFloat() * 9.81f,
            (cos(r) * cos(p)).toFloat() * 9.81f,
        )
    }

    private fun settle(est: OrientationEstimator, g: Triple<Float, Float, Float>, ms: Long): Long {
        var t = 0L
        while (t < ms) {
            est.update(ImuSample(t, g.first, g.second, g.third, 0f, 0f, 0f))
            t += 20
        }
        return t
    }

    @Test
    fun `a stationary tilted phone reports near-zero linear acceleration`() {
        // THE core property. A phone at 30 pitch / 20 roll still measures a full
        // 9.81 m/s² — if gravity is not removed, that reads as ~1g of permanent
        // phantom acceleration and every trip is full of false events.
        val est = OrientationEstimator(DetectionConfig())
        val g = gravityAt(30f, 20f)
        val t = settle(est, g, 5_000)

        val la = est.linearAccel(ImuSample(t, g.first, g.second, g.third, 0f, 0f, 0f))

        assertTrue(
            "gravity must be removed; got total=${la.totalMag} m/s² (expected ~0)",
            la.totalMag < 0.5f,
        )
        assertTrue(
            "horizontal component must be ~0 when stationary; got ${la.horizontalMag}",
            la.horizontalMag < 0.5f,
        )
    }

    @Test
    fun `real acceleration survives gravity removal`() {
        // The complement of the test above: removing gravity must not also
        // remove the signal we actually want.
        val est = OrientationEstimator(DetectionConfig())
        val g = gravityAt(30f, 20f)
        val t = settle(est, g, 5_000)

        // 3 m/s² along device X, on top of gravity.
        val la = est.linearAccel(ImuSample(t, g.first + 3f, g.second, g.third, 0f, 0f, 0f))

        assertTrue(
            "a real 3 m/s² must still be visible; got total=${la.totalMag}",
            abs(la.totalMag - 3f) < 0.6f,
        )
    }

    @Test
    fun `a vertical pothole jolt does not leak into the horizontal channel`() {
        // Potholes are constant on Syrian roads. A vertical jolt must land in
        // `vertical`, not `horizontalMag` — otherwise every bump reads as
        // lateral acceleration and manufactures phantom cornering events.
        val est = OrientationEstimator(DetectionConfig())
        val g = gravityAt(0f, 0f)          // phone flat: gravity is along +Z
        val t = settle(est, g, 5_000)

        // 6 m/s² straight up — a hard bump, no lateral component at all.
        val la = est.linearAccel(ImuSample(t, g.first, g.second, g.third + 6f, 0f, 0f, 0f))

        assertTrue(
            "a purely vertical jolt must register as vertical; got ${la.vertical}",
            abs(la.vertical) > 5f,
        )
        assertTrue(
            "a purely vertical jolt must NOT appear horizontal; got ${la.horizontalMag}",
            la.horizontalMag < 1f,
        )
    }

    @Test
    fun `estimator converges from any mounting angle`() {
        // Drivers mount phones at arbitrary angles. Every one must converge.
        for (pitch in listOf(0f, 25f, 45f, 70f, 90f)) {
            for (roll in listOf(0f, 30f, 60f)) {
                val est = OrientationEstimator(DetectionConfig())
                val g = gravityAt(pitch, roll)
                val t = settle(est, g, 5_000)
                val la = est.linearAccel(ImuSample(t, g.first, g.second, g.third, 0f, 0f, 0f))
                assertTrue(
                    "failed to converge at pitch=$pitch roll=$roll: residual ${la.totalMag} m/s²",
                    la.totalMag < 0.6f,
                )
            }
        }
    }
}
