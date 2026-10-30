package chat.cypher.android.ui.chats

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.cypher.android.core.MeshController
import chat.cypher.android.ui.components.PeerAvatar
import chat.cypher.android.ui.theme.CypherThemeDefaults
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * A conversation: the #mesh timeline (conversationId == "mesh") or a
 * private DM. Bubbles, day dividers, delivery ticks, media sending — the
 * design's right screen.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChatThread(conversationId: String, onBack: () -> Unit) {
    val colors = CypherThemeDefaults.colors
    val context = LocalContext.current
    val isMesh = conversationId == "mesh"

    val messages by remember {
        derivedStateOf {
            if (isMesh) MeshController.meshMessages
            else MeshController.conversations[conversationId] ?: emptyList()
        }
    }
    val peer by remember(conversationId) {
        derivedStateOf { MeshController.peers.firstOrNull { it.peerId == conversationId } }
    }
    val peerCount by remember { derivedStateOf { MeshController.peers.count { it.connected } } }

    var draft by remember { mutableStateOf("") }
    var actionTarget by remember { mutableStateOf<MeshController.UiMessage?>(null) }
    val listState = rememberLazyListState()

    // Read receipts: opening a DM marks it seen.
    LaunchedEffect(conversationId) {
        if (!isMesh) MeshController.markConversationRead(conversationId)
    }
    // Auto-scroll to the newest message.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    val mediaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) sendPickedMedia(context, conversationId, isMesh, uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding(),
    ) {
        // ---- header ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(38.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.accentDark,
                )
            }
            PeerAvatar(
                name = if (isMesh) "#mesh" else (peer?.nickname ?: conversationId),
                peerId = conversationId,
                size = 42.dp,
                online = if (isMesh) true else peer?.connected == true,
                showOnlineDot = true,
                group = isMesh,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (isMesh) "#mesh" else (peer?.nickname ?: peerNameOf(conversationId)),
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        isMesh -> if (peerCount == 1) "1 peer nearby" else "$peerCount peers nearby"
                        peer?.connected == true -> "Online"
                        else -> "Offline · messages will queue"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isMesh || peer?.connected == true) colors.online else colors.textTertiary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (!isMesh && peer?.secureSession == true) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = "End-to-end encrypted",
                    tint = colors.textTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        // ---- messages ----
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp, vertical = 6.dp,
            ),
        ) {
            var lastDay = ""
            for (index in messages.indices) {
                val message = messages[index]
                val day = dayLabel(message.timestampMs)
                if (day != null && day != lastDay) {
                    lastDay = day
                    item(key = "day-$day-${message.id}") { DayDivider(day) }
                }
                item(key = message.id) {
                    MessageRow(
                        message = message,
                        showSender = isMesh && !message.mine,
                        onLongPress = { actionTarget = message },
                    )
                }
            }
            if (messages.isEmpty()) {
                item {
                    EmptyConversation(isMesh, peerCount)
                }
            }
        }

        // ---- composer ----
        Composer(
            value = draft,
            onValueChange = { draft = it },
            onSend = {
                if (draft.isNotBlank()) {
                    if (isMesh) MeshController.sendPublic(draft)
                    else MeshController.sendPrivate(conversationId, draft)
                    draft = ""
                }
            },
            onAttach = { mediaPicker.launch("*/*") },
            placeholder = if (isMesh) "Message #mesh" else "Type a message",
        )
    }

    // ---- long-press actions ----
    if (actionTarget != null) {
        val target = actionTarget!!
        ModalBottomSheet(
            onDismissRequest = { actionTarget = null },
            containerColor = colors.surface,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            MessageActions(
                message = target,
                onCopy = {
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(
                        android.content.ClipData.newPlainText("message", target.content),
                    )
                    actionTarget = null
                },
                onMessageUser = {
                    if (!target.mine) {
                        actionTarget = null
                        MeshController.openConversation = target.senderPeerId
                    }
                },
                onBlock = {
                    if (!target.mine) {
                        MeshController.block(target.senderPeerId)
                        actionTarget = null
                    }
                },
                onDismiss = { actionTarget = null },
            )
        }
    }
}

// ---- pieces -----------------------------------------------------------------

