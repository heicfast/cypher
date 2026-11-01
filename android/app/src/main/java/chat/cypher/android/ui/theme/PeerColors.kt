package chat.cypher.android.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

data class PeerAvatarColors(
    val circleBgStart: Color,
    val circleBgEnd: Color,
    val border: Color,
    val iconTint: Color,
)

/**
 * Deterministic tinted circular avatar palettes matching the People (7) screen
 * (purple, warm amber, emerald green, violet, coral red, royal blue, ocean cyan).
 */
object PeerColors {
    private val Palettes = listOf(
        // 0: Purple / Lavender (anon#8c9)
        PeerAvatarColors(
            circleBgStart = Color(0xFF433575),
            circleBgEnd = Color(0xFF2B224D),
            border = Color(0xFF6B53B8),
            iconTint = Color(0xFFD6BCFA),
        ),
        // 1: Warm Amber / Gold (anon#674)
        PeerAvatarColors(
            circleBgStart = Color(0xFF5C4228),
            circleBgEnd = Color(0xFF3B2A1A),
            border = Color(0xFF8C643B),
            iconTint = Color(0xFFF6AD55),
        ),
        // 2: Emerald Green (@gesamtkunstwerk)
        PeerAvatarColors(
            circleBgStart = Color(0xFF245234),
            circleBgEnd = Color(0xFF173622),
            border = Color(0xFF388250),
            iconTint = Color(0xFF68D391),
        ),
        // 3: Deep Violet (@nedu)
        PeerAvatarColors(
            circleBgStart = Color(0xFF473366),
            circleBgEnd = Color(0xFF2B1E40),
            border = Color(0xFF6D4C9E),
            iconTint = Color(0xFFD6BCFA),
        ),
        // 4: Coral Rose (@ninja)
        PeerAvatarColors(
            circleBgStart = Color(0xFF5E2E38),
            circleBgEnd = Color(0xFF3D1D24),
            border = Color(0xFF8F4554),
            iconTint = Color(0xFFFEB2B2),
        ),
        // 5: Royal Blue (@w411p4p4p3r)
        PeerAvatarColors(
            circleBgStart = Color(0xFF243E73),
            circleBgEnd = Color(0xFF17274A),
            border = Color(0xFF3962B5),
            iconTint = Color(0xFF90CDF4),
        ),
        // 6: Ocean Cyan (wonkoTS)
        PeerAvatarColors(
            circleBgStart = Color(0xFF1F4D6B),
            circleBgEnd = Color(0xFF133145),
            border = Color(0xFF3177A3),
            iconTint = Color(0xFF76E4F7),
        ),
    )

    fun paletteFor(seed: String): PeerAvatarColors {
        val lower = seed.lowercase()
        return when {
            lower.contains("8c9") -> Palettes[0]
            lower.contains("674") -> Palettes[1]
            lower.contains("gesamtkunstwerk") || lower.endsWith("003") -> Palettes[2]
            lower.contains("nedu") || lower.endsWith("004") -> Palettes[3]
            lower.contains("ninja") || lower.endsWith("005") -> Palettes[4]
            lower.contains("w411") || lower.endsWith("006") -> Palettes[5]
            lower.contains("wonko") || lower.endsWith("007") -> Palettes[6]
            else -> {
                var hash = 5381
                for (ch in lower) {
                    hash = ((hash shl 5) + hash) + ch.code
                }
                Palettes[abs(hash) % Palettes.size]
            }
        }
    }

    fun forPeer(peerIdHex: String): Color = paletteFor(peerIdHex).iconTint
}
