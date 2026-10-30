package chat.cypher.android.ui.chats

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import chat.cypher.android.core.AttachmentKind
import chat.cypher.android.core.ChatMessage
import chat.cypher.android.core.MediaAttachment
import chat.cypher.android.core.MeshController
import chat.cypher.android.core.MeshStore
import chat.cypher.android.core.PeerIdentity
import chat.cypher.android.core.PeerUiModel
import chat.cypher.android.core.ReplyReference
import chat.cypher.android.core.SlashCommandOutcome
import chat.cypher.android.core.VoiceNoteRecorder
import chat.cypher.android.ui.components.DeliveryReceiptIcon
import chat.cypher.android.ui.components.EncryptionStatusPill
import chat.cypher.android.ui.components.PeerAvatar
import chat.cypher.android.ui.components.SignalBarsBadge
import chat.cypher.android.ui.theme.CypherMonoFontFamily
import chat.cypher.android.ui.theme.LocalCypherPalette
import chat.cypher.android.ui.theme.PeerColors
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val QUICK_REACTIONS = listOf("👍", "❤️", "🔥", "⚡", "😂", "🛡️")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatThreadScreen(
    conversationKey: String,
    peer: PeerUiModel?,
    displayName: String,
    messages: List<ChatMessage>,
    peers: List<PeerUiModel>,
    initialDraft: String,
    isMuted: Boolean,
    onlineMeshCount: Int,
    onBack: () -> Unit,
    onSendText: (text: String, replyTo: ReplyReference?) -> Unit,
    onExecuteSlashCommand: (String) -> SlashCommandOutcome,
    onSendMediaUri: (android.net.Uri, AttachmentKind?) -> Unit,
    onSendVoiceNote: (File, Long) -> Unit,
    onToggleReaction: (messageId: String, emoji: String) -> Unit,
    onPingPeer: () -> Unit,
    onOpenVerification: (PeerUiModel?) -> Unit,
    onToggleMute: () -> Unit,
    onToggleBlock: () -> Unit,
    onClearHistory: () -> Unit,
    onDraftChanged: (String) -> Unit,
) {
    val palette = LocalCypherPalette.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val isBroadcast = conversationKey == MeshStore.BROADCAST_CONVERSATION_KEY

    var inputText by remember(conversationKey) { mutableStateOf(initialDraft) }
    var activeReply by remember(conversationKey) { mutableStateOf<ReplyReference?>(null) }
    var selectedMessageForSheet by remember { mutableStateOf<ChatMessage?>(null) }
    var fullscreenImagePath by remember { mutableStateOf<String?>(null) }
    var showOverflowMenu by remember { mutableStateOf(false) }

    // Voice note recorder state
    val voiceRecorder = remember(context) { VoiceNoteRecorder(context) }
    var isRecordingVoice by remember { mutableStateOf(false) }
    var recordingElapsedMs by remember { mutableLongStateOf(0L) }
    val amplitudeHistory = remember { mutableStateListOf<Float>() }

    DisposableEffect(voiceRecorder) {
        onDispose {
            if (voiceRecorder.isRecording) {
                voiceRecorder.stop(canceled = true)
            }
        }
    }

    LaunchedEffect(isRecordingVoice) {
        if (isRecordingVoice) {
            amplitudeHistory.clear()
            while (isActive && isRecordingVoice) {
                val rawAmp = voiceRecorder.pollAmplitude()
                val normalized = (rawAmp / 18_000f).coerceIn(0.08f, 1.0f)
                amplitudeHistory.add(normalized)
                if (amplitudeHistory.size > 32) {
                    amplitudeHistory.removeAt(0)
                }
                recordingElapsedMs = voiceRecorder.elapsedMs()
                if (recordingElapsedMs >= 60_000L) {
                    val result = voiceRecorder.stop(canceled = false)
                    isRecordingVoice = false
                    if (result != null) {
                        onSendVoiceNote(result.first, result.second)
                    }
                    break
                }
                delay(90L)
            }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val started = voiceRecorder.start()
            if (started != null) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                isRecordingVoice = true
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            onSendMediaUri(uri, AttachmentKind.IMAGE)
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            onSendMediaUri(uri, null)
        }
    }

    BackHandler {
        when {
            fullscreenImagePath != null -> fullscreenImagePath = null
            isRecordingVoice -> {
                voiceRecorder.stop(canceled = true)
                isRecordingVoice = false
            }
            activeReply != null -> activeReply = null
            else -> onBack()
        }
    }

    val listState = rememberLazyListState()

    // Auto-scroll to newest message when messages count increases
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    val showScrollToBottom by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            messages.isNotEmpty() && lastVisible < messages.lastIndex - 2
        }
    }

    // Slash command & @mention autocomplete matching
    val matchingSlashCommands = remember(inputText) {
        val trimmed = inputText.trimStart()
        if (trimmed.startsWith("/") && !trimmed.contains(" ")) {
            MeshController.SLASH_COMMANDS.filter {
                it.command.startsWith(trimmed.lowercase())
            }
        } else {
            emptyList()
        }
    }

    val mentionQuery = remember(inputText) {
        val lastAt = inputText.lastIndexOf('@')
        if (lastAt >= 0 && (lastAt == 0 || inputText[lastAt - 1].isWhitespace())) {
            val token = inputText.substring(lastAt + 1)
            if (!token.contains(' ') && !token.contains('\n')) token.lowercase() else null
        } else {
            null
        }
    }

    val matchingMentionPeers = remember(mentionQuery, peers) {
        if (mentionQuery == null) {
            emptyList()
        } else {
            peers.filter { !it.isBlocked && it.nickname.lowercase().contains(mentionQuery) }.take(5)
        }
    }

    fun submitCurrentMessage() {
        val text = inputText.trim()
        if (text.isEmpty()) return
        if (text.startsWith("/")) {
            when (val outcome = onExecuteSlashCommand(text)) {
                is SlashCommandOutcome.Handled -> {
                    inputText = ""
                    onDraftChanged("")
                    activeReply = null
                    return
                }
                is SlashCommandOutcome.OpenVerificationSheet -> {
                    inputText = ""
                    onDraftChanged("")
                    onOpenVerification(peer)
                    return
                }
                SlashCommandOutcome.NotASlashCommand -> Unit
            }
        }
        onSendText(text, activeReply)
        inputText = ""
        activeReply = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas)
            .windowInsetsPadding(WindowInsets.statusBars)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        // Elevated Thread Header Bar
        Surface(
            color = palette.surface0,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("thread_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to messages",
                            tint = palette.textPrimary,
                        )
                    }

                    PeerAvatar(
                        nickname = displayName,
                        peerIdHex = conversationKey,
                        size = 42.dp,
                        isBroadcastChannel = isBroadcast,
                        isOnline = if (isBroadcast) onlineMeshCount > 0 else peer?.isDirectlyConnected == true,
                        isVerified = peer?.isVerified == true,
                        modifier = Modifier.clickable {
                            if (peer != null) onOpenVerification(peer)
                        },
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                if (peer != null) onOpenVerification(peer)
                            },
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = if (isBroadcast) "#mesh" else displayName,
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            EncryptionStatusPill(
                                sessionState = peer?.sessionState ?: "none",
                                isVerified = peer?.isVerified == true,
                                isBroadcast = isBroadcast,
                                onClick = if (peer != null) {
                                    { onOpenVerification(peer) }
                                } else {
                                    null
                                },
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (isBroadcast) {
                                Text(
                                    text = "$onlineMeshCount nearby nodes · multi-hop broadcast",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.textSecondary,
                                )
                            } else {
                                Text(
                                    text = PeerIdentity.shortId(conversationKey),
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = CypherMonoFontFamily),
                                    color = palette.textSecondary,
                                )
                                if (peer != null) {
                                    SignalBarsBadge(
                                        rssi = peer.rssi,
                                        isDirectlyConnected = peer.isDirectlyConnected,
                                    )
                                }
                            }
                        }
                    }

                    // Header Action Icons
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPingPeer()
                        },
                        modifier = Modifier.testTag("thread_ping_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.NetworkPing,
                            contentDescription = if (isBroadcast) "Announce to mesh" else "Send mesh ping",
                            tint = palette.accent,
                        )
                    }

                    if (!isBroadcast && peer != null) {
                        IconButton(
                            onClick = { onOpenVerification(peer) },
                            modifier = Modifier.testTag("thread_verify_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.VerifiedUser,
                                contentDescription = "Verify safety number",
                                tint = if (peer.isVerified) palette.encryptedGreen else palette.textSecondary,
                            )
                        }
                    }

                    Box {
                        IconButton(
                            onClick = { showOverflowMenu = true },
                            modifier = Modifier.testTag("thread_overflow_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "More options",
                                tint = palette.textSecondary,
                            )
                        }

                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                            containerColor = palette.surface1,
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (isMuted) "Unmute notifications" else "Mute notifications",
                                        color = palette.textPrimary,
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.NotificationsOff,
                                        contentDescription = null,
                                        tint = palette.textSecondary,
                                    )
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    onToggleMute()
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Clear message history",
                                        color = palette.textPrimary,
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.DeleteOutline,
                                        contentDescription = null,
                                        tint = palette.textSecondary,
                                    )
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    onClearHistory()
                                },
                            )
                            if (!isBroadcast) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = if (peer?.isBlocked == true) "Unblock peer" else "Block peer",
                                            color = palette.dangerRed,
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Filled.Block,
                                            contentDescription = null,
                                            tint = palette.dangerRed,
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        onToggleBlock()
                                    },
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = palette.borderSubtle, thickness = 1.dp)
            }
        }

        // Message Timeline Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (messages.isEmpty()) {
                EmptyThreadBanner(
                    isBroadcast = isBroadcast,
                    displayName = displayName,
                    peerIdHex = conversationKey,
                    onPing = onPingPeer,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("thread_messages_list"),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    itemsIndexed(messages, key = { _, msg -> msg.id }) { idx, msg ->
                        val prev = messages.getOrNull(idx - 1)
                        val next = messages.getOrNull(idx + 1)

                        val showDateHeader = prev == null || !isSameCalendarDay(prev.timestampMs, msg.timestampMs)
                        val isFirstInGroup = prev == null ||
                            prev.isSystem ||
                            msg.isSystem ||
                            prev.senderPeerHex != msg.senderPeerHex ||
                            prev.isOutgoing != msg.isOutgoing ||
                            (msg.timestampMs - prev.timestampMs) > 120_000L
                        val isLastInGroup = next == null ||
                            next.isSystem ||
                            msg.isSystem ||
                            next.senderPeerHex != msg.senderPeerHex ||
                            next.isOutgoing != msg.isOutgoing ||
                            (next.timestampMs - msg.timestampMs) > 120_000L

                        if (showDateHeader) {
                            DateHeaderPill(timestampMs = msg.timestampMs)
                        }

                        if (msg.isSystem) {
                            SystemEventPill(text = msg.text)
                        } else {
                            MessageBubbleRow(
                                message = msg,
                                isBroadcast = isBroadcast,
                                isFirstInGroup = isFirstInGroup,
                                isLastInGroup = isLastInGroup,
                                onSwipeReply = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    activeReply = ReplyReference(
                                        messageId = msg.id,
                                        senderNickname = if (msg.isOutgoing) "You" else msg.senderNickname,
                                        previewText = msg.text,
                                    )
                                },
                                onLongPress = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    selectedMessageForSheet = msg
                                },
                                onTapImage = { path -> fullscreenImagePath = path },
                                onQuickToggleReaction = { emoji ->
                                    onToggleReaction(msg.id, emoji)
                                },
                            )
                        }
                    }
                }
            }

            // Floating Scroll-to-Bottom Button
            androidx.compose.animation.AnimatedVisibility(
                visible = showScrollToBottom,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                FloatingActionButton(
                    onClick = {
                        scope.launch {
                            if (messages.isNotEmpty()) {
                                listState.animateScrollToItem(messages.lastIndex)
                            }
                        }
                    },
                    containerColor = palette.surface2,
                    contentColor = palette.accent,
                    shape = CircleShape,
                    modifier = Modifier.size(42.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Scroll to newest message",
                    )
                }
            }
        }

        // Slash Command Autocomplete Popup
        AnimatedVisibility(
            visible = matchingSlashCommands.isNotEmpty(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Surface(
                color = palette.surface1,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = palette.borderStrong,
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    ),
            ) {
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    matchingSlashCommands.forEach { spec ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val replacement = spec.command + " "
                                    inputText = replacement
                                    onDraftChanged(replacement)
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = spec.syntax,
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = CypherMonoFontFamily),
                                color = palette.accent,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = spec.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        // @Mention Autocomplete Popup
        AnimatedVisibility(
            visible = matchingMentionPeers.isNotEmpty(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Surface(
                color = palette.surface1,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = palette.borderStrong,
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    ),
            ) {
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    matchingMentionPeers.forEach { mentionPeer ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val lastAt = inputText.lastIndexOf('@')
                                    if (lastAt >= 0) {
                                        val next = inputText.substring(0, lastAt) + "@${mentionPeer.nickname} "
                                        inputText = next
                                        onDraftChanged(next)
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            PeerAvatar(
                                nickname = mentionPeer.nickname,
                                peerIdHex = mentionPeer.peerIdHex,
                                size = 28.dp,
                                isOnline = mentionPeer.isDirectlyConnected,
                            )
                            Text(
                                text = "@${mentionPeer.nickname}",
                                style = MaterialTheme.typography.titleSmall,
                                color = PeerColors.forPeer(mentionPeer.peerIdHex, palette.isDark),
                            )
                            Text(
                                text = PeerIdentity.shortId(mentionPeer.peerIdHex),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = CypherMonoFontFamily),
                                color = palette.textMuted,
                            )
                        }
                    }
                }
            }
        }

        // Active Reply Banner
        AnimatedVisibility(
            visible = activeReply != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            val reply = activeReply
            if (reply != null) {
                Surface(
                    color = palette.surface1,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(32.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(palette.accent),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Replying to @${reply.senderNickname}",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.accent,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = reply.previewText,
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(
                            onClick = { activeReply = null },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Cancel reply",
                                tint = palette.textMuted,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }

        // Bottom Composer Capsule Bar
        Surface(
            color = palette.surface0,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                HorizontalDivider(color = palette.borderSubtle, thickness = 1.dp)

                if (isRecordingVoice) {
                    VoiceRecordingComposerBar(
                        elapsedMs = recordingElapsedMs,
                        amplitudes = amplitudeHistory,
                        onCancel = {
                            voiceRecorder.stop(canceled = true)
                            isRecordingVoice = false
                        },
                        onSend = {
                            val result = voiceRecorder.stop(canceled = false)
                            isRecordingVoice = false
                            if (result != null) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSendVoiceNote(result.first, result.second)
                            }
                        },
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Image & File attachment buttons
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 4.dp),
                        ) {
                            IconButton(
                                onClick = {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                    )
                                },
                                modifier = Modifier
                                    .size(38.dp)
                                    .testTag("attach_image_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AddPhotoAlternate,
                                    contentDescription = "Attach image",
                                    tint = palette.textSecondary,
                                    modifier = Modifier.size(21.dp),
                                )
                            }

                            IconButton(
                                onClick = {
                                    filePickerLauncher.launch(arrayOf("*/*"))
                                },
                                modifier = Modifier
                                    .size(38.dp)
                                    .testTag("attach_file_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AttachFile,
                                    contentDescription = "Attach file",
                                    tint = palette.textSecondary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }

                        // Text input capsule
                        Surface(
                            color = palette.surface1,
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 46.dp, max = 132.dp)
                                .border(1.dp, palette.borderStrong, RoundedCornerShape(24.dp)),
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (inputText.isEmpty()) {
                                    Text(
                                        text = if (isBroadcast) {
                                            "Broadcast to #mesh or type / for commands…"
                                        } else {
                                            "Encrypted message to $displayName…"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = palette.textMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                BasicTextField(
                                    value = inputText,
                                    onValueChange = {
                                        inputText = it
                                        onDraftChanged(it)
                                    },
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                                        color = palette.textPrimary,
                                    ),
                                    cursorBrush = SolidColor(palette.accent),
                                    maxLines = 5,
                                    keyboardOptions = KeyboardOptions(
                                        capitalization = KeyboardCapitalization.Sentences,
                                        imeAction = ImeAction.Send,
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onSend = { submitCurrentMessage() },
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("chat_composer_input"),
                                )
                            }
                        }

                        // Primary Send OR Voice Note Mic Button
                        val canSend = inputText.trim().isNotEmpty()
                        Box(
                            modifier = Modifier
                                .padding(bottom = 1.dp)
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(
                                    if (canSend) palette.accent else palette.surface2,
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (canSend) palette.accent else palette.borderStrong,
                                    shape = CircleShape,
                                )
                                .clickable {
                                    if (canSend) {
                                        submitCurrentMessage()
                                    } else {
                                        val hasMicPerm = ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.RECORD_AUDIO,
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (hasMicPerm) {
                                            val started = voiceRecorder.start()
                                            if (started != null) {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                isRecordingVoice = true
                                            }
                                        } else {
                                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    }
                                }
                                .testTag(if (canSend) "send_message_button" else "record_voice_note_button"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (canSend) Icons.AutoMirrored.Filled.Send else Icons.Filled.Mic,
                                contentDescription = if (canSend) "Send message" else "Record voice note",
                                tint = if (canSend) palette.accentOnPrimary else palette.accent,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    // Long-press Message Context Bottom Sheet
    val sheetMessage = selectedMessageForSheet
    if (sheetMessage != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { selectedMessageForSheet = null },
            sheetState = sheetState,
            containerColor = palette.surface0,
            contentColor = palette.textPrimary,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Quick Emoji Reaction Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    QUICK_REACTIONS.forEach { emoji ->
                        val isSelected = sheetMessage.reaction == emoji
                        Surface(
                            color = if (isSelected) palette.accentSoft else palette.surface1,
                            shape = CircleShape,
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onToggleReaction(sheetMessage.id, emoji)
                                    selectedMessageForSheet = null
                                }
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) palette.accent else palette.borderSubtle,
                                    shape = CircleShape,
                                ),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(text = emoji, fontSize = 20.sp)
                            }
                        }
                    }
                }

                HorizontalDivider(color = palette.borderSubtle)

                ListItem(
                    headlineContent = { Text("Reply to message", color = palette.textPrimary) },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Reply,
                            contentDescription = null,
                            tint = palette.accent,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = palette.surface0),
                    modifier = Modifier.clickable {
                        activeReply = ReplyReference(
                            messageId = sheetMessage.id,
                            senderNickname = if (sheetMessage.isOutgoing) "You" else sheetMessage.senderNickname,
                            previewText = sheetMessage.text,
                        )
                        selectedMessageForSheet = null
                    },
                )

                ListItem(
                    headlineContent = { Text("Copy message text", color = palette.textPrimary) },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = null,
                            tint = palette.textSecondary,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = palette.surface0),
                    modifier = Modifier.clickable {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cb?.setPrimaryClip(ClipData.newPlainText("Cypher Message", sheetMessage.text))
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        selectedMessageForSheet = null
                    },
                )

                if (!sheetMessage.isOutgoing) {
                    ListItem(
                        headlineContent = {
                            Text("Mention @${sheetMessage.senderNickname}", color = palette.textPrimary)
                        },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Filled.Public,
                                contentDescription = null,
                                tint = palette.textSecondary,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = palette.surface0),
                        modifier = Modifier.clickable {
                            val next = if (inputText.isBlank()) {
                                "@${sheetMessage.senderNickname} "
                            } else {
                                "$inputText @${sheetMessage.senderNickname} "
                            }
                            inputText = next
                            onDraftChanged(next)
                            selectedMessageForSheet = null
                        },
                    )
                }
            }
        }
    }

    // Full-screen Image Lightbox Dialog
    val imagePath = fullscreenImagePath
    if (imagePath != null) {
        FullscreenImageDialog(
            localPath = imagePath,
            onDismiss = { fullscreenImagePath = null },
        )
    }
}

