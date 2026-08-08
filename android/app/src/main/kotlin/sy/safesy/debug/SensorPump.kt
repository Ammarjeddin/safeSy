package sy.safesy.debug

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.GnssStatus
import android.location.LocationManager
import android.net.TrafficStats
import java.io.File
import android.os.BatteryManager
import android.os.Bundle
import android.telephony.TelephonyManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import sy.safesy.detect.DetectedEvent
import sy.safesy.detect.DrivingDetector
import sy.safesy.detect.GnssSample
import sy.safesy.detect.ImuSample
import sy.safesy.detect.VehicleProfile

/**
 * Feeds real device sensors into the detection engine and publishes metrics.
 *
 * This is the DEBUG harness — the real production path is the foreground
 * service in `policy/` + `outbox/` (Step 3). It exists now so the detector can
 * be exercised against real hardware before that machinery is written, which
 * is the fastest way to find out whether the thresholds are anywhere near right.
 *
 * Deliberately uses LocationManager.GPS_PROVIDER rather than FusedLocation:
 *  - it works on de-Googled and grey-market devices, which the target fleet has
 *  - only GPS_PROVIDER fixes carry satellite time; network/fused fixes return
 *    the untrusted system clock and must never populate gnss_t_ms
 */
class SensorPump(
    private val context: Context,
    /** When set, traces are written into that session's folder. */
    private val sessionDir: File? = null,
) : SensorEventListener, LocationListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val detector = DrivingDetector(VehicleProfile.BUS)

    private val accel = FloatArray(3)
    private val gyro = FloatArray(3)
    private var haveAccel = false

    private var imuCount = 0L
    private var gnssCount = 0L
    private var startedAtMs = 0L
    private var lastRateCalcMs = 0L
    private var imuAtLastCalc = 0L
    private var gnssAtLastCalc = 0L

    private val eventCounts = mutableMapOf<DetectedEvent.Kind, Int>()
    private val recentEvents = mutableListOf<DetectedEvent>()

    // Real per-app traffic, measured by the OS. This is what validates (or
    // refutes) the ~11.9 MB/month claim — the whole point of the drive test.
    private var txAtStart = 0L
    private var rxAtStart = 0L
    private var coverageGapStartMs = 0L
    private var longestGapSec = 0L

    /**
     * Per-second CSV trace of the drive, written to app-external storage so it
     * survives the app being killed and can be pulled over adb afterwards.
     *
     * A drive test with no record is an anecdote. This is the record.
     */
    private var traceFile: File? = null
    private var eventFile: File? = null

    fun start() {
        startedAtMs = SystemClock.elapsedRealtime()
        lastRateCalcMs = startedAtMs
        DebugMetrics.reset()

        val uid = android.os.Process.myUid()
        txAtStart = TrafficStats.getUidTxBytes(uid)
        rxAtStart = TrafficStats.getUidRxBytes(uid)

        val dir = sessionDir ?: context.getExternalFilesDir(null)
        val stamp = System.currentTimeMillis()
        traceFile = File(dir, if (sessionDir != null) "trace.csv" else "drive-$stamp.csv").apply {
            appendText("elapsed_s,lat,lon,speed_kmh,hdop,provider,imu_hz,gnss_hz," +
                "accel_total,accel_vert,accel_horiz,gps_accel,calibrated,mount_suppressed," +
                "sats_seen,sats_used,app_fg,bg_sec,near_ear,ear_sec," +
                "rat,rssi,data_ok,batt_pct,batt_temp_c,charging,tx_bytes,rx_bytes\n")
        }
        eventFile = File(dir, if (sessionDir != null) "events.csv" else "events-$stamp.csv").apply {
            appendText("elapsed_s,kind,severity,peak,duration_ms\n")
        }

        // SENSOR_DELAY_GAME ~= 50 Hz, which is what the spec assumes.
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            // Request BOTH providers.
            //
            // A cold GNSS start needs 30-90 s of clear sky to download ephemeris.
            // On a short trip — or one starting indoors, in an urban canyon, or
            // behind a coated windscreen — the receiver may never finish, and
            // requesting GPS alone means the app shows NOTHING rather than a
            // degraded position.
            //
            // The strict rule still holds: only genuine GPS_PROVIDER fixes are
            // authoritative for time (network fixes carry the untrusted system
            // clock), which `fromGpsProvider` and the `provider` field track.
            runCatching {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000L, 0f, this,
                )
            }
            runCatching {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 1000L, 0f, this,
                )
            }
            // Seed from the last known fix so the screen is useful immediately
            // instead of blank for the first minute.
            runCatching {
                locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    ?.let { onLocationChanged(it) }
            }
            // Satellite visibility — lets a tester SEE acquisition progress
            // rather than staring at an empty screen wondering if it is broken.
            runCatching {
                locationManager.registerGnssStatusCallback(gnssCallback, null)
            }
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        runCatching { locationManager.removeUpdates(this) }
        runCatching { locationManager.unregisterGnssStatusCallback(gnssCallback) }
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var visible = 0
            var used = 0
            val cn0 = ArrayList<Float>(status.satelliteCount)
            for (i in 0 until status.satelliteCount) {
                visible++
                cn0 += status.getCn0DbHz(i)
                if (status.usedInFix(i)) used++
            }
            DebugMetrics.update { it.copy(satsVisible = visible, satsUsed = used, satCn0 = cn0) }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                event.values.copyInto(accel, endIndex = 3)
                haveAccel = true
            }
            Sensor.TYPE_GYROSCOPE -> event.values.copyInto(gyro, endIndex = 3)
            else -> return
        }
        if (!haveAccel || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val tMs = event.timestamp / 1_000_000  // sensor timestamps are ns since boot
        detector.onImu(ImuSample(tMs, accel[0], accel[1], accel[2], gyro[0], gyro[1], gyro[2]))
        imuCount++
        drainAndPublish(tMs)
    }

    override fun onLocationChanged(loc: Location) {
        gnssCount++
        // Satellite-derived time ONLY from GPS_PROVIDER (see class doc).
        val isGps = loc.provider == LocationManager.GPS_PROVIDER
        val hdop = if (loc.hasAccuracy()) loc.accuracy / 5f else 99f  // rough proxy

        // Network fixes are display-only: they have ~100 m accuracy and no
        // usable speed, so feeding them to the detector would manufacture
        // phantom acceleration from position noise.
        if (isGps) detector.onGnss(
            GnssSample(
                tMs = loc.elapsedRealtimeNanos / 1_000_000,
                lat = loc.latitude,
                lon = loc.longitude,
                speedMps = if (loc.hasSpeed()) loc.speed else 0f,
                headingDeg = if (loc.hasBearing()) loc.bearing else 0f,
                hdop = hdop,
                fromGpsProvider = isGps,
            )
        )
        DebugMetrics.update {
            it.copy(
                lat = loc.latitude, lon = loc.longitude,
                speedKmh = (if (loc.hasSpeed()) loc.speed else 0f) * 3.6f,
                hdop = hdop,
                gpsProvider = loc.provider ?: "—",
                fixIsGps = isGps,
                gnssFixes = gnssCount,
            )
        }
    }

    private fun drainAndPublish(tMs: Long) {
        detector.drain().forEach { e ->
            eventCounts[e.kind] = (eventCounts[e.kind] ?: 0) + 1
            recentEvents += e
            if (recentEvents.size > 20) recentEvents.removeAt(0)
            val el = (SystemClock.elapsedRealtime() - startedAtMs) / 1000
            runCatching {
                eventFile?.appendText("$el,${e.kind},${e.severity},${e.peak},${e.durationMs}\n")
            }
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastRateCalcMs < 1000) return

        val dt = (now - lastRateCalcMs) / 1000f
        val imuHz = (imuCount - imuAtLastCalc) / dt
        val gnssHz = (gnssCount - gnssAtLastCalc) / dt
        lastRateCalcMs = now
        imuAtLastCalc = imuCount
        gnssAtLastCalc = gnssCount

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
        val charging = bm?.isCharging ?: false
        val tempC = readBatteryTemp()

        val uid = android.os.Process.myUid()
        val tx = (TrafficStats.getUidTxBytes(uid) - txAtStart).coerceAtLeast(0)
        val rx = (TrafficStats.getUidRxBytes(uid) - rxAtStart).coerceAtLeast(0)

        val (rat, rssi, dataOk) = readRadio()
        // A dead zone is DATA, not absence of data — record it explicitly.
        if (rat == "NONE" || !dataOk) {
            if (coverageGapStartMs == 0L) coverageGapStartMs = now
        } else if (coverageGapStartMs != 0L) {
            longestGapSec = maxOf(longestGapSec, (now - coverageGapStartMs) / 1000)
            coverageGapStartMs = 0L
        }
        val currentGapSec = if (coverageGapStartMs != 0L) (now - coverageGapStartMs) / 1000 else 0L

        val elapsed = (now - startedAtMs) / 1000
        val snap = DebugMetrics.state.value
        runCatching {
            traceFile?.appendText(
                "$elapsed,${snap.lat},${snap.lon},${snap.speedKmh},${snap.hdop},${snap.gpsProvider}," +
                "$imuHz,$gnssHz,${detector.lastLinear?.totalMag ?: 0f},${detector.lastLinear?.vertical ?: 0f}," +
                "${detector.lastLinear?.horizontalMag ?: 0f},${detector.lastGpsAccel}," +
                "${detector.isCalibrated},${detector.isMountSuppressed(now)}," +
                "${snap.satsVisible},${snap.satsUsed},${snap.appForeground},${snap.backgroundSec},${snap.nearEar},${snap.nearEarSec}," +
                "$rat,$rssi,$dataOk,$pct,$tempC,$charging,$tx,$rx\n"
            )
        }

        DebugMetrics.update {
            it.copy(
                imuHz = imuHz, gnssHz = gnssHz, imuSamples = imuCount,
                eventCounts = eventCounts.toMap(),
                recentEvents = recentEvents.toList(),
                batteryPct = pct, charging = charging, batteryTempC = tempC,
                tripElapsedSec = (now - startedAtMs) / 1000,
                calibrated = detector.isCalibrated,
                calibrationProgress = detector.calibrationProgress,
                mountSuppressed = detector.isMountSuppressed(SystemClock.elapsedRealtime()),
                linearAccelMag = detector.lastLinear?.totalMag ?: 0f,
                verticalAccel = detector.lastLinear?.vertical ?: 0f,
                horizontalAccel = detector.lastLinear?.horizontalMag ?: 0f,
                gpsAccelMps2 = detector.lastGpsAccel,
                bytesUploaded = tx, bytesDownloaded = rx,
                rat = rat, rssiDbm = rssi, dataOk = dataOk,
                coverageGapSeconds = currentGapSec,
                longestGapSeconds = maxOf(longestGapSec, currentGapSec),
            )
        }
    }

    /** Radio access technology, signal strength, and whether data actually works. */
    @Suppress("DEPRECATION")
    private fun readRadio(): Triple<String, Int, Boolean> = runCatching {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val rat = when (tm.dataNetworkType) {
            TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
            TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
            TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA -> "HSPA"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_NR -> "NR"
            TelephonyManager.NETWORK_TYPE_UNKNOWN -> "NONE"
            else -> "OTHER"
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val ok = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        // Signal strength needs READ_PHONE_STATE; 0 means unavailable, not zero signal.
        // Android returns Integer.MAX_VALUE (and sometimes MIN_VALUE) as the
        // "unknown" sentinel — displaying it raw reads as a nonsense value.
        val raw = runCatching { tm.signalStrength?.cellSignalStrengths?.firstOrNull()?.dbm ?: 0 }
            .getOrDefault(0)
        val rssi = if (raw == Int.MAX_VALUE || raw == Int.MIN_VALUE || raw > 0) 0 else raw
        Triple(rat, rssi, ok)
    }.getOrDefault(Triple("—", 0, false))

    private fun readBatteryTemp(): Float =
        runCatching {
            val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        }.getOrDefault(0f)

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
}
