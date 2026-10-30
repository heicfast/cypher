package chat.cypher.android.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.cypher.android.data.MeshController
import chat.cypher.android.ui.calls.CallsScreen
import chat.cypher.android.ui.chats.ChatsScreen
import chat.cypher.android.ui.chats.ConversationScreen
import chat.cypher.android.ui.components.PeerAvatar
import chat.cypher.android.ui.components.UnreadBadge
import chat.cypher.android.ui.contacts.ContactsScreen
import chat.cypher.android.ui.profile.ProfileScreen
import chat.cypher.android.ui.sheets.AboutSheet
import chat.cypher.android.ui.sheets.PeerInfoSheet
import chat.cypher.android.ui.theme.LocalCypherPalette
import kotlinx.coroutines.launch

/** Bottom-nav tabs (reference design: Chats · Contacts · Calls · Profile). */
enum class Tab(val label: String) {
    CHATS("Chats"),
    CONTACTS("Contacts"),
    CALLS("Calls"),
    PROFILE("Profile"),
}

/**
 * App root once onboarding completes: bottom navigation + conversation
 * push navigation + peer/about sheets.
 */
@Composable
fun CypherApp(
    controller: MeshController,
    openConversationId: String?,
    onConversationOpened: () -> Unit,
    onChangeNickname: (String) -> Unit,
    onThemeChange: () -> Unit,
) {
    val palette = LocalCypherPalette.current
    var tab by remember { mutableStateOf(Tab.CHATS) }
    var openConversation by remember { mutableStateOf<String?>(null) }
    var peerInfoPeerId by remember { mutableStateOf<String?>(null) }
    var showNewChat by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    // Deep link from notifications.
    LaunchedEffect(openConversationId) {
        openConversationId?.let {
            openConversation = it
            controller.openConversation(it)
            onConversationOpened()
        }
    }

    val peerInfoPeer = remember(peerInfoPeerId, controller.peers) {
        peerInfoPeerId?.let { id -> controller.peers.firstOrNull { it.peerId == id } }
    }

    Box(Modifier.fillMaxSize().background(palette.background)) {
        // ---- tab content ----
        Box(Modifier.fillMaxSize().padding(bottom = 0.dp)) {
            when (tab) {
                Tab.CHATS -> ChatsScreen(
                    controller = controller,
                    onOpenConversation = { openConversation = it },
                    onNewChat = { showNewChat = true },
                )
                Tab.CONTACTS -> ContactsScreen(
                    controller = controller,
                    onOpenConversation = { openConversation = it },
                    onPeerInfo = { peerInfoPeerId = it },
                )
                Tab.CALLS -> CallsScreen()
                Tab.PROFILE -> ProfileScreen(
                    controller = controller,
                    onChangeNickname = onChangeNickname,
                    onAbout = { showAbout = true },
                    onThemeChange = { onThemeChange() },
                )
            }
        }

        // ---- conversation overlay (slides over the tabs) ----
        openConversation?.let { conversationId ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(palette.surface)
            ) {
                ConversationScreen(
                    controller = controller,
                    conversationId = conversationId,
                    onBack = {
                        controller.closeConversation()
                        openConversation = null
                    },
                    onPeerInfo = { peerInfoPeerId = conversationId.removePrefix("dm:") },
                )
            }
        }

        // ---- bottom navigation (hidden inside conversations) ----
        if (openConversation == null) {
            BottomNavBar(
                current = tab,
                unreadTotal = controller.unreadTotal(),
                onSelect = { tab = it },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // ---- sheets ----
        if (showNewChat) {
            NewChatSheet(
                controller = controller,
                onDismiss = { showNewChat = false },
                onOpenConversation = {
                    showNewChat = false
                    openConversation = it
                },
            )
        }
        if (peerInfoPeer != null && peerInfoPeerId != null) {
            PeerInfoSheet(
                controller = controller,
                peer = peerInfoPeer!!,
                onDismiss = { peerInfoPeerId = null },
            )
        }
        if (showAbout) {
            AboutSheet(onDismiss = { showAbout = false })
        }
    }
}

// ---- bottom nav -----------------------------------------------------------------

@Composable
private fun BottomNavBar(
    current: Tab,
    unreadTotal: Int,
    onSelect: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalCypherPalette.current
    Surface(
        color = palette.surface.copy(alpha = 0.96f),
        shadowElevation = 12.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            Modifier
                .background(palette.surface)
                .navigationBarsPadding()
                .padding(top = 8.dp)
        ) {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .height(1.dp)
                    .fillMaxWidth()
                    .background(palette.separator)
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Tab.entries.forEach { tabItem ->
                    NavItem(
                        tab = tabItem,
                        selected = current == tabItem,
                        badge = if (tabItem == Tab.CHATS) unreadTotal else 0,
                        onClick = { onSelect(tabItem) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun NavItem(
    tab: Tab,
    selected: Boolean,
    badge: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalCypherPalette.current
    val tint = if (selected) palette.primary else palette.textTertiary
    val icon: ImageVector = when (tab) {
        Tab.CHATS -> if (selected) Icons.Filled.ChatBubble else Icons.Outlined.ChatBubbleOutline
        Tab.CONTACTS -> Icons.Outlined.Person
        Tab.CALLS -> Icons.Outlined.Call
        Tab.PROFILE -> Icons.Outlined.Person
    }
    Column(
        modifier
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box {
            Icon(
                icon,
                contentDescription = tab.label,
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
            if (badge > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(start = 14.dp)
                        .background(palette.primary, CircleShape)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Text(
                        if (badge > 99) "99+" else badge.toString(),
                        color = palette.onPrimary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        Text(
            tab.label,
            color = tint,
            fontSize = 10.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

// ---- new chat sheet -----------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewChatSheet(
    controller: MeshController,
    onDismiss: () -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    val palette = LocalCypherPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var channelName by remember { mutableStateOf("") }
    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp)
        ) {
            Text(
                "New chat",
                color = palette.textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Message a nearby peer or join a channel",
                color = palette.textSecondary,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(18.dp))

            // ---- join channel ----
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = palette.surfaceSearch,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Join a channel",
                            color = palette.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextField(
                            value = channelName,
                            onValueChange = { if (it.length <= 24) channelName = it.removePrefix("#") },
                            placeholder = {
                                Text("channel name", color = palette.textTertiary, fontSize = 14.sp)
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = palette.surface,
                                unfocusedContainerColor = palette.surface,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                cursorColor = palette.primary,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = palette.primary,
                            onClick = {
                                if (channelName.isNotBlank()) {
                                    val channel =
                                        if (channelName.startsWith("#")) channelName else "#$channelName"
                                    controller.joinChannel(channel)
                                    close()
                                    onOpenConversation(channel)
                                }
                            },
                        ) {
                            Text(
                                "Join",
                                color = palette.onPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Channels are mesh-wide topics. Messages broadcast signed — anyone in range relays them.",
                        color = palette.textTertiary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    )
                }
            }
            Spacer(Modifier.height(18.dp))

            // ---- peers ----
            Text(
                "NEARBY PEERS",
                color = palette.textTertiary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(8.dp))
            if (controller.peers.isEmpty()) {
                Text(
                    "No one in range yet. Keep the app open with Bluetooth on.",
                    color = palette.textSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            } else {
                LazyColumn {
                    items(controller.peers, key = { it.peerId }) { peer ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    close()
                                    onOpenConversation("dm:${peer.peerId}")
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            PeerAvatar(
                                name = peer.nickname,
                                identityKey = peer.peerId,
                                size = 42.dp,
                                online = peer.connected,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    peer.nickname,
                                    color = palette.textPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "#${peer.suffix} · ${if (peer.connected) "in range" else "seen recently"}",
                                    color = palette.textSecondary,
                                    fontSize = 12.sp,
                                )
                            }
                            Icon(
                                Icons.Filled.ChatBubble, null,
                                tint = palette.textTertiary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
