package sy.safesy.debug

import android.app.Activity
import android.app.Application
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.SystemClock

/**
 * Tracks whether the app is in the foreground and whether the phone appears to
 * be in the driver's hand.
 *
 * WHY THIS EXISTS — and why it is a *measurement*, not an enforcement feature:
 *
 * Syrian drivers take calls and send messages while driving. Whatever the rules
 * say, it happens, and a data-collection system that pretends otherwise
 * produces data that does not describe reality. Two concrete consequences:
 *
 *  1. **Data quality.** When the app is backgrounded, MIUI may throttle or stop
 *     sensor delivery. A gap in the trace could be a coverage dead zone, a
 *     killed process, or the driver answering a call — indistinguishable after
 *     the fact unless we record which.
 *
 *  2. **Detection validity.** A phone lifted to an ear rotates ~90 degrees and
 *     accelerates hard. Without knowing that happened, the detector sees
 *     violent cornering. Handling is the single largest source of phantom IMU
 *     events, and it must be separable from real driving.
 *
 * ⚠️ **This is deliberately NOT phone-handling detection for scoring.**
 * DESIGN.md §3.3 rejects that: it turns a safety companion into a surveillance
 * tool and undermines the voluntary-adoption story the whole project rests on.
 * The signal is recorded to make the *safety* data interpretable, and it should
 * stay in debug builds and aggregate research — never in a driver's score.
 */
class PhoneUsageTracker(
    private val app: Application,
) : Application.ActivityLifecycleCallbacks, SensorEventListener {

    private val sensorManager =
        app.getSystemService(Application.SENSOR_SERVICE) as SensorManager

    private var foreground = false
    private var lastBackgroundAtMs = 0L
    private var totalBackgroundMs = 0L
    private var backgroundEpisodes = 0

    /** Set while the proximity sensor is covered — phone likely at an ear. */
    private var nearEar = false
    private var nearEarSinceMs = 0L
    private var totalNearEarMs = 0L
    private var nearEarEpisodes = 0

    fun start() {
        app.registerActivityLifecycleCallbacks(this)
        sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        runCatching { app.unregisterActivityLifecycleCallbacks(this) }
        runCatching { sensorManager.unregisterListener(this) }
    }

    fun reset() {
        totalBackgroundMs = 0
        backgroundEpisodes = 0
        totalNearEarMs = 0
        nearEarEpisodes = 0
    }

    private fun publish() {
        val bgNow = if (!foreground && lastBackgroundAtMs > 0) {
            SystemClock.elapsedRealtime() - lastBackgroundAtMs
        } else 0L
        val earNow = if (nearEar && nearEarSinceMs > 0) {
            SystemClock.elapsedRealtime() - nearEarSinceMs
        } else 0L
        DebugMetrics.update {
            it.copy(
                appForeground = foreground,
                backgroundSec = (totalBackgroundMs + bgNow) / 1000,
                backgroundEpisodes = backgroundEpisodes,
                nearEar = nearEar,
                nearEarSec = (totalNearEarMs + earNow) / 1000,
                nearEarEpisodes = nearEarEpisodes,
            )
        }
    }

    // --- Foreground / background -----------------------------------------

    override fun onActivityResumed(activity: Activity) {
        if (!foreground && lastBackgroundAtMs > 0) {
            totalBackgroundMs += SystemClock.elapsedRealtime() - lastBackgroundAtMs
        }
        foreground = true
        publish()
    }

    override fun onActivityPaused(activity: Activity) {
        foreground = false
        lastBackgroundAtMs = SystemClock.elapsedRealtime()
        backgroundEpisodes++
        publish()
    }

    // --- Proximity: a proxy for "held to the ear" ------------------------

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_PROXIMITY) return
        // Most proximity sensors report a small value (often 0) when covered.
        val covered = event.values[0] < (event.sensor.maximumRange / 2f)
        if (covered == nearEar) return

        if (covered) {
            nearEar = true
            nearEarSinceMs = SystemClock.elapsedRealtime()
            nearEarEpisodes++
        } else {
            if (nearEarSinceMs > 0) {
                totalNearEarMs += SystemClock.elapsedRealtime() - nearEarSinceMs
            }
            nearEar = false
            nearEarSinceMs = 0
        }
        publish()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
    override fun onActivityStarted(a: Activity) = Unit
    override fun onActivityStopped(a: Activity) = Unit
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
    override fun onActivityDestroyed(a: Activity) = Unit
}
