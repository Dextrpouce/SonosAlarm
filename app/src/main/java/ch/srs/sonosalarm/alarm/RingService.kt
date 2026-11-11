package ch.srs.sonosalarm.alarm

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.IBinder
import android.os.VibrationEffect
import android.os.VibratorManager
import android.view.KeyEvent
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import ch.srs.sonosalarm.data.AlarmStore
import ch.srs.sonosalarm.data.phoneLevel
import ch.srs.sonosalarm.sonos.SonosClient
import ch.srs.sonosalarm.sonos.SonosException
import ch.srs.sonosalarm.spotify.Spotify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * Runs while an alarm rings. First checks that the speaker is really playing; if not, rings on
 * the phone: ringtone right away, then the alarm's Spotify playlist if it can be started.
 */
class RingService : Service() {
    companion object {
        private const val ACTION_CHECK = "check"
        private const val ACTION_RING_PHONE = "ring_phone"

        /** Speaker should be ringing: verify, fall back to the phone if not. */
        fun check(context: Context) = start(context, ACTION_CHECK)

        /** Ring on the phone directly (snooze over while on phone, or test). */
        fun ringPhone(context: Context) = start(context, ACTION_RING_PHONE)

        fun silence(context: Context) {
            context.stopService(Intent(context, RingService::class.java))
        }

        private fun start(context: Context, action: String) {
            ContextCompat.startForegroundService(context, Intent(context, RingService::class.java).setAction(action))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: MediaPlayer? = null
    private var spotifyPlaying = false
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private var savedAlarmVolume: Int? = null
    private var savedMusicVolume: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = AlarmStore(this)
        val session = store.session
        if (session == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, AlarmNotifications.RINGING_ID, AlarmNotifications.buildRinging(this, session),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        when (intent?.action) {
            ACTION_RING_PHONE -> ringOnPhone()
            else -> scope.launch {
                if (speakerIsRinging(store)) {
                    // All good: keep the notification, drop the service.
                    ServiceCompat.stopForeground(this@RingService, ServiceCompat.STOP_FOREGROUND_DETACH)
                    stopSelf()
                } else {
                    ringOnPhone()
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun speakerIsRinging(store: AlarmStore): Boolean {
        val speaker = store.speaker ?: return false
        if (!onWifi()) return false
        val client = SonosClient(speaker.ip)
        var unreachable = 0
        // The Sonos alarm can start a few seconds late: poll for ~10 s.
        repeat(6) {
            try {
                val state = client.transportState()
                if (state == "PLAYING" || state == "TRANSITIONING") return true
            } catch (e: SonosException) {
                if (e.unreachable && ++unreachable >= 2) return false
            }
            delay(2000)
        }
        return false
    }

    private fun onWifi(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun ringOnPhone() {
        val store = AlarmStore(this)
        val session = (store.session ?: return).copy(onPhone = true)
        store.session = session
        AlarmNotifications.showRinging(this, session)
        if (!store.phoneFallback) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            stopSelf()
            return
        }
        startRingtone(store.ringtoneUri, session.phoneLevel())
        startVibration()

        val uri = session.spotifyUri ?: return
        scope.launch {
            val ok = runCatching {
                Spotify.playOnThisDevice(this@RingService, uri, session.shuffle) { wakeSpotify() }
            }.getOrDefault(false)
            if (ok) {
                spotifyPlaying = true
                setStream(AudioManager.STREAM_MUSIC, session.phoneLevel()) { savedMusicVolume = it }
                stopRingtone()
            }
        }
    }

    private fun startRingtone(custom: String?, level: Float) {
        setStream(AudioManager.STREAM_ALARM, level) { savedAlarmVolume = it }
        val candidates = listOfNotNull(
            custom?.let(Uri::parse),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
        )
        for (uri in candidates) {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                mp.setDataSource(this, uri)
                mp.isLooping = true
                mp.prepare()
                mp.start()
                player = mp
                return
            } catch (e: Exception) {
                mp.release()
            }
        }
    }

    private fun stopRingtone() {
        player?.runCatching { stop(); release() }
        player = null
    }

    private fun startVibration() {
        val vm = getSystemService(VibratorManager::class.java) ?: return
        vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 800), 0))
    }

    /** Sets a stream to [level] of its max, remembering the previous value to restore it later. */
    private fun setStream(stream: Int, level: Float, save: (Int) -> Unit) {
        runCatching {
            save(audio.getStreamVolume(stream))
            val max = audio.getStreamMaxVolume(stream)
            audio.setStreamVolume(stream, ceil(max * level).toInt().coerceIn(1, max), 0)
        }
    }

    /** Starts the Spotify app so it shows up as a playback device. */
    private fun wakeSpotify() {
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            runCatching {
                sendBroadcast(
                    Intent(Intent.ACTION_MEDIA_BUTTON)
                        .setPackage(Spotify.SPOTIFY_PACKAGE)
                        .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY)),
                )
            }
        }
        runCatching {
            packageManager.getLaunchIntentForPackage(Spotify.SPOTIFY_PACKAGE)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?.let(::startActivity)
        }
    }

    override fun onDestroy() {
        stopRingtone()
        getSystemService(VibratorManager::class.java)?.defaultVibrator?.cancel()
        savedAlarmVolume?.let { v -> runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, v, 0) } }
        savedMusicVolume?.let { v -> runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) } }
        if (spotifyPlaying) {
            val app = applicationContext
            CoroutineScope(Dispatchers.IO).launch { Spotify.pause(app) }
        }
        scope.cancel()
        super.onDestroy()
    }
}
