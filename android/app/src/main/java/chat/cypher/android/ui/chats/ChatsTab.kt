package chat.cypher.android.ui.chats

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.cypher.android.core.ChatMessage
import chat.cypher.android.core.Geohash
import chat.cypher.android.core.GeohashChannelLevel
import chat.cypher.android.core.LocationChannelResolver
import chat.cypher.android.core.MeshController
import chat.cypher.android.core.MeshStore
import chat.cypher.android.core.PeerEntry
import chat.cypher.android.ui.components.CipherChainLogoIcon
import chat.cypher.android.ui.components.PeerAvatar
import chat.cypher.android.ui.contacts.PeopleScreenContent
import chat.cypher.android.ui.theme.CypherTheme
import chat.cypher.android.ui.theme.GeistMono
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class HomeSubTab(val label: String) {
    CHATS("Chats"),
    CHANNELS("Channels"),
    PEOPLE("People"),
}

data class LocationChannelMetadata(
    val tag: String,
    val displayName: String,
    val subtitle: String,
    val isBluetoothChannel: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatsTab(
    selectedTab: HomeSubTab,
    onSelectTab: (HomeSubTab) -> Unit,
    onOpenConversation: (String) -> Unit,
    onOpenMeshTopology: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPanicMode: () -> Unit,
    onOpenSecurityVerification: (String) -> Unit,
    onPanicWipe: () -> Unit,
) {
    val palette = CypherTheme.colors
    val peers by MeshStore.peers.collectAsState()
    val knownPeers by MeshStore.knownPeers.collectAsState()
    val conversations by MeshStore.conversations.collectAsState()
    val joinedChannels by MeshStore.joinedChannels.collectAsState()
    val bookmarkedChannels by MeshStore.bookmarkedChannels.collectAsState()
    val pinnedConversations by MeshStore.pinnedConversations.collectAsState()
    val mutedConversations by MeshStore.mutedConversations.collectAsState()
    val archivedConversations by MeshStore.archivedConversations.collectAsState()
    val unreadCounts by MeshStore.unreadCounts.collectAsState()
    val drafts by MeshStore.drafts.collectAsState()
    val verifiedPeers by MeshStore.verifiedPeers.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    var showComposeSheet by remember { mutableStateOf(false) }
    var selectedConversationForMenu by remember { mutableStateOf<String?>(null) }
    var showTopOverflowMenu by remember { mutableStateOf(false) }

    // Triple-tap logo detector for Panic Mode
    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapMs by remember { mutableLongStateOf(0L) }

    when (selectedTab) {
        HomeSubTab.CHANNELS -> {
            LocationChannelsScreenContent(
                joinedChannels = joinedChannels,
                bookmarkedChannels = bookmarkedChannels,
                peers = peers,
                onSelectTab = onSelectTab,
                onOpenChannel = { tag ->
                    MeshStore.joinChannel(tag)
                    onOpenConversation(tag)
                },
                onToggleBookmark = { tag ->
                    MeshStore.toggleBookmarkChannel(tag)
                },
            )
            return
        }
        HomeSubTab.PEOPLE -> {
            PeopleScreenContent(
                onSelectTab = onSelectTab,
                onOpenDirectMessage = { peerKey ->
                    MeshStore.selectConversation(peerKey)
                    onOpenConversation(peerKey)
                },
                onOpenSecurityVerification = onOpenSecurityVerification,
            )
            return
        }
        HomeSubTab.CHATS -> Unit
    }

    val allConversationKeys = remember(
        joinedChannels,
        conversations,
        peers,
        knownPeers,
        pinnedConversations,
        archivedConversations,
        showArchived,
    ) {
        val raw = linkedSetOf<String>()
        raw.add(MeshStore.PUBLIC_CONVERSATION)
        raw.addAll(joinedChannels)
        raw.addAll(conversations.keys)
        peers.forEach { raw.add(it.peerKeyHex.lowercase(Locale.US)) }

        val visible = raw.filter { key ->
            val isArchived = key in archivedConversations
            if (showArchived) isArchived else !isArchived
        }

        visible.sortedWith(
            compareByDescending<String> { it == MeshStore.PUBLIC_CONVERSATION }
                .thenByDescending { it in pinnedConversations }
                .thenByDescending { key ->
                    conversations[key]?.lastOrNull()?.timestampMs ?: 0L
                },
        )
    }

    val filteredKeys = remember(allConversationKeys, searchQuery, peers, knownPeers, conversations) {
        if (searchQuery.isBlank()) {
            allConversationKeys
        } else {
            val q = searchQuery.trim().lowercase(Locale.US)
            allConversationKeys.filter { key ->
                val title = MeshController.resolveConversationTitle(key).lowercase(Locale.US)
                val lastMsg = conversations[key]?.lastOrNull()?.text?.lowercase(Locale.US).orEmpty()
                title.contains(q) || key.lowercase(Locale.US).contains(q) || lastMsg.contains(q)
            }
        }
    }

    Scaffold(
        containerColor = palette.bgPrimary,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showComposeSheet = true },
                containerColor = Color.Transparent,
                contentColor = Color.White,
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                shape = CircleShape,
                modifier = Modifier
                    .padding(end = 4.dp, bottom = 6.dp)
                    .size(58.dp)
                    .shadow(
                        elevation = 16.dp,
                        shape = CircleShape,
                        ambientColor = palette.accent,
                        spotColor = palette.accent,
                    )
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF9162FF),
                                palette.accent,
                                Color(0xFF6938EF),
                            ),
                        ),
                        shape = CircleShape,
                    )
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.25f),
                        shape = CircleShape,
                    )
                    .testTag("new_message_fab"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = "New Conversation",
                    modifier = Modifier.size(24.dp),
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF131524),
                            palette.bgPrimary,
                        ),
                    ),
                )
                .padding(innerPadding),
        ) {
            // Top Header: Cipher Chain Logo + "Cipher" + Search + Overflow menu
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    val now = System.currentTimeMillis()
                                    if (now - lastTapMs < 550L) {
                                        tapCount += 1
                                    } else {
                                        tapCount = 1
                                    }
                                    lastTapMs = now
                                    if (tapCount >= 3) {
                                        tapCount = 0
                                        onOpenPanicMode()
                                    }
                                },
                            )
                        }
                        .testTag("cipher_brand_header"),
                ) {
                    CipherChainLogoIcon(size = 30.dp)
                    Text(
                        text = "Cipher",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 23.sp,
                            letterSpacing = (-0.4).sp,
                        ),
                        color = palette.textPrimary,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(
                        onClick = {
                            searchExpanded = !searchExpanded
                            if (!searchExpanded) searchQuery = ""
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("toggle_search_button"),
                    ) {
                        Icon(
                            imageVector = if (searchExpanded) Icons.Rounded.Close else Icons.Rounded.Search,
                            contentDescription = "Search conversations",
                            tint = palette.textPrimary,
                            modifier = Modifier.size(22.dp),
                        )
                    }

                    Box {
                        IconButton(
                            onClick = { showTopOverflowMenu = true },
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("home_overflow_menu_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = "More options",
                                tint = palette.textPrimary,
                                modifier = Modifier.size(22.dp),
                            )
                        }

                        DropdownMenu(
                            expanded = showTopOverflowMenu,
                            onDismissRequest = { showTopOverflowMenu = false },
                            containerColor = palette.surfaceElevated,
                        ) {
                            DropdownMenuItem(
                                text = { Text("Mesh Network", color = palette.textPrimary) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Hub, contentDescription = null, tint = palette.accentLight)
                                },
                                onClick = {
                                    showTopOverflowMenu = false
                                    onOpenMeshTopology()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Location Channels", color = palette.textPrimary) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = palette.accentLight)
                                },
                                onClick = {
                                    showTopOverflowMenu = false
                                    onSelectTab(HomeSubTab.CHANNELS)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Nearby People (${peers.size})", color = palette.textPrimary) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Groups, contentDescription = null, tint = palette.accentLight)
                                },
                                onClick = {
                                    showTopOverflowMenu = false
                                    onSelectTab(HomeSubTab.PEOPLE)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Security Verification", color = palette.textPrimary) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.VerifiedUser, contentDescription = null, tint = palette.success)
                                },
                                onClick = {
                                    showTopOverflowMenu = false
                                    onOpenSecurityVerification(peers.firstOrNull()?.peerKeyHex.orEmpty())
                                },
                            )
                            if (unreadCounts.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("Mark All as Read", color = palette.textPrimary) },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.DoneAll, contentDescription = null, tint = palette.textSecondary)
                                    },
                                    onClick = {
                                        showTopOverflowMenu = false
                                        unreadCounts.keys.toList().forEach { MeshStore.markConversationRead(it) }
                                    },
                                )
                            }
                            if (archivedConversations.isNotEmpty() || showArchived) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (showArchived) "Show Active Chats" else "Archived (${archivedConversations.size})",
                                            color = palette.textPrimary,
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.Archive, contentDescription = null, tint = palette.textSecondary)
                                    },
                                    onClick = {
                                        showTopOverflowMenu = false
                                        showArchived = !showArchived
                                    },
                                )
                            }
                            HorizontalDivider(color = palette.borderSubtle)
                            DropdownMenuItem(
                                text = { Text("Settings", color = palette.textPrimary) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Settings, contentDescription = null, tint = palette.textPrimary)
                                },
                                onClick = {
                                    showTopOverflowMenu = false
                                    onOpenSettings()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Panic Mode", color = palette.danger) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = palette.danger)
                                },
                                onClick = {
                                    showTopOverflowMenu = false
                                    onOpenPanicMode()
                                },
                            )
                        }
                    }
                }
            }

            // Expandable Search Input
            AnimatedVisibility(
                visible = searchExpanded,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                        .height(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(palette.surface)
                        .border(1.dp, palette.borderSubtle, RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = "Search",
                            tint = palette.textMuted,
                            modifier = Modifier.size(18.dp),
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "Search chats, #channels, or @peers...",
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
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Clear search",
                                tint = palette.textMuted,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { searchQuery = "" },
                            )
                        }
                    }
                }
            }

            // Segmented Pill Tab Bar: [ Chats | Channels | People ]
            SegmentedHomeTabsPill(
                selectedTab = selectedTab,
                onSelectTab = onSelectTab,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Conversation List
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                items(filteredKeys, key = { it }) { convKey ->
                    val messages = conversations[convKey].orEmpty()
                    val lastMsg = messages.lastOrNull()
                    val isChannel = convKey.startsWith("#")
                    val peer = if (!isChannel) {
                        peers.firstOrNull { it.peerKeyHex.equals(convKey, ignoreCase = true) }
                    } else {
                        null
                    }
                    val knownPeer = if (!isChannel) {
                        knownPeers[convKey.lowercase(Locale.US)]
                    } else {
                        null
                    }
                    val title = if (isChannel) {
                        MeshController.resolveConversationTitle(convKey)
                    } else {
                        peer?.nickname ?: knownPeer?.nickname ?: MeshStore.resolvePeerNickname(convKey)
                    }

                    val unread = unreadCounts[convKey] ?: 0
                    val draft = drafts[convKey]
                    val isPinned = convKey in pinnedConversations
                    val isMuted = convKey in mutedConversations
                    val isVerified = convKey.lowercase(Locale.US) in verifiedPeers ||
                        knownPeer?.verified == true

                    CipherConversationRow(
                        conversationKey = convKey,
                        title = title,
                        isChannel = isChannel,
                        peer = peer,
                        connectedPeerCount = peers.count { it.connected },
                        lastMessage = lastMsg,
                        draftText = draft,
                        unreadCount = unread,
                        isPinned = isPinned,
                        isMuted = isMuted,
                        isVerified = isVerified,
                        onClick = {
                            MeshStore.selectConversation(convKey)
                            onOpenConversation(convKey)
                        },
                        onLongClick = {
                            selectedConversationForMenu = convKey
                        },
                    )

                    HorizontalDivider(
                        color = palette.borderSubtle.copy(alpha = 0.55f),
                        thickness = 0.7.dp,
                        modifier = Modifier.padding(start = 82.dp, end = 20.dp),
                    )
                }
            }
        }
    }

    if (selectedConversationForMenu != null) {
        val convKey = selectedConversationForMenu!!
        ConversationActionsBottomSheet(
            conversationKey = convKey,
            isPinned = convKey in pinnedConversations,
            isMuted = convKey in mutedConversations,
            isArchived = convKey in archivedConversations,
            onDismiss = { selectedConversationForMenu = null },
            onTogglePin = {
                MeshStore.togglePinConversation(convKey)
                selectedConversationForMenu = null
            },
            onToggleMute = {
                MeshStore.toggleMuteConversation(convKey)
                selectedConversationForMenu = null
            },
            onToggleArchive = {
                MeshStore.toggleArchiveConversation(convKey)
                selectedConversationForMenu = null
            },
            onClearMessages = {
                MeshStore.clearConversation(convKey)
                selectedConversationForMenu = null
            },
        )
    }

    if (showComposeSheet) {
        NewMessageBottomSheet(
            peers = peers,
            joinedChannels = joinedChannels,
            onDismiss = { showComposeSheet = false },
            onJoinChannel = { tag, password ->
                val normalized = Geohash.normalizeTag(tag)
                MeshStore.joinChannel(
                    tag = normalized,
                    password = password?.takeIf { it.isNotBlank() },
                    ownerPeerHex = MeshStore.localIdentityHex.value.takeIf { it.isNotBlank() },
                )
                showComposeSheet = false
                onOpenConversation(normalized)
            },
            onSelectPeer = { peerKey ->
                MeshStore.selectConversation(peerKey)
                showComposeSheet = false
                onOpenConversation(peerKey)
            },
        )
    }
}

