package ch.srs.sonosalarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ch.srs.sonosalarm.data.PauseManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class AlarmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_FIRE = "ch.srs.sonosalarm.FIRE"
        const val ACTION_SNOOZE = "ch.srs.sonosalarm.SNOOZE"
        const val ACTION_SNOOZE_END = "ch.srs.sonosalarm.SNOOZE_END"
        const val ACTION_STOP = "ch.srs.sonosalarm.STOP"
        const val ACTION_PAUSE_TICK = "ch.srs.sonosalarm.PAUSE_TICK"
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_MINUTES = "minutes"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            ACTION_FIRE -> AlarmActions.onFire(app, intent.getStringExtra(EXTRA_ALARM_ID))
            ACTION_SNOOZE -> runAsync(app) { AlarmActions.snooze(app, intent.getIntExtra(EXTRA_MINUTES, 15)) }
            ACTION_SNOOZE_END -> runAsync(app) { AlarmActions.onSnoozeEnd(app) }
            ACTION_STOP -> runAsync(app) { AlarmActions.stop(app) }
            ACTION_PAUSE_TICK -> PauseManager.enqueue(app)
        }
    }

    private fun runAsync(context: Context, block: suspend () -> Unit) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(9_000) { block() }
            } catch (e: Exception) {
                AlarmNotifications.showError(context, e.message ?: "L'enceinte n'a pas répondu")
            } finally {
                pending.finish()
            }
        }
    }
}

/** Reschedules the phone alarm after reboot, time change or app update. */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.reschedule(context.applicationContext)
        PauseManager.scheduleNext(context.applicationContext)
    }
}
