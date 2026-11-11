package ch.srs.sonosalarm

import android.app.Application
import ch.srs.sonosalarm.alarm.AlarmNotifications
import ch.srs.sonosalarm.data.SyncWorker

class SonosAlarmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AlarmNotifications.createChannels(this)
        SyncWorker.schedule(this)
    }
}
