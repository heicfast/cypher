package chat.cypher.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable

/**
 * Cypher theme adapter over the bitchat design system (same palette,
 * typography, and tokens — the visual identity carries over unchanged).
 */

/** Alias for the bitchat theme composable. */
@Composable
fun CypherTheme(content: @Composable () -> Unit) {
    BitchatTheme(content = content)
}

/** Material typography on the Geist Mono family (the bitchat tokens). */
val CypherTypography: Typography = Typography
