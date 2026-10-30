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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.cypher.android.data.CypherMessage
import chat.cypher.android.data.Delivery
import chat.cypher.android.data.MeshController
import chat.cypher.android.data.MessageKind
import chat.cypher.android.data.ConversationKind
import chat.cypher.android.ui.components.OnlineDot
import chat.cypher.android.ui.components.PeerAvatar
import chat.cypher.android.ui.components.DeliveryTicks
import chat.cypher.android.ui.theme.LocalCypherPalette
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Conversation screen — the premium iMessage-style chat: frosted header with
 * peer identity + security lock, blue/gray bubbles with tails and inline
 * timestamps + delivery ticks, day separators, and the capsule composer with
 * a circular blue send button.
 */
@Composable
fun ConversationScreen(
    controller: MeshController,
    conversationId: String,
    onBack: () -> Unit,
    onPeerInfo: () -> Unit,
) {
    val palette = LocalCypherPalette.current
    val conversation = remember(controller.conversations, conversationId) {
        controller.conversation(conversationId)
    }
    val messages = remember(controller.messages, conversationId) {
        controller.messagesFor(conversationId)
    }
    var composerText by remember(conversationId) {
        mutableStateOf(controller.conversation(conversationId)?.draft ?: "")
    }
    val listState = rememberLazyListState()

    // Follow the newest message while pinned at the bottom.
    LaunchedEffect(messages.size, conversationId) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.surface)
            .statusBarsPadding()
            .imePadding()
    ) {
        // ---- header ------------------------------------------------------------
        Surface(color = palette.surface, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBackIos, "Back",
                        tint = palette.textPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                PeerAvatar(
                    name = conversation?.displayName ?: "?",
                    identityKey = conversation?.peerId ?: conversationId,
                    size = 38.dp,
                    online = conversation?.online == true,
                )
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { if (conversation?.peerId != null) onPeerInfo() }
                ) {
                    Text(
                        conversation?.displayName ?: "Chat",
                        color = palette.textPrimary,
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 1,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        when {
                            conversation?.kind == ConversationKind.MESH -> {
                                val count = controller.peers.count { it.connected }
                                OnlineDot(7.dp)
                                Text(
                                    "$count ${if (count == 1) "peer" else "peers"} nearby",
                                    color = palette.online,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            conversation?.online == true -> {
                                OnlineDot(7.dp)
                                Text(
                                    "Online",
                                    color = palette.online,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            else -> Text(
                                "Offline · messages will send when in range",
                                color = palette.textTertiary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                            )
                        }
                    }
                }
                if (conversation?.kind == ConversationKind.DIRECT) {
                    // Security lock (Noise session state) — tap for peer sheet.
                    val locked = conversation.secureSession
                    Icon(
                        if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        if (locked) "End-to-end encrypted" else "Not encrypted yet",
                        tint = if (locked) palette.online else palette.textTertiary,
                        modifier = Modifier
                            .size(22.dp)
                            .clickable(onClick = onPeerInfo),
                    )
                    Icon(
                        Icons.Filled.Call, "Call",
                        tint = palette.textTertiary,
                        modifier = Modifier.size(21.dp),
                    )
                    Icon(
                        Icons.Filled.Videocam, "Video",
                        tint = palette.textTertiary,
                        modifier = Modifier.size(23.dp),
                    )
                }
            }
        }

        // ---- messages -------------------------------------------------------------
        Box(Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp, vertical = 10.dp
                ),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                val reversed = messages.asReversed()
                items(reversed, key = { it.id }) { message ->
                    MessageBubble(message)
                }
            }
        }

        // ---- composer ----------------------------------------------------------------
        Composer(
            text = composerText,
            onTextChange = {
                composerText = it
                controller.setDraft(conversationId, it)
            },
            onSend = {
                if (controller.sendMessage(conversationId, it)) {
                    composerText = ""
                    controller.setDraft(conversationId, "")
                }
            },
            hint = when (conversation?.kind) {
                ConversationKind.MESH -> "Message the mesh…"
                ConversationKind.CHANNEL -> "Message ${conversation.displayName}…"
                else -> "Type a message…"
            },
        )
    }
}

// ---- bubbles ----------------------------------------------------------------------

@Composable
private fun MessageBubble(message: CypherMessage) {
    val palette = LocalCypherPalette.current

    if (message.kind == MessageKind.SYSTEM) {
        Text(
            message.content,
            color = palette.textTertiary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        )
        return
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isOwn) Arrangement.End else Arrangement.Start,
    ) {
        val shape = RoundedCornerShape(
            topStart = 20.dp,
            topEnd = 20.dp,
            bottomStart = if (message.isOwn) 20.dp else 6.dp,
            bottomEnd = if (message.isOwn) 6.dp else 20.dp,
        )
        Column(horizontalAlignment = if (message.isOwn) Alignment.End else Alignment.Start) {
            Box(
                Modifier
                    .widthIn(max = 300.dp)
                    .background(
                        if (message.isOwn) palette.bubbleSent else palette.bubbleReceived,
                        shape,
                    )
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            ) {
                Column {
                    Text(
                        message.content,
                        color = if (message.isOwn) palette.bubbleSentText else palette.bubbleReceivedText,
                        fontSize = 15.5.sp,
                        lineHeight = 21.sp,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                    ) {
                        Text(
                            bubbleTime(message.timestampMs),
                            color = if (message.isOwn) Color.White.copy(alpha = 0.72f)
                            else palette.textTertiary,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        if (message.isOwn) DeliveryTicks(message.delivery)
                    }
                }
            }
        }
    }
}

private fun bubbleTime(ms: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))

// ---- composer ------------------------------------------------------------------------

@Composable
private fun Composer(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: (String) -> Unit,
    hint: String,
) {
    val palette = LocalCypherPalette.current
    Surface(
        color = palette.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // + button
            Box(
                Modifier
                    .size(38.dp)
                    .background(palette.surfaceSearch, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Add, "Attach", tint = palette.textSecondary, modifier = Modifier.size(19.dp))
            }
            // capsule input
            Row(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .background(palette.surfaceSearch, RoundedCornerShape(24.dp))
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = palette.textPrimary),
                    cursorBrush = SolidColor(palette.primary),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) {
                                Text(hint, color = palette.textTertiary, style = MaterialTheme.typography.bodyLarge)
                            }
                            inner()
                        }
                    },
                )
                Text("🙂", fontSize = 20.sp)
            }
            // Send button (blue circle)
            val canSend = text.isNotBlank()
            Box(
                Modifier
                    .size(48.dp)
                    .background(
                        if (canSend) palette.primary else palette.primary.copy(alpha = 0.4f),
                        CircleShape,
                    )
                    .clickable(enabled = canSend) { onSend(text) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send, "Send",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