@Composable
fun SegmentedHomeTabsPill(
    selectedTab: HomeSubTab,
    onSelectTab: (HomeSubTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = CypherTheme.colors
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xFF171928))
            .border(1.dp, palette.borderSubtle, RoundedCornerShape(50))
            .padding(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HomeSubTab.entries.forEach { tab ->
                val selected = selectedTab == tab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (selected) {
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        Color(0xFF8B5CF6),
                                        Color(0xFF703EFF),
                                    ),
                                )
                            } else {
                                Brush.horizontalGradient(
                                    colors = listOf(Color.Transparent, Color.Transparent),
                                )
                            },
                        )
                        .clickable { onSelectTab(tab) }
                        .testTag("subtab_${tab.name.lowercase()}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            fontSize = 14.sp,
                        ),
                        color = if (selected) Color.White else palette.textSecondary,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CipherConversationRow(
    conversationKey: String,
    title: String,
    isChannel: Boolean,
    peer: PeerEntry?,
    connectedPeerCount: Int,
    lastMessage: ChatMessage?,
    draftText: String?,
    unreadCount: Int,
    isPinned: Boolean,
    isMuted: Boolean,
    isVerified: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val palette = CypherTheme.colors
    val previewText = when {
        !draftText.isNullOrBlank() -> "Draft: $draftText"
        lastMessage != null -> {
            if (lastMessage.outgoing && isChannel) {
                "You: ${lastMessage.text.replace('\n', ' ')}"
            } else {
                lastMessage.text.replace('\n', ' ')
            }
        }
        conversationKey == MeshStore.PUBLIC_CONVERSATION -> {
            if (connectedPeerCount > 0) {
                "$connectedPeerCount peer(s) in Bluetooth mesh range"
            } else {
                "Bluetooth LE broadcast • Scanning for nearby peers"
            }
        }
        isChannel -> {
            val raw = conversationKey.removePrefix("#")
            val isProtected = MeshStore.isChannelPasswordProtected(conversationKey)
            when {
                isProtected -> "Password-protected encrypted channel"
                Geohash.isValid(raw) -> "${Geohash.approximateRangeLabel(raw.length)} • Geohash channel"
                else -> "Mesh topic channel • $conversationKey"
            }
        }
        else -> {
            when {
                peer?.connected == true && peer.sessionEstablished ->
                    "Connected • Noise XX encrypted (${peer.hops} hop${if (peer.hops == 1) "" else "s"})"
                peer?.connected == true ->
                    "Nearby on BLE mesh (${peer.hops} hop${if (peer.hops == 1) "" else "s"})"
                else -> "Offline • Messages queued via Store & Forward"
            }
        }
    }

    val timestampLabel = remember(lastMessage?.timestampMs) {
        lastMessage?.let { formatCipherChatTime(it.timestampMs) }.orEmpty()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .testTag("conversation_row_$conversationKey"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PeerAvatar(
            peerKeyHex = conversationKey,
            nickname = title,
            size = 50.dp,
            connected = peer?.connected ?: false,
            sessionEstablished = peer?.sessionEstablished ?: false,
            isChannel = isChannel,
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
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
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                        ),
                        color = palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isChannel && MeshStore.isChannelPasswordProtected(conversationKey)) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = "Password Protected",
                            tint = palette.accentLight,
                            modifier = Modifier.size(14.dp),
                        )
                    } else if (!isChannel && isVerified) {
                        Icon(
                            imageVector = Icons.Rounded.VerifiedUser,
                            contentDescription = "Verified",
                            tint = palette.success,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    if (isMuted) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.VolumeOff,
                            contentDescription = "Muted",
                            tint = palette.textMuted,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }

                if (timestampLabel.isNotEmpty()) {
                    Text(
                        text = timestampLabel,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 12.sp,
                            fontWeight = if (unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = if (unreadCount > 0) palette.textPrimary else palette.textSecondary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = previewText,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        fontWeight = if (unreadCount > 0) FontWeight.Medium else FontWeight.Normal,
                    ),
                    color = when {
                        !draftText.isNullOrBlank() -> palette.accentLight
                        unreadCount > 0 -> palette.textPrimary.copy(alpha = 0.90f)
                        else -> palette.textSecondary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                if (unreadCount > 0) {
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .size(23.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF9366FF), palette.accent),
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = unreadCount.coerceAtMost(99).toString(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.5.sp,
                            ),
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Location Channels Screen — uses real GPS/Network coordinates via LocationChannelResolver
 * and real user-bookmarked channels from Room DB.
 */
@Composable
fun LocationChannelsScreenContent(
    joinedChannels: Set<String>,
    bookmarkedChannels: Set<String>,
    peers: List<PeerEntry>,
    onSelectTab: (HomeSubTab) -> Unit,
    onOpenChannel: (String) -> Unit,
    onToggleBookmark: (String) -> Unit,
) {
    val context = LocalContext.current
    val palette = CypherTheme.colors
    var channelInput by remember { mutableStateOf("") }
    var locationRefreshTick by remember { mutableIntStateOf(0) }

    val nearbyResult = remember(locationRefreshTick) {
        LocationChannelResolver.computeNearbyGeohashChannels(context)
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        locationRefreshTick += 1
    }

    val bookmarkedEntries = remember(bookmarkedChannels, nearbyResult) {
        val gpsByTag = nearbyResult.channels.associateBy { it.tag }
        bookmarkedChannels.sorted().map { tag ->
            val gpsChan = gpsByTag[tag]
            val raw = tag.removePrefix("#")
            when {
                gpsChan != null -> LocationChannelMetadata(
                    tag = tag,
                    displayName = gpsChan.displayName,
                    subtitle = gpsChan.subtitle,
                )
                tag == MeshStore.PUBLIC_CONVERSATION -> LocationChannelMetadata(
                    tag = tag,
                    displayName = "Mesh Group",
                    subtitle = "Bluetooth • 30–160 ft",
                    isBluetoothChannel = true,
                )
                Geohash.isValid(raw) -> {
                    val level = GeohashChannelLevel.entries.firstOrNull { it.precision == raw.length }
                    val title = if (level != null) "${level.displayName} #$raw" else "#$raw"
                    val range = Geohash.approximateRangeLabel(raw.length)
                    LocationChannelMetadata(
                        tag = tag,
                        displayName = title,
                        subtitle = "$range • Geohash #$raw",
                    )
                }
                else -> LocationChannelMetadata(
                    tag = tag,
                    displayName = tag,
                    subtitle = "Custom mesh channel",
                )
            }
        }
    }

    val nearbyEntries = remember(nearbyResult, peers) {
        val connectedCount = peers.count { it.connected }
        val bleSubtitle = if (connectedCount > 0) {
            "Bluetooth • 30–160 ft • $connectedCount peer(s)"
        } else {
            "Bluetooth • 30–160 ft"
        }
        val list = mutableListOf(
            LocationChannelMetadata(
                tag = MeshStore.PUBLIC_CONVERSATION,
                displayName = "Mesh #mesh",
                subtitle = bleSubtitle,
                isBluetoothChannel = true,
            ),
        )
        nearbyResult.channels.forEach { gh ->
            list.add(
                LocationChannelMetadata(
                    tag = gh.tag,
                    displayName = gh.displayName,
                    subtitle = gh.subtitle,
                ),
            )
        }
        list
    }

    Scaffold(
        containerColor = palette.bgPrimary,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF131524), palette.bgPrimary),
                    ),
                )
                .padding(innerPadding),
        ) {
            // Top bar: Pin icon + "Location Channels" + GPS refresh icon
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    IconButton(
                        onClick = { onSelectTab(HomeSubTab.CHATS) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.LocationOn,
                            contentDescription = "Location Channels",
                            tint = palette.accentLight,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Text(
                        text = "Location Channels",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                        ),
                        color = palette.textPrimary,
                    )
                }

                IconButton(
                    onClick = {
                        if (!nearbyResult.hasPermission) {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                        } else {
                            locationRefreshTick += 1
                        }
                    },
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("refresh_gps_channels_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MyLocation,
                        contentDescription = "Refresh GPS geohash channels",
                        tint = palette.accentLight,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Text(
                text = "Chat with people near you using geohash channels. Only a coarse geohash prefix is used locally.",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                ),
                color = palette.textSecondary,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Input Row: [ # Enter geohash ] + [ Join ] button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(palette.surface)
                        .border(1.dp, palette.borderSubtle, RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "#",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = GeistMono,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = palette.textSecondary,
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            if (channelInput.isEmpty()) {
                                Text(
                                    text = "Enter geohash or channel",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette.textMuted,
                                )
                            }
                            BasicTextField(
                                value = channelInput,
                                onValueChange = { channelInput = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = palette.textPrimary,
                                ),
                                cursorBrush = SolidColor(palette.accent),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("location_channel_input"),
                            )
                        }
                    }
                }

                val canJoin = channelInput.trim().removePrefix("#").isNotEmpty()
                Box(
                    modifier = Modifier
                        .height(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.horizontalGradient(
                                if (canJoin) {
                                    listOf(Color(0xFF8B5CF6), Color(0xFF6D39F4))
                                } else {
                                    listOf(Color(0xFF332959), Color(0xFF282047))
                                },
                            ),
                        )
                        .clickable(enabled = canJoin) {
                            val target = Geohash.normalizeTag(channelInput.trim())
                            channelInput = ""
                            onOpenChannel(target)
                        }
                        .padding(horizontal = 22.dp)
                        .testTag("join_geohash_button"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Join",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.5.sp,
                        ),
                        color = if (canJoin) Color.White else palette.textSecondary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        text = "BOOKMARKED",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            fontSize = 11.5.sp,
                        ),
                        color = palette.textSecondary,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                    )
                }

                if (bookmarkedEntries.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(palette.surface.copy(alpha = 0.65f))
                                .border(1.dp, palette.borderSubtle, RoundedCornerShape(16.dp))
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                        ) {
                            Text(
                                text = "No bookmarked channels yet. Tap the bookmark icon on any channel or enter a #geohash above.",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textSecondary,
                            )
                        }
                    }
                } else {
                    items(bookmarkedEntries, key = { "bm_${it.tag}" }) { item ->
                        LocationChannelCard(
                            metadata = item,
                            isBookmarked = true,
                            showBookmarkTrailing = true,
                            onClick = { onOpenChannel(item.tag) },
                            onActionClick = { onToggleBookmark(item.tag) },
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "NEARBY CHANNELS",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                fontSize = 11.5.sp,
                            ),
                            color = palette.textSecondary,
                        )
                        if (nearbyResult.location != null) {
                            Text(
                                text = "#${nearbyResult.location.geohash(7)}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = GeistMono,
                                ),
                                color = palette.accentLight,
                            )
                        }
                    }
                }

                items(nearbyEntries, key = { "near_${it.tag}" }) { item ->
                    LocationChannelCard(
                        metadata = item,
                        isBookmarked = item.tag in bookmarkedChannels,
                        showBookmarkTrailing = false,
                        onClick = { onOpenChannel(item.tag) },
                        onActionClick = {
                            onToggleBookmark(item.tag)
                            onOpenChannel(item.tag)
                        },
                    )
                }

                if (!nearbyResult.hasPermission || nearbyResult.location == null) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(palette.surface)
                                .border(1.dp, palette.borderSubtle, RoundedCornerShape(16.dp))
                                .padding(16.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = if (!nearbyResult.hasPermission) {
                                        "Enable Location Permission for Geohash Channels"
                                    } else {
                                        "Waiting for Device GPS Fix"
                                    },
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                    color = palette.textPrimary,
                                )
                                Text(
                                    text = if (!nearbyResult.hasPermission) {
                                        "Grant location access to automatically compute your local Block, Neighborhood, City, and Region geohash channels. You can also manually join any #geohash above."
                                    } else {
                                        "No cached GPS/Network location fix is available on this device yet. Tap Refresh after acquiring a GPS signal or manually enter any #geohash above."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.textSecondary,
                                )
                                if (!nearbyResult.hasPermission) {
                                    Button(
                                        onClick = {
                                            locationPermissionLauncher.launch(
                                                arrayOf(
                                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                                ),
                                            )
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = palette.accent),
                                        shape = RoundedCornerShape(12.dp),
                                    ) {
                                        Text("Grant Location Access", color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(20.dp)) }
            }
        }
    }
}

