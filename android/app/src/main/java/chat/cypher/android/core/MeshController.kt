package chat.cypher.android.core

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import chat.cypher.android.MainActivity
import chat.cypher.android.ble.MeshEvents
import chat.cypher.android.ui.theme.ThemePreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The UI's single source of truth: folds engine events into Compose state,
 * persists history, and exposes every send path. The Rust engine owns the
 * wire; this owns the experience.
 */
object MeshController {

    // ---- UI state ---------------------------------------------------------------

    data class UiPeer(
        val peerId: String,
        val nickname: String,
        val connected: Boolean,
        val verified: Boolean,
        val favorite: Boolean,
        val secureSession: Boolean,
        val lastSeenMs: Long,
        val noisePublicKeyHex: String,
        val blocked: Boolean,
    )

    enum class Kind { TEXT, IMAGE, FILE, VOICE, SYSTEM }

    data class UiMessage(
        val id: String,
        val senderPeerId: String,
        val senderNickname: String,
        val content: String,
        val kind: Kind,
        val timestampMs: Long,
        val mine: Boolean,
        val peerId: String, // "mesh" or the DM peer id
        val status: String, // sending | sent | delivered | read | failed
        val fileName: String? = null,
        val mimeType: String? = null,
        val localPath: String? = null,
    )

    var engineReady by mutableStateOf(false)
        private set
    var myPeerId by mutableStateOf("")
        private set
    var myFingerprint by mutableStateOf("")
        private set
    var myNickname by mutableStateOf("")
        private set
    var peers by mutableStateOf<List<UiPeer>>(emptyList())
        private set
    var meshMessages by mutableStateOf<List<UiMessage>>(emptyList())
        private set
    var conversations by mutableStateOf<Map<String, List<UiMessage>>>(emptyMap())
        private set
    var unreadPeers by mutableStateOf<Set<String>>(emptySet())
        private set
    var bluetoothOn by mutableStateOf(true)
        private set
    var appInForeground by mutableStateOf(false)

    /** The currently open conversation ("mesh" or a DM peer id). */
    var openConversation by mutableStateOf<String?>(null)

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var appContext: Context

    /** True when [peerId] is blocked (mirrors the persisted blocklist). */
    fun isBlocked(peerId: String): Boolean = MeshStore.blocked().contains(peerId)

    // ---- lifecycle ---------------------------------------------------------------

    fun init(context: Context) {
        if (this::appContext.isInitialized) return
        appContext = context.applicationContext
        MeshStore.init(appContext)
        createNotificationChannels()

        myNickname = MeshStore.nickname()

        MeshEvents.subscribe { json -> mainHandler.post { foldEvents(json) } }

        // Engine comes up with the runtime; fold state once it exists.
        mainHandler.post {
            refreshIdentity()
            loadPersistedConversations()
            applyPersistedPeerFlags()
        }
    }

    fun onEngineStarted() {
        engineReady = true
        refreshIdentity()
        applyPersistedPeerFlags()
    }

    private fun loadPersistedConversations() {
        meshMessages = MeshStore.loadConversation("mesh").map { it.toUi() }
        val map = mutableMapOf<String, List<UiMessage>>()
        for (peerId in MeshStore.conversationPeerIds()) {
            map[peerId] = MeshStore.loadConversation(peerId).map { it.toUi() }
        }
        conversations = map
        unreadPeers = MeshStore.conversationPeerIds()
            .filter { it !in MeshStore.readPeers() }
            .filter { peerId -> conversations[peerId]?.any { !it.mine } == true }
            .toSet()
    }

    private fun applyPersistedPeerFlags() {
        val handle = MeshRuntime.engineHandle() ?: return
        for (peerId in MeshStore.favorites()) {
            CypherBridge.nativeSetPeerFavorite(handle, peerId, true)
        }
        for (peerId in MeshStore.blocked()) {
            CypherBridge.nativeBlockPeer(handle, peerId)
        }
    }

    // ---- state refresh -----------------------------------------------------------

