package ch.srs.sonosalarm.alarm

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import ch.srs.sonosalarm.data.AlarmStore
import ch.srs.sonosalarm.hm
import ch.srs.sonosalarm.ui.theme.SonosAlarmTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Full-screen wake-up screen, shown over the lock screen. */
class RingingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        lifecycleScope.launch { AlarmActions.closeRingingScreen.collect { finish() } }
        setContent {
            SonosAlarmTheme(dark = true, dynamic = false) {
                RingingScreen(onDone = { finish() })
            }
        }
    }
}

@Composable
private fun RingingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val store = remember { AlarmStore(context) }
    // Re-read every second: the phone may take over a few seconds after the screen opens.
    val session by produceState(store.session) {
        while (true) {
            delay(1000)
            value = store.session
        }
    }
    val speaker = remember { store.speaker }
    val where = if (session?.onPhone == true) "sur le téléphone" else speaker?.name ?: "Sonos"
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val now by produceState(LocalTime.now()) {
        while (true) {
            value = LocalTime.now()
            delay(1000)
        }
    }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "scale",
    )

    fun act(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                block()
                onDone()
            } catch (e: Exception) {
                error = e.message ?: "L'enceinte n'a pas répondu"
                busy = false
            }
        }
    }

    val step = store.volumeStep
    val current = session?.targetVolume
    val next = current?.let { if (step <= 0 || it >= store.maxVolume) it else (it + step).coerceAtMost(store.maxVolume) }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF14103A), Color(0xFF3B2A8C), Color(0xFFB4539A), Color(0xFFF59E0B)),
                ),
            ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))
            Text(
                LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH))
                    .replaceFirstChar { it.uppercase() },
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                now.hm(),
                color = Color.White,
                fontSize = 104.sp,
                fontWeight = FontWeight.Thin,
            )
            Text(
                session.let { s ->
                    when {
                        s == null -> where
                        s.snoozeCount > 0 -> "Rappel n°${s.snoozeCount} · $where"
                        else -> "Réveil de ${s.label} · $where"
                    }
                },
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyLarge,
            )

            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(132.dp)
                    .scale(pulse)
                    .background(Color.White.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Alarm, null, tint = Color.White, modifier = Modifier.size(64.dp))
            }
            Spacer(Modifier.weight(1f))

            if (current != null && next != null && next != current) {
                Text(
                    "Volume $current → $next au prochain rappel",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(15, 30).forEach { minutes ->
                    Button(
                        onClick = { act { AlarmActions.snooze(context, minutes) } },
                        enabled = !busy,
                        modifier = Modifier.weight(1f).height(72.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color(0xFF2B1F73),
                        ),
                    ) {
                        Icon(Icons.Filled.Snooze, null)
                        Spacer(Modifier.width(8.dp))
                        Text("+$minutes min", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { act { AlarmActions.stop(context) } },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.8f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            ) {
                Text("Arrêter", fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }
            if (busy) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color.White)
            }
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
