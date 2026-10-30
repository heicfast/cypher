package chat.cypher.android.ui.chats

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.cypher.android.data.Conversation
import chat.cypher.android.data.ConversationKind
import chat.cypher.android.data.MeshController
import chat.cypher.android.ui.components.PeerAvatar
import chat.cypher.android.ui.components.UnreadBadge
import chat.cypher.android.ui.components.DeliveryTicks
import chat.cypher.android.ui.theme.LocalCypherPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Chats tab — the premium chats list: title + search pill + stories row of
 * connected peers + conversation rows on a rounded white sheet.
 */
@Composable
fun ChatsScreen(
    controller: MeshController,
    onOpenConversation: (String) -> Unit,
    onNewChat: () -> Unit,
) {
    val palette = LocalCypherPalette.current
    val conversations = controller.conversations
    val peers = controller.peers
    var query by rememberSaveable { mutableStateOf("") }

    val filtered = remember(conversations, query) {
        if (query.isBlank()) conversations
        else conversations.filter {
            it.displayName.contains(query, ignoreCase = true) ||
                (it.lastMessage?.content ?: "").contains(query, ignoreCase = true)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding()
    ) {
        // Title row
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Chats",
                color = palette.textPrimary,
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.weight(1f),
            )
            Surface(
                shape = CircleShape,
                color = palette.surfaceSearch,
                modifier = Modifier
                    .size(36.dp)
                    .clickable(onClick = onNewChat),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Edit, null,
                        tint = palette.textSecondary,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }

        // Search pill
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .height(44.dp)
                .background(palette.surfaceSearch, RoundedCornerShape(22.dp))
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Filled.Search, null, tint = palette.textTertiary, modifier = Modifier.size(17.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = palette.textPrimary),
                cursorBrush = SolidColor(palette.primary),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                "Search chats",
                                color = palette.textTertiary,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        inner()
                    }
                },
            )
        }

        // Stories row: connected peers (premium stories strip)
        if (peers.isNotEmpty()) {
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(bottom = 12.dp),
            ) {
                items(peers, key = { it.peerId }) { peer ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(64.dp)
                            .clickable { onOpenConversation("dm:${peer.peerId}") },
                    ) {
                        PeerAvatar(
                            name = peer.nickname,
                            identityKey = peer.peerId,
                            size = 58.dp,
                            online = peer.connected,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            peer.nickname,
                            color = palette.textSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }

        // Conversation sheet (rounded white card)
        Surface(
            color = palette.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 14.dp, bottom = 110.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                val mesh = filtered.filter { it.kind == ConversationKind.MESH }
                val others = filtered.filter { it.kind != ConversationKind.MESH }
                    .sortedByDescending { it.lastActivityMs }
                items(mesh + others, key = { it.id }) { conversation ->
                    ConversationRow(conversation, controller) { onOpenConversation(conversation.id) }
                    HorizontalDivider(
                        color = palette.separator.copy(alpha = 0.55f),
                        thickness = 0.6.dp,
                        modifier = Modifier.padding(start = 90.dp),
                    )
                }
                item {
                    if (filtered.isEmpty()) {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 60.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                if (query.isBlank()) "No conversations yet"
                                else "No results for \"$query\"",
                                color = palette.textTertiary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                if (query.isBlank())
                                    "Peers you chat with appear here — check Contacts for nearby Cypher users"
                                else "Try a different search",
                                color = palette.textTertiary,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    controller: MeshController,
    onClick: () -> Unit,
) {
    val palette = LocalCypherPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PeerAvatar(
            name = conversation.displayName,
            identityKey = conversation.peerId ?: conversation.id,
            size = 52.dp,
            online = conversation.online,
            isGroup = conversation.kind == ConversationKind.CHANNEL,
            isMesh = conversation.kind == ConversationKind.MESH,
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    conversation.displayName,
                    color = palette.textPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    relativeTime(conversation.lastActivityMs),
                    color = palette.textTertiary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val preview = buildPreview(conversation)
                Text(
                    preview.text,
                    color = if (conversation.unreadCount > 0) palette.textPrimary else palette.textSecondary,
                    fontSize = 14.sp,
                    fontWeight = if (conversation.unreadCount > 0) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                if (conversation.favorite) {
                    Icon(
                        Icons.Filled.PushPin, null,
                        tint = palette.textTertiary,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                if (conversation.draft.isNotEmpty()) {
                    Text("✎", color = palette.warning, fontSize = 13.sp)
                    Spacer(Modifier.width(4.dp))
                }
                UnreadBadge(conversation.unreadCount)
            }
        }
    }
}

private data class Preview(val text: String)

private fun buildPreview(conversation: Conversation): Preview {
    val last = conversation.lastMessage
    return when {
        conversation.draft.isNotEmpty() && conversation.draft.isNotBlank() ->
            Preview("Draft: ${conversation.draft}")
        conversation.kind == ConversationKind.MESH && last == null ->
            Preview(
                if (conversation.online) "Peers nearby — say hi 👋"
                else "Waiting for peers to come in range"
            )
        last == null -> Preview("Say hello 👋")
        last.kind == chat.cypher.android.data.MessageKind.SYSTEM -> Preview(last.content)
        last.kind == chat.cypher.android.data.MessageKind.IMAGE ->
            Preview(if (last.isOwn) "📷 You sent a photo" else "📷 Photo")
        last.kind == chat.cypher.android.data.MessageKind.VOICE ->
            Preview(if (last.isOwn) "🎤 You sent a voice note" else "🎤 Voice message")
        last.kind == chat.cypher.android.data.MessageKind.FILE ->
            Preview(if (last.isOwn) "📎 ${last.fileName ?: "File"}" else "📎 ${last.fileName ?: "File"}")
        last.isOwn -> Preview("You: ${last.content}")
        else -> Preview(last.content)
    }
}

/** Relative timestamps like the reference ("9:41 AM", "Yesterday", "Monday"). */
fun relativeTime(ms: Long): String {
    if (ms <= 0) return ""
    val now = System.currentTimeMillis()
    val dayFmt = SimpleDateFormat("EEEE", Locale.getDefault())
    val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
    val dateFmt = SimpleDateFormat("M/d/yy", Locale.getDefault())
    val diffDays = ((now - ms) / 86_400_000L).toInt()
    val sameDay = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).run {
        format(Date(now)) == format(Date(ms))
    }
    return when {
        sameDay -> timeFmt.format(Date(ms))
        diffDays < 2 -> "Yesterday"
        diffDays < 7 -> dayFmt.format(Date(ms))
        else -> dateFmt.format(Date(ms))
    }
}
