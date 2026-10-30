package chat.cypher.android.ui.contacts

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.cypher.android.core.PeerIdentity
import chat.cypher.android.core.PeerUiModel
import chat.cypher.android.ui.components.EncryptionStatusPill
import chat.cypher.android.ui.components.PeerAvatar
import chat.cypher.android.ui.components.SignalBarsBadge
import chat.cypher.android.ui.theme.CypherMonoFontFamily
import chat.cypher.android.ui.theme.LocalCypherPalette
import chat.cypher.android.ui.theme.PeerColors
import kotlin.math.cos
import kotlin.math.sin

enum class PeerDirectoryFilter(val label: String) {
    ALL("All Nodes"),
    DIRECT("Direct BLE"),
    FAVORITES("Starred"),
    VERIFIED("Verified"),
    BLOCKED("Blocked"),
}

@Composable
fun ContactsTab(
    peers: List<PeerUiModel>,
    myNickname: String,
    myPeerIdHex: String,
    isMeshRunning: Boolean,
    connectedBleCount: Int,
    onOpenDm: (String) -> Unit,
    onPingPeer: (PeerUiModel) -> Unit,
    onVerifyPeer: (PeerUiModel) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onToggleBlocked: (String) -> Unit,
    onAnnounceNow: () -> Unit,
) {
    val palette = LocalCypherPalette.current
    val haptic = LocalHapticFeedback.current
    var selectedFilter by remember { mutableStateOf(PeerDirectoryFilter.ALL) }

    val filteredPeers = remember(peers, selectedFilter) {
        peers.filter { peer ->
            when (selectedFilter) {
                PeerDirectoryFilter.ALL -> !peer.isBlocked
                PeerDirectoryFilter.DIRECT -> peer.isDirectlyConnected && !peer.isBlocked
                PeerDirectoryFilter.FAVORITES -> peer.isFavorite && !peer.isBlocked
                PeerDirectoryFilter.VERIFIED -> peer.isVerified && !peer.isBlocked
                PeerDirectoryFilter.BLOCKED -> peer.isBlocked
            }
        }
    }

    val directCount = remember(peers, connectedBleCount) {
        maxOf(connectedBleCount, peers.count { it.isDirectlyConnected })
    }
    val e2eeCount = remember(peers) { peers.count { it.sessionState == "established" } }
    val verifiedCount = remember(peers) { peers.count { it.isVerified } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Mesh Radar",
                    style = MaterialTheme.typography.headlineLarge,
                    color = palette.textPrimary,
                )
                Text(
                    text = "${peers.count { !it.isBlocked }} discovered · $directCount direct BLE · $e2eeCount Noise XX",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                )
            }

            Surface(
                color = palette.accentSoft,
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onAnnounceNow()
                    }
                    .border(1.dp, palette.accent.copy(alpha = 0.45f), RoundedCornerShape(50))
                    .testTag("radar_announce_button"),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Broadcast presence",
                        tint = palette.accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "Announce",
                        style = MaterialTheme.typography.labelLarge,
                        color = palette.accent,
                    )
                }
            }
        }

        // Filter Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PeerDirectoryFilter.entries.forEach { filter ->
                val isSelected = selectedFilter == filter
                val bg by animateColorAsState(
                    targetValue = if (isSelected) palette.accentSoft else palette.surface1,
                    label = "peer_filter_bg",
                )
                val border by animateColorAsState(
                    targetValue = if (isSelected) palette.accent else palette.borderSubtle,
                    label = "peer_filter_border",
                )
                val fg by animateColorAsState(
                    targetValue = if (isSelected) palette.accent else palette.textSecondary,
                    label = "peer_filter_fg",
                )

                Surface(
                    color = bg,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { selectedFilter = filter }
                        .border(1.dp, border, RoundedCornerShape(50))
                        .testTag("peer_filter_${filter.name.lowercase()}"),
                ) {
                    Text(
                        text = filter.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = fg,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("mesh_peers_list"),
            contentPadding = PaddingValues(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Live Mesh Radar Visualization Card
            item(key = "mesh_radar_visualizer") {
                MeshRadarVisualizerCard(
                    myNickname = myNickname,
                    myPeerIdHex = myPeerIdHex,
                    peers = peers.filter { !it.isBlocked }.take(10),
                    isMeshRunning = isMeshRunning,
                    directCount = directCount,
                    e2eeCount = e2eeCount,
                    verifiedCount = verifiedCount,
                )
            }

            if (filteredPeers.isEmpty()) {
                item(key = "empty_peers_state") {
                    Surface(
                        color = palette.surface1,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                            .border(1.dp, palette.borderSubtle, RoundedCornerShape(20.dp)),
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Radar,
                                contentDescription = null,
                                tint = palette.accent,
                                modifier = Modifier.size(32.dp),
                            )
                            Text(
                                text = if (selectedFilter == PeerDirectoryFilter.ALL) {
                                    "Scanning Bluetooth LE Spectrum…"
                                } else {
                                    "No Peers in '${selectedFilter.label}'"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.textPrimary,
                            )
                            Text(
                                text = "Keep Cypher open or unrestricted in the background on two or more devices to automatically establish multi-hop mesh links.",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textSecondary,
                            )
                        }
                    }
                }
            } else {
                items(filteredPeers, key = { it.peerIdHex }) { peer ->
                    PeerDirectoryCard(
                        peer = peer,
                        onOpenDm = { onOpenDm(peer.peerIdHex) },
                        onPing = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPingPeer(peer)
                        },
                        onVerify = { onVerifyPeer(peer) },
                        onToggleFavorite = { onToggleFavorite(peer.peerIdHex) },
                        onToggleBlocked = { onToggleBlocked(peer.peerIdHex) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MeshRadarVisualizerCard(
    myNickname: String,
    myPeerIdHex: String,
    peers: List<PeerUiModel>,
    isMeshRunning: Boolean,
    directCount: Int,
    e2eeCount: Int,
    verifiedCount: Int,
) {
    val palette = LocalCypherPalette.current
    val transition = rememberInfiniteTransition(label = "radar_sweep")
    val sweepAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep_angle",
    )
    val pulseRadius by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.98f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulse_radius",
    )

    val peerDotColors = remember(peers, palette.isDark) {
        peers.map { PeerColors.forPeer(it.peerIdHex, palette.isDark) }
    }

    Surface(
        color = palette.surface1,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .border(1.dp, palette.borderSubtle, RoundedCornerShape(22.dp)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "LOCAL NODE TOPOLOGY",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.accent,
                    )
                    Text(
                        text = PeerIdentity.formatHandle(myNickname, myPeerIdHex),
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = CypherMonoFontFamily),
                        color = palette.textPrimary,
                    )
                }
                Surface(
                    color = if (isMeshRunning) palette.encryptedGreenSoft else palette.warningAmberSoft,
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        text = if (isMeshRunning) "GATT DUAL-ROLE" else "STANDBY",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = CypherMonoFontFamily),
                        color = if (isMeshRunning) palette.encryptedGreen else palette.warningAmber,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(176.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.canvas),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val maxR = (minOf(size.width, size.height) / 2f) * 0.86f

                    // Concentric range rings
                    for (ring in 1..3) {
                        drawCircle(
                            color = palette.borderStrong.copy(alpha = 0.45f),
                            radius = maxR * (ring / 3f),
                            center = center,
                            style = Stroke(width = 1.2f),
                        )
                    }

                    // Expanding sonar pulse ring when active
                    if (isMeshRunning) {
                        drawCircle(
                            color = palette.accent.copy(alpha = (1f - pulseRadius) * 0.45f),
                            radius = maxR * pulseRadius,
                            center = center,
                            style = Stroke(width = 2.2f),
                        )

                        // Rotating sweep line
                        val rad = Math.toRadians(sweepAngle.toDouble())
                        val end = Offset(
                            x = center.x + (maxR * cos(rad)).toFloat(),
                            y = center.y + (maxR * sin(rad)).toFloat(),
                        )
                        drawLine(
                            brush = Brush.linearGradient(
                                colors = listOf(palette.accent.copy(alpha = 0.55f), Color.Transparent),
                                start = center,
                                end = end,
                            ),
                            start = center,
                            end = end,
                            strokeWidth = 2.5f,
                        )
                    }

                    // Plot discovered peers around center
                    peers.forEachIndexed { idx, peer ->
                        val angleRad = Math.toRadians((idx * (360.0 / peers.size.coerceAtLeast(1))) + 25.0)
                        val distFactor = when {
                            peer.isDirectlyConnected && (peer.rssi ?: -65) >= -68 -> 0.42f
                            peer.isDirectlyConnected -> 0.64f
                            else -> 0.84f
                        }
                        val nodePos = Offset(
                            x = center.x + (maxR * distFactor * cos(angleRad)).toFloat(),
                            y = center.y + (maxR * distFactor * sin(angleRad)).toFloat(),
                        )
                        val dotColor = peerDotColors.getOrElse(idx) { palette.accent }

                        // Link spoke line
                        drawLine(
                            color = if (peer.sessionState == "established") {
                                palette.encryptedGreen.copy(alpha = 0.45f)
                            } else {
                                dotColor.copy(alpha = 0.30f)
                            },
                            start = center,
                            end = nodePos,
                            strokeWidth = if (peer.isDirectlyConnected) 2.2f else 1.2f,
                        )

                        drawCircle(
                            color = dotColor.copy(alpha = 0.25f),
                            radius = 16f,
                            center = nodePos,
                        )
                        drawCircle(
                            color = dotColor,
                            radius = 7.5f,
                            center = nodePos,
                        )
                    }

                    // Central self node
                    drawCircle(
                        color = palette.encryptedGreen.copy(alpha = 0.25f),
                        radius = 20f,
                        center = center,
                    )
                    drawCircle(
                        color = palette.encryptedGreen,
                        radius = 8.5f,
                        center = center,
                    )
                }
            }

            // Telemetry Summary Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TelemetryMetricChip(
                    label = "DIRECT BLE",
                    value = directCount.toString(),
                    accent = palette.accent,
                    modifier = Modifier.weight(1f),
                )
                TelemetryMetricChip(
                    label = "NOISE XX",
                    value = e2eeCount.toString(),
                    accent = palette.encryptedGreen,
                    modifier = Modifier.weight(1f),
                )
                TelemetryMetricChip(
                    label = "VERIFIED",
                    value = verifiedCount.toString(),
                    accent = palette.encryptedGreen,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TelemetryMetricChip(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val palette = LocalCypherPalette.current
    Surface(
        color = palette.canvas,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.border(1.dp, palette.borderSubtle, RoundedCornerShape(12.dp)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = CypherMonoFontFamily),
                fontWeight = FontWeight.Bold,
                color = accent,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = palette.textMuted,
            )
        }
    }
}

