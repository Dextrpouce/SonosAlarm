package ch.srs.sonosalarm.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ch.srs.sonosalarm.spotify.Spotify
import ch.srs.sonosalarm.spotify.SpotifyPlaylist
import coil.compose.AsyncImage

private val SpotifyGreen = Color(0xFF1DB954)

/** Client ID entry and login button, with the steps to create the Spotify developer app. */
@Suppress("DEPRECATION")
@Composable
fun SpotifySetup(spotify: SpotifyUi, callbacks: SpotifyCallbacks) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var clientId by remember(spotify.clientId) { mutableStateOf(spotify.clientId) }

    if (spotify.user != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, null, tint = SpotifyGreen)
            Spacer(Modifier.width(8.dp))
            Text("Connecté : ${spotify.user}", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = callbacks.logout) { Text("Déconnecter") }
        }
        if (!spotify.canPlay) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Reconnectez-vous une fois pour autoriser la lecture sur le téléphone quand l'enceinte ne répond pas.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                callbacks.logout()
                callbacks.login()
            }) { Text("Se reconnecter") }
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Une seule fois : créez une app développeur Spotify (gratuit, compte Premium requis).",
            style = MaterialTheme.typography.bodyMedium,
        )
        Step("1", "Sur developer.spotify.com/dashboard, touchez « Create app ».")
        TextButton(onClick = {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://developer.spotify.com/dashboard")))
            }
        }) { Text("Ouvrir le tableau de bord Spotify") }
        Step("2", "Cochez « Web API » et collez cette Redirect URI :")
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Spotify.REDIRECT_URI,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                IconButton(onClick = { clipboard.setText(AnnotatedString(Spotify.REDIRECT_URI)) }) {
                    Icon(Icons.Filled.ContentCopy, "Copier")
                }
            }
        }
        Step("3", "Copiez le « Client ID » de l'app ici :")
        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it.trim() },
            label = { Text("Client ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                callbacks.setClientId(clientId)
                callbacks.login()
            },
            enabled = clientId.length >= 16 && !spotify.loading,
            colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (spotify.loading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.Black)
                Spacer(Modifier.width(8.dp))
            }
            Text("Se connecter à Spotify", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Step(n: String, text: String) {
    Row {
        Box(
            Modifier.size(22.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(n, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Full-screen picker of the user's Spotify playlists. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyPlaylistPicker(
    spotify: SpotifyUi,
    callbacks: SpotifyCallbacks,
    selectedUri: String,
    onPick: (SpotifyPlaylist) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    LaunchedEffect(spotify.user) { callbacks.loadPlaylists(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Playlists Spotify") },
                    navigationIcon = { TextButton(onClick = onDismiss) { Text("Fermer") } },
                    actions = {
                        if (spotify.user != null) {
                            IconButton(onClick = { callbacks.loadPlaylists(true) }, enabled = !spotify.loading) {
                                Icon(Icons.Filled.Refresh, "Actualiser")
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
                if (spotify.user == null) {
                    Column(Modifier.verticalScrollable()) { SpotifySetup(spotify, callbacks) }
                    return@Column
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Rechercher une playlist") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                val list = spotify.playlists
                when {
                    list == null || (spotify.loading && list.isEmpty()) -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    list.isEmpty() -> Text(
                        "Aucune playlist sur ce compte Spotify.",
                        Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> {
                        val shown = list.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
                        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                            items(shown, key = { it.id }) { p ->
                                PlaylistRow(p, selected = selectedUri.contains(p.id), onClick = { onPick(p) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistRow(p: SpotifyPlaylist, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) scheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(scheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.QueueMusic, null, tint = scheme.onSurfaceVariant)
            if (p.imageUrl != null) {
                AsyncImage(
                    model = p.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(
                listOfNotNull(p.owner.takeIf { it.isNotBlank() }, p.tracks?.let { "$it titres" }).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        if (selected) Icon(Icons.Filled.CheckCircle, null, tint = SpotifyGreen)
    }
}

@Composable
private fun Modifier.verticalScrollable(): Modifier =
    this.verticalScroll(androidx.compose.foundation.rememberScrollState())
