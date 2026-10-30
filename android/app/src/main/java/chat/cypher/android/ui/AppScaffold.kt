package chat.cypher.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.cypher.android.core.MeshController
import chat.cypher.android.core.MeshStore
import chat.cypher.android.core.PeerIdentity
import chat.cypher.android.core.PeerUiModel
import chat.cypher.android.ui.chats.ChatThreadScreen
import chat.cypher.android.ui.chats.ChatsTab
import chat.cypher.android.ui.components.AboutCypherSheet
import chat.cypher.android.ui.components.SecurityVerificationSheet
import chat.cypher.android.ui.contacts.ContactsTab
import chat.cypher.android.ui.profile.ProfileTab
import chat.cypher.android.ui.theme.LocalCypherPalette
import kotlinx.coroutines.launch

enum class PrimaryDestination(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String,
) {
    MESSAGES(
        label = "Messages",
        selectedIcon = Icons.AutoMirrored.Filled.Chat,
        unselectedIcon = Icons.AutoMirrored.Outlined.Chat,
        testTag = "nav_tab_messages",
    ),
    MESH_RADAR(
        label = "Mesh Radar",
        selectedIcon = Icons.Filled.Radar,
        unselectedIcon = Icons.Outlined.Radar,
        testTag = "nav_tab_radar",
    ),
    IDENTITY(
        label = "Identity",
        selectedIcon = Icons.Filled.Person,
        unselectedIcon = Icons.Outlined.Person,
        testTag = "nav_tab_identity",
    ),
}

