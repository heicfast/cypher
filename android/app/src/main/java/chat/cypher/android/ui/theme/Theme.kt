package chat.cypher.android.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Cypher premium design system — verified against the reference mockup:
 * cool light-blue canvas, white surfaces, saturated blue accents, tailless
 * bubbles, green presence.
 */
object CypherPalette {
    // Brand
    val Blue = Color(0xFF3B82F6)
    val BlueDark = Color(0xFF2563EB)
    val BlueSoft = Color(0xFF93B4F5)
    val Online = Color(0xFF10B981)

    // Light surfaces
    val CanvasLight = Color(0xFFEFF3F8)
    val SearchLight = Color(0xFFE3EAF3)
    val DividerPillLight = Color(0xFFE1E8F0)

    // Text
    val TextPrimary = Color(0xFF111827)
    val TextSecondary = Color(0xFF6B7280)
    val TextTertiary = Color(0xFF9CA3AF)

    // Dark surfaces
    val CanvasDark = Color(0xFF0B1220)
    val SurfaceDark = Color(0xFF141E30)
    val SurfaceElevatedDark = Color(0xFF1B2739)
    val SearchDark = Color(0xFF182338)

    val BadgeMuted = Color(0xFFC4CEDA)
    val Danger = Color(0xFFEF4444)
    val Warning = Color(0xFFF59E0B)
}

data class CypherColors(
    val canvas: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val searchField: Color,
    val bubbleIncoming: Color,
    val bubbleIncomingText: Color,
    val bubbleOutgoing: Color,
    val bubbleOutgoingText: Color,
    val accent: Color,
    val accentDark: Color,
    val online: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val badgeMuted: Color,
    val danger: Color,
    val dividerPill: Color,
    val isDark: Boolean,
)

val LightCypherColors = CypherColors(
    canvas = CypherPalette.CanvasLight,
    surface = Color.White,
    surfaceElevated = Color.White,
    searchField = CypherPalette.SearchLight,
    bubbleIncoming = Color.White,
    bubbleIncomingText = CypherPalette.TextPrimary,
    bubbleOutgoing = CypherPalette.Blue,
    bubbleOutgoingText = Color.White,
    accent = CypherPalette.Blue,
    accentDark = CypherPalette.BlueDark,
    online = CypherPalette.Online,
    textPrimary = CypherPalette.TextPrimary,
    textSecondary = CypherPalette.TextSecondary,
    textTertiary = CypherPalette.TextTertiary,
    badgeMuted = CypherPalette.BadgeMuted,
    danger = CypherPalette.Danger,
    dividerPill = CypherPalette.DividerPillLight,
    isDark = false,
)

val DarkCypherColors = CypherColors(
    canvas = CypherPalette.CanvasDark,
    surface = CypherPalette.SurfaceDark,
    surfaceElevated = CypherPalette.SurfaceElevatedDark,
    searchField = Color(0xFF182338),
    bubbleIncoming = CypherPalette.SurfaceElevatedDark,
    bubbleIncomingText = Color(0xFFE7EDF5),
    bubbleOutgoing = Color(0xFF2F6FE4),
    bubbleOutgoingText = Color.White,
    accent = Color(0xFF5B9BFB),
    accentDark = CypherPalette.Blue,
    online = CypherPalette.Online,
    textPrimary = Color(0xFFE7EDF5),
    textSecondary = Color(0xFF9AA8BC),
    textTertiary = Color(0xFF67748A),
    badgeMuted = Color(0xFF46536B),
    danger = Color(0xFFF87171),
    dividerPill = Color(0xFF1E2A40),
    isDark = true,
)

val LocalCypherColors = staticCompositionLocalOf { LightCypherColors }

/** Material scheme derived from [CypherColors] so M3 components match. */
private fun materialScheme(c: CypherColors): ColorScheme = if (c.isDark) {
    darkColorScheme(
        primary = c.accent,
        onPrimary = Color.White,
        primaryContainer = Color(0xFF1D3A6E),
        onPrimaryContainer = Color(0xFFD6E3FB),
        secondary = c.accent,
        background = c.canvas,
        onBackground = c.textPrimary,
        surface = c.surface,
        onSurface = c.textPrimary,
        surfaceVariant = c.surfaceElevated,
        onSurfaceVariant = c.textSecondary,
        outline = Color(0xFF3A465C),
        error = c.danger,
    )
} else {
    lightColorScheme(
        primary = c.accentDark,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFDCE9FD),
        onPrimaryContainer = Color(0xFF153E7E),
        secondary = c.accentDark,
        background = c.canvas,
        onBackground = c.textPrimary,
        surface = c.surface,
        onSurface = c.textPrimary,
        surfaceVariant = c.searchField,
        onSurfaceVariant = c.textSecondary,
        outline = Color(0xFFC9D4E2),
        error = c.danger,
    )
}

/**
 * The Cypher theme. Follows the user's system dark-mode setting unless a
 * preference is pinned in settings.
 */
@Composable
fun CypherTheme(
    preference: ThemePreference? = null,
    content: @Composable () -> Unit,
) {
    val flowPreference by ThemePreferenceManager.themeFlow.collectAsState()
    val pref = preference ?: flowPreference
    val dark = when (pref) {
        ThemePreference.System -> isSystemInDarkTheme()
        ThemePreference.Light -> false
        ThemePreference.Dark -> true
    }
    val colors = if (dark) DarkCypherColors else LightCypherColors

    val view = LocalView.current
    val context = LocalContext.current
    androidx.compose.runtime.SideEffect {
        val window = (context as? Activity)?.window ?: return@SideEffect
        val insets = WindowCompat.getInsetsController(window, view)
        insets.isAppearanceLightStatusBars = !dark
        insets.isAppearanceLightNavigationBars = !dark
        window.statusBarColor = colors.canvas.toArgb()
        window.navigationBarColor = colors.surface.toArgb()
    }

    CompositionLocalProvider(LocalCypherColors provides colors) {
        MaterialTheme(
            colorScheme = materialScheme(colors),
            typography = CypherTypography,
            content = content,
        )
    }
}

/** Convenience accessor for the extended palette. */
object CypherThemeDefaults {
    val colors: CypherColors
        @Composable get() = LocalCypherColors.current
}
