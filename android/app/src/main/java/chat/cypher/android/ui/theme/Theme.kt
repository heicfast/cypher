package chat.cypher.android.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Theme preference (System / Light / Dark), persisted in SharedPreferences. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

object ThemePrefs {
    private const val PREFS = "cypher_settings"
    private const val KEY_THEME = "theme_mode"

    @Volatile
    var current: ThemeMode = ThemeMode.SYSTEM
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        current = runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name)!!)
        }.getOrDefault(ThemeMode.SYSTEM)
    }

    fun set(context: Context, mode: ThemeMode) {
        current = mode
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME, mode.name).apply()
    }
}

/** M3 color scheme mapped onto the Cypher premium palette. */
private fun m3Scheme(p: CypherPalette, dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = p.primary,
        onPrimary = p.onPrimary,
        primaryContainer = p.primaryDark,
        onPrimaryContainer = p.onPrimary,
        secondary = p.primary,
        onSecondary = p.onPrimary,
        background = p.background,
        onBackground = p.textPrimary,
        surface = p.surface,
        onSurface = p.textPrimary,
        surfaceVariant = p.surfaceElevated,
        onSurfaceVariant = p.textSecondary,
        outline = p.separator,
        outlineVariant = p.separator,
        error = p.error,
        onError = Color.White,
    )
} else {
    lightColorScheme(
        primary = p.primary,
        onPrimary = p.onPrimary,
        primaryContainer = Color(0xFFDBEAFE),
        onPrimaryContainer = Color(0xFF1E40AF),
        secondary = p.primary,
        onSecondary = p.onPrimary,
        background = p.background,
        onBackground = p.textPrimary,
        surface = p.surface,
        onSurface = p.textPrimary,
        surfaceVariant = p.surfaceSearch,
        onSurfaceVariant = p.textSecondary,
        outline = p.separator,
        outlineVariant = p.separator,
        error = p.error,
        onError = Color.White,
    )
}

val CypherShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun CypherTheme(
    mode: ThemeMode = ThemePrefs.current,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val palette = if (dark) DarkCypherPalette else LightCypherPalette
    CompositionLocalProvider(LocalCypherPalette provides palette) {
        MaterialTheme(
            colorScheme = m3Scheme(palette, dark),
            typography = CypherTypography,
            shapes = CypherShapes,
            content = content,
        )
    }
}
