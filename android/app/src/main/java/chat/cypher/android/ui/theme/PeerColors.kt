package chat.cypher.android.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * High-contrast, WCAG AA+ compliant deterministic peer identity colors.
 */
object PeerColors {
    private val DarkModePeerAccents = listOf(
        Color(0xFF38BDF8), // Electric Sky
        Color(0xFF34D399), // Mint Emerald
        Color(0xFFA78BFA), // Soft Violet
        Color(0xFFF472B6), // Coral Rose
        Color(0xFFFBBF24), // Warm Amber
        Color(0xFF2DD4BF), // Cyan Teal
        Color(0xFF818CF8), // Indigo Glow
        Color(0xFFFB923C), // Tangerine
        Color(0xFF60A5FA), // Cobalt Blue
        Color(0xFFE879F9), // Orchid
    )

    private val LightModePeerAccents = listOf(
        Color(0xFF0284C7), // Deep Sky
        Color(0xFF059669), // Deep Emerald
        Color(0xFF7C3AED), // Deep Violet
        Color(0xFFDB2777), // Deep Rose
        Color(0xFFD97706), // Deep Amber
        Color(0xFF0D9488), // Deep Teal
        Color(0xFF4F46E5), // Deep Indigo
        Color(0xFFEA580C), // Deep Orange
        Color(0xFF2563EB), // Royal Blue
        Color(0xFFC026D3), // Fuchsia
    )

    fun forPeer(peerIdHex: String, isDark: Boolean = true): Color {
        val palette = if (isDark) DarkModePeerAccents else LightModePeerAccents
        if (peerIdHex.isBlank()) return palette[0]
        var hash = 5381
        for (ch in peerIdHex.lowercase()) {
            hash = ((hash shl 5) + hash) + ch.code
        }
        return palette[abs(hash) % palette.size]
    }
}
