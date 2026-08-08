package sy.safesy.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import sy.safesy.detect.DetectedEvent

/**
 * Live metrics for the drive-test debug screen.
 *
 * This exists because the Step 4 drive spike (SPEC.md §8) is a go/no-go gate
 * measured on a real bus, on a real route, in summer heat — and a tester in a
 * moving vehicle cannot read logcat. Everything that decides pass/fail has to
 * be visible on the screen.
 *
 * DEBUG BUILDS ONLY. This is not Drive Mode: it is dense, it is in English,
 * and it deliberately shows raw numbers that would be a distraction — and a
 * privacy leak — in the driver-facing app.
 */
object DebugMetrics {

    data class Snapshot(
        // --- Sensors ---
        val imuHz: Float = 0f,
        val gnssHz: Float = 0f,
        val imuSamples: Long = 0,
        val gnssFixes: Long = 0,

        // --- Position ---
        val lat: Double = 0.0,
        val lon: Double = 0.0,
        val speedKmh: Float = 0f,
        val hdop: Float = 0f,
        val gpsProvider: String = "—",
        /** False when the fix came from network/fused — gnss_t_ms must not be set. */
        val fixIsGps: Boolean = false,
        /** Satellites the chip can see, and how many are used in a fix. */
        val satsVisible: Int = 0,
        val satsUsed: Int = 0,
        /** Per-satellite carrier-to-noise density, dB-Hz. Empty = nothing received. */
        val satCn0: List<Float> = emptyList(),
        /** Seconds since the first satellite was seen — how long it has been trying. */
        val gnssSearchingSec: Long = 0,

        // --- Detection ---
        val calibrated: Boolean = false,
        val calibrationProgress: Float = 0f,
        val mountSuppressed: Boolean = false,
        val linearAccelMag: Float = 0f,
        val verticalAccel: Float = 0f,
        val horizontalAccel: Float = 0f,
        val gpsAccelMps2: Float = 0f,
        val recentEvents: List<DetectedEvent> = emptyList(),
        val eventCounts: Map<DetectedEvent.Kind, Int> = emptyMap(),

        // --- Radio / coverage (§S3.4) ---
        val rat: String = "—",
        val rssiDbm: Int = 0,
        val dataOk: Boolean = false,
        val coverageGapSeconds: Long = 0,
        val longestGapSeconds: Long = 0,

        // --- Outbox ---
        val pointsStored: Long = 0,
        val batchesSealed: Long = 0,
        val batchesPending: Int = 0,
        val batchesUploaded: Long = 0,
        val bytesUploaded: Long = 0,
        val bytesDownloaded: Long = 0,
        val lastUploadMs: Long = 0,

        // --- Health: the numbers the drive spike actually gates on ---
        val batteryPct: Int = 0,
        val batteryTempC: Float = 0f,
        val charging: Boolean = false,
        val tripElapsedSec: Long = 0,
        val serviceRestarts: Int = 0,

        // --- Phone usage. Recorded to make the safety data INTERPRETABLE
        // (handling is the largest source of phantom IMU events), never as a
        // scoring input — see PhoneUsageTracker's rationale.
        val appForeground: Boolean = true,
        val backgroundSec: Long = 0,
        val backgroundEpisodes: Int = 0,
        val nearEar: Boolean = false,
        val nearEarSec: Long = 0,
        val nearEarEpisodes: Int = 0,
    ) {
        /**
         * Projected monthly data at the current rate, against 208 driving-hours
         * (8 h/day x 26 days). This is what validates or refutes the
         * ~11.9 MB/month claim — the number the whole design is sized around.
         */
        val projectedMbPerMonth: Float
            get() = if (tripElapsedSec < 60) 0f
            else ((bytesUploaded + bytesDownloaded).toFloat() / tripElapsedSec) * 3600f * 208f / 1024f / 1024f

        val bytesPerHour: Float
            get() = if (tripElapsedSec < 60) 0f
            else (bytesUploaded + bytesDownloaded).toFloat() / tripElapsedSec * 3600f
    }

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state

    fun update(transform: (Snapshot) -> Snapshot) {
        _state.value = transform(_state.value)
    }

    fun reset() {
        _state.value = Snapshot()
    }
}