    fun refreshIdentity() {
        val handle = MeshRuntime.engineHandle() ?: return
        val blob = CypherBridge.nativeStateJson(handle) ?: return
        val json = runCatching { JSONObject(String(blob, Charsets.UTF_8)) }.getOrNull() ?: return
        myPeerId = json.optString("peerId")
        myFingerprint = json.optString("fingerprint")
        myNickname = json.optString("nickname", MeshStore.nickname())
        val blocked = json.optJSONArray("blocked")?.let { array ->
            (0 until array.length()).map { array.optString(it) }.toSet()
        } ?: emptySet()
        peers = json.optJSONArray("peers")?.let { array ->
            (0 until array.length()).mapNotNull { i ->
                val p = array.optJSONObject(i) ?: return@mapNotNull null
                UiPeer(
                    peerId = p.optString("peerId"),
                    nickname = p.optString("nickname").ifBlank { p.optString("peerId") },
                    connected = p.optBoolean("connected"),
                    verified = p.optBoolean("verified"),
                    favorite = p.optBoolean("favorite"),
                    secureSession = p.optBoolean("secureSession"),
                    lastSeenMs = p.optLong("lastSeenMs"),
                    noisePublicKeyHex = p.optString("noisePublicKey"),
                    blocked = blocked.contains(p.optString("peerId")),
                )
            }
        } ?: emptyList()
    }

    // ---- event folding -------------------------------------------------------------

    private fun foldEvents(json: String) {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return
        var peersChanged = false
        var identityChanged = false
        for (i in 0 until array.length()) {
            val event = array.optJSONObject(i) ?: continue
            val type = event.keys().next()
            when (type) {
                "PublicMessageReceived" -> {
                    val e = event.optJSONObject(type) ?: continue
                    appendMessage(
                        UiMessage(
                            id = e.optString("message_id"),
                            senderPeerId = e.optString("sender"),
                            senderNickname = e.optString("nickname").ifBlank { "peer" },
                            content = e.optString("content"),
                            kind = Kind.TEXT,
                            timestampMs = e.optLong("timestamp_ms"),
                            mine = false,
                            peerId = "mesh",
                            status = "sent",
                        ),
                    )
                }

                "NoisePayloadReceived" -> {
                    val e = event.optJSONObject(type) ?: continue
                    val peer = e.optString("peer")
                    val payloadType = e.optInt("payload_type")
                    val payload = e.optJSONArray("payload")?.toBytes() ?: continue
                    when (payloadType) {
                        0x01 -> foldPrivateMessage(peer, payload)
                        0x02 -> foldReadReceipt(peer, payload)
                        0x03 -> foldDeliveryAck(peer, payload)
                        else -> { /* group/verify/vouch handled in later revisions */ }
                    }
                }

                "CourierEnvelopeOpened" -> {
                    val e = event.optJSONObject(type) ?: continue
                    val message = e.optJSONObject("message") ?: continue
                    // Courier mail arrives without a known sender transport
                    // alias; attribute by content until identity sync.
                    appendMessage(
                        UiMessage(
                            id = message.optString("message_id"),
                            senderPeerId = "courier",
                            senderNickname = "Courier mail",
                            content = message.optString("content"),
                            kind = Kind.TEXT,
                            timestampMs = System.currentTimeMillis(),
                            mine = false,
                            peerId = "courier",
                            status = "sent",
                        ),
                    )
                }

                "FileTransferReceived" -> {
                    val e = event.optJSONObject(type) ?: continue
                    foldFileTransfer(e)
                }

                "MessageDeliveryStatusUpdated" -> {
                    val e = event.optJSONObject(type) ?: continue
                    val id = e.optString("message_id")
                    val status = when {
                        e.optBoolean("failed") -> "failed"
                        e.optBoolean("sending") -> "sent"
                        else -> "sent"
                    }
                    updateMessageStatus(id, status)
                }

                "PeerConnected", "PeerDisconnected", "SessionEstablished",
                "SessionRestored", "SessionFailed", "PeerListUpdated",
                -> peersChanged = true

                "BluetoothStateChanged" -> {
                    bluetoothOn = event.optString(type) == "poweredOn"
                }
            }
        }
        if (peersChanged || identityChanged) refreshIdentity()
    }

