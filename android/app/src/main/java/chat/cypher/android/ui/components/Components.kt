package chat.cypher.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.cypher.android.ui.theme.LocalCypherPalette
import chat.cypher.android.ui.theme.peerGradientIndex

/**
 * Circular gradient avatar with the peer's initial (mesh apps have no photos;
 * the deterministic gradient stands in for one, like the reference design's
 * colorful avatars).
 */
@Composable
fun PeerAvatar(
    name: String,
    identityKey: String,
    size: Dp = 52.dp,
    online: Boolean = false,
    isGroup: Boolean = false,
    isMesh: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val palette = LocalCypherPalette.current
    val gradients = palette.avatarGradients
    val index = if (identityKey.isEmpty()) 0 else peerGradientIndex(identityKey)
    val gradient = gradients[index % gradients.size]
    val label = name.trim().take(1).uppercase().ifEmpty { "?" }
    Box(modifier = modifier.size(size)) {
        Box(
            modifier = Modifier
                .size(size)
                .background(
                    brush = Brush.linearGradient(gradient),
                    shape = if (isGroup) RoundedCornerShape(size * 0.35f) else CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.38f).sp,
            )
        }
        if (isMesh) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.30f)
                    .background(palette.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(size * 0.12f)
                        .background(Color.White, RoundedCornerShape(1.dp))
                )
            }
        } else if (online) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.26f)
                    .background(palette.online, CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
            )
        }
    }
}

/** Small circle used as the online-status indicator. */
@Composable
fun OnlineDot(size: Dp = 8.dp) {
    val palette = LocalCypherPalette.current
    Box(
        Modifier
            .size(size)
            .background(palette.online, CircleShape)
    )
}

/** Blue pill unread badge (reference: solid blue circle, white number). */
@Composable
fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    val palette = LocalCypherPalette.current
    val label = if (count > 99) "99+" else count.toString()
    Box(
        modifier = modifier
            .size(20.dp)
            .background(palette.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = palette.onPrimary,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Delivery ticks for own messages (✓ sending, ✓✓ delivered, blue✓✓ read). */
@Composable
fun DeliveryTicks(state: chat.cypher.android.data.Delivery?) {
    val palette = LocalCypherPalette.current
    val (color, glyph) = when (state) {
        null -> palette.textTertiary to ""
        chat.cypher.android.data.Delivery.SENDING -> palette.textTertiary to "✓"
        chat.cypher.android.data.Delivery.SENT -> palette.textTertiary to "✓✓"
        chat.cypher.android.data.Delivery.DELIVERED -> palette.textSecondary to "✓✓"
        chat.cypher.android.data.Delivery.READ -> palette.primary to "✓✓"
        chat.cypher.android.data.Delivery.FAILED -> palette.error to "!"
    }
    if (glyph.isEmpty()) return
    Text(
        text = glyph,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-1.5).sp,
    )
}
