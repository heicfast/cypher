package chat.cypher.android.ui.chats

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.NetworkPing
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.SentimentSatisfiedAlt
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.cypher.android.core.AttachmentKind
import chat.cypher.android.core.ChatMessage
import chat.cypher.android.core.MeshController
import chat.cypher.android.core.ReplyReference
import chat.cypher.android.core.SlashCommandOutcome
import chat.cypher.android.ui.components.DeliveryReceiptIcon
import chat.cypher.android.ui.components.PeerAvatar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val QUICK_EMOJIS = listOf("👋", "👍", "🔥", "❤️", "😂", "🎉", "✅", "📍")

@Composable
fun ChatThreadScreen(
    controller: MeshController,
    conversationKey: String,
    onBack: () -> Unit,
    onOpenVerification: (String?) -> Unit,
    onOpenMeshNetwork: () -> Unit = {},
) {
    BackHandler(onBack = onBack)

    val conversations by controller.store.conversations.collectAsState()
    val drafts by controller.store.drafts.collectAsState()
    val messages = conversations[conversationKey].orEmpty()
    val isChannel = conversationKey.startsWith("#")
    val title = controller.resolveConversationTitle(conversationKey)

    var inputText by remember(conversationKey) {
        mutableStateOf(drafts[conversationKey].orEmpty())
    }
    var replyTarget by remember(conversationKey) { mutableStateOf<ReplyReference?>(null) }
    var showAttachmentBar by remember { mutableStateOf(false) }
    var showEmojiBar by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) {
            controller.sendMedia(
                recipientHex = if (isChannel) null else conversationKey,
                uri = uri,
                forcedKind = AttachmentKind.IMAGE,
            )
            showAttachmentBar = false
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            controller.sendMedia(
                recipientHex = if (isChannel) null else conversationKey,
                uri = uri,
                forcedKind = AttachmentKind.FILE,
            )
            showAttachmentBar = false
        }
    }

    val sendAction = {
        val trimmed = inputText.trim()
        if (trimmed.isNotEmpty()) {
            val slashOutcome = controller.executeSlashCommand(
                rawInput = trimmed,
                conversationKey = conversationKey,
            )
            when (slashOutcome) {
                is SlashCommandOutcome.NotASlashCommand -> {
                    if (isChannel) {
                        controller.sendChannelMessage(conversationKey, trimmed, replyTarget)
                    } else {
                        controller.sendPrivateMessage(conversationKey, trimmed, replyTarget)
                    }
                }
                is SlashCommandOutcome.OpenVerificationSheet -> {
                    onOpenVerification(slashOutcome.peerHex ?: conversationKey)
                }
                is SlashCommandOutcome.Handled -> Unit
            }
            inputText = ""
            replyTarget = null
            showEmojiBar = false
            showAttachmentBar = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0E17))
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .imePadding(),
    ) {
        // Top Header Bar matching Screen 3 (<- Avatar Rick / Nearby • Encrypted  ⋮)
        ChatThreadHeader(
            conversationKey = conversationKey,
            title = title,
            subtitle = if (isChannel) "Bluetooth Mesh • Encrypted" else "Nearby • Encrypted",
            onBack = onBack,
            onHeaderClick = {
                onOpenVerification(if (isChannel) null else conversationKey)
            },
            showMenu = showMenu,
            onToggleMenu = { showMenu = it },
            onVerifyIdentity = {
                showMenu = false
                onOpenVerification(if (isChannel) null else conversationKey)
            },
            onShareApproxLocation = {
                showMenu = false
                controller.sendApproximateLocation(conversationKey)
            },
            onOpenMeshNetwork = {
                showMenu = false
                onOpenMeshNetwork()
            },
            onSendPing = {
                showMenu = false
                if (isChannel) {
                    controller.announceNow()
                } else {
                    controller.sendMeshPing(conversationKey)
                }
            },
            onClearChat = {
                showMenu = false
                controller.clearConversationMessages(conversationKey)
            },
            onToggleBlock = {
                showMenu = false
                if (!isChannel) {
                    controller.toggleBlocked(conversationKey)
                }
            },
            isChannel = isChannel,
        )

        HorizontalDivider(color = Color(0xFF1B1E30), thickness = 0.8.dp)

        // Messages List with "Today" pill
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("chat_messages_list"),
            contentPadding = PaddingValues(top = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "today_header_pill") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF1D2032))
                            .padding(horizontal = 14.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = "Today",
                            color = Color(0xFFC4C8DC),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            items(
                items = messages,
                key = { it.id },
            ) { message ->
                if (message.isSystem) {
                    SystemNoticePill(text = message.text)
                } else {
                    CipherMessageBubble(
                        message = message,
                        showSenderName = isChannel && !message.isOutgoing,
                        onLongClick = {
                            replyTarget = ReplyReference(
                                messageId = message.id,
                                senderNickname = message.senderNickname,
                                previewText = message.text,
                            )
                        },
                    )
                }
            }
        }

        // Reply banner if active
        AnimatedVisibility(visible = replyTarget != null) {
            replyTarget?.let { ref ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF161929))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Reply,
                        contentDescription = null,
                        tint = Color(0xFFA78BFA),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Replying to ${ref.senderNickname}",
                            color = Color(0xFFA78BFA),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = ref.previewText,
                            color = Color(0xFF9CA3AF),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(
                        onClick = { replyTarget = null },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Cancel reply",
                            tint = Color(0xFF9CA3AF),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }

        // Quick attachment strip when '+' is tapped
        AnimatedVisibility(visible = showAttachmentBar) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF141625))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AttachmentActionChip(
                    icon = Icons.Outlined.MyLocation,
                    label = "Approx. Location",
                    onClick = {
                        controller.sendApproximateLocation(conversationKey)
                        showAttachmentBar = false
                    },
                )
                AttachmentActionChip(
                    icon = Icons.Outlined.Image,
                    label = "Photo",
                    onClick = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                )
                AttachmentActionChip(
                    icon = Icons.Outlined.AttachFile,
                    label = "File",
                    onClick = {
                        filePickerLauncher.launch("*/*")
                    },
                )
            }
        }

        // Quick emoji bar when smiley icon is tapped
        AnimatedVisibility(visible = showEmojiBar) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF141625))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                QUICK_EMOJIS.forEach { emoji ->
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E2236))
                            .clickable {
                                inputText += emoji
                                controller.updateDraft(conversationKey, inputText)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = emoji, fontSize = 18.sp)
                    }
                }
            }
        }

        // Bottom Composer Bar: (+)  [ Type a message...  😊 ]  (➤)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Left purple '+' button
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF7C4DFF), Color(0xFF5B32D6)),
                        )
                    )
                    .clickable {
                        showAttachmentBar = !showAttachmentBar
                        if (showAttachmentBar) showEmojiBar = false
                    }
                    .testTag("chat_attach_button"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Attach or share location",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }

            // Middle dark rounded pill input with trailing emoji icon
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xFF181A28))
                    .border(1.dp, Color(0xFF262A40), RoundedCornerShape(22.dp))
                    .padding(start = 16.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (inputText.isEmpty()) {
                        Text(
                            text = "Type a message...",
                            color = Color(0xFF7D829E),
                            fontSize = 14.5.sp,
                        )
                    }
                    BasicTextField(
                        value = inputText,
                        onValueChange = {
                            inputText = it
                            controller.updateDraft(conversationKey, it)
                        },
                        singleLine = false,
                        maxLines = 4,
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 14.5.sp,
                        ),
                        cursorBrush = SolidColor(Color(0xFF8B5CF6)),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { sendAction() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("chat_message_input"),
                    )
                }

                IconButton(
                    onClick = {
                        showEmojiBar = !showEmojiBar
                        if (showEmojiBar) showAttachmentBar = false
                    },
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("chat_emoji_button"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SentimentSatisfiedAlt,
                        contentDescription = "Insert emoji",
                        tint = Color(0xFF9CA3AF),
                        modifier = Modifier.size(21.dp),
                    )
                }
            }

            // Right purple paper-plane Send button
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF8854FF), Color(0xFF6938EF)),
                        )
                    )
                    .clickable { sendAction() }
                    .testTag("chat_send_button"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = Color.White,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

@Composable
private fun ChatThreadHeader(
    conversationKey: String,
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onHeaderClick: () -> Unit,
    showMenu: Boolean,
    onToggleMenu: (Boolean) -> Unit,
    onVerifyIdentity: () -> Unit,
    onShareApproxLocation: () -> Unit,
    onOpenMeshNetwork: () -> Unit,
    onSendPing: () -> Unit,
    onClearChat: () -> Unit,
    onToggleBlock: () -> Unit,
    isChannel: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(40.dp)
                .testTag("chat_back_button"),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onHeaderClick)
                .padding(horizontal = 4.dp, vertical = 2.dp)
                .testTag("chat_header_identity"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PeerAvatar(
                nickname = title,
                peerIdHex = conversationKey,
                conversationKey = conversationKey,
                size = 40.dp,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 16.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    color = Color(0xFF9CA3AF),
                    fontSize = 12.5.sp,
                    maxLines = 1,
                )
            }
        }

        Box {
            IconButton(
                onClick = { onToggleMenu(true) },
                modifier = Modifier
                    .size(40.dp)
                    .testTag("chat_overflow_button"),
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "Thread options",
                    tint = Color.White,
                    modifier = Modifier.size(21.dp),
                )
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { onToggleMenu(false) },
                containerColor = Color(0xFF1A1D2E),
            ) {
                DropdownMenuItem(
                    text = { Text("Security Verification", color = Color.White) },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.VerifiedUser,
                            contentDescription = null,
                            tint = Color(0xFF4ADE80),
                        )
                    },
                    onClick = onVerifyIdentity,
                )
                DropdownMenuItem(
                    text = { Text("Share Approx. Location", color = Color.White) },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.MyLocation,
                            contentDescription = null,
                            tint = Color(0xFFA78BFA),
                        )
                    },
                    onClick = onShareApproxLocation,
                )
                DropdownMenuItem(
                    text = { Text("Mesh Network", color = Color.White) },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Hub,
                            contentDescription = null,
                            tint = Color(0xFF60A5FA),
                        )
                    },
                    onClick = onOpenMeshNetwork,
                )
                DropdownMenuItem(
                    text = { Text("Send Mesh Ping", color = Color.White) },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.NetworkPing,
                            contentDescription = null,
                            tint = Color(0xFFC4B5FD),
                        )
                    },
                    onClick = onSendPing,
                )
                DropdownMenuItem(
                    text = { Text("Clear Chat", color = Color.White) },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.DeleteSweep,
                            contentDescription = null,
                            tint = Color(0xFF9CA3AF),
                        )
                    },
                    onClick = onClearChat,
                )
                if (!isChannel) {
                    DropdownMenuItem(
                        text = { Text("Block / Unblock Peer", color = Color(0xFFF87171)) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Block,
                                contentDescription = null,
                                tint = Color(0xFFF87171),
                            )
                        },
                        onClick = onToggleBlock,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CipherMessageBubble(
    message: ChatMessage,
    showSenderName: Boolean,
    onLongClick: () -> Unit,
) {
    val isOutgoing = message.isOutgoing
    val isLocationBubble = message.text.startsWith("Location (approx.)")
    val formattedTime = remember(message.timestampMs) {
        SimpleDateFormat("h:mm a", Locale.US).format(Date(message.timestampMs))
    }

    val bubbleShape = if (isOutgoing) {
        RoundedCornerShape(
            topStart = 18.dp,
            topEnd = 18.dp,
            bottomStart = 18.dp,
            bottomEnd = 6.dp,
        )
    } else {
        RoundedCornerShape(
            topStart = 18.dp,
            topEnd = 18.dp,
            bottomEnd = 18.dp,
            bottomStart = 6.dp,
        )
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isOutgoing) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 110.dp, max = 285.dp)
                .clip(bubbleShape)
                .then(
                    if (isOutgoing) {
                        Modifier.background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF8352FD), Color(0xFF6938EF)),
                            )
                        )
                    } else {
                        Modifier.background(Color(0xFF222538))
                    }
                )
                .combinedClickable(
                    onClick = {},
                    onLongClick = onLongClick,
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (showSenderName) {
                Text(
                    text = message.senderNickname,
                    color = Color(0xFFA78BFA),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }

            message.replyTo?.let { reply ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.22f))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    Text(
                        text = reply.senderNickname,
                        color = Color(0xFFD8B4FE),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = reply.previewText,
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (isLocationBubble) {
                val lines = message.text.lines()
                val headerLine = lines.firstOrNull() ?: "Location (approx.)"
                val coordsLine = lines.drop(1).joinToString(" ").ifBlank { "30.3165° N, 78.0322° E" }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.LocationOn,
                            contentDescription = "Location Pin",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Column {
                        Text(
                            text = headerLine,
                            color = Color.White,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = coordsLine,
                            color = Color.White.copy(alpha = 0.95f),
                            fontSize = 14.sp,
                        )
                    }
                }
            } else {
                Text(
                    text = message.text,
                    color = Color.White,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                )
            }

            Spacer(modifier = Modifier.height(3.dp))

            Row(
                modifier = Modifier.align(Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = formattedTime,
                    color = if (isOutgoing) Color(0xFFE2D9FF) else Color(0xFF8E93AD),
                    fontSize = 11.sp,
                )
                if (isOutgoing) {
                    DeliveryReceiptIcon(state = message.delivery)
                }
            }
        }
    }
}

@Composable
private fun SystemNoticePill(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1A1D2E))
                .border(1.dp, Color(0xFF262A40), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 5.dp),
        ) {
            Text(
                text = text,
                color = Color(0xFF9CA3AF),
                fontSize = 11.5.sp,
            )
        }
    }
}

@Composable
private fun AttachmentActionChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1E2135))
            .border(1.dp, Color(0xFF2D3250), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color(0xFFA78BFA),
            modifier = Modifier.size(17.dp),
        )
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
