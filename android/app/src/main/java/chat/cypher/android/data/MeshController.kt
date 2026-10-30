package chat.cypher.android.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import chat.cypher.android.ble.MeshEvents
import chat.cypher.android.core.MeshRuntime
import chat.cypher.android.notify.Notifier
import chat.cypher.android.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Process-wide mesh façade for the UI: folds engine events into observable
 * state, provides the send/actions API, persists history, and posts DM
 * notifications (parity with bitchat's ChatViewModel + AppStateStore).
 */
class MeshController private constructor(context: Context) {

    companion object {
        private const val TAG = "MeshController"
        const val MESH_CONVERSATION = "mesh"
        private const val MAX_MESSAGES_PER_CONVERSATION = 500
        private const val MAX_TOTAL = 5000
        private const val PERSIST_DEBOUNCE_MS = 1500L

        @Volatile
        private var instance: MeshController? = null

        fun get(context: Context): MeshController =
            instance ?: synchronized(this) {
                instance ?: MeshController(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val history = SecureHistoryStore(appContext)
    private val notifier = Notifier(appContext)
    private var unsubscribe: (() -> Unit)? = null

    // ---- observable state (Compose snapshot; mutated on main thread) --------

    var messages by mutableStateOf<Map<String, List<CypherMessage>>>(emptyMap())
        private set
    var conversations by mutableStateOf<List<Conversation>>(emptyList())
        private set
    var peers by mutableStateOf<List<PeerUi>>(emptyList())
        private set
    var identity by mutableStateOf(MyIdentity("", "", ""))
        private set
    var joinedChannels by mutableStateOf<List<String>>(emptyList())
        private set
    var focusedConversation by mutableStateOf<String?>(null)
    var foregrounded by mutableStateOf(true)

    /** Unread counts by conversation id. */
    private var unread = mutableMapOf<String, Int>()
    private var drafts = mutableMapOf<String, String>()
    private var peerMeta = mutableMapOf<String, PeerUi>() // stable peer metadata
    private var persistScheduled = false

    // ---- lifecycle ------------------------------------------------------------

    fun start() {
        refreshIdentity()
        loadHistory()
        unsubscribe?.invoke()
        unsubscribe = MeshEvents.subscribe(::onEngineEvents)
    }

    fun stop() {
        unsubscribe?.invoke()
        unsubscribe = null
    }

    private fun handle(): Long? = MeshRuntime.engineHandle()

    // ---- identity / state -----------------------------------------------------

    fun refreshIdentity() {
        val h = handle() ?: return
        try {
            val blob = chat.cypher.android.core.CypherBridge.nativeStateJson(h) ?: return
            val root = JSONObject(String(blob, Charsets.UTF_8))
            identity = MyIdentity(
                peerId = root.optString("peerId"),
                nickname = root.optString("nickname"),
                fingerprint = root.optString("fingerprint"),
            )
            val peerList = ArrayList<PeerUi>()
            val arr = root.optJSONArray("peers") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val id = p.optString("peerId")
                val existing = peerMeta[id]
                val peer = PeerUi(
                    peerId = id,
                    nickname = p.optString("nickname").ifEmpty { "anon" },
                    connected = p.optBoolean("connected"),
                    secureSession = p.optBoolean("secureSession"),
                    verified = p.optBoolean("verified"),
                    favorite = p.optBoolean("favorite"),
                    lastSeenMs = p.optLong("lastSeenMs"),
                    suffix = id.takeLast(4),
                    fingerprint = p.optString("noisePublicKey"),
                )
                peerMeta[id] = peer
                peerList.add(peer)
            }
            peers = peerList.sortedWith(
                compareByDescending<PeerUi> { it.connected }.thenBy { it.nickname.lowercase() }
            )
            rebuildConversations()
        } catch (e: Exception) {
            Log.e(TAG, "state refresh failed", e)
        }
    }

    fun setNickname(nickname: String, onDone: () -> Unit = {}) {
        val h = handle() ?: return
        try {
            val out = chat.cypher.android.core.CypherBridge.nativeSetNickname(h, nickname)
            emitOutput(out)
            refreshIdentity()
        } catch (e: Exception) {
            Log.e(TAG, "setNickname failed", e)
        } finally {
            onDone()
        }
    }

    // ---- sending ----------------------------------------------------------------

    fun sendMessage(conversationId: String, rawContent: String): Boolean {
        val content = rawContent.trim()
        if (content.isEmpty()) return false
        val h = handle() ?: return false

        // Slash commands (bitchat CommandProcessor parity).
        if (content.startsWith("/")) {
            return handleCommand(conversationId, content)
        }

        return when {
            conversationId == MESH_CONVERSATION -> {
                val echo = newOwnMessage(MESH_CONVERSATION, content)
                appendMessage(echo, markRead = true)
                emitOutput(chat.cypher.android.core.CypherBridge.nativeSendPublicMessage(h, content))
                true
            }
            conversationId.startsWith("#") -> {
                // Channel messages ship as signed broadcasts (bitchat-android
                // parity); the channel label is local.
                val echo = newOwnMessage(conversationId, content)
                appendMessage(echo, markRead = true)
                emitOutput(chat.cypher.android.core.CypherBridge.nativeSendPublicMessage(h, content))
                true
            }
            conversationId.startsWith("dm:") -> {
                val peerId = conversationId.removePrefix("dm:")
                val echo = newOwnMessage(conversationId, content)
                appendMessage(echo, markRead = true)
                emitOutput(
                    chat.cypher.android.core.CypherBridge.nativeSendPrivateMessage(
                        h, peerId, content, echo.id
                    )
                )
                true
            }
            else -> false
        }
    }

    private fun newOwnMessage(conversationId: String, content: String) = CypherMessage(
        id = UUID.randomUUID().toString().uppercase(),
        conversationId = conversationId,
        senderId = identity.peerId,
        senderNickname = identity.nickname,
        content = content,
        timestampMs = System.currentTimeMillis(),
        isOwn = true,
        delivery = Delivery.SENDING,
    )

    private fun emitOutput(blob: ByteArray?) {
        val parsed = chat.cypher.android.core.CypherBridge.parseResponse(blob)
        feedEngineOutput(parsed.eventsJson)
    }

    // ---- commands (bitchat parity) ----------------------------------------------

    private fun handleCommand(conversationId: String, content: String): Boolean {
        val parts = content.split(" ", limit = 3)
        val cmd = parts[0].lowercase()
        val arg = parts.getOrNull(1)?.removePrefix("@") ?: ""
        fun sys(text: String) =
            appendMessage(systemMessage(conversationId, text), markRead = true)
        when (cmd) {
            "/join", "/j" -> {
                if (arg.isEmpty()) {
                    sys("usage: /join <channel>")
                } else {
                    val channel = if (arg.startsWith("#")) arg else "#$arg"
                    joinChannel(channel)
                    sys("joined channel $channel")
                }
            }
            "/channels" -> {
                sys(if (joinedChannels.isEmpty()) "no channels joined — /join <name> to create or join one"
                else "channels: ${joinedChannels.joinToString(" ")}")
            }
            "/leave" -> {
                val channel = if (arg.startsWith("#")) arg else "#$arg"
                if (joinedChannels.contains(channel)) {
                    leaveChannel(channel)
                    sys("left channel $channel")
                } else sys("not in channel $channel")
            }
            "/msg", "/m" -> {
                if (arg.isEmpty()) {
                    sys("usage: /msg <nickname> [message]")
                } else {
                    val peer = peers.firstOrNull {
                        it.nickname.equals(arg, ignoreCase = true)
                    } ?: peers.firstOrNull { it.nickname.startsWith(arg, ignoreCase = true) }
                    if (peer != null) {
                        openConversation("dm:${peer.peerId}")
                        return true
                    }
                    sys("no peer named \"$arg\" is nearby")
                }
            }
            "/w" -> {
                val online = peers.filter { it.connected }
                sys(if (online.isEmpty()) "no one else is around right now."
                else "online: ${online.joinToString(", ") { it.nickname }}")
            }
            "/clear" -> {
                messages = messages.toMutableMap().apply { remove(conversationId) }
                rebuildConversations()
            }
            "/block" -> {
                val peer = peers.firstOrNull { it.nickname.equals(arg, true) }
                val h = handle()
                if (peer != null && h != null) {
                    chat.cypher.android.core.CypherBridge.nativeBlockPeer(h, peer.peerId)
                    sys("blocked user ${peer.nickname}")
                } else sys("usage: /block <nickname>")
            }
            "/unblock" -> {
                val peer = peers.firstOrNull { it.nickname.equals(arg, true) }
                val h = handle()
                if (peer != null && h != null) {
                    chat.cypher.android.core.CypherBridge.nativeUnblockPeer(h, peer.peerId)
                    sys("unblocked user ${peer.nickname}")
                } else sys("usage: /unblock <nickname>")
            }
            "/slap" -> {
                val me = identity.nickname
                sys("* $me slaps $arg around a bit with a large trout 🐟 *")
            }
            "/hug" -> {
                val me = identity.nickname
                sys("* $me gives $arg a warm hug 🫂 *")
            }
            else -> sys("unknown command: $cmd")
        }
        return true
    }

    private fun systemMessage(conversationId: String, text: String) = CypherMessage(
        id = "sys-${UUID.randomUUID()}",
        conversationId = conversationId,
        senderId = "system",
        senderNickname = "system",
        content = text,
        timestampMs = System.currentTimeMillis(),
        isOwn = false,
        kind = MessageKind.SYSTEM,
    )

    // ---- channels ----------------------------------------------------------------

    fun joinChannel(channel: String) {
        if (!joinedChannels.contains(channel)) {
            joinedChannels = joinedChannels + channel
        }
        rebuildConversations()
    }

    fun leaveChannel(channel: String) {
        joinedChannels = joinedChannels - channel
        messages = messages.toMutableMap().apply { remove(channel) }
        rebuildConversations()
    }

    // ---- conversation management ---------------------------------------------------

    fun openConversation(id: String) {
        focusedConversation = id
        unread[id]?.let {
            unread.remove(id)
            rebuildConversations()
        }
        // Reactive read receipts: any of this peer's messages not yet READ.
        val peerId = id.takeIf { it.startsWith("dm:") }?.removePrefix("dm:")
        if (peerId != null) markPeerMessagesRead(peerId)
    }

    fun closeConversation() {
        focusedConversation = null
    }

    fun setDraft(conversationId: String, text: String) {
        if (text.isBlank()) drafts.remove(conversationId) else drafts[conversationId] = text
        rebuildConversations()
    }

    fun conversation(id: String): Conversation? = conversations.firstOrNull { it.id == id }

    fun messagesFor(id: String): List<CypherMessage> = messages[id] ?: emptyList()

    fun unreadTotal(): Int = unread.values.sum()

    // ---- engine event folding ---------------------------------------------------------

    private fun onEngineEvents(eventsJson: String) {
        main.post { feedEngineOutput(eventsJson) }
    }

    private fun feedEngineOutput(eventsJson: String) {
        val events = try {
            JSONArray(eventsJson)
        } catch (_: Exception) {
            return
        }
        var peersChanged = false
        var conversationChanged = false
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            val keys = event.keys()
            if (!keys.hasNext()) continue
            val variant = keys.next()
            val f = event.optJSONObject(variant) ?: JSONObject()
            when (variant) {
                "PublicMessageReceived" -> {
                    val msg = CypherMessage(
                        id = f.optString("message_id"),
                        conversationId = MESH_CONVERSATION,
                        senderId = f.optString("sender"),
                        senderNickname = f.optString("nickname"),
                        content = f.optString("content"),
                        timestampMs = f.optLong("timestamp_ms"),
                        isOwn = f.optString("sender") == identity.peerId,
                    )
                    appendMessage(msg, markRead = focusedConversation == MESH_CONVERSATION)
                    conversationChanged = true
                }
                "NoisePayloadReceived" -> {
                    onNoisePayload(f.optString("peer"), f.optInt("payload_type"), f.optJSONArray("payload"))
                    conversationChanged = true
                }
                "PeerConnected", "PeerDisconnected" -> {
                    peersChanged = true
                    scheduleStateRefresh()
                }
                "PeerListUpdated" -> peersChanged = true
                "MessageDeliveryStatusUpdated" -> {
                    updateDelivery(
                        f.optString("message_id"),
                        sending = f.optBoolean("sending"),
                        failed = f.optBoolean("failed"),
                    )
                    conversationChanged = true
                }
                "SessionEstablished", "SessionFailed" -> {
                    peersChanged = true
                    scheduleStateRefresh()
                }
                "MeshPingResult", "BoardPayloadReceived",
                "PublicVoiceFrameReceived", "GroupMessageReceived", "CourierEnvelopeOpened" -> {
                    // surfaced when their feature UI lands (v0.5)
                }
                "FileTransferReceived" -> {
                    foldFileTransfer(f)
                    conversationChanged = true
                }
            }
        }
        if (peersChanged) scheduleStateRefresh()
        if (conversationChanged || peersChanged) rebuildConversations()
        schedulePersist()
    }

    /**
     * Private-message TLV: `0x00 len id · 0x01 len content` (byte-exact with
     * the Swift/Rust wire format).
     */
    private fun onNoisePayload(peerHex: String, payloadType: Int, payload: JSONArray?) {
        val bytes = ByteArray(payload?.length() ?: 0)
        if (payload != null) for (i in 0 until payload.length()) bytes[i] = payload.optInt(i).toByte()
        val h = handle()
        val conversationId = "dm:$peerHex"
        when (payloadType) {
            0x01 -> {
                val decoded = decodePrivateMessage(bytes) ?: return
                val nickname = peerMeta[peerHex]?.nickname ?: peerHex.take(8)
                val msg = CypherMessage(
                    id = decoded.first,
                    conversationId = conversationId,
                    senderId = peerHex,
                    senderNickname = nickname,
                    content = decoded.second,
                    timestampMs = System.currentTimeMillis(),
                    isOwn = false,
                )
                appendMessage(msg, markRead = focusedConversation == conversationId)
                // Delivery ACK straight away (bitchat parity).
                if (h != null) {
                    chat.cypher.android.core.CypherBridge.nativeSendDeliveryAck(h, peerHex, decoded.first)
                }
                if (focusedConversation != conversationId || !foregrounded) {
                    notifier.notifyDm(peerHex, nickname, decoded.second)
                    unread[conversationId] = (unread[conversationId] ?: 0) + 1
                }
            }
            0x02 -> { // read receipt for one of our messages
                val messageId = decodeReceiptMessageId(bytes)
                if (messageId != null) updateDelivery(messageId, delivery = Delivery.READ)
            }
            0x03 -> { // delivery ack
                val messageId = decodeReceiptMessageId(bytes)
                if (messageId != null) updateDelivery(messageId, delivery = Delivery.DELIVERED)
            }
            else -> Unit
        }
    }

    private fun decodePrivateMessage(bytes: ByteArray): Pair<String, String>? {
        try {
            var offset = 0
            var id: String? = null
            var content: String? = null
            while (offset < bytes.size) {
                val tag = bytes[offset].toInt() and 0xFF
                val len = bytes[offset + 1].toInt() and 0xFF
                val body = bytes.copyOfRange(offset + 2, offset + 2 + len)
                when (tag) {
                    0x00 -> id = String(body, Charsets.UTF_8)
                    0x01 -> content = String(body, Charsets.UTF_8)
                }
                offset += 2 + len
            }
            if (id != null && content != null) return id to content
        } catch (_: Exception) {
        }
        return null
    }

    /** Receipt payloads carry a null-terminated or length-free message id; the
     *  engine frames them as the raw id bytes. */
    private fun decodeReceiptMessageId(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        // Trim trailing NULs if present.
        var end = bytes.size
        while (end > 0 && bytes[end - 1] == 0.toByte()) end--
        return String(bytes, 0, end, Charsets.UTF_8).takeIf { it.isNotEmpty() }
    }

    // ---- media transfer (bitchat file pipeline parity) --------------------------

    /** 10 MB file cap — same as bitchat's `MAX_FILE_SIZE_BYTES`. */
    private val maxMediaBytes = 10L * 1024 * 1024

    /**
     * Send an image/file/voice note. `peerId == null` broadcasts on the mesh
     * timeline; otherwise it rides the private Noise session.
     */
    fun sendMedia(conversationId: String, fileName: String, mimeType: String, bytes: ByteArray): String? {
        if (bytes.size > maxMediaBytes) return null
        val h = handle() ?: return null
        val transferId = UUID.randomUUID().toString()
        val kind = when {
            mimeType.startsWith("image/") -> MessageKind.IMAGE
            mimeType.startsWith("audio/") -> MessageKind.VOICE
            else -> MessageKind.FILE
        }
        val peerId = conversationId.takeIf { it.startsWith("dm:") }?.removePrefix("dm:")
        try {
            if (peerId == null) {
                emitOutput(
                    chat.cypher.android.core.CypherBridge.nativeSendFileBroadcast(
                        h, fileName, mimeType, bytes
                    )
                )
            } else {
                emitOutput(
                    chat.cypher.android.core.CypherBridge.nativeSendFilePrivate(
                        h, peerId, fileName, mimeType, bytes, transferId
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendMedia failed", e)
            return null
        }
        appendMessage(
            CypherMessage(
                id = transferId,
                conversationId = conversationId,
                senderId = identity.peerId,
                senderNickname = identity.nickname,
                content = fileName,
                timestampMs = System.currentTimeMillis(),
                isOwn = true,
                kind = kind,
                delivery = Delivery.SENDING,
                fileName = fileName,
                mimeType = mimeType,
                byteSize = bytes.size.toLong(),
            ),
            markRead = true,
        )
        return transferId
    }

    private fun foldFileTransfer(event: JSONObject) {
        val sender = event.optString("sender")
        val file = event.optJSONObject("file") ?: return
        val name = file.optString("file_name")
        val mime = file.optString("mime_type")
        val size = file.optLong("file_size")
        val content = file.optJSONArray("content")?.let { arr ->
            ByteArray(arr.length()) { i -> arr.optInt(i).toByte() }
        } ?: return

        val incomingDir = File(appContext.filesDir, "incoming").apply { mkdirs() }
        val safeName = name.replace(Regex("[/\\\\]"), "_")
        val target = File(incomingDir, "${System.currentTimeMillis()}_$safeName")
        try {
            target.writeBytes(content)
        } catch (e: Exception) {
            Log.e(TAG, "media write failed", e)
            return
        }

        val kind = when {
            mime.startsWith("image/") -> MessageKind.IMAGE
            mime.startsWith("audio/") -> MessageKind.VOICE
            else -> MessageKind.FILE
        }
        val senderPeer = peers.firstOrNull { it.peerId == sender }
        val isPrivate = event.optBoolean("is_private")
        val conversationId = if (isPrivate) "dm:$sender" else MESH_CONVERSATION
        appendMessage(
            CypherMessage(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                senderId = sender,
                senderNickname = senderPeer?.nickname ?: sender.take(8),
                content = name,
                timestampMs = System.currentTimeMillis(),
                isOwn = false,
                kind = kind,
                fileName = name,
                mimeType = mime,
                localPath = target.absolutePath,
                byteSize = size,
            ),
            markRead = focusedConversation == conversationId,
        )
        if (isPrivate && focusedConversation != conversationId) {
            val preview = appContext.getString(
                when (kind) {
                    MessageKind.IMAGE -> R.string.dm_notification_sent_image
                    MessageKind.VOICE -> R.string.dm_notification_sent_voice
                    else -> R.string.dm_notification_sent_file
                }
            )
            notifier.notifyDm(sender, senderPeer?.nickname ?: sender.take(8), preview)
            unread[conversationId] = (unread[conversationId] ?: 0) + 1
        }
    }

    private fun markPeerMessagesRead(peerId: String) {
        val h = handle() ?: return
        val convId = "dm:$peerId"
        // Received messages carry no delivery status in the UI; the receipt
        // itself is fire-and-forget over the established Noise session.
        val seen = HashSet<String>()
        messages[convId]?.forEach { m ->
            if (!m.isOwn && seen.add(m.id)) {
                try {
                    chat.cypher.android.core.CypherBridge.nativeSendReadReceipt(h, peerId, m.id)
                } catch (_: Exception) {
                }
            }
        }
    }

    // ---- state mutation helpers ---------------------------------------------------

    private fun appendMessage(msg: CypherMessage, markRead: Boolean) {
        val list = messages[msg.conversationId].orEmpty()
        if (list.any { it.id == msg.id }) return // dedup by id
        val next = (list + msg).takeLast(MAX_MESSAGES_PER_CONVERSATION)
        messages = messages.toMutableMap().apply { put(msg.conversationId, next) }
        if (!markRead && !msg.isOwn && msg.kind != MessageKind.SYSTEM) {
            unread[msg.conversationId] = (unread[msg.conversationId] ?: 0) + 1
        }
        rebuildConversations()
    }

    private fun updateDelivery(
        messageId: String,
        sending: Boolean = false,
        failed: Boolean = false,
        delivery: Delivery? = null,
    ) {
        var found = false
        val updated = messages.mapValues { (convId, list) ->
            list.map { m ->
                if (m.id == messageId && m.isOwn) {
                    found = true
                    val newDelivery = when {
                        failed -> Delivery.FAILED
                        delivery != null ->
                            if ((m.delivery?.rank ?: -1) < delivery.rank) delivery else m.delivery
                        sending -> Delivery.SENDING
                        else -> Delivery.SENT
                    }
                    if (newDelivery != m.delivery) {
                        m.copy(delivery = newDelivery)
                    } else m
                } else m
            }
        }
        if (found) {
            messages = updated
            rebuildConversations()
        }
    }

    fun rebuildConversations() {
        val result = ArrayList<Conversation>()
        // Mesh timeline (always present, pinned to top).
        val meshMessages = messages[MESH_CONVERSATION].orEmpty()
        result.add(
            Conversation(
                id = MESH_CONVERSATION,
                kind = ConversationKind.MESH,
                displayName = "Mesh Broadcast",
                peerId = null,
                lastMessage = meshMessages.lastOrNull(),
                unreadCount = unread[MESH_CONVERSATION] ?: 0,
                online = peers.any { it.connected },
                lastActivityMs = meshMessages.lastOrNull()?.timestampMs ?: 0L,
            )
        )
        // Channels.
        joinedChannels.sorted().forEach { channel ->
            val channelMessages = messages[channel].orEmpty()
            result.add(
                Conversation(
                    id = channel,
                    kind = ConversationKind.CHANNEL,
                    displayName = channel,
                    peerId = null,
                    lastMessage = channelMessages.lastOrNull(),
                    unreadCount = unread[channel] ?: 0,
                    lastActivityMs = channelMessages.lastOrNull()?.timestampMs ?: 0L,
                )
            )
        }
        // DMs.
        peers.forEach { peer ->
            val convId = "dm:${peer.peerId}"
            val dmMessages = messages[convId].orEmpty()
            result.add(
                Conversation(
                    id = convId,
                    kind = ConversationKind.DIRECT,
                    displayName = peer.nickname,
                    peerId = peer.peerId,
                    lastMessage = dmMessages.lastOrNull(),
                    unreadCount = unread[convId] ?: 0,
                    online = peer.connected,
                    verified = peer.verified,
                    favorite = peer.favorite,
                    secureSession = peer.secureSession,
                    draft = drafts[convId] ?: "",
                    lastActivityMs = dmMessages.lastOrNull()?.timestampMs ?: peer.lastSeenMs,
                )
            )
        }
        conversations = result.sortedWith(
            compareByDescending<Conversation> { it.kind == ConversationKind.MESH }
                .thenByDescending { it.lastActivityMs }
        )
    }

    private fun scheduleStateRefresh() {
        // The engine emits peer events immediately; the full state snapshot is
        // cheap and safe to re-read on the next main-loop turn.
        main.post { refreshIdentity() }
    }

    // ---- persistence ---------------------------------------------------------------

    private fun loadHistory() {
        val snapshot = history.load()
        val byConv = snapshot.messages.groupBy { it.conversationId }
        messages = byConv.mapValues { (_, v) -> v.takeLast(MAX_MESSAGES_PER_CONVERSATION) }
        unread = snapshot.unread.toMutableMap()
        drafts = snapshot.drafts.toMutableMap()
        rebuildConversations()
    }

    private fun schedulePersist() {
        if (persistScheduled) return
        persistScheduled = true
        main.postDelayed(
            {
                persistScheduled = false
                persistNow()
            },
            PERSIST_DEBOUNCE_MS,
        )
    }

    fun persistNow() {
        val flat = messages.values.flatten()
            .sortedBy { it.timestampMs }
            .takeLast(MAX_TOTAL)
        history.save(flat, unread, drafts)
    }

    fun panicWipe() {
        history.wipe()
        messages = emptyMap()
        unread = mutableMapOf()
        drafts = mutableMapOf()
        peerMeta = mutableMapOf()
        joinedChannels = emptyList()
        notifier.cancelAllDms()
        rebuildConversations()
    }

    // ---- peer actions ----------------------------------------------------------------

    fun toggleFavorite(peerId: String) {
        val h = handle() ?: return
        val current = peers.firstOrNull { it.peerId == peerId }?.favorite ?: false
        chat.cypher.android.core.CypherBridge.nativeSetPeerFavorite(h, peerId, !current)
        scheduleStateRefresh()
    }

    fun setVerified(peerId: String, verified: Boolean) {
        val h = handle() ?: return
        chat.cypher.android.core.CypherBridge.nativeSetPeerVerified(h, peerId, verified)
        scheduleStateRefresh()
    }

    fun blockPeer(peerId: String) {
        val h = handle() ?: return
        chat.cypher.android.core.CypherBridge.nativeBlockPeer(h, peerId)
        scheduleStateRefresh()
    }

    fun meshPing(peerId: String) {
        val h = handle() ?: return
        emitOutput(chat.cypher.android.core.CypherBridge.nativeSendMeshPing(h, peerId))
    }
}
