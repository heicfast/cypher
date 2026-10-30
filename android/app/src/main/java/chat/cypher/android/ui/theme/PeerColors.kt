package chat.cypher.android.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import chat.cypher.android.core.PeerIdentity
import kotlin.math.abs

/**
 * Chroma applied after a peer's stable hue is derived.
 *
 * The djb2 hash + orange-avoidance hue math are byte-identical to the
 * bitchat iOS implementation — only the saturation/value styling differs
 * for the premium light design (vivid avatar fills, deeper text tones).
 */
@Immutable
data class PeerColorStyle(
    val saturation: Float,
    val value: Float,
) {
    companion object {
        /** Vivid fills for avatar circles (works on light + dark canvases). */
        val Avatar = PeerColorStyle(saturation = 0.62f, value = 0.86f)

        /** Deep tones for peer-name text on light surfaces. */
        val TextLight = PeerColorStyle(saturation = 0.72f, value = 0.48f)

        /** Brighter tones for peer-name text on dark surfaces. */
        val TextDark = PeerColorStyle(saturation = 0.58f, value = 0.80f)
    }
}

/** The single identity-to-color boundary used across the whole UI. */
fun peerColor(identity: PeerIdentity, style: PeerColorStyle): Color {
    var hash = 5381UL
    for (byte in identity.stableKey.toByteArray()) {
        hash = ((hash shl 5) + hash) + byte.toUByte().toULong()
    }

    var hue = (hash % 360UL).toDouble() / 360.0
    val orange = 30.0 / 360.0
    if (abs(hue - orange) < 0.05) {
        hue = (hue + 0.12) % 1.0
    }

    return Color.hsv(
        hue = (hue * 360).toFloat(),
        saturation = style.saturation,
        value = style.value,
    )
}

/** Avatar fill color for a peer identity. */
fun peerAvatarColor(identity: PeerIdentity): Color = peerColor(identity, PeerColorStyle.Avatar)

/** Text color for a peer display name. */
fun peerTextColor(identity: PeerIdentity, isDark: Boolean): Color =
    peerColor(identity, if (isDark) PeerColorStyle.TextDark else PeerColorStyle.TextLight)
