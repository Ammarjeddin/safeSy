package sy.safesy.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sy.safesy.policy.OrphanResolver
import sy.safesy.policy.OrphanResolver.Decision
import sy.safesy.policy.TripState

class TripStateTest {

    // --- The IDLE privacy guarantee is testable, so test it. ---

    @Test
    fun `only ACTIVE collects sensor data`() {
        TripState.entries.forEach { state ->
            assertEquals(
                "only ACTIVE may collect sensors; $state must not",
                state == TripState.ACTIVE,
                state.collectsSensorData,
            )
        }
    }

    @Test
    fun `IDLE does not touch the network`() {
        assertTrue("IDLE must be fully inert", !TripState.IDLE.drainsOutbox)
    }

    @Test
    fun `ORPHANED still drains its outbox`() {
        // A crash is exactly when the process dies AND the data matters most.
        assertTrue(TripState.ORPHANED.drainsOutbox)
        assertTrue(TripState.CLOSED_INCOMPLETE.drainsOutbox)
    }

    // --- Orphan resolution. Bias toward CLOSED_INCOMPLETE. ---

    @Test
    fun `resumes when GNSS gap is short and vehicle has not moved`() {
        val d = OrphanResolver.resolve(
            gnssGapMs = 60_000,
            wallClockGapMs = null,
            distanceFromLastFixM = 50.0,
        )
        assertEquals(Decision.Resume, d)
    }

    @Test
    fun `closes when gap exceeds the resume window`() {
        val d = OrphanResolver.resolve(
            gnssGapMs = 20 * 60 * 1000,
            wallClockGapMs = null,
            distanceFromLastFixM = 10.0,
        )
        assertTrue(d is Decision.CloseIncomplete)
    }

    @Test
    fun `closes when the vehicle moved far from the last fix`() {
        val d = OrphanResolver.resolve(
            gnssGapMs = 60_000,
            wallClockGapMs = null,
            distanceFromLastFixM = 5_000.0,
        )
        assertTrue(d is Decision.CloseIncomplete)
    }

    @Test
    fun `no trustworthy clock closes the trip rather than guessing`() {
        val d = OrphanResolver.resolve(null, null, 10.0)
        assertTrue(d is Decision.CloseIncomplete)
    }

    @Test
    fun `wall clock alone gets a tighter tolerance than GNSS`() {
        // The wall clock is untrusted by design, so the same gap that would
        // resume under GNSS must NOT resume under wall clock alone.
        val gapMs = 10 * 60 * 1000L

        assertEquals(
            "GNSS-backed gap within window should resume",
            Decision.Resume,
            OrphanResolver.resolve(gnssGapMs = gapMs, wallClockGapMs = null, distanceFromLastFixM = 10.0),
        )
        assertTrue(
            "same gap on wall clock alone must be treated as unreliable",
            OrphanResolver.resolve(gnssGapMs = null, wallClockGapMs = gapMs, distanceFromLastFixM = 10.0)
                    is Decision.CloseIncomplete,
        )
    }

    @Test
    fun `missing fix closes rather than assuming stationary`() {
        val d = OrphanResolver.resolve(60_000, null, null)
        assertTrue(d is Decision.CloseIncomplete)
    }
}
