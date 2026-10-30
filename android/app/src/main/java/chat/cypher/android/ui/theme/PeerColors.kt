package chat.cypher.android.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

data class ArtisticAvatarPalette(
    val primary: Color,
    val secondary: Color,
    val highlight: Color,
    val silhouette: Color,
)

/**
 * Rich, dreamy multi-stop artistic portrait palettes inspired by editorial digital portraits.
 */
object PeerColors {
    private val PortraitPalettes = listOf(
        // 0: Dreamy Lilac, Rose & Ice Cyan (Jenny / Daniel style)
        ArtisticAvatarPalette(
            primary = Color(0xFF9368F7),
            secondary = Color(0xFFF48FB1),
            highlight = Color(0xFF7DD3FC),
            silhouette = Color(0xFF311B6E),
        ),
        // 1: Sunset Coral, Amber & Warm Gold (Guy / Oliver style)
        ArtisticAvatarPalette(
            primary = Color(0xFFFF6B4A),
            secondary = Color(0xFFFFC145),
            highlight = Color(0xFFFF9E80),
            silhouette = Color(0xFF4A1912),
        ),
        // 2: Emerald Teal, Cyan & Warm Ochre (Evelyn / Kristin style)
        ArtisticAvatarPalette(
            primary = Color(0xFF0EA5E9),
            secondary = Color(0xFF14B8A6),
            highlight = Color(0xFFFBBF24),
            silhouette = Color(0xFF082F49),
        ),
        // 3: Deep Indigo, Electric Violet & Magenta (Arlene / Lucas style)
        ArtisticAvatarPalette(
            primary = Color(0xFF6366F1),
            secondary = Color(0xFFD946EF),
            highlight = Color(0xFF38BDF8),
            silhouette = Color(0xFF1E1B4B),
        ),
        // 4: Crimson Rose, Peach & Soft Lavender (Amelia / Emma style)
        ArtisticAvatarPalette(
            primary = Color(0xFFF43F5E),
            secondary = Color(0xFFFB923C),
            highlight = Color(0xFFFBCFE8),
            silhouette = Color(0xFF4C0519),
        ),
        // 5: Ocean Cerulean, Mint & Soft Rose (Sophia / Isabella style)
        ArtisticAvatarPalette(
            primary = Color(0xFF2563EB),
            secondary = Color(0xFF2DD4BF),
            highlight = Color(0xFFF472B6),
            silhouette = Color(0xFF0F172A),
        ),
        // 6: Golden Hour Amber, Terracotta & Teal (Noah / Elijah style)
        ArtisticAvatarPalette(
            primary = Color(0xFFEA580C),
            secondary = Color(0xFF38BDF8),
            highlight = Color(0xFFFDE047),
            silhouette = Color(0xFF291306),
        ),
        // 7: Pastel Pearl, Blush Pink & Sky Blue (Ava / Olivia style)
        ArtisticAvatarPalette(
            primary = Color(0xFFEC4899),
            secondary = Color(0xFF818CF8),
            highlight = Color(0xFFE0F2FE),
            silhouette = Color(0xFF3B0A28),
        ),
    )

    fun avatarPaletteFor(seed: String): ArtisticAvatarPalette {
        if (seed.isBlank()) return PortraitPalettes[0]
        var hash = 5381
        for (ch in seed.lowercase()) {
            hash = ((hash shl 5) + hash) + ch.code
        }
        return PortraitPalettes[abs(hash) % PortraitPalettes.size]
    }

    fun forPeer(peerIdHex: String, isDark: Boolean = true): Color {
        val p = avatarPaletteFor(peerIdHex)
        return if (isDark) p.highlight else p.primary
    }
}