@Composable
fun AppScaffold(
    controller: MeshController,
) {
    val palette = LocalCypherPalette.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val peers by controller.peers.collectAsState()
    val conversations by controller.conversations.collectAsState()
    val unreadCounts by controller.unreadCounts.collectAsState()
    val pinnedConversations by controller.pinnedConversations.collectAsState()
    val mutedConversations by controller.mutedConversations.collectAsState()
    val knownPeerNames by controller.knownPeerNames.collectAsState()
    val drafts by controller.drafts.collectAsState()
    val activeConversationKey by controller.activeConversationKey.collectAsState()
    val isMeshRunning by controller.isMeshRunning.collectAsState()
    val connectedBleAddresses by controller.connectedBleAddresses.collectAsState()
    val nickname by controller.nickname.collectAsState()
    val myPeerIdHex by controller.myPeerIdHex.collectAsState()
    val myStaticKeyHex by controller.myStaticPublicKeyHex.collectAsState()
    val myFingerprintHex by controller.myFingerprintHex.collectAsState()
    val coreVersion by controller.coreVersion.collectAsState()
    val backgroundServiceEnabled by controller.backgroundServiceEnabled.collectAsState()
    val themePreference by controller.themePreference.collectAsState()
    val blockedPeers by controller.blockedPeers.collectAsState()
    val verifiedPeers by controller.verifiedPeers.collectAsState()

    var currentTab by rememberSaveable { mutableStateOf(PrimaryDestination.MESSAGES) }
    var verificationPeerTarget by remember { mutableStateOf<PeerUiModel?>(null) }
    var showProtocolGuide by remember { mutableStateOf(false) }

    val totalUnreadMessages = remember(unreadCounts) {
        unreadCounts.values.sum()
    }
    val onlineCount = remember(peers, connectedBleAddresses) {
        maxOf(connectedBleAddresses.size, peers.count { it.isDirectlyConnected })
    }

    // Back navigation from non-default tabs returns to Messages first
    BackHandler(enabled = activeConversationKey == null && currentTab != PrimaryDestination.MESSAGES) {
        currentTab = PrimaryDestination.MESSAGES
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas),
    ) {
        AnimatedContent(
            targetState = activeConversationKey,
            transitionSpec = {
                if (targetState != null) {
                    (slideInHorizontally(tween(240)) { it / 3 } + fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(tween(200)) { -it / 6 } + fadeOut(tween(150)))
                } else {
                    (slideInHorizontally(tween(220)) { -it / 5 } + fadeIn(tween(180))) togetherWith
                        (slideOutHorizontally(tween(200)) { it / 3 } + fadeOut(tween(150)))
                }
            },
            label = "root_thread_transition",
        ) { threadKey ->
            if (threadKey != null) {
                val peer = peers.firstOrNull { it.peerIdHex.equals(threadKey, ignoreCase = true) }
                val displayName = when {
                    threadKey == MeshStore.BROADCAST_CONVERSATION_KEY -> "#mesh"
                    peer != null -> peer.nickname
                    else -> controller.resolvePeerNickname(threadKey)
                }
                val threadMessages = conversations[threadKey].orEmpty()
                val initialDraft = drafts[threadKey].orEmpty()

                ChatThreadScreen(
                    conversationKey = threadKey,
                    peer = peer,
                    displayName = displayName,
                    messages = threadMessages,
                    peers = peers,
                    initialDraft = initialDraft,
                    isMuted = mutedConversations.contains(threadKey),
                    onlineMeshCount = onlineCount,
                    onBack = { controller.openConversation(null) },
                    onSendText = { text, replyTo ->
                        if (threadKey == MeshStore.BROADCAST_CONVERSATION_KEY) {
                            controller.sendBroadcastText(text, replyTo)
                        } else {
                            controller.sendPrivateMessage(threadKey, text, replyTo)
                        }
                    },
                    onExecuteSlashCommand = { cmd ->
                        controller.executeSlashCommand(cmd, threadKey)
                    },
                    onSendMediaUri = { uri, forcedKind ->
                        val ok = controller.sendMedia(threadKey, uri, forcedKind)
                        if (!ok) {
                            scope.launch {
                                snackbarHostState.showSnackbar("Attachment exceeds 512 KB mesh limit")
                            }
                        }
                    },
                    onSendVoiceNote = { file, durationMs ->
                        controller.sendVoiceNoteFile(threadKey, file, durationMs)
                    },
                    onToggleReaction = { msgId, emoji ->
                        controller.toggleReaction(threadKey, msgId, emoji)
                    },
                    onPingPeer = {
                        if (threadKey == MeshStore.BROADCAST_CONVERSATION_KEY) {
                            controller.announceNow()
                            scope.launch {
                                snackbarHostState.showSnackbar("Broadcasted presence announce across #mesh")
                            }
                        } else {
                            controller.sendMeshPing(threadKey)
                        }
                    },
                    onOpenVerification = { targetPeer ->
                        val resolved = targetPeer ?: PeerUiModel(
                            peerIdHex = threadKey,
                            nickname = displayName,
                            sessionState = "none",
                            isKnown = true,
                            lastSeenMs = 0L,
                            fingerprintHex = PeerIdentity.fingerprintHex(threadKey),
                        )
                        verificationPeerTarget = resolved
                    },
                    onToggleMute = {
                        val muted = controller.toggleMuteConversation(threadKey)
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                if (muted) "Muted notifications" else "Unmuted notifications",
                            )
                        }
                    },
                    onToggleBlock = {
                        if (threadKey != MeshStore.BROADCAST_CONVERSATION_KEY) {
                            controller.toggleBlocked(threadKey)
                        }
                    },
                    onClearHistory = {
                        controller.clearConversationMessages(threadKey)
                    },
                    onDraftChanged = { draft ->
                        controller.updateDraft(threadKey, draft)
                    },
                )
            } else {
                Scaffold(
                    containerColor = palette.canvas,
                    contentWindowInsets = WindowInsets.statusBars,
                    snackbarHost = {
                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier.padding(bottom = 84.dp),
                        )
                    },
                    bottomBar = {
                        MinimalBottomNavigationBar(
                            currentTab = currentTab,
                            unreadMessagesCount = totalUnreadMessages,
                            nearbyNodesCount = onlineCount,
                            onSelectTab = { nextTab ->
                                if (currentTab != nextTab) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentTab = nextTab
                                }
                            },
                        )
                    },
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = innerPadding.calculateTopPadding()),
                    ) {
                        AnimatedContent(
                            targetState = currentTab,
                            transitionSpec = {
                                fadeIn(tween(180)) togetherWith fadeOut(tween(130))
                            },
                            label = "tab_content_transition",
                        ) { destination ->
                            when (destination) {
                                PrimaryDestination.MESSAGES -> ChatsTab(
                                    peers = peers,
                                    conversations = conversations,
                                    unreadCounts = unreadCounts,
                                    pinnedConversations = pinnedConversations,
                                    mutedConversations = mutedConversations,
                                    knownPeerNames = knownPeerNames,
                                    drafts = drafts,
                                    isMeshRunning = isMeshRunning,
                                    connectedBleCount = connectedBleAddresses.size,
                                    onOpenConversation = { key -> controller.openConversation(key) },
                                    onTogglePin = { key ->
                                        val pinned = controller.togglePinConversation(key)
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                message = if (pinned) "Pinned conversation" else "Unpinned conversation",
                                                duration = SnackbarDuration.Short,
                                            )
                                        }
                                    },
                                    onToggleMute = { key ->
                                        controller.toggleMuteConversation(key)
                                    },
                                    onToggleUnread = { key ->
                                        controller.toggleMarkUnread(key)
                                    },
                                    onDeleteConversation = { key ->
                                        val backup = controller.deleteConversationWithBackup(key)
                                        if (backup != null) {
                                            scope.launch {
                                                val res = snackbarHostState.showSnackbar(
                                                    message = "Conversation deleted",
                                                    actionLabel = "Undo",
                                                    duration = SnackbarDuration.Short,
                                                )
                                                if (res == SnackbarResult.ActionPerformed) {
                                                    controller.restoreConversationBackup(backup)
                                                }
                                            }
                                        }
                                    },
                                    onVerifyPeer = { peer -> verificationPeerTarget = peer },
                                    onAnnouncePulse = {
                                        controller.announceNow()
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                message = "Broadcasted signed presence announce over BLE",
                                                duration = SnackbarDuration.Short,
                                            )
                                        }
                                    },
                                    onOpenGuide = { showProtocolGuide = true },
                                )

                                PrimaryDestination.MESH_RADAR -> ContactsTab(
                                    peers = peers,
                                    myNickname = nickname,
                                    myPeerIdHex = myPeerIdHex,
                                    isMeshRunning = isMeshRunning,
                                    connectedBleCount = connectedBleAddresses.size,
                                    onOpenDm = { peerHex -> controller.openConversation(peerHex) },
                                    onPingPeer = { peer ->
                                        controller.sendMeshPing(peer.peerIdHex)
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                message = "Sent encrypted mesh ping to @${peer.nickname}",
                                                duration = SnackbarDuration.Short,
                                            )
                                        }
                                    },
                                    onVerifyPeer = { peer -> verificationPeerTarget = peer },
                                    onToggleFavorite = { peerHex -> controller.toggleFavorite(peerHex) },
                                    onToggleBlocked = { peerHex -> controller.toggleBlocked(peerHex) },
                                    onAnnounceNow = {
                                        controller.announceNow()
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                message = "Scanning & advertising presence across BLE mesh…",
                                                duration = SnackbarDuration.Short,
                                            )
                                        }
                                    },
                                )

                                PrimaryDestination.IDENTITY -> ProfileTab(
                                    nickname = nickname,
                                    myPeerIdHex = myPeerIdHex,
                                    myStaticKeyHex = myStaticKeyHex,
                                    myFingerprintHex = myFingerprintHex,
                                    coreVersion = coreVersion,
                                    isMeshRunning = isMeshRunning,
                                    backgroundServiceEnabled = backgroundServiceEnabled,
                                    themePreference = themePreference,
                                    blockedCount = blockedPeers.size,
                                    verifiedCount = verifiedPeers.size,
                                    onUpdateNickname = { newNick ->
                                        controller.updateNickname(newNick)
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                message = "Nickname updated to @$newNick",
                                                duration = SnackbarDuration.Short,
                                            )
                                        }
                                    },
                                    onSetBackgroundService = { enabled ->
                                        controller.setBackgroundServiceEnabled(enabled)
                                    },
                                    onSetThemePreference = { pref ->
                                        controller.setThemePreference(pref)
                                    },
                                    onOpenGuide = { showProtocolGuide = true },
                                    onPanicWipe = {
                                        controller.panicWipe()
                                        currentTab = PrimaryDestination.MESSAGES
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                message = "All keys and history wiped. New identity generated.",
                                                duration = SnackbarDuration.Short,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Security Verification Modal Sheet
    val verifyTarget = verificationPeerTarget
    if (verifyTarget != null) {
        val livePeer = peers.firstOrNull {
            it.peerIdHex.equals(verifyTarget.peerIdHex, ignoreCase = true)
        } ?: verifyTarget

        SecurityVerificationSheet(
            peer = livePeer,
            myPeerIdHex = myPeerIdHex,
            myFingerprintHex = myFingerprintHex,
            onMarkVerified = {
                controller.markPeerVerified(livePeer.peerIdHex)
                verificationPeerTarget = livePeer.copy(isVerified = true)
            },
            onUnverify = {
                controller.unverifyPeer(livePeer.peerIdHex)
                verificationPeerTarget = livePeer.copy(isVerified = false)
            },
            onDismiss = { verificationPeerTarget = null },
        )
    }

    // Protocol & Gesture Guide Sheet
    if (showProtocolGuide) {
        AboutCypherSheet(
            coreVersion = coreVersion,
            onDismiss = { showProtocolGuide = false },
        )
    }
}

