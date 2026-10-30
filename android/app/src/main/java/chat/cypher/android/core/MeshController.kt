package chat.cypher.android.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.app.NotificationCompat
import chat.cypher.android.MainActivity
import chat.cypher.android.MeshForegroundService
import chat.cypher.android.R
import chat.cypher.android.ble.BleLinkLayer
import chat.cypher.android.ble.MeshEvents
import chat.cypher.android.onboarding.hasAllMeshPermissions
import chat.cypher.android.ui.theme.ThemePreference
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class PeerUiModel(
    val peerIdHex: String,
    val nickname: String,
    val sessionState: String,
    val isKnown: Boolean,
    val lastSeenMs: Long,
    val rssi: Int? = null,
    val isDirectlyConnected: Boolean = false,
    val isFavorite: Boolean = false,
    val isVerified: Boolean = false,
    val isBlocked: Boolean = false,
    val fingerprintHex: String = "",
)

data class SlashCommandSpec(
    val command: String,
    val syntax: String,
    val description: String,
)

sealed class SlashCommandOutcome {
    data object NotASlashCommand : SlashCommandOutcome()
    data class Handled(val feedback: String? = null) : SlashCommandOutcome()
    data class OpenVerificationSheet(val peerHex: String?) : SlashCommandOutcome()
}

/**
 * Coordinates the Rust mesh engine (via [CypherBridge] & [MeshRuntime]),
 * the Android BLE link layer ([BleLinkLayer]), and persistent storage ([MeshStore]).
 */
