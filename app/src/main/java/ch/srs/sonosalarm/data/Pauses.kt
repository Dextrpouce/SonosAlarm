package ch.srs.sonosalarm.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ch.srs.sonosalarm.alarm.AlarmReceiver
import ch.srs.sonosalarm.sonos.SonosAlarm
import ch.srs.sonosalarm.sonos.SonosClient
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class PauseState {
    /** Not started yet. */
    PENDING,

    /** The app turned the alarm off and will turn it back on. */
    DISABLED_BY_APP,

    /** The alarm was already off: leave it off at the end. */
    WAS_OFF,
}

/** No ring from [from] to [to] (both inclusive), e.g. holidays. */
data class AlarmPause(
    val alarmId: String,
    val from: LocalDate,
    val to: LocalDate,
    val state: PauseState = PauseState.PENDING,
) {
    fun covers(date: LocalDate) = !date.isBefore(from) && !date.isAfter(to)

    /** Just after the last ring before the holidays. */
    fun disableAt(alarm: SonosAlarm): LocalDateTime = from.minusDays(1).atTime(alarm.startTime).plusMinutes(1)

    /** Just after the time it would have rung on the last day off. */
    fun enableAt(alarm: SonosAlarm): LocalDateTime = to.atTime(alarm.startTime).plusMinutes(1)

    val label: String get() = "Pas d'alarme du ${shortDate(from)} au ${shortDate(to)}"
    val untilLabel: String get() = "En pause jusqu'au ${shortDate(to)}"
}

private val SHORT = DateTimeFormatter.ofPattern("d MMM", Locale.FRENCH)
fun shortDate(d: LocalDate): String = d.format(SHORT)

/** Next ring, skipping the paused days. */
fun SonosAlarm.nextTrigger(from: LocalDateTime, pause: AlarmPause?): LocalDateTime? {
    var cursor = from
    repeat(400) {
        val t = nextTrigger(cursor) ?: return null
        if (pause == null || !pause.covers(t.toLocalDate())) return t
        cursor = t
    }
    return null
}

/** Turns paused alarms off and back on at the right time, retrying until the speaker is reachable. */
object PauseManager {
    private const val RC_TICK = 102
    private const val WORK = "pause-apply"

    /** Applies the transitions that are due. Returns false if the speaker could not be reached. */
    suspend fun apply(context: Context): Boolean {
        val repo = AlarmRepository(context)
        val store = repo.store
        val speaker = store.speaker ?: return true
        val client = SonosClient(speaker.ip)
        val now = LocalDateTime.now()
        var ok = true
        var changed = false
        val kept = mutableListOf<AlarmPause>()

        for (p in store.pauses) {
            val alarm = store.alarms.find { it.id == p.alarmId }
            if (alarm == null) {
                changed = true // alarm deleted: drop its pause
                continue
            }
            try {
                when {
                    !now.isBefore(p.enableAt(alarm)) -> {
                        if (p.state == PauseState.DISABLED_BY_APP && !alarm.enabled) {
                            client.updateAlarm(alarm.copy(enabled = true))
                        }
                        changed = true // pause over: drop it
                    }
                    !now.isBefore(p.disableAt(alarm)) && p.state == PauseState.PENDING -> {
                        if (alarm.enabled) {
                            client.updateAlarm(alarm.copy(enabled = false))
                            kept += p.copy(state = PauseState.DISABLED_BY_APP)
                        } else {
                            kept += p.copy(state = PauseState.WAS_OFF)
                        }
                        changed = true
                    }
                    else -> kept += p
                }
            } catch (e: Exception) {
                ok = false
                kept += p
            }
        }
        store.pauses = kept
        if (changed) {
            runCatching { repo.refresh() }.onFailure { repo.onAlarmsChanged() }
        }
        scheduleNext(context)
        return ok
    }

    /** Wakes the app at the next start or end of a pause. */
    fun scheduleNext(context: Context) {
        val store = AlarmStore(context)
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = tickIntent(context)
        am.cancel(pi)
        val next = store.pauses.mapNotNull { p ->
            val alarm = store.alarms.find { it.id == p.alarmId } ?: return@mapNotNull null
            if (p.state == PauseState.PENDING) p.disableAt(alarm) else p.enableAt(alarm)
        }.minOrNull() ?: return
        if (!next.isAfter(LocalDateTime.now())) {
            enqueue(context)
            return
        }
        val millis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        }
    }

    /** Runs [apply] as soon as Wi-Fi is available, then every 15 min until it succeeds. */
    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<PauseWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
    }

    private fun tickIntent(context: Context) = PendingIntent.getBroadcast(
        context, RC_TICK,
        Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_PAUSE_TICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

class PauseWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (PauseManager.apply(applicationContext)) Result.success() else Result.retry()
}
