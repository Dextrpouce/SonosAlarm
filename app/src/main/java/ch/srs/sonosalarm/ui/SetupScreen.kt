package ch.srs.sonosalarm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun SetupScreen(
    state: UiState,
    snackbar: SnackbarHostState,
    onDiscover: () -> Unit,
    onConnect: (String) -> Unit,
) {
    var ip by remember { mutableStateOf("") }
    val scheme = MaterialTheme.colorScheme
    LaunchedEffect(Unit) { onDiscover() }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier.size(72.dp).background(scheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Alarm, null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(36.dp))
            }
            Text("Connectez votre Sonos", style = MaterialTheme.typography.headlineMedium)
            Text(
                "L'appareil doit être sur le même Wi-Fi que l'enceinte.",
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurfaceVariant,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Enceintes trouvées", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (state.discovering) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onDiscover) { Icon(Icons.Filled.Refresh, "Rechercher à nouveau") }
                }
            }
            state.discovered.forEach { sp ->
                ElevatedCard(
                    onClick = { onConnect(sp.ip) },
                    enabled = !state.connecting,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ListItem(
                        headlineContent = { Text(sp.name) },
                        supportingContent = { Text(listOf(sp.model, sp.ip).filter { it.isNotBlank() }.joinToString(" · ")) },
                        leadingContent = { Icon(Icons.Filled.Speaker, null, tint = scheme.primary) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
            if (!state.discovering && state.discovered.isEmpty()) {
                Text(
                    "Aucune enceinte trouvée. Vérifiez le Wi-Fi, ou saisissez l'adresse IP ci-dessous (app Sonos › Paramètres › Système › À propos).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Ou saisissez l'adresse IP", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = ip,
                onValueChange = { ip = it },
                label = { Text("Adresse IP") },
                placeholder = { Text("192.168.1.50") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onConnect(ip) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onConnect(ip) },
                enabled = ip.isNotBlank() && !state.connecting,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (state.connecting) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = scheme.onPrimary)
                    Spacer(Modifier.width(12.dp))
                }
                Text("Connecter")
            }
        }
    }
}
