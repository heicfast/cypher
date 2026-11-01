package chat.cypher.android.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import chat.cypher.android.core.AppThemeMode
import chat.cypher.android.core.MeshStore

@Immutable
data class CypherColorPalette(
    val bgPrimary: Color,
    val bgSecondary: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val surfaceGlass: Color,
    val borderSubtle: Color,
    val borderHighlight: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val accentLight: Color,
    val accentSubtle: Color,
    val bubbleOutgoingStart: Color,
    val bubbleOutgoingEnd: Color,
    val bubbleIncoming: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val dangerSurface: Color,
    val signalStrong: Color,
)

private val DefaultDarkColors = CypherColorPalette(
    bgPrimary = Color(0xFF0D0E17),
    bgSecondary = Color(0xFF121422),
    surface = Color(0xFF171928),
    surfaceElevated = Color(0xFF202336),
    surfaceGlass = Color(0xCC171928),
    borderSubtle = Color(0xFF262A40),
    borderHighlight = Color(0xFF3E4468),
    textPrimary = Color(0xFFF5F6FA),
    textSecondary = Color(0xFF9EA3BC),
    textMuted = Color(0xFF6B708B),
    accent = Color(0xFF8352FD),
    accentLight = Color(0xFFA78BFA),
    accentSubtle = Color(0x288352FD),
    bubbleOutgoingStart = Color(0xFF8352FD),
    bubbleOutgoingEnd = Color(0xFF6B39F4),
    bubbleIncoming = Color(0xFF1D2033),
    success = Color(0xFF34D399),
    warning = Color(0xFFFBBF24),
    danger = Color(0xFFEF4444),
    dangerSurface = Color(0xFF2B141D),
    signalStrong = Color(0xFF34D399),
)

private val AmoledDarkColors = DefaultDarkColors.copy(
    bgPrimary = Color(0xFF000000),
    bgSecondary = Color(0xFF07080E),
    surface = Color(0xFF10121D),
    surfaceElevated = Color(0xFF181A2A),
)

val LocalCypherColors = staticCompositionLocalOf { DefaultDarkColors }

object CypherTheme {
    val colors: CypherColorPalette
        @Composable
        @ReadOnlyComposable
        get() = LocalCypherColors.current
}

@Composable
fun CypherTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val themeMode by MeshStore.themeMode.collectAsState()
    val accentHex by MeshStore.accentHex.collectAsState()

    val parsedAccent = runCatching {
        Color(android.graphics.Color.parseColor(accentHex))
    }.getOrDefault(DefaultDarkColors.accent)

    val basePalette = when (themeMode) {
        AppThemeMode.AMOLED -> AmoledDarkColors
        AppThemeMode.DARK, AppThemeMode.SYSTEM -> DefaultDarkColors
    }.copy(
        accent = parsedAccent,
        accentSubtle = parsedAccent.copy(alpha = 0.16f),
    )

    val colorScheme = darkColorScheme(
        primary = basePalette.accent,
        onPrimary = Color.White,
        secondary = basePalette.accentLight,
        onSecondary = Color.White,
        tertiary = basePalette.success,
        background = basePalette.bgPrimary,
        onBackground = basePalette.textPrimary,
        surface = basePalette.surface,
        onSurface = basePalette.textPrimary,
        surfaceVariant = basePalette.surfaceElevated,
        onSurfaceVariant = basePalette.textSecondary,
        outline = basePalette.borderSubtle,
        error = basePalette.danger,
        onError = Color.White,
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    CompositionLocalProvider(LocalCypherColors provides basePalette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = CypherTypography,
            content = content,
        )
    }
}
