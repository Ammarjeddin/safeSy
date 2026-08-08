package sy.safesy.trip

/**
 * Trip lifecycle. See SPEC.md §S2.
 *
 * The trip is the ONLY unit of collection — nothing happens outside one.
 *
 * IDLE ──[Start]──> ACTIVE ──[End]──> CLOSING ──(drained)──> SETTLED
 *                      │
 *                      ├─[<5 km/h for 45 min]──> PAUSED (motion resumes)
 *                      └─[process death]───────> ORPHANED
 *                                                   ├─ resumable → ACTIVE (same trip_id)
 *                                                   └─ else      → CLOSED_INCOMPLETE
 */
enum class TripState {
    /**
     * Zero sensor subscriptions, zero location requests, zero stored points,
     * zero network. The app is inert.
     *
     * This is a PRIVACY GUARANTEE, not just a battery optimization, and it is
     * stated plainly in Arabic onboarding — it is what makes voluntary
     * adoption credible.
     */
    IDLE,

    /** Foreground service running, sensors live, points persisting at 1 Hz. */
    ACTIVE,

    /**
     * Auto-entered after 45 min under 5 km/h; motion resumes it.
     *
     * PAUSE, not auto-end: checkpoint and border queues are routine in Syria.
     * Auto-ending there would split one journey into two trips and lose the
     * post-queue driving until the driver noticed.
     */
    PAUSED,

    /**
     * Trip ended, outbox still draining. Sensors off; network only.
     * If the process dies here the drain resumes on next launch — the trip
     * is not lost.
     */
    CLOSING,

    /** Outbox empty. Terminal, healthy. */
    SETTLED,

    /**
     * Process died while ACTIVE — OEM kill, OOM, reboot, or force-swipe.
     * On this device population that is ROUTINE, not exceptional.
     *
     * An ORPHANED trip STILL DRAINS ITS OUTBOX. A crash is exactly the
     * scenario where the process dies *and* the data matters most.
     */
    ORPHANED,

    /** Auto-ended at the last known fix. Still drains. Terminal. */
    CLOSED_INCOMPLETE;

    /** Sensors run only here. Everything else is storage and network. */
    val collectsSensorData: Boolean get() = this == ACTIVE

    /** Outbox drains in every state except IDLE — including the bad ones. */
    val drainsOutbox: Boolean get() = this != IDLE

    val isTerminal: Boolean get() = this == SETTLED || this == CLOSED_INCOMPLETE
}

/**
 * Resolves an ORPHANED trip on next launch.
 *
 * ⚠️ Which clock? `elapsedRealtime` has just reset (that is what boot_id
 * detects) and the wall clock is untrusted by design (§S3.1). So prefer GNSS
 * time deltas, and when neither is available fall back to wall clock with a
 * WIDE tolerance and a data-quality flag.
 *
 * BIAS TOWARD CLOSED_INCOMPLETE: wrongly splitting one trip into two is far
 * cheaper than wrongly merging two separate journeys into one.
 */
object OrphanResolver {
    const val RESUME_WINDOW_MS = 15 * 60 * 1000L
    const val RESUME_RADIUS_M = 500.0

    sealed interface Decision {
        /** Same trip_id, seq continues. A reboot mid-route is ONE trip. */
        data object Resume : Decision
        data class CloseIncomplete(val reason: String) : Decision
    }

    fun resolve(
        gnssGapMs: Long?,
        wallClockGapMs: Long?,
        distanceFromLastFixM: Double?,
    ): Decision {
        // GNSS time is authoritative when we have it on both sides.
        val gap = gnssGapMs ?: wallClockGapMs
            ?: return Decision.CloseIncomplete("no trustworthy clock")

        val toleranceMs = if (gnssGapMs != null) RESUME_WINDOW_MS else RESUME_WINDOW_MS / 3
        if (gap > toleranceMs) return Decision.CloseIncomplete("gap ${gap}ms exceeds window")

        // Proximity needs one location fix while IDLE. That is carved out of the
        // IDLE guarantee EXPLICITLY (§S2.1) — a single one-shot fix at launch,
        // only when an ORPHANED trip exists, disclosed in onboarding.
        // It must never become background location.
        val dist = distanceFromLastFixM ?: return Decision.CloseIncomplete("no fix to compare")
        if (dist > RESUME_RADIUS_M) return Decision.CloseIncomplete("moved ${dist.toInt()}m")

        return Decision.Resume
    }
}
