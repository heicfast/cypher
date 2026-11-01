package chat.cypher.android.ui.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.cypher.android.core.MeshController
import chat.cypher.android.core.MeshStore
import chat.cypher.android.core.PeerEntry
import chat.cypher.android.core.PeerIdentity
import chat.cypher.android.core.RoutePlanner
import chat.cypher.android.data.PeerEntity
import chat.cypher.android.ui.chats.HomeSubTab
import chat.cypher.android.ui.components.RadarPulseGraphic
import chat.cypher.android.ui.components.SignalBarsIndicator
import chat.cypher.android.ui.theme.CypherTheme
import chat.cypher.android.ui.theme.GeistMono
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

data class MeshPersonItem(
    val peerKeyHex: String,
    val displayName: String,
    val distanceMeters: Int?,
    val hops: Int,
    val rssi: Int?,
    val avatarTint: Color,
    val connected: Boolean = true,
    val sessionEstablished: Boolean = false,
    val verified: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreenContent(
    onSelectTab: (HomeSubTab) -> Unit,
    onOpenDirectMessage: (String) -> Unit,
    onOpenSecurityVerification: (String) -> Unit,
) {
    val palette = CypherTheme.colors
    val livePeers by MeshStore.peers.collectAsState()
    val knownPeers by MeshStore.knownPeers.collectAsState()
    val verifiedPeers by MeshStore.verifiedPeers.collectAsState()
    val favoritePeers by MeshStore.favoritePeers.collectAsState()
    val blockedPeers by MeshStore.blockedPeers.collectAsState()
    val localIdentityHex by MeshStore.localIdentityHex.collectAsState()

    var selectedPerson by remember { mutableStateOf<MeshPersonItem?>(null) }

    val peopleList = remember(livePeers, knownPeers, verifiedPeers, favoritePeers) {
        buildRealPeopleList(
            livePeers = livePeers,
            knownPeers = knownPeers,
            verifiedPeers = verifiedPeers,
            favoritePeers = favoritePeers,
        )
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
                .padding(innerPadding),
        ) {
            // Top bar: People icon + "People (N)" + trailing action button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.clickable { onSelectTab(HomeSubTab.CHATS) },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Groups,
                        contentDescription = "People",
                        tint = palette.textPrimary,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        text = "People (${peopleList.size})",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                        ),
                        color = palette.textPrimary,
                    )
                }

                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF282048))
                        .border(1.dp, palette.accent.copy(alpha = 0.45f), CircleShape)
                        .clickable {
                            MeshController.announceNow()
                            MeshController.pingAll()
                        }
                        .testTag("people_announce_button"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PersonAdd,
                        contentDescription = "Announce presence to nearby peers",
                        tint = palette.accentLight,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }

            Text(
                text = "Nearby on mesh network",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                ),
                color = palette.textSecondary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )

            Spacer(modifier = Modifier.height(6.dp))

            if (peopleList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .padding(horizontal = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        RadarPulseGraphic(size = 132.dp)
                        Text(
                            text = "Scanning for Nearby Mesh Peers",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                            color = palette.textPrimary,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = "No other Cipher or bitchat nodes have been discovered in Bluetooth LE range yet. Keep Bluetooth enabled or broadcast an immediate presence frame.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.textSecondary,
                            textAlign = TextAlign.Center,
                        )
                        Button(
                            onClick = {
                                MeshController.announceNow()
                                MeshController.pingAll()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = palette.accent),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.testTag("empty_people_broadcast_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.BluetoothSearching,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Broadcast Presence Announce",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(peopleList, key = { it.peerKeyHex }) { person ->
                        MeshPersonCard(
                            person = person,
                            isVerified = person.verified || person.peerKeyHex in verifiedPeers,
                            isFavorite = person.peerKeyHex in favoritePeers,
                            onClick = { selectedPerson = person },
                        )
                    }
                    item { Spacer(modifier = Modifier.height(24.dp)) }
                }
            }
        }
    }

    if (selectedPerson != null) {
        val person = selectedPerson!!
        val isVerified = person.verified || person.peerKeyHex in verifiedPeers
        val isFavorite = person.peerKeyHex in favoritePeers
        val isBlocked = person.peerKeyHex in blockedPeers
        val shortestPath = remember(person.peerKeyHex, localIdentityHex) {
            if (localIdentityHex.isNotBlank()) {
                RoutePlanner.shortestPath(localIdentityHex, person.peerKeyHex)
            } else {
                null
            }
        }

        ModalBottomSheet(
            onDismissRequest = { selectedPerson = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = palette.surfaceElevated,
            contentColor = palette.textPrimary,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(person.avatarTint.copy(alpha = 0.22f))
                        .border(1.5.dp, person.avatarTint.copy(alpha = 0.55f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = person.displayName,
                        tint = person.avatarTint,
                        modifier = Modifier.size(34.dp),
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = person.displayName,
                            style = MaterialTheme.typography.headlineMedium,
                            color = palette.textPrimary,
                        )
                        if (isVerified) {
                            Icon(
                                imageVector = Icons.Rounded.VerifiedUser,
                                contentDescription = "Verified",
                                tint = palette.success,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    val distText = person.distanceMeters?.let { "~ $it m • " }.orEmpty()
                    val statusText = if (person.connected) {
                        "${distText}${person.hops} ${if (person.hops == 1) "hop" else "hops"} • ${person.rssi ?: -68} dBm"
                    } else {
                        "Offline • Last known at ${person.hops} hop(s)"
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = GeistMono),
                        color = palette.textSecondary,
                    )
                    if (shortestPath != null && shortestPath.size > 1) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Route,
                                contentDescription = null,
                                tint = palette.accentLight,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = "Route: ${shortestPath.size - 1} hop(s)",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = GeistMono),
                                color = palette.accentLight,
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = {
                            val key = person.peerKeyHex
                            selectedPerson = null
                            onOpenDirectMessage(key)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = palette.accent),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("person_sheet_message_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Chat,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Message", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        onClick = {
                            val key = person.peerKeyHex
                            selectedPerson = null
                            onOpenSecurityVerification(key)
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("person_sheet_verify_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.VerifiedUser,
                            contentDescription = null,
                            tint = if (isVerified) palette.success else palette.accentLight,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isVerified) "Verified" else "Verify Key",
                            color = palette.textPrimary,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    IconButton(onClick = { MeshStore.toggleFavoritePeer(person.peerKeyHex) }) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                            contentDescription = "Favorite",
                            tint = if (isFavorite) Color(0xFFFBBF24) else palette.textSecondary,
                        )
                    }
                    IconButton(onClick = { MeshStore.toggleBlockPeer(person.peerKeyHex) }) {
                        Icon(
                            imageVector = Icons.Rounded.Block,
                            contentDescription = "Block",
                            tint = if (isBlocked) palette.danger else palette.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MeshPersonCard(
    person: MeshPersonItem,
    isVerified: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
) {
    val palette = CypherTheme.colors
    val hopText = if (person.hops == 1) "1 hop" else "${person.hops} hops"
    val subtitle = if (person.connected && person.distanceMeters != null) {
        "~ ${person.distanceMeters} m • $hopText"
    } else if (person.connected) {
        "Connected • $hopText"
    } else {
        "Offline • Saved contact ($hopText)"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.surface)
            .border(1.dp, palette.borderSubtle, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("mesh_person_card_${person.peerKeyHex}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
            modifier = Modifier.weight(1f),
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(person.avatarTint.copy(alpha = 0.22f))
                    .border(1.2.dp, person.avatarTint.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Person,
                    contentDescription = person.displayName,
                    tint = person.avatarTint,
                    modifier = Modifier.size(22.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = person.displayName,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                        ),
                        color = palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isVerified) {
                        Icon(
                            imageVector = Icons.Rounded.VerifiedUser,
                            contentDescription = "Verified",
                            tint = palette.success,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    if (isFavorite) {
                        Icon(
                            imageVector = Icons.Rounded.Star,
                            contentDescription = "Favorite",
                            tint = Color(0xFFFBBF24),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.5.sp,
                    ),
                    color = palette.textSecondary,
                )
            }
        }

        SignalBarsIndicator(
            rssi = person.rssi,
            hops = person.hops,
            barWidth = 3.2.dp,
            maxBarHeight = 15.dp,
        )
    }
}

private fun buildRealPeopleList(
    livePeers: List<PeerEntry>,
    knownPeers: Map<String, PeerEntity>,
    verifiedPeers: Set<String>,
    favoritePeers: Set<String>,
): List<MeshPersonItem> {
    val result = mutableListOf<MeshPersonItem>()
    val seenKeys = mutableSetOf<String>()

    livePeers.forEach { peer ->
        val normKey = peer.peerKeyHex.lowercase(Locale.US)
        seenKeys.add(normKey)
        val distanceMeters = estimateDistanceMeters(peer.rssi, peer.hops)
        val tint = PeerIdentity.colorForPeer(normKey, isDark = true)
        val display = if (peer.nickname.startsWith("anon#") || peer.nickname.startsWith("@")) {
            peer.nickname
        } else {
            "@${peer.nickname}"
        }
        result.add(
            MeshPersonItem(
                peerKeyHex = normKey,
                displayName = display,
                distanceMeters = distanceMeters,
                hops = peer.hops.coerceIn(1, 7),
                rssi = peer.rssi,
                avatarTint = tint,
                connected = peer.connected,
                sessionEstablished = peer.sessionEstablished,
                verified = peer.verified || normKey in verifiedPeers,
            ),
        )
    }

    // Also include offline peers that the user has explicitly verified or favorited
    knownPeers.values
        .filter { entity ->
            val k = entity.peerKeyHex.lowercase(Locale.US)
            k !in seenKeys && (entity.verified || entity.favorite || k in verifiedPeers || k in favoritePeers)
        }
        .sortedByDescending { it.lastSeenMs }
        .forEach { entity ->
            val normKey = entity.peerKeyHex.lowercase(Locale.US)
            val tint = PeerIdentity.colorForPeer(normKey, isDark = true)
            val rawName = entity.customAlias.ifBlank { entity.nickname }
            val display = if (rawName.startsWith("anon#") || rawName.startsWith("@")) {
                rawName
            } else {
                "@$rawName"
            }
            result.add(
                MeshPersonItem(
                    peerKeyHex = normKey,
                    displayName = display,
                    distanceMeters = null,
                    hops = entity.lastHops.coerceIn(1, 7),
                    rssi = entity.lastRssi,
                    avatarTint = tint,
                    connected = false,
                    sessionEstablished = false,
                    verified = entity.verified || normKey in verifiedPeers,
                ),
            )
        }

    return result
}

/**
 * Free-space path-loss distance estimate from calibrated BLE RSSI (-59 dBm @ 1m, n = 2.2)
 * scaled by multi-hop relay count.
 */
private fun estimateDistanceMeters(rssi: Int?, hops: Int): Int {
    if (rssi == null) return (hops * 18).coerceIn(5, 250)
    val txPower = -59.0
    val ratio = (txPower - rssi.coerceIn(-100, -35)) / (10.0 * 2.2)
    val singleHopMeters = 10.0.pow(ratio)
    return (singleHopMeters * hops.coerceAtLeast(1)).roundToInt().coerceIn(2, 300)
}