class MeshController(private val appContext: Context) {
    companion object {
        private const val TAG = "MeshController"
        private const val PUMP_INTERVAL_MS = 250L
        private const val MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024 // 10 MiB cap (bitchat parity)
        private const val DM_NOTIFICATION_CHANNEL = "cypher_dm_alerts"
        private const val DM_NOTIFICATION_BASE_ID = 5200

        private val WIRE_REPLY_REGEX = Regex("^> @([^:\\n]{1,32}): ([^\\n]{1,120})\\n([\\s\\S]*)$")

        val SLASH_COMMANDS: List<SlashCommandSpec> = listOf(
            SlashCommandSpec("/hug", "/hug [peer]", "Send a warm mesh hug to the channel or peer"),
            SlashCommandSpec("/slap", "/slap [peer]", "Slap someone around a bit with a large trout"),
            SlashCommandSpec("/shrug", "/shrug [message]", "Append ¯\\_(ツ)_/¯ to your message"),
            SlashCommandSpec("/ping", "/ping", "Send an encrypted Noise / mesh latency probe"),
            SlashCommandSpec("/verify", "/verify", "Inspect & verify SHA-256 safety numbers"),
            SlashCommandSpec("/nick", "/nick <name>", "Change your mesh broadcast nickname"),
            SlashCommandSpec("/w", "/w", "List online mesh peers in range"),
            SlashCommandSpec("/clear", "/clear", "Clear messages in the active conversation"),
            SlashCommandSpec("/block", "/block [@peer]", "Mute & block messages from a peer"),
            SlashCommandSpec("/unblock", "/unblock [@peer]", "Unblock a previously muted peer"),
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val coreLock = Any()

    val store: MeshStore = MeshStore(appContext)

    private var nodeHandle: Long = 0L
    private var tickJob: Job? = null
    private var unsubscribeEvents: (() -> Unit)? = null

    private val _myPeerIdHex = MutableStateFlow("")
    val myPeerIdHex: StateFlow<String> = _myPeerIdHex.asStateFlow()

    private val _myStaticPublicKeyHex = MutableStateFlow("")
    val myStaticPublicKeyHex: StateFlow<String> = _myStaticPublicKeyHex.asStateFlow()

    private val _myFingerprintHex = MutableStateFlow("")
    val myFingerprintHex: StateFlow<String> = _myFingerprintHex.asStateFlow()

    private val _nickname = MutableStateFlow(store.loadNickname())
    val nickname: StateFlow<String> = _nickname.asStateFlow()

    private val _coreVersion = MutableStateFlow("0.1.0")
    val coreVersion: StateFlow<String> = _coreVersion.asStateFlow()

    private val _peers = MutableStateFlow<List<PeerUiModel>>(emptyList())
    val peers: StateFlow<List<PeerUiModel>> = _peers.asStateFlow()

    private val _activeConversationKey = MutableStateFlow<String?>(null)
    val activeConversationKey: StateFlow<String?> = _activeConversationKey.asStateFlow()

    private val _isMeshRunning = MutableStateFlow(false)
    val isMeshRunning: StateFlow<Boolean> = _isMeshRunning.asStateFlow()

    private val _lastAnnounceTimeMs = MutableStateFlow(0L)
    val lastAnnounceTimeMs: StateFlow<Long> = _lastAnnounceTimeMs.asStateFlow()

    val conversations: StateFlow<Map<String, List<ChatMessage>>> = store.conversations
    val unreadCounts: StateFlow<Map<String, Int>> = store.unreadCounts
    val favorites: StateFlow<Set<String>> = store.favorites
    val verifiedPeers: StateFlow<Map<String, String>> = store.verifiedPeers
    val blockedPeers: StateFlow<Set<String>> = store.blockedPeers
    val knownPeerNames: StateFlow<Map<String, String>> = store.knownPeerNames
    val pinnedConversations: StateFlow<Set<String>> = store.pinnedConversations
    val mutedConversations: StateFlow<Set<String>> = store.mutedConversations
    val markedUnreadConversations: StateFlow<Set<String>> = store.markedUnreadConversations
    val drafts: StateFlow<Map<String, String>> = store.drafts
    val themePreference: StateFlow<ThemePreference> = store.themePreference
    val backgroundServiceEnabled: StateFlow<Boolean> = store.backgroundServiceEnabled
    val batteryOptimizationSkipped: StateFlow<Boolean> = store.batteryOptimizationSkipped

    val bleLinkLayer = BleLinkLayer(appContext)

    val connectedBleAddresses: StateFlow<Set<String>> = bleLinkLayer.connectedAddresses

    init {
        ensureDmNotificationChannel()
        unsubscribeEvents = MeshEvents.subscribe(::onEngineEvents)
        initNativeNode()
    }

    private fun initNativeNode() {
        synchronized(coreLock) {
            nodeHandle = MeshRuntime.start(appContext, _nickname.value)
            if (nodeHandle != 0L) {
                refreshStateFromEngineLocked()
                // Sync persisted favorites, verified, and blocked peer sets into the Rust engine
                for (fav in store.favorites.value) {
                    runCatching { CypherBridge.nativeSetPeerFavorite(nodeHandle, fav, true) }
                }
                for ((peerHex, _) in store.verifiedPeers.value) {
                    runCatching { CypherBridge.nativeSetPeerVerified(nodeHandle, peerHex, true) }
                }
                for (blocked in store.blockedPeers.value) {
                    runCatching { CypherBridge.nativeBlockPeer(nodeHandle, blocked) }
                }
            } else {
                // Fallback identity when running in JVM unit tests without native .so
                val seed = store.loadOrNullIdentitySeed() ?: ByteArray(16).also {
                    java.security.SecureRandom().nextBytes(it)
                    store.persistIdentitySeed(it)
                }
                val peerHex = seed.take(8).joinToString("") { "%02x".format(it) }
                val fp = PeerIdentity.fingerprintHex(peerHex)
                _myPeerIdHex.value = peerHex
                _myStaticPublicKeyHex.value = fp
                _myFingerprintHex.value = fp
            }
        }
    }

    fun startMesh() {
        val radioStarted = bleLinkLayer.start()
        _isMeshRunning.value = radioStarted || nodeHandle != 0L
        synchronized(coreLock) {
            if (nodeHandle != 0L) {
                val out = runCatching { CypherBridge.nativeAnnounce(nodeHandle) }.getOrNull()
                emitEngineOutputLocked(out)
                refreshStateFromEngineLocked()
                _lastAnnounceTimeMs.value = System.currentTimeMillis()
            }
        }
        if (tickJob?.isActive != true) {
            tickJob = scope.launch {
                var tickCounter = 0
                while (isActive) {
                    delay(PUMP_INTERVAL_MS)
                    synchronized(coreLock) {
                        if (nodeHandle != 0L) {
                            bleLinkLayer.tick()
                            tickCounter++
                            if (tickCounter % 8 == 0) {
                                refreshStateFromEngineLocked()
                            }
                        }
                    }
                }
            }
        }
    }

    fun stopMesh() {
        tickJob?.cancel()
        tickJob = null
        synchronized(coreLock) {
            if (nodeHandle != 0L) {
                val out = runCatching { CypherBridge.nativeStopServices(nodeHandle) }.getOrNull()
                emitEngineOutputLocked(out)
            }
        }
        bleLinkLayer.stop()
        _isMeshRunning.value = false
    }

    fun announceNow(): Boolean {
        return synchronized(coreLock) {
            if (nodeHandle != 0L) {
                val out = runCatching { CypherBridge.nativeAnnounce(nodeHandle) }.getOrNull()
                emitEngineOutputLocked(out)
                refreshStateFromEngineLocked()
                _lastAnnounceTimeMs.value = System.currentTimeMillis()
                true
            } else {
                false
            }
        }
    }

    fun setBackgroundServiceEnabled(enabled: Boolean) {
        store.setBackgroundServiceEnabled(enabled)
        if (enabled) {
            if (hasAllMeshPermissions(appContext)) {
                MeshForegroundService.start(appContext)
            }
        } else {
            val stopIntent = Intent(appContext, MeshForegroundService::class.java).apply {
                action = MeshForegroundService.ACTION_STOP
            }
            runCatching { appContext.startService(stopIntent) }
        }
    }

    fun setThemePreference(preference: ThemePreference) {
        store.setThemePreference(preference)
    }

    fun setBatteryOptimizationSkipped(skipped: Boolean) {
        store.setBatteryOptimizationSkipped(skipped)
    }

    fun updateNickname(newName: String) {
        val clean = newName.trim().take(24)
        if (clean.isEmpty()) return
        _nickname.value = clean
        store.saveNickname(clean)
        synchronized(coreLock) {
            if (nodeHandle != 0L) {
                val out = runCatching { CypherBridge.nativeSetNickname(nodeHandle, clean) }.getOrNull()
                emitEngineOutputLocked(out)
                refreshStateFromEngineLocked()
                _lastAnnounceTimeMs.value = System.currentTimeMillis()
            }
        }
    }

    fun openConversation(conversationKey: String?) {
        _activeConversationKey.value = conversationKey
        if (conversationKey != null) {
            val unreadIncoming = store.markConversationRead(conversationKey)
            if (conversationKey != MeshStore.BROADCAST_CONVERSATION_KEY && unreadIncoming.isNotEmpty()) {
                synchronized(coreLock) {
                    if (nodeHandle != 0L) {
                        val seenIds = HashSet<String>()
                        for (msg in unreadIncoming) {
                            if (seenIds.add(msg.id)) {
                                val out = runCatching {
                                    CypherBridge.nativeSendReadReceipt(nodeHandle, conversationKey, msg.id)
                                }.getOrNull()
                                emitEngineOutputLocked(out)
                            }
                        }
                    }
                }
            }
        }
    }

    fun sendBroadcastText(text: String, replyTo: ReplyReference? = null) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val wireText = encodeWireReply(clean, replyTo)
        val msgId = UUID.randomUUID().toString().uppercase()

        store.appendMessage(
            ChatMessage(
                id = msgId,
                conversationKey = MeshStore.BROADCAST_CONVERSATION_KEY,
                senderPeerHex = _myPeerIdHex.value,
                senderNickname = _nickname.value,
                text = clean,
                timestampMs = System.currentTimeMillis(),
                isOutgoing = true,
                isPrivate = false,
                delivery = DeliveryState.SENT,
                replyTo = replyTo,
            ),
            activeConversationKey = _activeConversationKey.value,
        )
        store.updateDraft(MeshStore.BROADCAST_CONVERSATION_KEY, "")

        synchronized(coreLock) {
            if (nodeHandle != 0L) {
                val out = runCatching {
                    CypherBridge.nativeSendPublicMessage(nodeHandle, wireText)
                }.getOrNull()
                emitEngineOutputLocked(out)
            }
        }
    }

    fun sendPrivateMessage(recipientHex: String, text: String, replyTo: ReplyReference? = null) {
        val clean = text.trim()
        val peerKey = recipientHex.lowercase()
        if (clean.isEmpty() || peerKey.isEmpty()) return
        val wireText = encodeWireReply(clean, replyTo)
        val msgId = UUID.randomUUID().toString().uppercase()

        store.appendMessage(
            ChatMessage(
                id = msgId,
                conversationKey = peerKey,
                senderPeerHex = _myPeerIdHex.value,
                senderNickname = _nickname.value,
                text = clean,
                timestampMs = System.currentTimeMillis(),
                isOutgoing = true,
                isPrivate = true,
                delivery = DeliveryState.SENT,
                replyTo = replyTo,
            ),
            activeConversationKey = _activeConversationKey.value,
        )
        store.updateDraft(peerKey, "")

        synchronized(coreLock) {
            if (nodeHandle != 0L) {
                val out = runCatching {
                    CypherBridge.nativeSendPrivateMessage(nodeHandle, peerKey, wireText, msgId)
                }.getOrNull()
                emitEngineOutputLocked(out)
                refreshStateFromEngineLocked()
            }
        }
    }

    fun sendMeshPing(peerHex: String): Boolean {
        val cleanHex = peerHex.lowercase().trim()
        if (cleanHex.isEmpty() || cleanHex == MeshStore.BROADCAST_CONVERSATION_KEY) return false
        val ok = synchronized(coreLock) {
            if (nodeHandle != 0L) {
                val out = runCatching { CypherBridge.nativeSendMeshPing(nodeHandle, cleanHex) }.getOrNull()
                emitEngineOutputLocked(out)
                out != null
            } else {
                false
            }
        }
        val peerName = resolvePeerNickname(cleanHex)
        appendSystemMessage(
            conversationKey = cleanHex,
            text = if (ok) {
                "Sent encrypted mesh ping to @$peerName (${PeerIdentity.shortId(cleanHex)})"
            } else {
                "Queued mesh probe for @$peerName (${PeerIdentity.shortId(cleanHex)})"
            },
        )
        return ok
    }

    fun sendVoiceNoteFile(
        recipientHex: String?,
        audioFile: File,
        durationMs: Long,
    ): Boolean {
        if (!audioFile.exists()) return false
        val payload = runCatching { audioFile.readBytes() }.getOrNull() ?: return false
        if (payload.isEmpty() || payload.size > MAX_ATTACHMENT_BYTES) return false

        val normalizedRecipient = recipientHex
            ?.takeIf { it != MeshStore.BROADCAST_CONVERSATION_KEY }
            ?.lowercase()
        val convKey = normalizedRecipient ?: MeshStore.BROADCAST_CONVERSATION_KEY
        val transferId = UUID.randomUUID().toString()

        val savedAttachment = store.saveIncomingMedia(
            messageId = transferId,
            kind = AttachmentKind.AUDIO,
            fileName = audioFile.name,
            mimeType = "audio/mp4",
            bytes = payload,
        ).copy(durationMs = durationMs)

        store.appendMessage(
            ChatMessage(
                id = transferId,
                conversationKey = convKey,
                senderPeerHex = _myPeerIdHex.value,
                senderNickname = _nickname.value,
                text = "Voice note (${formatDurationShort(durationMs)})",
                timestampMs = System.currentTimeMillis(),
                isOutgoing = true,
                isPrivate = normalizedRecipient != null,
                delivery = DeliveryState.SENT,
                attachment = savedAttachment,
            ),
            activeConversationKey = _activeConversationKey.value,
        )

        synchronized(coreLock) {
            if (nodeHandle != 0L) {
                val out = runCatching {
                    if (normalizedRecipient == null) {
                        CypherBridge.nativeSendFileBroadcast(nodeHandle, audioFile.name, "audio/mp4", payload)
                    } else {
                        CypherBridge.nativeSendFilePrivate(
                            nodeHandle,
                            normalizedRecipient,
                            audioFile.name,
                            "audio/mp4",
                            payload,
                            transferId,
                        )
                    }
                }.getOrNull()
                emitEngineOutputLocked(out)
            }
        }
        return true
    }

    fun sendMedia(
        recipientHex: String?,
        uri: Uri,
        forcedKind: AttachmentKind? = null,
    ): Boolean {
        val resolver = appContext.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val fileName = queryFileName(uri) ?: "attachment_${System.currentTimeMillis()}"
        val payload = runCatching {
            resolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return false

        if (payload.isEmpty() || payload.size > MAX_ATTACHMENT_BYTES) {
            Log.w(TAG, "Attachment rejected: size=${payload.size} bytes")
            return false
        }

        val kind = forcedKind ?: when {
            mime.startsWith("image/") -> AttachmentKind.IMAGE
            mime.startsWith("audio/") -> AttachmentKind.AUDIO
            else -> AttachmentKind.FILE
        }

        val normalizedRecipient = recipientHex
            ?.takeIf { it != MeshStore.BROADCAST_CONVERSATION_KEY }
            ?.lowercase()
        val convKey = normalizedRecipient ?: MeshStore.BROADCAST_CONVERSATION_KEY
        val transferId = UUID.randomUUID().toString()

        val savedAttachment = store.saveIncomingMedia(transferId, kind, fileName, mime, payload)

        store.appendMessage(
            ChatMessage(
                id = transferId,
                conversationKey = convKey,
                senderPeerHex = _myPeerIdHex.value,
                senderNickname = _nickname.value,
                text = fileName,
                timestampMs = System.currentTimeMillis(),
                isOutgoing = true,
                isPrivate = normalizedRecipient != null,
                delivery = DeliveryState.SENT,
                attachment = savedAttachment,
            ),
            activeConversationKey = _activeConversationKey.value,
        )

        synchronized(coreLock) {
            if (nodeHandle != 0L) {
                val out = runCatching {
                    if (normalizedRecipient == null) {
                        CypherBridge.nativeSendFileBroadcast(nodeHandle, fileName, mime, payload)
                    } else {
                        CypherBridge.nativeSendFilePrivate(
                            nodeHandle,
                            normalizedRecipient,
                            fileName,
                            mime,
                            payload,
                            transferId,
                        )
                    }
                }.getOrNull()
                emitEngineOutputLocked(out)
            }
        }
        return true
    }

    fun executeSlashCommand(
        rawInput: String,
        conversationKey: String,
    ): SlashCommandOutcome {
        val trimmed = rawInput.trim()
        if (!trimmed.startsWith("/")) return SlashCommandOutcome.NotASlashCommand

        val parts = trimmed.split(Regex("\\s+"), limit = 2)
        val cmd = parts[0].lowercase()
        val arg = parts.getOrNull(1)?.trim().orEmpty()
        val isBroadcast = conversationKey == MeshStore.BROADCAST_CONVERSATION_KEY
        val defaultTargetName = if (!isBroadcast) resolvePeerNickname(conversationKey) else "everyone"

        return when (cmd) {
            "/hug" -> {
                val target = arg.removePrefix("@").ifBlank { defaultTargetName }
                val actionText = "* ${_nickname.value} gives @$target a warm encrypted mesh hug *"
                if (isBroadcast) sendBroadcastText(actionText) else sendPrivateMessage(conversationKey, actionText)
                SlashCommandOutcome.Handled()
            }
            "/slap" -> {
                val target = arg.removePrefix("@").ifBlank { defaultTargetName }
                val actionText = "* ${_nickname.value} slaps @$target around a bit with a large trout *"
                if (isBroadcast) sendBroadcastText(actionText) else sendPrivateMessage(conversationKey, actionText)
                SlashCommandOutcome.Handled()
            }
            "/shrug" -> {
                val body = if (arg.isBlank()) "¯\\_(ツ)_/¯" else "$arg ¯\\_(ツ)_/¯"
                if (isBroadcast) sendBroadcastText(body) else sendPrivateMessage(conversationKey, body)
                SlashCommandOutcome.Handled()
            }
            "/nick" -> {
                if (arg.isBlank()) {
                    appendSystemMessage(conversationKey, "Usage: /nick <new-nickname>")
                } else {
                    updateNickname(arg)
                    appendSystemMessage(conversationKey, "Nickname updated to @${_nickname.value}")
                }
                SlashCommandOutcome.Handled()
            }
            "/w" -> {
                val online = _peers.value.filter { it.isDirectlyConnected }
                val msg = if (online.isEmpty()) {
                    "No other peers are directly connected right now."
                } else {
                    "Online mesh peers (${online.size}): ${online.joinToString(", ") { "@${it.nickname}" }}"
                }
                appendSystemMessage(conversationKey, msg)
                SlashCommandOutcome.Handled()
            }
            "/ping" -> {
                if (isBroadcast) {
                    val ok = announceNow()
                    appendSystemMessage(
                        conversationKey,
                        if (ok) "Broadcasted signed presence announce across #mesh." else "Mesh announce queued.",
                    )
                } else {
                    sendMeshPing(conversationKey)
                }
                SlashCommandOutcome.Handled()
            }
            "/clear" -> {
                store.clearConversationMessages(conversationKey)
                SlashCommandOutcome.Handled("Cleared conversation history")
            }
            "/verify" -> {
                val peerTarget = if (isBroadcast) null else conversationKey
                SlashCommandOutcome.OpenVerificationSheet(peerTarget)
            }
            "/block" -> {
                val peerHex = resolvePeerHexFromArg(arg, conversationKey)
                if (peerHex == null) {
                    appendSystemMessage(conversationKey, "Specify a peer to block or run /block inside a DM.")
                } else if (!store.isBlocked(peerHex)) {
                    toggleBlocked(peerHex)
                } else {
                    appendSystemMessage(conversationKey, "Peer is already blocked.")
                }
                SlashCommandOutcome.Handled()
            }
            "/unblock" -> {
                val peerHex = resolvePeerHexFromArg(arg, conversationKey)
                if (peerHex == null) {
                    appendSystemMessage(conversationKey, "Specify a peer to unblock or run /unblock inside a DM.")
                } else if (store.isBlocked(peerHex)) {
                    toggleBlocked(peerHex)
                } else {
                    appendSystemMessage(conversationKey, "Peer is not blocked.")
                }
                SlashCommandOutcome.Handled()
            }
            else -> SlashCommandOutcome.NotASlashCommand
        }
    }

    private fun resolvePeerHexFromArg(arg: String, currentConversationKey: String): String? {
        if (arg.isBlank()) {
            return currentConversationKey.takeIf { it != MeshStore.BROADCAST_CONVERSATION_KEY }
        }
        val clean = arg.removePrefix("@").lowercase()
        val matchByPeer = _peers.value.firstOrNull {
            it.peerIdHex.equals(clean, ignoreCase = true) ||
                it.nickname.equals(clean, ignoreCase = true) ||
                PeerIdentity.formatHandle(it.nickname, it.peerIdHex).removePrefix("@").equals(clean, ignoreCase = true)
        }
        if (matchByPeer != null) return matchByPeer.peerIdHex
        val matchByKnown = store.knownPeerNames.value.entries.firstOrNull { (hex, name) ->
            hex.equals(clean, ignoreCase = true) || name.equals(clean, ignoreCase = true)
        }
        return matchByKnown?.key
    }

    fun resolvePeerNickname(peerHex: String): String {
        val key = peerHex.lowercase()
        return _peers.value.firstOrNull { it.peerIdHex == key }?.nickname
            ?: store.knownPeerNames.value[key]
            ?: "peer-${key.take(4)}"
    }

    fun toggleReaction(conversationKey: String, messageId: String, emoji: String) {
        store.toggleMessageReaction(conversationKey, messageId, emoji)
    }

    fun toggleFavorite(peerHex: String) {
        val clean = peerHex.lowercase()
        store.toggleFavorite(clean)
        val isNowFav = store.favorites.value.contains(clean)
        synchronized(coreLock) {
            if (nodeHandle != 0L) {
                runCatching { CypherBridge.nativeSetPeerFavorite(nodeHandle, clean, isNowFav) }
            }
            refreshStateFromEngineLocked()
        }
    }

    fun markPeerVerified(peerHex: String) {
        val clean = peerHex.lowercase()
        val peer = _peers.value.firstOrNull { it.peerIdHex.equals(clean, ignoreCase = true) }
        val fp = peer?.fingerprintHex?.ifBlank { PeerIdentity.fingerprintHex(clean) }
            ?: PeerIdentity.fingerprintHex(clean)
        store.markPeerVerified(clean, fp)
        synchronized(coreLock) {
            if (nodeHandle != 0L) {
                runCatching { CypherBridge.nativeSetPeerVerified(nodeHandle, clean, true) }
            }
            refreshStateFromEngineLocked()
        }
        appendSystemMessage(
            conversationKey = clean,
            text = "Marked safety number as verified (${fp.take(12).uppercase()}…)",
        )
    }

    fun unverifyPeer(peerHex: String) {
        val clean = peerHex.lowercase()
        store.unverifyPeer(clean)
        synchronized(coreLock) {
            if (nodeHandle != 0L) {
                runCatching { CypherBridge.nativeSetPeerVerified(nodeHandle, clean, false) }
            }
            refreshStateFromEngineLocked()
        }
        appendSystemMessage(
            conversationKey = clean,
            text = "Cleared verified status for this peer",
        )
    }

    fun toggleBlocked(peerHex: String) {
        val clean = peerHex.lowercase()
        val nowBlocked = store.toggleBlocked(clean)
        synchronized(coreLock) {
            if (nodeHandle != 0L) {
                runCatching {
                    if (nowBlocked) {
                        CypherBridge.nativeBlockPeer(nodeHandle, clean)
                    } else {
                        CypherBridge.nativeUnblockPeer(nodeHandle, clean)
                    }
                }
            }
            refreshStateFromEngineLocked()
        }
        appendSystemMessage(
            conversationKey = clean,
            text = if (nowBlocked) "Peer blocked — incoming traffic muted" else "Peer unblocked",
        )
    }

    fun togglePinConversation(conversationKey: String): Boolean =
        store.togglePinConversation(conversationKey)

    fun toggleMuteConversation(conversationKey: String): Boolean =
        store.toggleMuteConversation(conversationKey)

    fun toggleMarkUnread(conversationKey: String): Boolean =
        store.toggleMarkUnread(conversationKey)

    fun deleteConversationWithBackup(conversationKey: String): ConversationBackup? =
        store.deleteConversationWithBackup(conversationKey)

    fun restoreConversationBackup(backup: ConversationBackup) {
        store.restoreConversationBackup(backup)
    }

    fun clearConversationMessages(conversationKey: String) {
        store.clearConversationMessages(conversationKey)
    }

    fun updateDraft(conversationKey: String, text: String) {
        store.updateDraft(conversationKey, text)
    }

    fun panicWipe() {
        stopMesh()
        store.wipeAll()
        _peers.value = emptyList()
        _nickname.value = store.loadNickname()
        synchronized(coreLock) {
            nodeHandle = MeshRuntime.panicReset(appContext, _nickname.value)
            if (nodeHandle != 0L) {
                refreshStateFromEngineLocked()
            } else {
                initNativeNode()
            }
        }
        startMesh()
    }

    // ---- Rust Engine Output & Event Folding ---------------------------------

    private fun emitEngineOutputLocked(blob: ByteArray?) {
        if (blob == null) return
        val parsed = CypherBridge.parseResponse(blob)
        bleLinkLayer.executeCommands(parsed.commands)
        if (parsed.eventsJson != "[]") {
            processEngineEventsJsonLocked(parsed.eventsJson)
        }
    }

    private fun onEngineEvents(eventsJson: String) {
        synchronized(coreLock) {
            processEngineEventsJsonLocked(eventsJson)
        }
    }

    private fun processEngineEventsJsonLocked(eventsJson: String) {
        val events = runCatching { JSONArray(eventsJson) }.getOrNull() ?: return
        var peersChanged = false

        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            val keys = event.keys()
            if (!keys.hasNext()) continue
            val variant = keys.next()
            val f = event.optJSONObject(variant) ?: JSONObject()

            when (variant) {
                "PublicMessageReceived" -> {
                    val senderHex = f.optString("sender").lowercase()
                    if (senderHex.isNotEmpty() && senderHex == _myPeerIdHex.value.lowercase()) {
                        continue
                    }
                    if (store.isBlocked(senderHex)) continue

                    val rawNick = f.optString("nickname")
                    val nick = rawNick.ifBlank { resolvePeerNickname(senderHex) }
                    if (senderHex.isNotEmpty() && rawNick.isNotBlank()) {
                        store.rememberPeerName(senderHex, rawNick)
                    }
                    val rawContent = f.optString("content")
                    val (cleanText, parsedReply) = decodeWireReply(rawContent)
                    val ts = f.optLong("timestamp_ms").takeIf { it > 0L } ?: System.currentTimeMillis()
                    val msgId = f.optString("message_id").ifBlank { "$senderHex-$ts" }

                    store.appendMessage(
                        ChatMessage(
                            id = msgId,
                            conversationKey = MeshStore.BROADCAST_CONVERSATION_KEY,
                            senderPeerHex = senderHex,
                            senderNickname = nick,
                            text = cleanText,
                            timestampMs = ts,
                            isOutgoing = false,
                            isPrivate = false,
                            replyTo = parsedReply,
                        ),
                        activeConversationKey = _activeConversationKey.value,
                    )

                    if (cleanText.contains("@${_nickname.value}", ignoreCase = true) &&
                        !store.mutedConversations.value.contains(MeshStore.BROADCAST_CONVERSATION_KEY)
                    ) {
                        maybePostNotification(
                            conversationKey = MeshStore.BROADCAST_CONVERSATION_KEY,
                            title = "$nick mentioned you in #mesh",
                            body = cleanText,
                        )
                    }
                }

                "NoisePayloadReceived" -> {
                    val peerHex = f.optString("peer").lowercase()
                    val payloadType = f.optInt("payload_type")
                    val payloadArr = f.optJSONArray("payload")
                    onNoisePayloadLocked(peerHex, payloadType, payloadArr)
                }

                "PeerConnected", "PeerDisconnected", "PeerListUpdated" -> {
                    peersChanged = true
                }

                "MessageDeliveryStatusUpdated" -> {
                    val msgId = f.optString("message_id")
                    val failed = f.optBoolean("failed", false)
                    val sending = f.optBoolean("sending", false)
                    if (msgId.isNotBlank()) {
                        when {
                            failed -> store.updateDeliveryState(msgId, DeliveryState.FAILED)
                            sending -> store.updateDeliveryState(msgId, DeliveryState.SENDING)
                            else -> store.updateDeliveryState(msgId, DeliveryState.SENT)
                        }
                    }
                }

                "SessionEstablished" -> {
                    val peerHex = f.optString("peer").ifBlank { f.optString("peerId") }.lowercase()
                    if (peerHex.isNotBlank()) {
                        appendSystemMessage(
                            conversationKey = peerHex,
                            text = "Noise XX end-to-end encrypted session established",
                        )
                    }
                    peersChanged = true
                }

                "SessionFailed" -> {
                    peersChanged = true
                }

                "FileTransferReceived" -> {
                    foldFileTransferLocked(f)
                }
            }
        }

        if (peersChanged) {
            refreshStateFromEngineLocked()
        }
    }

    /**
     * Private-message TLV: `0x00 len id · 0x01 len content` (byte-exact with
     * the Swift/Rust wire format).
     */
    private fun onNoisePayloadLocked(peerHex: String, payloadType: Int, payload: JSONArray?) {
        if (peerHex.isEmpty() || store.isBlocked(peerHex)) return
        val bytes = ByteArray(payload?.length() ?: 0)
        if (payload != null) {
            for (i in 0 until payload.length()) {
                bytes[i] = payload.optInt(i).toByte()
            }
        }
        when (payloadType) {
            0x01 -> {
                val decoded = decodePrivateMessage(bytes) ?: return
                val msgId = decoded.first
                val rawContent = decoded.second
                val (cleanContent, parsedReply) = decodeWireReply(rawContent)
                val nick = resolvePeerNickname(peerHex)

                store.appendMessage(
                    ChatMessage(
                        id = msgId.ifBlank { UUID.randomUUID().toString() },
                        conversationKey = peerHex,
                        senderPeerHex = peerHex,
                        senderNickname = nick,
                        text = cleanContent,
                        timestampMs = System.currentTimeMillis(),
                        isOutgoing = false,
                        isPrivate = true,
                        replyTo = parsedReply,
                    ),
                    activeConversationKey = _activeConversationKey.value,
                )

                if (msgId.isNotBlank() && nodeHandle != 0L) {
                    val ackOut = runCatching {
                        CypherBridge.nativeSendDeliveryAck(nodeHandle, peerHex, msgId)
                    }.getOrNull()
                    emitEngineOutputLocked(ackOut)
                    if (_activeConversationKey.value == peerHex) {
                        val readOut = runCatching {
                            CypherBridge.nativeSendReadReceipt(nodeHandle, peerHex, msgId)
                        }.getOrNull()
                        emitEngineOutputLocked(readOut)
                    }
                }

                if (!store.mutedConversations.value.contains(peerHex)) {
                    maybePostNotification(
                        conversationKey = peerHex,
                        title = nick,
                        body = cleanContent,
                    )
                }
            }

            0x02 -> {
                val messageId = decodeReceiptMessageId(bytes)
                if (messageId != null) {
                    store.updateDeliveryState(messageId, DeliveryState.READ)
                }
            }

            0x03 -> {
                val messageId = decodeReceiptMessageId(bytes)
                if (messageId != null) {
                    store.updateDeliveryState(messageId, DeliveryState.DELIVERED)
                }
            }
        }
    }

    private fun decodePrivateMessage(bytes: ByteArray): Pair<String, String>? {
        return try {
            var offset = 0
            var id: String? = null
            var content: String? = null
            while (offset + 2 <= bytes.size) {
                val tag = bytes[offset].toInt() and 0xFF
                val len = bytes[offset + 1].toInt() and 0xFF
                if (offset + 2 + len > bytes.size) break
                val body = bytes.copyOfRange(offset + 2, offset + 2 + len)
                when (tag) {
                    0x00 -> id = String(body, Charsets.UTF_8)
                    0x01 -> content = String(body, Charsets.UTF_8)
                }
                offset += 2 + len
            }
            if (id != null && content != null) id to content else null
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeReceiptMessageId(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        var end = bytes.size
        while (end > 0 && bytes[end - 1] == 0.toByte()) end--
        return String(bytes, 0, end, Charsets.UTF_8).takeIf { it.isNotEmpty() }
    }

    private fun foldFileTransferLocked(event: JSONObject) {
        val senderHex = event.optString("sender").lowercase()
        if (senderHex.isEmpty() || store.isBlocked(senderHex)) return
        val file = event.optJSONObject("file") ?: return
        val fileName = file.optString("file_name", "attachment")
        val mimeType = file.optString("mime_type", "application/octet-stream")
        val content = file.optJSONArray("content")?.let { arr ->
            ByteArray(arr.length()) { i -> arr.optInt(i).toByte() }
        } ?: return
        if (content.isEmpty()) return

        val kind = when {
            mimeType.startsWith("image/") -> AttachmentKind.IMAGE
            mimeType.startsWith("audio/") -> AttachmentKind.AUDIO
            else -> AttachmentKind.FILE
        }
        val msgId = UUID.randomUUID().toString()
        val attachment = store.saveIncomingMedia(msgId, kind, fileName, mimeType, content)
        val isPrivate = event.optBoolean("is_private", false)
        val convKey = if (isPrivate) senderHex else MeshStore.BROADCAST_CONVERSATION_KEY
        val nick = resolvePeerNickname(senderHex)

        store.appendMessage(
            ChatMessage(
                id = msgId,
                conversationKey = convKey,
                senderPeerHex = senderHex,
                senderNickname = nick,
                text = if (kind == AttachmentKind.AUDIO) "Voice note" else fileName,
                timestampMs = System.currentTimeMillis(),
                isOutgoing = false,
                isPrivate = isPrivate,
                attachment = attachment,
            ),
            activeConversationKey = _activeConversationKey.value,
        )

        if (isPrivate && !store.mutedConversations.value.contains(senderHex)) {
            maybePostNotification(
                conversationKey = senderHex,
                title = nick,
                body = if (kind == AttachmentKind.AUDIO) "Sent an encrypted voice note" else "Sent $fileName",
            )
        }
    }

    private fun refreshStateFromEngineLocked() {
        if (nodeHandle == 0L) return
        val blob = runCatching { CypherBridge.nativeStateJson(nodeHandle) }.getOrNull() ?: return
        val root = runCatching { JSONObject(String(blob, Charsets.UTF_8)) }.getOrNull() ?: return

        val myId = root.optString("peerId").lowercase()
        if (myId.isNotEmpty()) {
            _myPeerIdHex.value = myId
        }
        val myFp = root.optString("fingerprint").ifBlank {
            PeerIdentity.fingerprintHex(_myPeerIdHex.value)
        }
        _myFingerprintHex.value = myFp
        _myStaticPublicKeyHex.value = myFp

        val arr = root.optJSONArray("peers") ?: JSONArray()
        val favs = store.favorites.value
        val verifiedMap = store.verifiedPeers.value
        val blockedSet = store.blockedPeers.value
        val knownNames = store.knownPeerNames.value
        val latestRssi = bleLinkLayer.latestRssi.value

        val list = ArrayList<PeerUiModel>(arr.length())
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val peerHex = p.optString("peerId").lowercase()
            if (peerHex.isEmpty()) continue
            val rawNick = p.optString("nickname")
            val nick = rawNick.ifBlank { knownNames[peerHex] ?: "peer-${peerHex.take(4)}" }
            if (rawNick.isNotBlank() && rawNick != "anon") {
                store.rememberPeerName(peerHex, rawNick)
            }
            val connected = p.optBoolean("connected", false)
            val secureSession = p.optBoolean("secureSession", false)
            val noisePubKey = p.optString("noisePublicKey")
            val fp = noisePubKey.ifBlank { PeerIdentity.fingerprintHex(peerHex) }
            val isVerified = p.optBoolean("verified", false) || verifiedMap.containsKey(peerHex)
            val isFavorite = p.optBoolean("favorite", false) || favs.contains(peerHex)

            list.add(
                PeerUiModel(
                    peerIdHex = peerHex,
                    nickname = nick,
                    sessionState = if (secureSession) "established" else if (connected) "handshaking" else "none",
                    isKnown = true,
                    lastSeenMs = p.optLong("lastSeenMs", 0L),
                    rssi = if (connected) (latestRssi ?: -62) else null,
                    isDirectlyConnected = connected,
                    isFavorite = isFavorite,
                    isVerified = isVerified,
                    isBlocked = blockedSet.contains(peerHex),
                    fingerprintHex = fp,
                ),
            )
        }

        _peers.value = list.sortedWith(
            compareByDescending<PeerUiModel> { it.isFavorite }
                .thenByDescending { it.isDirectlyConnected }
                .thenByDescending { it.sessionState == "established" }
                .thenBy { it.nickname.lowercase() },
        )
    }

    private fun appendSystemMessage(conversationKey: String, text: String) {
        store.appendMessage(
            ChatMessage(
                id = "sys-${UUID.randomUUID()}",
                conversationKey = conversationKey,
                senderPeerHex = _myPeerIdHex.value,
                senderNickname = "system",
                text = text,
                timestampMs = System.currentTimeMillis(),
                isOutgoing = false,
                isPrivate = conversationKey != MeshStore.BROADCAST_CONVERSATION_KEY,
                isSystem = true,
            ),
            activeConversationKey = _activeConversationKey.value,
        )
    }

    private fun encodeWireReply(cleanText: String, replyTo: ReplyReference?): String {
        if (replyTo == null) return cleanText
        val singleLinePreview = replyTo.previewText
            .replace('\n', ' ')
            .trim()
            .take(80)
        val cleanSender = replyTo.senderNickname.replace(':', '_').trim().take(24)
        return "> @$cleanSender: $singleLinePreview\n$cleanText"
    }

    private fun decodeWireReply(rawText: String): Pair<String, ReplyReference?> {
        val match = WIRE_REPLY_REGEX.matchEntire(rawText) ?: return rawText to null
        val sender = match.groupValues[1].trim()
        val preview = match.groupValues[2].trim()
        val body = match.groupValues[3].trim()
        return body to ReplyReference(
            messageId = "",
            senderNickname = sender,
            previewText = preview,
        )
    }

    private fun formatDurationShort(durationMs: Long): String {
        val totalSec = (durationMs / 1000L).coerceAtLeast(1L)
        val mins = totalSec / 60L
        val secs = totalSec % 60L
        return "%d:%02d".format(mins, secs)
    }

    private fun queryFileName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()

    private fun ensureDmNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = appContext.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            DM_NOTIFICATION_CHANNEL,
            appContext.getString(R.string.dm_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = appContext.getString(R.string.dm_channel_desc)
        }
        nm.createNotificationChannel(channel)
    }

    private fun maybePostNotification(conversationKey: String, title: String, body: String) {
        if (_activeConversationKey.value == conversationKey) return
        val nm = appContext.getSystemService(NotificationManager::class.java) ?: return
        val openIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("conversation_key", conversationKey)
        }
        val pending = PendingIntent.getActivity(
            appContext,
            conversationKey.hashCode(),
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(appContext, DM_NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        runCatching {
            nm.notify(DM_NOTIFICATION_BASE_ID + (conversationKey.hashCode() and 0x3FF), notification)
        }
    }
}
