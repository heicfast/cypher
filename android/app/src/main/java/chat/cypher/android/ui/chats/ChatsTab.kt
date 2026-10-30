package chat.cypher.android.ui.chats

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MarkChatRead
import androidx.compose.material.icons.filled.MarkChatUnread
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.NotificationsPaused
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.cypher.android.core.ChatMessage
import chat.cypher.android.core.MeshStore
import chat.cypher.android.core.PeerIdentity
import chat.cypher.android.core.PeerUiModel
import chat.cypher.android.ui.components.DeliveryReceiptIcon
import chat.cypher.android.ui.components.LiveMeshPulseDot
import chat.cypher.android.ui.components.PeerAvatar
import chat.cypher.android.ui.components.UnreadCountBadge
import chat.cypher.android.ui.theme.CypherMonoFontFamily
import chat.cypher.android.ui.theme.LocalCypherPalette
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

enum class ChatFilter(val label: String) {
    ALL("All"),
    UNREAD("Unread"),
    ONLINE("Online"),
    VERIFIED("Verified"),
    PINNED("Pinned"),
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
) {
    val palette = LocalCypherPalette.current
    val haptic = LocalHapticFeedback.current

    var isSearchExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(ChatFilter.ALL) }
    var contextMenuTarget by remember { mutableStateOf<ConversationRowSummary?>(null) }

    val peerMap = remember(peers) { peers.associateBy { it.peerIdHex.lowercase() } }

    val broadcastMessages = conversations[MeshStore.BROADCAST_CONVERSATION_KEY].orEmpty()
    val broadcastLastMessage = broadcastMessages.lastOrNull()
    val broadcastUnread = unreadCounts[MeshStore.BROADCAST_CONVERSATION_KEY] ?: 0
    val broadcastDraft = drafts[MeshStore.BROADCAST_CONVERSATION_KEY]

    val dmRows = remember(
        conversations,
        peers,
        unreadCounts,
        pinnedConversations,
        mutedConversations,
        knownPeerNames,
        drafts,
        searchQuery,
        selectedFilter,
    ) {
        val allKeys = (
            conversations.keys.filter { it != MeshStore.BROADCAST_CONVERSATION_KEY } +
                peers.map { it.peerIdHex.lowercase() }
            ).toSet()

        allKeys.mapNotNull { key ->
            val peer = peerMap[key]
            if (peer?.isBlocked == true) return@mapNotNull null
            val msgs = conversations[key].orEmpty()
            val lastMsg = msgs.lastOrNull()
            val unread = unreadCounts[key] ?: 0
            val isPinned = pinnedConversations.contains(key)
            val draft = drafts[key]?.takeIf { it.isNotBlank() }

            // Only show rows in the inbox that have messages, unread state, pinned state, draft, or active peer
            if (msgs.isEmpty() && unread == 0 && !isPinned && draft == null && peer == null) {
                return@mapNotNull null
            }

            val displayName = peer?.nickname
                ?: knownPeerNames[key]
                ?: msgs.lastOrNull { !it.isOutgoing }?.senderNickname
                ?: "peer-${key.take(4)}"

            ConversationRowSummary(
                conversationKey = key,
                peer = peer,
                displayName = displayName,
                lastMessage = lastMsg,
                unreadCount = unread,
                isPinned = isPinned,
                isMuted = mutedConversations.contains(key),
                draftText = draft,
            )
        }.filter { row ->
            val matchesFilter = when (selectedFilter) {
                ChatFilter.ALL -> true
                ChatFilter.UNREAD -> row.unreadCount > 0
                ChatFilter.ONLINE -> row.peer?.isDirectlyConnected == true || row.peer?.sessionState == "established"
                ChatFilter.VERIFIED -> row.peer?.isVerified == true
                ChatFilter.PINNED -> row.isPinned
            }
            val q = searchQuery.trim().lowercase()
            val matchesQuery = q.isEmpty() ||
                row.displayName.lowercase().contains(q) ||
                row.conversationKey.contains(q) ||
                (row.lastMessage?.text?.lowercase()?.contains(q) == true)
            matchesFilter && matchesQuery
        }.sortedWith(
            compareByDescending<ConversationRowSummary> { it.isPinned }
                .thenByDescending { it.unreadCount > 0 }
                .thenByDescending { it.lastMessage?.timestampMs ?: it.peer?.lastSeenMs ?: 0L },
        )
    }

    val onlinePeersCount = remember(peers, connectedBleCount) {
        maxOf(connectedBleCount, peers.count { it.isDirectlyConnected })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas),
    ) {
        // Top Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Cypher",
                        style = MaterialTheme.typography.headlineLarge,
                        color = palette.textPrimary,
                    )
                    // Interactive Mesh Pulse Pill
                    Surface(
                        color = if (isMeshRunning) palette.encryptedGreenSoft else palette.warningAmberSoft,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onAnnouncePulse()
                            }
                            .border(
                                width = 0.8.dp,
                                color = if (isMeshRunning) {
                                    palette.encryptedGreen.copy(alpha = 0.4f)
                                } else {
                                    palette.warningAmber.copy(alpha = 0.4f)
                                },
                                shape = RoundedCornerShape(50),
                            )
                            .testTag("mesh_status_pulse_pill"),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            LiveMeshPulseDot(isActive = isMeshRunning, size = 7.dp)
                            Text(
                                text = when {
                                    !isMeshRunning -> "RADIO PAUSED"
                                    onlinePeersCount > 0 -> "$onlinePeersCount NEARBY"
                                    else -> "MESH READY"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = CypherMonoFontFamily),
                                color = if (isMeshRunning) palette.encryptedGreen else palette.warningAmber,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                Text(
                    text = "End-to-end encrypted Bluetooth LE mesh",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    onClick = {
                        isSearchExpanded = !isSearchExpanded
                        if (!isSearchExpanded) searchQuery = ""
                    },
                    modifier = Modifier.testTag("toggle_search_button"),
                ) {
                    Icon(
                        imageVector = if (isSearchExpanded) Icons.Filled.Close else Icons.Filled.Search,
                        contentDescription = "Search conversations",
                        tint = if (isSearchExpanded) palette.accent else palette.textSecondary,
                    )
                }
                IconButton(
                    onClick = onOpenGuide,
                    modifier = Modifier.testTag("open_protocol_guide_button"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.HelpOutline,
                        contentDescription = "How Cypher works",
                        tint = palette.textSecondary,
                    )
                }
            }
        }

        // Collapsible Search Input
        AnimatedVisibility(
            visible = isSearchExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 10.dp),
            ) {
                Surface(
                    color = palette.surface1,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, palette.borderStrong, RoundedCornerShape(14.dp)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(18.dp),
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "Filter by peer name, hex ID, or message…",
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
                                cursorBrush = SolidColor(palette.accent),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("chats_search_input"),
                            )
                        }
                        if (searchQuery.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Clear search",
                                tint = palette.textMuted,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .clickable { searchQuery = "" },
                            )
                        }
                    }
                }
            }
        }

        // Filter Chips Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChatFilter.entries.forEach { filter ->
                val isSelected = selectedFilter == filter
                val bg by animateColorAsState(
                    targetValue = if (isSelected) palette.accentSoft else palette.surface1,
                    label = "chip_bg",
                )
                val border by animateColorAsState(
                    targetValue = if (isSelected) palette.accent else palette.borderSubtle,
                    label = "chip_border",
                )
                val fg by animateColorAsState(
                    targetValue = if (isSelected) palette.accent else palette.textSecondary,
                    label = "chip_fg",
                )

                Surface(
                    color = bg,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { selectedFilter = filter }
                        .border(1.dp, border, RoundedCornerShape(50))
                        .testTag("chat_filter_${filter.name.lowercase()}"),
                ) {
                    Text(
                        text = filter.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = fg,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("conversations_list"),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            // Active Nearby Mesh Nodes strip (when peers are discovered)
            val activeStripPeers = peers.filter { !it.isBlocked }.take(12)
            if (activeStripPeers.isNotEmpty()) {
                item(key = "nearby_peers_strip") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 10.dp),
                    ) {
                        Text(
                            text = "NEARBY NODES",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textMuted,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(activeStripPeers, key = { it.peerIdHex }) { peer ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .width(64.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { onOpenConversation(peer.peerIdHex) }
                                        .padding(vertical = 4.dp),
                                ) {
                                    PeerAvatar(
                                        nickname = peer.nickname,
                                        peerIdHex = peer.peerIdHex,
                                        size = 48.dp,
                                        isOnline = peer.isDirectlyConnected,
                                        isVerified = peer.isVerified,
                                    )
                                    Spacer(modifier = Modifier.height(5.dp))
                                    Text(
                                        text = peer.nickname,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = palette.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Pinned #mesh Broadcast Channel Hero Card
            if (selectedFilter == ChatFilter.ALL ||
                selectedFilter == ChatFilter.PINNED ||
                (selectedFilter == ChatFilter.UNREAD && broadcastUnread > 0)
            ) {
                item(key = MeshStore.BROADCAST_CONVERSATION_KEY) {
                    MeshBroadcastHeroCard(
                        lastMessage = broadcastLastMessage,
                        unreadCount = broadcastUnread,
                        draftText = broadcastDraft,
                        onlineCount = onlinePeersCount,
                        isMuted = mutedConversations.contains(MeshStore.BROADCAST_CONVERSATION_KEY),
                        onClick = { onOpenConversation(MeshStore.BROADCAST_CONVERSATION_KEY) },
                    )
                }
            }

            // Section header for Direct Messages
            item(key = "dm_section_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            tint = palette.encryptedGreen,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = "DIRECT MESSAGES · NOISE XX",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textMuted,
                        )
                    }
                    Text(
                        text = "Swipe row to pin or delete",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textMuted.copy(alpha = 0.75f),
                    )
                }
            }

            if (dmRows.isEmpty()) {
                item(key = "empty_dm_state") {
                    EmptyInboxCard(
                        isFiltered = selectedFilter != ChatFilter.ALL || searchQuery.isNotBlank(),
                        onOpenBroadcast = { onOpenConversation(MeshStore.BROADCAST_CONVERSATION_KEY) },
                        onPulseMesh = onAnnouncePulse,
                    )
                }
            } else {
                items(dmRows, key = { it.conversationKey }) { row ->
                    SwipeableConversationRow(
                        row = row,
                        onClick = { onOpenConversation(row.conversationKey) },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            contextMenuTarget = row
                        },
                        onSwipeRightPin = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onTogglePin(row.conversationKey)
                        },
                        onSwipeLeftDelete = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDeleteConversation(row.conversationKey)
                        },
                    )
                }
            }
        }
    }

    // Long-press Conversation Quick Actions Bottom Sheet
    val target = contextMenuTarget
    if (target != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { contextMenuTarget = null },
            sheetState = sheetState,
            containerColor = palette.surface0,
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
                            text = PeerIdentity.formatHandle(target.displayName, target.conversationKey),
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textSecondary,
                        )
                    }
                }

                SheetActionRow(
                    icon = if (target.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    label = if (target.isPinned) "Unpin conversation" else "Pin to top of inbox",
                    onClick = {
                        onTogglePin(target.conversationKey)
                        contextMenuTarget = null
                    },
                )

                SheetActionRow(
                    icon = if (target.unreadCount > 0) Icons.Filled.MarkChatRead else Icons.Filled.MarkChatUnread,
                    label = if (target.unreadCount > 0) "Mark as read" else "Mark as unread",
                    onClick = {
                        onToggleUnread(target.conversationKey)
                        contextMenuTarget = null
                    },
                )

                SheetActionRow(
                    icon = if (target.isMuted) Icons.Filled.NotificationsPaused else Icons.Filled.NotificationsOff,
                    label = if (target.isMuted) "Unmute notifications" else "Mute notifications",
                    onClick = {
                        onToggleMute(target.conversationKey)
                        contextMenuTarget = null
                    },
                )

                if (target.peer != null) {
                    SheetActionRow(
                        icon = Icons.Filled.VerifiedUser,
                        label = "Verify SHA-256 safety number",
                        onClick = {
                            val p = target.peer
                            contextMenuTarget = null
                            onVerifyPeer(p)
                        },
                    )
                }

                SheetActionRow(
                    icon = Icons.Filled.DeleteOutline,
                    label = "Delete conversation",
                    tint = palette.dangerRed,
                    onClick = {
                        val key = target.conversationKey
                        contextMenuTarget = null
                        onDeleteConversation(key)
                    },
                )
            }
        }
    }
}

