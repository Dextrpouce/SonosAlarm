package ch.srs.sonosalarm.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BeachAccess
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ChevronRight
import ch.srs.sonosalarm.data.AlarmPause
import ch.srs.sonosalarm.data.nextTrigger
import ch.srs.sonosalarm.data.shortDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.srs.sonosalarm.dayLabel
import ch.srs.sonosalarm.hm
import ch.srs.sonosalarm.sonos.BUZZER_URI
import ch.srs.sonosalarm.sonos.Favorite
import ch.srs.sonosalarm.sonos.Recurrence
import ch.srs.sonosalarm.sonos.SonosAlarm
import ch.srs.sonosalarm.sonos.SpotifyLink
import ch.srs.sonosalarm.untilLabel
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.roundToInt

private val DURATIONS = listOf(15 to "15 min", 30 to "30 min", 60 to "1 h", 120 to "2 h")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditScreen(
    initial: SonosAlarm,
    others: List<SonosAlarm>,
    favorites: List<Favorite>?,
    spotify: SpotifyUi,
    spotifyCallbacks: SpotifyCallbacks,
    pause: AlarmPause?,
    saving: Boolean,
    snackbar: SnackbarHostState,
    onLoadFavorites: () -> Unit,
    onSetPause: (LocalDate, LocalDate) -> Unit,
    onCancelPause: () -> Unit,
    onBack: () -> Unit,
    onSave: (SonosAlarm) -> Unit,
    onDelete: (SonosAlarm) -> Unit,
) {
    var alarm by remember(initial) { mutableStateOf(initial) }
    var showTime by remember { mutableStateOf(false) }
    var showSource by remember { mutableStateOf(false) }
    var showSpotify by remember { mutableStateOf(false) }
    var showPause by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val conflict = others.firstOrNull { alarm.conflictsWith(it) }
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { onLoadFavorites() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial.id == null) "Nouvelle alarme" else "Modifier l'alarme") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
                },
                actions = {
                    if (saving) {
                        CircularProgressIndicator(Modifier.padding(end = 16.dp).size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Button(
                            onClick = { onSave(alarm.copy(enabled = true).takeIf { initial.id == null } ?: alarm) },
                            enabled = conflict == null,
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Text("Enregistrer")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // ---- Time ----
            val next = alarm.copy(enabled = true).nextTrigger(LocalDateTime.now(), pause)
            Surface(
                onClick = { showTime = true },
                shape = RoundedCornerShape(28.dp),
                color = scheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        alarm.startTime.hm(),
                        fontSize = 80.sp,
                        fontWeight = FontWeight.Light,
                        color = scheme.onPrimaryContainer,
                    )
                    if (next != null) {
                        Text(
                            "Sonnera ${dayLabel(next).lowercase()} · ${untilLabel(next)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onPrimaryContainer.copy(alpha = 0.8f),
                        )
                    }
                }
            }

            if (conflict != null) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = scheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, null, tint = scheme.onErrorContainer)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Votre alarme de ${conflict.startTime.hm()} (${conflict.recurrenceLabel.lowercase()}) sonne déjà à cette " +
                                "heure-là. Sonos n'accepte pas deux alarmes à la même minute le même jour : décalez d'une minute " +
                                "ou changez les jours.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onErrorContainer,
                        )
                    }
                }
            }

            // ---- Repeat ----
            Section("Répétition") {
                DayPicker(alarm.days) { alarm = alarm.copy(days = it) }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "En semaine" to Recurrence.WEEKDAYS,
                        "Tous les jours" to Recurrence.DAILY,
                        "Une fois" to emptySet(),
                    ).forEach { (label, days) ->
                        FilterChip(
                            selected = alarm.days == days,
                            onClick = { alarm = alarm.copy(days = days) },
                            label = { Text(label) },
                        )
                    }
                }
            }

            // ---- Sound ----
            Section("Son") {
                Surface(
                    onClick = { showSource = true },
                    shape = RoundedCornerShape(20.dp),
                    color = scheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.MusicNote, null, tint = scheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(alarm.programTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            if (SpotifyLink.isSpotify(alarm.programUri)) {
                                Text("Spotify", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                            }
                        }
                        Icon(Icons.Filled.ChevronRight, null, tint = scheme.onSurfaceVariant)
                    }
                }
            }

            // ---- Volume ----
            Section("Volume") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.VolumeDown, null, tint = scheme.onSurfaceVariant)
                    Slider(
                        value = alarm.volume.toFloat(),
                        onValueChange = { alarm = alarm.copy(volume = it.roundToInt()) },
                        valueRange = 0f..100f,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = scheme.onSurfaceVariant)
                    Text(
                        "${alarm.volume}",
                        Modifier.width(40.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    )
                }
            }

            // ---- Duration ----
            Section("Durée de la sonnerie") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    DURATIONS.forEachIndexed { i, (minutes, label) ->
                        SegmentedButton(
                            selected = alarm.durationMinutes == minutes,
                            onClick = { alarm = alarm.copy(durationMinutes = minutes) },
                            shape = SegmentedButtonDefaults.itemShape(i, DURATIONS.size),
                        ) { Text(label) }
                    }
                }
            }

            // ---- Options ----
            Section("Options") {
                SwitchRow("Lecture aléatoire", "Pour les playlists", alarm.shuffle) {
                    alarm = alarm.copy(playMode = if (it) "SHUFFLE_NOREPEAT" else "NORMAL")
                }
                SwitchRow("Inclure les enceintes groupées", "Sonne aussi sur les enceintes regroupées", alarm.includeLinkedZones) {
                    alarm = alarm.copy(includeLinkedZones = it)
                }
                if (initial.id != null) {
                    SwitchRow("Alarme activée", null, alarm.enabled) { alarm = alarm.copy(enabled = it) }
                }
            }

            if (initial.id != null) {
                Section("Vacances") {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (pause != null) scheme.tertiaryContainer else scheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.BeachAccess, null,
                                    tint = if (pause != null) scheme.onTertiaryContainer else scheme.primary,
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    pause?.label ?: "Suspendre l'alarme pendant des vacances",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (pause != null) scheme.onTertiaryContainer else scheme.onSurface,
                                )
                            }
                            Text(
                                if (pause != null) "Elle se réactive toute seule pour le ${shortDate(pause.to.plusDays(1))}."
                                else "Elle se coupe puis se réactive toute seule.",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (pause != null) scheme.onTertiaryContainer.copy(alpha = 0.8f) else scheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 36.dp, top = 2.dp),
                            )
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                                if (pause != null) {
                                    TextButton(onClick = onCancelPause, enabled = !saving) { Text("Annuler la pause") }
                                }
                                TextButton(onClick = { showPause = true }, enabled = !saving) {
                                    Text(if (pause != null) "Modifier les dates" else "Choisir les dates")
                                }
                            }
                        }
                    }
                }
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    enabled = !saving,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.error),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Filled.Delete, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Supprimer l'alarme")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showTime) {
        val tps = rememberTimePickerState(alarm.startTime.hour, alarm.startTime.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = {
                TextButton(onClick = {
                    alarm = alarm.copy(startTime = LocalTime.of(tps.hour, tps.minute))
                    showTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("Annuler") } },
            text = { TimePicker(state = tps) },
        )
    }

    if (showSource) {
        SourceDialog(
            current = alarm,
            favorites = favorites,
            onPick = { uri, meta ->
                alarm = alarm.copy(programUri = uri, programMetaData = meta)
                showSource = false
            },
            onDismiss = { showSource = false },
            onOpenSpotify = {
                showSource = false
                showSpotify = true
            },
        )
    }

    if (showSpotify) {
        SpotifyPlaylistPicker(
            spotify = spotify,
            callbacks = spotifyCallbacks,
            selectedUri = alarm.programUri,
            onPick = { p ->
                val f = SpotifyLink.forId("playlist", p.id, p.name, favorites.orEmpty())
                alarm = alarm.copy(programUri = f.uri, programMetaData = f.metadata, playMode = "SHUFFLE_NOREPEAT")
                showSpotify = false
            },
            onDismiss = { showSpotify = false },
        )
    }

    if (showPause) {
        PauseDialog(
            initial = pause,
            onConfirm = { from, to ->
                showPause = false
                onSetPause(from, to)
            },
            onDismiss = { showPause = false },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer cette alarme ?") },
            text = { Text("L'alarme de ${initial.startTime.hm()} sera supprimée de l'enceinte.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(initial)
                }) { Text("Supprimer", color = scheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun SourceDialog(
    current: SonosAlarm,
    favorites: List<Favorite>?,
    onPick: (uri: String, meta: String) -> Unit,
    onDismiss: () -> Unit,
    onOpenSpotify: () -> Unit,
) {
    var link by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val parsed = SpotifyLink.parse(link)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Son de l'alarme") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        text = {
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                item {
                    SourceRow("Carillon Sonos", null, current.programUri == BUZZER_URI || current.programUri.isBlank()) {
                        onPick(BUZZER_URI, "")
                    }
                }
                item {
                    Surface(
                        onClick = onOpenSpotify,
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF1DB954).copy(alpha = 0.15f),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.QueueMusic, null, tint = Color(0xFF1DB954))
                            Spacer(Modifier.width(12.dp))
                            Text("Mes playlists Spotify", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                            Icon(Icons.Filled.ChevronRight, null)
                        }
                    }
                }
                item { DialogHeader("Favoris Sonos") }
                when {
                    favorites == null -> item {
                        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Chargement…")
                        }
                    }
                    favorites.isEmpty() -> item {
                        Text(
                            "Aucun favori. Dans l'app Sonos, ajoutez une playlist Spotify à « Mes Sonos » pour la retrouver ici.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    else -> items(favorites) { f ->
                        SourceRow(f.title, if (SpotifyLink.isSpotify(f.uri)) "Spotify" else null, current.programUri == f.uri) {
                            onPick(f.uri, f.metadata)
                        }
                    }
                }
                item { DialogHeader("Lien Spotify") }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = link,
                            onValueChange = { link = it },
                            label = { Text("Lien de playlist ou d'album") },
                            placeholder = { Text("https://open.spotify.com/playlist/…") },
                            leadingIcon = { Icon(Icons.Filled.Link, null) },
                            isError = link.isNotBlank() && parsed == null,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Nom affiché (facultatif)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FilledTonalButton(
                            onClick = {
                                SpotifyLink.toFavorite(link, name.trim(), favorites.orEmpty())?.let { onPick(it.uri, it.metadata) }
                            },
                            enabled = parsed != null,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Utiliser ce lien") }
                        Text(
                            "Dans Spotify : Partager › Copier le lien. Le compte Spotify doit être relié à Sonos.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PauseDialog(
    initial: AlarmPause?,
    onConfirm: (LocalDate, LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    fun LocalDate.utcMillis() = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    fun Long.toDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
    val today = LocalDate.now().utcMillis()
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initial?.from?.utcMillis(),
        initialSelectedEndDateMillis = initial?.to?.utcMillis(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= today
        },
    )
    val start = state.selectedStartDateMillis
    val end = state.selectedEndDateMillis ?: start
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { if (start != null && end != null) onConfirm(start.toDate(), end.toDate()) },
                enabled = start != null,
            ) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    ) {
        DateRangePicker(
            state = state,
            title = {
                Text(
                    "Jours sans alarme",
                    Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp),
                )
            },
            headline = {
                Text(
                    when {
                        start == null -> "Premier et dernier jour"
                        end == null || end == start -> shortDate(start.toDate())
                        else -> "${shortDate(start.toDate())} → ${shortDate(end.toDate())}"
                    },
                    Modifier.padding(start = 24.dp, end = 12.dp, bottom = 12.dp),
                    style = MaterialTheme.typography.headlineSmall,
                )
            },
            showModeToggle = false,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DialogHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SourceRow(title: String, tag: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (tag != null) Text(tag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp),
        )
        content()
    }
}

@Composable
internal fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun DayPicker(days: Set<DayOfWeek>, onChange: (Set<DayOfWeek>) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        DayOfWeek.entries.forEach { d ->
            val on = d in days
            Surface(
                onClick = { onChange(if (on) days - d else days + d) },
                shape = CircleShape,
                color = if (on) scheme.primary else scheme.surfaceContainerHigh,
                contentColor = if (on) scheme.onPrimary else scheme.onSurfaceVariant,
                modifier = Modifier.size(42.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(Recurrence.letter(d), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
