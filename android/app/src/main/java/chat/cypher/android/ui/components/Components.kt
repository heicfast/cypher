package chat.cypher.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.cypher.android.core.DeliveryState
import chat.cypher.android.core.MeshStore
import chat.cypher.android.ui.theme.LocalCypherPalette
import chat.cypher.android.ui.theme.PeerColors

/**
 * Interlocking two-link chain logo of Cipher.
 * Supports optional triple-tap detection to trigger Panic Mode (`onTripleTap`).
 */
@Composable
fun CipherChainLogo(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    isDanger: Boolean = false,
    onTripleTap: (() -> Unit)? = null,
) {
    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapMs by remember { mutableLongStateOf(0L) }

    val clickMod = if (onTripleTap != null) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) {
            val now = System.currentTimeMillis()
            if (now - lastTapMs <= 750L) {
                tapCount += 1
            } else {
                tapCount = 1
            }
            lastTapMs = now
            if (tapCount >= 3) {
                tapCount = 0
                onTripleTap()
            }
        }
    } else {
        Modifier
    }

    Canvas(
        modifier = modifier
            .size(size)
            .then(clickMod),
    ) {
        val w = this.size.width
        val h = this.size.height
        val strokeWidth = w * 0.135f

        val gradientColors = if (isDanger) {
            listOf(Color(0xFFFF8A8A), Color(0xFFEF4444), Color(0xFFDC2626))
        } else {
            listOf(Color(0xFFD8B4FE), Color(0xFFA855F7), Color(0xFF7C3AED))
        }
        val brush = Brush.linearGradient(
            colors = gradientColors,
            start = Offset(0f, 0f),
            end = Offset(w, h),
        )

        rotate(degrees = -35f, pivot = Offset(w * 0.5f, h * 0.5f)) {
            val linkRadius = h * 0.21f
            val cy = h * 0.5f

            // Left U-shaped link
            val leftPath = Path().apply {
                moveTo(w * 0.46f, cy - linkRadius)
                lineTo(w * 0.28f, cy - linkRadius)
                cubicTo(
                    w * 0.10f,
                    cy - linkRadius,
                    w * 0.10f,
                    cy + linkRadius,
                    w * 0.28f,
                    cy + linkRadius,
                )
                lineTo(w * 0.52f, cy + linkRadius)
            }
            drawPath(
                path = leftPath,
                brush = brush,
                style = Stroke(
                    width = strokeWidth,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )

            // Right U-shaped link interlocking
            val rightPath = Path().apply {
                moveTo(w * 0.54f, cy + linkRadius)
                lineTo(w * 0.72f, cy + linkRadius)
                cubicTo(
                    w * 0.90f,
                    cy + linkRadius,
                    w * 0.90f,
                    cy - linkRadius,
                    w * 0.72f,
                    cy - linkRadius,
                )
                lineTo(w * 0.48f, cy - linkRadius)
            }
            drawPath(
                path = rightPath,
                brush = brush,
                style = Stroke(
                    width = strokeWidth,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
    }
}

/**
 * Renders conversation & peer avatars matching all screens in the Cipher design:
 * - `#mesh` (`Mesh Group`): Vibrant purple circle with 3-person group icon
 * - `#cipher-dev` (`Cipher Dev`): Slate-indigo circle with `</>` code icon
 * - `#nearby-chat` (`Nearby Chat`): Deep purple circle with `((p))` radio broadcast icon
 * - `#field-team` (`Field Team`): Deep purple circle with shield icon
 * - `Rick`: Realistic portrait illustration avatar
 * - Other peers (`anon#8c9`, `@nedu`, etc.): Tinted circular avatar with clean person silhouette
 */
@Composable
fun PeerAvatar(
    nickname: String,
    peerIdHex: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    conversationKey: String = peerIdHex,
    forcePersonSilhouette: Boolean = false,
) {
    val keyLower = conversationKey.lowercase()
    val nameLower = nickname.lowercase()

    if (!forcePersonSilhouette && (keyLower.startsWith("#") || nameLower == "mesh group" || nameLower == "cipher dev" || nameLower == "nearby chat" || nameLower == "field team")) {
        val (bgBrush, borderCol, iconVec) = when {
            keyLower == "#mesh" || nameLower == "mesh group" -> Triple(
                Brush.linearGradient(listOf(Color(0xFF6938EF), Color(0xFF4F29B8))),
                Color(0xFF8B5CF6),
                Icons.Filled.Groups,
            )
            keyLower == "#cipher-dev" || nameLower == "cipher dev" -> Triple(
                Brush.linearGradient(listOf(Color(0xFF3E4259), Color(0xFF2B2E40))),
                Color(0xFF565B7A),
                Icons.Filled.Code,
            )
            keyLower == "#nearby-chat" || nameLower == "nearby chat" -> Triple(
                Brush.linearGradient(listOf(Color(0xFF4B2B7F), Color(0xFF311B54))),
                Color(0xFF7C4DFF),
                Icons.Filled.Podcasts,
            )
            keyLower == "#field-team" || nameLower == "field team" -> Triple(
                Brush.linearGradient(listOf(Color(0xFF4A2574), Color(0xFF2D164B))),
                Color(0xFF7C4DFF),
                Icons.Filled.Shield,
            )
            else -> Triple(
                Brush.linearGradient(listOf(Color(0xFF362F59), Color(0xFF231F3D))),
                Color(0xFF5B4E96),
                Icons.Filled.Groups,
            )
        }

        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(bgBrush)
                .border(1.dp, borderCol.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = iconVec,
                contentDescription = nickname,
                tint = Color.White,
                modifier = Modifier.size((size.value * 0.50f).dp),
            )
        }
        return
    }

    // Rick's portrait avatar in Chats list and Chat Thread header
    if (!forcePersonSilhouette && (keyLower == MeshStore.RICK_PEER_KEY || nameLower == "rick")) {
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val w = this.size.width
                val h = this.size.height

                // Soft outdoor green-meadow & sky backdrop matching Rick's photo
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFCEE8D5),
                            Color(0xFF7CB686),
                            Color(0xFF437A50),
                        ),
                    ),
                )

                // Teal-green jacket / shoulders
                val shoulders = Path().apply {
                    moveTo(w * 0.12f, h)
                    cubicTo(w * 0.18f, h * 0.66f, w * 0.82f, h * 0.66f, w * 0.88f, h)
                    close()
                }
                drawPath(
                    path = shoulders,
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF148573), Color(0xFF0C594D)),
                    ),
                )

                // Inner warm shirt v-neck
                val shirt = Path().apply {
                    moveTo(w * 0.37f, h * 0.72f)
                    lineTo(w * 0.50f, h)
                    lineTo(w * 0.63f, h * 0.72f)
                    close()
                }
                drawPath(path = shirt, color = Color(0xFFD98B6C))

                // Neck
                drawRoundRect(
                    color = Color(0xFFD49573),
                    topLeft = Offset(w * 0.42f, h * 0.54f),
                    size = Size(w * 0.16f, h * 0.20f),
                    cornerRadius = CornerRadius(8f, 8f),
                )

                // Head / face
                drawOval(
                    color = Color(0xFFE0A382),
                    topLeft = Offset(w * 0.31f, h * 0.20f),
                    size = Size(w * 0.38f, h * 0.43f),
                )

                // Dark brown hair cap
                val hair = Path().apply {
                    moveTo(w * 0.30f, h * 0.36f)
                    cubicTo(w * 0.30f, h * 0.13f, w * 0.70f, h * 0.13f, w * 0.70f, h * 0.36f)
                    cubicTo(w * 0.62f, h * 0.25f, w * 0.38f, h * 0.25f, w * 0.30f, h * 0.36f)
                    close()
                }
                drawPath(path = hair, color = Color(0xFF2B1D16))
            }
        }
        return
    }

    // Standard tinted person silhouette avatar (used in People (7), Security Verification, and general peers)
    val seed = if (forcePersonSilhouette && nameLower == "rick") "8c9" else (nickname.ifBlank { peerIdHex })
    val pal = PeerColors.paletteFor(seed)

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(pal.circleBgStart, pal.circleBgEnd),
                ),
            )
            .border(1.2.dp, pal.border.copy(alpha = 0.75f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Person,
            contentDescription = nickname,
            tint = pal.iconTint,
            modifier = Modifier.size((size.value * 0.56f).dp),
        )
    }
}

