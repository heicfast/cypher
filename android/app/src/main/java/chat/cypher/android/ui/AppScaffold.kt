package chat.cypher.android.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import chat.cypher.android.MeshForegroundService
import chat.cypher.android.core.MeshController
import chat.cypher.android.core.MeshStore
import chat.cypher.android.core.PeerEntry
import chat.cypher.android.core.PeerUiModel
import chat.cypher.android.data.PeerEntity
import chat.cypher.android.onboarding.OnboardingPreferences
import chat.cypher.android.ui.chats.ChatThread
import chat.cypher.android.ui.chats.ChatsTab
import chat.cypher.android.ui.chats.HomeSubTab
import chat.cypher.android.ui.components.MeshNetworkScreen
import chat.cypher.android.ui.components.PanicModeScreen
import chat.cypher.android.ui.components.SecurityVerificationScreen
import chat.cypher.android.ui.profile.ProfileTab
import java.util.Locale

private enum class FullScreenRoute {
    HOME,
    CHAT_THREAD,
    MESH_NETWORK,
    SECURITY_VERIFICATION,
    SETTINGS,
    PANIC_MODE,
}

@Composable
fun CypherAppRoot(
    onRequestPermissions: () -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    onEmergencyWipeCompleted: () -> Unit,
) {
    val context = LocalContext.current
    val activeConversation by MeshStore.activeConversation.collectAsState()
    val peers by MeshStore.peers.collectAsState()
    val knownPeers by MeshStore.knownPeers.collectAsState()
    val verifiedPeers by MeshStore.verifiedPeers.collectAsState()
    val localIdentityHex by MeshStore.localIdentityHex.collectAsState()

    var currentRoute by rememberSaveable { mutableStateOf(FullScreenRoute.HOME) }
    var previousRoute by rememberSaveable { mutableStateOf(FullScreenRoute.HOME) }
    var selectedHomeTab by rememberSaveable { mutableStateOf(HomeSubTab.CHATS) }
    var verificationPeerKey by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        MeshController.start(context)
    }

    val performEmergencyWipe: () -> Unit = {
        MeshController.stop()
        runCatching {
            context.stopService(Intent(context, MeshForegroundService::class.java))
        }
        MeshStore.emergencyWipe(context)
        OnboardingPreferences.resetOnboarding(context)
        onEmergencyWipeCompleted()
    }

    BackHandler(enabled = currentRoute != FullScreenRoute.HOME || selectedHomeTab != HomeSubTab.CHATS) {
        when (currentRoute) {
            FullScreenRoute.HOME -> {
                if (selectedHomeTab != HomeSubTab.CHATS) {
                    selectedHomeTab = HomeSubTab.CHATS
                }
            }
            FullScreenRoute.SECURITY_VERIFICATION,
            FullScreenRoute.PANIC_MODE -> {
                currentRoute = previousRoute
            }
            else -> {
                currentRoute = FullScreenRoute.HOME
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = currentRoute,
            transitionSpec = {
                fadeIn(animationSpec = tween(180)) togetherWith
                    fadeOut(animationSpec = tween(140))
            },
            label = "cipher_full_screen_router",
        ) { route ->
            when (route) {
                FullScreenRoute.HOME -> {
                    ChatsTab(
                        selectedTab = selectedHomeTab,
                        onSelectTab = { tab ->
                            selectedHomeTab = tab
                        },
                        onOpenConversation = { convKey ->
                            MeshStore.selectConversation(convKey)
                            previousRoute = FullScreenRoute.HOME
                            currentRoute = FullScreenRoute.CHAT_THREAD
                        },
                        onOpenMeshTopology = {
                            previousRoute = FullScreenRoute.HOME
                            currentRoute = FullScreenRoute.MESH_NETWORK
                        },
                        onOpenSettings = {
                            previousRoute = FullScreenRoute.HOME
                            currentRoute = FullScreenRoute.SETTINGS
                        },
                        onOpenPanicMode = {
                            previousRoute = FullScreenRoute.HOME
                            currentRoute = FullScreenRoute.PANIC_MODE
                        },
                        onOpenSecurityVerification = { peerKey ->
                            verificationPeerKey = peerKey
                            previousRoute = FullScreenRoute.HOME
                            currentRoute = FullScreenRoute.SECURITY_VERIFICATION
                        },
                        onPanicWipe = performEmergencyWipe,
                    )
                }

                FullScreenRoute.CHAT_THREAD -> {
                    ChatThread(
                        conversationKey = activeConversation,
                        onBack = {
                            currentRoute = FullScreenRoute.HOME
                        },
                        onOpenSecurityVerification = { peerKey ->
                            verificationPeerKey = peerKey
                            previousRoute = FullScreenRoute.CHAT_THREAD
                            currentRoute = FullScreenRoute.SECURITY_VERIFICATION
                        },
                    )
                }

                FullScreenRoute.MESH_NETWORK -> {
                    MeshNetworkScreen(
                        onBack = {
                            currentRoute = FullScreenRoute.HOME
                        },
                        onSelectPeer = { peerKey ->
                            MeshStore.selectConversation(peerKey)
                            currentRoute = FullScreenRoute.CHAT_THREAD
                        },
                    )
                }

                FullScreenRoute.SECURITY_VERIFICATION -> {
                    val peerUi = remember(verificationPeerKey, peers, knownPeers, verifiedPeers) {
                        resolveVerificationPeerModel(
                            peerKey = verificationPeerKey,
                            peers = peers,
                            knownPeers = knownPeers,
                            verifiedPeers = verifiedPeers,
                        )
                    }
                    val normKey = peerUi?.peerKey?.lowercase(Locale.US).orEmpty()
                    val isVerified = normKey.isNotEmpty() && (
                        normKey in verifiedPeers ||
                            knownPeers[normKey]?.verified == true
                        )

                    SecurityVerificationScreen(
                        peer = peerUi,
                        localIdentityHex = localIdentityHex,
                        isVerified = isVerified,
                        onToggleVerified = {
                            if (normKey.isNotEmpty()) {
                                MeshStore.toggleVerifiedPeer(normKey)
                            }
                        },
                        onBack = {
                            currentRoute = previousRoute
                        },
                    )
                }

                FullScreenRoute.SETTINGS -> {
                    ProfileTab(
                        onBack = {
                            currentRoute = FullScreenRoute.HOME
                        },
                        onOpenMeshNetwork = {
                            previousRoute = FullScreenRoute.SETTINGS
                            currentRoute = FullScreenRoute.MESH_NETWORK
                        },
                        onOpenSecurityVerification = {
                            verificationPeerKey = peers.firstOrNull()?.peerKeyHex.orEmpty()
                            previousRoute = FullScreenRoute.SETTINGS
                            currentRoute = FullScreenRoute.SECURITY_VERIFICATION
                        },
                        onOpenChannels = {
                            selectedHomeTab = HomeSubTab.CHANNELS
                            currentRoute = FullScreenRoute.HOME
                        },
                        onOpenPanicMode = {
                            previousRoute = FullScreenRoute.SETTINGS
                            currentRoute = FullScreenRoute.PANIC_MODE
                        },
                        onRequestBatteryExemption = onRequestBatteryOptimization,
                        onEmergencyWipe = performEmergencyWipe,
                    )
                }

                FullScreenRoute.PANIC_MODE -> {
                    PanicModeScreen(
                        onClearNow = performEmergencyWipe,
                        onCancel = {
                            currentRoute = previousRoute
                        },
                    )
                }
            }
        }
    }
}

