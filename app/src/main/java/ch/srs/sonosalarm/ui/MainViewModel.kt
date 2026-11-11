package ch.srs.sonosalarm.ui

import android.app.Application
import android.net.Uri
import ch.srs.sonosalarm.data.AlarmPause
import ch.srs.sonosalarm.data.PauseManager
import ch.srs.sonosalarm.data.PauseState
import java.time.LocalDate
import ch.srs.sonosalarm.spotify.Spotify
import ch.srs.sonosalarm.spotify.SpotifyPlaylist
import ch.srs.sonosalarm.spotify.SpotifyStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.srs.sonosalarm.alarm.AlarmActions
import ch.srs.sonosalarm.alarm.AlarmScheduler
import ch.srs.sonosalarm.data.AlarmRepository
import ch.srs.sonosalarm.sonos.Favorite
import ch.srs.sonosalarm.sonos.SonosAlarm
import ch.srs.sonosalarm.sonos.SonosClient
import ch.srs.sonosalarm.sonos.SonosDiscovery
import ch.srs.sonosalarm.sonos.Speaker
import ch.srs.sonosalarm.widget.AlarmWidget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val speaker: Speaker? = null,
    val alarms: List<SonosAlarm> = emptyList(),
    /** Holiday pauses by alarm ID. */
    val pauses: Map<String, AlarmPause> = emptyMap(),
    val lastSync: Long = 0,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val message: String? = null,
    val discovering: Boolean = false,
    val connecting: Boolean = false,
    val discovered: List<Speaker> = emptyList(),
    /** null = not loaded yet. */
    val favorites: List<Favorite>? = null,
    val volumeStep: Int = 5,
    val maxVolume: Int = 60,
    val phoneAlert: Boolean = true,
    val phoneFallback: Boolean = true,
    val ringtoneUri: String? = null,
    val spotify: SpotifyUi = SpotifyUi(),
)

data class SpotifyUi(
    val clientId: String = "",
    /** Account name when logged in. */
    val user: String? = null,
    /** null = not loaded yet. */
    val playlists: List<SpotifyPlaylist>? = null,
    val loading: Boolean = false,
    /** Allowed to start playback on the phone (needs a login made with this version). */
    val canPlay: Boolean = false,
    val appInstalled: Boolean = false,
)

