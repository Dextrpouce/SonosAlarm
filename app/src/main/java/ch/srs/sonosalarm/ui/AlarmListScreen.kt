package ch.srs.sonosalarm.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.BeachAccess
import ch.srs.sonosalarm.data.AlarmPause
import ch.srs.sonosalarm.data.PauseState
import ch.srs.sonosalarm.data.nextTrigger
import ch.srs.sonosalarm.data.shortDate
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import ch.srs.sonosalarm.dayLabel
import ch.srs.sonosalarm.hm
import ch.srs.sonosalarm.sonos.Recurrence
import ch.srs.sonosalarm.sonos.SonosAlarm
import ch.srs.sonosalarm.untilLabel
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmListScreen(
    state: UiState,
    snackbar: SnackbarHostState,
    onRefresh: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (SonosAlarm) -> Unit,
    onToggle: (SonosAlarm, Boolean) -> Unit,
    onSettings: () -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            delay(20_000)
            value = LocalDateTime.now()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Alarmes") },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Réglages") }
                },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Nouvelle alarme") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = onRefresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 112.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.phoneAlert) item { PermissionBanner() }
                item { NextAlarmCard(state, now) }
                if (state.alarms.isEmpty() && !state.loading) {
                    item { EmptyState() }
                }
                items(state.alarms, key = { it.id ?: it.hashCode().toString() }) { alarm ->
                    AlarmCard(alarm, state.pauses[alarm.id], onClick = { onEdit(alarm) }, onToggle = { onToggle(alarm, it) })
                }
                if (state.lastSync > 0) {
                    item {
                        val t = java.time.Instant.ofEpochMilli(state.lastSync)
                            .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
                        Text(
                            "Synchronisé avec ${state.speaker?.name ?: "l'enceinte"} · ${dayLabel(t, now).lowercase()} à ${
                                t.format(DateTimeFormatter.ofPattern("HH:mm"))
                            }",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NextAlarmCard(state: UiState, now: LocalDateTime) {
    val next = state.alarms
        .mapNotNull { a -> a.nextTrigger(now, state.pauses[a.id])?.let { a to it } }
        .minByOrNull { it.second }
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(listOf(scheme.primary, scheme.tertiary)),
                RoundedCornerShape(28.dp),
            )
            .padding(24.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Speaker, null, tint = scheme.onPrimary.copy(alpha = 0.85f), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    listOfNotNull(state.speaker?.name, state.speaker?.model?.takeIf { it.isNotBlank() }).joinToString(" · "),
                    color = scheme.onPrimary.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Spacer(Modifier.height(12.dp))
            if (next == null) {
                Text("Aucune alarme active", color = scheme.onPrimary, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Activez une alarme ou créez-en une nouvelle.",
                    color = scheme.onPrimary.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                val (alarm, at) = next
                Text("Prochain réveil", color = scheme.onPrimary.copy(alpha = 0.85f), style = MaterialTheme.typography.titleSmall)
                Text(
                    alarm.startTime.hm(),
                    color = scheme.onPrimary,
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Light,
                    lineHeight = 68.sp,
                )
                Text(
                    "${dayLabel(at, now)} · ${untilLabel(at, now)}",
                    color = scheme.onPrimary,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun AlarmCard(alarm: SonosAlarm, pause: AlarmPause?, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val paused = pause != null && pause.state != PauseState.PENDING
    val dim = if (alarm.enabled || paused) 1f else 0.45f
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    alarm.startTime.hm(),
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Light,
                    lineHeight = 48.sp,
                    color = scheme.onSurface.copy(alpha = dim),
                )
                Spacer(Modifier.height(4.dp))
                DaysIndicator(alarm.days, alarm.enabled)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.MusicNote, null, Modifier.size(16.dp), tint = scheme.onSurfaceVariant.copy(alpha = dim))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        alarm.programTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant.copy(alpha = dim),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(12.dp))
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, null, Modifier.size(16.dp), tint = scheme.onSurfaceVariant.copy(alpha = dim))
                    Spacer(Modifier.width(4.dp))
                    Text("${alarm.volume}", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant.copy(alpha = dim))
                }
                if (pause != null) {
                    Spacer(Modifier.height(8.dp))
                    Surface(shape = RoundedCornerShape(50), color = scheme.tertiaryContainer) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.BeachAccess, null, Modifier.size(14.dp), tint = scheme.onTertiaryContainer)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (paused) pause.untilLabel else "Vacances du ${shortDate(pause.from)} au ${shortDate(pause.to)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = scheme.onTertiaryContainer,
                            )
                        }
                    }
                }
            }
            Switch(checked = alarm.enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun DaysIndicator(days: Set<DayOfWeek>, enabled: Boolean) {
    val scheme = MaterialTheme.colorScheme
    if (days.isEmpty()) {
        Text("Une fois", style = MaterialTheme.typography.labelLarge, color = if (enabled) scheme.primary else scheme.outline)
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        DayOfWeek.entries.forEach { d ->
            val on = d in days
            Text(
                Recurrence.letter(d),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    !on -> scheme.outlineVariant
                    enabled -> scheme.primary
                    else -> scheme.outline
                },
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            Recurrence.label(days).takeIf { days.size == 7 || days == Recurrence.WEEKDAYS || days == Recurrence.WEEKEND } ?: "",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.AlarmOff, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))
        Text("Pas encore d'alarme sur l'enceinte", style = MaterialTheme.typography.titleMedium)
        Text(
            "Touchez « Nouvelle alarme » pour en créer une.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Shown when the phone could not display the ringing screen. */
@Composable
private fun PermissionBanner() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val missing = remember(tick) { missingPermission(context) } ?: return
    val (text, intent) = missing
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.NotificationsActive, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(12.dp))
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            TextButton(onClick = { runCatching { context.startActivity(intent) } }) { Text("Autoriser") }
        }
    }
}

private fun missingPermission(context: Context): Pair<String, Intent>? = when {
    !Permissions.notifications(context) ->
        "Autorisez les notifications pour voir le réveil sur le téléphone." to Permissions.notificationSettings(context)
    !Permissions.fullScreen(context) ->
        "Autorisez l'affichage plein écran pour que le réveil apparaisse sur l'écran verrouillé." to Permissions.fullScreenSettings(context)
    !Permissions.exactAlarms(context) ->
        "Autorisez les alarmes exactes pour que le réveil s'affiche à l'heure." to Permissions.exactAlarmSettings(context)
    else -> null
}
