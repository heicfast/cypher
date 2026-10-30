package chat.cypher.android.ui.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.cypher.android.core.MeshController
import chat.cypher.android.ui.chats.relativeTime
import chat.cypher.android.ui.components.PeerAvatar
import chat.cypher.android.ui.theme.CypherThemeDefaults

/**
 * The Contacts tab: everyone the mesh has seen — connected peers first,
 * then recently-seen, with favorites pinned to the top. Tapping opens the
 * peer detail sheet (fingerprint verification, favorite, block).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsTab(onOpenConversation: (String) -> Unit) {
    val colors = CypherThemeDefaults.colors
    var detailPeer by remember { mutableStateOf<String?>(null) }

    val peers = MeshController.peers
        .filter { !it.blocked }
        .sortedWith(
            compareByDescending<MeshController.UiPeer> { it.connected }
                .thenByDescending { it.favorite }
                .thenByDescending { it.lastSeenMs },
        )
    val blocked = MeshController.peers.filter { it.blocked }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding(),
    ) {
        Text(
            "Contacts",
            style = MaterialTheme.typography.headlineMedium,
            color = colors.textPrimary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
        )

        if (peers.isEmpty() && blocked.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .background(colors.searchField)
                            .padding(22.dp),
                    ) {
                        Icon(Icons.Outlined.PersonOutline, contentDescription = null, tint = colors.textTertiary)
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Nobody around…",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                    )
                    Text(
                        "Cypher peers appear here when they're nearby.\nKeep the app open with Bluetooth on.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(top = 6.dp, start = 32.dp, end = 32.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn {
                if (peers.isNotEmpty()) {
                    item {
                        Text(
                            "PEOPLE NEARBY",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textTertiary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 24.dp, top = 10.dp, bottom = 6.dp),
                        )
                    }
                }
                items(peers, key = { it.peerId }) { peer ->
                    PeerRow(peer) {
                        onOpenConversation(peer.peerId)
                    }
                    PeerRowActions(peer) {
                        detailPeer = peer.peerId
                    }
                }
                if (blocked.isNotEmpty()) {
                    item {
                        Text(
                            "BLOCKED",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textTertiary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 24.dp, top = 18.dp, bottom = 6.dp),
                        )
                    }
                    items(blocked, key = { "b" + it.peerId }) { peer ->
                        PeerRow(peer, blocked = true) {
                            detailPeer = peer.peerId
                        }
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }

    if (detailPeer != null) {
        val peer = MeshController.peers.firstOrNull { it.peerId == detailPeer }
        if (peer != null) {
            ModalBottomSheet(
                onDismissRequest = { detailPeer = null },
                containerColor = colors.surface,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                PeerDetailSheet(
                    peer = peer,
                    onClose = { detailPeer = null },
                    onMessage = {
                        detailPeer = null
                        onOpenConversation(peer.peerId)
                    },
                )
            }
        }
    }
}

@Composable
private fun PeerRow(
    peer: MeshController.UiPeer,
    blocked: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = CypherThemeDefaults.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PeerAvatar(
            name = peer.nickname,
            peerId = peer.peerId,
            size = 50.dp,
            online = peer.connected,
            showOnlineDot = true,
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    peer.nickname,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (peer.verified) {
                    Icon(
                        Icons.Filled.Verified,
                        contentDescription = "Verified",
                        tint = colors.accent,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            Text(
                text = when {
                    blocked -> "Blocked"
                    peer.connected -> "Online · ${if (peer.secureSession) "encrypted" else "handshaking…"}"
                    else -> "Last seen ${relativeTime(peer.lastSeenMs)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (blocked) colors.danger else colors.textSecondary,
            )
        }
        if (peer.favorite) {
            Icon(
                Icons.Filled.Star,
                contentDescription = "Favorite",
                tint = androidx.compose.ui.graphics.Color(0xFFF5B301),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun PeerRowActions(peer: MeshController.UiPeer, onDetail: () -> Unit) {
    // The info affordance lives on the row's trailing edge.
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeerDetailSheet(
    peer: MeshController.UiPeer,
    onClose: () -> Unit,
    onMessage: () -> Unit,
) {
    val colors = CypherThemeDefaults.colors
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PeerAvatar(
            name = peer.nickname,
            peerId = peer.peerId,
            size = 74.dp,
            online = peer.connected,
            showOnlineDot = true,
        )
        Text(
            peer.nickname,
            style = MaterialTheme.typography.headlineSmall,
            color = colors.textPrimary,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            when {
                peer.connected -> "Online · end-to-end encrypted"
                else -> "Last seen ${relativeTime(peer.lastSeenMs)}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(22.dp))

        // Fingerprint block
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(colors.canvas)
                .padding(16.dp),
        ) {
            Text(
                "Their fingerprint",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                fingerprintBlock(peer.noisePublicKeyHex),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
                color = colors.textPrimary,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Your fingerprint",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                fingerprintBlock(MeshController.myFingerprint),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
                color = colors.textPrimary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Text(
            "Compare these fingerprints with ${peer.nickname} using a secure channel.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textTertiary,
            modifier = Modifier.padding(top = 10.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )

        Spacer(Modifier.height(18.dp))
        DetailAction(
            label = if (peer.favorite) "Remove from favorites" else "Add to favorites",
            icon = if (peer.favorite) Icons.Filled.StarBorder else Icons.Filled.Star,
        ) {
            MeshController.setFavorite(peer.peerId, !peer.favorite)
            onClose()
        }
        DetailAction(
            label = if (peer.verified) "Remove verification" else "Mark as verified",
            icon = Icons.Filled.Verified,
        ) {
            MeshController.setVerified(peer.peerId, !peer.verified)
            onClose()
        }
        DetailAction(
            label = "Message ${peer.nickname}",
            icon = Icons.Filled.Person,
            prominent = true,
        ) {
            onMessage()
        }
        DetailAction(
            label = if (peer.blocked) "Unblock ${peer.nickname}" else "Block ${peer.nickname}",
            icon = Icons.Filled.Block,
            destructive = true,
        ) {
            if (peer.blocked) MeshController.unblock(peer.peerId) else MeshController.block(peer.peerId)
            onClose()
        }
        DetailAction("Copy fingerprint", icon = Icons.Filled.Verified) {
            clipboard.setText(AnnotatedString(peer.noisePublicKeyHex))
        }
        DetailAction("Close", icon = Icons.Filled.Block) { onClose() }
    }
}

@Composable
private fun DetailAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    destructive: Boolean = false,
    prominent: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = CypherThemeDefaults.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (prominent) colors.accent else Color(0x00000000))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (prominent) androidx.compose.ui.graphics.Color.White
            else if (destructive) colors.danger else colors.textSecondary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                prominent -> androidx.compose.ui.graphics.Color.White
                destructive -> colors.danger
                else -> colors.textPrimary
            },
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun fingerprintBlock(hex: String): String {
    val clean = hex.uppercase().take(64).chunked(4)
    return if (clean.size <= 4) clean.joinToString(" ")
    else clean.chunked(4).joinToString("\n") { it.joinToString(" ") }
}
