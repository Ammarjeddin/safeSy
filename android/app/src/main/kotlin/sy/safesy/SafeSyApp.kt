package sy.safesy

import android.app.Application
import sy.safesy.debug.GnssMonitor
import sy.safesy.debug.PhoneUsageTracker

class SafeSyApp : Application() {

    /**
     * Owned at Application scope, not by a screen.
     *
     * A GNSS receiver that powers down when you navigate away has to cold-start
     * again — 30-90 s each time, and far longer without A-GPS. Keeping it alive
     * for the whole app session means the fix is ready the moment a drive starts.
     */
    val gnss: GnssMonitor by lazy { GnssMonitor(this) }
    val phoneUsage: PhoneUsageTracker by lazy { PhoneUsageTracker(this) }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) phoneUsage.start()
    }
}
