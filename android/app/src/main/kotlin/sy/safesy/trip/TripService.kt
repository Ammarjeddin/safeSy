package sy.safesy.trip

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service that owns an ACTIVE trip.
 * Step 3 implements sensors, Room persistence, and the outbox.
 */
class TripService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
