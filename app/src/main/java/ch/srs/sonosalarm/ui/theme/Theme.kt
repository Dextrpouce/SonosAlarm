package ch.srs.sonosalarm.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// "Sunrise" palette: night indigo to morning amber. Used when dynamic colours are unavailable.
private val Light = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2E0FF),
    onPrimaryContainer = Color(0xFF15105C),
    secondary = Color(0xFF7C5800),
    secondaryContainer = Color(0xFFFFDEA6),
    tertiary = Color(0xFFD97706),
    background = Color(0xFFFBFAFF),
    surface = Color(0xFFFBFAFF),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFC3C0FF),
    onPrimary = Color(0xFF221A87),
    primaryContainer = Color(0xFF3A33B0),
    onPrimaryContainer = Color(0xFFE2E0FF),
    secondary = Color(0xFFF8BD48),
    secondaryContainer = Color(0xFF5E4200),
    tertiary = Color(0xFFFBBF24),
    background = Color(0xFF121318),
    surface = Color(0xFF121318),
)

@Composable
fun SonosAlarmTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamic: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
