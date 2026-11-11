package ch.srs.sonosalarm.alarm

import android.content.Context
import ch.srs.sonosalarm.data.AlarmStore
import ch.srs.sonosalarm.data.RingSession
import ch.srs.sonosalarm.hm
import ch.srs.sonosalarm.sonos.SonosClient
import ch.srs.sonosalarm.sonos.SonosException
import ch.srs.sonosalarm.spotify.Spotify
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.LocalDateTime
import java.time.LocalTime

/** What happens when an alarm rings, is snoozed or stopped. Shared by the notification and the ringing screen. */
object AlarmActions {
    private val _closeRingingScreen = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val closeRingingScreen = _closeRingingScreen.asSharedFlow()

    /** Called by AlarmManager at the alarm time. */
    fun onFire(context: Context, alarmId: String?) {
        val store = AlarmStore(context)
        val alarm = store.alarms.find { it.id == alarmId }
        val session = RingSession(
            alarmId = alarmId,
            label = (alarm?.startTime ?: LocalTime.now()).hm(),
            durationMinutes = alarm?.durationMinutes ?: 60,
            baseVolume = alarm?.volume ?: 20,
            targetVolume = alarm?.volume ?: 20,
            spotifyUri = alarm?.let { Spotify.uriFromSonos(it.programUri) },
            shuffle = alarm?.shuffle ?: true,
        )
        store.session = session
        // Sonos turns off a one-time alarm once it has rung: mirror it until the next sync.
        if (alarm != null && alarm.isOnce) {
            store.alarms = store.alarms.map { if (it.id == alarmId) it.copy(enabled = false) else it }
        }
        AlarmNotifications.showRinging(context, session)
        if (store.phoneFallback) RingService.check(context)
        AlarmScheduler.reschedule(context, from = LocalDateTime.now().plusSeconds(30))
    }

    /** Lets you hear the phone fallback without waiting for an alarm. */
    fun testPhoneRing(context: Context) {
        val store = AlarmStore(context)
        val alarm = store.alarms.firstOrNull { it.enabled } ?: store.alarms.firstOrNull()
        store.session = RingSession(
            alarmId = null,
            label = "Test",
            durationMinutes = 5,
            baseVolume = alarm?.volume ?: 25,
            targetVolume = alarm?.volume ?: 25,
            onPhone = true,
            spotifyUri = alarm?.let { Spotify.uriFromSonos(it.programUri) },
            shuffle = alarm?.shuffle ?: true,
        )
        RingService.ringPhone(context)
    }

    private fun nextVolume(store: AlarmStore, current: Int): Int = when {
        store.volumeStep <= 0 || current >= store.maxVolume -> current
        else -> (current + store.volumeStep).coerceAtMost(store.maxVolume)
    }

    /** Snoozes (speaker or phone) and raises the volume for the next ring. */
    suspend fun snooze(context: Context, minutes: Int) {
        val store = AlarmStore(context)
        val session = store.session ?: RingSession(null, LocalTime.now().hm(), 60, 20, 20)
        val target: Int
        if (session.onPhone) {
            RingService.silence(context)
            target = nextVolume(store, session.targetVolume)
        } else {
            val speaker = store.speaker ?: throw SonosException("Aucune enceinte configurée")
            val client = SonosClient(speaker.ip)
            client.snooze(minutes)
            val current = runCatching { client.getVolume() }.getOrDefault(session.targetVolume)
            target = nextVolume(store, current)
            if (target != current) runCatching { client.setVolume(target) }
        }

        val until = System.currentTimeMillis() + minutes * 60_000L
        val updated = session.copy(targetVolume = target, snoozeCount = session.snoozeCount + 1, snoozeUntil = until)
        store.session = updated
        AlarmScheduler.scheduleSnoozeEnd(context, until)
        AlarmNotifications.showSnoozed(context, updated)
        _closeRingingScreen.tryEmit(Unit)
    }

    /** Snooze is over: ring again on the phone, or let the speaker resume and enforce the new volume. */
    suspend fun onSnoozeEnd(context: Context) {
        val store = AlarmStore(context)
        val session = store.session?.copy(snoozeUntil = null) ?: return
        store.session = session
        if (session.onPhone) {
            RingService.ringPhone(context)
            return
        }
        AlarmNotifications.showRinging(context, session)
        if (store.phoneFallback) RingService.check(context)
        val speaker = store.speaker ?: return
        delay(2500)
        runCatching { SonosClient(speaker.ip).setVolume(session.targetVolume) }
    }

    /** Stops the alarm (or cancels a pending snooze) and restores the alarm's volume. */
    suspend fun stop(context: Context) {
        val store = AlarmStore(context)
        val session = store.session
        AlarmScheduler.cancelSnoozeEnd(context)
        RingService.silence(context)
        AlarmNotifications.cancelAll(context)
        store.session = null
        _closeRingingScreen.tryEmit(Unit)
        if (session?.onPhone == true) return

        val speaker = store.speaker ?: return
        val client = SonosClient(speaker.ip)
        try {
            client.stop()
        } catch (e: SonosException) {
            // 701 = nothing playing; unreachable = nothing to stop there.
            if (e.upnpCode != 701 && !e.unreachable) throw e
        }
        if (session != null && session.targetVolume != session.baseVolume) {
            runCatching { client.setVolume(session.baseVolume) }
        }
    }
}
