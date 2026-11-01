package chat.cypher.android.ui.chats

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SentimentSatisfiedAlt
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import chat.cypher.android.core.ChatMessage
import chat.cypher.android.core.DeliveryStatus
import chat.cypher.android.core.Geohash
import chat.cypher.android.core.MeshController
import chat.cypher.android.core.MeshStore
import chat.cypher.android.core.MessageKind
import chat.cypher.android.core.PeerEntry
import chat.cypher.android.core.VoiceNotePlayer
import chat.cypher.android.core.VoiceNoteRecorder
import chat.cypher.android.ui.components.PeerAvatar
import chat.cypher.android.ui.theme.CypherTheme
import chat.cypher.android.ui.theme.GeistMono
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val QUICK_EMOJIS = listOf("👍", "❤️", "🔥", "😂", "⚡", "🙏", "👋", "✅")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatThread(
    conversationKey: String,
    onBack: () -> Unit,
    onOpenSecurityVerification: (String) -> Unit,
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val palette = CypherTheme.colors
    val conversations by MeshStore.conversations.collectAsState()
    val peers by MeshStore.peers.collectAsState()
    val knownPeers by MeshStore.knownPeers.collectAsState()
    val drafts by MeshStore.drafts.collectAsState()
    val verifiedPeers by MeshStore.verifiedPeers.collectAsState()
    val bookmarkedChannels by MeshStore.bookmarkedChannels.collectAsState()
    val voicePlayback by VoiceNotePlayer.state.collectAsState()

    val messages = conversations[conversationKey].orEmpty()
    val isChannel = conversationKey.startsWith("#")
    val peer = if (!isChannel) {
        peers.firstOrNull { it.peerKeyHex.equals(conversationKey, ignoreCase = true) }
    } else {
        null
    }
    val knownPeer = if (!isChannel) {
        knownPeers[conversationKey.lowercase(Locale.US)]
    } else {
        null
    }
    val title = if (isChannel) {
        MeshController.resolveConversationTitle(conversationKey)
    } else {
        peer?.nickname ?: knownPeer?.nickname ?: MeshStore.resolvePeerNickname(conversationKey)
    }
    val isVerified = !isChannel && (
        conversationKey.lowercase(Locale.US) in verifiedPeers ||
            knownPeer?.verified == true
        )

    var inputText by remember(conversationKey) {
        mutableStateOf(drafts[conversationKey].orEmpty())
    }
    var replyTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var selectedMessageForActions by remember { mutableStateOf<ChatMessage?>(null) }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showTopMenu by remember { mutableStateOf(false) }
    var showSearchInChat by remember { mutableStateOf(false) }
    var inChatQuery by remember { mutableStateOf("") }
    var showChannelPasswordDialog by remember { mutableStateOf(false) }
    var previewImagePath by remember { mutableStateOf<String?>(null) }

    // Voice recording state
    val recorder = remember { VoiceNoteRecorder(context) }
    var isRecordingVoice by remember { mutableStateOf(false) }
    var recordingElapsedMs by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) {
        onDispose {
            if (isRecordingVoice) {
                recorder.cancel()
            }
        }
    }

    LaunchedEffect(isRecordingVoice) {
        val startedAt = System.currentTimeMillis()
        while (isRecordingVoice) {
            recordingElapsedMs = System.currentTimeMillis() - startedAt
            delay(200L)
        }
        recordingElapsedMs = 0L
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val file = recorder.start()
            if (file != null) {
                isRecordingVoice = true
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) {
            MeshController.sendMediaAttachment(context, uri, isImage = true)
        }
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            MeshController.sendMediaAttachment(context, uri, isImage = false)
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        MeshController.sendApproximateLocation(context)
    }

    val listState = rememberLazyListState()

    LaunchedEffect(conversationKey, messages.size) {
        MeshStore.markConversationRead(conversationKey)
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    val displayedMessages = remember(messages, showSearchInChat, inChatQuery) {
        if (!showSearchInChat || inChatQuery.isBlank()) {
            messages
        } else {
            val q = inChatQuery.trim().lowercase(Locale.US)
            messages.filter {
                it.text.lowercase(Locale.US).contains(q) ||
                    it.senderLabel.lowercase(Locale.US).contains(q)
            }
        }
    }

    val commandSuggestions = remember(inputText) {
        MeshController.filterCommandSuggestions(inputText)
    }

    Scaffold(
        containerColor = palette.bgPrimary,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.bgSecondary)
                    .statusBarsPadding(),
            ) {
                ChatThreadHeader(
                    conversationKey = conversationKey,
                    title = title,
                    isChannel = isChannel,
                    peer = peer,
                    connectedPeerCount = peers.count { it.connected },
                    isVerified = isVerified,
                    isBookmarked = isChannel && conversationKey in bookmarkedChannels,
                    showMenu = showTopMenu,
                    onToggleMenu = { showTopMenu = it },
                    onBack = onBack,
                    onOpenVerify = {
                        if (!isChannel) {
                            onOpenSecurityVerification(conversationKey)
                        } else {
                            onOpenSecurityVerification(peers.firstOrNull()?.peerKeyHex.orEmpty())
                        }
                    },
                    onToggleSearch = {
                        showSearchInChat = !showSearchInChat
                        if (!showSearchInChat) inChatQuery = ""
                    },
                    onToggleBookmarkChannel = {
                        if (isChannel) {
                            MeshStore.toggleBookmarkChannel(conversationKey)
                        }
                    },
                    onOpenChannelPasswordDialog = {
                        showChannelPasswordDialog = true
                    },
                    onComputeRoute = {
                        if (!isChannel) {
                            MeshController.sendMessage("/route $conversationKey")
                        }
                    },
                    onExportTranscript = {
                        val transcript = MeshController.exportConversationTranscript(conversationKey)
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Cipher Chat Transcript — $title")
                            putExtra(Intent.EXTRA_TEXT, transcript)
                        }
                        context.startActivity(Intent.createChooser(share, "Export Transcript"))
                    },
                    onClearHistory = {
                        MeshStore.clearConversation(conversationKey)
                    },
                    onBlockPeer = {
                        if (!isChannel) {
                            MeshStore.toggleBlockPeer(conversationKey)
                            onBack()
                        }
                    },
                )

                AnimatedVisibility(visible = showSearchInChat) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.surface)
                            .border(1.dp, palette.borderSubtle, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            tint = palette.textMuted,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        BasicTextField(
                            value = inChatQuery,
                            onValueChange = { inChatQuery = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = palette.textPrimary),
                            cursorBrush = SolidColor(palette.accent),
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close search",
                            tint = palette.textSecondary,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable {
                                    inChatQuery = ""
                                    showSearchInChat = false
                                },
                        )
                    }
                }

                HorizontalDivider(color = palette.borderSubtle, thickness = 0.8.dp)
            }
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.bgPrimary)
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                // Slash Command Autocomplete Popup
                AnimatedVisibility(visible = commandSuggestions.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(palette.surfaceElevated)
                            .border(1.dp, palette.borderSubtle, RoundedCornerShape(14.dp))
                            .padding(vertical = 4.dp),
                    ) {
                        commandSuggestions.take(5).forEach { spec ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        inputText = "${spec.command} "
                                        MeshStore.updateDraft(conversationKey, inputText)
                                    }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = spec.syntax,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontFamily = GeistMono,
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                    color = palette.accentLight,
                                )
                                Text(
                                    text = spec.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 10.dp),
                                )
                            }
                        }
                    }
                }

                // Reply Banner
                AnimatedVisibility(visible = replyTarget != null) {
                    replyTarget?.let { target ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(palette.surface)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.Reply,
                                    contentDescription = null,
                                    tint = palette.accentLight,
                                    modifier = Modifier.size(18.dp),
                                )
                                Column {
                                    Text(
                                        text = "Replying to ${target.senderLabel}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.accentLight,
                                    )
                                    Text(
                                        text = target.text,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = palette.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            IconButton(
                                onClick = { replyTarget = null },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Cancel reply",
                                    tint = palette.textSecondary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }

                // Quick Emoji Strip
                AnimatedVisibility(visible = showEmojiPicker) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(palette.surface)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        QUICK_EMOJIS.forEach { emoji ->
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(palette.surfaceElevated)
                                    .clickable {
                                        inputText += emoji
                                        MeshStore.updateDraft(conversationKey, inputText)
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(text = emoji, fontSize = 18.sp)
                            }
                        }
                    }
                }

                // Active Voice Note Recording Banner
                AnimatedVisibility(visible = isRecordingVoice) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF28193D))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(palette.danger),
                            )
                            Text(
                                text = "Recording voice note • ${formatRecordingElapsed(recordingElapsedMs)}",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    recorder.cancel()
                                    isRecordingVoice = false
                                },
                            ) {
                                Text("Cancel", color = palette.textSecondary)
                            }
                            Button(
                                onClick = {
                                    val result = recorder.stop()
                                    isRecordingVoice = false
                                    if (result != null) {
                                        MeshController.sendVoiceNote(result.file, result.durationMs)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = palette.accent),
                                shape = RoundedCornerShape(50),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Stop,
                                    contentDescription = "Stop & Send",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Send", color = Color.White)
                            }
                        }
                    }
                }

                // Composer Bar: ( + ) [ Type a message... 😊 ] ( 🎤 / ➤ )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Left Purple '+' Circle Button
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF6B42E8), Color(0xFF4C2BB8)),
                                ),
                            )
                            .border(1.dp, palette.accentLight.copy(alpha = 0.35f), CircleShape)
                            .clickable { showAttachmentSheet = true }
                            .testTag("chat_attach_button"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "Attach media or location",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }

                    // Rounded Text Input Pill with trailing Smiley icon
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(palette.surface)
                            .border(1.dp, palette.borderSubtle, RoundedCornerShape(22.dp))
                            .padding(start = 16.dp, end = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            if (inputText.isEmpty()) {
                                Text(
                                    text = "Type a message or /command...",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5.sp),
                                    color = palette.textMuted,
                                )
                            }
                            BasicTextField(
                                value = inputText,
                                onValueChange = {
                                    inputText = it
                                    MeshStore.updateDraft(conversationKey, it)
                                },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = palette.textPrimary,
                                    fontSize = 14.5.sp,
                                ),
                                cursorBrush = SolidColor(palette.accent),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(
                                    onSend = {
                                        if (inputText.isNotBlank()) {
                                            MeshController.sendMessage(
                                                text = inputText,
                                                replyToMessageId = replyTarget?.id,
                                                replyPreview = replyTarget?.text,
                                            )
                                            inputText = ""
                                            replyTarget = null
                                            MeshStore.updateDraft(conversationKey, "")
                                        }
                                    },
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("chat_message_input"),
                            )
                        }

                        Icon(
                            imageVector = Icons.Rounded.SentimentSatisfiedAlt,
                            contentDescription = "Emoji",
                            tint = if (showEmojiPicker) palette.accentLight else palette.textSecondary,
                            modifier = Modifier
                                .size(22.dp)
                                .clickable { showEmojiPicker = !showEmojiPicker }
                                .testTag("chat_emoji_button"),
                        )
                    }

                    // Right Purple Send Circle Button
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF9366FF), palette.accent),
                                ),
                            )
                            .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                            .clickable {
                                if (inputText.isNotBlank()) {
                                    MeshController.sendMessage(
                                        text = inputText,
                                        replyToMessageId = replyTarget?.id,
                                        replyPreview = replyTarget?.text,
                                    )
                                    inputText = ""
                                    replyTarget = null
                                    MeshStore.updateDraft(conversationKey, "")
                                }
                            }
                            .testTag("chat_send_button"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = "Send message",
                            tint = Color.White,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF121423), palette.bgPrimary),
                    ),
                )
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Top Date Pill
            item(key = "today_header_pill") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFF1D2034))
                            .border(1.dp, palette.borderSubtle, RoundedCornerShape(50))
                            .padding(horizontal = 16.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = "Today",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp,
                            ),
                            color = palette.textSecondary,
                        )
                    }
                }
            }

            if (displayedMessages.isEmpty()) {
                item(key = "empty_thread_notice") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 36.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .background(palette.surface.copy(alpha = 0.7f))
                                .border(1.dp, palette.borderSubtle, RoundedCornerShape(18.dp))
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = null,
                                tint = palette.accentLight,
                                modifier = Modifier.size(22.dp),
                            )
                            Text(
                                text = if (isChannel) {
                                    "Offline Bluetooth LE Mesh Channel ($conversationKey)"
                                } else {
                                    "Noise XX End-to-End Encrypted Session"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = palette.textPrimary,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                text = if (isChannel) {
                                    "Messages broadcast across nearby peers up to ${MeshStore.maxTtlHops.value} hops. Type /help for IRC commands."
                                } else {
                                    "Messages are encrypted with Noise_XX_25519_ChaChaPoly_BLAKE2s. If the peer is offline, messages queue in Store & Forward."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textSecondary,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }

            items(displayedMessages, key = { it.id }) { msg ->
                if (msg.kind == MessageKind.SYSTEM) {
                    SystemNoticePill(message = msg)
                } else {
                    CipherMessageBubbleItem(
                        message = msg,
                        isChannel = isChannel,
                        voicePlayback = voicePlayback,
                        onLongClick = { selectedMessageForActions = msg },
                        onToggleReaction = { emoji ->
                            MeshStore.toggleMessageReaction(conversationKey, msg.id, emoji)
                        },
                        onToggleVoicePlayback = {
                            VoiceNotePlayer.togglePlayback(msg.attachmentPath)
                        },
                        onOpenImagePreview = { path ->
                            previewImagePath = path
                        },
                        onClickLocation = { coords ->
                            runCatching {
                                val geoUri = Uri.parse("geo:0,0?q=${Uri.encode(coords)}")
                                val mapIntent = Intent(Intent.ACTION_VIEW, geoUri)
                                context.startActivity(mapIntent)
                            }.onFailure {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("location", coords))
                            }
                        },
                    )
                }
            }
        }
    }

    if (selectedMessageForActions != null) {
        val target = selectedMessageForActions!!
        MessageContextMenuSheet(
            message = target,
            onDismiss = { selectedMessageForActions = null },
            onReply = {
                replyTarget = target
                selectedMessageForActions = null
            },
            onReact = { emoji ->
                MeshStore.toggleMessageReaction(conversationKey, target.id, emoji)
                selectedMessageForActions = null
            },
            onToggleStar = {
                MeshStore.toggleStarMessage(conversationKey, target.id)
                selectedMessageForActions = null
            },
            onCopy = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("cipher_message", target.text))
                selectedMessageForActions = null
            },
            onDelete = {
                MeshStore.deleteMessage(conversationKey, target.id)
                selectedMessageForActions = null
            },
        )
    }

    if (showAttachmentSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAttachmentSheet = false },
            containerColor = palette.surfaceElevated,
            contentColor = palette.textPrimary,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Share over BLE Mesh",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.textPrimary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )

                AttachmentSheetItem(
                    icon = Icons.Rounded.LocationOn,
                    title = "Send Approximate Location",
                    subtitle = "Share your real coarse GPS coordinates & geohash",
                    onClick = {
                        showAttachmentSheet = false
                        if (chat.cypher.android.core.LocationChannelResolver.hasLocationPermission(context)) {
                            MeshController.sendApproximateLocation(context)
                        } else {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                        }
                    },
                )
                AttachmentSheetItem(
                    icon = Icons.Rounded.Mic,
                    title = "Record Voice Message",
                    subtitle = "Record an AAC voice note over Bluetooth mesh",
                    onClick = {
                        showAttachmentSheet = false
                        val hasAudioPerm = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasAudioPerm) {
                            val started = recorder.start()
                            if (started != null) isRecordingVoice = true
                        } else {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                )
                AttachmentSheetItem(
                    icon = Icons.Rounded.Image,
                    title = "Photo from Gallery",
                    subtitle = "Pick an image using Android Photo Picker",
                    onClick = {
                        showAttachmentSheet = false
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                )
                AttachmentSheetItem(
                    icon = Icons.Rounded.AttachFile,
                    title = "Document or File",
                    subtitle = "Share a binary file over multi-hop BLE fragments",
                    onClick = {
                        showAttachmentSheet = false
                        documentPickerLauncher.launch("*/*")
                    },
                )
            }
        }
    }

    if (showChannelPasswordDialog && isChannel) {
        var passwordValue by remember {
            mutableStateOf(MeshStore.getChannelPassword(conversationKey).orEmpty())
        }
        AlertDialog(
            onDismissRequest = { showChannelPasswordDialog = false },
            containerColor = palette.surfaceElevated,
            title = {
                Text("Channel Password ($conversationKey)", color = palette.textPrimary)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Derives a 256-bit AES-GCM key via PBKDF2-HMAC-SHA256 (60,000 iterations). Leave empty to remove password.",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary,
                    )
                    OutlinedTextField(
                        value = passwordValue,
                        onValueChange = { passwordValue = it },
                        singleLine = true,
                        label = { Text("Channel password") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = palette.accent,
                            unfocusedBorderColor = palette.borderSubtle,
                            focusedTextColor = palette.textPrimary,
                            unfocusedTextColor = palette.textPrimary,
                        ),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showChannelPasswordDialog = false
                        if (passwordValue.isBlank()) {
                            MeshController.sendMessage("/pass")
                        } else {
                            MeshController.sendMessage("/pass $passwordValue")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.accent),
                ) {
                    Text("Save", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showChannelPasswordDialog = false }) {
                    Text("Cancel", color = palette.textSecondary)
                }
            },
        )
    }

    if (previewImagePath != null) {
        val path = previewImagePath!!
        val bmp = remember(path) {
            runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
        }
        AlertDialog(
            onDismissRequest = { previewImagePath = null },
            containerColor = palette.surfaceElevated,
            title = { Text("Image Attachment", color = palette.textPrimary) },
            text = {
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Attachment preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp)),
                    )
                } else {
                    Text("Image file path: $path", color = palette.textSecondary)
                }
            },
            confirmButton = {
                TextButton(onClick = { previewImagePath = null }) {
                    Text("Close", color = palette.accentLight)
                }
            },
        )
    }
}

