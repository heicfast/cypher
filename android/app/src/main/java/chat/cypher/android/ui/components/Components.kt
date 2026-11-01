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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.cypher.android.core.Geohash
import chat.cypher.android.core.PeerIdentity
import chat.cypher.android.ui.theme.CypherTheme
import java.util.Locale

/**
 * Draws the signature Cipher interlocking chain-link emblem in a purple-to-violet gradient.
 */
@Composable
fun CipherChainLogoIcon(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    tintOverride: Color? = null,
) {
    val palette = CypherTheme.colors
    val brush = if (tintOverride != null) {
        Brush.linearGradient(listOf(tintOverride, tintOverride))
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFFA77BFF),
                palette.accent,
                Color(0xFF6938EF),
            ),
        )
    }

    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val strokeWidth = w * 0.145f
        val linkW = w * 0.50f
        val linkH = h * 0.30f
        val corner = linkH * 0.5f

        rotate(degrees = -34f, pivot = Offset(w * 0.5f, h * 0.5f)) {
            drawRoundRect(
                brush = brush,
                topLeft = Offset(w * 0.14f, h * 0.35f),
                size = Size(linkW, linkH),
                cornerRadius = CornerRadius(corner, corner),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
            drawRoundRect(
                brush = brush,
                topLeft = Offset(w * 0.36f, h * 0.35f),
                size = Size(linkW, linkH),
                cornerRadius = CornerRadius(corner, corner),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * Rounded-square app badge containing the Cipher chain emblem.
 */
@Composable
fun CipherAppBadge(
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    isDanger: Boolean = false,
) {
    val palette = CypherTheme.colors
    val corner = size * 0.27f
    val bgBrush = if (isDanger) {
        Brush.linearGradient(
            listOf(Color(0xFF421622), Color(0xFF230D15)),
        )
    } else {
        Brush.linearGradient(
            listOf(Color(0xFF392675), Color(0xFF1E1745)),
        )
    }
    val borderColor = if (isDanger) {
        palette.danger.copy(alpha = 0.45f)
    } else {
        palette.accentLight.copy(alpha = 0.35f)
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(bgBrush)
            .border(1.dp, borderColor, RoundedCornerShape(corner)),
        contentAlignment = Alignment.Center,
    ) {
        CipherChainLogoIcon(
            size = size * 0.62f,
            tintOverride = if (isDanger) palette.danger else null,
        )
    }
}

/**
 * 4-bar cellular/mesh signal strength indicator.
 */
@Composable
fun SignalBarsIndicator(
    rssi: Int?,
    hops: Int = 1,
    modifier: Modifier = Modifier,
    barWidth: Dp = 3.dp,
    maxBarHeight: Dp = 14.dp,
) {
    val palette = CypherTheme.colors
    val activeBars = when {
        rssi != null && rssi >= -62 -> 4
        rssi != null && rssi >= -74 -> 3
        rssi != null && rssi >= -85 -> 2
        rssi != null -> 1
        hops <= 1 -> 4
        hops == 2 -> 3
        hops == 3 -> 2
        else -> 1
    }
    val activeColor = when (activeBars) {
        4, 3 -> palette.signalStrong
        2 -> palette.warning
        else -> palette.danger
    }

    Row(
        modifier = modifier.height(maxBarHeight),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (i in 1..4) {
            val fraction = when (i) {
                1 -> 0.36f
                2 -> 0.56f
                3 -> 0.78f
                else -> 1.0f
            }
            Box(
                modifier = Modifier
                    .width(barWidth)
                    .height(maxBarHeight * fraction)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(
                        if (i <= activeBars) activeColor else palette.borderSubtle.copy(alpha = 0.7f),
                    ),
            )
        }
    }
}

/**
 * Deterministic peer/channel avatar for real mesh peers and channels.
 */
@Composable
fun PeerAvatar(
    peerKeyHex: String,
    nickname: String,
    size: Dp = 44.dp,
    connected: Boolean = false,
    sessionEstablished: Boolean = false,
    isChannel: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val palette = CypherTheme.colors
    val accent = if (isChannel) {
        when (peerKeyHex.lowercase(Locale.US)) {
            "#mesh" -> palette.accent
            else -> palette.accentLight
        }
    } else {
        PeerIdentity.colorForPeer(peerKeyHex.ifEmpty { nickname }, isDark = true)
    }

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        if (isChannel) {
            val channelKey = peerKeyHex.lowercase(Locale.US)
            val isMeshPrimary = channelKey == "#mesh"
            val rawTag = channelKey.removePrefix("#")
            val isGeohashChannel = !isMeshPrimary && Geohash.isValid(rawTag)
            val isPasswordProtected = !isMeshPrimary &&
                chat.cypher.android.core.MeshStore.isChannelPasswordProtected(channelKey)
            val bgBrush = if (isMeshPrimary) {
                Brush.linearGradient(listOf(Color(0xFF4F33A8), Color(0xFF2D1E6B)))
            } else {
                Brush.linearGradient(listOf(Color(0xFF23253A), Color(0xFF181A2A)))
            }
            val borderCol = if (isMeshPrimary) {
                palette.accentLight.copy(alpha = 0.45f)
            } else {
                palette.borderSubtle
            }
            val iconVec = when {
                isMeshPrimary -> Icons.Rounded.Groups
                isPasswordProtected -> Icons.Rounded.Security
                isGeohashChannel -> Icons.Rounded.Public
                channelKey.contains("dev") -> Icons.Rounded.Code
                channelKey.contains("radio") || channelKey.contains("freq") -> Icons.Rounded.Podcasts
                else -> Icons.Rounded.Tag
            }
            val iconTint = if (isMeshPrimary) Color.White else palette.accentLight

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(bgBrush)
                    .border(1.dp, borderCol, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = iconVec,
                    contentDescription = nickname,
                    tint = iconTint,
                    modifier = Modifier.size(size * 0.48f),
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                accent.copy(alpha = 0.28f),
                                Color(0xFF1B1E32),
                            ),
                        ),
                    )
                    .border(
                        width = 1.2.dp,
                        color = accent.copy(alpha = 0.48f),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Person,
                    contentDescription = nickname,
                    tint = accent,
                    modifier = Modifier.size(size * 0.54f),
                )
            }
        }

        if (connected) {
            val dotSize = (size.value * 0.25f).coerceIn(10f, 14f).dp
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(palette.bgPrimary)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(if (sessionEstablished) palette.success else palette.accentLight),
            )
        }
    }
}