@Composable
private fun VoiceRecordingComposerBar(
    elapsedMs: Long,
    amplitudes: List<Float>,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    val palette = LocalCypherPalette.current
    val totalSec = (elapsedMs / 1000L).toInt()
    val timeFormatted = "%d:%02d".format(totalSec / 60, totalSec % 60)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconButton(
            onClick = onCancel,
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(palette.dangerRedSoft)
                .testTag("cancel_voice_recording_button"),
        ) {
            Icon(
                imageVector = Icons.Filled.DeleteOutline,
                contentDescription = "Discard voice note",
                tint = palette.dangerRed,
            )
        }

        Surface(
            color = palette.surface1,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .weight(1f)
                .height(46.dp)
                .border(1.dp, palette.dangerRed.copy(alpha = 0.45f), RoundedCornerShape(24.dp)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(palette.dangerRed),
                )
                Text(
                    text = timeFormatted,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = CypherMonoFontFamily),
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(22.dp),
                ) {
                    val barCount = 28
                    val slotWidth = size.width / barCount
                    val barWidth = (slotWidth * 0.58f).coerceAtLeast(2f)
                    for (i in 0 until barCount) {
                        val amp = amplitudes.getOrElse(amplitudes.size - barCount + i) { 0.12f }
                        val bh = (size.height * amp).coerceAtLeast(4f)
                        val x = i * slotWidth
                        val y = (size.height - bh) / 2f
                        drawRoundRect(
                            color = palette.dangerRed,
                            topLeft = Offset(x, y),
                            size = Size(barWidth, bh),
                            cornerRadius = CornerRadius(3f, 3f),
                        )
                    }
                }
            }
        }

        IconButton(
            onClick = onSend,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(palette.encryptedGreen)
                .testTag("send_voice_recording_button"),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send voice note",
                tint = Color(0xFF022C22),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubbleRow(
    message: ChatMessage,
    isBroadcast: Boolean,
    isFirstInGroup: Boolean,
    isLastInGroup: Boolean,
    onSwipeReply: () -> Unit,
    onLongPress: () -> Unit,
    onTapImage: (String) -> Unit,
    onQuickToggleReaction: (String) -> Unit,
) {
    val palette = LocalCypherPalette.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val dragX = remember { Animatable(0f) }
    val replyThresholdPx = with(density) { 56.dp.toPx() }
    val maxDragPx = with(density) { 84.dp.toPx() }

    val isOutgoing = message.isOutgoing
    val senderColor = remember(message.senderPeerHex, palette.isDark) {
        PeerColors.forPeer(message.senderPeerHex, palette.isDark)
    }

    val topStart = if (!isOutgoing && !isFirstInGroup) 6.dp else 18.dp
    val bottomStart = if (!isOutgoing && !isLastInGroup) 6.dp else 18.dp
    val topEnd = if (isOutgoing && !isFirstInGroup) 6.dp else 18.dp
    val bottomEnd = if (isOutgoing && !isLastInGroup) 6.dp else 18.dp
    val bubbleShape = RoundedCornerShape(
        topStart = topStart,
        topEnd = topEnd,
        bottomStart = bottomStart,
        bottomEnd = bottomEnd,
    )

    val isActionEmote = message.text.startsWith("* ") && message.text.endsWith(" *")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = if (isFirstInGroup) 8.dp else 2.dp,
                bottom = if (isLastInGroup) 4.dp else 1.dp,
            ),
    ) {
        // Reply icon revealed during horizontal swipe
        if (dragX.value > 8f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(palette.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Reply,
                    contentDescription = "Reply",
                    tint = palette.accent,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(dragX.value.roundToInt(), 0) }
                .pointerInput(message.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val triggered = dragX.value >= replyThresholdPx
                            scope.launch {
                                dragX.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                            }
                            if (triggered) {
                                onSwipeReply()
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                dragX.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                            }
                        },
                        onHorizontalDrag = { change, amount ->
                            if (amount > 0f || dragX.value > 0f) {
                                change.consume()
                                val next = (dragX.value + amount).coerceIn(0f, maxDragPx)
                                scope.launch { dragX.snapTo(next) }
                            }
                        },
                    )
                },
            horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Bottom,
        ) {
            if (!isOutgoing && isBroadcast) {
                if (isLastInGroup) {
                    PeerAvatar(
                        nickname = message.senderNickname,
                        peerIdHex = message.senderPeerHex,
                        size = 30.dp,
                    )
                } else {
                    Spacer(modifier = Modifier.width(30.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            Column(
                horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start,
                modifier = Modifier.widthIn(max = 310.dp),
            ) {
                Box(
                    modifier = Modifier
                        .clip(bubbleShape)
                        .background(
                            brush = if (isOutgoing) {
                                Brush.linearGradient(
                                    colors = listOf(
                                        palette.outgoingBubbleStart,
                                        palette.outgoingBubbleEnd,
                                    ),
                                )
                            } else {
                                Brush.linearGradient(
                                    colors = listOf(
                                        palette.incomingBubble,
                                        palette.incomingBubble,
                                    ),
                                )
                            },
                        )
                        .border(
                            width = if (isOutgoing) 0.dp else 1.dp,
                            color = if (isOutgoing) Color.Transparent else palette.incomingBubbleBorder,
                            shape = bubbleShape,
                        )
                        .combinedClickable(
                            onClick = {},
                            onLongClick = onLongPress,
                        )
                        .padding(horizontal = 13.dp, vertical = 9.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        // Sender header on first message in broadcast group
                        if (!isOutgoing && isBroadcast && isFirstInGroup) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = "@${message.senderNickname}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = senderColor,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = "#${message.senderPeerHex.takeLast(4)}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = CypherMonoFontFamily),
                                    color = palette.textMuted,
                                )
                            }
                        }

                        // Quoted Reply Block
                        val reply = message.replyTo
                        if (reply != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isOutgoing) {
                                            Color.Black.copy(alpha = 0.20f)
                                        } else {
                                            palette.canvas.copy(alpha = 0.65f)
                                        },
                                    )
                                    .padding(horizontal = 9.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(2.5.dp)
                                        .height(28.dp)
                                        .background(if (isOutgoing) Color.White else palette.accent),
                                )
                                Column {
                                    Text(
                                        text = "@${reply.senderNickname}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isOutgoing) Color.White else palette.accent,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = reply.previewText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isOutgoing) {
                                            Color.White.copy(alpha = 0.85f)
                                        } else {
                                            palette.textSecondary
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }

                        // Media Attachment Content
                        val attachment = message.attachment
                        if (attachment != null) {
                            AttachmentBubbleContent(
                                attachment = attachment,
                                isOutgoing = isOutgoing,
                                onTapImage = onTapImage,
                            )
                        }

                        // Message Body Text (with @mention highlighting)
                        if (attachment == null || (attachment.kind != AttachmentKind.AUDIO && message.text != attachment.fileName)) {
                            val textColor = if (isOutgoing) palette.outgoingBubbleText else palette.incomingBubbleText
                            val styledText = remember(message.text, textColor, palette.accent) {
                                formatMessageAnnotatedText(
                                    raw = message.text,
                                    baseColor = textColor,
                                    mentionColor = if (isOutgoing) Color.White else palette.accent,
                                )
                            }
                            Text(
                                text = styledText,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontStyle = if (isActionEmote) FontStyle.Italic else FontStyle.Normal,
                                ),
                            )
                        }

                        // Timestamp & Delivery Receipt Footer
                        Row(
                            modifier = Modifier.align(Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = formatClockTime(message.timestampMs),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                color = if (isOutgoing) {
                                    palette.outgoingBubbleText.copy(alpha = 0.75f)
                                } else {
                                    palette.textMuted
                                },
                            )
                            if (isOutgoing && message.isPrivate) {
                                DeliveryReceiptIcon(
                                    state = message.delivery,
                                    tintOverride = palette.outgoingBubbleText.copy(alpha = 0.85f),
                                )
                            }
                        }
                    }
                }

                // Reaction Pill
                val reaction = message.reaction
                if (!reaction.isNullOrBlank()) {
                    Surface(
                        color = palette.surface2,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier
                            .offset(y = (-4).dp)
                            .clip(RoundedCornerShape(50))
                            .clickable { onQuickToggleReaction(reaction) }
                            .border(1.dp, palette.borderStrong, RoundedCornerShape(50)),
                    ) {
                        Text(
                            text = reaction,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentBubbleContent(
    attachment: MediaAttachment,
    isOutgoing: Boolean,
    onTapImage: (String) -> Unit,
) {
    val palette = LocalCypherPalette.current

    when (attachment.kind) {
        AttachmentKind.IMAGE -> {
            val bitmap = remember(attachment.localPath) {
                runCatching {
                    val file = File(attachment.localPath)
                    if (file.exists()) BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() else null
                }.getOrNull()
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = attachment.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 220.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onTapImage(attachment.localPath) },
                )
            }
        }

        AttachmentKind.AUDIO -> {
            VoiceNotePlayerCard(
                attachment = attachment,
                isOutgoing = isOutgoing,
            )
        }

        AttachmentKind.FILE -> {
            Surface(
                color = if (isOutgoing) Color.Black.copy(alpha = 0.20f) else palette.surface2,
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Description,
                        contentDescription = null,
                        tint = if (isOutgoing) Color.White else palette.accent,
                        modifier = Modifier.size(22.dp),
                    )
                    Column {
                        Text(
                            text = attachment.fileName,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (isOutgoing) Color.White else palette.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${(attachment.sizeBytes / 1024).coerceAtLeast(1)} KB · ${attachment.mimeType}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isOutgoing) Color.White.copy(alpha = 0.75f) else palette.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceNotePlayerCard(
    attachment: MediaAttachment,
    isOutgoing: Boolean,
) {
    val palette = LocalCypherPalette.current
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    val bars = remember(attachment.localPath) {
        val seed = attachment.fileName.hashCode()
        List(24) { idx ->
            val v = abs(((seed shr (idx % 16)) xor (idx * 37)) % 100) / 100f
            0.22f + (v * 0.78f)
        }
    }

    DisposableEffect(attachment.localPath) {
        onDispose {
            runCatching { player?.stop() }
            runCatching { player?.release() }
            player = null
        }
    }

    LaunchedEffect(isPlaying) {
        while (isActive && isPlaying) {
            val mp = player
            if (mp != null && runCatching { mp.isPlaying }.getOrDefault(false)) {
                val dur = mp.duration.coerceAtLeast(1)
                progress = (mp.currentPosition.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
            } else {
                isPlaying = false
                progress = 0f
                break
            }
            delay(80L)
        }
    }

    val fgColor = if (isOutgoing) Color.White else palette.accent
    val mutedBarColor = if (isOutgoing) Color.White.copy(alpha = 0.35f) else palette.borderStrong

    Row(
        modifier = Modifier
            .width(220.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (isOutgoing) Color.White.copy(alpha = 0.22f) else palette.accentSoft)
                .clickable {
                    if (isPlaying) {
                        runCatching { player?.pause() }
                        isPlaying = false
                    } else {
                        val file = File(attachment.localPath)
                        if (file.exists()) {
                            runCatching {
                                val mp = player ?: MediaPlayer().apply {
                                    setDataSource(file.absolutePath)
                                    prepare()
                                    setOnCompletionListener {
                                        isPlaying = false
                                        progress = 0f
                                    }
                                }.also { player = it }
                                mp.start()
                                isPlaying = true
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause voice note" else "Play voice note",
                tint = fgColor,
                modifier = Modifier.size(20.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp),
            ) {
                val count = bars.size
                val slot = size.width / count
                val bw = (slot * 0.6f).coerceAtLeast(2f)
                for (i in 0 until count) {
                    val ratio = i / count.toFloat()
                    val bh = size.height * bars[i]
                    val x = i * slot
                    val y = (size.height - bh) / 2f
                    drawRoundRect(
                        color = if (ratio <= progress) fgColor else mutedBarColor,
                        topLeft = Offset(x, y),
                        size = Size(bw, bh),
                        cornerRadius = CornerRadius(3f, 3f),
                    )
                }
            }

            val totalSecs = ((attachment.durationMs / 1000L).takeIf { it > 0L } ?: 2L).toInt()
            Text(
                text = "Voice note · %d:%02d".format(totalSecs / 60, totalSecs % 60),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = CypherMonoFontFamily),
                color = if (isOutgoing) Color.White.copy(alpha = 0.85f) else palette.textSecondary,
            )
        }
    }
}

@Composable
private fun DateHeaderPill(timestampMs: Long) {
    val palette = LocalCypherPalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = palette.surface1,
            shape = RoundedCornerShape(50),
            modifier = Modifier.border(1.dp, palette.borderSubtle, RoundedCornerShape(50)),
        ) {
            Text(
                text = formatDayHeader(timestampMs),
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun SystemEventPill(text: String) {
    val palette = LocalCypherPalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = palette.surface1.copy(alpha = 0.75f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.border(1.dp, palette.borderSubtle, RoundedCornerShape(12.dp)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = palette.encryptedGreen,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun EmptyThreadBanner(
    isBroadcast: Boolean,
    displayName: String,
    peerIdHex: String,
    onPing: () -> Unit,
) {
    val palette = LocalCypherPalette.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = palette.surface1,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier
                .widthIn(max = 420.dp)
                .border(1.dp, palette.borderSubtle, RoundedCornerShape(22.dp)),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PeerAvatar(
                    nickname = displayName,
                    peerIdHex = peerIdHex,
                    size = 56.dp,
                    isBroadcastChannel = isBroadcast,
                )
                Text(
                    text = if (isBroadcast) "Public #mesh Broadcast Channel" else "Encrypted Channel with $displayName",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.textPrimary,
                )
                Text(
                    text = if (isBroadcast) {
                        "Messages sent here hop across all nearby Bluetooth LE Cypher nodes. Type /hug, /slap, or @nickname to interact."
                    } else {
                        "Messages are end-to-end encrypted using Noise_XX_25519_ChaChaPoly_SHA256 and padded to resist traffic analysis."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                )
                Surface(
                    color = palette.accentSoft,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onPing)
                        .border(1.dp, palette.accent.copy(alpha = 0.4f), RoundedCornerShape(50)),
                ) {
                    Text(
                        text = if (isBroadcast) "Announce Presence" else "Send Mesh Ping",
                        style = MaterialTheme.typography.labelLarge,
                        color = palette.accent,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FullscreenImageDialog(
    localPath: String,
    onDismiss: () -> Unit,
) {
    val palette = LocalCypherPalette.current
    val bitmap = remember(localPath) {
        runCatching {
            val f = File(localPath)
            if (f.exists()) BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() else null
        }.getOrNull()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.canvas.copy(alpha = 0.96f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Fullscreen attachment",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(20.dp)
                    .clip(CircleShape)
                    .background(palette.surface2),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close image",
                    tint = palette.textPrimary,
                )
            }
        }
    }
}

private fun formatMessageAnnotatedText(
    raw: String,
    baseColor: Color,
    mentionColor: Color,
): AnnotatedString = buildAnnotatedString {
    append(raw)
    addStyle(SpanStyle(color = baseColor), 0, raw.length)
    val regex = Regex("@[A-Za-z0-9_#-]{2,28}")
    regex.findAll(raw).forEach { match ->
        addStyle(
            SpanStyle(
                color = mentionColor,
                fontWeight = FontWeight.Bold,
            ),
            match.range.first,
            match.range.last + 1,
        )
    }
}

private fun formatClockTime(timestampMs: Long): String {
    if (timestampMs <= 0L) return ""
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMs))
}

private fun formatDayHeader(timestampMs: Long): String {
    val now = Calendar.getInstance()
    val msgCal = Calendar.getInstance().apply { timeInMillis = timestampMs }
    return when {
        now.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == msgCal.get(Calendar.DAY_OF_YEAR) -> "Today"
        now.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) - msgCal.get(Calendar.DAY_OF_YEAR) == 1 -> "Yesterday"
        else -> SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(timestampMs))
    }
}

private fun isSameCalendarDay(aMs: Long, bMs: Long): Boolean {
    val a = Calendar.getInstance().apply { timeInMillis = aMs }
    val b = Calendar.getInstance().apply { timeInMillis = bMs }
    return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}
