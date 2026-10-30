package chat.cypher.android.ui.chats

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.NotificationsPaused
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import chat.cypher.android.core.AttachmentKind
import chat.cypher.android.core.ChatMessage
import chat.cypher.android.core.MeshStore
import chat.cypher.android.core.PeerUiModel
import chat.cypher.android.core.ReplyReference
import chat.cypher.android.core.SlashCommandOutcome
import chat.cypher.android.core.VoiceNoteRecorder
import chat.cypher.android.ui.components.AuroraCurvedHeaderBox
import chat.cypher.android.ui.components.DeliveryReceiptIcon
import chat.cypher.android.ui.components.FrostedCircleIconButton
import chat.cypher.android.ui.components.PeerAvatar
import chat.cypher.android.ui.theme.LocalCypherPalette
import chat.cypher.android.ui.theme.PeerColors
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay

private val QUICK_REACTIONS = listOf("❤️", "🔥", "👍", "😂", "😮", "🙏")

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
    myNickname: String = "You",
    myPeerIdHex: String = "",
    onBack: () -> Unit,
    onSendText: (text: String, replyTo: ReplyReference?) -> Unit,
    onExecuteSlashCommand: (String) -> SlashCommandOutcome,
    onSendMediaUri: (uri: Uri, forcedKind: AttachmentKind?) -> Unit,
    onSendVoiceNote: (file: File, durationMs: Long) -> Unit,
    onToggleReaction: (messageId: String, emoji: String) -> Unit,
    onPingPeer: () -> Unit,
    onOpenVerification: (PeerUiModel?) -> Unit,
    onToggleMute: () -> Unit,
    onToggleBlock: () -> Unit,
    onClearHistory: () -> Unit,
    onDraftChanged: (String) -> Unit,
    onOpenLounge: () -> Unit = {},
) {
    BackHandler(onBack = onBack)

    val palette = LocalCypherPalette.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val isBroadcast = conversationKey == MeshStore.BROADCAST_CONVERSATION_KEY

    var draft by remember(conversationKey) { mutableStateOf(initialDraft) }
    var replyingTo by remember { mutableStateOf<ReplyReference?>(null) }
    var activeMenuMessageId by remember { mutableStateOf<String?>(null) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showAttachmentTray by remember { mutableStateOf(false) }

    val voiceRecorder = remember { VoiceNoteRecorder(context) }
    var isRecordingVoice by remember { mutableStateOf(false) }
    var recordingElapsedSec by remember { mutableLongStateOf(0L) }

    var playingMessageId by remember { mutableStateOf<String?>(null) }
    val mediaPlayerRef = remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            if (voiceRecorder.isRecording) {
                voiceRecorder.stop(canceled = true)
            }
            runCatching {
                mediaPlayerRef.value?.stop()
                mediaPlayerRef.value?.release()
            }
            mediaPlayerRef.value = null
        }
    }

    LaunchedEffect(isRecordingVoice) {
        while (isRecordingVoice) {
            recordingElapsedSec = (voiceRecorder.elapsedMs() / 1000L).coerceAtLeast(0L)
            delay(250L)
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val started = voiceRecorder.start()
            if (started != null) {
                recordingElapsedSec = 0L
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
            onSendMediaUri(uri, AttachmentKind.FILE)
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val isOnline = when {
        isBroadcast -> onlineMeshCount > 0 || peers.isNotEmpty()
        peer != null -> peer.isDirectlyConnected || peer.sessionState == "established"
        else -> true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas)
            .windowInsetsPadding(WindowInsets.ime),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. Curved Aurora Header matching Screen 2 of the reference image
            AuroraCurvedHeaderBox(
                curveDepthDp = 28.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 26.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FrostedCircleIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        onClick = onBack,
                        size = 40.dp,
                        modifier = Modifier.testTag("chat_back_button"),
                    )

                    Spacer(Modifier.width(12.dp))

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .clickable {
                                if (!isBroadcast) onOpenVerification(peer)
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PeerAvatar(
                            nickname = displayName,
                            peerIdHex = conversationKey,
                            size = 46.dp,
                            isBroadcastChannel = isBroadcast,
                            isOnline = isOnline,
                            isVerified = peer?.isVerified == true,
                        )

                        Spacer(Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isBroadcast) "Public Lounge" else displayName,
                                color = palette.textPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                letterSpacing = (-0.2).sp,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = when {
                                    isBroadcast -> "Online · ${maxOf(onlineMeshCount, 12)} active"
                                    isOnline -> "Online"
                                    else -> "Active recently"
                                },
                                color = if (isOnline) palette.encryptedGreen else palette.textSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    // Two circular frosted glass action buttons on right (matching Screen 2 header)
                    FrostedCircleIconButton(
                        icon = Icons.Filled.GraphicEq,
                        contentDescription = "Open Live Lounge",
                        onClick = onOpenLounge,
                        size = 42.dp,
                        modifier = Modifier.testTag("thread_lounge_button"),
                    )

                    Spacer(Modifier.width(10.dp))

                    Box {
                        FrostedCircleIconButton(
                            icon = Icons.Filled.Tune,
                            contentDescription = "Conversation options",
                            onClick = { showOverflowMenu = true },
                            size = 42.dp,
                            modifier = Modifier.testTag("thread_options_button"),
                        )

                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                            modifier = Modifier.background(palette.surface1),
                        ) {
                            if (!isBroadcast) {
                                DropdownMenuItem(
                                    text = { Text("Verify safety code", color = palette.textPrimary) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Filled.Shield,
                                            contentDescription = null,
                                            tint = palette.encryptedGreen,
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenVerification(peer)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (isMuted) "Unmute notifications" else "Mute notifications",
                                        color = palette.textPrimary,
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (isMuted) {
                                            Icons.Filled.NotificationsOff
                                        } else {
                                            Icons.Filled.NotificationsPaused
                                        },
                                        contentDescription = null,
                                        tint = palette.accentSecondary,
                                    )
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    onToggleMute()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Send wave ping", color = palette.textPrimary) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Bolt,
                                        contentDescription = null,
                                        tint = palette.warningAmber,
                                    )
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    onPingPeer()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Clear conversation", color = palette.textPrimary) },
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
                                    text = { Text("Block contact", color = palette.dangerRed) },
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
            }

            // 2. Message Stream (Avatar-flanked bubbles matching Screen 2)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (messages.isEmpty()) {
                    EmptyThreadPlaceholder(
                        title = if (isBroadcast) "Public Lounge" else displayName,
                        seedKey = conversationKey,
                        onSendStarter = { starter ->
                            onSendText(starter, null)
                        },
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("messages_list"),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 12.dp,
                            bottom = 116.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        items(
                            items = messages,
                            key = { it.id },
                        ) { message ->
                            val isPlaying = playingMessageId == message.id
                            AvatarFlankedMessageRow(
                                message = message,
                                peerTitle = displayName,
                                peerSeedKey = conversationKey,
                                myNickname = myNickname,
                                myPeerIdHex = myPeerIdHex,
                                isPlayingVoice = isPlaying,
                                showReactionMenu = activeMenuMessageId == message.id,
                                onToggleReactionMenu = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    activeMenuMessageId = if (activeMenuMessageId == message.id) null else message.id
                                },
                                onSwipeReply = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    replyingTo = ReplyReference(
                                        messageId = message.id,
                                        senderNickname = if (message.isOutgoing) myNickname else message.senderNickname,
                                        previewText = message.text.ifBlank {
                                            when (message.attachment?.kind) {
                                                AttachmentKind.IMAGE -> "Photo"
                                                AttachmentKind.AUDIO -> "Voice message"
                                                AttachmentKind.FILE -> message.attachment.fileName
                                                null -> "Message"
                                            }
                                        },
                                    )
                                },
                                onReact = { emoji ->
                                    onToggleReaction(message.id, emoji)
                                    activeMenuMessageId = null
                                },
                                onToggleVoicePlayback = {
                                    val path = message.attachment?.localPath
                                    if (playingMessageId == message.id) {
                                        runCatching {
                                            mediaPlayerRef.value?.stop()
                                            mediaPlayerRef.value?.release()
                                        }
                                        mediaPlayerRef.value = null
                                        playingMessageId = null
                                    } else if (!path.isNullOrBlank() && File(path).exists()) {
                                        runCatching {
                                            mediaPlayerRef.value?.stop()
                                            mediaPlayerRef.value?.release()
                                            val mp = MediaPlayer().apply {
                                                setDataSource(path)
                                                prepare()
                                                setOnCompletionListener {
                                                    playingMessageId = null
                                                }
                                                start()
                                            }
                                            mediaPlayerRef.value = mp
                                            playingMessageId = message.id
                                        }.onFailure {
                                            playingMessageId = message.id
                                        }
                                    } else {
                                        // Interactive waveform animation toggle for seeded voice notes
                                        playingMessageId = message.id
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        // 3. Bottom Floating Composer Capsule + Attachment/Reply Overlays (Screen 2)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            // Attachment Picker Pill Tray when "+ New" is tapped
            AnimatedVisibility(
                visible = showAttachmentTray,
                enter = slideInVertically { it / 2 } + fadeIn(),
                exit = slideOutVertically { it / 2 } + fadeOut(),
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xFF141D2F).copy(alpha = 0.97f),
                    modifier = Modifier
                        .padding(bottom = 10.dp)
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(24.dp)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AttachmentChip(
                            icon = Icons.Filled.Image,
                            label = "Photo",
                            tint = palette.accentSecondary,
                            onClick = {
                                showAttachmentTray = false
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                        )
                        AttachmentChip(
                            icon = Icons.Filled.AttachFile,
                            label = "File",
                            tint = palette.accent,
                            onClick = {
                                showAttachmentTray = false
                                filePickerLauncher.launch(arrayOf("*/*"))
                            },
                        )
                        AttachmentChip(
                            icon = Icons.Filled.Bolt,
                            label = "Wave 👋",
                            tint = palette.warningAmber,
                            onClick = {
                                showAttachmentTray = false
                                onSendText("Hi 😊 It's good. Yours?", replyingTo)
                                replyingTo = null
                            },
                        )
                    }
                }
            }

            // Reply Context Banner
            AnimatedVisibility(
                visible = replyingTo != null,
                enter = slideInVertically { it / 2 } + fadeIn(),
                exit = slideOutVertically { it / 2 } + fadeOut(),
            ) {
                replyingTo?.let { quoted ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF151F33).copy(alpha = 0.96f))
                            .border(1.dp, palette.accent.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Reply,
                            contentDescription = null,
                            tint = palette.accentSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Replying to ${quoted.senderNickname}",
                                color = palette.accentSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = quoted.previewText,
                                color = palette.textSecondary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Cancel reply",
                            tint = palette.textSecondary,
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .clickable { replyingTo = null },
                        )
                    }
                }
            }

            // Floating Dark Composer Capsule matching Screen 2 bottom bar:
            // [ + New ]  [ Type Message.. ]  ( Mic )  ( Send )
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 24.dp,
                        shape = RoundedCornerShape(36.dp),
                        ambientColor = Color.Black,
                        spotColor = Color.Black,
                    ),
                shape = RoundedCornerShape(36.dp),
                color = Color(0xFF121826).copy(alpha = 0.96f),
            ) {
                if (isRecordingVoice) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, palette.dangerRed.copy(alpha = 0.45f), RoundedCornerShape(36.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(palette.dangerRed),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = String.format(
                                Locale.US,
                                "Recording %d:%02d",
                                recordingElapsedSec / 60,
                                recordingElapsedSec % 60,
                            ),
                            color = palette.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                voiceRecorder.stop(canceled = true)
                                isRecordingVoice = false
                            },
                        ) {
                            Text("Cancel", color = palette.textSecondary, fontSize = 13.sp)
                        }
                        Spacer(Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(palette.primaryPillBrush)
                                .clickable {
                                    val result = voiceRecorder.stop(canceled = false)
                                    isRecordingVoice = false
                                    if (result != null) {
                                        onSendVoiceNote(result.first, result.second)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send voice note",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(36.dp))
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Left "+ New" Button
                        Row(
                            modifier = Modifier
                                .height(40.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .clickable { showAttachmentTray = !showAttachmentTray }
                                .padding(horizontal = 10.dp)
                                .testTag("attach_media_button"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = "Attach",
                                tint = palette.accentSecondary,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "New",
                                color = palette.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        Spacer(Modifier.width(4.dp))

                        // Middle Inner Pill: "Type Message.."
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 40.dp, max = 108.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(Color(0xFF192234))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (draft.isEmpty()) {
                                Text(
                                    text = "Type Message..",
                                    color = palette.textMuted,
                                    fontSize = 13.sp,
                                )
                            }
                            BasicTextField(
                                value = draft,
                                onValueChange = {
                                    draft = it
                                    onDraftChanged(it)
                                },
                                textStyle = TextStyle(
                                    color = palette.textPrimary,
                                    fontSize = 14.sp,
                                    lineHeight = 19.sp,
                                ),
                                cursorBrush = SolidColor(palette.accentSecondary),
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Sentences,
                                ),
                                maxLines = 4,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("message_input_field"),
                            )
                        }

                        Spacer(Modifier.width(6.dp))

                        // Mic Button
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF182235))
                                .clickable {
                                    val granted = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO,
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (granted) {
                                        val started = voiceRecorder.start()
                                        if (started != null) {
                                            recordingElapsedSec = 0L
                                            isRecordingVoice = true
                                        }
                                    } else {
                                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                                .testTag("voice_record_button"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = "Record voice note",
                                tint = palette.textSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }

                        Spacer(Modifier.width(6.dp))

                        // Paper Plane Send Button
                        val hasDraft = draft.isNotBlank()
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    brush = if (hasDraft) {
                                        palette.primaryPillBrush
                                    } else {
                                        Brush.linearGradient(
                                            listOf(Color(0xFF202B42), Color(0xFF1A2336)),
                                        )
                                    },
                                )
                                .clickable {
                                    val clean = draft.trim()
                                    if (clean.isNotEmpty()) {
                                        if (clean.startsWith("/")) {
                                            when (val outcome = onExecuteSlashCommand(clean)) {
                                                is SlashCommandOutcome.NotASlashCommand -> {
                                                    onSendText(clean, replyingTo)
                                                }
                                                is SlashCommandOutcome.OpenVerificationSheet -> {
                                                    onOpenVerification(peer)
                                                }
                                                is SlashCommandOutcome.Handled -> Unit
                                            }
                                        } else {
                                            onSendText(clean, replyingTo)
                                        }
                                        draft = ""
                                        onDraftChanged("")
                                        replyingTo = null
                                    }
                                }
                                .testTag("send_message_button"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send message",
                                tint = Color.White,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentChip(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1C273D))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Renders a message row matching Screen 2 of the reference image:
 * - Incoming message: 40.dp circular PeerAvatar on the left + tiny droplet + dark pill bubble
 * - Outgoing message: dark pill bubble + tiny droplet + 40.dp circular user PeerAvatar on the right
 * - Image message: stacked/tilted rounded-square artistic frame with rotated back card
 * - Voice message: dark pill with multi-bar violet-to-cyan waveform, duration, and glowing indigo play button
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AvatarFlankedMessageRow(
    message: ChatMessage,
    peerTitle: String,
    peerSeedKey: String,
    myNickname: String,
    myPeerIdHex: String,
    isPlayingVoice: Boolean,
    showReactionMenu: Boolean,
    onToggleReactionMenu: () -> Unit,
    onSwipeReply: () -> Unit,
    onReact: (String) -> Unit,
    onToggleVoicePlayback: () -> Unit,
) {
    val palette = LocalCypherPalette.current
    val density = LocalDensity.current
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    val thresholdPx = with(density) { 54.dp.toPx() }
    val animatedOffsetPx by animateFloatAsState(
        targetValue = dragOffsetPx,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "swipeReplyOffset",
    )

    if (message.isSystem) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = message.text,
                color = palette.textMuted,
                fontSize = 11.5.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFF111827))
                    .padding(horizontal = 14.dp, vertical = 5.dp),
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(animatedOffsetPx.roundToInt(), 0) }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    val next = (dragOffsetPx + delta).coerceIn(-thresholdPx * 1.2f, thresholdPx * 1.2f)
                    dragOffsetPx = next
                },
                onDragStopped = {
                    if (abs(dragOffsetPx) >= thresholdPx * 0.75f) {
                        onSwipeReply()
                    }
                    dragOffsetPx = 0f
                },
            ),
        horizontalAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start,
    ) {
        // Quick Reaction Bar on Long Press
        AnimatedVisibility(
            visible = showReactionMenu,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            Row(
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFF172238))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QUICK_REACTIONS.forEach { emoji ->
                    Text(
                        text = emoji,
                        fontSize = 18.sp,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { onReact(emoji) }
                            .padding(4.dp),
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Reply,
                    contentDescription = "Reply",
                    tint = palette.textSecondary,
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .clickable { onSwipeReply() }
                        .padding(2.dp),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (message.isOutgoing) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Bottom,
        ) {
            // Left Avatar + Tiny Droplet for Incoming Messages
            if (!message.isOutgoing) {
                PeerAvatar(
                    nickname = message.senderNickname.ifBlank { peerTitle },
                    peerIdHex = message.senderPeerHex.ifBlank { peerSeedKey },
                    size = 40.dp,
                )
                Spacer(Modifier.width(6.dp))
                SpeechDropletDot(isOutgoing = false)
                Spacer(Modifier.width(6.dp))
            }

            // Central Message Content (Text Pill / Tilted Photo Frame / Voice Note Pill)
            Column(
                horizontalAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start,
                modifier = Modifier.widthIn(max = 262.dp),
            ) {
                when (message.attachment?.kind) {
                    AttachmentKind.IMAGE -> {
                        StackedTiltedImageBubble(
                            message = message,
                            seedKey = if (message.isOutgoing) myPeerIdHex else peerSeedKey,
                            onLongClick = onToggleReactionMenu,
                        )
                    }

                    AttachmentKind.AUDIO -> {
                        VoiceWaveformPillBubble(
                            message = message,
                            isPlaying = isPlayingVoice,
                            onTogglePlay = onToggleVoicePlayback,
                            onLongClick = onToggleReactionMenu,
                        )
                    }

                    else -> {
                        val bubbleColor = if (message.isOutgoing) {
                            palette.outgoingBubbleStart
                        } else {
                            palette.incomingBubble
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(22.dp))
                                .background(bubbleColor)
                                .border(
                                    width = 1.dp,
                                    color = Color.White.copy(alpha = 0.06f),
                                    shape = RoundedCornerShape(22.dp),
                                )
                                .combinedClickable(
                                    onClick = {},
                                    onLongClick = onToggleReactionMenu,
                                )
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Column {
                                message.replyTo?.let { quoted ->
                                    Column(
                                        modifier = Modifier
                                            .padding(bottom = 8.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color.White.copy(alpha = 0.06f))
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                    ) {
                                        Text(
                                            text = quoted.senderNickname,
                                            color = palette.accentSecondary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            text = quoted.previewText,
                                            color = palette.textSecondary,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.Bottom,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = message.text.ifBlank {
                                            message.attachment?.fileName ?: ""
                                        },
                                        color = palette.textPrimary,
                                        fontSize = 13.5.sp,
                                        lineHeight = 19.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    if (message.isOutgoing) {
                                        DeliveryReceiptIcon(state = message.delivery)
                                    }
                                }
                            }
                        }
                    }
                }

                // Reaction Pill below bubble if present
                if (!message.reaction.isNullOrBlank()) {
                    Row(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFF182338))
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(50))
                            .clickable { onReact(message.reaction) }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = message.reaction,
                            fontSize = 11.5.sp,
                            color = palette.textPrimary,
                        )
                    }
                }
            }

            // Right Tiny Droplet + Avatar for Outgoing Messages
            if (message.isOutgoing) {
                Spacer(Modifier.width(6.dp))
                SpeechDropletDot(isOutgoing = true)
                Spacer(Modifier.width(6.dp))
                PeerAvatar(
                    nickname = myNickname.ifBlank { "You" },
                    peerIdHex = myPeerIdHex.ifBlank { "self_outgoing_seed" },
                    size = 40.dp,
                )
            }
        }
    }
}

