package chat.cypher.android.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Cypher premium palette — iOS-style light theme (default) + deep-navy dark
 * theme, per the v0.4 design system (VLM-verified against the reference
 * mock-ups in `docs/design/`).
 */
data class CypherPalette(
    // Core surfaces
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val surfaceSearch: Color,
    // Brand
    val primary: Color,
    val primaryDark: Color,
    val onPrimary: Color,
    val primarySoft: Color,      // 12% wash for highlights / active nav pill
    // Text
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    // Bubbles
    val bubbleSent: Color,
    val bubbleSentText: Color,
    val bubbleReceived: Color,
    val bubbleReceivedText: Color,
    // Semantic
    val online: Color,
    val separator: Color,
    val error: Color,
    val success: Color,
    val warning: Color,
    // Peer avatar gradient stops (deterministic per peer identity)
    val avatarGradients: List<List<Color>>,
)

val LightCypherPalette = CypherPalette(
    background = Color(0xFFF0F4F8),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFFFFFFF),
    surfaceSearch = Color(0xFFE2E8F0),
    primary = Color(0xFF3B82F6),
    primaryDark = Color(0xFF2F6FE4),
    onPrimary = Color(0xFFFFFFFF),
    primarySoft = Color(0x1F3B82F6),
    textPrimary = Color(0xFF111827),
    textSecondary = Color(0xFF6B7280),
    textTertiary = Color(0xFF9CA3AF),
    bubbleSent = Color(0xFF3B82F6),
    bubbleSentText = Color(0xFFFFFFFF),
    bubbleReceived = Color(0xFFEFF2F6),
    bubbleReceivedText = Color(0xFF111827),
    online = Color(0xFF22C55E),
    separator = Color(0xFFE5E7EB),
    error = Color(0xFFEF4444),
    success = Color(0xFF22C55E),
    warning = Color(0xFFF59E0B),
    avatarGradients = listOf(
        listOf(Color(0xFFF472B6), Color(0xFFFB7185)), // rose
        listOf(Color(0xFF60A5FA), Color(0xFF3B82F6)), // blue
        listOf(Color(0xFF34D399), Color(0xFF10B981)), // emerald
        listOf(Color(0xFFFBBF24), Color(0xFFF59E0B)), // amber
        listOf(Color(0xFFA78BFA), Color(0xFF8B5CF6)), // violet
        listOf(Color(0xFF22D3EE), Color(0xFF0EA5E9)), // cyan
        listOf(Color(0xFFFB923C), Color(0xFFF97316)), // orange
        listOf(Color(0xFF4ADE80), Color(0xFF22C55E)), // green
    ),
)

val DarkCypherPalette = CypherPalette(
    background = Color(0xFF0B1220),
    surface = Color(0xFF151E2E),
    surfaceElevated = Color(0xFF1B2637),
    surfaceSearch = Color(0xFF1E2A3D),
    primary = Color(0xFF3B82F6),
    primaryDark = Color(0xFF2F6FE4),
    onPrimary = Color(0xFFFFFFFF),
    primarySoft = Color(0x293B82F6),
    textPrimary = Color(0xFFF1F5F9),
    textSecondary = Color(0xFF94A3B8),
    textTertiary = Color(0xFF64748B),
    bubbleSent = Color(0xFF3B82F6),
    bubbleSentText = Color(0xFFFFFFFF),
    bubbleReceived = Color(0xFF223047),
    bubbleReceivedText = Color(0xFFE7EDF5),
    online = Color(0xFF4ADE80),
    separator = Color(0xFF24324A),
    error = Color(0xFFF87171),
    success = Color(0xFF4ADE80),
    warning = Color(0xFFFBBF24),
    avatarGradients = LightCypherPalette.avatarGradients,
)

val LocalCypherPalette = staticCompositionLocalOf { LightCypherPalette }

/**
 * Deterministic peer color: djb2 hash of the stable identity key → index into
 * the avatar gradient table (mirrors bitchat's `colorForPeer` semantics).
 * Pure function — the gradient table has a fixed size of 8 in both themes.
 */
fun peerGradientIndex(identityKey: String, tableSize: Int = 8): Int {
    var hash = 5381L
    for (c in identityKey) hash = ((hash shl 5) + hash + c.code) and 0xFFFFFFFFL
    return (hash % tableSize).toInt()
}