@Composable
private fun LocationChannelCard(
    metadata: LocationChannelMetadata,
    isBookmarked: Boolean,
    showBookmarkTrailing: Boolean,
    onClick: () -> Unit,
    onActionClick: () -> Unit,
) {
    val palette = CypherTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.surface)
            .border(1.dp, palette.borderSubtle, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("location_channel_card_${metadata.tag}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF22253A))
                    .border(1.dp, palette.borderSubtle, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (metadata.isBluetoothChannel) {
                        Icons.Rounded.BluetoothSearching
                    } else {
                        Icons.Rounded.Groups
                    },
                    contentDescription = null,
                    tint = palette.textSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = metadata.displayName,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                    ),
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = metadata.subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.5.sp,
                    ),
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (showBookmarkTrailing) {
            IconButton(
                onClick = onActionClick,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    imageVector = if (isBookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                    contentDescription = "Bookmark channel",
                    tint = palette.accentLight,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF282249))
                    .border(1.dp, palette.accent.copy(alpha = 0.4f), CircleShape)
                    .clickable(onClick = onActionClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "Join channel",
                    tint = palette.accentLight,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationActionsBottomSheet(
    conversationKey: String,
    isPinned: Boolean,
    isMuted: Boolean,
    isArchived: Boolean,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleArchive: () -> Unit,
    onClearMessages: () -> Unit,
) {
    val palette = CypherTheme.colors
    val sheetState = rememberModalBottomSheetState()
    val title = MeshController.resolveConversationTitle(conversationKey)

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
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = palette.textPrimary,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            SheetOptionRow(
                icon = Icons.Rounded.PushPin,
                label = if (isPinned) "Unpin conversation" else "Pin to top",
                onClick = onTogglePin,
            )
            SheetOptionRow(
                icon = if (isMuted) Icons.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                label = if (isMuted) "Unmute notifications" else "Mute notifications",
                onClick = onToggleMute,
            )
            if (conversationKey != MeshStore.PUBLIC_CONVERSATION) {
                SheetOptionRow(
                    icon = if (isArchived) Icons.Rounded.Unarchive else Icons.Rounded.Archive,
                    label = if (isArchived) "Unarchive conversation" else "Archive conversation",
                    onClick = onToggleArchive,
                )
            }
            SheetOptionRow(
                icon = Icons.Rounded.DeleteOutline,
                label = "Clear message history",
                tint = palette.danger,
                onClick = onClearMessages,
            )
        }
    }
}

@Composable
private fun SheetOptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = CypherTheme.colors.textPrimary,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = CypherTheme.colors.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Text(text = label, style = MaterialTheme.typography.bodyLarge, color = tint)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewMessageBottomSheet(
    peers: List<PeerEntry>,
    joinedChannels: Set<String>,
    onDismiss: () -> Unit,
    onJoinChannel: (String, String?) -> Unit,
    onSelectPeer: (String) -> Unit,
) {
    val palette = CypherTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var channelName by remember { mutableStateOf("") }
    var channelPassword by remember { mutableStateOf("") }

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
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "New Conversation",
                style = MaterialTheme.typography.headlineMedium,
                color = palette.textPrimary,
            )
            Text(
                text = "Join a #channel (with optional AES-256-GCM password) or message a nearby peer over Bluetooth LE.",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary,
            )

            OutlinedTextField(
                value = channelName,
                onValueChange = { channelName = it },
                singleLine = true,
                label = { Text("Channel or Geohash (e.g. #camp-alpha or #9q8yyk)") },
                leadingIcon = { Icon(Icons.Rounded.Tag, contentDescription = null) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = palette.accent,
                    unfocusedBorderColor = palette.borderSubtle,
                    focusedTextColor = palette.textPrimary,
                    unfocusedTextColor = palette.textPrimary,
                    cursorColor = palette.accent,
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("new_channel_name_input"),
            )

            OutlinedTextField(
                value = channelPassword,
                onValueChange = { channelPassword = it },
                singleLine = true,
                label = { Text("Channel password (optional, AES-256-GCM)") },
                leadingIcon = { Icon(Icons.Rounded.Security, contentDescription = null) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = palette.accent,
                    unfocusedBorderColor = palette.borderSubtle,
                    focusedTextColor = palette.textPrimary,
                    unfocusedTextColor = palette.textPrimary,
                    cursorColor = palette.accent,
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("new_channel_password_input"),
            )

            Button(
                onClick = {
                    val cleaned = channelName.trim().removePrefix("#")
                    if (cleaned.isNotEmpty()) {
                        onJoinChannel("#$cleaned", channelPassword.takeIf { it.isNotBlank() })
                    }
                },
                enabled = channelName.trim().removePrefix("#").isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = palette.accent),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("confirm_join_channel_button"),
            ) {
                Text("Open Channel", color = Color.White, fontWeight = FontWeight.SemiBold)
            }

            if (peers.isNotEmpty()) {
                HorizontalDivider(color = palette.borderSubtle)
                Text(
                    text = "Nearby Mesh Peers (${peers.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.textSecondary,
                )
                peers.take(6).forEach { peer ->
                    Surface(
                        onClick = { onSelectPeer(peer.peerKeyHex) },
                        shape = RoundedCornerShape(14.dp),
                        color = palette.surface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            PeerAvatar(
                                peerKeyHex = peer.peerKeyHex,
                                nickname = peer.nickname,
                                size = 40.dp,
                                connected = peer.connected,
                                sessionEstablished = peer.sessionEstablished,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = peer.nickname,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = palette.textPrimary,
                                )
                                Text(
                                    text = "${peer.hops} hop(s) • ${if (peer.sessionEstablished) "Noise XX Encrypted" else "BLE Mesh"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.textSecondary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatCipherChatTime(timestampMs: Long): String {
    val now = Calendar.getInstance()
    val msgTime = Calendar.getInstance().apply { timeInMillis = timestampMs }
    val sameDay = now.get(Calendar.YEAR) == msgTime.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == msgTime.get(Calendar.DAY_OF_YEAR)
    if (sameDay) {
        return SimpleDateFormat("h:mm a", Locale.US).format(Date(timestampMs))
    }
    return SimpleDateFormat("MMM d", Locale.US).format(Date(timestampMs))
}
