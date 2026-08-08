package sy.safesy.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import sy.safesy.BuildConfig
import sy.safesy.debug.DebugScreen
import sy.safesy.debug.PermissionsScreen
import sy.safesy.debug.SensorPump

class MainActivity : ComponentActivity() {

    private var pump: SensorPump? = null

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A drive test is useless if the screen sleeps: MIUI grants location
        // "while using the app", so a sleeping screen silently stops GPS.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme {
                Surface {
                    var showPermissions by remember { mutableStateOf(false) }
                    var recording by remember { mutableStateOf(false) }

                    if (showPermissions) {
                        PermissionsScreen()
                        // Back returns to the metrics screen.
                        androidx.activity.compose.BackHandler { showPermissions = false }
                    } else {
                        DebugScreen(
                            recording = recording,
                            onToggleRecording = {
                                recording = !recording
                                if (recording) startPump() else stopPump()
                            },
                            onOpenPermissions = { showPermissions = true },
                        )
                    }
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

    /**
     * Debug harness only. The production path is the foreground service in
     * policy/ + outbox/ (Step 3); recording is driver-initiated there too.
     */
    private fun startPump() {
        if (!BuildConfig.DEBUG) return
        if (pump == null) pump = SensorPump(this)
        pump?.start()
    }

    private fun stopPump() {
        pump?.stop()
    }

    override fun onDestroy() {
        stopPump()
        super.onDestroy()
    }
}