/** Spotify actions passed down to the screens. */
class SpotifyCallbacks(
    val setClientId: (String) -> Unit,
    val login: () -> Unit,
    val loadPlaylists: (force: Boolean) -> Unit,
    val logout: () -> Unit,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = AlarmRepository(app)
    private val store = repo.store
    private val spotifyStore = SpotifyStore(app)

    private val _state = MutableStateFlow(fromStore(UiState()))
    val state = _state.asStateFlow()

    init {
        if (store.speaker != null) refresh()
    }

    private fun fromStore(s: UiState) = s.copy(
        speaker = store.speaker,
        alarms = store.alarms,
        pauses = store.pauses.associateBy { it.alarmId },
        lastSync = store.lastSync,
        volumeStep = store.volumeStep,
        maxVolume = store.maxVolume,
        phoneAlert = store.phoneAlert,
        phoneFallback = store.phoneFallback,
        ringtoneUri = store.ringtoneUri,
        spotify = s.spotify.copy(
            clientId = spotifyStore.clientId,
            user = if (spotifyStore.connected) spotifyStore.userName ?: "Spotify" else null,
            canPlay = Spotify.canPlay(getApplication()),
            appInstalled = Spotify.isInstalled(getApplication()),
        ),
    )

    private fun syncFromStore() = _state.update(::fromStore)

    private fun fail(e: Throwable) = _state.update { it.copy(message = e.message ?: "Erreur inattendue") }

    fun messageShown() = _state.update { it.copy(message = null) }

    fun refresh() = viewModelScope.launch {
        _state.update { it.copy(loading = true) }
        runCatching { repo.refresh() }
            .onSuccess { if (store.pauses.isNotEmpty()) PauseManager.apply(getApplication()) }
            .onFailure(::fail)
        _state.update { fromStore(it).copy(loading = false) }
    }

    /** No ring from [from] to [to]; the alarm is switched off and back on automatically. */
    fun setPause(alarm: SonosAlarm, from: LocalDate, to: LocalDate, onDone: () -> Unit = {}) = viewModelScope.launch {
        val id = alarm.id ?: return@launch
        onDone()
        val old = store.pauseFor(id)
        // Keep the "turned off by the app" state when only the dates change.
        val state = if (old?.state == PauseState.DISABLED_BY_APP) PauseState.DISABLED_BY_APP else PauseState.PENDING
        store.pauses = store.pauses.filterNot { it.alarmId == id } + AlarmPause(id, from, to, state)
        _state.update { fromStore(it).copy(saving = true) }
        val ok = PauseManager.apply(getApplication())
        if (!ok) PauseManager.enqueue(getApplication())
        _state.update {
            fromStore(it).copy(
                saving = false,
                message = if (ok) "Pause enregistrée" else "Pause enregistrée, appliquée dès que l'enceinte sera joignable",
            )
        }
    }

    fun cancelPause(alarm: SonosAlarm, onDone: () -> Unit = {}) = viewModelScope.launch {
        val pause = store.pauseFor(alarm.id) ?: return@launch
        onDone()
        store.pauses = store.pauses - pause
        PauseManager.scheduleNext(getApplication())
        _state.update { fromStore(it).copy(saving = true) }
        val current = store.alarms.find { it.id == alarm.id } ?: alarm
        if (pause.state == PauseState.DISABLED_BY_APP && !current.enabled) {
            runCatching { repo.setEnabled(current, true) }.onFailure(::fail)
        } else {
            repo.onAlarmsChanged()
        }
        _state.update { fromStore(it).copy(saving = false, message = "Pause annulée") }
    }

    fun save(alarm: SonosAlarm, onDone: () -> Unit) = viewModelScope.launch {
        _state.update { it.copy(saving = true) }
        runCatching { repo.save(alarm) }
            .onSuccess {
                onDone()
                _state.update { it.copy(message = "Alarme enregistrée") }
            }
            .onFailure(::fail)
        _state.update { fromStore(it).copy(saving = false) }
    }

    fun delete(alarm: SonosAlarm, onDone: () -> Unit) = viewModelScope.launch {
        _state.update { it.copy(saving = true) }
        runCatching { repo.delete(alarm) }
            .onSuccess {
                onDone()
                _state.update { it.copy(message = "Alarme supprimée") }
            }
            .onFailure(::fail)
        _state.update { fromStore(it).copy(saving = false) }
    }

    fun toggle(alarm: SonosAlarm, enabled: Boolean) {
        // A manual switch ends any holiday pause on that alarm.
        if (store.pauseFor(alarm.id) != null) {
            store.pauses = store.pauses.filterNot { it.alarmId == alarm.id }
            PauseManager.scheduleNext(getApplication())
        }
        // Optimistic: flip the switch now, roll back if the speaker refuses.
        _state.update { s -> s.copy(alarms = s.alarms.map { if (it.id == alarm.id) it.copy(enabled = enabled) else it }) }
        viewModelScope.launch {
            runCatching { repo.setEnabled(alarm, enabled) }.onFailure(::fail)
            syncFromStore()
        }
    }

    fun loadFavorites() {
        if (_state.value.favorites != null) return
        viewModelScope.launch {
            runCatching { repo.favorites() }
                .onSuccess { list -> _state.update { it.copy(favorites = list) } }
                .onFailure { e -> _state.update { it.copy(favorites = emptyList()) }; fail(e) }
        }
    }

    // ---- Speaker setup --------------------------------------------------------------

    fun discover() = viewModelScope.launch {
        _state.update { it.copy(discovering = true) }
        val found = runCatching { SonosDiscovery.discover(getApplication()) }.getOrDefault(emptyList())
        _state.update { it.copy(discovering = false, discovered = found) }
    }

    fun connect(ip: String) = viewModelScope.launch {
        val clean = ip.trim()
        if (clean.isEmpty()) return@launch
        _state.update { it.copy(connecting = true) }
        runCatching { SonosClient(clean).describe() }
            .onSuccess { speaker ->
                store.speaker = speaker
                _state.update { it.copy(favorites = null) }
                runCatching { repo.refresh() }.onFailure(::fail)
            }
            .onFailure(::fail)
        _state.update { fromStore(it).copy(connecting = false) }
    }

    fun forgetSpeaker() = viewModelScope.launch {
        store.speaker = null
        store.alarms = emptyList()
        repo.onAlarmsChanged()
        _state.update { fromStore(it).copy(favorites = null) }
    }

    // ---- Settings ----------------------------------------------------------------------

    fun setVolumeStep(v: Int) {
        store.volumeStep = v
        syncFromStore()
    }

    fun setMaxVolume(v: Int) {
        store.maxVolume = v
        syncFromStore()
    }

    fun setPhoneFallback(enabled: Boolean) {
        store.phoneFallback = enabled
        syncFromStore()
    }

    fun setRingtone(uri: String?) {
        store.ringtoneUri = uri
        syncFromStore()
    }

    fun testPhoneRing() = AlarmActions.testPhoneRing(getApplication())

    fun setPhoneAlert(enabled: Boolean) {
        store.phoneAlert = enabled
        AlarmScheduler.reschedule(getApplication())
        syncFromStore()
    }

    // ---- Spotify -----------------------------------------------------------------------

    val spotifyCallbacks = SpotifyCallbacks(
        setClientId = { id ->
            spotifyStore.clientId = id
            syncFromStore()
        },
        login = ::spotifyLogin,
        loadPlaylists = ::loadSpotifyPlaylists,
        logout = {
            Spotify.logout(getApplication())
            _state.update { fromStore(it).copy(spotify = it.spotify.copy(playlists = null)) }
        },
    )

    private fun updateSpotify(f: (SpotifyUi) -> SpotifyUi) = _state.update { it.copy(spotify = f(it.spotify)) }

    private fun spotifyLogin() {
        runCatching { getApplication<Application>().startActivity(Spotify.loginIntent(getApplication())) }
            .onFailure(::fail)
    }

    fun onSpotifyCallback(uri: Uri) = viewModelScope.launch {
        updateSpotify { it.copy(loading = true) }
        runCatching { Spotify.handleCallback(getApplication(), uri) }
            .onSuccess { name -> _state.update { it.copy(message = "Connecté à Spotify ($name)") } }
            .onFailure(::fail)
        _state.update { fromStore(it).copy(spotify = fromStore(it).spotify.copy(loading = false)) }
        if (spotifyStore.connected) loadSpotifyPlaylists(force = true)
    }

    private fun loadSpotifyPlaylists(force: Boolean) {
        val s = _state.value.spotify
        if (s.user == null || s.loading || (s.playlists != null && !force)) return
        viewModelScope.launch {
            updateSpotify { it.copy(loading = true) }
            runCatching { Spotify.playlists(getApplication()) }
                .onSuccess { list -> updateSpotify { it.copy(playlists = list) } }
                .onFailure(::fail)
            _state.update { fromStore(it).copy(spotify = fromStore(it).spotify.copy(loading = false)) }
        }
    }

    fun ensureScheduled() = viewModelScope.launch {
        AlarmScheduler.reschedule(getApplication())
        AlarmWidget.updateAll(getApplication())
    }
}
