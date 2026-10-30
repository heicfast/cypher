package chat.cypher.android.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SyncLock
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.cypher.android.core.DeliveryState
import chat.cypher.android.ui.theme.CypherMonoFontFamily
import chat.cypher.android.ui.theme.LocalCypherPalette
import chat.cypher.android.ui.theme.PeerColors
import kotlin.math.abs

/**
 * Artistic editorial portrait avatar matching the dreamy multi-toned portrait spheres
 * in the reference design, with optional story ring, online emerald dot, or corner badge.
 */
@Composable
fun PeerAvatar(
    nickname: String,
    peerIdHex: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    isBroadcastChannel: Boolean = false,
    isOnline: Boolean = false,
    isVerified: Boolean = false,
    hasGlowRing: Boolean = false,
    cornerBadgeIcon: ImageVector? = null,
) {
    val palette = LocalCypherPalette.current
    val seed = if (isBroadcastChannel) "lounge_broadcast" else (peerIdHex.ifBlank { nickname })
    val art = PeerColors.avatarPaletteFor(seed)
    val poseVariant = remember(seed) { abs(seed.hashCode()) % 4 }

    Box(modifier = modifier.size(size)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .then(
                    if (hasGlowRing) {
                        Modifier
                            .border(
                                width = 2.2.dp,
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Color(0xFF6366F1),
                                        Color(0xFF38BDF8),
                                        Color(0xFFF472B6),
                                    ),
                                ),
                                shape = CircleShape,
                            )
                            .padding(3.dp)
                    } else {
                        Modifier.border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.18f),
                            shape = CircleShape,
                        )
                    },
                )
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val w = this.size.width
                val h = this.size.height

                // 1. Lush diagonal background wash
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(art.primary, art.secondary, art.silhouette),
                        start = Offset(0f, 0f),
                        end = Offset(w, h),
                    ),
                )

                // 2. Dreamy radial light flare (top-left or top-right based on variant)
                val flareCenter = when (poseVariant) {
                    0 -> Offset(w * 0.28f, h * 0.26f)
                    1 -> Offset(w * 0.72f, h * 0.24f)
                    2 -> Offset(w * 0.50f, h * 0.20f)
                    else -> Offset(w * 0.32f, h * 0.65f)
                }
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            art.highlight.copy(alpha = 0.82f),
                            art.secondary.copy(alpha = 0.35f),
                            Color.Transparent,
                        ),
                        center = flareCenter,
                        radius = w * 0.68f,
                    ),
                    radius = w * 0.68f,
                    center = flareCenter,
                )

                // 3. Flowing silk aurora wave across the background
                val wavePath = Path().apply {
                    moveTo(0f, h * 0.65f)
                    cubicTo(
                        w * 0.30f,
                        h * (0.38f + poseVariant * 0.04f),
                        w * 0.68f,
                        h * 0.82f,
                        w,
                        h * 0.48f,
                    )
                    lineTo(w, h)
                    lineTo(0f, h)
                    close()
                }
                drawPath(
                    path = wavePath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            art.highlight.copy(alpha = 0.40f),
                            art.primary.copy(alpha = 0.55f),
                        ),
                    ),
                )

                // 4. Stylized editorial portrait silhouette (head, neck, shoulders & rim light)
                val headCenterX = w * (0.48f + (poseVariant - 1.5f) * 0.03f)
                val headCenterY = h * 0.39f
                val headRadius = w * 0.19f

                // Soft rim-light halo behind head
                drawCircle(
                    color = art.highlight.copy(alpha = 0.55f),
                    radius = headRadius * 1.16f,
                    center = Offset(headCenterX - w * 0.02f, headCenterY - h * 0.02f),
                )

                // Shoulders / bust curve
                val bustPath = Path().apply {
                    moveTo(w * 0.14f, h * 1.02f)
                    cubicTo(
                        w * 0.20f,
                        h * 0.66f,
                        w * 0.80f,
                        h * 0.66f,
                        w * 0.86f,
                        h * 1.02f,
                    )
                    close()
                }
                drawPath(
                    path = bustPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            art.silhouette.copy(alpha = 0.86f),
                            art.silhouette.copy(alpha = 0.96f),
                        ),
                    ),
                )

                // Portrait head
                drawCircle(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            art.highlight.copy(alpha = 0.90f),
                            art.secondary.copy(alpha = 0.92f),
                            art.silhouette.copy(alpha = 0.95f),
                        ),
                        start = Offset(headCenterX - headRadius, headCenterY - headRadius),
                        end = Offset(headCenterX + headRadius, headCenterY + headRadius),
                    ),
                    radius = headRadius,
                    center = Offset(headCenterX, headCenterY),
                )

                // Expressive flowing hair / veil stroke (matches the dreamy windswept look)
                val hairPath = Path().apply {
                    moveTo(headCenterX - headRadius * 1.1f, headCenterY - headRadius * 0.2f)
                    cubicTo(
                        headCenterX - headRadius * 0.2f,
                        headCenterY - headRadius * 1.45f,
                        headCenterX + headRadius * 1.5f,
                        headCenterY - headRadius * 0.5f,
                        headCenterX + headRadius * 1.25f,
                        headCenterY + headRadius * 1.15f,
                    )
                }
                drawPath(
                    path = hairPath,
                    color = art.highlight.copy(alpha = 0.48f),
                    style = Stroke(width = w * 0.06f),
                )
            }
        }

        // Corner status badge (for Lounge grid items or online dot)
        if (cornerBadgeIcon != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size((size.value * 0.34f).coerceIn(18f, 24f).dp)
                    .clip(CircleShape)
                    .background(Color(0xFF141C2E))
                    .border(1.5.dp, palette.canvas, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = cornerBadgeIcon,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size((size.value * 0.18f).coerceIn(10f, 13f).dp),
                )
            }
        } else if (isOnline) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size((size.value * 0.27f).coerceIn(11f, 15f).dp)
                    .clip(CircleShape)
                    .background(palette.canvas)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(palette.encryptedGreen),
            )
        } else if (isVerified) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size((size.value * 0.30f).coerceIn(12f, 16f).dp)
                    .clip(CircleShape)
                    .background(palette.canvas)
                    .padding(1.5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.VerifiedUser,
                    contentDescription = "Verified peer",
                    tint = palette.encryptedGreen,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
    }
}

