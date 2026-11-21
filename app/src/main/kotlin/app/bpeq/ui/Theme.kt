package app.bpeq.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The user's theme choice (Device tab → Settings). SYSTEM follows the phone's light/dark setting. */
enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark"), AMOLED("AMOLED") }

object ThemePrefs {
    private const val KEY = "theme"
    private val _mode = MutableStateFlow(ThemeMode.SYSTEM)
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    fun load(ctx: Context) {
        _mode.value = runCatching { ThemeMode.valueOf(ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(KEY, null)!!) }
            .getOrDefault(ThemeMode.SYSTEM)
    }

    fun set(ctx: Context, m: ThemeMode) {
        _mode.value = m
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString(KEY, m.name).apply()
    }
}

/** Extra colours the graph and status chips use, beyond the Material scheme; they follow the active theme. */
object Palette {
    class Set(val accent: Color, val warm: Color, val good: Color, val bad: Color, val warn: Color, val meas: Color, val compare: Color)

    val DARK = Set(
        accent = Color(0xFF8AB4FF), warm = Color(0xFFFFB86B), good = Color(0xFF7EE0B5), bad = Color(0xFFFF8A80),
        warn = Color(0xFFFFD166), meas = Color(0xFFB0B7C3), compare = Color(0xFFC792EA),
    )
    /** Darker, more saturated versions so lines and text keep their contrast on a light background. */
    val LIGHT = Set(
        accent = Color(0xFF2F5FAF), warm = Color(0xFFB45F00), good = Color(0xFF1B7F55), bad = Color(0xFFC62828),
        warn = Color(0xFF8A6100), meas = Color(0xFF6B7280), compare = Color(0xFF8E3FB8),
    )

    /** Snapshot state, so reading a colour in composition recomposes when the theme changes. */
    internal var current by mutableStateOf(DARK)

    val accent get() = current.accent    // EQ curve, primary
    val warm get() = current.warm        // selected band / device curve
    val good get() = current.good
    val bad get() = current.bad
    val warn get() = current.warn
    val meas get() = current.meas        // IEM measurement overlay
    val compare get() = current.compare  // compared profile overlay
}

private fun darkScheme(p: Palette.Set) = darkColorScheme(
    primary = p.accent,
    onPrimary = Color(0xFF0A1A33),
    primaryContainer = Color(0xFF223554),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = p.warm,
    onSecondary = Color(0xFF2B1700),
    secondaryContainer = Color(0xFF4A3215),
    onSecondaryContainer = Color(0xFFFFDDB8),
    tertiary = p.good,
    onTertiary = Color(0xFF00281A),
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
    error = p.bad,
)

/** AMOLED: true black background (pixels off), cards just above black, same accents as Dark. */
private val Amoled = darkScheme(Palette.DARK).copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF15181D),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF07080A),
    surfaceContainer = Color(0xFF0D0F12),
    surfaceContainerHigh = Color(0xFF14171B),
    surfaceContainerHighest = Color(0xFF1B1F25),
    outlineVariant = Color(0xFF22262D),
)

private val Dark = darkScheme(Palette.DARK)

private val Light = lightColorScheme(
    primary = Palette.LIGHT.accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E3FA),
    onPrimaryContainer = Color(0xFF0E2A55),
    secondary = Palette.LIGHT.warm,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE2C4),
    onSecondaryContainer = Color(0xFF3A1E00),
    tertiary = Palette.LIGHT.good,
    onTertiary = Color.White,
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF1A1D23),
    surface = Color(0xFFF7F8FA),
    onSurface = Color(0xFF1A1D23),
    surfaceVariant = Color(0xFFE3E6EC),
    onSurfaceVariant = Color(0xFF4A505C),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F3F6),
    surfaceContainer = Color(0xFFEBEEF2),
    surfaceContainerHigh = Color(0xFFE4E8ED),
    surfaceContainerHighest = Color(0xFFDDE1E7),
    outline = Color(0xFF8A919E),
    outlineVariant = Color(0xFFC9CED6),
    error = Palette.LIGHT.bad,
)

/** True when [mode] means a dark theme right now. */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK, ThemeMode.AMOLED -> true
}

@Composable
fun BpeqTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = mode.isDark()
    val scheme = when {
        !dark -> Light
        mode == ThemeMode.AMOLED -> Amoled
        else -> Dark
    }
    val palette = if (dark) Palette.DARK else Palette.LIGHT
    // Set before the content reads it in this composition. The check doesn't subscribe this scope to the state,
    // so the write can't make the theme recompose itself.
    if (Snapshot.withoutReadObservation { Palette.current } !== palette) Palette.current = palette
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
