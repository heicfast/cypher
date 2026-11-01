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
 * Deterministic tinted circular avatar palettes derived from a peer's cryptographic ID hash.
 */
object PeerColors {
    private val Palettes = listOf(
        PeerAvatarColors(
            circleBgStart = Color(0xFF433575),
            circleBgEnd = Color(0xFF2B224D),
            border = Color(0xFF6B53B8),
            iconTint = Color(0xFFD6BCFA),
        ),
        PeerAvatarColors(
            circleBgStart = Color(0xFF5C4228),
            circleBgEnd = Color(0xFF3B2A1A),
            border = Color(0xFF8C643B),
            iconTint = Color(0xFFF6AD55),
        ),
        PeerAvatarColors(
            circleBgStart = Color(0xFF245234),
            circleBgEnd = Color(0xFF173622),
            border = Color(0xFF388250),
            iconTint = Color(0xFF68D391),
        ),
        PeerAvatarColors(
            circleBgStart = Color(0xFF473366),
            circleBgEnd = Color(0xFF2B1E40),
            border = Color(0xFF6D4C9E),
            iconTint = Color(0xFFD6BCFA),
        ),
        PeerAvatarColors(
            circleBgStart = Color(0xFF5E2E38),
            circleBgEnd = Color(0xFF3D1D24),
            border = Color(0xFF8F4554),
            iconTint = Color(0xFFFEB2B2),
        ),
        PeerAvatarColors(
            circleBgStart = Color(0xFF243E73),
            circleBgEnd = Color(0xFF17274A),
            border = Color(0xFF3962B5),
            iconTint = Color(0xFF90CDF4),
        ),
        PeerAvatarColors(
            circleBgStart = Color(0xFF1F4D6B),
            circleBgEnd = Color(0xFF133145),
            border = Color(0xFF3177A3),
            iconTint = Color(0xFF76E4F7),
        ),
    )

    fun paletteFor(seed: String): PeerAvatarColors {
        val lower = seed.lowercase()
        var hash = 5381
        for (ch in lower) {
            hash = ((hash shl 5) + hash) + ch.code
        }
        return Palettes[abs(hash) % Palettes.size]
    }

    fun forPeer(peerIdHex: String): Color = paletteFor(peerIdHex).iconTint
}
