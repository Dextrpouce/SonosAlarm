package ch.srs.sonosalarm.data

import android.content.Context
import androidx.core.content.edit
import ch.srs.sonosalarm.sonos.Recurrence
import ch.srs.sonosalarm.sonos.SonosAlarm
import ch.srs.sonosalarm.sonos.Speaker
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

/** State of an alarm currently ringing or snoozed. */
data class RingSession(
    val alarmId: String?,
    val label: String,
    val durationMinutes: Int,
    /** Alarm's own volume, restored when the alarm is stopped. */
    val baseVolume: Int,
    /** Volume applied after the last snooze. */
    val targetVolume: Int,
    val snoozeCount: Int = 0,
    val snoozeUntil: Long? = null,
    /** The speaker did not answer: the phone rings instead. */
    val onPhone: Boolean = false,
    /** Spotify URI (spotify:playlist:…) to try on the phone, if the alarm uses Spotify. */
    val spotifyUri: String? = null,
    val shuffle: Boolean = true,
)

/** Phone volume (0..1 of the stream max) matching a Sonos-like 0..100 volume. */
fun RingSession.phoneLevel(): Float = (targetVolume.coerceIn(5, 100)) / 100f

/** Local cache: chosen speaker, last alarm list (for scheduling and widget), settings. */
class AlarmStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sonos_alarm", Context.MODE_PRIVATE)

    var speaker: Speaker?
        get() {
            val ip = prefs.getString("ip", null) ?: return null
            return Speaker(
                ip = ip,
                name = prefs.getString("name", null) ?: "Sonos",
                model = prefs.getString("model", null).orEmpty(),
                uuid = prefs.getString("uuid", null).orEmpty(),
            )
        }
        set(v) = prefs.edit {
            if (v == null) {
                remove("ip"); remove("name"); remove("model"); remove("uuid")
            } else {
                putString("ip", v.ip); putString("name", v.name); putString("model", v.model); putString("uuid", v.uuid)
            }
        }

    var alarms: List<SonosAlarm>
        get() = runCatching {
            val arr = JSONArray(prefs.getString("alarms", "[]"))
            (0 until arr.length()).map { arr.getJSONObject(it).toAlarm() }
        }.getOrDefault(emptyList())
        set(v) = prefs.edit { putString("alarms", JSONArray(v.map { it.toJson() }).toString()) }

    var pauses: List<AlarmPause>
        get() = runCatching {
            val arr = JSONArray(prefs.getString("pauses", "[]"))
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                AlarmPause(
                    alarmId = o.getString("id"),
                    from = LocalDate.parse(o.getString("from")),
                    to = LocalDate.parse(o.getString("to")),
                    state = runCatching { PauseState.valueOf(o.getString("state")) }.getOrDefault(PauseState.PENDING),
                )
            }
        }.getOrDefault(emptyList())
        set(v) = prefs.edit {
            putString(
                "pauses",
                JSONArray(
                    v.map {
                        JSONObject().put("id", it.alarmId).put("from", it.from.toString())
                            .put("to", it.to.toString()).put("state", it.state.name)
                    },
                ).toString(),
            )
        }

    fun pauseFor(alarmId: String?): AlarmPause? = pauses.find { it.alarmId == alarmId }

    var lastSync: Long
        get() = prefs.getLong("last_sync", 0)
        set(v) = prefs.edit { putLong("last_sync", v) }

    /** Volume added at each snooze (0 = unchanged). */
    var volumeStep: Int
        get() = prefs.getInt("volume_step", 5)
        set(v) = prefs.edit { putInt("volume_step", v) }

    /** Snoozes never raise the volume above this. */
    var maxVolume: Int
        get() = prefs.getInt("max_volume", 60)
        set(v) = prefs.edit { putInt("max_volume", v) }

    /** Show the ringing screen on the phone. */
    var phoneAlert: Boolean
        get() = prefs.getBoolean("phone_alert", true)
        set(v) = prefs.edit { putBoolean("phone_alert", v) }

    /** Ring on the phone when the speaker does not answer. */
    var phoneFallback: Boolean
        get() = prefs.getBoolean("phone_fallback", true)
        set(v) = prefs.edit { putBoolean("phone_fallback", v) }

    /** Phone ringtone; null = default alarm sound. */
    var ringtoneUri: String?
        get() = prefs.getString("ringtone", null)
        set(v) = prefs.edit { putString("ringtone", v) }

    var session: RingSession?
        get() = runCatching {
            val s = prefs.getString("session", null) ?: return null
            val o = JSONObject(s)
            RingSession(
                alarmId = o.optString("alarmId").ifBlank { null },
                label = o.getString("label"),
                durationMinutes = o.optInt("duration", 60),
                baseVolume = o.getInt("base"),
                targetVolume = o.getInt("target"),
                snoozeCount = o.optInt("count"),
                snoozeUntil = if (o.has("until")) o.getLong("until") else null,
                onPhone = o.optBoolean("phone"),
                spotifyUri = o.optString("spotify").ifBlank { null },
                shuffle = o.optBoolean("shuffle", true),
            )
        }.getOrNull()
        set(v) = prefs.edit {
            if (v == null) remove("session")
            else putString(
                "session",
                JSONObject()
                    .put("alarmId", v.alarmId ?: "")
                    .put("label", v.label)
                    .put("duration", v.durationMinutes)
                    .put("base", v.baseVolume)
                    .put("target", v.targetVolume)
                    .put("count", v.snoozeCount)
                    .put("phone", v.onPhone)
                    .put("spotify", v.spotifyUri ?: "")
                    .put("shuffle", v.shuffle)
                    .apply { v.snoozeUntil?.let { put("until", it) } }
                    .toString(),
            )
        }

    private fun SonosAlarm.toJson() = JSONObject()
        .put("id", id ?: "")
        .put("time", startTime.toString())
        .put("duration", durationMinutes)
        .put("recurrence", Recurrence.format(days))
        .put("enabled", enabled)
        .put("room", roomUuid)
        .put("uri", programUri)
        .put("meta", programMetaData)
        .put("mode", playMode)
        .put("volume", volume)
        .put("linked", includeLinkedZones)

    private fun JSONObject.toAlarm() = SonosAlarm(
        id = optString("id").ifBlank { null },
        startTime = LocalTime.parse(getString("time")),
        durationMinutes = optInt("duration", 60),
        days = Recurrence.parse(optString("recurrence")),
        enabled = optBoolean("enabled"),
        roomUuid = optString("room"),
        programUri = optString("uri"),
        programMetaData = optString("meta"),
        playMode = optString("mode", "NORMAL"),
        volume = optInt("volume", 20),
        includeLinkedZones = optBoolean("linked"),
    )
}
