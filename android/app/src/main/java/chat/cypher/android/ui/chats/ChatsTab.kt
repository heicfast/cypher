package chat.cypher.android.ui.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.cypher.android.core.MeshController
import chat.cypher.android.ui.components.PeerAvatar
import chat.cypher.android.ui.components.StoryAvatar
import chat.cypher.android.ui.components.UnreadBadge
import chat.cypher.android.ui.theme.CypherThemeDefaults
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The Chats tab — the design's left screen: large title, search field, an
 * online-peers strip (the mesh-native "stories" row), and the conversation
 * list (#mesh broadcast first, then DMs by recency).
 */
@Composable
fun ChatsTab(onOpenConversation: (String) -> Unit) {
    val colors = CypherThemeDefaults.colors
    var query by remember { mutableStateOf("") }

    val peers by remember { derivedStateOf { MeshController.peers } }
    val connected = peers.filter { it.connected && !it.blocked }
    val conversations by remember { derivedStateOf { MeshController.conversations } }
    val unread by remember { derivedStateOf { MeshController.unreadPeers } }
    val meshMessages by remember { derivedStateOf { MeshController.meshMessages } }

    val dmRows = conversations.entries
        .filter { it.value.isNotEmpty() && !MeshController.isBlocked(it.key) }
        .map { (peerId, messages) ->
            val last = messages.last()
            ConversationRowData(
                peerId = peerId,
                name = peerName(peerId),
                preview = previewOf(last),
                timestampMs = last.timestampMs,
                unread = if (peerId in unread) unreadCount(peerId, messages) else 0,
                connected = peers.firstOrNull { it.peerId == peerId }?.connected == true,
                lastIsMine = last.mine,
                lastStatus = last.status,
            )
        }
        .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
        .sortedByDescending { it.timestampMs }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding(),
    ) {
        // Title
        Text(
            text = "Chats",
            style = MaterialTheme.typography.headlineMedium,
            color = colors.textPrimary,
            modifier = Modifier.padding(start = 24.dp, top = 10.dp, end = 24.dp),
        )

        // Search
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 14.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.searchField)
                .clickable { },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(17.dp),
                )
                androidx.compose.foundation.text.BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.textPrimary),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text(
                                "Search chats",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textTertiary,
                            )
                        } else {
                            inner()
                        }
                    },
                )
            }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            // Online peers strip
            if (connected.isNotEmpty() || true) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        StoryAvatar(
                            name = MeshController.myNickname,
                            peerId = MeshController.myPeerId,
                            online = true,
                            onClick = { onOpenConversation("mesh") },
                        )
                        for (peer in connected) {
                            StoryAvatar(
                                name = peer.nickname,
                                peerId = peer.peerId,
                                online = true,
                                onClick = { onOpenConversation(peer.peerId) },
                            )
                        }
                        if (connected.isEmpty()) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.size(width = 62.dp, height = 74.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(CircleShape)
                                        .background(colors.searchField),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.Wifi,
                                        contentDescription = null,
                                        tint = colors.textTertiary,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                                Text(
                                    "scanning…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textTertiary,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                    }
                }
            }

            // #mesh broadcast channel
            item {
                ConversationRow(
                    data = ConversationRowData(
                        peerId = "mesh",
                        name = "#mesh",
                        preview = meshPreview(meshMessages),
                        timestampMs = meshMessages.lastOrNull()?.timestampMs ?: 0L,
                        unread = 0,
                        connected = true,
                        lastIsMine = false,
                        lastStatus = "sent",
                        isMesh = true,
                        peerCount = peers.count { it.connected },
                    ),
                    onClick = { onOpenConversation("mesh") },
                )
            }

            if (dmRows.isEmpty()) {
                item {
                    if (query.isBlank()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 28.dp),
                        ) {
                            Text(
                                "Conversations",
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.textTertiary,
                            )
                            Text(
                                "Private conversations will appear here.\nPeople nearby show up in Contacts — tap one to say hello.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            } else {
                item {
                    Text(
                        "Conversations",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textTertiary,
                        modifier = Modifier.padding(start = 24.dp, top = 14.dp, bottom = 6.dp),
                    )
                }
                items(dmRows, key = { it.peerId }) { row ->
                    ConversationRow(row, onClick = { onOpenConversation(row.peerId) })
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

private data class ConversationRowData(
    val peerId: String,
    val name: String,
    val preview: String,
    val timestampMs: Long,
    val unread: Int,
    val connected: Boolean,
    val lastIsMine: Boolean,
    val lastStatus: String,
    val isMesh: Boolean = false,
    val peerCount: Int = 0,
)

@Composable
private fun ConversationRow(data: ConversationRowData, onClick: () -> Unit) {
    val colors = CypherThemeDefaults.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PeerAvatar(
            name = data.name,
            peerId = data.peerId,
            size = 54.dp,
            online = data.connected,
            showOnlineDot = true,
            group = data.isMesh,
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = data.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = relativeTime(data.timestampMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (data.unread > 0) colors.accentDark else colors.textTertiary,
                    fontWeight = if (data.unread > 0) FontWeight.Bold else FontWeight.Medium,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp),
            ) {
                val preview = buildString {
                    if (data.lastIsMine && !data.isMesh) append("You: ")
                    append(data.preview)
                }
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (data.unread > 0) {
                    UnreadBadge(data.unread, modifier = Modifier.padding(start = 8.dp))
                } else if (data.lastIsMine && data.lastStatus != "sending") {
                    DeliveryTicks(data.lastStatus)
                }
            }
        }
    }
}

/** Double-check delivery ticks (grey = sent, blue = delivered/read). */
@Composable
fun DeliveryTicks(status: String) {
    val colors = CypherThemeDefaults.colors
    val tint = when (status) {
        "read" -> colors.accent
        "delivered" -> colors.accentDark
        "failed" -> colors.danger
        else -> colors.textTertiary
    }
    if (status == "failed") {
        Text("!", color = colors.danger, fontWeight = FontWeight.Bold)
    } else if (status == "sending") {
        Text("·", color = colors.textTertiary, fontWeight = FontWeight.Bold)
    } else {
        androidx.compose.foundation.Canvas(Modifier.size(width = 16.dp, height = 10.dp)) {
            val stroke = 1.6.dp.toPx()
            drawLine(tint, androidx.compose.ui.geometry.Offset(0f, size.height * 0.55f), androidx.compose.ui.geometry.Offset(size.width * 0.28f, size.height * 0.95f), stroke)
            drawLine(tint, androidx.compose.ui.geometry.Offset(size.width * 0.28f, size.height * 0.95f), androidx.compose.ui.geometry.Offset(size.width * 0.52f, size.height * 0.15f), stroke)
            drawLine(tint, androidx.compose.ui.geometry.Offset(size.width * 0.45f, size.height * 0.55f), androidx.compose.ui.geometry.Offset(size.width * 0.72f, size.height * 0.95f), stroke)
            drawLine(tint, androidx.compose.ui.geometry.Offset(size.width * 0.72f, size.height * 0.95f), androidx.compose.ui.geometry.Offset(size.width * 0.98f, size.height * 0.15f), stroke)
        }
    }
}

// ---- helpers -----------------------------------------------------------------

private fun peerName(peerId: String): String =
    MeshController.peers.firstOrNull { it.peerId == peerId }?.nickname
        ?: MeshController.conversations[peerId]?.lastOrNull { !it.mine }?.senderNickname
        ?: peerId.take(8)

private fun unreadCount(peerId: String, messages: List<MeshController.UiMessage>): Int =
    messages.count { !it.mine }

private fun previewOf(message: MeshController.UiMessage): String = when (message.kind) {
    MeshController.Kind.IMAGE -> "📷 Photo"
    MeshController.Kind.FILE -> "📎 ${message.content}"
    MeshController.Kind.VOICE -> "🎤 Voice message"
    else -> message.content
}

private fun meshPreview(messages: List<MeshController.UiMessage>): String {
    val last = messages.lastOrNull() ?: return "Public mesh broadcast"
    val who = if (last.mine) "You" else last.senderNickname
    return "$who: ${previewOf(last)}"
}

/** "9:41 AM" / "Yesterday" / "Mon" / "3/12/25" — chat-list style. */
fun relativeTime(timestampMs: Long): String {
    if (timestampMs <= 0) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = timestampMs }
    return when {
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) ->
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestampMs))
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR) == 1 -> "Yesterday"
        now.timeInMillis - timestampMs < 6 * 24 * 3600_000L ->
            SimpleDateFormat("EEE", Locale.getDefault()).format(Date(timestampMs))
        else -> SimpleDateFormat("M/d/yy", Locale.getDefault()).format(Date(timestampMs))
    }
}