@Composable
private fun PeerDirectoryCard(
    peer: PeerUiModel,
    onOpenDm: () -> Unit,
    onPing: () -> Unit,
    onVerify: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleBlocked: () -> Unit,
) {
    val palette = LocalCypherPalette.current

    Surface(
        color = palette.surface1,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .border(1.dp, palette.borderSubtle, RoundedCornerShape(18.dp))
            .testTag("peer_card_${peer.peerIdHex.take(8)}"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PeerAvatar(
                    nickname = peer.nickname,
                    peerIdHex = peer.peerIdHex,
                    size = 48.dp,
                    isOnline = peer.isDirectlyConnected,
                    isVerified = peer.isVerified,
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = peer.nickname,
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        EncryptionStatusPill(
                            sessionState = peer.sessionState,
                            isVerified = peer.isVerified,
                            isBroadcast = false,
                            onClick = onVerify,
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = PeerIdentity.shortId(peer.peerIdHex),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = CypherMonoFontFamily),
                            color = palette.textSecondary,
                        )
                        SignalBarsBadge(
                            rssi = peer.rssi,
                            isDirectlyConnected = peer.isDirectlyConnected,
                        )
                        if (peer.lastSeenMs > 0L) {
                            Text(
                                text = "seen ${PeerIdentity.relativeTime(peer.lastSeenMs)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textMuted,
                            )
                        }
                    }
                }

                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = if (peer.isFavorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                        contentDescription = if (peer.isFavorite) "Remove favorite" else "Favorite peer",
                        tint = if (peer.isFavorite) palette.warningAmber else palette.textMuted,
                    )
                }
            }

            // Action Buttons Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onOpenDm,
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .testTag("peer_message_button_${peer.peerIdHex.take(6)}"),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.accent,
                        contentColor = palette.accentOnPrimary,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Chat,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Message",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }

                OutlinedButton(
                    onClick = onPing,
                    modifier = Modifier
                        .height(40.dp)
                        .testTag("peer_ping_button_${peer.peerIdHex.take(6)}"),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.NetworkPing,
                        contentDescription = "Send mesh ping",
                        tint = palette.accent,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "Ping",
                        style = MaterialTheme.typography.labelLarge,
                        color = palette.textPrimary,
                    )
                }

                OutlinedButton(
                    onClick = onVerify,
                    modifier = Modifier
                        .height(40.dp)
                        .testTag("peer_verify_button_${peer.peerIdHex.take(6)}"),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.VerifiedUser,
                        contentDescription = "Verify safety number",
                        tint = if (peer.isVerified) palette.encryptedGreen else palette.textSecondary,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = if (peer.isVerified) "Verified" else "Verify",
                        style = MaterialTheme.typography.labelLarge,
                        color = palette.textPrimary,
                    )
                }

                IconButton(
                    onClick = onToggleBlocked,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Block,
                        contentDescription = if (peer.isBlocked) "Unblock peer" else "Block peer",
                        tint = if (peer.isBlocked) palette.dangerRed else palette.textMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