@Composable
private fun DayDivider(label: String) {
    val colors = CypherThemeDefaults.colors
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = colors.textTertiary,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(colors.dividerPill)
                .padding(horizontal = 14.dp, vertical = 5.dp),
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
        )
    }
}

@Composable
private fun MessageRow(
    message: MeshController.UiMessage,
    showSender: Boolean,
    onLongPress: () -> Unit,
) {
    val colors = CypherThemeDefaults.colors
    val mine = message.mine
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!mine && showSender) {
            PeerAvatar(
                name = message.senderNickname,
                peerId = message.senderPeerId,
                size = 30.dp,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            if (showSender) {
                Text(
                    text = message.senderNickname,
                    style = MaterialTheme.typography.labelSmall,
                    color = chat.cypher.android.ui.components.peerNameColor(message.senderPeerId),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 6.dp, bottom = 2.dp),
                )
            }
            Bubble(message = message, mine = mine, onLongPress = onLongPress)
        }
    }
}

@Composable
private fun Bubble(
    message: MeshController.UiMessage,
    mine: Boolean,
    onLongPress: () -> Unit,
) {
    val colors = CypherThemeDefaults.colors
    val shape = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = if (mine) 20.dp else 6.dp,
        bottomEnd = if (mine) 6.dp else 20.dp,
    )
    Column(
        modifier = Modifier
            .widthIn(max = 290.dp)
            .clip(shape)
            .background(if (mine) colors.bubbleOutgoing else colors.bubbleIncoming)
            .combinedClickable(
                onClick = {},
                onLongClick = onLongPress,
            )
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        when (message.kind) {
            MeshController.Kind.IMAGE -> {
                MediaThumb(message, mine)
                if (message.content.isNotBlank() && message.content != message.fileName) {
                    Text(
                        message.content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (mine) colors.bubbleOutgoingText else colors.bubbleIncomingText,
                    )
                }
            }
            MeshController.Kind.VOICE -> VoiceBubble(message, mine)
            MeshController.Kind.FILE -> FileBubble(message, mine)
            else -> Text(
                message.content,
                style = MaterialTheme.typography.bodyLarge,
                color = if (mine) colors.bubbleOutgoingText else colors.bubbleIncomingText,
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.End)
                .padding(top = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = clockTime(message.timestampMs),
                style = MaterialTheme.typography.labelSmall,
                color = if (mine) Color.White.copy(alpha = 0.75f) else colors.textTertiary,
            )
            if (mine) DeliveryTicks(message.status)
        }
    }
}

@Composable
private fun MediaThumb(message: MeshController.UiMessage, mine: Boolean) {
    val colors = CypherThemeDefaults.colors
    val bitmap = remember(message.localPath) {
        message.localPath?.let { path ->
            runCatching {
                android.graphics.BitmapFactory.decodeFile(path)
            }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .padding(bottom = 4.dp)
            .size(width = 220.dp, height = 146.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.searchField),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = message.fileName ?: "image",
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text("📷 ${message.fileName ?: "photo"}", color = colors.textSecondary)
        }
    }
}

@Composable
private fun VoiceBubble(message: MeshController.UiMessage, mine: Boolean) {
    val colors = CypherThemeDefaults.colors
    var playing by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    DisposablePlayerEffect(player) { player = null; playing = false }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (mine) Color.White.copy(alpha = 0.2f) else colors.accent),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(onClick = {
                val path = message.localPath
                if (path != null) {
                    if (playing) {
                        player?.stop()
                        player = null
                        playing = false
                    } else {
                        runCatching {
                            val p = android.media.MediaPlayer()
                            p.setDataSource(path)
                            p.prepare()
                            p.setOnCompletionListener {
                                playing = false
                                player = null
                            }
                            p.start()
                            player = p
                            playing = true
                        }
                    }
                }
            }, modifier = Modifier.size(36.dp)) {
                Text(
                    if (playing) "❚❚" else "▶",
                    color = Color.White,
                    fontSize = 13.sp,
                )
            }
        }
        Column {
            Text(
                text = message.fileName ?: "Voice message",
                style = MaterialTheme.typography.bodyLarge,
                color = if (mine) colors.bubbleOutgoingText else colors.bubbleIncomingText,
            )
            Text(
                text = "🎤 Voice message",
                style = MaterialTheme.typography.labelSmall,
                color = if (mine) Color.White.copy(alpha = 0.7f) else colors.textTertiary,
            )
        }
    }
}

