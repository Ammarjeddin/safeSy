package sy.safesy.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Session recording with human-marked ground truth. DEBUG BUILDS ONLY.
 *
 * The mark buttons are deliberately large: they are pressed in a moving
 * vehicle, often by a passenger, sometimes without looking. One tap, no
 * confirmation, no menu — a mark that is awkward to record will not be
 * recorded, and an unrecorded observation is lost forever.
 */
@Composable
fun SessionScreen(
    recording: Boolean,
    sessionName: String,
    placement: String,
    elapsedSec: Long,
    markCount: Int,
    lastMark: String?,
    onNameChange: (String) -> Unit,
    onPlacementChange: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onMark: (String) -> Unit,
    sessions: List<SessionStore.Session>,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFFFFFFF))
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!recording) {
            Text("SESSION NAME", color = Dim2, fontFamily = FontFamily.Monospace,
                fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFEDEDED))
                    .padding(14.dp),
            ) {
                BasicTextField(
                    value = sessionName,
                    onValueChange = onNameChange,
                    textStyle = TextStyle(
                        color = Color.Black,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 18.sp,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (sessionName.isEmpty()) {
                    Text("e.g. highway hard brakes", color = Color(0xFF9A9A9A),
                        fontFamily = FontFamily.Monospace, fontSize = 18.sp)
                }
            }
        }

        if (!recording) {
            Text("PHONE PLACEMENT", color = Dim2, fontFamily = FontFamily.Monospace,
                fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(
                "Syrian drivers may not use a cradle. Each placement stresses " +
                    "the detector differently — record which one this is.",
                color = Dim2, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            )
            SessionStore.PLACEMENTS.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pair.forEach { p ->
                        val sel = p == placement
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (sel) Color(0xFF1B4E8A) else Color(0xFFEDEDED))
                                .clickable { onPlacementChange(p) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(p, color = if (sel) Color.White else Color.Black,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                    if (pair.size == 1) Box(Modifier.weight(1f)) {}
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (recording) Bad2 else Good2)
                .clickable { if (recording) onStop() else onStart() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (recording) "■  STOP  ($elapsedSec s · $markCount marks)" else "▶  START SESSION",
                color = Color.White, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, fontSize = 24.sp,
            )
        }

        if (recording) {
            Text(
                "TAP WHAT JUST HAPPENED",
                color = Dim2, fontFamily = FontFamily.Monospace,
                fontSize = 13.sp, fontWeight = FontWeight.Bold,
            )
            // Two per row: big enough to hit without looking.
            SessionStore.LABELS.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pair.forEach { label ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1B4E8A))
                                .clickable { onMark(label) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(label, color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                    if (pair.size == 1) Box(Modifier.weight(1f)) {}
                }
            }
            lastMark?.let {
                Text("last: $it", color = Good2, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
            }
        }

        if (sessions.isNotEmpty()) {
            Text("SAVED SESSIONS", color = Dim2, fontFamily = FontFamily.Monospace,
                fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp))
            sessions.forEach { s ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(s.name, color = Color.Black,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("${s.placement} · ${s.durationSec}s · ${s.marks} marks · ${s.events} events",
                            color = Dim2, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    }
                    Text("delete", color = Bad2,
                        fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                        modifier = Modifier.clickable { onDelete(s.id) })
                }
            }
        }
    }
}

private val Dim2 = Color(0xFF5A5A5A)
private val Good2 = Color(0xFF00701A)
private val Bad2 = Color(0xFFC10015)
