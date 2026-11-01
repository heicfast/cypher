package chat.cypher.android.ui.chats

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.cypher.android.core.ChatMessage
import chat.cypher.android.core.ConversationBackup
import chat.cypher.android.core.MeshController
import chat.cypher.android.core.MeshStore
import chat.cypher.android.ui.components.CipherChainLogo
import chat.cypher.android.ui.components.PeerAvatar
import chat.cypher.android.ui.components.UnreadCountBadge
import chat.cypher.android.ui.contacts.ContactsTab
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class CipherHomeSegment(val label: String) {
    CHATS("Chats"),
    CHANNELS("Channels"),
    PEOPLE("People"),
}

private data class ConversationRowItem(
    val conversationKey: String,
    val title: String,
    val subtitle: String,
    val timestampLabel: String,
    val lastTimestampMs: Long,
    val unreadCount: Int,
    val isPinned: Boolean,
    val isMuted: Boolean,
)

private data class ChannelDirectoryEntry(
    val channelKey: String,
    val displayTitle: String,
    val subtitle: String,
)

private val PRESET_CHANNELS = listOf(
    ChannelDirectoryEntry(
        channelKey = "#9q_k6",
        displayTitle = "#9q_k6",
        subtitle = "~ 3 mi • CA and AZ",
    ),
    ChannelDirectoryEntry(
        channelKey = "#city-3",
        displayTitle = "City #3",
        subtitle = "~ 3 mi • Athens",
    ),
    ChannelDirectoryEntry(
        channelKey = "#mesh-2",
        displayTitle = "Mesh #2",
        subtitle = "Bluetooth • 30–160 ft",
    ),
    ChannelDirectoryEntry(
        channelKey = "#block-10",
        displayTitle = "Block #10",
        subtitle = "~ 0.1 mi • Athens",
    ),
    ChannelDirectoryEntry(
        channelKey = "#neighborhood-0",
        displayTitle = "Neighborhood #0",
        subtitle = "~ 0.8 mi • Athens",
    ),
)

