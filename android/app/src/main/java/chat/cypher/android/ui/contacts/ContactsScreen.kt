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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.cypher.android.data.MeshController
import chat.cypher.android.ui.components.PeerAvatar
import chat.cypher.android.ui.components.UnreadBadge
import chat.cypher.android.ui.theme.LocalCypherPalette

/** Contacts tab — the people on the mesh, online first. */
@Composable
fun ContactsScreen(
    controller: MeshController,
    onOpenConversation: (String) -> Unit,
    onPeerInfo: (String) -> Unit,
) {
    val palette = LocalCypherPalette.current
    val peers = controller.peers
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(peers, query) {
        if (query.isBlank()) peers
        else peers.filter { it.nickname.contains(query, ignoreCase = true) }
    }
    val online = filtered.filter { it.connected }
    val offline = filtered.filter { !it.connected }

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding()
    ) {
        Text(
            "Contacts",
            color = palette.textPrimary,
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.padding(start = 24.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
        )
        Surface(
            color = palette.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (peers.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(top = 120.dp, start = 40.dp, end = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "No one nearby yet",
                        color = palette.textPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Keep Cypher open — peers in Bluetooth range appear here automatically. Both devices need the app running.",
                        color = palette.textSecondary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        top = 20.dp, bottom = 110.dp
                    )
                ) {
                    if (online.isNotEmpty()) {
                        item {
                            SectionLabel("ONLINE — ${online.size}")
                        }
                        items(online, key = { it.peerId }) { peer ->
                            ContactRow(peer, hasUnread = false) {
                                onOpenConversation("dm:${peer.peerId}")
                            }
                        }
                    }
                    if (offline.isNotEmpty()) {
                        item { SectionLabel("RECENTLY SEEN") }
                        items(offline, key = { it.peerId }) { peer ->
                            ContactRow(peer, hasUnread = false) {
                                onOpenConversation("dm:${peer.peerId}")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    val palette = LocalCypherPalette.current
    Text(
        text,
        color = palette.textTertiary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 24.dp, top = 14.dp, bottom = 8.dp),
    )
}

@Composable
private fun ContactRow(
    peer: chat.cypher.android.data.PeerUi,
    hasUnread: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalCypherPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PeerAvatar(
            name = peer.nickname,
            identityKey = peer.peerId,
            size = 48.dp,
            online = peer.connected,
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    peer.nickname,
                    color = palette.textPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (peer.secureSession) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    if (peer.secureSession) "Encrypted session" else "No session yet",
                    tint = if (peer.secureSession) palette.online else palette.textTertiary,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                if (peer.connected) "#${peer.suffix} · in range"
                else "#${peer.suffix} · ${chat.cypher.android.ui.chats.relativeTime(peer.lastSeenMs).ifEmpty { "seen recently" }}",
                color = palette.textSecondary,
                fontSize = 13.sp,
            )
        }
    }
}
