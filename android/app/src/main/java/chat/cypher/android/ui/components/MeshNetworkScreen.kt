package chat.cypher.android.ui.components

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.cypher.android.core.MeshController
import chat.cypher.android.core.MeshGraphService
import chat.cypher.android.core.MeshStore
import chat.cypher.android.core.PeerEntry
import chat.cypher.android.ui.theme.CypherTheme
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Live BLE Mesh Network Topology Screen powered by real peers and MeshGraphService edges.
 */
@Composable
fun MeshNetworkScreen(
    onBack: () -> Unit,
    onSelectPeer: (String) -> Unit = {},
) {
    BackHandler(onBack = onBack)

    val palette = CypherTheme.colors
    val peers by MeshStore.peers.collectAsState()
    val maxTtlHops by MeshStore.maxTtlHops.collectAsState()
    val graphSnapshot by MeshGraphService.graphState.collectAsState()

    val nearbyCount = peers.size
    val maxObservedHops = if (peers.isNotEmpty()) {
        peers.maxOf { it.hops }.coerceIn(1, 7)
    } else {
        0
    }
    val bestRssi = peers.mapNotNull { it.rssi }.maxOrNull()
    val signalQualityLabel = when {
        peers.isEmpty() -> "Scanning"
        bestRssi == null -> "Good"
        bestRssi >= -65 -> "Strong"
        bestRssi >= -78 -> "Good"
        else -> "Fair"
    }
    val isConnected = peers.any { it.connected }
    val statusBadgeLabel = when {
        isConnected -> "Connected"
        peers.isNotEmpty() -> "Discovered"
        else -> "Scanning BLE"
    }
    val statusBadgeColor = if (isConnected || peers.isNotEmpty()) {
        palette.success
    } else {
        palette.accentLight
    }

    Scaffold(
        containerColor = palette.bgPrimary,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF131524), palette.bgPrimary),
                    ),
                )
                .padding(innerPadding)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top Bar: Back arrow + Centered "Mesh Network" title + Refresh button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(40.dp)
                        .testTag("mesh_network_back_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = palette.textPrimary,
                    )
                }

                Text(
                    text = "Mesh Network",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp,
                    ),
                    color = palette.textPrimary,
                    modifier = Modifier.align(Alignment.Center),
                )

                IconButton(
                    onClick = {
                        MeshController.announceNow()
                        MeshController.pingAll()
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(40.dp)
                        .testTag("mesh_network_refresh_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Broadcast presence and ping mesh",
                        tint = palette.accentLight,
                    )
                }
            }

            // Status Pill
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(statusBadgeColor.copy(alpha = 0.16f))
                    .border(1.dp, statusBadgeColor.copy(alpha = 0.40f), RoundedCornerShape(50))
                    .clickable {
                        MeshController.announceNow()
                        MeshController.pingAll()
                    }
                    .padding(horizontal = 18.dp, vertical = 6.dp)
                    .testTag("mesh_connected_pill"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = statusBadgeLabel,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    ),
                    color = statusBadgeColor,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Radial Mesh Topology Graph Area
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                val density = LocalDensity.current
                val minSideDp = minOf(maxWidth, maxHeight)
                val innerOrbitRadiusDp = minSideDp * 0.25f
                val outerOrbitRadiusDp = minSideDp * 0.38f
                val innerOrbitPx = with(density) { innerOrbitRadiusDp.toPx() }
                val outerOrbitPx = with(density) { outerOrbitRadiusDp.toPx() }

                val transition = rememberInfiniteTransition(label = "mesh_glow")
                val glowScale by transition.animateFloat(
                    initialValue = 0.92f,
                    targetValue = 1.08f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2400, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "glow_scale",
                )

                val visiblePeers: List<PeerEntry> = peers.take(12)
                val peerPositions = visiblePeers.mapIndexed { idx, peer ->
                    val angleDeg = -90.0 + (360.0 / visiblePeers.size.coerceAtLeast(1)) * idx
                    val rad = Math.toRadians(angleDeg)
                    val radiusPx = if (peer.hops <= 1) innerOrbitPx else outerOrbitPx
                    val radiusDp = if (peer.hops <= 1) innerOrbitRadiusDp else outerOrbitRadiusDp
                    Triple(peer, rad, radiusPx to radiusDp)
                }
                val posByPeerId = peerPositions.associate { (peer, rad, radii) ->
                    peer.peerKeyHex to Triple(rad, radii.first, radii.second)
                }

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)

                    // Outer ambient radial glow
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                palette.accent.copy(alpha = 0.26f * glowScale),
                                Color.Transparent,
                            ),
                            center = center,
                            radius = outerOrbitPx * 1.25f,
                        ),
                        radius = outerOrbitPx * 1.25f,
                        center = center,
                    )

                    // 3 Concentric orbit rings (1-hop, 2-hop, multi-hop)
                    listOf(0.45f, 0.72f, 1.0f).forEach { factor ->
                        drawCircle(
                            color = palette.accentLight.copy(alpha = 0.14f),
                            radius = outerOrbitPx * factor,
                            center = center,
                            style = Stroke(width = 1.2.dp.toPx()),
                        )
                    }

                    // Inter-peer relay edges from MeshGraphService
                    graphSnapshot.edges.forEach { edge ->
                        val pA = posByPeerId[edge.a]
                        val pB = posByPeerId[edge.b]
                        if (pA != null && pB != null) {
                            val ptA = Offset(
                                x = center.x + (cos(pA.first) * pA.second).toFloat(),
                                y = center.y + (sin(pA.first) * pA.second).toFloat(),
                            )
                            val ptB = Offset(
                                x = center.x + (cos(pB.first) * pB.second).toFloat(),
                                y = center.y + (sin(pB.first) * pB.second).toFloat(),
                            )
                            drawLine(
                                color = palette.accentLight.copy(alpha = if (edge.isConfirmed) 0.45f else 0.24f),
                                start = ptA,
                                end = ptB,
                                strokeWidth = 1.5.dp.toPx(),
                                pathEffect = if (edge.isConfirmed) {
                                    null
                                } else {
                                    PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                                },
                            )
                        }
                    }

                    // Spokes from center node to discovered peers
                    peerPositions.forEach { (peer, rad, radii) ->
                        val rPx = radii.first
                        val nodeCenter = Offset(
                            x = center.x + (cos(rad) * rPx).toFloat(),
                            y = center.y + (sin(rad) * rPx).toFloat(),
                        )
                        drawLine(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    palette.accentLight.copy(alpha = 0.75f),
                                    palette.accent.copy(alpha = 0.30f),
                                ),
                                start = center,
                                end = nodeCenter,
                            ),
                            start = center,
                            end = nodeCenter,
                            strokeWidth = if (peer.hops <= 1) 2.dp.toPx() else 1.4.dp.toPx(),
                            pathEffect = if (peer.hops <= 1) {
                                null
                            } else {
                                PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                            },
                        )
                    }
                }

                // Discovered peer nodes
                peerPositions.forEach { (peer, rad, radii) ->
                    val rDp = radii.second
                    val offsetX = with(density) { (rDp * cos(rad).toFloat()).toPx().roundToInt() }
                    val offsetY = with(density) { (rDp * sin(rad).toFloat()).toPx().roundToInt() }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .offset { IntOffset(offsetX, offsetY) }
                            .clickable { onSelectPeer(peer.peerKeyHex) }
                            .testTag("mesh_peer_node_${peer.peerKeyHex}"),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .shadow(
                                    elevation = 10.dp,
                                    shape = CircleShape,
                                    ambientColor = palette.accent,
                                    spotColor = palette.accent,
                                )
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            Color(0xFF4F3799),
                                            Color(0xFF281E54),
                                        ),
                                    ),
                                )
                                .border(
                                    width = 1.5.dp,
                                    color = if (peer.sessionEstablished) {
                                        palette.success.copy(alpha = 0.8f)
                                    } else {
                                        palette.accentLight.copy(alpha = 0.65f)
                                    },
                                    shape = CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Person,
                                contentDescription = peer.nickname,
                                tint = Color(0xFFD0BCFF),
                                modifier = Modifier.size(23.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = peer.nickname,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                            color = palette.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Central glowing "Cipher" Local Hub Node
                Box(
                    modifier = Modifier
                        .size(102.dp)
                        .shadow(
                            elevation = 24.dp,
                            shape = CircleShape,
                            ambientColor = palette.accent,
                            spotColor = palette.accent,
                        )
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF472BA8),
                                    Color(0xFF231757),
                                    Color(0xFF171238),
                                ),
                            ),
                        )
                        .border(
                            width = 2.dp,
                            brush = Brush.linearGradient(
                                listOf(
                                    Color(0xFFB794FF),
                                    palette.accent,
                                ),
                            ),
                            shape = CircleShape,
                        )
                        .clickable {
                            MeshController.announceNow()
                            MeshController.pingAll()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CipherChainLogoIcon(size = 34.dp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Cipher",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.5.sp,
                            ),
                            color = Color.White,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 3 Bottom Metric Cards: [ Peers Nearby ] [ Hops (Max 7) ] [ Signal ]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 26.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Card 1: Peers Nearby
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(86.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(palette.surface)
                        .border(1.dp, palette.borderSubtle, RoundedCornerShape(18.dp))
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = nearbyCount.toString(),
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 23.sp,
                            ),
                            color = palette.textPrimary,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Peers Nearby",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 11.5.sp,
                            ),
                            color = palette.textSecondary,
                        )
                    }
                }

                // Card 2: Hops (Max 7)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(86.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(palette.surface)
                        .border(1.dp, palette.borderSubtle, RoundedCornerShape(18.dp))
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = maxObservedHops.toString(),
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 23.sp,
                            ),
                            color = palette.textPrimary,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Hops (Max $maxTtlHops)",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 11.5.sp,
                            ),
                            color = palette.textSecondary,
                        )
                    }
                }

                // Card 3: Signal Quality
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(86.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(palette.surface)
                        .border(1.dp, palette.borderSubtle, RoundedCornerShape(18.dp))
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        SignalBarsIndicator(
                            rssi = bestRssi,
                            hops = if (peers.isNotEmpty()) 1 else 4,
                            barWidth = 3.5.dp,
                            maxBarHeight = 16.dp,
                        )
                        Spacer(modifier = Modifier.height(5.dp))
                        Text(
                            text = signalQualityLabel,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp,
                            ),
                            color = palette.textPrimary,
                        )
                        Text(
                            text = "Signal",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 11.sp,
                            ),
                            color = palette.textSecondary,
                        )
                    }
                }
            }
        }
    }
}
