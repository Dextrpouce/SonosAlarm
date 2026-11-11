package ch.srs.sonosalarm.data

import android.content.Context
import ch.srs.sonosalarm.alarm.AlarmScheduler
import ch.srs.sonosalarm.sonos.Favorite
import ch.srs.sonosalarm.sonos.SonosAlarm
import ch.srs.sonosalarm.sonos.SonosClient
import ch.srs.sonosalarm.sonos.SonosException
import ch.srs.sonosalarm.widget.AlarmWidget

/** The speaker is the source of truth; the local cache follows every change. */
class AlarmRepository(context: Context) {
    private val app = context.applicationContext
    val store = AlarmStore(app)

    private fun client(): SonosClient {
        val speaker = store.speaker ?: throw SonosException("Aucune enceinte configurée")
        return SonosClient(speaker.ip)
    }

    suspend fun refresh(): List<SonosAlarm> {
        val list = client().listAlarms().sortedWith(compareBy({ it.startTime }, { it.id }))
        store.alarms = list
        store.lastSync = System.currentTimeMillis()
        onAlarmsChanged()
        return list
    }

    suspend fun save(alarm: SonosAlarm) {
        val c = client()
        val withRoom = if (alarm.roomUuid.isBlank()) alarm.copy(roomUuid = store.speaker?.uuid.orEmpty()) else alarm
        if (withRoom.id == null) c.createAlarm(withRoom) else c.updateAlarm(withRoom)
        refresh()
    }

    suspend fun setEnabled(alarm: SonosAlarm, enabled: Boolean) = save(alarm.copy(enabled = enabled))

    suspend fun delete(alarm: SonosAlarm) {
        alarm.id?.let { client().destroyAlarm(it) }
        refresh()
    }

    suspend fun favorites(): List<Favorite> = client().favorites()

    suspend fun onAlarmsChanged() {
        AlarmScheduler.reschedule(app)
        AlarmWidget.updateAll(app)
    }
}