@Composable
private fun MinimalBottomNavigationBar(
    currentTab: PrimaryDestination,
    unreadMessagesCount: Int,
    nearbyNodesCount: Int,
    onSelectTab: (PrimaryDestination) -> Unit,
) {
    val palette = LocalCypherPalette.current

    Surface(
        color = palette.surface0.copy(alpha = 0.96f),
        modifier = Modifier
            .fillMaxWidth()
            .border(width = 1.dp, color = palette.borderSubtle)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(66.dp)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrimaryDestination.entries.forEach { dest ->
                val selected = currentTab == dest
                val scale by animateFloatAsState(
                    targetValue = if (selected) 1.04f else 1.0f,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "nav_scale",
                )
                val pillBg by animateColorAsState(
                    targetValue = if (selected) palette.accentSoft else androidx.compose.ui.graphics.Color.Transparent,
                    label = "nav_pill_bg",
                )
                val contentColor by animateColorAsState(
                    targetValue = if (selected) palette.accent else palette.textSecondary,
                    label = "nav_fg",
                )

                val badgeCount = when (dest) {
                    PrimaryDestination.MESSAGES -> unreadMessagesCount
                    PrimaryDestination.MESH_RADAR -> nearbyNodesCount
                    PrimaryDestination.IDENTITY -> 0
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .scale(scale)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelectTab(dest) },
                        )
                        .padding(vertical = 6.dp)
                        .testTag(dest.testTag),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(pillBg)
                                .padding(horizontal = 18.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                contentDescription = dest.label,
                                tint = contentColor,
                                modifier = Modifier.size(21.dp),
                            )

                            if (badgeCount > 0) {
                                val badgeColor = if (dest == PrimaryDestination.MESSAGES) {
                                    palette.accent
                                } else {
                                    palette.encryptedGreen
                                }
                                val badgeTextColor = if (dest == PrimaryDestination.MESSAGES) {
                                    palette.accentOnPrimary
                                } else {
                                    androidx.compose.ui.graphics.Color(0xFF022C22)
                                }
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(start = 16.dp)
                                        .clip(CircleShape)
                                        .background(badgeColor)
                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = if (badgeCount > 99) "99+" else badgeCount.toString(),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = badgeTextColor,
                                    )
                                }
                            }
                        }

                        Text(
                            text = dest.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = contentColor,
                        )
                    }
                }
            }
        }
    }
}
