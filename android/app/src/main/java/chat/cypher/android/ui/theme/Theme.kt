package chat.cypher.android.ui.theme

import android.app.Activity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Immutable
data class CypherPalette(
    val isDark: Boolean,
    val canvas: Color,
    val surface0: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val borderSubtle: Color,
    val borderStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val accentSoft: Color,
    val accentOnPrimary: Color,
    val encryptedGreen: Color,
    val encryptedGreenSoft: Color,
    val warningAmber: Color,
    val warningAmberSoft: Color,
    val dangerRed: Color,
    val dangerRedSoft: Color,
    val outgoingBubbleStart: Color,
    val outgoingBubbleEnd: Color,
    val outgoingBubbleText: Color,
    val incomingBubble: Color,
    val incomingBubbleBorder: Color,
    val incomingBubbleText: Color,
)

private val DarkCypherPalette = CypherPalette(
    isDark = true,
    canvas = Color(0xFF060911),
    surface0 = Color(0xFF0A0F1C),
    surface1 = Color(0xFF101729),
    surface2 = Color(0xFF162038),
    surface3 = Color(0xFF1E2B4A),
    borderSubtle = Color(0xFF1D2942),
    borderStrong = Color(0xFF2B3D63),
    textPrimary = Color(0xFFF8FAFC),
    textSecondary = Color(0xFF94A3B8),
    textMuted = Color(0xFF64748B),
    accent = Color(0xFF38BDF8),
    accentSoft = Color(0x2438BDF8),
    accentOnPrimary = Color(0xFF04101C),
    encryptedGreen = Color(0xFF10B981),
    encryptedGreenSoft = Color(0x2410B981),
    warningAmber = Color(0xFFF59E0B),
    warningAmberSoft = Color(0x24F59E0B),
    dangerRed = Color(0xFFEF4444),
    dangerRedSoft = Color(0x24EF4444),
    outgoingBubbleStart = Color(0xFF0284C7),
    outgoingBubbleEnd = Color(0xFF0369A1),
    outgoingBubbleText = Color(0xFFF8FAFC),
    incomingBubble = Color(0xFF11192B),
    incomingBubbleBorder = Color(0xFF1E2B45),
    incomingBubbleText = Color(0xFFF1F5F9),
)

private val LightCypherPalette = CypherPalette(
    isDark = false,
    canvas = Color(0xFFF8FAFC),
    surface0 = Color(0xFFFFFFFF),
    surface1 = Color(0xFFF1F5F9),
    surface2 = Color(0xFFE2E8F0),
    surface3 = Color(0xFFCBD5E1),
    borderSubtle = Color(0xFFE2E8F0),
    borderStrong = Color(0xFFCBD5E1),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF475569),
    textMuted = Color(0xFF64748B),
    accent = Color(0xFF0284C7),
    accentSoft = Color(0x1F0284C7),
    accentOnPrimary = Color(0xFFFFFFFF),
    encryptedGreen = Color(0xFF059669),
    encryptedGreenSoft = Color(0x1F059669),
    warningAmber = Color(0xFFD97706),
    warningAmberSoft = Color(0x1FD97706),
    dangerRed = Color(0xFFDC2626),
    dangerRedSoft = Color(0x1FDC2626),
    outgoingBubbleStart = Color(0xFF0284C7),
    outgoingBubbleEnd = Color(0xFF0369A1),
    outgoingBubbleText = Color(0xFFFFFFFF),
    incomingBubble = Color(0xFFFFFFFF),
    incomingBubbleBorder = Color(0xFFE2E8F0),
    incomingBubbleText = Color(0xFF0F172A),
)

val LocalCypherPalette = staticCompositionLocalOf { DarkCypherPalette }

