package app.bpeq.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Extra colours the graph and status chips use, beyond the Material scheme. */
object Palette {
    val accent = Color(0xFF8AB4FF)    // EQ curve, primary
    val warm = Color(0xFFFFB86B)      // selected band / device curve
    val good = Color(0xFF7EE0B5)
    val bad = Color(0xFFFF8A80)
    val warn = Color(0xFFFFD166)
}

private val Dark = darkColorScheme(
    primary = Palette.accent,
    onPrimary = Color(0xFF0A1A33),
    primaryContainer = Color(0xFF223554),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Palette.warm,
    onSecondary = Color(0xFF2B1700),
    secondaryContainer = Color(0xFF4A3215),
    onSecondaryContainer = Color(0xFFFFDDB8),
    tertiary = Palette.good,
    background = Color(0xFF0E1014),
    onBackground = Color(0xFFE3E5EA),
    surface = Color(0xFF0E1014),
    onSurface = Color(0xFFE3E5EA),
    surfaceVariant = Color(0xFF1E232C),
    onSurfaceVariant = Color(0xFFB7BDC9),
    surfaceContainerLowest = Color(0xFF0A0C0F),
    surfaceContainerLow = Color(0xFF14171D),
    surfaceContainer = Color(0xFF181C23),
    surfaceContainerHigh = Color(0xFF1F242C),
    surfaceContainerHighest = Color(0xFF272D36),
    outline = Color(0xFF454C59),
    outlineVariant = Color(0xFF2C323C),
    error = Palette.bad,
)

private val Light = lightColorScheme(
    primary = Color(0xFF2F5FAF),
    secondary = Color(0xFFB0640A),
    tertiary = Color(0xFF1E8A5E),
)

@Composable
fun BpeqTheme(dark: Boolean = true, content: @Composable () -> Unit) { // dark-first; light kept for later setting
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Typography(), content = content)
}
