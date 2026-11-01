package chat.cypher.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import chat.cypher.android.core.MeshController
import chat.cypher.android.core.MeshStore
import chat.cypher.android.core.PeerIdentity
import chat.cypher.android.core.PeerUiModel
import chat.cypher.android.ui.chats.ChatThreadScreen
import chat.cypher.android.ui.chats.ChatsTab
import chat.cypher.android.ui.chats.CipherHomeSegment
import chat.cypher.android.ui.components.AboutCypherSheet
import chat.cypher.android.ui.components.MeshNetworkScreen
import chat.cypher.android.ui.components.PanicModeScreen
import chat.cypher.android.ui.components.SecurityVerificationSheet
import chat.cypher.android.ui.profile.ProfileTab
import kotlinx.coroutines.launch

private enum class CipherOverlayScreen {
    NONE,
    SETTINGS,
    MESH_NETWORK,
    SECURITY_VERIFICATION,
    PANIC_MODE,
}

@Composable
fun AppScaffold(
    controller: MeshController,
) {
    val activeConversationKey by controller.activeConversationKey.collectAsState()
    val peers by controller.peers.collectAsState()
    val verifiedMap by controller.store.verifiedPeers.collectAsState()
    val myPeerIdHex by controller.myPeerIdHex.collectAsState()
    val myFingerprintHex by controller.myFingerprintHex.collectAsState()
    val isMeshRunning by controller.isMeshRunning.collectAsState()
    val coreVersion by controller.coreVersion.collectAsState()

    var selectedHomeSegment by rememberSaveable { mutableStateOf(CipherHomeSegment.CHATS) }
    var overlayScreen by rememberSaveable { mutableStateOf(CipherOverlayScreen.NONE) }
    var verificationPeerHex by rememberSaveable { mutableStateOf<String?>(null) }
    var showAboutSheet by rememberSaveable { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        containerColor = Color(0xFF0D0E17),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding()),
        ) {
            AnimatedContent(
                targetState = Pair(overlayScreen, activeConversationKey),
                transitionSpec = {
                    fadeIn(animationSpec = tween(180)) togetherWith
                        fadeOut(animationSpec = tween(150))
                },
                label = "cipher_screen_router",
            ) { (currentOverlay, currentConvKey) ->
                when {
                    currentOverlay == CipherOverlayScreen.PANIC_MODE -> {
                        PanicModeScreen(
                            onClearNow = {
                                controller.panicWipe()
                                overlayScreen = CipherOverlayScreen.NONE
                                selectedHomeSegment = CipherHomeSegment.CHATS
                            },
                            onCancel = {
                                overlayScreen = CipherOverlayScreen.NONE
                            },
                        )
                    }

                    currentOverlay == CipherOverlayScreen.SECURITY_VERIFICATION -> {
                        val targetHex = (verificationPeerHex ?: MeshStore.RICK_PEER_KEY).lowercase()
                        val targetPeer = resolveVerificationPeerModel(
                            peerHex = targetHex,
                            peers = peers,
                            verifiedMap = verifiedMap,
                            controller = controller,
                        )
                        SecurityVerificationSheet(
                            peer = targetPeer,
                            myPeerIdHex = myPeerIdHex,
                            myFingerprintHex = myFingerprintHex,
                            onMarkVerified = {
                                controller.markPeerVerified(targetPeer.peerIdHex)
                            },
                            onUnverify = {
                                controller.unverifyPeer(targetPeer.peerIdHex)
                            },
                            onDismiss = {
                                overlayScreen = CipherOverlayScreen.NONE
                            },
                        )
                    }

                    currentOverlay == CipherOverlayScreen.MESH_NETWORK -> {
                        MeshNetworkScreen(
                            peers = peers,
                            isMeshRunning = isMeshRunning,
                            onBack = {
                                overlayScreen = CipherOverlayScreen.NONE
                            },
                            onSelectPeer = { peerHex ->
                                overlayScreen = CipherOverlayScreen.NONE
                                controller.openConversation(peerHex)
                            },
                            onAnnouncePulse = {
                                controller.announceNow()
                            },
                        )
                    }

                    currentOverlay == CipherOverlayScreen.SETTINGS -> {
                        ProfileTab(
                            controller = controller,
                            onBack = {
                                overlayScreen = CipherOverlayScreen.NONE
                            },
                            onOpenVerification = { peerHex ->
                                verificationPeerHex = peerHex ?: MeshStore.RICK_PEER_KEY
                                overlayScreen = CipherOverlayScreen.SECURITY_VERIFICATION
                            },
                            onOpenAbout = {
                                showAboutSheet = true
                            },
                            onOpenMeshNetwork = {
                                overlayScreen = CipherOverlayScreen.MESH_NETWORK
                            },
                            onOpenChannels = {
                                overlayScreen = CipherOverlayScreen.NONE
                                controller.openConversation(null)
                                selectedHomeSegment = CipherHomeSegment.CHANNELS
                            },
                            onOpenPanicMode = {
                                overlayScreen = CipherOverlayScreen.PANIC_MODE
                            },
                        )
                    }

                    currentConvKey != null -> {
                        ChatThreadScreen(
                            controller = controller,
                            conversationKey = currentConvKey,
                            onBack = {
                                controller.openConversation(null)
                            },
                            onOpenVerification = { peerHex ->
                                verificationPeerHex = peerHex ?: if (currentConvKey.startsWith("#")) {
                                    MeshStore.RICK_PEER_KEY
                                } else {
                                    currentConvKey
                                }
                                overlayScreen = CipherOverlayScreen.SECURITY_VERIFICATION
                            },
                            onOpenMeshNetwork = {
                                overlayScreen = CipherOverlayScreen.MESH_NETWORK
                            },
                        )
                    }

                    else -> {
                        ChatsTab(
                            controller = controller,
                            selectedSegment = selectedHomeSegment,
                            onSelectSegment = { selectedHomeSegment = it },
                            onOpenConversation = { key ->
                                controller.openConversation(key)
                            },
                            onOpenMeshNetwork = {
                                overlayScreen = CipherOverlayScreen.MESH_NETWORK
                            },
                            onOpenSecurityVerification = { peerHex ->
                                verificationPeerHex = peerHex ?: MeshStore.RICK_PEER_KEY
                                overlayScreen = CipherOverlayScreen.SECURITY_VERIFICATION
                            },
                            onOpenSettings = {
                                overlayScreen = CipherOverlayScreen.SETTINGS
                            },
                            onOpenPanicMode = {
                                overlayScreen = CipherOverlayScreen.PANIC_MODE
                            },
                            onConversationDeleted = { backup ->
                                coroutineScope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = "Conversation deleted",
                                        actionLabel = "Undo",
                                        duration = SnackbarDuration.Short,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        controller.restoreConversationBackup(backup)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showAboutSheet) {
        AboutCypherSheet(
            coreVersion = coreVersion,
            onDismiss = { showAboutSheet = false },
        )
    }
}

private fun resolveVerificationPeerModel(
    peerHex: String,
    peers: List<PeerUiModel>,
    verifiedMap: Map<String, String>,
    controller: MeshController,
): PeerUiModel {
    val cleanHex = peerHex.lowercase()
    val live = peers.firstOrNull { it.peerIdHex.equals(cleanHex, ignoreCase = true) }
    if (live != null) return live

    val isRick = cleanHex == MeshStore.RICK_PEER_KEY
    val nick = if (isRick) "Rick" else controller.resolvePeerNickname(cleanHex)
    val fp = when {
        isRick -> MeshStore.RICK_FINGERPRINT_HEX
        verifiedMap[cleanHex] != null -> verifiedMap.getValue(cleanHex)
        else -> PeerIdentity.fingerprintHex(cleanHex)
    }
    val isVerified = isRick || verifiedMap.containsKey(cleanHex)

    return PeerUiModel(
        peerIdHex = cleanHex,
        nickname = nick,
        sessionState = "established",
        isKnown = true,
        lastSeenMs = System.currentTimeMillis(),
        rssi = -56,
        hops = 1,
        distanceMeters = 12,
        isDirectlyConnected = true,
        isFavorite = isRick,
        isVerified = isVerified,
        isBlocked = controller.store.isBlocked(cleanHex),
        fingerprintHex = fp,
    )
}