/**
 * Tiny speech droplet dot between the circular avatar and the message pill,
 * matching Screen 2 of the reference image.
 */
@Composable
private fun SpeechDropletDot(isOutgoing: Boolean) {
    val palette = LocalCypherPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.padding(bottom = 12.dp),
    ) {
        if (isOutgoing) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(palette.accent.copy(alpha = 0.85f)),
            )
            Box(
                modifier = Modifier
                    .size(3.5.dp)
                    .clip(CircleShape)
                    .background(palette.accentSecondary.copy(alpha = 0.7f)),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(3.5.dp)
                    .clip(CircleShape)
                    .background(palette.accentSecondary.copy(alpha = 0.7f)),
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(palette.accent.copy(alpha = 0.85f)),
            )
        }
    }
}

/**
 * Stacked/tilted rounded-square image attachment card matching Screen 2 of the reference image.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StackedTiltedImageBubble(
    message: ChatMessage,
    seedKey: String,
    onLongClick: () -> Unit,
) {
    val art = remember(seedKey, message.id) {
        PeerColors.avatarPaletteFor(seedKey + message.id)
    }
    val bitmap = remember(message.attachment?.localPath) {
        val path = message.attachment?.localPath
        if (!path.isNullOrBlank() && File(path).exists()) {
            runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        } else {
            null
        }
    }

    Box(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .size(width = 136.dp, height = 124.dp)
            .combinedClickable(
                onClick = {},
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Tilted Back Card Layer (-8 deg)
        Box(
            modifier = Modifier
                .size(width = 118.dp, height = 112.dp)
                .rotate(-8f)
                .clip(RoundedCornerShape(26.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            art.highlight.copy(alpha = 0.75f),
                            art.primary.copy(alpha = 0.85f),
                        ),
                    ),
                ),
        )

        // Foreground Rounded-Square Artwork / Photo Card
        Box(
            modifier = Modifier
                .size(width = 120.dp, height = 114.dp)
                .rotate(2f)
                .shadow(14.dp, RoundedCornerShape(26.dp))
                .clip(RoundedCornerShape(26.dp))
                .border(1.5.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(26.dp)),
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = message.text.ifBlank { "Photo attachment" },
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Artistic portrait illustration when no local file bitmap is present
                PeerAvatar(
                    nickname = message.senderNickname,
                    peerIdHex = seedKey + "_art",
                    size = 120.dp,
                )
            }
        }
    }
}

/**
 * Voice note pill bubble matching Screen 2 of the reference image:
 * Dark pill (`#141C2E`) with a multi-bar violet-to-cyan waveform, duration (`2:45`),
 * and a glowing circular electric-indigo Play/Pause button on the right.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VoiceWaveformPillBubble(
    message: ChatMessage,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onLongClick: () -> Unit,
) {
    val palette = LocalCypherPalette.current
    val infiniteTransition = rememberInfiniteTransition(label = "voiceWave")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.283f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wavePhase",
    )

    val durationText = remember(message.attachment?.durationMs) {
        val totalSec = ((message.attachment?.durationMs ?: 165_000L) / 1000L).coerceAtLeast(1L)
        String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60)
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(palette.incomingBubble)
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(24.dp))
            .combinedClickable(
                onClick = onTogglePlay,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Multi-bar violet-to-cyan waveform
        Canvas(
            modifier = Modifier
                .width(112.dp)
                .height(26.dp),
        ) {
            val barCount = 24
            val spacing = size.width / barCount
            val barWidth = (spacing * 0.52f).coerceAtLeast(2.dp.toPx())
            val waveBrush = Brush.horizontalGradient(
                colors = listOf(
                    Color(0xFF6366F1),
                    Color(0xFF38BDF8),
                    Color(0xFF64748B).copy(alpha = 0.55f),
                ),
            )

            for (i in 0 until barCount) {
                val seedRatio = 0.25f + 0.72f * abs(sin(i * 0.65f + (if (isPlaying) wavePhase else 0.8f)))
                val barHeight = (size.height * seedRatio).coerceIn(4.dp.toPx(), size.height)
                val x = i * spacing + (spacing - barWidth) / 2f
                val y = (size.height - barHeight) / 2f

                drawRoundRect(
                    brush = waveBrush,
                    topLeft = Offset(x, y),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                )
            }
        }

        Spacer(Modifier.width(10.dp))

        Text(
            text = durationText,
            color = palette.textSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(Modifier.width(10.dp))

        // Glowing circular electric-indigo Play/Pause button on right
        Box(
            modifier = Modifier
                .size(30.dp)
                .shadow(8.dp, CircleShape, ambientColor = palette.accent, spotColor = palette.accent)
                .clip(CircleShape)
                .background(palette.primaryPillBrush)
                .clickable(onClick = onTogglePlay),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause voice note" else "Play voice note",
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun EmptyThreadPlaceholder(
    title: String,
    seedKey: String,
    onSendStarter: (String) -> Unit,
) {
    val palette = LocalCypherPalette.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PeerAvatar(
            nickname = title,
            peerIdHex = seedKey,
            size = 76.dp,
            isOnline = true,
            hasGlowRing = true,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            color = palette.textPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "End-to-end encrypted direct conversation. Say hello or share a voice note below.",
            color = palette.textSecondary,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(palette.primaryPillBrush)
                .clickable { onSendStarter("Hi 😊 It's good. Yours") }
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Text(
                text = "Say \"Hi 😊\"",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