    private fun foldPrivateMessage(peer: String, payload: ByteArray) {
        // TLV: [0x00][len][id] [0x01][len][content]
        var messageId = ""
        var content = ""
        var offset = 0
        while (offset + 1 < payload.size) {
            val tag = payload[offset].toInt() and 0xFF
            val length = payload[offset + 1].toInt() and 0xFF
            offset += 2
            if (offset + length > payload.size) break
            val field = String(payload, offset, length, Charsets.UTF_8)
            when (tag) {
                0x00 -> messageId = field
                0x01 -> content = field
            }
            offset += length
        }
        if (content.isBlank()) return

        val sender = peers.firstOrNull { it.peerId == peer }
        appendMessage(
            UiMessage(
                id = messageId.ifBlank { UUID.randomUUID().toString() },
                senderPeerId = peer,
                senderNickname = sender?.nickname ?: peer,
                content = content,
                kind = Kind.TEXT,
                timestampMs = System.currentTimeMillis(),
                mine = false,
                peerId = peer,
                status = "sent",
            ),
        )

        val handle = MeshRuntime.engineHandle()
        if (handle != null) {
            // Delivery ACK for everything we receive (bitchat parity).
            CypherBridge.nativeSendDeliveryAck(handle, peer, messageId)
            // Read receipt fires when the conversation is actually visible.
            if (openConversation == peer && appInForeground) {
                CypherBridge.nativeSendReadReceipt(handle, peer, messageId)
                MeshStore.markPeerRead(peer)
                unreadPeers = unreadPeers - peer
            } else {
                unreadPeers = unreadPeers + peer
                notifyDm(peer, sender?.nickname ?: peer, content)
            }
        }
    }

    private fun foldReadReceipt(peer: String, payload: ByteArray) {
        val messageId = String(payload, Charsets.UTF_8)
        updateMessageStatus(messageId, "read")
    }

    private fun foldDeliveryAck(peer: String, payload: ByteArray) {
        val messageId = String(payload, Charsets.UTF_8)
        updateMessageStatus(messageId, "delivered")
    }

    private fun foldFileTransfer(event: JSONObject) {
        val sender = event.optString("sender")
        val file = event.optJSONObject("file") ?: return
        val name = file.optString("file_name")
        val mime = file.optString("mime_type")
        val content = file.optJSONArray("content")?.toBytes() ?: return

        val incomingDir = File(appContext.filesDir, "incoming").apply { mkdirs() }
        val safeName = name.replace(Regex("[/\\\\]"), "_")
        val target = File(incomingDir, "${System.currentTimeMillis()}_$safeName")
        runCatching { target.writeBytes(content) }

        val kind = when {
            mime.startsWith("image/") -> Kind.IMAGE
            mime.startsWith("audio/") -> Kind.VOICE
            else -> Kind.FILE
        }
        val senderPeer = peers.firstOrNull { it.peerId == sender }
        val isPrivate = event.optBoolean("is_private")
        val conversation = if (isPrivate) sender else "mesh"
        appendMessage(
            UiMessage(
                id = UUID.randomUUID().toString(),
                senderPeerId = sender,
                senderNickname = senderPeer?.nickname ?: sender,
                content = name,
                kind = kind,
                timestampMs = System.currentTimeMillis(),
                mine = false,
                peerId = conversation,
                status = "sent",
                fileName = name,
                mimeType = mime,
                localPath = target.absolutePath,
            ),
        )
        if (isPrivate && (kind == Kind.IMAGE || kind == Kind.FILE || kind == Kind.VOICE)) {
            notifyDm(sender, senderPeer?.nickname ?: sender, when (kind) {
                Kind.IMAGE -> "📷 sent an image"
                Kind.VOICE -> "🎤 sent a voice message"
                else -> "📎 sent a file"
            })
        }
    }

    private fun appendMessage(message: UiMessage) {
        if (message.peerId == "mesh") {
            meshMessages = (meshMessages + message).takeLast(500)
        } else {
            val current = conversations[message.peerId] ?: emptyList()
            conversations = conversations + (message.peerId to (current + message).takeLast(500))
        }
        MeshStore.append(message.toStored())
    }