private val HOME_CONVERSATION_ORDER = listOf(
    "#mesh",
    MeshStore.RICK_PEER_KEY,
    "#cipher-dev",
    "#nearby-chat",
    "#field-team",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatsTab(
    controller: MeshController,
    selectedSegment: CipherHomeSegment = CipherHomeSegment.CHATS,
    onSelectSegment: (CipherHomeSegment) -> Unit = {},
    onOpenConversation: (String) -> Unit,
    onOpenMeshNetwork: () -> Unit = {},
    onOpenSecurityVerification: (String?) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenPanicMode: () -> Unit = {},
    onConversationDeleted: (ConversationBackup) -> Unit = {},
) {
    val conversations by controller.store.conversations.collectAsState()
    val unreadCounts by controller.store.unreadCounts.collectAsState()
    val markedUnread by controller.store.markedUnreadConversations.collectAsState()
    val pinnedSet by controller.store.pinnedConversations.collectAsState()
    val mutedSet by controller.store.mutedConversations.collectAsState()
    val bookmarkedChannels by controller.store.bookmarkedChannels.collectAsState()
    val joinedChannels by controller.store.joinedChannels.collectAsState()
    val peers by controller.peers.collectAsState()

    var searchActive by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showComposeSheet by remember { mutableStateOf(false) }
    var contextMenuTargetKey by remember { mutableStateOf<String?>(null) }

    val conversationRows = remember(
        conversations,
        unreadCounts,
        markedUnread,
        pinnedSet,
        mutedSet,
        peers,
        searchQuery,
    ) {
        buildConversationRowItems(
            controller = controller,
            conversations = conversations,
            unreadCounts = unreadCounts,
            markedUnread = markedUnread,
            pinnedSet = pinnedSet,
            mutedSet = mutedSet,
            query = searchQuery,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0E17))
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top App Bar: Cipher Chain Logo (triple-tap -> Panic Mode) + "Cipher" + Search + 3-dot Menu
            CipherTopBar(
                searchActive = searchActive,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                onToggleSearch = {
                    searchActive = !searchActive
                    if (!searchActive) searchQuery = ""
                },
                showOverflowMenu = showOverflowMenu,
                onToggleOverflowMenu = { showOverflowMenu = it },
                onOpenMeshNetwork = onOpenMeshNetwork,
                onOpenSecurityVerification = { onOpenSecurityVerification(MeshStore.RICK_PEER_KEY) },
                onOpenSettings = onOpenSettings,
                onOpenPanicMode = onOpenPanicMode,
            )

            // 3-way Segmented Pill Bar: Chats | Channels | People
            SegmentedHomePillBar(
                selectedSegment = selectedSegment,
                onSelectSegment = onSelectSegment,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            when (selectedSegment) {
                CipherHomeSegment.CHATS -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("conversations_list"),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
                    ) {
                        itemsIndexed(
                            items = conversationRows,
                            key = { _, item -> item.conversationKey },
                        ) { index, item ->
                            Box {
                                ConversationListRow(
                                    item = item,
                                    onClick = { onOpenConversation(item.conversationKey) },
                                    onLongClick = { contextMenuTargetKey = item.conversationKey },
                                )
                                DropdownMenu(
                                    expanded = contextMenuTargetKey == item.conversationKey,
                                    onDismissRequest = { contextMenuTargetKey = null },
                                    containerColor = Color(0xFF1C1F30),
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (item.isPinned) "Unpin conversation" else "Pin conversation",
                                                color = Color.White,
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Outlined.PushPin,
                                                contentDescription = null,
                                                tint = Color(0xFFA78BFA),
                                            )
                                        },
                                        onClick = {
                                            controller.togglePinConversation(item.conversationKey)
                                            contextMenuTargetKey = null
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (item.isMuted) "Unmute notifications" else "Mute notifications",
                                                color = Color.White,
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Outlined.NotificationsOff,
                                                contentDescription = null,
                                                tint = Color(0xFF9CA3AF),
                                            )
                                        },
                                        onClick = {
                                            controller.toggleMuteConversation(item.conversationKey)
                                            contextMenuTargetKey = null
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text("Delete conversation", color = Color(0xFFF87171))
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Outlined.DeleteOutline,
                                                contentDescription = null,
                                                tint = Color(0xFFF87171),
                                            )
                                        },
                                        onClick = {
                                            val backup = controller.deleteConversationWithBackup(item.conversationKey)
                                            if (backup != null) {
                                                onConversationDeleted(backup)
                                            }
                                            contextMenuTargetKey = null
                                        },
                                    )
                                }
                            }
                            if (index < conversationRows.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 78.dp, end = 16.dp),
                                    thickness = 0.7.dp,
                                    color = Color(0xFF1E2133),
                                )
                            }
                        }
                    }
                }

                CipherHomeSegment.CHANNELS -> {
                    LocationChannelsScreenContent(
                        bookmarkedChannels = bookmarkedChannels,
                        joinedChannels = joinedChannels,
                        onJoinChannel = { rawTag ->
                            val normalized = controller.joinChannel(rawTag)
                            onOpenConversation(normalized)
                        },
                        onToggleBookmark = { tag ->
                            controller.toggleBookmarkChannel(tag)
                        },
                        onOpenChannel = { tag ->
                            val normalized = controller.joinChannel(tag)
                            onOpenConversation(normalized)
                        },
                    )
                }

                CipherHomeSegment.PEOPLE -> {
                    ContactsTab(
                        controller = controller,
                        onOpenConversation = onOpenConversation,
                        onOpenVerification = onOpenSecurityVerification,
                        onOpenSettings = onOpenSettings,
                        embeddedInHome = true,
                    )
                }
            }
        }

        // Floating Action Button on Chats tab (purple circle with white pencil icon)
        AnimatedVisibility(
            visible = selectedSegment == CipherHomeSegment.CHATS,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 22.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .shadow(
                        elevation = 14.dp,
                        shape = CircleShape,
                        ambientColor = Color(0xFF7C4DFF),
                        spotColor = Color(0xFF7C4DFF),
                    )
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF8B5CF6), Color(0xFF6938EF)),
                        )
                    )
                    .clickable { showComposeSheet = true }
                    .testTag("new_chat_fab"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = "New Message",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }

    if (showComposeSheet) {
        NewMessageBottomSheet(
            controller = controller,
            onDismiss = { showComposeSheet = false },
            onOpenConversation = { key ->
                showComposeSheet = false
                onOpenConversation(key)
            },
            onSwitchToChannels = {
                showComposeSheet = false
                onSelectSegment(CipherHomeSegment.CHANNELS)
            },
        )
    }
}

