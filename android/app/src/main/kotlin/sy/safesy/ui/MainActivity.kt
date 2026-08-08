package sy.safesy.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import sy.safesy.BuildConfig
import sy.safesy.debug.DebugNav
import sy.safesy.debug.SessionScreen
import sy.safesy.debug.SessionStore

class MainActivity : ComponentActivity() {

    private var pump: sy.safesy.debug.SensorPump? = null

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A drive test is useless if the screen sleeps: MIUI grants location
        // "while using the app", so a sleeping screen silently stops GPS.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val store = SessionStore(this)

        setContent {
            MaterialTheme {
                Surface {
                    var recording by remember { mutableStateOf(false) }
                    var name by remember { mutableStateOf("") }
                    var placement by remember { mutableStateOf(SessionStore.PLACEMENTS.first()) }
                    var sessionId by remember { mutableLongStateOf(0L) }
                    var startedAt by remember { mutableLongStateOf(0L) }
                    var elapsed by remember { mutableLongStateOf(0L) }
                    var marks by remember { mutableIntStateOf(0) }
                    var lastMark by remember { mutableStateOf<String?>(null) }
                    var sessions by remember { mutableStateOf(store.list()) }

                    LaunchedEffect(recording) {
                        while (recording) {
                            elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1000
                            delay(1000)
                        }
                    }

                    DebugNav(
                        recording = recording,
                        onToggleRecording = { /* session page owns start/stop */ },
                        sessionContent = {
                            SessionScreen(
                                recording = recording,
                                sessionName = name,
                                placement = placement,
                                elapsedSec = elapsed,
                                markCount = marks,
                                lastMark = lastMark,
                                onNameChange = { name = it },
                                onPlacementChange = { placement = it },
                                onStart = {
                                    val now = System.currentTimeMillis()
                                    sessionId = store.createSession(
                                        name.ifBlank { "session" }, now, placement,
                                    )
                                    startedAt = SystemClock.elapsedRealtime()
                                    elapsed = 0; marks = 0; lastMark = null
                                    recording = true
                                    startPump(store.sessionDir(sessionId))
                                },
                                onStop = {
                                    recording = false
                                    stopPump()
                                    store.finish(sessionId, elapsed)
                                    sessions = store.list()
                                },
                                onMark = { label ->
                                    store.addMark(sessionId, elapsed, label)
                                    marks++
                                    lastMark = "$label @ ${elapsed}s"
                                },
                                sessions = sessions,
                                onDelete = { id ->
                                    store.delete(id); sessions = store.list()
                                },
                            )
                        },
                    )
                }
            }
        }

        val needed = buildList {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.READ_PHONE_STATE)
        }
        if (needed.isNotEmpty()) requestPermissions.launch(needed.toTypedArray())
    }

    private fun startPump(dir: java.io.File) {
        if (!BuildConfig.DEBUG) return
        pump = sy.safesy.debug.SensorPump(this, dir)
        pump?.start()
    }

    private fun stopPump() {
        pump?.stop()
        pump = null
    }

    override fun onDestroy() {
        stopPump()
        super.onDestroy()
    }
}