    private fun updateMessageStatus(messageId: String, status: String) {
        fun rewrite(list: List<UiMessage>): List<UiMessage> =
            if (list.any { it.id == messageId && it.mine }) {
                list.map { if (it.id == messageId && it.mine) it.copy(status = status) else it }
            } else {
                list
            }

        meshMessages = rewrite(meshMessages)
        val updated = conversations.mapValues { (_, list) -> rewrite(list) }
        if (updated != conversations) conversations = updated
        // Persist (find the conversation that holds the message).
        for ((peerId, list) in conversations) {
            if (list.any { it.id == messageId }) {
                MeshStore.updateStatus(peerId, messageId, status)
            }
        }
    }

    // ---- send paths -------------------------------------------------------------

    fun sendPublic(text: String) {
        val handle = MeshRuntime.engineHandle() ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        CypherBridge.nativeSendPublicMessage(handle, trimmed)
        appendMessage(
            UiMessage(
                id = UUID.randomUUID().toString(),
                senderPeerId = myPeerId,
                senderNickname = myNickname,
                content = trimmed,
                kind = Kind.TEXT,
                timestampMs = System.currentTimeMillis(),
                mine = true,
                peerId = "mesh",
                status = "sent",
            ),
        )
    }

    fun sendPrivate(peerId: String, text: String) {
        val handle = MeshRuntime.engineHandle() ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val messageId = UUID.randomUUID().toString()
        CypherBridge.nativeSendPrivateMessage(handle, peerId, trimmed, messageId)
        appendMessage(
            UiMessage(
                id = messageId,
                senderPeerId = myPeerId,
                senderNickname = myNickname,
                content = trimmed,
                kind = Kind.TEXT,
                timestampMs = System.currentTimeMillis(),
                mine = true,
                peerId = peerId,
                status = "sending",
            ),
        )
    }

    /** Send a media file into a conversation (DM peer, or null for mesh broadcast). */
    fun sendMedia(peerId: String?, fileName: String, mimeType: String, bytes: ByteArray): String {
        val handle = MeshRuntime.engineHandle() ?: error("engine not running")
        val transferId = UUID.randomUUID().toString()
        val kind = when {
            mimeType.startsWith("image/") -> Kind.IMAGE
            mimeType.startsWith("audio/") -> Kind.VOICE
            else -> Kind.FILE
        }
        if (peerId == null) {
            CypherBridge.nativeSendFileBroadcast(handle, fileName, mimeType, bytes)
        } else {
            CypherBridge.nativeSendFilePrivate(handle, peerId, fileName, mimeType, bytes, transferId)
        }
        val conversation = peerId ?: "mesh"
        appendMessage(
            UiMessage(
                id = transferId,
                senderPeerId = myPeerId,
                senderNickname = myNickname,
                content = fileName,
                kind = kind,
                timestampMs = System.currentTimeMillis(),
                mine = true,
                peerId = conversation,
                status = "sending",
                fileName = fileName,
                mimeType = mimeType,
            ),
        )
        return transferId
    }

    fun setNickname(nickname: String) {
        val trimmed = nickname.trim().take(15)
        if (trimmed.isEmpty()) return
        val handle = MeshRuntime.engineHandle()
        if (handle != null) {
            CypherBridge.nativeSetNickname(handle, trimmed)
            refreshIdentity()
        }
        MeshStore.setNickname(trimmed)
        myNickname = trimmed
    }

    fun setFavorite(peerId: String, favorite: Boolean) {
        val handle = MeshRuntime.engineHandle()
        if (handle != null) CypherBridge.nativeSetPeerFavorite(handle, peerId, favorite)
        MeshStore.setFavorite(peerId, favorite)
        refreshIdentity()
    }

    fun setVerified(peerId: String, verified: Boolean) {
        val handle = MeshRuntime.engineHandle()
        if (handle != null) CypherBridge.nativeSetPeerVerified(handle, peerId, verified)
        refreshIdentity()
    }

    fun block(peerId: String) {
        val handle = MeshRuntime.engineHandle()
        if (handle != null) CypherBridge.nativeBlockPeer(handle, peerId)
        MeshStore.setBlocked(peerId, true)
        refreshIdentity()
    }

