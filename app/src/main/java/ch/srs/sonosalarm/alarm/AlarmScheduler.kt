package ch.srs.sonosalarm.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import ch.srs.sonosalarm.MainActivity
import ch.srs.sonosalarm.data.AlarmStore
import ch.srs.sonosalarm.data.nextTrigger
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The speaker rings on its own; the phone keeps a copy of the schedule so it can show
 * the ringing screen at the same time. Only the soonest alarm is scheduled.
 */
object AlarmScheduler {
    private const val RC_FIRE = 100
    private const val RC_SNOOZE_END = 101

    fun reschedule(context: Context, from: LocalDateTime = LocalDateTime.now()) {
        val am = context.getSystemService(AlarmManager::class.java)
        val store = AlarmStore(context)
        am.cancel(fireIntent(context, null))
        if (!store.phoneAlert || store.speaker == null) return

        val next = store.alarms
            .mapNotNull { a -> a.nextTrigger(from, store.pauseFor(a.id))?.let { a to it } }
            .minByOrNull { it.second } ?: return
        val millis = next.second.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        setExact(context, am, millis, fireIntent(context, next.first.id))
    }

    fun scheduleSnoozeEnd(context: Context, atMillis: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        setExact(context, am, atMillis, snoozeEndIntent(context))
    }

    fun cancelSnoozeEnd(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(snoozeEndIntent(context))
    }

    private fun setExact(context: Context, am: AlarmManager, millis: Long, pi: PendingIntent) {
        val show = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(millis, show), pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        }
    }

    private fun fireIntent(context: Context, alarmId: String?): PendingIntent = PendingIntent.getBroadcast(
        context, RC_FIRE,
        Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_FIRE)
            .putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun snoozeEndIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, RC_SNOOZE_END,
        Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_SNOOZE_END),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