@Composable
private fun ChatThreadHeader(
    conversationKey: String,
    title: String,
    isChannel: Boolean,
    peer: PeerEntry?,
    connectedPeerCount: Int,
    isVerified: Boolean,
    isBookmarked: Boolean,
    showMenu: Boolean,
    onToggleMenu: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenVerify: () -> Unit,
    onToggleSearch: () -> Unit,
    onToggleBookmarkChannel: () -> Unit,
    onOpenChannelPasswordDialog: () -> Unit,
    onComputeRoute: () -> Unit,
    onExportTranscript: () -> Unit,
    onClearHistory: () -> Unit,
    onBlockPeer: () -> Unit,
) {
    val palette = CypherTheme.colors
    val subtitle = when {
        isChannel && conversationKey == MeshStore.PUBLIC_CONVERSATION ->
            if (connectedPeerCount > 0) {
                "Bluetooth LE Mesh • $connectedPeerCount peer(s) in range"
            } else {
                "Bluetooth LE Mesh • Scanning"
            }
        isChannel && MeshStore.isChannelPasswordProtected(conversationKey) ->
            "AES-256-GCM Encrypted • $conversationKey"
        isChannel && Geohash.isValid(conversationKey.removePrefix("#")) -> {
            val p = conversationKey.removePrefix("#").length
            "Geohash Channel • ${Geohash.approximateRangeLabel(p)}"
        }
        isChannel -> "Mesh Channel • $conversationKey"
        peer?.connected == true && peer.sessionEstablished ->
            "Nearby • Encrypted (${peer.hops} hop${if (peer.hops == 1) "" else "s"})"
        peer?.connected == true ->
            "Nearby • ${peer.hops} hop${if (peer.hops == 1) "" else "s"}"
        else -> "Offline • Store & Forward queued"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpenVerify),
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(40.dp)
                    .testTag("chat_back_button"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = palette.textPrimary,
                )
            }

            PeerAvatar(
                peerKeyHex = conversationKey,
                nickname = title,
                size = 40.dp,
                connected = peer?.connected ?: false,
                sessionEstablished = peer?.sessionEstablished ?: false,
                isChannel = isChannel,
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.5.sp,
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
                            modifier = Modifier.size(15.dp),
                        )
                    } else if (isChannel && MeshStore.isChannelPasswordProtected(conversationKey)) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = "Encrypted Channel",
                            tint = palette.accentLight,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Box {
            IconButton(
                onClick = { onToggleMenu(true) },
                modifier = Modifier
                    .size(40.dp)
                    .testTag("chat_thread_menu_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "Thread options",
                    tint = palette.textPrimary,
                )
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { onToggleMenu(false) },
                containerColor = palette.surfaceElevated,
            ) {
                DropdownMenuItem(
                    text = { Text("Security Verification", color = palette.textPrimary) },
                    leadingIcon = {
                        Icon(Icons.Rounded.VerifiedUser, contentDescription = null, tint = palette.success)
                    },
                    onClick = {
                        onToggleMenu(false)
                        onOpenVerify()
                    },
                )
                if (!isChannel) {
                    DropdownMenuItem(
                        text = { Text("Show Mesh Route", color = palette.textPrimary) },
                        leadingIcon = {
                            Icon(Icons.Rounded.Route, contentDescription = null, tint = palette.accentLight)
                        },
                        onClick = {
                            onToggleMenu(false)
                            onComputeRoute()
                        },
                    )
                }
                if (isChannel) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (isBookmarked) "Remove Bookmark" else "Bookmark Channel",
                                color = palette.textPrimary,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                if (isBookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                                contentDescription = null,
                                tint = palette.accentLight,
                            )
                        },
                        onClick = {
                            onToggleMenu(false)
                            onToggleBookmarkChannel()
                        },
                    )
                    if (conversationKey != MeshStore.PUBLIC_CONVERSATION) {
                        DropdownMenuItem(
                            text = { Text("Channel Password (AES-GCM)", color = palette.textPrimary) },
                            leadingIcon = {
                                Icon(Icons.Rounded.Lock, contentDescription = null, tint = palette.accentLight)
                            },
                            onClick = {
                                onToggleMenu(false)
                                onOpenChannelPasswordDialog()
                            },
                        )
                    }
                }
                DropdownMenuItem(
                    text = { Text("Search in Chat", color = palette.textPrimary) },
                    leadingIcon = {
                        Icon(Icons.Rounded.Search, contentDescription = null, tint = palette.textSecondary)
                    },
                    onClick = {
                        onToggleMenu(false)
                        onToggleSearch()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Export Transcript", color = palette.textPrimary) },
                    leadingIcon = {
                        Icon(Icons.Rounded.IosShare, contentDescription = null, tint = palette.textSecondary)
                    },
                    onClick = {
                        onToggleMenu(false)
                        onExportTranscript()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Clear Chat History", color = palette.textPrimary) },
                    leadingIcon = {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = palette.textSecondary)
                    },
                    onClick = {
                        onToggleMenu(false)
                        onClearHistory()
                    },
                )
                if (!isChannel) {
                    DropdownMenuItem(
                        text = { Text("Block Peer", color = palette.danger) },
                        leadingIcon = {
                            Icon(Icons.Rounded.Block, contentDescription = null, tint = palette.danger)
                        },
                        onClick = {
                            onToggleMenu(false)
                            onBlockPeer()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SystemNoticePill(message: ChatMessage) {
    val palette = CypherTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF1A1D30))
                .border(1.dp, palette.borderSubtle, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = GeistMono,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                ),
                color = palette.textSecondary,
                textAlign = TextAlign.Start,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CipherMessageBubbleItem(
    message: ChatMessage,
    isChannel: Boolean,
    voicePlayback: VoiceNotePlayer.PlaybackState,
    onLongClick: () -> Unit,
    onToggleReaction: (String) -> Unit,
    onToggleVoicePlayback: () -> Unit,
    onOpenImagePreview: (String) -> Unit,
    onClickLocation: (String) -> Unit,
) {
    val palette = CypherTheme.colors
    val isOutgoing = message.outgoing
    val timeFormatted = remember(message.timestampMs) {
        SimpleDateFormat("h:mm a", Locale.US).format(Date(message.timestampMs))
    }

    val bubbleShape = if (isOutgoing) {
        RoundedCornerShape(
            topStart = 20.dp,
            topEnd = 20.dp,
            bottomStart = 20.dp,
            bottomEnd = 6.dp,
        )
    } else {
        RoundedCornerShape(
            topStart = 20.dp,
            topEnd = 20.dp,
            bottomStart = 6.dp,
            bottomEnd = 20.dp,
        )
    }

    val bubbleBrush = if (isOutgoing) {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFF8352FD),
                Color(0xFF6B39F4),
            ),
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                palette.bubbleIncoming,
                Color(0xFF191C2E),
            ),
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start,
    ) {
        if (isChannel && !isOutgoing) {
            Text(
                text = "@${message.senderLabel}",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.5.sp,
                ),
                color = palette.accentLight,
                modifier = Modifier.padding(start = 6.dp, bottom = 3.dp),
            )
        }

        Box(
            modifier = Modifier
                .widthIn(min = 120.dp, max = 296.dp)
                .clip(bubbleShape)
                .background(bubbleBrush)
                .border(
                    width = if (isOutgoing) 0.dp else 1.dp,
                    color = if (isOutgoing) Color.Transparent else palette.borderSubtle,
                    shape = bubbleShape,
                )
                .combinedClickable(
                    onClick = {
                        when (message.kind) {
                            MessageKind.VOICE_NOTE -> onToggleVoicePlayback()
                            MessageKind.IMAGE -> message.attachmentPath?.let(onOpenImagePreview)
                            MessageKind.LOCATION -> onClickLocation(message.attachmentMeta ?: message.text)
                            else -> Unit
                        }
                    },
                    onLongClick = onLongClick,
                )
                .padding(horizontal = 15.dp, vertical = 11.dp)
                .testTag("message_bubble_${message.id}"),
        ) {
            Column {
                if (!message.replyPreview.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.22f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = message.replyPreview,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                when (message.kind) {
                    MessageKind.LOCATION -> {
                        val lines = message.text.lines()
                        val titleLine = lines.firstOrNull() ?: "Location (approx.)"
                        val coordLine = lines.getOrNull(1) ?: message.attachmentMeta.orEmpty()
                        val geohashLine = lines.getOrNull(2).orEmpty()

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.LocationOn,
                                    contentDescription = "Location",
                                    tint = Color.White,
                                    modifier = Modifier.size(19.dp),
                                )
                            }
                            Column {
                                Text(
                                    text = titleLine,
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                    ),
                                    color = Color.White,
                                )
                                if (coordLine.isNotEmpty()) {
                                    Text(
                                        text = coordLine,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 13.sp,
                                        ),
                                        color = Color.White.copy(alpha = 0.92f),
                                    )
                                }
                                if (geohashLine.isNotEmpty()) {
                                    Text(
                                        text = geohashLine,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = GeistMono,
                                            fontSize = 11.5.sp,
                                        ),
                                        color = Color.White.copy(alpha = 0.78f),
                                    )
                                }
                            }
                        }
                    }

                    MessageKind.VOICE_NOTE -> {
                        val isThisPlaying = voicePlayback.activePath != null &&
                            voicePlayback.activePath == message.attachmentPath &&
                            voicePlayback.isPlaying
                        val progress = if (voicePlayback.activePath == message.attachmentPath) {
                            voicePlayback.progressFraction
                        } else {
                            0f
                        }
                        val bars = remember(message.attachmentPath, message.id) {
                            VoiceNotePlayer.extractWaveformBars(
                                filePath = message.attachmentPath,
                                seedText = message.id,
                                barCount = 24,
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(vertical = 2.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.20f))
                                    .clickable { onToggleVoicePlayback() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = if (isThisPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = if (isThisPlaying) "Pause voice note" else "Play voice note",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp),
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(24.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.5.dp),
                                ) {
                                    bars.forEachIndexed { idx, heightFrac ->
                                        val barPassed = (idx.toFloat() / bars.size.toFloat()) <= progress && isThisPlaying
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height((22f * heightFrac).dp)
                                                .clip(RoundedCornerShape(2.dp))
                                                .background(
                                                    if (barPassed) {
                                                        Color.White
                                                    } else {
                                                        Color.White.copy(alpha = 0.45f)
                                                    },
                                                ),
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = message.attachmentMeta ?: message.text,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                    color = Color.White.copy(alpha = 0.85f),
                                )
                            }
                        }
                    }

                    MessageKind.IMAGE -> {
                        val bitmap = remember(message.attachmentPath) {
                            val path = message.attachmentPath
                            if (!path.isNullOrBlank() && File(path).exists()) {
                                runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
                            } else {
                                null
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = message.text,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(150.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Image,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = message.attachmentMeta ?: message.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White,
                                )
                            }
                        }
                    }

                    MessageKind.FILE -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.White.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Description,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Column {
                                Text(
                                    text = message.text,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Medium,
                                    ),
                                    color = Color.White,
                                )
                                if (!message.attachmentMeta.isNullOrBlank()) {
                                    Text(
                                        text = message.attachmentMeta,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.78f),
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 14.5.sp,
                                lineHeight = 20.sp,
                            ),
                            color = palette.textPrimary,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Timestamp + Delivery Checkmarks Row
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (message.starred) {
                        Icon(
                            imageVector = Icons.Rounded.Star,
                            contentDescription = "Starred",
                            tint = Color(0xFFFBBF24),
                            modifier = Modifier.size(12.dp),
                        )
                    }
                    Text(
                        text = timeFormatted,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                        ),
                        color = if (isOutgoing) Color.White.copy(alpha = 0.78f) else palette.textSecondary,
                    )
                    if (isOutgoing) {
                        val deliveryIcon = when (message.delivery) {
                            DeliveryStatus.QUEUED -> Icons.Rounded.Schedule
                            DeliveryStatus.SENT -> Icons.Rounded.Done
                            DeliveryStatus.DELIVERED, DeliveryStatus.READ -> Icons.Rounded.DoneAll
                        }
                        Icon(
                            imageVector = deliveryIcon,
                            contentDescription = message.delivery.name,
                            tint = Color.White.copy(alpha = 0.88f),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }

        // Reactions Row if any
        if (message.reactions.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                message.reactions.forEach { (emoji, count) ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(palette.surfaceElevated)
                            .border(1.dp, palette.borderSubtle, RoundedCornerShape(50))
                            .clickable { onToggleReaction(emoji) }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = if (count > 1) "$emoji $count" else emoji,
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textPrimary,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageContextMenuSheet(
    message: ChatMessage,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onReact: (String) -> Unit,
    onToggleStar: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    val palette = CypherTheme.colors
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.surfaceElevated,
        contentColor = palette.textPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                listOf("👍", "❤️", "🔥", "😂", "⚡", "🙏").forEach { emoji ->
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(palette.surface)
                            .border(1.dp, palette.borderSubtle, CircleShape)
                            .clickable { onReact(emoji) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = emoji, fontSize = 20.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            AttachmentSheetItem(
                icon = Icons.AutoMirrored.Rounded.Reply,
                title = "Reply",
                subtitle = "Quote this message in your response",
                onClick = onReply,
            )
            AttachmentSheetItem(
                icon = if (message.starred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                title = if (message.starred) "Remove Star" else "Star Message",
                subtitle = "Keep important mesh coordinates or notes saved",
                onClick = onToggleStar,
            )
            AttachmentSheetItem(
                icon = Icons.Rounded.ContentCopy,
                title = "Copy Text",
                subtitle = "Copy message contents to clipboard",
                onClick = onCopy,
            )
            AttachmentSheetItem(
                icon = Icons.Rounded.DeleteOutline,
                title = "Delete Message",
                subtitle = "Remove from local device storage",
                onClick = onDelete,
                tint = palette.danger,
            )
        }
    }
}

@Composable
private fun AttachmentSheetItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    tint: Color = CypherTheme.colors.textPrimary,
    onClick: () -> Unit,
) {
    val palette = CypherTheme.colors
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = palette.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            Column {
                Text(text = title, style = MaterialTheme.typography.titleSmall, color = tint)
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
            }
        }
    }
}

private fun formatRecordingElapsed(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val m = totalSec / 60L
    val s = totalSec % 60L
    return "%d:%02d".format(m, s)
}
