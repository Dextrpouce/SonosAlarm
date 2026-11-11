package ch.srs.sonosalarm.alarm

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ch.srs.sonosalarm.MainActivity
import ch.srs.sonosalarm.R
import ch.srs.sonosalarm.data.AlarmStore
import ch.srs.sonosalarm.data.RingSession
import ch.srs.sonosalarm.hm
import java.time.Instant
import java.time.ZoneId

object AlarmNotifications {
    private const val CH_RING = "ringing"
    private const val CH_STATUS = "status"
    private const val ID_RING = 1
    private const val ID_STATUS = 2

    fun createChannels(context: Context) {
        val ring = NotificationChannel(CH_RING, "Alarme en cours", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Écran de réveil quand l'enceinte sonne"
            setSound(null, null) // the speaker makes the noise
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 300, 400)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val status = NotificationChannel(CH_STATUS, "Rappels et erreurs", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Rappel en attente, problème de connexion à l'enceinte"
            setSound(null, null)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(ring, status))
    }

    const val RINGING_ID = ID_RING

    fun showRinging(context: Context, session: RingSession) {
        cancel(context, ID_STATUS)
        notify(context, ID_RING, buildRinging(context, session))
    }

    fun buildRinging(context: Context, session: RingSession): Notification {
        val speaker = if (session.onPhone) "Le téléphone" else AlarmStore(context).speaker?.name ?: "Sonos"
        val full = PendingIntent.getActivity(
            context, 10,
            Intent(context, RingingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (session.snoozeCount > 0) "Rappel n°${session.snoozeCount} · ${session.label}" else "Réveil · ${session.label}"
        return NotificationCompat.Builder(context, CH_RING)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(title)
            .setContentText("$speaker sonne")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setTimeoutAfter(session.durationMinutes * 60_000L)
            .addAction(0, "+15 min", actionIntent(context, AlarmReceiver.ACTION_SNOOZE, 15))
            .addAction(0, "+30 min", actionIntent(context, AlarmReceiver.ACTION_SNOOZE, 30))
            .addAction(0, "Arrêter", actionIntent(context, AlarmReceiver.ACTION_STOP, 0))
            .build()
    }

    fun showSnoozed(context: Context, session: RingSession) {
        val until = session.snoozeUntil ?: return
        val time = Instant.ofEpochMilli(until).atZone(ZoneId.systemDefault()).toLocalTime().hm()
        val n = NotificationCompat.Builder(context, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("Rappel à $time")
            .setContentText("Volume ${session.targetVolume} au prochain réveil")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setSilent(true)
            .setWhen(until)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setContentIntent(openApp(context))
            .addAction(0, "Annuler le rappel", actionIntent(context, AlarmReceiver.ACTION_STOP, 0))
            .build()
        cancel(context, ID_RING)
        notify(context, ID_STATUS, n)
    }

    fun showError(context: Context, message: String) {
        val n = NotificationCompat.Builder(context, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("Problème avec l'enceinte")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        notify(context, ID_STATUS, n)
    }

    fun cancelAll(context: Context) {
        cancel(context, ID_RING)
        cancel(context, ID_STATUS)
    }

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 11, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun actionIntent(context: Context, action: String, minutes: Int) = PendingIntent.getBroadcast(
        context, action.hashCode() + minutes,
        Intent(context, AlarmReceiver::class.java).setAction(action).putExtra(AlarmReceiver.EXTRA_MINUTES, minutes),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    @SuppressLint("MissingPermission")
    private fun notify(context: Context, id: Int, n: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
        }
    }

    private fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)
}
