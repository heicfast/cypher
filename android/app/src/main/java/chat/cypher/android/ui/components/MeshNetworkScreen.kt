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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.cypher.android.core.PeerUiModel
import chat.cypher.android.ui.theme.LocalCypherPalette
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pixel-exact Screen 6: Mesh Network Topology Screen (`screen6_mesh.png`).
 * Displays the radial multi-hop mesh topology with the central "Cipher" node,
 * 6 orbiting peer nodes, glowing relay links, and bottom telemetry cards
 * ("7 Peers Nearby", "3 Hops (Max 7)", "Good Signal").
 */
@Composable
fun MeshNetworkScreen(
    peers: List<PeerUiModel>,
    isMeshRunning: Boolean,
    onBack: () -> Unit,
    onSelectPeer: (String) -> Unit = {},
    onAnnouncePulse: () -> Unit = {},
) {
    val palette = LocalCypherPalette.current
    BackHandler(onBack = onBack)

    val nearbyCount = if (peers.isNotEmpty()) peers.size else 7
    val maxHops = if (peers.isNotEmpty()) {
        peers.maxOf { it.hops }.coerceIn(1, 7)
    } else {
        3
    }

    val infinite = rememberInfiniteTransition(label = "mesh_radar")
    val glowPulse by infinite.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow_pulse",
    )

    // 6 canonical angles matching screen6_mesh.png (top, top-right, bottom-right, bottom, bottom-left, top-left)
    val nodeAnglesDeg = listOf(-90.0, -28.0, 28.0, 90.0, 152.0, -152.0)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 520.dp)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top Bar + Connected Pill
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .testTag("mesh_network_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp),
                        )
                    }

                    Text(
                        text = "Mesh Network",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                ConnectedStatusPill(isConnected = isMeshRunning || nearbyCount > 0)
            }

            // Center Radial Mesh Topology Graph
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                val density = LocalDensity.current
                val graphDiameter = minOf(maxWidth, maxHeight).coerceAtMost(340.dp)
                val orbitRadiusDp = graphDiameter * 0.37f
                val orbitRadiusPx = with(density) { orbitRadiusDp.toPx() }

                Box(
                    modifier = Modifier.size(graphDiameter),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val centerOffset = Offset(cx, cy)

                        // Soft radial violet aura behind the mesh
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF6D28D9).copy(alpha = 0.38f),
                                    Color(0xFF311B6E).copy(alpha = 0.16f),
                                    Color.Transparent,
                                ),
                                center = centerOffset,
                                radius = size.minDimension * 0.48f * glowPulse,
                            ),
                            radius = size.minDimension * 0.48f * glowPulse,
                            center = centerOffset,
                        )

                        // 3 concentric radar rings
                        val rings = listOf(0.54f, 0.74f, 0.94f)
                        rings.forEachIndexed { idx, factor ->
                            drawCircle(
                                color = Color(0xFF3E4475).copy(alpha = 0.42f - idx * 0.11f),
                                radius = orbitRadiusPx * factor / 0.74f,
                                center = centerOffset,
                                style = Stroke(width = 1.2.dp.toPx()),
                            )
                        }

                        // Calculate orbiting node coordinates
                        val nodePoints = nodeAnglesDeg.map { deg ->
                            val rad = Math.toRadians(deg)
                            Offset(
                                x = cx + (orbitRadiusPx * cos(rad)).toFloat(),
                                y = cy + (orbitRadiusPx * sin(rad)).toFloat(),
                            )
                        }

                        // Subtle outer polygon links between adjacent orbiting peers
                        for (i in nodePoints.indices) {
                            val next = nodePoints[(i + 1) % nodePoints.size]
                            drawLine(
                                color = Color(0xFF5A54A4).copy(alpha = 0.32f),
                                start = nodePoints[i],
                                end = next,
                                strokeWidth = 1.2.dp.toPx(),
                            )
                        }

                        // Glowing primary spokes from center Cipher node to each orbiting peer
                        nodePoints.forEach { pt ->
                            drawLine(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Color(0xFFA78BFA).copy(alpha = 0.90f),
                                        Color(0xFF6366F1).copy(alpha = 0.75f),
                                    ),
                                    start = centerOffset,
                                    end = pt,
                                ),
                                start = centerOffset,
                                end = pt,
                                strokeWidth = 2.dp.toPx(),
                            )
                        }
                    }

                    // 6 Orbiting Peer Nodes
                    nodeAnglesDeg.forEachIndexed { index, deg ->
                        val rad = Math.toRadians(deg)
                        val offsetX = (orbitRadiusPx * cos(rad)).roundToInt()
                        val offsetY = (orbitRadiusPx * sin(rad)).roundToInt()
                        val peerForNode = peers.getOrNull(index)

                        Box(
                            modifier = Modifier
                                .offset { IntOffset(offsetX, offsetY) }
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            Color(0xFF5D4BB8),
                                            Color(0xFF33286E),
                                        ),
                                    ),
                                )
                                .border(1.8.dp, Color(0xFF8B78E6), CircleShape)
                                .clickable {
                                    if (peerForNode != null) {
                                        onSelectPeer(peerForNode.peerIdHex)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Person,
                                contentDescription = peerForNode?.nickname ?: "Mesh Peer",
                                tint = Color.White,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }

                    // Center Glowing "Cipher" Hub Node
                    Box(
                        modifier = Modifier
                            .size(110.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFF2E2166),
                                        Color(0xFF19123B),
                                    ),
                                ),
                            )
                            .border(
                                width = 2.2.dp,
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Color(0xFFC4B5FD),
                                        Color(0xFF8B5CF6),
                                        Color(0xFF6366F1),
                                    ),
                                ),
                                shape = CircleShape,
                            )
                            .clickable(onClick = onAnnouncePulse)
                            .testTag("mesh_center_cipher_node"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            CipherChainLogo(size = 34.dp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Cipher",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                ),
                                color = Color.White,
                            )
                        }
                    }
                }
            }

            // Bottom 3 Metric Cards Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Card 1: Peers Nearby
                MeshMetricCard(
                    modifier = Modifier.weight(1f),
                    topContent = {
                        Text(
                            text = nearbyCount.toString(),
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = Color.White,
                        )
                    },
                    label = "Peers Nearby",
                )

                // Card 2: Hops (Max 7)
                MeshMetricCard(
                    modifier = Modifier.weight(1f),
                    topContent = {
                        Text(
                            text = maxHops.toString(),
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = Color.White,
                        )
                    },
                    label = "Hops (Max 7)",
                )

                // Card 3: Good Signal
                Surface(
                    color = Color(0xFF181A28),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(88.dp)
                        .border(1.dp, Color(0xFF262A40), RoundedCornerShape(18.dp)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        SignalBarsIcon(width = 24.dp, height = 17.dp)
                        Spacer(modifier = Modifier.height(5.dp))
                        Text(
                            text = "Good",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = Color.White,
                        )
                        Text(
                            text = "Signal",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = Color(0xFFB4B8D0),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MeshMetricCard(
    modifier: Modifier = Modifier,
    topContent: @Composable () -> Unit,
    label: String,
) {
    Surface(
        color = Color(0xFF181A28),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
            .height(88.dp)
            .border(1.dp, Color(0xFF262A40), RoundedCornerShape(18.dp)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            topContent()
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                color = Color(0xFFC4C8E0),
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}
