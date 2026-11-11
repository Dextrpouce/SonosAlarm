package ch.srs.sonosalarm.ui

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: UiState,
    onBack: () -> Unit,
    onForgetSpeaker: () -> Unit,
    onVolumeStep: (Int) -> Unit,
    onMaxVolume: (Int) -> Unit,
    onPhoneAlert: (Boolean) -> Unit,
    onPhoneFallback: (Boolean) -> Unit,
    onRingtone: (String?) -> Unit,
    onTestRing: () -> Unit,
    spotifyCallbacks: SpotifyCallbacks,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Réglages") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header("Enceinte")
            SettingsCard {
                ListItem(
                    headlineContent = { Text(state.speaker?.name ?: "—") },
                    supportingContent = {
                        Text(listOfNotNull(state.speaker?.model?.takeIf { it.isNotBlank() }, state.speaker?.ip).joinToString(" · "))
                    },
                    leadingContent = { Icon(Icons.Filled.Speaker, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = { TextButton(onClick = onForgetSpeaker) { Text("Changer") } },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }

            Header("Sur le téléphone")
            SettingsCard {
                Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    SwitchRow(
                        "Écran de réveil",
                        "Affiche l'alarme avec les boutons +15 / +30 min",
                        state.phoneAlert,
                        onPhoneAlert,
                    )
                }
            }

            Header("Si l'enceinte ne répond pas")
            SettingsCard {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    SwitchRow(
                        "Sonner sur le téléphone",
                        "Absent de la maison, enceinte éteinte ou pas de Wi-Fi",
                        state.phoneFallback && state.phoneAlert,
                    ) {
                        onPhoneFallback(it)
                        if (it && !state.phoneAlert) onPhoneAlert(true)
                    }
                    if (state.phoneFallback && state.phoneAlert) {
                        RingtoneRow(state.ringtoneUri, onRingtone)
                        Text(
                            when {
                                !state.spotify.appInstalled ->
                                    "Avec une playlist Spotify : installez l'app Spotify pour l'entendre sur le téléphone. Sinon, la sonnerie retentit."
                                state.spotify.user == null ->
                                    "Avec une playlist Spotify : connectez Spotify ci-dessous pour l'entendre sur le téléphone. Sinon, la sonnerie retentit."
                                !state.spotify.canPlay ->
                                    "Avec une playlist Spotify : reconnectez Spotify ci-dessous pour autoriser la lecture sur le téléphone."
                                else ->
                                    "Avec une playlist Spotify, elle est lancée dans l'app Spotify du téléphone ; la sonnerie sert de secours."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        )
                        FilledTonalButton(
                            onClick = onTestRing,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        ) { Text("Tester la sonnerie du téléphone") }
                    }
                }
            }

            Header("Rappel")
            SettingsCard {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (state.volumeStep == 0) "Volume inchangé à chaque rappel" else "Volume +${state.volumeStep} à chaque rappel",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Slider(
                        value = state.volumeStep.toFloat(),
                        onValueChange = { onVolumeStep(it.roundToInt()) },
                        valueRange = 0f..15f,
                        steps = 14,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Sans dépasser ${state.maxVolume}", style = MaterialTheme.typography.bodyLarge)
                    Slider(
                        value = state.maxVolume.toFloat(),
                        onValueChange = { onMaxVolume(it.roundToInt()) },
                        valueRange = 10f..100f,
                    )
                    Text(
                        "Le volume d'origine de l'alarme est rétabli quand vous l'arrêtez.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Header("Spotify")
            SettingsCard {
                Box(Modifier.padding(16.dp)) { SpotifySetup(state.spotify, spotifyCallbacks) }
            }

            Header("Autorisations")
            key(tick) {
                SettingsCard {
                    PermissionRow("Notifications", Permissions.notifications(context)) {
                        context.startActivity(Permissions.notificationSettings(context))
                    }
                    PermissionRow("Affichage plein écran", Permissions.fullScreen(context)) {
                        context.startActivity(Permissions.fullScreenSettings(context))
                    }
                    PermissionRow("Alarmes exactes", Permissions.exactAlarms(context)) {
                        context.startActivity(Permissions.exactAlarmSettings(context))
                    }
                }
            }
        }
    }
}

@Composable
private fun RingtoneRow(current: String?, onPick: (String?) -> Unit) {
    val context = LocalContext.current
    val title = remember(current) {
        val uri = current?.let(Uri::parse) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        runCatching { RingtoneManager.getRingtone(context, uri)?.getTitle(context) }.getOrNull()
            ?.let { if (current == null) "Par défaut ($it)" else it } ?: "Par défaut"
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val picked = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            val default = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            onPick(picked?.takeIf { it != default }?.toString())
        }
    }
    ListItem(
        headlineContent = { Text("Sonnerie") },
        supportingContent = { Text(title) },
        leadingContent = { Icon(Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.primary) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable {
            launcher.launch(
                Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Sonnerie du réveil")
                    .putExtra(
                        RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                        current?.let(Uri::parse) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    ),
            )
        },
    )
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
        content = content,
    )
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onFix: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = {
            Icon(
                if (granted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                null,
                tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        },
        trailingContent = {
            if (!granted) TextButton(onClick = { runCatching { onFix() } }) { Text("Autoriser") }
            else Text("OK", color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.padding(vertical = 0.dp),
    )
}