@Composable
private fun MeshBroadcastHeroCard(
    lastMessage: ChatMessage?,
    unreadCount: Int,
    draftText: String?,
    onlineCount: Int,
    isMuted: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalCypherPalette.current

    Surface(
        color = palette.surface1,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        palette.accent.copy(alpha = 0.55f),
                        palette.encryptedGreen.copy(alpha = 0.35f),
                    ),
                ),
                shape = RoundedCornerShape(20.dp),
            )
            .testTag("mesh_broadcast_channel_card"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(palette.accentSoft)
                    .border(1.dp, palette.accent.copy(alpha = 0.45f), RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Public,
                    contentDescription = "Mesh broadcast channel",
                    tint = palette.accent,
                    modifier = Modifier.size(25.dp),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "#mesh",
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = CypherMonoFontFamily),
                            fontWeight = FontWeight.Bold,
                            color = palette.textPrimary,
                        )
                        Surface(
                            color = palette.accentSoft,
                            shape = RoundedCornerShape(50),
                        ) {
                            Text(
                                text = "PUBLIC RELAY",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.accent,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }

                    Text(
                        text = if (lastMessage != null) {
                            PeerIdentity.relativeTime(lastMessage.timestampMs)
                        } else {
                            "$onlineCount online"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (unreadCount > 0) palette.accent else palette.textMuted,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    val previewText = when {
                        !draftText.isNullOrBlank() -> "Draft: $draftText"
                        lastMessage != null -> {
                            val prefix = if (lastMessage.isOutgoing) "You: " else "${lastMessage.senderNickname}: "
                            prefix + lastMessage.text
                        }
                        else -> "Tap to broadcast across all nearby Bluetooth LE nodes…"
                    }

                    Text(
                        text = previewText,
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            !draftText.isNullOrBlank() -> palette.warningAmber
                            unreadCount > 0 -> palette.textPrimary
                            else -> palette.textSecondary
                        },
                        fontWeight = if (unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )

                    if (unreadCount > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        UnreadCountBadge(count = unreadCount, isMuted = isMuted)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwipeableConversationRow(
    row: ConversationRowSummary,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSwipeRightPin: () -> Unit,
    onSwipeLeftDelete: () -> Unit,
) {
    val palette = LocalCypherPalette.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val thresholdPx = with(density) { 88.dp.toPx() }
    val maxDragPx = with(density) { 128.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        // Background Gesture Action Reveal Layer
        val currentOffset = offsetX.value
        if (abs(currentOffset) > 4f) {
            val isRightSwipe = currentOffset > 0f
            val bgColor = if (isRightSwipe) palette.accentSoft else palette.dangerRedSoft
            val iconColor = if (isRightSwipe) palette.accent else palette.dangerRed
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(18.dp))
                    .background(bgColor)
                    .padding(horizontal = 20.dp),
                contentAlignment = if (isRightSwipe) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = if (isRightSwipe) Icons.Filled.PushPin else Icons.Filled.DeleteOutline,
                        contentDescription = if (isRightSwipe) "Pin conversation" else "Delete conversation",
                        tint = iconColor,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = if (isRightSwipe) {
                            if (row.isPinned) "UNPIN" else "PIN"
                        } else {
                            "DELETE"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = iconColor,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // Foreground Card
        Surface(
            color = if (row.unreadCount > 0) palette.surface1 else palette.surface0,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .clip(RoundedCornerShape(18.dp))
                .border(
                    width = 1.dp,
                    color = if (row.unreadCount > 0) {
                        palette.accent.copy(alpha = 0.35f)
                    } else {
                        palette.borderSubtle
                    },
                    shape = RoundedCornerShape(18.dp),
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .pointerInput(row.conversationKey) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val finalX = offsetX.value
                            scope.launch {
                                when {
                                    finalX >= thresholdPx -> {
                                        onSwipeRightPin()
                                        offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                                    }
                                    finalX <= -thresholdPx -> {
                                        offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                                        onSwipeLeftDelete()
                                    }
                                    else -> {
                                        offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                                    }
                                }
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val next = (offsetX.value + dragAmount).coerceIn(-maxDragPx, maxDragPx)
                            scope.launch { offsetX.snapTo(next) }
                        },
                    )
                }
                .testTag("conversation_row_${row.conversationKey.take(8)}"),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PeerAvatar(
                    nickname = row.displayName,
                    peerIdHex = row.conversationKey,
                    size = 48.dp,
                    isOnline = row.peer?.isDirectlyConnected == true,
                    isVerified = row.peer?.isVerified == true,
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = row.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (row.peer?.isVerified == true) {
                                Icon(
                                    imageVector = Icons.Filled.VerifiedUser,
                                    contentDescription = "Verified",
                                    tint = palette.encryptedGreen,
                                    modifier = Modifier.size(14.dp),
                                )
                            } else if (row.peer?.sessionState == "established") {
                                Icon(
                                    imageVector = Icons.Filled.Lock,
                                    contentDescription = "Encrypted",
                                    tint = palette.encryptedGreen,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                            if (row.isPinned) {
                                Icon(
                                    imageVector = Icons.Filled.PushPin,
                                    contentDescription = "Pinned",
                                    tint = palette.accent,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                            if (row.isMuted) {
                                Icon(
                                    imageVector = Icons.Filled.NotificationsOff,
                                    contentDescription = "Muted",
                                    tint = palette.textMuted,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                        }

                        val timestampMs = row.lastMessage?.timestampMs ?: row.peer?.lastSeenMs ?: 0L
                        if (timestampMs > 0L) {
                            Text(
                                text = PeerIdentity.relativeTime(timestampMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (row.unreadCount > 0) palette.accent else palette.textMuted,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            if (row.draftText == null && row.lastMessage?.isOutgoing == true) {
                                DeliveryReceiptIcon(state = row.lastMessage.delivery)
                            }
                            val preview = when {
                                !row.draftText.isNullOrBlank() -> "Draft: ${row.draftText}"
                                row.lastMessage != null -> row.lastMessage.text
                                row.peer?.isDirectlyConnected == true -> "Nearby on BLE mesh · Tap to message"
                                else -> "Noise XX encrypted direct channel"
                            }
                            Text(
                                text = preview,
                                style = MaterialTheme.typography.bodySmall,
                                color = when {
                                    !row.draftText.isNullOrBlank() -> palette.warningAmber
                                    row.unreadCount > 0 -> palette.textPrimary
                                    else -> palette.textSecondary
                                },
                                fontWeight = if (row.unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        if (row.unreadCount > 0) {
                            Spacer(modifier = Modifier.width(8.dp))
                            UnreadCountBadge(count = row.unreadCount, isMuted = row.isMuted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyInboxCard(
    isFiltered: Boolean,
    onOpenBroadcast: () -> Unit,
    onPulseMesh: () -> Unit,
) {
    val palette = LocalCypherPalette.current
    Surface(
        color = palette.surface1,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .border(1.dp, palette.borderSubtle, RoundedCornerShape(20.dp)),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(palette.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Radar,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(26.dp),
                )
            }
            Text(
                text = if (isFiltered) "No Matching Conversations" else "Listening for Nearby Cypher Nodes",
                style = MaterialTheme.typography.titleMedium,
                color = palette.textPrimary,
            )
            Text(
                text = if (isFiltered) {
                    "Try clearing your active filter or search query to view all mesh conversations."
                } else {
                    "Other Cypher devices within Bluetooth LE range will appear automatically. You can also post in #mesh to reach everyone nearby."
                },
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Surface(
                    color = palette.accentSoft,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onOpenBroadcast)
                        .border(1.dp, palette.accent.copy(alpha = 0.4f), RoundedCornerShape(50)),
                ) {
                    Text(
                        text = "Open #mesh",
                        style = MaterialTheme.typography.labelLarge,
                        color = palette.accent,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }

                Surface(
                    color = palette.surface2,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onPulseMesh)
                        .border(1.dp, palette.borderStrong, RoundedCornerShape(50)),
                ) {
                    Text(
                        text = "Broadcast Presence",
                        style = MaterialTheme.typography.labelLarge,
                        color = palette.textPrimary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color? = null,
    onClick: () -> Unit,
) {
    val palette = LocalCypherPalette.current
    val resolvedTint = tint ?: palette.textPrimary
    ListItem(
        headlineContent = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = resolvedTint,
            )
        },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = resolvedTint,
            )
        },
        colors = ListItemDefaults.colors(containerColor = palette.surface0),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}
