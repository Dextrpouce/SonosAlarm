package ch.srs.sonosalarm

import android.Manifest
import android.content.Intent
import ch.srs.sonosalarm.spotify.Spotify
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.srs.sonosalarm.sonos.SonosAlarm
import ch.srs.sonosalarm.ui.AlarmEditScreen
import ch.srs.sonosalarm.ui.AlarmListScreen
import ch.srs.sonosalarm.ui.MainViewModel
import ch.srs.sonosalarm.ui.Permissions
import ch.srs.sonosalarm.ui.SettingsScreen
import ch.srs.sonosalarm.ui.SetupScreen
import ch.srs.sonosalarm.ui.theme.SonosAlarmTheme

private sealed interface Screen {
    data object Home : Screen
    data class Edit(val alarm: SonosAlarm) : Screen
    data object Settings : Screen
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vm.ensureScheduled()
        handleSpotifyRedirect(intent)
        setContent {
            SonosAlarmTheme {
                // On tablets, keep a comfortable reading width instead of stretching edge to edge.
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    Box(contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.widthIn(max = 720.dp)) { AppRoot(vm) }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSpotifyRedirect(intent)
    }

    private fun handleSpotifyRedirect(intent: Intent?) {
        val uri = intent?.data
        if (Spotify.isCallback(uri)) {
            vm.onSpotifyCallback(uri!!)
            intent.data = null
        }
    }
}

@Composable
private fun AppRoot(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    LaunchedEffect(state.message) {
        val m = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(m)
        vm.messageShown()
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(state.speaker != null) {
        if (state.speaker != null && Build.VERSION.SDK_INT >= 33 && !Permissions.notifications(context)) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val speaker = state.speaker
    if (speaker == null) {
        SetupScreen(
            state = state,
            snackbar = snackbar,
            onDiscover = vm::discover,
            onConnect = vm::connect,
        )
        return
    }

    AnimatedContent(
        targetState = screen,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "screen",
    ) { s ->
        when (s) {
            Screen.Home -> AlarmListScreen(
                state = state,
                snackbar = snackbar,
                onRefresh = vm::refresh,
                onAdd = {
                    // Default 07:00 on weekdays, moved to the next free half hour if taken.
                    val base = SonosAlarm(roomUuid = speaker.uuid)
                    val draft = (0 until 48).asSequence()
                        .map { base.copy(startTime = base.startTime.plusMinutes(30L * it)) }
                        .firstOrNull { d -> state.alarms.none { d.conflictsWith(it) } } ?: base
                    screen = Screen.Edit(draft)
                },
                onEdit = { screen = Screen.Edit(it) },
                onToggle = vm::toggle,
                onSettings = { screen = Screen.Settings },
            )
            is Screen.Edit -> AlarmEditScreen(
                initial = s.alarm,
                others = state.alarms,
                favorites = state.favorites,
                spotify = state.spotify,
                spotifyCallbacks = vm.spotifyCallbacks,
                pause = state.pauses[s.alarm.id],
                onSetPause = { from, to -> vm.setPause(s.alarm, from, to) { screen = Screen.Home } },
                onCancelPause = { vm.cancelPause(s.alarm) { screen = Screen.Home } },
                saving = state.saving,
                snackbar = snackbar,
                onLoadFavorites = vm::loadFavorites,
                onBack = { screen = Screen.Home },
                onSave = { vm.save(it) { screen = Screen.Home } },
                onDelete = { vm.delete(it) { screen = Screen.Home } },
            )
            Screen.Settings -> SettingsScreen(
                state = state,
                onBack = { screen = Screen.Home },
                onForgetSpeaker = {
                    screen = Screen.Home
                    vm.forgetSpeaker()
                },
                onVolumeStep = vm::setVolumeStep,
                onMaxVolume = vm::setMaxVolume,
                onPhoneAlert = vm::setPhoneAlert,
                onPhoneFallback = vm::setPhoneFallback,
                onRingtone = vm::setRingtone,
                onTestRing = vm::testPhoneRing,
                spotifyCallbacks = vm.spotifyCallbacks,
            )
        }
    }
}