private fun resolveVerificationPeerModel(
    peerKey: String,
    peers: List<PeerEntry>,
    knownPeers: Map<String, PeerEntity>,
    verifiedPeers: Set<String>,
): PeerUiModel? {
    val norm = peerKey.trim().lowercase(Locale.US)
    if (norm.isBlank() || norm.startsWith("#")) return null

    val live = peers.firstOrNull { it.peerKeyHex.equals(norm, ignoreCase = true) }
    if (live != null) {
        return PeerUiModel(
            peerKey = live.peerKeyHex,
            nickname = live.nickname,
            connected = live.connected,
            sessionEstablished = live.sessionEstablished,
            rssi = live.rssi,
            hops = live.hops,
            verified = live.verified || norm in verifiedPeers,
            distanceMeters = null,
        )
    }

    val known = knownPeers[norm]
    if (known != null) {
        return PeerUiModel(
            peerKey = known.peerKeyHex,
            nickname = known.customAlias.ifBlank { known.nickname },
            connected = false,
            sessionEstablished = false,
            rssi = known.lastRssi,
            hops = known.lastHops,
            verified = known.verified || norm in verifiedPeers,
            distanceMeters = null,
        )
    }

    return PeerUiModel(
        peerKey = norm,
        nickname = MeshStore.resolvePeerNickname(norm),
        connected = false,
        sessionEstablished = false,
        rssi = null,
        hops = 1,
        verified = norm in verifiedPeers,
        distanceMeters = null,
    )
}
