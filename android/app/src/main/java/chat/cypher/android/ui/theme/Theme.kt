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
import androidx.compose.ui.graphics.Brush
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
    val accentSecondary: Color,
    val accentSoft: Color,
    val accentOnPrimary: Color,
    val auroraTop: Color,
    val auroraMid: Color,
    val glassSurface: Color,
    val glassBorder: Color,
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
) {
    val primaryPillBrush: Brush
        get() = Brush.horizontalGradient(
            colors = listOf(accent, accentSecondary),
        )
}

private val DarkCypherPalette = CypherPalette(
    isDark = true,
    canvas = Color(0xFF04070F),
    surface0 = Color(0xFF080D1A),
    surface1 = Color(0xFF12192B),
    surface2 = Color(0xFF182238),
    surface3 = Color(0xFF212E4A),
    borderSubtle = Color(0xFF1E2A44),
    borderStrong = Color(0xFF2C3E63),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFF94A3B8),
    textMuted = Color(0xFF64748B),
    accent = Color(0xFF5B5EFF),
    accentSecondary = Color(0xFF38B6FF),
    accentSoft = Color(0x295B5EFF),
    accentOnPrimary = Color(0xFFFFFFFF),
    auroraTop = Color(0xFF1474A4),
    auroraMid = Color(0xFF0A395C),
    glassSurface = Color(0xFF162438),
    glassBorder = Color(0x26FFFFFF),
    encryptedGreen = Color(0xFF22C55E),
    encryptedGreenSoft = Color(0x2422C55E),
    warningAmber = Color(0xFFF59E0B),
    warningAmberSoft = Color(0x24F59E0B),
    dangerRed = Color(0xFFEF4444),
    dangerRedSoft = Color(0x24EF4444),
    outgoingBubbleStart = Color(0xFF192338),
    outgoingBubbleEnd = Color(0xFF151D30),
    outgoingBubbleText = Color(0xFFF8FAFC),
    incomingBubble = Color(0xFF141C2E),
    incomingBubbleBorder = Color(0xFF222F49),
    incomingBubbleText = Color(0xFFF8FAFC),
)

private val LightCypherPalette = CypherPalette(
    isDark = false,
    canvas = Color(0xFFF5F7FB),
    surface0 = Color(0xFFFFFFFF),
    surface1 = Color(0xFFEEF2F9),
    surface2 = Color(0xFFE2E8F3),
    surface3 = Color(0xFFCBD5E1),
    borderSubtle = Color(0xFFE2E8F0),
    borderStrong = Color(0xFFCBD5E1),
    textPrimary = Color(0xFF0B1120),
    textSecondary = Color(0xFF475569),
    textMuted = Color(0xFF64748B),
    accent = Color(0xFF4F46E5),
    accentSecondary = Color(0xFF0284C7),
    accentSoft = Color(0x1F4F46E5),
    accentOnPrimary = Color(0xFFFFFFFF),
    auroraTop = Color(0xFFBAE6FD),
    auroraMid = Color(0xFFE0F2FE),
    glassSurface = Color(0xCCFFFFFF),
    glassBorder = Color(0x330284C7),
    encryptedGreen = Color(0xFF16A34A),
    encryptedGreenSoft = Color(0x1F16A34A),
    warningAmber = Color(0xFFD97706),
    warningAmberSoft = Color(0x1FD97706),
    dangerRed = Color(0xFFDC2626),
    dangerRedSoft = Color(0x1FDC2626),
    outgoingBubbleStart = Color(0xFF4F46E5),
    outgoingBubbleEnd = Color(0xFF2563EB),
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
        accentSecondary = animateColorAsState(target.accentSecondary, animSpec, label = "accentSecondary").value,
        accentSoft = animateColorAsState(target.accentSoft, animSpec, label = "accentSoft").value,
        accentOnPrimary = animateColorAsState(target.accentOnPrimary, animSpec, label = "accentOnPrimary").value,
        auroraTop = animateColorAsState(target.auroraTop, animSpec, label = "auroraTop").value,
        auroraMid = animateColorAsState(target.auroraMid, animSpec, label = "auroraMid").value,
        glassSurface = animateColorAsState(target.glassSurface, animSpec, label = "glassSurface").value,
        glassBorder = animateColorAsState(target.glassBorder, animSpec, label = "glassBorder").value,
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
            secondary = animatedPalette.accentSecondary,
            onSecondary = Color.White,
            secondaryContainer = animatedPalette.surface2,
            onSecondaryContainer = animatedPalette.textPrimary,
            tertiary = animatedPalette.encryptedGreen,
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
            secondary = animatedPalette.accentSecondary,
            onSecondary = Color.White,
            secondaryContainer = animatedPalette.surface1,
            onSecondaryContainer = animatedPalette.textPrimary,
            tertiary = animatedPalette.encryptedGreen,
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
