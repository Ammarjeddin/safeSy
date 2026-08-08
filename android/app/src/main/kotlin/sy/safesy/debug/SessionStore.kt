package sy.safesy.debug

import android.content.Context
import java.io.File

/**
 * Named drive sessions with human-marked ground truth.
 *
 * SPEC.md §8 makes labeled ride-along data a pilot pass/fail requirement:
 * "IMU false positives < N/100 km **validated by ride-along ground truth**".
 * Without a human saying "I braked hard HERE", a detected event cannot be
 * scored as a true or false positive — you can only count events, not judge
 * them.
 *
 * So the tester marks what actually happened, in the moment, and the marks land
 * in the trace alongside the detector's own output. Comparing the two columns
 * is the whole experiment.
 */
class SessionStore(private val context: Context) {

    data class Session(
        val id: Long,
        val name: String,
        val startedAtMs: Long,
        val durationSec: Long,
        val marks: Int,
        val events: Int,
        val placement: String,
    )

    private val dir: File get() = context.getExternalFilesDir(null) ?: context.filesDir

    fun sessionDir(id: Long): File = File(dir, "session-$id").apply { mkdirs() }

    fun createSession(name: String, startedAtMs: Long, placement: String = "UNKNOWN"): Long {
        val id = startedAtMs
        val d = sessionDir(id)
        File(d, "meta.txt").writeText(
            "name=$name\nstarted_ms=$startedAtMs\nplacement=$placement\n"
        )
        File(d, "marks.csv").writeText("elapsed_s,label,note\n")
        return id
    }

    /**
     * Records what the human observed. Written immediately, because a mark
     * that only exists in memory is lost when the app is killed — and MIUI
     * kills apps.
     */
    fun addMark(id: Long, elapsedSec: Long, label: String, note: String = "") {
        runCatching {
            File(sessionDir(id), "marks.csv")
                .appendText("$elapsedSec,$label,${note.replace(',', ';')}\n")
        }
    }

    fun finish(id: Long, durationSec: Long) {
        runCatching {
            File(sessionDir(id), "meta.txt").appendText("duration_s=$durationSec\n")
        }
    }

    fun list(): List<Session> = runCatching {
        dir.listFiles { f -> f.isDirectory && f.name.startsWith("session-") }
            ?.mapNotNull { d ->
                val meta = File(d, "meta.txt").takeIf { it.exists() }?.readLines() ?: return@mapNotNull null
                val kv = meta.mapNotNull { l ->
                    l.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
                }.toMap()
                val id = d.name.removePrefix("session-").toLongOrNull() ?: return@mapNotNull null
                val marks = File(d, "marks.csv").takeIf { it.exists() }
                    ?.readLines()?.drop(1)?.count { it.isNotBlank() } ?: 0
                val events = File(d, "events.csv").takeIf { it.exists() }
                    ?.readLines()?.drop(1)?.count { it.isNotBlank() } ?: 0
                Session(
                    id = id,
                    name = kv["name"].orEmpty().ifBlank { "unnamed" },
                    startedAtMs = kv["started_ms"]?.toLongOrNull() ?: id,
                    durationSec = kv["duration_s"]?.toLongOrNull() ?: 0,
                    marks = marks,
                    events = events,
                    placement = kv["placement"].orEmpty().ifBlank { "UNKNOWN" },
                )
            }
            ?.sortedByDescending { it.startedAtMs }
            .orEmpty()
    }.getOrDefault(emptyList())

    fun delete(id: Long) {
        runCatching { sessionDir(id).deleteRecursively() }
    }

    companion object {
        /**
         * Where the phone actually is during the trip.
         *
         * ⚠️ The detector was designed assuming a CRADLE — a fixed orientation
         * learned once at trip start. Syrian drivers are unlikely to use one.
         * A phone in a shirt pocket rotates with the driver's torso; a loose
         * phone on a dashboard slides on every corner. Both break the
         * fixed-mount assumption, and the difference has to be MEASURED per
         * placement rather than guessed.
         *
         * Every session records this so traces can be compared across them.
         */
        val PLACEMENTS = listOf(
            "DASHBOARD LOOSE",
            "CRADLE",
            "SHIRT POCKET",
            "TROUSER POCKET",
            "CUP HOLDER",
            "SEAT / BAG",
        )

        /** Marks a tester can apply in one tap while a vehicle is moving. */
        val LABELS = listOf(
            "HARD BRAKE",
            "HARD ACCEL",
            "SHARP TURN",
            "POTHOLE",
            "SPEED BUMP",
            "STOPPED",
            "PHONE MOVED",
            "NOTE",
        )
    }
}