/**
 * Circular frosted-glass icon button used in top headers and floating docks.
 */
@Composable
fun FrostedCircleIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    iconSize: Dp = 20.dp,
    containerColor: Color = Color(0xFF172A40).copy(alpha = 0.78f),
    contentColor: Color = Color.White,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.91f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "frosted_btn_scale",
    )

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .scale(scale)
            .size(size)
            .clip(CircleShape)
            .background(containerColor)
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * Top aurora glow container with a smooth curved arc at the bottom,
 * matching the luminous teal-blue header atmosphere in the reference design.
 */
@Composable
fun AuroraCurvedHeaderBox(
    modifier: Modifier = Modifier,
    curveDepthDp: Dp = 26.dp,
    invertCurveDome: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = LocalCypherPalette.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val w = size.width
                val h = size.height
                val curvePx = curveDepthDp.toPx()

                // Radial cyan-blue aurora glow from top center
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            palette.auroraTop.copy(alpha = if (palette.isDark) 0.85f else 0.55f),
                            palette.auroraMid.copy(alpha = if (palette.isDark) 0.55f else 0.30f),
                            palette.canvas,
                        ),
                        center = Offset(w * 0.52f, -h * 0.08f),
                        radius = maxOf(w * 0.95f, h * 1.15f),
                    ),
                )

                // Subtle curved arc separating header from body
                val arcPath = Path().apply {
                    if (invertCurveDome) {
                        moveTo(0f, h)
                        quadraticBezierTo(w * 0.5f, h - curvePx * 1.5f, w, h)
                    } else {
                        moveTo(0f, h - curvePx)
                        quadraticBezierTo(w * 0.5f, h + curvePx * 0.55f, w, h - curvePx)
                    }
                }
                drawPath(
                    path = arcPath,
                    color = Color.White.copy(alpha = if (palette.isDark) 0.07f else 0.15f),
                    style = Stroke(width = 1.2.dp.toPx()),
                )
            },
        content = content,
    )
}

