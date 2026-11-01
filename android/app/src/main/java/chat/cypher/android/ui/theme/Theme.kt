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
            colors = listOf(Color(0xFF8B5CF6), Color(0xFF6D28D9)),
        )
}

private val DarkCypherPalette = CypherPalette(
    isDark = true,
    canvas = Color(0xFF0D0E17),
    surface0 = Color(0xFF121420),
    surface1 = Color(0xFF181A28),
    surface2 = Color(0xFF212437),
    surface3 = Color(0xFF2A2E45),
    borderSubtle = Color(0xFF25283C),
    borderStrong = Color(0xFF353954),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFA0A5C0),
    textMuted = Color(0xFF6E738F),
    accent = Color(0xFF7C4DFF),
    accentSecondary = Color(0xFFA78BFA),
    accentSoft = Color(0x2E7C4DFF),
    accentOnPrimary = Color(0xFFFFFFFF),
    auroraTop = Color(0xFF311B6E),
    auroraMid = Color(0xFF191338),
    glassSurface = Color(0xFF181A28),
    glassBorder = Color(0x24FFFFFF),
    encryptedGreen = Color(0xFF22C55E),
    encryptedGreenSoft = Color(0xFF122D25),
    warningAmber = Color(0xFFF59E0B),
    warningAmberSoft = Color(0x24F59E0B),
    dangerRed = Color(0xFFE13D4B),
    dangerRedSoft = Color(0xFF2B1219),
    outgoingBubbleStart = Color(0xFF7C4DFF),
    outgoingBubbleEnd = Color(0xFF6938EF),
    outgoingBubbleText = Color(0xFFFFFFFF),
    incomingBubble = Color(0xFF222538),
    incomingBubbleBorder = Color(0xFF2D3148),
    incomingBubbleText = Color(0xFFFFFFFF),
)

private val LightCypherPalette = CypherPalette(
    isDark = false,
    canvas = Color(0xFFF6F5FC),
    surface0 = Color(0xFFFFFFFF),
    surface1 = Color(0xFFEEEBFA),
    surface2 = Color(0xFFE4DFF6),
    surface3 = Color(0xFFD2C9EE),
    borderSubtle = Color(0xFFE2DDF2),
    borderStrong = Color(0xFFC8BFE4),
    textPrimary = Color(0xFF111222),
    textSecondary = Color(0xFF4F5370),
    textMuted = Color(0xFF757996),
    accent = Color(0xFF6D28D9),
    accentSecondary = Color(0xFF8B5CF6),
    accentSoft = Color(0x1F6D28D9),
    accentOnPrimary = Color(0xFFFFFFFF),
    auroraTop = Color(0xFFDDD6FE),
    auroraMid = Color(0xFFEDE9FE),
    glassSurface = Color(0xCCFFFFFF),
    glassBorder = Color(0x336D28D9),
    encryptedGreen = Color(0xFF16A34A),
    encryptedGreenSoft = Color(0xFFD1FAE5),
    warningAmber = Color(0xFFD97706),
    warningAmberSoft = Color(0x1FD97706),
    dangerRed = Color(0xFFDC2626),
    dangerRedSoft = Color(0xFFFEE2E2),
    outgoingBubbleStart = Color(0xFF7C4DFF),
    outgoingBubbleEnd = Color(0xFF6938EF),
    outgoingBubbleText = Color(0xFFFFFFFF),
    incomingBubble = Color(0xFFFFFFFF),
    incomingBubbleBorder = Color(0xFFE2DDF2),
    incomingBubbleText = Color(0xFF111222),
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
    val animSpec = tween<Color>(durationMillis = 220)

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