@Composable
private fun CipherTopBar(
    searchActive: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    showOverflowMenu: Boolean,
    onToggleOverflowMenu: (Boolean) -> Unit,
    onOpenMeshNetwork: () -> Unit,
    onOpenSecurityVerification: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPanicMode: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (!searchActive) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CipherChainLogo(
                    size = 28.dp,
                    onTripleTap = onOpenPanicMode,
                    modifier = Modifier.testTag("cipher_top_logo"),
                )
                Text(
                    text = "Cipher",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp,
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF181A28))
                    .border(1.dp, Color(0xFF2B2E44), RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (searchQuery.isEmpty()) {
                    Text(
                        text = "Search chats, channels, or peers...",
                        color = Color(0xFF787E99),
                        fontSize = 14.sp,
                    )
                }
                BasicTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 14.sp,
                    ),
                    cursorBrush = SolidColor(Color(0xFF8B5CF6)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("chats_search_input"),
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onToggleSearch,
                modifier = Modifier
                    .size(40.dp)
                    .testTag("chats_search_button"),
            ) {
                Icon(
                    imageVector = if (searchActive) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = if (searchActive) "Close search" else "Search",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }

            Box {
                IconButton(
                    onClick = { onToggleOverflowMenu(true) },
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("chats_overflow_button"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "More options",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }

                DropdownMenu(
                    expanded = showOverflowMenu,
                    onDismissRequest = { onToggleOverflowMenu(false) },
                    containerColor = Color(0xFF1A1D2E),
                ) {
                    DropdownMenuItem(
                        text = { Text("Mesh Network", color = Color.White) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Hub,
                                contentDescription = null,
                                tint = Color(0xFFA78BFA),
                            )
                        },
                        onClick = {
                            onToggleOverflowMenu(false)
                            onOpenMeshNetwork()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Security Verification", color = Color.White) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.VerifiedUser,
                                contentDescription = null,
                                tint = Color(0xFF4ADE80),
                            )
                        },
                        onClick = {
                            onToggleOverflowMenu(false)
                            onOpenSecurityVerification()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Settings", color = Color.White) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = null,
                                tint = Color(0xFFC4B5FD),
                            )
                        },
                        onClick = {
                            onToggleOverflowMenu(false)
                            onOpenSettings()
                        },
                    )
                    HorizontalDivider(color = Color(0xFF282C42))
                    DropdownMenuItem(
                        text = { Text("Panic Mode", color = Color(0xFFF87171)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.WarningAmber,
                                contentDescription = null,
                                tint = Color(0xFFF87171),
                            )
                        },
                        onClick = {
                            onToggleOverflowMenu(false)
                            onOpenPanicMode()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SegmentedHomePillBar(
    selectedSegment: CipherHomeSegment,
    onSelectSegment: (CipherHomeSegment) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(21.dp))
            .background(Color(0xFF171928))
            .border(1.dp, Color(0xFF25283D), RoundedCornerShape(21.dp))
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CipherHomeSegment.entries.forEach { segment ->
            val isSelected = segment == selectedSegment
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(18.dp))
                    .then(
                        if (isSelected) {
                            Modifier.background(
                                Brush.horizontalGradient(
                                    colors = listOf(Color(0xFF8352FD), Color(0xFF6938EF)),
                                )
                            )
                        } else {
                            Modifier
                        }
                    )
                    .clickable { onSelectSegment(segment) }
                    .testTag("segment_${segment.name.lowercase()}"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = segment.label,
                    color = if (isSelected) Color.White else Color(0xFF9CA3AF),
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationListRow(
    item: ConversationRowItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.5.dp)
            .testTag("conversation_row_${item.conversationKey}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PeerAvatar(
            nickname = item.title,
            peerIdHex = item.conversationKey,
            conversationKey = item.conversationKey,
            size = 48.dp,
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = item.timestampLabel,
                    color = Color(0xFF8E93AD),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Normal,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.subtitle,
                    color = Color(0xFF9CA3AF),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (item.unreadCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    UnreadCountBadge(count = item.unreadCount)
                }
            }
        }
    }
}

/**
 * Screen 5: Location Channels / Mesh Channels view matching the reference image pixel-by-pixel.
 */
@Composable
fun LocationChannelsScreenContent(
    bookmarkedChannels: Set<String>,
    joinedChannels: Set<String>,
    onJoinChannel: (String) -> Unit,
    onToggleBookmark: (String) -> Unit,
    onOpenChannel: (String) -> Unit,
) {
    var channelInput by rememberSaveable { mutableStateOf("") }

    val bookmarkedEntries = remember(bookmarkedChannels) {
        val defaults = listOf(
            ChannelDirectoryEntry("#9q_k6", "#9q_k6", "~ 3 mi • CA and AZ"),
            ChannelDirectoryEntry("#city-3", "City #3", "~ 3 mi • Athens"),
        )
        val extra = bookmarkedChannels
            .filter { tag -> defaults.none { it.channelKey.equals(tag, ignoreCase = true) } }
            .map { tag ->
                ChannelDirectoryEntry(
                    channelKey = tag,
                    displayTitle = formatChannelDisplayName(tag),
                    subtitle = "Bluetooth mesh • Bookmarked",
                )
            }
        defaults + extra
    }

    val nearbyEntries = remember(joinedChannels) {
        val defaults = listOf(
            ChannelDirectoryEntry("#mesh-2", "Mesh #2", "Bluetooth • 30–160 ft"),
            ChannelDirectoryEntry("#block-10", "Block #10", "~ 0.1 mi • Athens"),
            ChannelDirectoryEntry("#neighborhood-0", "Neighborhood #0", "~ 0.8 mi • Athens"),
            ChannelDirectoryEntry("#city-3", "City #3", "~ 3 mi • Athens"),
        )
        val customJoined = joinedChannels
            .filter { tag ->
                tag !in HOME_CONVERSATION_ORDER &&
                    defaults.none { it.channelKey.equals(tag, ignoreCase = true) } &&
                    !tag.equals("#9q_k6", ignoreCase = true)
            }
            .map { tag ->
                ChannelDirectoryEntry(
                    channelKey = tag,
                    displayTitle = formatChannelDisplayName(tag),
                    subtitle = "Bluetooth • Active channel",
                )
            }
        defaults + customJoined
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("location_channels_list"),
        contentPadding = PaddingValues(top = 8.dp, bottom = 36.dp),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.LocationOn,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = "Location Channels",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Chat with people near you using geohash channels. Only a coarse location is shared.",
                color = Color(0xFF8E93AD),
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )

            Spacer(modifier = Modifier.height(14.dp))

            // "# Enter geohash" + "Join" button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF171928))
                        .border(1.dp, Color(0xFF26293E), RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "#",
                        color = Color(0xFF8E93AD),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (channelInput.isEmpty()) {
                            Text(
                                text = "Enter geohash",
                                color = Color(0xFF6E738F),
                                fontSize = 14.sp,
                            )
                        }
                        BasicTextField(
                            value = channelInput,
                            onValueChange = { channelInput = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = Color.White,
                                fontSize = 14.sp,
                            ),
                            cursorBrush = SolidColor(Color(0xFF8B5CF6)),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    if (channelInput.isNotBlank()) {
                                        val target = channelInput.trim()
                                        channelInput = ""
                                        onJoinChannel(target)
                                    }
                                },
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("channel_geohash_input"),
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .height(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(Color(0xFF8352FD), Color(0xFF6938EF)),
                            )
                        )
                        .clickable {
                            val target = channelInput.trim().ifEmpty { "#9q_k6" }
                            channelInput = ""
                            onJoinChannel(target)
                        }
                        .padding(horizontal = 20.dp)
                        .testTag("channel_join_button"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Join",
                        color = Color.White,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "BOOKMARKED",
                color = Color(0xFF8E93AD),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF171928))
                    .border(1.dp, Color(0xFF25283D), RoundedCornerShape(20.dp)),
            ) {
                bookmarkedEntries.forEachIndexed { index, entry ->
                    ChannelDirectoryRow(
                        entry = entry,
                        isBookmarkedSection = true,
                        isBookmarked = bookmarkedChannels.contains(entry.channelKey) || index < 2,
                        onRowClick = { onOpenChannel(entry.channelKey) },
                        onActionClick = { onToggleBookmark(entry.channelKey) },
                    )
                    if (index < bookmarkedEntries.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp, end = 16.dp),
                            thickness = 0.7.dp,
                            color = Color(0xFF23263A),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "NEARBY CHANNELS",
                color = Color(0xFF8E93AD),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF171928))
                    .border(1.dp, Color(0xFF25283D), RoundedCornerShape(20.dp)),
            ) {
                nearbyEntries.forEachIndexed { index, entry ->
                    ChannelDirectoryRow(
                        entry = entry,
                        isBookmarkedSection = false,
                        isBookmarked = bookmarkedChannels.contains(entry.channelKey),
                        onRowClick = { onOpenChannel(entry.channelKey) },
                        onActionClick = { onJoinChannel(entry.channelKey) },
                    )
                    if (index < nearbyEntries.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp, end = 16.dp),
                            thickness = 0.7.dp,
                            color = Color(0xFF23263A),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelDirectoryRow(
    entry: ChannelDirectoryEntry,
    isBookmarkedSection: Boolean,
    isBookmarked: Boolean,
    onRowClick: () -> Unit,
    onActionClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRowClick)
            .padding(horizontal = 16.dp, vertical = 13.dp)
            .testTag("channel_row_${entry.channelKey}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Group,
            contentDescription = null,
            tint = Color(0xFFD0D5DD),
            modifier = Modifier.size(22.dp),
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = entry.displayTitle,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = entry.subtitle,
                color = Color(0xFF8E93AD),
                fontSize = 12.5.sp,
            )
        }

        if (isBookmarkedSection) {
            IconButton(
                onClick = onActionClick,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    contentDescription = "Bookmark channel",
                    tint = Color(0xFF8B5CF6),
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF261E46))
                    .border(1.dp, Color(0xFF3E306E), CircleShape)
                    .clickable(onClick = onActionClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Join ${entry.displayTitle}",
                    tint = Color(0xFFB69DF8),
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewMessageBottomSheet(
    controller: MeshController,
    onDismiss: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onSwitchToChannels: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var customTarget by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF151726),
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Text(
                text = "New Cipher Chat",
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Start an encrypted direct chat or join a mesh channel.",
                color = Color(0xFF8E93AD),
                fontSize = 13.5.sp,
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1D2034))
                        .border(1.dp, Color(0xFF2C314F), RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (customTarget.isEmpty()) {
                        Text(
                            text = "Enter #channel or peer ID...",
                            color = Color(0xFF727896),
                            fontSize = 14.sp,
                        )
                    }
                    BasicTextField(
                        value = customTarget,
                        onValueChange = { customTarget = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                        cursorBrush = SolidColor(Color(0xFF8B5CF6)),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Box(
                    modifier = Modifier
                        .height(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF8352FD), Color(0xFF6938EF)),
                            )
                        )
                        .clickable {
                            val clean = customTarget.trim()
                            if (clean.isNotEmpty()) {
                                val key = if (clean.startsWith("#")) {
                                    controller.joinChannel(clean)
                                } else {
                                    clean.lowercase()
                                }
                                onOpenConversation(key)
                            }
                        }
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Open",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Quick shortcuts
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF1B1E30))
                    .border(1.dp, Color(0xFF272B42), RoundedCornerShape(18.dp)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenConversation(MeshStore.RICK_PEER_KEY) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PeerAvatar(
                        nickname = "Rick",
                        peerIdHex = MeshStore.RICK_PEER_KEY,
                        conversationKey = MeshStore.RICK_PEER_KEY,
                        size = 40.dp,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Rick",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Nearby • Encrypted DM",
                            color = Color(0xFF8E93AD),
                            fontSize = 12.5.sp,
                        )
                    }
                }

                HorizontalDivider(color = Color(0xFF25293E), thickness = 0.7.dp)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSwitchToChannels() }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PeerAvatar(
                        nickname = "Mesh Group",
                        peerIdHex = "#mesh",
                        conversationKey = "#mesh",
                        size = 40.dp,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Browse Location Channels",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Join nearby Bluetooth & geohash channels",
                            color = Color(0xFF8E93AD),
                            fontSize = 12.5.sp,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

private fun buildConversationRowItems(
    controller: MeshController,
    conversations: Map<String, List<ChatMessage>>,
    unreadCounts: Map<String, Int>,
    markedUnread: Set<String>,
    pinnedSet: Set<String>,
    mutedSet: Set<String>,
    query: String,
): List<ConversationRowItem> {
    val allKeys = LinkedHashSet<String>()
    allKeys.addAll(HOME_CONVERSATION_ORDER)
    allKeys.addAll(conversations.keys)

    val cleanQuery = query.trim().lowercase()

    val items = allKeys.mapNotNull { key ->
        val msgs = conversations[key].orEmpty()
        val lastMsg = msgs.lastOrNull()
        val title = controller.resolveConversationTitle(key)
        val subtitle = when {
            lastMsg == null -> if (key.startsWith("#")) "Bluetooth mesh channel" else "Encrypted peer chat"
            key == "#mesh" && lastMsg.isOutgoing -> "You: ${lastMsg.text}"
            else -> lastMsg.text.lineSequence().firstOrNull().orEmpty()
        }
        if (cleanQuery.isNotEmpty()) {
            val matchesTitle = title.lowercase().contains(cleanQuery)
            val matchesSub = subtitle.lowercase().contains(cleanQuery)
            if (!matchesTitle && !matchesSub) return@mapNotNull null
        }

        val unread = unreadCounts[key] ?: if (markedUnread.contains(key)) 1 else 0
        val tsMs = lastMsg?.timestampMs ?: 0L
        ConversationRowItem(
            conversationKey = key,
            title = title,
            subtitle = subtitle,
            timestampLabel = formatHomeTimestamp(key, tsMs),
            lastTimestampMs = tsMs,
            unreadCount = unread,
            isPinned = pinnedSet.contains(key),
            isMuted = mutedSet.contains(key),
        )
    }

    return items.sortedWith { a, b ->
        val pinCmp = b.isPinned.compareTo(a.isPinned)
        if (pinCmp != 0) return@sortedWith pinCmp
        val idxA = HOME_CONVERSATION_ORDER.indexOf(a.conversationKey)
        val idxB = HOME_CONVERSATION_ORDER.indexOf(b.conversationKey)
        if (idxA >= 0 && idxB >= 0) {
            idxA.compareTo(idxB)
        } else if (idxA >= 0) {
            -1
        } else if (idxB >= 0) {
            1
        } else {
            b.lastTimestampMs.compareTo(a.lastTimestampMs)
        }
    }
}

private fun formatHomeTimestamp(conversationKey: String, timestampMs: Long): String {
    if (timestampMs <= 0L) return ""
    val nowCal = Calendar.getInstance()
    val msgCal = Calendar.getInstance().apply { timeInMillis = timestampMs }
    val sameDay = nowCal.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR) &&
        nowCal.get(Calendar.DAY_OF_YEAR) == msgCal.get(Calendar.DAY_OF_YEAR)
    if (!sameDay) {
        val yesterdayCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val isYesterday = yesterdayCal.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR) &&
            yesterdayCal.get(Calendar.DAY_OF_YEAR) == msgCal.get(Calendar.DAY_OF_YEAR)
        if (isYesterday || conversationKey == "#nearby-chat" || conversationKey == "#field-team") {
            return "Yesterday"
        }
        return SimpleDateFormat("MMM d", Locale.US).format(Date(timestampMs))
    }
    return SimpleDateFormat("h:mm a", Locale.US).format(Date(timestampMs))
}

private fun formatChannelDisplayName(tag: String): String {
    return when (tag.lowercase()) {
        "#city-3" -> "City #3"
        "#mesh-2" -> "Mesh #2"
        "#block-10" -> "Block #10"
        "#neighborhood-0" -> "Neighborhood #0"
        "#9q_k6" -> "#9q_k6"
        else -> tag
    }
}