@Composable
fun CypherTheme(
    preference: ThemePreference = ThemePreference.DARK,
    content: @Composable () -> Unit,
) {
    val isDark = when (preference) {
        ThemePreference.DARK -> true
        ThemePreference.LIGHT -> false
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
    }

    val target = if (isDark) DarkCypherPalette else LightCypherPalette
    val animSpec = tween<Color>(durationMillis = 240)

    val animatedPalette = CypherPalette(
        isDark = isDark,
        canvas = animateColorAsState(target.canvas, animSpec, label = "canvas").value,
        surface0 = animateColorAsState(target.surface0, animSpec, label = "surface0").value,
        surface1 = animateColorAsState(target.surface1, animSpec, label = "surface1").value,
        surface2 = animateColorAsState(target.surface2, animSpec, label = "surface2").value,
        surface3 = animateColorAsState(target.surface3, animSpec, label = "surface3").value,
        borderSubtle = animateColorAsState(target.borderSubtle, animSpec, label = "borderSubtle").value,
        borderStrong = animateColorAsState(target.borderStrong, animSpec, label = "borderStrong").value,
        textPrimary = animateColorAsState(target.textPrimary, animSpec, label = "textPrimary").value,
        textSecondary = animateColorAsState(target.textSecondary, animSpec, label = "textSecondary").value,
        textMuted = animateColorAsState(target.textMuted, animSpec, label = "textMuted").value,
        accent = animateColorAsState(target.accent, animSpec, label = "accent").value,
        accentSoft = animateColorAsState(target.accentSoft, animSpec, label = "accentSoft").value,
        accentOnPrimary = animateColorAsState(target.accentOnPrimary, animSpec, label = "accentOnPrimary").value,
        encryptedGreen = animateColorAsState(target.encryptedGreen, animSpec, label = "encryptedGreen").value,
        encryptedGreenSoft = animateColorAsState(target.encryptedGreenSoft, animSpec, label = "encryptedGreenSoft").value,
        warningAmber = animateColorAsState(target.warningAmber, animSpec, label = "warningAmber").value,
        warningAmberSoft = animateColorAsState(target.warningAmberSoft, animSpec, label = "warningAmberSoft").value,
        dangerRed = animateColorAsState(target.dangerRed, animSpec, label = "dangerRed").value,
        dangerRedSoft = animateColorAsState(target.dangerRedSoft, animSpec, label = "dangerRedSoft").value,
        outgoingBubbleStart = animateColorAsState(target.outgoingBubbleStart, animSpec, label = "outgoingStart").value,
        outgoingBubbleEnd = animateColorAsState(target.outgoingBubbleEnd, animSpec, label = "outgoingEnd").value,
        outgoingBubbleText = animateColorAsState(target.outgoingBubbleText, animSpec, label = "outgoingText").value,
        incomingBubble = animateColorAsState(target.incomingBubble, animSpec, label = "incomingBubble").value,
        incomingBubbleBorder = animateColorAsState(target.incomingBubbleBorder, animSpec, label = "incomingBorder").value,
        incomingBubbleText = animateColorAsState(target.incomingBubbleText, animSpec, label = "incomingText").value,
    )

    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = animatedPalette.accent,
            onPrimary = animatedPalette.accentOnPrimary,
            primaryContainer = animatedPalette.surface2,
            onPrimaryContainer = animatedPalette.textPrimary,
            secondary = animatedPalette.encryptedGreen,
            onSecondary = Color(0xFF022C22),
            secondaryContainer = animatedPalette.surface2,
            onSecondaryContainer = animatedPalette.textPrimary,
            tertiary = animatedPalette.warningAmber,
            background = animatedPalette.canvas,
            onBackground = animatedPalette.textPrimary,
            surface = animatedPalette.surface0,
            onSurface = animatedPalette.textPrimary,
            surfaceVariant = animatedPalette.surface1,
            onSurfaceVariant = animatedPalette.textSecondary,
            outline = animatedPalette.borderSubtle,
            outlineVariant = animatedPalette.borderStrong,
            error = animatedPalette.dangerRed,
            onError = Color.White,
        )
    } else {
        lightColorScheme(
            primary = animatedPalette.accent,
            onPrimary = animatedPalette.accentOnPrimary,
            primaryContainer = animatedPalette.surface1,
            onPrimaryContainer = animatedPalette.textPrimary,
            secondary = animatedPalette.encryptedGreen,
            onSecondary = Color.White,
            secondaryContainer = animatedPalette.surface1,
            onSecondaryContainer = animatedPalette.textPrimary,
            tertiary = animatedPalette.warningAmber,
            background = animatedPalette.canvas,
            onBackground = animatedPalette.textPrimary,
            surface = animatedPalette.surface0,
            onSurface = animatedPalette.textPrimary,
            surfaceVariant = animatedPalette.surface1,
            onSurfaceVariant = animatedPalette.textSecondary,
            outline = animatedPalette.borderSubtle,
            outlineVariant = animatedPalette.borderStrong,
            error = animatedPalette.dangerRed,
            onError = Color.White,
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    CompositionLocalProvider(LocalCypherPalette provides animatedPalette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = CypherTypography,
            content = content,
        )
    }
}
