package sy.safesy.debug

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/**
 * Permission and device-readiness check. DEBUG BUILDS ONLY.
 *
 * A drive test wasted because one toggle was wrong is expensive — you have to
 * go out again. This page shows every requirement, its live status, and lets
 * you fix each one without hunting through MIUI's settings tree.
 *
 * MIUI-specific items are called out because they are the ones that silently
 * break a drive: "foreground only" location stops updates the moment the
 * screen sleeps, and battery optimisation kills the process outright.
 */
@Composable
fun PermissionsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Bumping this recomposes the list after returning from a settings screen.
    var refresh by remember { mutableIntStateOf(0) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh++ }

    val checks = remember(refresh) { buildChecks(context) }
    val allOk = checks.all { it.ok }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFFFFFFF))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            if (allOk) "READY TO DRIVE" else "NOT READY",
            color = if (allOk) Color(0xFF00701A) else Color(0xFFC10015),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
        )

        checks.forEach { c ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        when (c.action) {
                            is Action.Request -> launcher.launch(c.action.permissions)
                            is Action.OpenSettings -> {
                                runCatching { context.startActivity(c.action.intent) }
                                refresh++
                            }
                            Action.None -> refresh++
                        }
                    }
                    .padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        c.title,
                        color = Color(0xFF000000),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (c.ok) "OK" else "FIX →",
                        color = if (c.ok) Color(0xFF00701A) else Color(0xFF8A5100),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    c.detail,
                    color = if (c.ok) Color(0xFF5A5A5A) else Color(0xFF8A5100),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
            }
        }

        Text(
            "Tap any row to fix it. Re-open this page after changing a setting.",
            color = Color(0xFF5A5A5A),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private sealed interface Action {
    data class Request(val permissions: Array<String>) : Action
    data class OpenSettings(val intent: Intent) : Action
    data object None : Action
}

private data class Check(
    val title: String,
    val ok: Boolean,
    val detail: String,
    val action: Action,
)

private fun buildChecks(context: Context): List<Check> {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val phone = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
        PackageManager.PERMISSION_GRANTED
    val notif = if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    } else true

    val locationOn = runCatching {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
    }.getOrDefault(false)

    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val unrestricted = pm.isIgnoringBatteryOptimizations(context.packageName)

    val appSettings = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )

    return listOf(
        Check(
            title = "Location permission",
            ok = fine,
            detail = if (fine) "granted (precise)" else "REQUIRED — no GPS without it",
            action = Action.Request(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )),
        ),
        Check(
            title = "Location · Allow all the time",
            // Cannot be read directly without requesting BACKGROUND_LOCATION,
            // which we deliberately never request (trip-scoped collection).
            // So this is advisory: MIUI defaults to "while using the app",
            // which silently stops GPS updates when the screen sleeps.
            ok = true,
            detail = "MIUI defaults to \"while using\" — updates stop if the " +
                "screen sleeps. Keep the app open during a drive test.",
            action = Action.OpenSettings(appSettings),
        ),
        Check(
            title = "Device GPS enabled",
            ok = locationOn,
            detail = if (locationOn) "GPS provider on" else "turn Location on in system settings",
            action = Action.OpenSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)),
        ),
        Check(
            title = "Phone state (radio metrics)",
            ok = phone,
            detail = if (phone) "granted — signal strength readable"
            else "without it, rssi and network type stay unknown",
            action = Action.Request(arrayOf(Manifest.permission.READ_PHONE_STATE)),
        ),
        Check(
            title = "Notifications",
            ok = notif,
            detail = if (notif) "granted" else "needed for the foreground-service notification",
            action = if (Build.VERSION.SDK_INT >= 33) {
                Action.Request(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            } else Action.None,
        ),
        Check(
            title = "Battery · No restrictions",
            ok = unrestricted,
            detail = if (unrestricted) "unrestricted — MIUI will not kill the service"
            else "MIUI WILL kill the app mid-drive. Set Battery saver → No restrictions.",
            action = Action.OpenSettings(appSettings),
        ),
        Check(
            title = "Autostart (MIUI)",
            // Not readable via any public API — MIUI-specific and unqueryable.
            ok = true,
            detail = "Cannot be checked from code. Enable Autostart in MIUI app " +
                "settings, or the app cannot restart after being killed.",
            action = Action.OpenSettings(appSettings),
        ),
        Check(
            title = "GPS warm start",
            ok = true,
            detail = "A cold receiver needs 5-15 min of open sky. Open Google Maps " +
                "first and wait for a precise blue dot — that downloads A-GPS data.",
            action = Action.None,
        ),
    )
}

/** True when every hard requirement is satisfied. */
fun allPermissionsGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

@Suppress("unused")
private fun Activity.unusedMarker() = Unit
