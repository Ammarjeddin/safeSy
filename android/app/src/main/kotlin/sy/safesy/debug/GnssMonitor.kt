package sy.safesy.debug

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.LocationManager
import androidx.core.content.ContextCompat

/**
 * Standalone GNSS reception monitor, independent of trip recording.
 *
 * Checking whether the receiver can even see satellites must NOT require
 * starting a fake trip — that would write a junk trace and conflate "is the
 * antenna working" with "am I testing driving behaviour".
 *
 * Registers only the status callback (satellite visibility and C/N0), not
 * location updates, so it is cheap enough to leave running while the GNSS
 * page is open.
 */
class GnssMonitor(private val context: Context) {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var registered = false
    private var firstSeenAtMs = 0L

    private val callback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            val cn0 = ArrayList<Float>(status.satelliteCount)
            for (i in 0 until status.satelliteCount) {
                cn0 += status.getCn0DbHz(i)
                if (status.usedInFix(i)) used++
            }
            if (cn0.isNotEmpty() && firstSeenAtMs == 0L) {
                firstSeenAtMs = android.os.SystemClock.elapsedRealtime()
            }
            val searchingSec =
                if (firstSeenAtMs == 0L) 0L
                else (android.os.SystemClock.elapsedRealtime() - firstSeenAtMs) / 1000

            DebugMetrics.update {
                it.copy(
                    satsVisible = status.satelliteCount,
                    satsUsed = used,
                    satCn0 = cn0,
                    gnssSearchingSec = searchingSec,
                )
            }
        }
    }

    fun start() {
        if (registered) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        // A status callback alone does not always wake the GNSS engine — some
        // HALs only power it up for an active location request. Request one at
        // a slow interval purely to keep the receiver running.
        runCatching {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, keepAlive,
            )
        }
        registered = runCatching {
            locationManager.registerGnssStatusCallback(callback, null)
        }.getOrDefault(false)
    }

    fun stop() {
        if (!registered) return
        runCatching { locationManager.unregisterGnssStatusCallback(callback) }
        runCatching { locationManager.removeUpdates(keepAlive) }
        registered = false
        firstSeenAtMs = 0L
    }

    /** Consumes fixes only to keep the engine powered; the pump owns real data. */
    private val keepAlive = android.location.LocationListener { loc ->
        DebugMetrics.update {
            it.copy(
                lat = loc.latitude, lon = loc.longitude,
                gpsProvider = loc.provider ?: "—",
                fixIsGps = loc.provider == LocationManager.GPS_PROVIDER,
                hdop = if (loc.hasAccuracy()) loc.accuracy / 5f else 99f,
            )
        }
    }
}