@Composable
fun LiveMeshPulseDot(
    isActive: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
) {
    val palette = LocalCypherPalette.current
    val baseColor = if (isActive) palette.encryptedGreen else palette.warningAmber
    val transition = rememberInfiniteTransition(label = "mesh_pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = if (isActive) 1.22f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Box(
        modifier = modifier
            .size(size)
            .scale(pulse)
            .clip(CircleShape)
            .background(baseColor),
    )
}

@Composable
fun EncryptionStatusPill(
    sessionState: String,
    isVerified: Boolean,
    isBroadcast: Boolean,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val palette = LocalCypherPalette.current
    val (bgColor, fgColor, icon, label) = when {
        isBroadcast -> Quad(
            palette.accentSoft,
            palette.accentSecondary,
            Icons.Filled.Public,
            "Public Lounge",
        )
        isVerified -> Quad(
            palette.encryptedGreenSoft,
            palette.encryptedGreen,
            Icons.Filled.VerifiedUser,
            "Verified",
        )
        sessionState == "established" -> Quad(
            palette.encryptedGreenSoft,
            palette.encryptedGreen,
            Icons.Filled.Lock,
            "Encrypted",
        )
        sessionState == "handshaking" -> Quad(
            palette.warningAmberSoft,
            palette.warningAmber,
            Icons.Filled.SyncLock,
            "Connecting",
        )
        else -> Quad(
            palette.surface2,
            palette.textSecondary,
            Icons.Filled.Lock,
            "Private",
        )
    }

    val clickableMod = if (onClick != null) {
        Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
    } else {
        Modifier
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(50),
        modifier = modifier
            .then(clickableMod)
            .border(0.8.dp, fgColor.copy(alpha = 0.35f), RoundedCornerShape(50)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = fgColor,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = fgColor,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
fun SignalBarsBadge(
    rssi: Int?,
    isDirectlyConnected: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = LocalCypherPalette.current
    val bars = when {
        rssi == null && isDirectlyConnected -> 3
        rssi == null -> 0
        rssi >= -60 -> 4
        rssi >= -72 -> 3
        rssi >= -84 -> 2
        else -> 1
    }
    val activeColor = when {
        bars >= 3 -> palette.encryptedGreen
        bars == 2 -> palette.accentSecondary
        bars == 1 -> palette.warningAmber
        else -> palette.textMuted
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Canvas(modifier = Modifier.size(width = 16.dp, height = 12.dp)) {
            val barCount = 4
            val barWidth = size.width / (barCount * 2 - 1)
            for (i in 0 until barCount) {
                val fraction = (i + 1) / barCount.toFloat()
                val bh = size.height * fraction
                val x = i * barWidth * 2
                val y = size.height - bh
                drawRoundRect(
                    color = if (i < bars) activeColor else palette.borderStrong.copy(alpha = 0.45f),
                    topLeft = Offset(x, y),
                    size = Size(barWidth, bh),
                    cornerRadius = CornerRadius(2f, 2f),
                )
            }
        }
        if (rssi != null) {
            Text(
                text = "${rssi}dBm",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = CypherMonoFontFamily),
                color = palette.textSecondary,
            )
        }
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
            .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
            .clip(CircleShape)
            .background(
                brush = if (isMuted) {
                    Brush.horizontalGradient(listOf(palette.surface3, palette.surface3))
                } else {
                    palette.primaryPillBrush
                },
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            fontSize = 11.sp,
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
            tintOverride ?: palette.textMuted,
            "Sending",
        )
        DeliveryState.SENT -> Triple(
            Icons.Filled.Check,
            tintOverride ?: palette.textSecondary,
            "Sent",
        )
        DeliveryState.DELIVERED -> Triple(
            Icons.Filled.DoneAll,
            tintOverride ?: palette.accentSecondary,
            "Delivered",
        )
        DeliveryState.READ -> Triple(
            Icons.Filled.DoneAll,
            palette.encryptedGreen,
            "Read",
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
        modifier = modifier.size(14.dp),
    )
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
