package chat.cypher.android.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import chat.cypher.android.MainActivity
import chat.cypher.android.MeshForegroundService
import chat.cypher.android.R
import chat.cypher.android.ble.BleLinkLayer
import chat.cypher.android.ui.theme.ThemePreference
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
 * Coordinates the Rust `CypherNode` (via [CypherBridge]), the Android BLE link layer
 * ([BleLinkLayer]), and persistent storage ([MeshStore]).
 */
class MeshController(private val appContext: Context) {
    companion object {
        private const val TAG = "MeshController"
        private const val TICK_INTERVAL_MS = 1_000L
        private const val MAX_ATTACHMENT_BYTES = 512 * 1024 // 512 KiB protocol limit
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

    private val addressToPeerHex = ConcurrentHashMap<String, String>()
    private val peerHexToRssi = ConcurrentHashMap<String, Int>()
    private val addressToRssi = ConcurrentHashMap<String, Int>()

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

    private val bleLinkLayer = BleLinkLayer(
        context = appContext,
        onFrameReceived = ::handleBleFrameReceived,
        onNeighborChanged = ::handleBleNeighborChanged,
    )

    val connectedBleAddresses: StateFlow<Set<String>> = bleLinkLayer.connectedAddresses

    init {
        ensureDmNotificationChannel()
        initNativeNode()
    }

    private fun initNativeNode() {
        if (!CypherBridge.isLibraryLoaded) {
            Log.e(TAG, "libcypher.so not loaded; running in UI-only fallback mode")
            return
        }
        synchronized(coreLock) {
            _coreVersion.value = runCatching { CypherBridge.nativeVersion() }.getOrDefault("0.1.0")
            val existingSeed = store.loadOrNullIdentitySeed()
            nodeHandle = CypherBridge.nativeCreateNode(existingSeed, _nickname.value)
            if (nodeHandle != 0L) {
                val seed = CypherBridge.nativeIdentitySeed(nodeHandle)
                if (seed.size == 32) {
                    store.persistIdentitySeed(seed)
                }
                val peerHex = CypherBridge.nativePeerIdHex(nodeHandle)
                val pubKeyBytes = CypherBridge.nativeStaticPublicKey(nodeHandle)
                val pubKeyHex = pubKeyBytes.joinToString("") { "%02x".format(it) }
                _myPeerIdHex.value = peerHex
                _myStaticPublicKeyHex.value = pubKeyHex
                _myFingerprintHex.value = PeerIdentity.fingerprintHex(peerHex, pubKeyHex)
            }
        }
    }

    fun startMesh() {
        val radioStarted = bleLinkLayer.start()
        _isMeshRunning.value = radioStarted
        synchronized(coreLock) {
            if (nodeHandle != 0L) {
                CypherBridge.nativeAnnounce(nodeHandle)
                _lastAnnounceTimeMs.value = System.currentTimeMillis()
                flushOutboundLocked()
            }
        }
        if (tickJob?.isActive != true) {
            tickJob = scope.launch {
                while (isActive) {
                    delay(TICK_INTERVAL_MS)
                    synchronized(coreLock) {
                        if (nodeHandle != 0L) {
                            CypherBridge.nativeTick(nodeHandle)
                            flushOutboundLocked()
                            refreshPeerListLocked()
                        }
                    }
                }
            }
        }
    }

    fun stopMesh() {
        tickJob?.cancel()
        tickJob = null
        bleLinkLayer.stop()
        _isMeshRunning.value = false
    }

    fun announceNow(): Boolean {
        return synchronized(coreLock) {
            if (nodeHandle != 0L) {
                CypherBridge.nativeAnnounce(nodeHandle)
                flushOutboundLocked()
                refreshPeerListLocked()
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
            MeshForegroundService.start(appContext)
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
                CypherBridge.nativeSetNickname(nodeHandle, clean)
                CypherBridge.nativeAnnounce(nodeHandle)
                _lastAnnounceTimeMs.value = System.currentTimeMillis()
                flushOutboundLocked()
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
                        for (msg in unreadIncoming) {
                            CypherBridge.nativeSendReadReceipt(nodeHandle, conversationKey, msg.id)
                        }
                        flushOutboundLocked()
                    }
                }
            }
        }
    }

    fun sendBroadcastText(text: String, replyTo: ReplyReference? = null) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val wireText = encodeWireReply(clean, replyTo)
        val msgId = synchronized(coreLock) {
            if (nodeHandle != 0L) {
                val id = CypherBridge.nativeSendBroadcastText(nodeHandle, wireText)
                flushOutboundLocked()
                id
            } else {
                ""
            }
        }.ifEmpty { UUID.randomUUID().toString() }

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
    }

    fun sendPrivateMessage(recipientHex: String, text: String, replyTo: ReplyReference? = null) {
        val clean = text.trim()
        val peerKey = recipientHex.lowercase()
        if (clean.isEmpty() || peerKey.isEmpty()) return
        val wireText = encodeWireReply(clean, replyTo)

        val msgId = synchronized(coreLock) {
            if (nodeHandle != 0L) {
                val id = CypherBridge.nativeSendPrivateMessage(nodeHandle, peerKey, wireText)
                flushOutboundLocked()
                refreshPeerListLocked()
                id
            } else {
                ""
            }
        }.ifEmpty { UUID.randomUUID().toString() }

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
    }

    fun sendMeshPing(peerHex: String): Boolean {
        val cleanHex = peerHex.lowercase().trim()
        if (cleanHex.isEmpty() || cleanHex == MeshStore.BROADCAST_CONVERSATION_KEY) return false
        val ok = synchronized(coreLock) {
            if (nodeHandle != 0L) {
                val queued = CypherBridge.nativeSendMeshPing(nodeHandle, cleanHex)
                flushOutboundLocked()
                queued
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

        val msgId = synchronized(coreLock) {
            if (nodeHandle != 0L) {
                val id = CypherBridge.nativeSendFileBytes(
                    nodeHandle,
                    normalizedRecipient,
                    AttachmentKind.AUDIO.wireCode,
                    audioFile.name,
                    "audio/mp4",
                    payload,
                )
                flushOutboundLocked()
                id
            } else {
                ""
            }
        }.ifEmpty { UUID.randomUUID().toString() }

        val savedAttachment = store.saveIncomingMedia(
            messageId = msgId,
            kind = AttachmentKind.AUDIO,
            fileName = audioFile.name,
            mimeType = "audio/mp4",
            bytes = payload,
        ).copy(durationMs = durationMs)

        store.appendMessage(
            ChatMessage(
                id = msgId,
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

        val msgId = synchronized(coreLock) {
            if (nodeHandle != 0L) {
                val id = CypherBridge.nativeSendFileBytes(
                    nodeHandle,
                    normalizedRecipient,
                    kind.wireCode,
                    fileName,
                    mime,
                    payload,
                )
                flushOutboundLocked()
                id
            } else {
                ""
            }
        }.ifEmpty { UUID.randomUUID().toString() }

        val savedAttachment = store.saveIncomingMedia(msgId, kind, fileName, mime, payload)

        store.appendMessage(
            ChatMessage(
                id = msgId,
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
        store.toggleFavorite(peerHex)
        synchronized(coreLock) { refreshPeerListLocked() }
    }

    fun markPeerVerified(peerHex: String) {
        val peer = _peers.value.firstOrNull { it.peerIdHex.equals(peerHex, ignoreCase = true) }
        val fp = peer?.fingerprintHex?.ifBlank { PeerIdentity.fingerprintHex(peerHex) }
            ?: PeerIdentity.fingerprintHex(peerHex)
        store.markPeerVerified(peerHex, fp)
        appendSystemMessage(
            conversationKey = peerHex.lowercase(),
            text = "Marked safety number as verified (${fp.take(12).uppercase()}…)",
        )
        synchronized(coreLock) { refreshPeerListLocked() }
    }

    fun unverifyPeer(peerHex: String) {
        store.unverifyPeer(peerHex)
        appendSystemMessage(
            conversationKey = peerHex.lowercase(),
            text = "Cleared verified status for this peer",
        )
        synchronized(coreLock) { refreshPeerListLocked() }
    }

    fun toggleBlocked(peerHex: String) {
        val nowBlocked = store.toggleBlocked(peerHex)
        appendSystemMessage(
            conversationKey = peerHex.lowercase(),
            text = if (nowBlocked) "Peer blocked — incoming traffic muted" else "Peer unblocked",
        )
        synchronized(coreLock) { refreshPeerListLocked() }
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
        synchronized(coreLock) {
            if (nodeHandle != 0L) {
                CypherBridge.nativePanicWipe(nodeHandle)
                CypherBridge.nativeDestroyNode(nodeHandle)
                nodeHandle = 0L
            }
        }
        store.wipeAll()
        addressToPeerHex.clear()
        peerHexToRssi.clear()
        addressToRssi.clear()
        _peers.value = emptyList()
        _nickname.value = store.loadNickname()
        initNativeNode()
        startMesh()
    }

    private fun handleBleFrameReceived(address: String, bytes: ByteArray) {
        synchronized(coreLock) {
            if (nodeHandle == 0L) return
            val knownPeer = addressToPeerHex[address]
            val eventsJson = CypherBridge.nativeIngestLinkFrame(nodeHandle, knownPeer, bytes)
            flushOutboundLocked()
            for (raw in eventsJson) {
                processCoreEventLocked(address, raw)
            }
            refreshPeerListLocked()
        }
    }

    private fun handleBleNeighborChanged(address: String, connected: Boolean, rssi: Int?) {
        if (rssi != null) {
            addressToRssi[address] = rssi
            addressToPeerHex[address]?.let { peerHex ->
                peerHexToRssi[peerHex] = rssi
            }
        }
        if (connected) {
            synchronized(coreLock) {
                if (nodeHandle != 0L) {
                    CypherBridge.nativeAnnounce(nodeHandle)
                    flushOutboundLocked()
                    refreshPeerListLocked()
                }
            }
        } else {
            synchronized(coreLock) {
                if (nodeHandle != 0L) {
                    refreshPeerListLocked()
                }
            }
        }
    }

    private fun processCoreEventLocked(sourceAddress: String, rawJson: String) {
        val obj = runCatching { JSONObject(rawJson) }.getOrNull() ?: return
        when (obj.optString("type")) {
            "peer_announced" -> {
                val peerHex = obj.optString("peerId").lowercase()
                val nick = obj.optString("nickname").ifBlank { "peer-${peerHex.take(4)}" }
                if (peerHex.isNotEmpty()) {
                    addressToPeerHex[sourceAddress] = peerHex
                    addressToRssi[sourceAddress]?.let { peerHexToRssi[peerHex] = it }
                    store.rememberPeerName(peerHex, nick)
                }
            }

            "broadcast_text" -> {
                val senderHex = obj.optString("sender").lowercase()
                if (store.isBlocked(senderHex)) return
                val nick = obj.optString("senderNickname").ifBlank {
                    resolvePeerNickname(senderHex)
                }
                store.rememberPeerName(senderHex, nick)
                val rawText = obj.optString("text")
                val (cleanText, parsedReply) = decodeWireReply(rawText)
                val ts = obj.optLong("timestampMs").takeIf { it > 0L } ?: System.currentTimeMillis()
                val id = obj.optString("messageId").ifBlank { "$senderHex-$ts" }

                store.appendMessage(
                    ChatMessage(
                        id = id,
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

            "private_message" -> {
                val senderHex = obj.optString("sender").lowercase()
                if (store.isBlocked(senderHex)) return
                val msgId = obj.optString("messageId")
                val rawContent = obj.optString("content")
                val (cleanContent, parsedReply) = decodeWireReply(rawContent)
                val ts = obj.optLong("timestampMs").takeIf { it > 0L } ?: System.currentTimeMillis()
                val nick = resolvePeerNickname(senderHex)

                store.appendMessage(
                    ChatMessage(
                        id = msgId.ifBlank { UUID.randomUUID().toString() },
                        conversationKey = senderHex,
                        senderPeerHex = senderHex,
                        senderNickname = nick,
                        text = cleanContent,
                        timestampMs = ts,
                        isOutgoing = false,
                        isPrivate = true,
                        replyTo = parsedReply,
                    ),
                    activeConversationKey = _activeConversationKey.value,
                )

                if (msgId.isNotBlank() && nodeHandle != 0L) {
                    CypherBridge.nativeSendDeliveryAck(nodeHandle, senderHex, msgId)
                    if (_activeConversationKey.value == senderHex) {
                        CypherBridge.nativeSendReadReceipt(nodeHandle, senderHex, msgId)
                    }
                    flushOutboundLocked()
                }
                if (!store.mutedConversations.value.contains(senderHex)) {
                    maybePostNotification(
                        conversationKey = senderHex,
                        title = nick,
                        body = cleanContent,
                    )
                }
            }

            "delivery_ack" -> {
                val msgId = obj.optString("messageId")
                if (msgId.isNotBlank()) {
                    store.updateDeliveryState(msgId, DeliveryState.DELIVERED)
                }
            }

            "read_receipt" -> {
                val msgId = obj.optString("messageId")
                if (msgId.isNotBlank()) {
                    store.updateDeliveryState(msgId, DeliveryState.READ)
                }
            }

            "session_established" -> {
                val peerHex = obj.optString("peerId").lowercase()
                if (peerHex.isNotBlank()) {
                    appendSystemMessage(
                        conversationKey = peerHex,
                        text = "Noise XX end-to-end encrypted session established",
                    )
                }
            }

            "file_received" -> {
                val senderHex = obj.optString("sender").lowercase()
                if (store.isBlocked(senderHex)) return
                val isPrivate = obj.optBoolean("isPrivate", false)
                val msgId = obj.optString("messageId").ifBlank { UUID.randomUUID().toString() }
                val kind = AttachmentKind.fromWire(obj.optInt("kind", 2))
                val fileName = obj.optString("fileName", "attachment")
                val mimeType = obj.optString("mimeType", "application/octet-stream")
                val bytes = runCatching {
                    Base64.decode(obj.optString("bytesB64", ""), Base64.NO_WRAP)
                }.getOrDefault(ByteArray(0))
                if (bytes.isEmpty()) return

                val attachment = store.saveIncomingMedia(msgId, kind, fileName, mimeType, bytes)
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
            }
        }
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

    private fun flushOutboundLocked() {
        if (nodeHandle == 0L) return
        val frames = CypherBridge.nativeDrainOutbound(nodeHandle)
        for (frame in frames) {
            bleLinkLayer.broadcastFrame(frame)
        }
    }

    private fun refreshPeerListLocked() {
        if (nodeHandle == 0L) return
        val raw = CypherBridge.nativePeersJson(nodeHandle)
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return
        val connectedAddrs = bleLinkLayer.connectedAddresses.value
        val connectedPeers = connectedAddrs.mapNotNull { addressToPeerHex[it] }.toSet()
        val favs = store.favorites.value
        val verified = store.verifiedPeers.value
        val blocked = store.blockedPeers.value
        val knownNames = store.knownPeerNames.value

        val list = ArrayList<PeerUiModel>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val peerHex = item.optString("peerId").lowercase()
            if (peerHex.isEmpty()) continue
            val rawNick = item.optString("nickname")
            val nick = rawNick.ifBlank { knownNames[peerHex] ?: "peer-${peerHex.take(4)}" }
            if (rawNick.isNotBlank()) {
                store.rememberPeerName(peerHex, rawNick)
            }
            val fp = PeerIdentity.fingerprintHex(peerHex)
            list.add(
                PeerUiModel(
                    peerIdHex = peerHex,
                    nickname = nick,
                    sessionState = item.optString("sessionState", "none"),
                    isKnown = item.optBoolean("isKnown", true),
                    lastSeenMs = item.optLong("lastSeenMs", 0L),
                    rssi = peerHexToRssi[peerHex],
                    isDirectlyConnected = connectedPeers.contains(peerHex),
                    isFavorite = favs.contains(peerHex),
                    isVerified = verified[peerHex] == fp,
                    isBlocked = blocked.contains(peerHex),
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