    fun unblock(peerId: String) {
        val handle = MeshRuntime.engineHandle()
        if (handle != null) CypherBridge.nativeUnblockPeer(handle, peerId)
        MeshStore.setBlocked(peerId, false)
        refreshIdentity()
    }

    fun markConversationRead(peerId: String) {
        MeshStore.markPeerRead(peerId)
        unreadPeers = unreadPeers - peerId
        // Flush read receipts for unread messages now visible.
        val handle = MeshRuntime.engineHandle() ?: return
        val list = conversations[peerId] ?: return
        for (message in list) {
            if (!message.mine && message.status == "sent") {
                CypherBridge.nativeSendReadReceipt(handle, peerId, message.id)
            }
        }
    }

    /** Panic wipe: destroy identity, history, and preferences (bitchat parity). */
    fun panicWipe(onWiped: () -> Unit) {
        val handle = MeshRuntime.engineHandle()
        if (handle != null) {
            runCatching { CypherBridge.nativeStopServices(handle) }
        }
        MeshRuntime.stop()
        val identityFile = File(appContext.filesDir, "identity.json")
        identityFile.delete()
        File(appContext.filesDir, "incoming").deleteRecursively()
        MeshStore.wipeAll()
        conversations = emptyMap()
        meshMessages = emptyList()
        unreadPeers = emptySet()
        peers = emptyList()
        myPeerId = ""
        myFingerprint = ""
        myNickname = MeshStore.nickname() // fresh anon####
        // Fresh identity on next engine start, then re-arm the radio so the
        // new engine receives its RadioStateChanged(PoweredOn) and scans.
        MeshRuntime.start(appContext, myNickname)
        chat.cypher.android.ble.BleLinkLayer.active?.pushRadioState(5L)
        onEngineStarted()
        onWiped()
    }

    // ---- notifications -----------------------------------------------------------

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DM,
                "Direct Messages",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Private messages from other Cypher users" },
        )
    }

    private fun notifyDm(peerId: String, nickname: String, preview: String) {
        if (appInForeground && openConversation == peerId) return
        val intent = PendingIntent.getActivity(
            appContext,
            peerId.hashCode(),
            Intent(appContext, MainActivity::class.java)
                .putExtra("open_peer", peerId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_DM)
            .setSmallIcon(chat.cypher.android.R.drawable.ic_notification)
            .setContentTitle(nickname)
            .setContentText(preview)
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build()
        val manager = appContext.getSystemService(NotificationManager::class.java)
        runCatching { manager.notify(peerId.hashCode(), notification) }
    }

    // ---- conversions ---------------------------------------------------------------

    private fun MeshStore.StoredMessage.toUi() = UiMessage(
        id = id,
        senderPeerId = senderPeerId,
        senderNickname = senderNickname,
        content = content,
        kind = when (kind) {
            "image" -> Kind.IMAGE
            "file" -> Kind.FILE
            "voice" -> Kind.VOICE
            "system" -> Kind.SYSTEM
            else -> Kind.TEXT
        },
        timestampMs = timestampMs,
        mine = mine,
        peerId = peerId,
        status = status,
        fileName = fileName,
        mimeType = mimeType,
        localPath = localPath,
    )

    private fun UiMessage.toStored() = MeshStore.StoredMessage(
        id = id,
        senderPeerId = senderPeerId,
        senderNickname = senderNickname,
        content = content,
        kind = when (kind) {
            Kind.IMAGE -> "image"
            Kind.FILE -> "file"
            Kind.VOICE -> "voice"
            Kind.SYSTEM -> "system"
            Kind.TEXT -> "text"
        },
        timestampMs = timestampMs,
        mine = mine,
        peerId = peerId,
        status = status,
        fileName = fileName,
        mimeType = mimeType,
        localPath = localPath,
    )

    private fun JSONArray.toBytes(): ByteArray = ByteArray(length()) { i ->
        (optInt(i) and 0xFF).toByte()
    }

    private const val CHANNEL_DM = "cypher_dm"
}
