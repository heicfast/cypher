package chat.cypher.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
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
        label = "Message",
        selectedIcon = Icons.AutoMirrored.Filled.Chat,
        unselectedIcon = Icons.AutoMirrored.Outlined.Chat,
        testTag = "nav_tab_messages",
    ),
    MESH_RADAR(
        label = "Lounge",
        selectedIcon = Icons.Filled.GraphicEq,
        unselectedIcon = Icons.Outlined.GraphicEq,
        testTag = "nav_tab_radar",
    ),
    IDENTITY(
        label = "Profile",
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

    val onlineCount = remember(peers, connectedBleAddresses) {
        maxOf(connectedBleAddresses.size, peers.count { it.isDirectlyConnected })
    }

    // Back navigation from Lounge or Profile returns to Message screen first
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
                    (slideInHorizontally(tween(260)) { it / 3 } + fadeIn(tween(210))) togetherWith
                        (slideOutHorizontally(tween(210)) { -it / 6 } + fadeOut(tween(150)))
                } else {
                    (slideInHorizontally(tween(240)) { -it / 5 } + fadeIn(tween(190))) togetherWith
                        (slideOutHorizontally(tween(210)) { it / 3 } + fadeOut(tween(150)))
                }
            },
            label = "root_thread_transition",
        ) { threadKey ->
            if (threadKey != null) {
                val peer = peers.firstOrNull { it.peerIdHex.equals(threadKey, ignoreCase = true) }
                val displayName = when {
                    threadKey == MeshStore.BROADCAST_CONVERSATION_KEY -> "Public Lounge"
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
                    myNickname = nickname,
                    myPeerIdHex = myPeerIdHex,
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
                                snackbarHostState.showSnackbar("Attachment exceeds size limit")
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
                                snackbarHostState.showSnackbar("Sent wave to Public Lounge")
                            }
                        } else {
                            controller.sendMeshPing(threadKey)
                        }
                    },
                    onOpenVerification = { targetPeer ->
                        val resolved = targetPeer ?: PeerUiModel(
                            peerIdHex = threadKey,
                            nickname = displayName,
                            sessionState = "established",
                            isKnown = true,
                            lastSeenMs = System.currentTimeMillis(),
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
                    onOpenLounge = {
                        controller.openConversation(null)
                        currentTab = PrimaryDestination.MESH_RADAR
                    },
                )
            } else {
                Scaffold(
                    containerColor = palette.canvas,
                    contentWindowInsets = WindowInsets.statusBars,
                    snackbarHost = {
                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier.padding(bottom = 96.dp),
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
                                fadeIn(tween(220)) togetherWith fadeOut(tween(150))
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
                                    myNickname = nickname,
                                    myPeerIdHex = myPeerIdHex,
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
                                                message = "Sent presence wave to nearby people",
                                                duration = SnackbarDuration.Short,
                                            )
                                        }
                                    },
                                    onOpenGuide = { showProtocolGuide = true },
                                    onOpenLounge = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        currentTab = PrimaryDestination.MESH_RADAR
                                    },
                                    onOpenIdentity = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        currentTab = PrimaryDestination.IDENTITY
                                    },
                                    onPublishNote = { note ->
                                        controller.sendBroadcastText("✨ $note", null)
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                message = "Status note shared",
                                                duration = SnackbarDuration.Short,
                                            )
                                        }
                                    },
                                )

                                PrimaryDestination.MESH_RADAR -> ContactsTab(
                                    peers = peers,
                                    myNickname = nickname,
                                    myPeerIdHex = myPeerIdHex,
                                    isMeshRunning = isMeshRunning,
                                    connectedBleCount = connectedBleAddresses.size,
                                    onOpenDm = { peerHex ->
                                        currentTab = PrimaryDestination.MESSAGES
                                        controller.openConversation(peerHex)
                                    },
                                    onPingPeer = { peer ->
                                        controller.sendMeshPing(peer.peerIdHex)
                                    },
                                    onVerifyPeer = { peer -> verificationPeerTarget = peer },
                                    onToggleFavorite = { peerHex -> controller.toggleFavorite(peerHex) },
                                    onToggleBlocked = { peerHex -> controller.toggleBlocked(peerHex) },
                                    onAnnounceNow = {
                                        controller.announceNow()
                                    },
                                    onBackToMessages = {
                                        currentTab = PrimaryDestination.MESSAGES
                                    },
                                    onSendLoungeReaction = { emoji ->
                                        controller.sendBroadcastText(emoji, null)
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
                                                message = "Display name updated to $newNick",
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
                                                message = "All data cleared and fresh profile created",
                                                duration = SnackbarDuration.Short,
                                            )
                                        }
                                    },
                                    onBackToMessages = {
                                        currentTab = PrimaryDestination.MESSAGES
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
