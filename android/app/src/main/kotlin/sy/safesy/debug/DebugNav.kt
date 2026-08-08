package sy.safesy.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Page container with back/forward arrows.
 *
 * Large tap targets: this is operated in a vehicle, sometimes by someone
 * wearing gloves, sometimes while the vehicle is moving.
 */
@Composable
fun DebugNav(
    recording: Boolean,
    onToggleRecording: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by remember { mutableIntStateOf(0) }
    val pages = listOf("METRICS", "GNSS", "PERMISSIONS")

    Column(modifier = modifier.fillMaxSize().background(Color(0xFFFFFFFF))) {
        // Arrow bar, pinned at the top so it never scrolls away.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFEDEDED)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavArrow("◀", enabled = page > 0, modifier = Modifier.weight(1f)) {
                if (page > 0) page--
            }
            Text(
                "${pages[page]}  ${page + 1}/${pages.size}",
                modifier = Modifier.weight(3f),
                color = Color(0xFF000000),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            NavArrow("▶", enabled = page < pages.lastIndex, modifier = Modifier.weight(1f)) {
                if (page < pages.lastIndex) page++
            }
        }

        when (page) {
            0 -> DebugScreen(
                recording = recording,
                onToggleRecording = onToggleRecording,
                onOpenPermissions = { page = 2 },
            )
            1 -> GnssScreen()
            else -> PermissionsScreen()
        }
    }
}

@Composable
private fun RowScope.NavArrow(
    glyph: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            color = if (enabled) Color(0xFF000000) else Color(0xFFBBBBBB),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
        )
    }
}