@Composable
fun EncryptionBadge(
    established: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = CypherTheme.colors
    val fg = if (established) palette.success else palette.textSecondary
    val bg = if (established) palette.success.copy(alpha = 0.14f) else palette.surfaceElevated

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(
                width = 1.dp,
                color = fg.copy(alpha = 0.28f),
                shape = RoundedCornerShape(50),
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = if (established) Icons.Rounded.Lock else Icons.Rounded.BluetoothSearching,
            contentDescription = if (established) "Encrypted" else "Mesh Broadcast",
            tint = fg,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = if (established) "Encrypted" else "BLE Mesh",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp,
            ),
            color = fg,
        )
    }
}

@Composable
fun RadarPulseGraphic(
    modifier: Modifier = Modifier,
    size: Dp = 140.dp,
) {
    val palette = CypherTheme.colors
    val transition = rememberInfiniteTransition(label = "radar_pulse")
    val pulse1 by transition.animateFloat(
        initialValue = 0.18f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulse1",
    )
    val pulse2 by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulse2",
    )

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val maxRadius = this.size.minDimension / 2f
            drawCircle(
                color = palette.accent.copy(alpha = (1f - pulse1) * 0.40f),
                radius = maxRadius * pulse1,
                style = Stroke(width = 2.dp.toPx()),
            )
            drawCircle(
                color = palette.accentLight.copy(alpha = (1f - pulse2) * 0.30f),
                radius = maxRadius * pulse2,
                style = Stroke(width = 1.5.dp.toPx()),
            )
            drawCircle(
                color = palette.accentSubtle,
                radius = maxRadius * 0.28f,
            )
        }
        CipherChainLogoIcon(size = size * 0.26f)
    }
}
