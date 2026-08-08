package sy.safesy.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * GNSS acquisition diagnostics.
 *
 * Exists because a drive test produced zero satellite fixes and the metrics
 * screen could only say "0 used / 0 seen" — true, but not actionable while
 * standing outside wondering whether to keep waiting.
 *
 * This shows per-satellite signal strength (C/N0), so the distinction that
 * matters is visible: **are satellites being SEEN but not USED** (acquiring,
 * keep waiting) **or is nothing being received at all** (antenna, shielding,
 * or a hardware fault — waiting will not help).
 */
@Composable
fun GnssScreen(modifier: Modifier = Modifier) {
    val m by DebugMetrics.state.collectAsStateWithLifecycle()

    // The monitor is owned by the Activity for the whole app session, not by
    // this page — a receiver that powers down when you navigate away has to
    // cold-start again, costing 30-90 s every time.

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFFFFFFF))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val verdict = when {
            m.satsUsed >= 4 -> "FIX — GPS working" to Color(0xFF00701A)
            m.satsUsed in 1..3 -> "ACQUIRING — need 4+" to Color(0xFF8A5100)
            m.satsVisible > 0 -> "SEEING SATS, NO LOCK — keep waiting" to Color(0xFF8A5100)
            else -> "NO SATELLITES RECEIVED" to Color(0xFFC10015)
        }
        Text(
            verdict.first,
            color = verdict.second,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Big("SEEN", "${m.satsVisible}")
            Big("USED", "${m.satsUsed}", if (m.satsUsed >= 4) Color(0xFF00701A) else Color(0xFFC10015))
        }

        if (m.satCn0.isNotEmpty()) {
            val best = m.satCn0.maxOrNull() ?: 0f
            val strong = m.satCn0.count { it >= 35f }
            Text(
                "searching ${m.gnssSearchingSec}s · best ${"%.0f".format(best)} dB-Hz · " +
                    "$strong above 35",
                color = Color(0xFF5A5A5A),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
            )
        }

        Text("SIGNAL STRENGTH (C/N0 dB-Hz)", color = Color(0xFF5A5A5A),
            fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold)

        if (m.satCn0.isEmpty()) {
            Text(
                "No satellite signals at all.\n\n" +
                    "That is NOT a slow cold start — a cold receiver still SEES\n" +
                    "satellites while it works out where it is.\n\n" +
                    "Likely causes:\n" +
                    "  • indoors / under metal / heated windscreen\n" +
                    "  • GNSS antenna fault\n" +
                    "  • MIUI power restriction on the GNSS engine\n\n" +
                    "Test: open Google Maps outdoors. If Maps also cannot get a\n" +
                    "precise blue dot, the problem is the phone, not this app.",
                color = Color(0xFF000000),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
            )
        } else {
            // Bar chart: usable signal is ~25+ dB-Hz, good is 35+.
            m.satCn0.sortedDescending().take(20).forEach { cn0 ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        "%2.0f".format(cn0),
                        color = Color(0xFF5A5A5A),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        modifier = Modifier.width(34.dp),
                    )
                    Box(
                        cn0 = cn0,
                    )
                }
            }
            Text(
                "25+ = usable · 35+ = good. Four satellites above 25 are needed for a fix.",
                color = Color(0xFF5A5A5A),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }

        Text(
            "provider: ${m.gpsProvider}   hdop: ${"%.1f".format(m.hdop)}",
            color = Color(0xFF5A5A5A),
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun Big(label: String, value: String, color: Color = Color(0xFF000000)) {
    Column {
        Text(label, color = Color(0xFF5A5A5A), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        Text(value, color = color, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold, fontSize = 40.sp)
    }
}

@Composable
private fun Box(cn0: Float) {
    val frac = (cn0 / 50f).coerceIn(0f, 1f)
    val color = when {
        cn0 >= 35f -> Color(0xFF00701A)
        cn0 >= 25f -> Color(0xFF8A5100)
        else -> Color(0xFFC10015)
    }
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth(frac)
            .height(16.dp)
            .background(color),
    )
}
