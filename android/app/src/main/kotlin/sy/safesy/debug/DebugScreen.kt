package sy.safesy.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

/**
 * Drive-test debug screen. DEBUG BUILDS ONLY.
 *
 * The Step 4 drive spike is a go/no-go gate measured on a real bus over 8
 * hours in summer heat — and a tester in a moving vehicle cannot read logcat.
 * Every number that decides pass/fail is on this screen.
 *
 * Deliberately NOT Drive Mode: dense, English, monospace, and showing raw
 * values that would be a distraction (and a privacy leak) in the driver app.
 *
 * Colour is used only to answer "is this OK at a glance" — green fine, amber
 * watch, red problem. A tester glancing at this while a bus is moving should
 * be able to spot trouble without reading.
 */
@Composable
fun DebugScreen(modifier: Modifier = Modifier) {
    val m by DebugMetrics.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "safeSy · DRIVE TEST",
            color = Color(0xFF4CAF50),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )

        // --- The go/no-go numbers, first because they decide the spike ---
        Section("HEALTH") {
            Metric("battery", "${m.batteryPct}%${if (m.charging) " ⚡" else ""}",
                warn = !m.charging && m.batteryPct < 40, bad = !m.charging && m.batteryPct < 20)
            // Thermal is a named risk: a dash-cradled phone in a 45 °C+ cabin
            // will throttle, stop charging, or shut down.
            Metric("temp", "${"%.1f".format(m.batteryTempC)} °C",
                warn = m.batteryTempC > 40f, bad = m.batteryTempC > 45f)
            Metric("trip elapsed", formatDuration(m.tripElapsedSec))
            Metric("service restarts", "${m.serviceRestarts}",
                warn = m.serviceRestarts > 0, bad = m.serviceRestarts > 2)
        }

        Section("SENSORS") {
            Metric("IMU", "${"%.1f".format(m.imuHz)} Hz", warn = m.imuHz < 40f, bad = m.imuHz < 25f)
            Metric("GNSS", "${"%.2f".format(m.gnssHz)} Hz", warn = m.gnssHz < 0.8f, bad = m.gnssHz < 0.4f)
            Metric("samples", "${m.imuSamples} imu / ${m.gnssFixes} fix")
        }

        Section("POSITION") {
            Metric("speed", "${m.speedKmh.roundToInt()} km/h")
            Metric("lat/lon", "${"%.5f".format(m.lat)}, ${"%.5f".format(m.lon)}")
            Metric("hdop", "%.1f".format(m.hdop), warn = m.hdop > 3f, bad = m.hdop > 5f)
            // Only GPS_PROVIDER fixes carry satellite time; network/fused fixes
            // return the untrusted system clock and must not set gnss_t_ms.
            Metric("provider", m.gpsProvider, bad = !m.fixIsGps)
        }

        Section("DETECTION") {
            if (!m.calibrated) {
                Metric("calibrating", "${(m.calibrationProgress * 100).roundToInt()}%", warn = true)
            } else {
                Metric("calibrated", "yes")
            }
            if (m.mountSuppressed) Metric("mount", "SHIFTED — suppressed", bad = true)
            Metric("accel |a|", "${"%.2f".format(m.linearAccelMag)} m/s²")
            Metric("  vertical", "%.2f".format(m.verticalAccel))
            Metric("  horizontal", "%.2f".format(m.horizontalAccel))
            Metric("gps accel", "${"%.2f".format(m.gpsAccelMps2)} m/s²")
        }

        if (m.eventCounts.isNotEmpty()) {
            Section("EVENTS") {
                m.eventCounts.forEach { (kind, count) ->
                    Metric(kind.name.lowercase(), "$count",
                        bad = kind.name == "POSSIBLE_CRASH")
                }
            }
        }

        // Recording rat=NONE explicitly is the point of the radio channel —
        // a dead zone is data, not absence of data.
        Section("RADIO / COVERAGE") {
            Metric("rat", m.rat, warn = m.rat in setOf("GPRS", "EDGE"), bad = m.rat == "NONE")
            Metric("rssi", "${m.rssiDbm} dBm", warn = m.rssiDbm < -100, bad = m.rssiDbm < -110)
            Metric("data", if (m.dataOk) "ok" else "DOWN", bad = !m.dataOk)
            if (m.coverageGapSeconds > 0) {
                Metric("current gap", formatDuration(m.coverageGapSeconds), warn = true)
            }
            Metric("longest gap", formatDuration(m.longestGapSeconds))
        }

        Section("OUTBOX") {
            Metric("points stored", "${m.pointsStored}")
            Metric("sealed", "${m.batchesSealed}")
            Metric("pending", "${m.batchesPending}",
                warn = m.batchesPending > 30, bad = m.batchesPending > 120)
            Metric("uploaded", "${m.batchesUploaded} batches")
        }

        // The section that validates the design's headline claim.
        Section("DATA USAGE (measured by OS)") {
            Metric("tx", formatBytes(m.bytesUploaded))
            Metric("rx", formatBytes(m.bytesDownloaded))
            Metric("per hour", formatBytes(m.bytesPerHour.toLong()))
            Metric("→ MB/month", "%.1f".format(m.projectedMbPerMonth) + "  (target 11.9)",
                warn = m.projectedMbPerMonth > 20f, bad = m.projectedMbPerMonth > 40f)
        }

        if (m.recentEvents.isNotEmpty()) {
            Section("RECENT") {
                m.recentEvents.takeLast(8).reversed().forEach { e ->
                    Text(
                        "${e.kind.name.take(12).padEnd(13)} sev=${e.severity.toString().padStart(4)} " +
                            "peak=${"%.1f".format(e.peak)}",
                        color = Color(0xFFBBBBBB),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            title,
            color = Color(0xFF666666),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        content()
    }
}

@Composable
private fun Metric(label: String, value: String, warn: Boolean = false, bad: Boolean = false) {
    val color = when {
        bad -> Color(0xFFE53935)
        warn -> Color(0xFFFFB300)
        else -> Color(0xFFE0E0E0)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color(0xFF888888), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Text(value, color = color, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
            fontWeight = if (bad || warn) FontWeight.Bold else FontWeight.Normal)
    }
}

private fun formatDuration(sec: Long): String = when {
    sec < 60 -> "${sec}s"
    sec < 3600 -> "${sec / 60}m ${sec % 60}s"
    else -> "${sec / 3600}h ${(sec % 3600) / 60}m"
}

private fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "${b / 1024} KB"
    else -> "%.1f MB".format(b / 1024f / 1024f)
}
