package sy.safesy.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts nothing sensor-related on boot — only schedules an outbox drain,
 * so a phone that reboots at the depot delivers yesterday's tail.
 *
 * NOTE: after a MIUI force-swipe (effectively force-stop) this receiver is
 * suspended until the user manually launches the app. Recovery there is a
 * notification prompting one tap — it is not automatic.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Step 3: enqueue OutboxWorker
    }
}
