package chat.cypher.android.ui.chats

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.MarkChatRead
import androidx.compose.material.icons.filled.MarkChatUnread
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.NotificationsPaused
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.cypher.android.core.AttachmentKind
import chat.cypher.android.core.ChatMessage
import chat.cypher.android.core.MeshStore
import chat.cypher.android.core.PeerUiModel
import chat.cypher.android.ui.components.AuroraCurvedHeaderBox
import chat.cypher.android.ui.components.DeliveryReceiptIcon
import chat.cypher.android.ui.components.FrostedCircleIconButton
import chat.cypher.android.ui.components.PeerAvatar
import chat.cypher.android.ui.components.UnreadCountBadge
import chat.cypher.android.ui.theme.LocalCypherPalette
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

enum class ChatFilter(val label: String) {
    INBOX("Inbox"),
    UNREAD("Unread"),
    REQUESTS("Requests"),
}

data class ConversationRowSummary(
    val conversationKey: String,
    val peer: PeerUiModel?,
    val displayName: String,
    val lastMessage: ChatMessage?,
    val unreadCount: Int,
    val isPinned: Boolean,
    val isMuted: Boolean,
    val draftText: String?,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatsTab(
    peers: List<PeerUiModel>,
    conversations: Map<String, List<ChatMessage>>,
    unreadCounts: Map<String, Int>,
    pinnedConversations: Set<String>,
    mutedConversations: Set<String>,
    knownPeerNames: Map<String, String>,
    drafts: Map<String, String>,
    myNickname: String = "You",
    myPeerIdHex: String = "",
    isMeshRunning: Boolean,
    connectedBleCount: Int,
    onOpenConversation: (String) -> Unit,
    onTogglePin: (String) -> Unit,
    onToggleMute: (String) -> Unit,
    onToggleUnread: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onVerifyPeer: (PeerUiModel) -> Unit,
    onAnnouncePulse: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenLounge: () -> Unit = {},
    onOpenIdentity: () -> Unit = {},
    onPublishNote: (String) -> Unit = {},
) {
    val palette = LocalCypherPalette.current
    val haptic = LocalHapticFeedback.current

    var isSearchExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(ChatFilter.INBOX) }
    var contextMenuTarget by remember { mutableStateOf<ConversationRowSummary?>(null) }
    var showTopOverflowMenu by remember { mutableStateOf(false) }
    var showAddNoteDialog by remember { mutableStateOf(false) }
    var showNewMessageSheet by remember { mutableStateOf(false) }
    var userStatusNote by rememberSaveable { mutableStateOf("Add note") }
    var draftStatusNote by remember { mutableStateOf("") }

    val peerMap = remember(peers) { peers.associateBy { it.peerIdHex.lowercase() } }

    val broadcastMessages = conversations[MeshStore.BROADCAST_CONVERSATION_KEY].orEmpty()
    val broadcastLastMessage = broadcastMessages.lastOrNull()
    val broadcastUnread = unreadCounts[MeshStore.BROADCAST_CONVERSATION_KEY] ?: 0
    val broadcastDraft = drafts[MeshStore.BROADCAST_CONVERSATION_KEY]

    val publicLoungeRow = remember(
        broadcastLastMessage,
        broadcastUnread,
        pinnedConversations,
        mutedConversations,
        broadcastDraft,
        connectedBleCount,
        peers,
    ) {
        ConversationRowSummary(
            conversationKey = MeshStore.BROADCAST_CONVERSATION_KEY,
            peer = null,
            displayName = "Public Lounge",
            lastMessage = broadcastLastMessage,
            unreadCount = broadcastUnread,
            isPinned = pinnedConversations.contains(MeshStore.BROADCAST_CONVERSATION_KEY),
            isMuted = mutedConversations.contains(MeshStore.BROADCAST_CONVERSATION_KEY),
            draftText = broadcastDraft,
        )
    }

    val dmRows = remember(
        conversations,
        peers,
        unreadCounts,
        pinnedConversations,
        mutedConversations,
        knownPeerNames,
        drafts,
    ) {
        val allPeerKeys = linkedSetOf<String>()
        conversations.keys
            .filter { it != MeshStore.BROADCAST_CONVERSATION_KEY }
            .forEach { allPeerKeys.add(it.lowercase()) }
        peers.filter { !it.isBlocked }.forEach { allPeerKeys.add(it.peerIdHex.lowercase()) }

        allPeerKeys.mapNotNull { key ->
            val peer = peerMap[key]
            if (peer?.isBlocked == true) return@mapNotNull null
            val msgs = conversations[key].orEmpty()
            val lastMsg = msgs.lastOrNull()
            val unread = unreadCounts[key] ?: 0
            val name = peer?.nickname
                ?: knownPeerNames[key]
                ?: lastMsg?.senderNickname?.takeIf { it.isNotBlank() && it != "system" }
                ?: "Peer ${key.take(4).uppercase()}"

            ConversationRowSummary(
                conversationKey = key,
                peer = peer,
                displayName = name,
                lastMessage = lastMsg,
                unreadCount = unread,
                isPinned = pinnedConversations.contains(key),
                isMuted = mutedConversations.contains(key),
                draftText = drafts[key],
            )
        }.sortedWith(
            compareByDescending<ConversationRowSummary> { it.isPinned }
                .thenByDescending { it.unreadCount > 0 }
                .thenByDescending { it.lastMessage?.timestampMs ?: it.peer?.lastSeenMs ?: 0L }
                .thenBy { it.displayName.lowercase() },
        )
    }

    val allConversationRows = remember(publicLoungeRow, dmRows) {
        listOf(publicLoungeRow) + dmRows
    }

    val filteredRows = remember(allConversationRows, selectedFilter, searchQuery) {
        val q = searchQuery.trim().lowercase()
        allConversationRows.filter { row ->
            val matchesFilter = when (selectedFilter) {
                ChatFilter.INBOX -> true
                ChatFilter.UNREAD -> row.unreadCount > 0
                ChatFilter.REQUESTS -> row.peer != null && !row.peer.isVerified
            }
            val matchesQuery = if (q.isEmpty()) {
                true
            } else {
                row.displayName.lowercase().contains(q) ||
                    (row.lastMessage?.text?.lowercase()?.contains(q) == true)
            }
            matchesFilter && matchesQuery
        }
    }

    val inboxBadgeCount = remember(allConversationRows, unreadCounts) {
        val unreadSum = unreadCounts.values.sum()
        if (unreadSum > 0) unreadSum else allConversationRows.size
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("conversations_list"),
            contentPadding = PaddingValues(bottom = 112.dp),
        ) {
            // 1. Luminous Aurora Header + Curved 5-Avatar Constellation Arc
            item(key = "aurora_header_and_arc") {
                AuroraCurvedHeaderBox(
                    curveDepthDp = 24.dp,
                    modifier = Modifier.padding(bottom = 6.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp, bottom = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Top Bar: Left circular frosted button · "Message" · Right circular More button
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            FrostedCircleIconButton(
                                icon = if (isSearchExpanded) {
                                    Icons.Filled.Close
                                } else {
                                    Icons.AutoMirrored.Filled.KeyboardArrowLeft
                                },
                                contentDescription = if (isSearchExpanded) "Close search" else "Open Lounge",
                                onClick = {
                                    if (isSearchExpanded) {
                                        isSearchExpanded = false
                                        searchQuery = ""
                                    } else {
                                        onOpenLounge()
                                    }
                                },
                                modifier = Modifier.testTag("header_left_lounge_button"),
                            )

                            Text(
                                text = "Message",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = palette.textPrimary,
                            )

                            Box {
                                FrostedCircleIconButton(
                                    icon = Icons.Filled.MoreVert,
                                    contentDescription = "More options",
                                    onClick = { showTopOverflowMenu = true },
                                    modifier = Modifier.testTag("header_more_menu_button"),
                                )

                                DropdownMenu(
                                    expanded = showTopOverflowMenu,
                                    onDismissRequest = { showTopOverflowMenu = false },
                                    modifier = Modifier.background(palette.surface1),
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Search messages", color = palette.textPrimary) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Filled.Search,
                                                contentDescription = null,
                                                tint = palette.accentSecondary,
                                            )
                                        },
                                        onClick = {
                                            showTopOverflowMenu = false
                                            isSearchExpanded = !isSearchExpanded
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Live Lounge Grid", color = palette.textPrimary) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Filled.GraphicEq,
                                                contentDescription = null,
                                                tint = palette.accent,
                                            )
                                        },
                                        onClick = {
                                            showTopOverflowMenu = false
                                            onOpenLounge()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Profile & Settings", color = palette.textPrimary) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Filled.Person,
                                                contentDescription = null,
                                                tint = palette.encryptedGreen,
                                            )
                                        },
                                        onClick = {
                                            showTopOverflowMenu = false
                                            onOpenIdentity()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Broadcast Presence Wave", color = palette.textPrimary) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Filled.Radar,
                                                contentDescription = null,
                                                tint = palette.accentSecondary,
                                            )
                                        },
                                        onClick = {
                                            showTopOverflowMenu = false
                                            onAnnouncePulse()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("About & Security Guide", color = palette.textPrimary) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Filled.HelpOutline,
                                                contentDescription = null,
                                                tint = palette.textSecondary,
                                            )
                                        },
                                        onClick = {
                                            showTopOverflowMenu = false
                                            onOpenGuide()
                                        },
                                    )
                                }
                            }
                        }

                        // Collapsible Search Field
                        AnimatedVisibility(
                            visible = isSearchExpanded,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 12.dp)
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(palette.surface1.copy(alpha = 0.90f))
                                    .border(1.dp, palette.glassBorder, RoundedCornerShape(24.dp))
                                    .padding(horizontal = 16.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = null,
                                    tint = palette.textMuted,
                                    modifier = Modifier.size(18.dp),
                                )
                                Box(modifier = Modifier.weight(1f)) {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = "Search conversations…",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = palette.textMuted,
                                        )
                                    }
                                    BasicTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                                            color = palette.textPrimary,
                                        ),
                                        cursorBrush = SolidColor(palette.accentSecondary),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("conversations_search_input"),
                                    )
                                }
                                if (searchQuery.isNotEmpty()) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "Clear",
                                        tint = palette.textMuted,
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clickable { searchQuery = "" },
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Curved 5-Avatar Constellation Arc + "Add note" Speech Bubble
                        CurvedAvatarConstellationArc(
                            myNickname = myNickname,
                            myPeerIdHex = myPeerIdHex,
                            statusNote = userStatusNote,
                            peers = peers.filter { !it.isBlocked },
                            onAddNoteClick = {
                                draftStatusNote = if (userStatusNote == "Add note") "" else userStatusNote
                                showAddNoteDialog = true
                            },
                            onPeerClick = { peer ->
                                onOpenConversation(peer.peerIdHex)
                            },
                            onFallbackOrbitClick = {
                                onOpenLounge()
                            },
                        )
                    }
                }
            }

            // 2. Segmented 3-Tab Pill Switcher (Inbox · Unread · Requests)
            item(key = "segmented_filter_bar") {
                SegmentedInboxPillBar(
                    selectedFilter = selectedFilter,
                    inboxCount = inboxBadgeCount,
                    unreadCount = unreadCounts.values.sum(),
                    requestsCount = peers.count { !it.isBlocked && !it.isVerified },
                    onSelectFilter = { next ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        selectedFilter = next
                    },
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                )
            }

            // 3. Conversation List Rows
            if (filteredRows.isEmpty()) {
                item(key = "empty_filter_state") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = when (selectedFilter) {
                                ChatFilter.UNREAD -> "All caught up"
                                ChatFilter.REQUESTS -> "No pending requests"
                                ChatFilter.INBOX -> "No conversations yet"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.textPrimary,
                        )
                        Text(
                            text = "Tap + New below to start a conversation or open the Public Lounge.",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                items(
                    items = filteredRows,
                    key = { it.conversationKey },
                ) { row ->
                    LuxuryConversationItemRow(
                        row = row,
                        isBroadcast = row.conversationKey == MeshStore.BROADCAST_CONVERSATION_KEY,
                        onlineCount = maxOf(connectedBleCount, peers.count { it.isDirectlyConnected }),
                        onClick = { onOpenConversation(row.conversationKey) },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            contextMenuTarget = row
                        },
                        onSwipePin = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onTogglePin(row.conversationKey)
                        },
                        onSwipeMute = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggleMute(row.conversationKey)
                        },
                    )
                }
            }
        }

        // 4. Bottom Floating Capsule (+ New · Mic · Send) matching Screen 1 of the reference image
        FloatingHomeComposerCapsule(
            onNewClick = { showNewMessageSheet = true },
            onMicClick = { onOpenConversation(MeshStore.BROADCAST_CONVERSATION_KEY) },
            onSendClick = {
                val target = dmRows.firstOrNull()?.conversationKey ?: MeshStore.BROADCAST_CONVERSATION_KEY
                onOpenConversation(target)
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp),
        )
    }

    // "Add note" modal dialog
    if (showAddNoteDialog) {
        AlertDialog(
            onDismissRequest = { showAddNoteDialog = false },
            containerColor = palette.surface1,
            titleContentColor = palette.textPrimary,
            textContentColor = palette.textSecondary,
            title = {
                Text(
                    text = "Share a Status Note",
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Your note floats above your avatar and is broadcast to nearby people.",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(palette.canvas)
                            .border(1.dp, palette.accent.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        if (draftStatusNote.isEmpty()) {
                            Text(
                                text = "What's on your mind?",
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.textMuted,
                            )
                        }
                        BasicTextField(
                            value = draftStatusNote,
                            onValueChange = { draftStatusNote = it.take(36) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = palette.textPrimary,
                            ),
                            cursorBrush = SolidColor(palette.accentSecondary),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = draftStatusNote.trim()
                        if (clean.isNotEmpty()) {
                            userStatusNote = clean
                            onPublishNote(clean)
                        } else {
                            userStatusNote = "Add note"
                        }
                        showAddNoteDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.accent,
                        contentColor = Color.White,
                    ),
                ) {
                    Text("Share Note")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddNoteDialog = false }) {
                    Text("Cancel", color = palette.textSecondary)
                }
            },
        )
    }

    // "+ New" Bottom Sheet (Quick Message / Lounge / Broadcast)
    if (showNewMessageSheet) {
        ModalBottomSheet(
            onDismissRequest = { showNewMessageSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = palette.surface1,
            contentColor = palette.textPrimary,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 28.dp),
            ) {
                Text(
                    text = "New Conversation",
                    style = MaterialTheme.typography.titleLarge,
                    color = palette.textPrimary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                ListItem(
                    headlineContent = { Text("Public Lounge", fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text("Message everyone nearby") },
                    leadingContent = {
                        PeerAvatar(
                            nickname = "Public Lounge",
                            peerIdHex = "lounge",
                            size = 44.dp,
                            isBroadcastChannel = true,
                            isOnline = isMeshRunning,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = palette.surface1),
                    modifier = Modifier.clickable {
                        showNewMessageSheet = false
                        onOpenConversation(MeshStore.BROADCAST_CONVERSATION_KEY)
                    },
                )
                ListItem(
                    headlineContent = { Text("Live Lounge Grid", fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text("Explore nearby people & send quick emoji waves") },
                    leadingContent = {
                        FrostedCircleIconButton(
                            icon = Icons.Filled.GraphicEq,
                            contentDescription = null,
                            onClick = {
                                showNewMessageSheet = false
                                onOpenLounge()
                            },
                            size = 44.dp,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = palette.surface1),
                    modifier = Modifier.clickable {
                        showNewMessageSheet = false
                        onOpenLounge()
                    },
                )
                peers.filter { !it.isBlocked }.forEach { peer ->
                    ListItem(
                        headlineContent = { Text(peer.nickname, fontWeight = FontWeight.SemiBold) },
                        supportingContent = {
                            Text(if (peer.isDirectlyConnected) "Online nearby" else "Recent contact")
                        },
                        leadingContent = {
                            PeerAvatar(
                                nickname = peer.nickname,
                                peerIdHex = peer.peerIdHex,
                                size = 44.dp,
                                isOnline = peer.isDirectlyConnected,
                                isVerified = peer.isVerified,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = palette.surface1),
                        modifier = Modifier.clickable {
                            showNewMessageSheet = false
                            onOpenConversation(peer.peerIdHex)
                        },
                    )
                }
            }
        }
    }

    // Long-press Conversation Context Sheet
    val target = contextMenuTarget
    if (target != null) {
        val isBroadcast = target.conversationKey == MeshStore.BROADCAST_CONVERSATION_KEY
        ModalBottomSheet(
            onDismissRequest = { contextMenuTarget = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = palette.surface1,
            contentColor = palette.textPrimary,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PeerAvatar(
                        nickname = target.displayName,
                        peerIdHex = target.conversationKey,
                        size = 44.dp,
                        isBroadcastChannel = isBroadcast,
                        isOnline = target.peer?.isDirectlyConnected == true,
                        isVerified = target.peer?.isVerified == true,
                    )
                    Column {
                        Text(
                            text = target.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.textPrimary,
                        )
                        Text(
                            text = if (isBroadcast) "Public Lounge" else "Encrypted Direct Chat",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textSecondary,
                        )
                    }
                }

                ListItem(
                    headlineContent = {
                        Text(if (target.isPinned) "Unpin conversation" else "Pin to top")
                    },
                    leadingContent = {
                        Icon(
                            imageVector = if (target.isPinned) Icons.Outlined.PushPin else Icons.Filled.PushPin,
                            contentDescription = null,
                            tint = palette.accentSecondary,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = palette.surface1),
                    modifier = Modifier.clickable {
                        onTogglePin(target.conversationKey)
                        contextMenuTarget = null
                    },
                )

                ListItem(
                    headlineContent = {
                        Text(if (target.isMuted) "Unmute notifications" else "Mute notifications")
                    },
                    leadingContent = {
                        Icon(
                            imageVector = if (target.isMuted) {
                                Icons.Filled.NotificationsOff
                            } else {
                                Icons.Filled.NotificationsPaused
                            },
                            contentDescription = null,
                            tint = palette.warningAmber,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = palette.surface1),
                    modifier = Modifier.clickable {
                        onToggleMute(target.conversationKey)
                        contextMenuTarget = null
                    },
                )

                ListItem(
                    headlineContent = {
                        Text(if (target.unreadCount > 0) "Mark as read" else "Mark as unread")
                    },
                    leadingContent = {
                        Icon(
                            imageVector = if (target.unreadCount > 0) {
                                Icons.Filled.MarkChatRead
                            } else {
                                Icons.Filled.MarkChatUnread
                            },
                            contentDescription = null,
                            tint = palette.textSecondary,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = palette.surface1),
                    modifier = Modifier.clickable {
                        onToggleUnread(target.conversationKey)
                        contextMenuTarget = null
                    },
                )

                if (!isBroadcast && target.peer != null) {
                    ListItem(
                        headlineContent = { Text("Verify safety number") },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Filled.VerifiedUser,
                                contentDescription = null,
                                tint = palette.encryptedGreen,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = palette.surface1),
                        modifier = Modifier.clickable {
                            val peer = target.peer
                            contextMenuTarget = null
                            onVerifyPeer(peer)
                        },
                    )
                }

                if (!isBroadcast) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 6.dp),
                        color = palette.borderSubtle,
                    )
                    ListItem(
                        headlineContent = {
                            Text("Delete conversation", color = palette.dangerRed)
                        },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Filled.DeleteOutline,
                                contentDescription = null,
                                tint = palette.dangerRed,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = palette.surface1),
                        modifier = Modifier.clickable {
                            val key = target.conversationKey
                            contextMenuTarget = null
                            onDeleteConversation(key)
                        },
                    )
                }
            }
        }
    }
}

/**
 * The signature 5-Avatar Curved Constellation Arc + floating "Add note" speech bubble
 * and bottom "+" badge from Screen 1 of the reference image.
 */
@Composable
private fun CurvedAvatarConstellationArc(
    myNickname: String,
    myPeerIdHex: String,
    statusNote: String,
    peers: List<PeerUiModel>,
    onAddNoteClick: () -> Unit,
    onPeerClick: (PeerUiModel) -> Unit,
    onFallbackOrbitClick: () -> Unit,
) {
    val palette = LocalCypherPalette.current
    val floatTransition = rememberInfiniteTransition(label = "note_float")
    val noteFloatOffset by floatTransition.animateFloat(
        initialValue = -2f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "note_y",
    )

    // Pick up to 4 peers for the 4 flanking orbit positions; fill remaining slots with ambient seeds
    val slot0 = peers.getOrNull(0)
    val slot1 = peers.getOrNull(1)
    val slot2 = peers.getOrNull(2)
    val slot3 = peers.getOrNull(3)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Floating "Add note" gradient speech bubble with downward pointer triangle
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .offset(y = noteFloatOffset.dp)
                .clickable(onClick = onAddNoteClick)
                .testTag("add_note_bubble_button"),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(palette.primaryPillBrush)
                    .padding(horizontal = 16.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = statusNote,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.5.sp,
                    ),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Speech-bubble triangle pointer
            Canvas(modifier = Modifier.size(width = 12.dp, height = 6.dp)) {
                val path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width / 2f, size.height)
                    close()
                }
                drawPath(path = path, color = Color(0xFF4A88FF))
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 5-Avatar Curved Constellation Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Far-Left Orbit Avatar (highest on the arc)
            Box(
                modifier = Modifier
                    .offset(y = (-28).dp)
                    .clip(CircleShape)
                    .clickable {
                        if (slot0 != null) onPeerClick(slot0) else onFallbackOrbitClick()
                    },
            ) {
                PeerAvatar(
                    nickname = slot0?.nickname ?: "Jenny",
                    peerIdHex = slot0?.peerIdHex ?: "orbit_slot_0",
                    size = 44.dp,
                    isOnline = slot0?.isDirectlyConnected == true,
                )
            }

            // Inner-Left Orbit Avatar (mid-arc)
            Box(
                modifier = Modifier
                    .offset(y = (-8).dp)
                    .clip(CircleShape)
                    .clickable {
                        if (slot1 != null) onPeerClick(slot1) else onFallbackOrbitClick()
                    },
            ) {
                PeerAvatar(
                    nickname = slot1?.nickname ?: "Guy",
                    peerIdHex = slot1?.peerIdHex ?: "orbit_slot_1",
                    size = 50.dp,
                    isOnline = slot1?.isDirectlyConnected == true,
                )
            }

            // Center Hero Avatar with glowing ring + bottom "+" button
            Box(
                contentAlignment = Alignment.BottomCenter,
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .clickable(onClick = onAddNoteClick),
            ) {
                PeerAvatar(
                    nickname = myNickname,
                    peerIdHex = myPeerIdHex.ifBlank { "self_hero_portrait" },
                    size = 84.dp,
                    hasGlowRing = true,
                )
                Box(
                    modifier = Modifier
                        .offset(y = 10.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(2.5.dp, palette.canvas, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Add status note",
                        tint = Color(0xFF0A0F1D),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            // Inner-Right Orbit Avatar (mid-arc)
            Box(
                modifier = Modifier
                    .offset(y = (-8).dp)
                    .clip(CircleShape)
                    .clickable {
                        if (slot2 != null) onPeerClick(slot2) else onFallbackOrbitClick()
                    },
            ) {
                PeerAvatar(
                    nickname = slot2?.nickname ?: "Daniel",
                    peerIdHex = slot2?.peerIdHex ?: "orbit_slot_2",
                    size = 50.dp,
                    isOnline = slot2?.isDirectlyConnected == true,
                )
            }

            // Far-Right Orbit Avatar (highest on the arc)
            Box(
                modifier = Modifier
                    .offset(y = (-28).dp)
                    .clip(CircleShape)
                    .clickable {
                        if (slot3 != null) onPeerClick(slot3) else onFallbackOrbitClick()
                    },
            ) {
                PeerAvatar(
                    nickname = slot3?.nickname ?: "Kristin",
                    peerIdHex = slot3?.peerIdHex ?: "orbit_slot_3",
                    size = 44.dp,
                    isOnline = slot3?.isDirectlyConnected == true,
                )
            }
        }
    }
}

/**
 * 3-Segment Frosted Pill Bar ("Inbox 26" · "Unread" · "Requests") matching Screen 1.
 */
@Composable
private fun SegmentedInboxPillBar(
    selectedFilter: ChatFilter,
    inboxCount: Int,
    unreadCount: Int,
    requestsCount: Int,
    onSelectFilter: (ChatFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalCypherPalette.current

    Surface(
        color = Color(0xFF111827),
        shape = RoundedCornerShape(30.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(30.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChatFilter.entries.forEach { filter ->
                val selected = selectedFilter == filter
                val labelText = when (filter) {
                    ChatFilter.INBOX -> "Inbox $inboxCount"
                    ChatFilter.UNREAD -> if (unreadCount > 0) "Unread $unreadCount" else "Unread"
                    ChatFilter.REQUESTS -> if (requestsCount > 0) "Requests $requestsCount" else "Requests"
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .then(
                            if (selected) {
                                Modifier.background(palette.primaryPillBrush)
                            } else {
                                Modifier.background(Color.Transparent)
                            },
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelectFilter(filter) },
                        )
                        .testTag("filter_chip_${filter.name.lowercase()}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = labelText,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 13.5.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        ),
                        color = if (selected) Color.White else Color(0xFF7D89A3),
                    )
                }
            }
        }
    }
}

/**
 * Clean, spacious conversation row matching Screen 1:
 * Left 52.dp portrait avatar · Bold Name + 1-line preview · Timestamp + Right circular Bell button.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LuxuryConversationItemRow(
    row: ConversationRowSummary,
    isBroadcast: Boolean,
    onlineCount: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSwipePin: () -> Unit,
    onSwipeMute: () -> Unit,
) {
    val palette = LocalCypherPalette.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val offsetX = remember { Animatable(0f) }
    val swipeThresholdPx = with(density) { 78.dp.toPx() }

    val previewText = when {
        !row.draftText.isNullOrBlank() -> "Draft: ${row.draftText}"
        row.lastMessage != null -> {
            val msg = row.lastMessage
            val body = when (msg.attachment?.kind) {
                AttachmentKind.IMAGE -> "Sent a photo"
                AttachmentKind.AUDIO -> "Voice message"
                AttachmentKind.FILE -> msg.attachment.fileName
                null -> msg.text
            }
            if (isBroadcast && !msg.isOutgoing && !msg.isSystem) {
                "${msg.senderNickname}: $body"
            } else {
                body
            }
        }
        isBroadcast -> if (onlineCount > 0) {
            "$onlineCount people nearby · Tap to say hi"
        } else {
            "Tap to share a message with everyone nearby"
        }
        row.peer?.isDirectlyConnected == true -> "Online nearby · Say hello 👋"
        else -> "Start an encrypted conversation"
    }

    val timestampLabel = row.lastMessage?.timestampMs?.let { formatInboxTimestamp(it) }
        ?: if (isBroadcast || row.peer?.isDirectlyConnected == true) "Now" else ""

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        // Underlying swipe action reveal
        if (abs(offsetX.value) > 6f) {
            val isPinning = offsetX.value > 0f
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (isPinning) palette.accent.copy(alpha = 0.22f) else palette.warningAmberSoft,
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = if (isPinning) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = if (isPinning) Icons.Filled.PushPin else Icons.Filled.NotificationsOff,
                    contentDescription = null,
                    tint = if (isPinning) palette.accentSecondary else palette.warningAmber,
                )
            }
        }

        Row(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(palette.canvas)
                .pointerInput(row.conversationKey) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val current = offsetX.value
                            if (current >= swipeThresholdPx) {
                                onSwipePin()
                            } else if (current <= -swipeThresholdPx) {
                                onSwipeMute()
                            }
                            scope.launch {
                                offsetX.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                )
                            }
                        },
                        onDragCancel = {
                            scope.launch { offsetX.animateTo(0f) }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            scope.launch {
                                val next = (offsetX.value + dragAmount * 0.65f)
                                    .coerceIn(-swipeThresholdPx * 1.25f, swipeThresholdPx * 1.25f)
                                offsetX.snapTo(next)
                            }
                        },
                    )
                }
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .padding(horizontal = 8.dp, vertical = 11.dp)
                .testTag(
                    if (isBroadcast) {
                        "mesh_broadcast_row"
                    } else {
                        "conversation_row_${row.conversationKey}"
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PeerAvatar(
                nickname = row.displayName,
                peerIdHex = row.conversationKey,
                size = 52.dp,
                isBroadcastChannel = isBroadcast,
                isOnline = if (isBroadcast) onlineCount > 0 else (row.peer?.isDirectlyConnected == true),
                isVerified = row.peer?.isVerified == true,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = row.displayName,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (row.isPinned) {
                        Icon(
                            imageVector = Icons.Filled.PushPin,
                            contentDescription = "Pinned",
                            tint = palette.accentSecondary,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    if (row.lastMessage?.isOutgoing == true && row.draftText.isNullOrBlank()) {
                        DeliveryReceiptIcon(state = row.lastMessage.delivery)
                    }
                    Text(
                        text = previewText,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.5.sp,
                            fontWeight = if (row.unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = when {
                            !row.draftText.isNullOrBlank() -> palette.accentSecondary
                            row.unreadCount > 0 -> palette.textPrimary
                            else -> Color(0xFF8A96B0)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Right side: Timestamp + Circular notification bell / unread badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (timestampLabel.isNotEmpty()) {
                    Text(
                        text = timestampLabel,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = Color(0xFF6D7993),
                    )
                }

                if (row.unreadCount > 0) {
                    UnreadCountBadge(
                        count = row.unreadCount,
                        isMuted = row.isMuted,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF141C2E))
                            .border(1.dp, Color.White.copy(alpha = 0.06f), CircleShape)
                            .clickable { onSwipeMute() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (row.isMuted) {
                                Icons.Filled.NotificationsOff
                            } else {
                                Icons.Filled.NotificationsNone
                            },
                            contentDescription = if (row.isMuted) "Muted" else "Notifications active",
                            tint = if (row.isMuted) Color(0xFF64748B) else Color(0xFF94A3B8),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Floating bottom capsule ("+ New" · Mic · Send) matching the bottom of Screen 1 in the reference image.
 */
@Composable
private fun FloatingHomeComposerCapsule(
    onNewClick: () -> Unit,
    onMicClick: () -> Unit,
    onSendClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color(0xFF121826),
        shape = RoundedCornerShape(36.dp),
        shadowElevation = 14.dp,
        modifier = modifier
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(36.dp)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // "+ New" pill button
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .clickable(onClick = onNewClick)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .testTag("home_new_message_button"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "New",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.5.sp,
                    ),
                    color = Color.White,
                )
            }

            // Circular Mic button
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1B2438))
                    .clickable(onClick = onMicClick)
                    .testTag("home_mic_button"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = "Voice note",
                    tint = Color(0xFFB8C4DC),
                    modifier = Modifier.size(19.dp),
                )
            }

            // Circular Paper-Plane Send button
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1B2438))
                    .clickable(onClick = onSendClick)
                    .testTag("home_send_button"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Open chat",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

private fun formatInboxTimestamp(timestampMs: Long): String {
    if (timestampMs <= 0L) return ""
    val now = System.currentTimeMillis()
    val diffMs = now - timestampMs
    if (diffMs < 60_000L) return "Just now"
    if (diffMs < 3_600_000L) return "${(diffMs / 60_000L).coerceAtLeast(1)}mins ago"

    val calNow = Calendar.getInstance()
    val calMsg = Calendar.getInstance().apply { timeInMillis = timestampMs }
    val sameDay = calNow.get(Calendar.YEAR) == calMsg.get(Calendar.YEAR) &&
        calNow.get(Calendar.DAY_OF_YEAR) == calMsg.get(Calendar.DAY_OF_YEAR)
    if (sameDay) {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMs))
    }

    calNow.add(Calendar.DAY_OF_YEAR, -1)
    val yesterday = calNow.get(Calendar.YEAR) == calMsg.get(Calendar.YEAR) &&
        calNow.get(Calendar.DAY_OF_YEAR) == calMsg.get(Calendar.DAY_OF_YEAR)
    if (yesterday) return "Yesterday"

    return SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestampMs))
}