@Composable
private fun DisposablePlayerEffect(
    player: android.media.MediaPlayer?,
    onReleased: () -> Unit,
) {
    androidx.compose.runtime.DisposableEffect(player) {
        onDispose {
            runCatching { player?.stop() }
            runCatching { player?.release() }
            if (player != null) onReleased()
        }
    }
}

@Composable
private fun FileBubble(message: MeshController.UiMessage, mine: Boolean) {
    val colors = CypherThemeDefaults.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (mine) Color.White.copy(alpha = 0.2f) else colors.searchField),
            contentAlignment = Alignment.Center,
        ) {
            Text("📎", fontSize = 16.sp)
        }
        Text(
            text = message.fileName ?: message.content,
            style = MaterialTheme.typography.bodyLarge,
            color = if (mine) colors.bubbleOutgoingText else colors.bubbleIncomingText,
        )
    }
}

@Composable
private fun EmptyConversation(isMesh: Boolean, peerCount: Int) {
    val colors = CypherThemeDefaults.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (isMesh) {
                "You're on #mesh — messages reach everyone within Bluetooth range"
            } else {
                "No messages yet — say hello"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    placeholder: String,
) {
    val colors = CypherThemeDefaults.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surface)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Filled.AddCircleOutline,
                contentDescription = "Attach",
                tint = colors.textTertiary,
                modifier = Modifier
                    .size(24.dp)
                    .clickable(onClick = onAttach),
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                maxLines = 5,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.textPrimary),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.textTertiary)
                    } else {
                        inner()
                    }
                },
            )
        }
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(if (value.isNotBlank()) colors.accent else colors.badgeMuted)
                .clickable(enabled = value.isNotBlank(), onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = Color.White,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
private fun MessageActions(
    message: MeshController.UiMessage,
    onCopy: () -> Unit,
    onMessageUser: () -> Unit,
    onBlock: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CypherThemeDefaults.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "@${message.senderNickname}",
            style = MaterialTheme.typography.titleLarge,
            color = colors.textPrimary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        ActionRow("Copy message", onCopy)
        if (!message.mine) {
            ActionRow("Message ${message.senderNickname}", onMessageUser)
            ActionRow(
                "Block ${message.senderNickname}",
                onBlock,
                destructive = true,
            )
        }
        ActionRow("Cancel", onDismiss)
    }
}

@Composable
private fun ActionRow(label: String, onClick: () -> Unit, destructive: Boolean = false) {
    val colors = CypherThemeDefaults.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (destructive) colors.danger else colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ---- helpers -----------------------------------------------------------------

private fun peerNameOf(peerId: String): String =
    MeshController.peers.firstOrNull { it.peerId == peerId }?.nickname
        ?: MeshController.conversations[peerId]?.lastOrNull { !it.mine }?.senderNickname
        ?: peerId.take(8)

private fun clockTime(timestampMs: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestampMs))

/** Day label for dividers; null when the day is already implied. */
private fun dayLabel(timestampMs: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = timestampMs }
    return when {
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) -> "Today"
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR) == 1 -> "Yesterday"
        else -> SimpleDateFormat("MMMM d", Locale.getDefault()).format(Date(timestampMs))
    }
}

/** Read a picked content URI and ship it through the engine. */
private fun sendPickedMedia(
    context: android.content.Context,
    conversationId: String,
    isMesh: Boolean,
    uri: Uri,
) {
    runCatching {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return
        if (bytes.isEmpty() || bytes.size > 8 * 1024 * 1024) return
        val name = queryDisplayName(resolver, uri) ?: "file"
        MeshController.sendMedia(
            peerId = if (isMesh) null else conversationId,
            fileName = name,
            mimeType = mime,
            bytes = bytes,
        )
    }
}

private fun queryDisplayName(
    resolver: android.content.ContentResolver,
    uri: Uri,
): String? {
    resolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
    }
    return null
}