/**
 * 4-bar green signal strength meter icon matching People (7) and Mesh Network screens.
 */
@Composable
fun SignalBarsIcon(
    rssi: Int? = -60,
    hops: Int = 1,
    modifier: Modifier = Modifier,
    width: Dp = 20.dp,
    height: Dp = 15.dp,
) {
    val palette = LocalCypherPalette.current
    val activeBars = when {
        rssi != null && rssi >= -62 && hops <= 1 -> 4
        rssi != null && rssi >= -74 && hops <= 2 -> 4
        hops <= 2 -> 4
        hops == 3 -> 3
        else -> 3
    }

    Canvas(modifier = modifier.size(width = width, height = height)) {
        val barCount = 4
        val spacing = size.width * 0.10f
        val barWidth = (size.width - spacing * (barCount - 1)) / barCount
        for (i in 0 until barCount) {
            val fraction = 0.34f + (i * 0.22f)
            val bh = size.height * fraction
            val x = i * (barWidth + spacing)
            val y = size.height - bh
            drawRoundRect(
                color = if (i < activeBars) palette.encryptedGreen else Color(0xFF2D334A),
                topLeft = Offset(x, y),
                size = Size(barWidth, bh),
                cornerRadius = CornerRadius(3f, 3f),
            )
        }
    }
}

