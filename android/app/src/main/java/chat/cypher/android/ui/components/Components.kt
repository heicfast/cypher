package chat.cypher.android.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.cypher.android.core.DeliveryState
import chat.cypher.android.ui.theme.CypherDisplayFontFamily
import chat.cypher.android.ui.theme.CypherMonoFontFamily
import chat.cypher.android.ui.theme.LocalCypherPalette
import chat.cypher.android.ui.theme.PeerColors

@Composable
fun PeerAvatar(
    nickname: String,
    peerIdHex: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    isBroadcastChannel: Boolean = false,
    isOnline: Boolean = false,
    isVerified: Boolean = false,
) {
    val palette = LocalCypherPalette.current
    val peerAccent = if (isBroadcastChannel) {
        palette.accent
    } else {
        PeerColors.forPeer(peerIdHex, palette.isDark)
    }

    val initials = if (isBroadcastChannel) {
        "#"
    } else {
        val clean = nickname.trim().removePrefix("@")
        when {
            clean.length >= 2 -> clean.take(2).uppercase()
            clean.length == 1 -> clean.uppercase()
            peerIdHex.length >= 2 -> peerIdHex.take(2).uppercase()
            else -> "CY"
        }
    }

    Box(modifier = modifier.size(size)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            peerAccent.copy(alpha = if (palette.isDark) 0.24f else 0.16f),
                            peerAccent.copy(alpha = if (palette.isDark) 0.10f else 0.08f),
                        ),
                    ),
                )
                .border(
                    width = 1.25.dp,
                    color = peerAccent.copy(alpha = if (isOnline) 0.65f else 0.32f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initials,
                fontFamily = if (isBroadcastChannel) CypherMonoFontFamily else CypherDisplayFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.36f).sp,
                color = peerAccent,
            )
        }

        if (isOnline) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size((size.value * 0.28f).coerceAtLeast(11f).dp)
                    .clip(CircleShape)
                    .background(palette.surface0)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(palette.encryptedGreen),
            )
        } else if (isVerified) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size((size.value * 0.32f).coerceAtLeast(13f).dp)
                    .clip(CircleShape)
                    .background(palette.surface0)
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
            palette.accent,
            Icons.Filled.Public,
            "MESH BROADCAST",
        )
        isVerified -> Quad(
            palette.encryptedGreenSoft,
            palette.encryptedGreen,
            Icons.Filled.VerifiedUser,
            "VERIFIED · NOISE XX",
        )
        sessionState == "established" -> Quad(
            palette.encryptedGreenSoft,
            palette.encryptedGreen,
            Icons.Filled.Lock,
            "NOISE XX E2EE",
        )
        sessionState == "handshaking" -> Quad(
            palette.warningAmberSoft,
            palette.warningAmber,
            Icons.Filled.SyncLock,
            "HANDSHAKING",
        )
        else -> Quad(
            palette.surface2,
            palette.textSecondary,
            Icons.Filled.Lock,
            "AUTO-HANDSHAKE",
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
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
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
        bars == 2 -> palette.accent
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
    val bg = if (isMuted) palette.surface3 else palette.accent
    val fg = if (isMuted) palette.textSecondary else palette.accentOnPrimary

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = fg,
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
            "Sent to mesh",
        )
        DeliveryState.DELIVERED -> Triple(
            Icons.Filled.DoneAll,
            tintOverride ?: palette.textSecondary,
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
