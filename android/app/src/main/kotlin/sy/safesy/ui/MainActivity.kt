package sy.safesy.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.core.content.ContextCompat
import sy.safesy.BuildConfig
import sy.safesy.debug.DebugScreen
import sy.safesy.debug.SensorPump

class MainActivity : ComponentActivity() {

    private var pump: SensorPump? = null

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { startPumpIfDebug() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface { DebugScreen() } } }

        val needed = buildList {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.READ_PHONE_STATE)
        }
        if (needed.isEmpty()) startPumpIfDebug() else requestPermissions.launch(needed.toTypedArray())
    }

    /**
     * Debug builds only. The production path is the foreground service in
     * policy/ + outbox/ (Step 3); this harness exists so the detector can be
     * exercised against real hardware before that machinery is written.
     */
    private fun startPumpIfDebug() {
        if (!BuildConfig.DEBUG) return
        if (pump == null) pump = SensorPump(this)
        pump?.start()
    }

    override fun onDestroy() {
        pump?.stop()
        super.onDestroy()
    }
}