/**
 * Connected green pill badge shown under "Mesh Network" title.
 */
@Composable
fun ConnectedStatusPill(
    isConnected: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val bg = if (isConnected) Color(0xFF15382B) else Color(0xFF2C2618)
    val border = if (isConnected) Color(0xFF22C55E).copy(alpha = 0.45f) else Color(0xFFF59E0B).copy(alpha = 0.45f)
    val fg = if (isConnected) Color(0xFF6EE7B7) else Color(0xFFFCD34D)

    Surface(
        color = bg,
        shape = RoundedCornerShape(50),
        modifier = modifier.border(1.dp, border, RoundedCornerShape(50)),
    ) {
        Text(
            text = if (isConnected) "Connected" else "Scanning",
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.5.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun UnreadCountBadge(
    count: Int,
    isMuted: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (count <= 0) return
    val palette = LocalCypherPalette.current

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 24.dp, minHeight = 24.dp)
            .clip(CircleShape)
            .background(
                color = if (isMuted) palette.surface3 else Color(0xFF7C4DFF),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            fontSize = 12.sp,
        )
    }
}

@Composable
fun DeliveryReceiptIcon(
    state: DeliveryState,
    tintOverride: Color? = null,
    modifier: Modifier = Modifier,
) {
    val palette = LocalCypherPalette.current
    val (icon, tint, label) = when (state) {
        DeliveryState.SENDING -> Triple(
            Icons.Filled.Schedule,
            tintOverride ?: Color.White.copy(alpha = 0.75f),
            "Sending",
        )
        DeliveryState.SENT -> Triple(
            Icons.Filled.Check,
            tintOverride ?: Color.White.copy(alpha = 0.85f),
            "Sent",
        )
        DeliveryState.DELIVERED,
        DeliveryState.READ,
        -> Triple(
            Icons.Filled.DoneAll,
            tintOverride ?: Color.White.copy(alpha = 0.92f),
            "Delivered",
        )
        DeliveryState.FAILED -> Triple(
            Icons.Filled.Schedule,
            palette.dangerRed,
            "Failed",
        )
    }
    Icon(
        imageVector = icon,
        contentDescription = label,
        tint = tint,
        modifier = modifier.size(15.dp),
    )
}
