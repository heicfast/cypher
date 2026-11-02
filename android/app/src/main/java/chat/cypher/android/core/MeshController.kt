package chat.cypher.android.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import chat.cypher.android.MainActivity
import chat.cypher.android.R
import chat.cypher.android.ble.BleLinkLayer
import chat.cypher.android.ble.MeshEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt

data class SlashCommandSpec(
    val command: String,
    val aliases: List<String> = emptyList(),
    val syntax: String,
    val description: String,
)

/**
 * Coordinates the native Rust mesh engine (`CypherBridge`), the Android `BleLinkLayer`,
 * `MeshGraphService`, `ChannelCrypto`, `VerificationService`, Store-and-Forward outbox,
 * and `MeshStore` Room SQLite persistence.
 */
object MeshController {
    private const val TAG = "CypherMeshCtrl"
    private const val MSG_NOTIFICATION_CHANNEL = "cypher_incoming_messages"
    private const val MAX_FILE_TRANSFER_BYTES = 10 * 1024 * 1024L

    val store: MeshStore
        get() = MeshStore

    val myPeerIdHex: StateFlow<String>
        get() = MeshStore.localIdentityHex

    val myFingerprintHex: StateFlow<String>
        get() = MeshStore.localFingerprintHex

    val SLASH_COMMANDS: List<SlashCommandSpec> = listOf(
        SlashCommandSpec("/join", listOf("/j"), "/join #channel [password]", "Join or create a mesh/geohash channel"),
        SlashCommandSpec("/leave", emptyList(), "/leave", "Leave the current channel"),
        SlashCommandSpec("/msg", listOf("/m", "/dm"), "/msg @nickname [message]", "Start or send an encrypted direct message"),
        SlashCommandSpec("/who", listOf("/w"), "/who", "List peers currently reachable on the BLE mesh"),
        SlashCommandSpec("/channels", emptyList(), "/channels", "List joined and bookmarked channels"),
        SlashCommandSpec("/pass", emptyList(), "/pass [password]", "Set or clear password for current channel"),
        SlashCommandSpec("/transfer", emptyList(), "/transfer @nickname", "Transfer channel ownership to a peer"),
        SlashCommandSpec("/nick", emptyList(), "/nick <nickname>", "Change your mesh nickname"),
        SlashCommandSpec("/verify", emptyList(), "/verify [@nickname]", "Send a cryptographic verification challenge"),
        SlashCommandSpec("/route", emptyList(), "/route @nickname", "Compute shortest multi-hop mesh route to peer"),
        SlashCommandSpec("/fav", emptyList(), "/fav @nickname", "Toggle favorite status for a peer"),
        SlashCommandSpec("/block", emptyList(), "/block [@nickname]", "Block a peer or list blocked peers"),
        SlashCommandSpec("/unblock", emptyList(), "/unblock @nickname", "Unblock a peer"),
        SlashCommandSpec("/hug", emptyList(), "/hug @nickname", "Send a warm hug action"),
        SlashCommandSpec("/slap", emptyList(), "/slap @nickname", "Slap a peer around a bit with a large trout"),
        SlashCommandSpec("/shrug", emptyList(), "/shrug [message]", "Append a shrug emoticon"),
        SlashCommandSpec("/ping", emptyList(), "/ping", "Broadcast a mesh latency ping"),
        SlashCommandSpec("/announce", emptyList(), "/announce", "Broadcast immediate presence announcement"),
        SlashCommandSpec("/clear", emptyList(), "/clear", "Clear messages in the active conversation"),
        SlashCommandSpec("/help", emptyList(), "/help", "Show all available IRC slash commands"),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engineLock = Any()
    private val started = AtomicBoolean(false)
    private val handleRef = AtomicLong(0L)
    private val pendingChallenges = ConcurrentHashMap<String, String>()
    private val voiceBurstBuffers = ConcurrentHashMap<String, ByteArrayOutputStream>()

    private var bleLayer: BleLinkLayer? = null
    private var unsubscribeEvents: (() -> Unit)? = null
    private var appContext: Context? = null

    fun filterCommandSuggestions(input: String): List<SlashCommandSpec> {
        val trimmed = input.trimStart()
        if (!trimmed.startsWith("/")) return emptyList()
        val token = trimmed.substringBefore(' ').lowercase(Locale.US)
        if (trimmed.contains(' ')) return emptyList()
        return SLASH_COMMANDS.filter { spec ->
            spec.command.startsWith(token) || spec.aliases.any { it.startsWith(token) }
        }
    }

    fun start(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        MeshStore.attachDatabase(appCtx)
        MeshStore.loadOrCreateSeed(appCtx)
        if (!started.compareAndSet(false, true)) return

        val nick = MeshStore.nickname.value

        unsubscribeEvents?.invoke()
        unsubscribeEvents = MeshEvents.subscribe { eventsJson ->
            parseEngineEvents(eventsJson)
        }

        if (CypherBridge.isLoaded) {
            val h = synchronized(engineLock) {
                MeshRuntime.start(appCtx, nick)
            }
            handleRef.set(h)
            refreshEngineState()
        }

        val layer = BleLinkLayer(appCtx)
        bleLayer = layer
        layer.start()

        scope.launch {
            while (isActive && started.get()) {
                val h = handleRef.get()
                if (h != 0L && CypherBridge.isLoaded) {
                    val response = synchronized(engineLock) {
                        CypherBridge.nativeTick(h)
                    }
                    processNativeResponse(response)
                    refreshEngineState()
                }
                delay(250L)
            }
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        unsubscribeEvents?.invoke()
        unsubscribeEvents = null
        bleLayer?.stop()
        bleLayer = null
        val h = handleRef.getAndSet(0L)
        if (h != 0L && CypherBridge.isLoaded) {
            synchronized(engineLock) {
                MeshRuntime.stop()
            }
        }
    }

    fun updateNickname(context: Context, nickname: String) {
        MeshStore.updateNickname(context, nickname)
        val h = handleRef.get()
        if (h != 0L && CypherBridge.isLoaded) {
            val resp = synchronized(engineLock) {
                CypherBridge.nativeSetNickname(h, MeshStore.nickname.value)
            }
            processNativeResponse(resp)
            refreshEngineState()
        }
    }

    fun updateFullProfile(
        context: Context,
        displayName: String,
        username: String,
        statusBio: String,
        avatarUri: String?,
    ) {
        MeshStore.updateNickname(context, username)
        MeshStore.updateDisplayName(context, displayName)
        MeshStore.updateStatusBio(context, statusBio)
        MeshStore.updateAvatarUri(context, avatarUri)
        val h = handleRef.get()
        if (h != 0L && CypherBridge.isLoaded) {
            val resp = synchronized(engineLock) {
                CypherBridge.nativeSetNickname(h, MeshStore.nickname.value)
            }
            processNativeResponse(resp)
            refreshEngineState()
        }
    }

    fun rotateIdentity(context: Context) {
        val appCtx = context.applicationContext
        MeshStore.rotateIdentitySeed(appCtx)
        val nick = MeshStore.nickname.value
        if (CypherBridge.isLoaded) {
            val newHandle = synchronized(engineLock) {
                MeshRuntime.panicReset(appCtx, nick)
            }
            handleRef.set(newHandle)
            // Re-push PoweredOn (state 5) so the freshly-created Rust engine resumes scanning and advertising
            BleLinkLayer.active?.pushRadioState(5)
            refreshEngineState()
            announceNow()
        }
    }

    /**
     * Marks a conversation as read locally and dispatches Noise `0x02` read receipts for unread
     * incoming private messages.
     */
    fun markConversationReadAndNotify(conversationKey: String) {
        val newlyRead = MeshStore.markConversationRead(conversationKey)
        if (conversationKey.startsWith("#")) return
        val h = handleRef.get()
        if (h == 0L || !CypherBridge.isLoaded) return
        val peerHex = conversationKey.lowercase(Locale.US)
        newlyRead.forEach { msg ->
            val resp = synchronized(engineLock) {
                CypherBridge.nativeSendReadReceipt(h, peerHex, msg.id)
            }
            processNativeResponse(resp)
        }
    }

    fun sendMessage(
        text: String,
        replyToMessageId: String? = null,
        replyPreview: String? = null,
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        if (trimmed.startsWith("/")) {
            if (handleSlashCommand(trimmed)) return
        }

        val activeConv = MeshStore.activeConversation.value
        val now = System.currentTimeMillis()
        val h = handleRef.get()

        val wireText = if (replyPreview != null) {
            "> ${replyPreview.take(60)}\n$trimmed"
        } else {
            trimmed
        }

        if (activeConv.startsWith("#")) {
            val normalizedChannel = Geohash.normalizeTag(activeConv)
            val password = MeshStore.getChannelPassword(normalizedChannel)
            val channelPayload = if (!password.isNullOrBlank()) {
                ChannelCrypto.encryptChannelPayload(normalizedChannel, password, wireText)
            } else {
                wireText
            }
            val payloadWithChannel = if (normalizedChannel == MeshStore.PUBLIC_CONVERSATION) {
                channelPayload
            } else {
                "[$normalizedChannel] $channelPayload"
            }

            val msgId = UUID.randomUUID().toString()
            // Append locally BEFORE calling nativeSendPublicMessage so if the native engine
            // emits an immediate loopback event we already have the outgoing message in place.
            MeshStore.appendMessage(
                ChatMessage(
                    id = msgId,
                    conversationKey = normalizedChannel,
                    senderHex = MeshStore.localIdentityHex.value.ifEmpty { "self" },
                    senderLabel = MeshStore.nickname.value,
                    text = trimmed,
                    timestampMs = now,
                    outgoing = true,
                    encrypted = !password.isNullOrBlank(),
                    hops = 0,
                    delivery = DeliveryStatus.SENT,
                    replyToMessageId = replyToMessageId,
                    replyPreview = replyPreview,
                ),
            )

            if (h != 0L && CypherBridge.isLoaded) {
                val resp = synchronized(engineLock) {
                    CypherBridge.nativeSendPublicMessage(h, payloadWithChannel)
                }
                MeshStore.recordTraffic(packets = 1, bytes = payloadWithChannel.length.toLong())
                processNativeResponse(resp)
            }
            if (MeshStore.peers.value.any { it.connected }) {
                MeshStore.markMessageDelivered(msgId, DeliveryStatus.DELIVERED)
            }
        } else {
            sendPrivateMessageInternal(
                peerKeyHex = activeConv,
                displayText = trimmed,
                wirePayload = wireText,
                kind = MessageKind.TEXT,
                replyToMessageId = replyToMessageId,
                replyPreview = replyPreview,
            )
        }
    }

    private fun sendPrivateMessageInternal(
        peerKeyHex: String,
        displayText: String,
        wirePayload: String,
        kind: MessageKind = MessageKind.TEXT,
        attachmentPath: String? = null,
        attachmentMeta: String? = null,
        replyToMessageId: String? = null,
        replyPreview: String? = null,
    ) {
        val normPeer = peerKeyHex.lowercase(Locale.US)
        val now = System.currentTimeMillis()
        val msgId = UUID.randomUUID().toString()
        val peerOnline = MeshStore.peers.value.any {
            it.peerKeyHex.equals(normPeer, ignoreCase = true) && it.connected
        }
        val h = handleRef.get()

        val initialStatus = when {
            peerOnline -> DeliveryStatus.SENT
            MeshStore.storeAndForwardEnabled.value -> DeliveryStatus.QUEUED
            else -> DeliveryStatus.SENT
        }

        MeshStore.appendMessage(
            ChatMessage(
                id = msgId,
                conversationKey = normPeer,
                senderHex = MeshStore.localIdentityHex.value.ifEmpty { "self" },
                senderLabel = MeshStore.nickname.value,
                text = displayText,
                timestampMs = now,
                outgoing = true,
                encrypted = true,
                hops = 0,
                delivery = initialStatus,
                kind = kind,
                attachmentPath = attachmentPath,
                attachmentMeta = attachmentMeta,
                replyToMessageId = replyToMessageId,
                replyPreview = replyPreview,
            ),
        )

        if (h != 0L && CypherBridge.isLoaded) {
            // Always pass to the Rust engine as well (which manages its own pending/Noise handshake queue
            // and courier envelopes)
            val resp = synchronized(engineLock) {
                CypherBridge.nativeSendPrivateMessage(h, normPeer, wirePayload, msgId)
            }
            MeshStore.recordTraffic(packets = 1, bytes = wirePayload.length.toLong())
            processNativeResponse(resp)
            if (!peerOnline && MeshStore.storeAndForwardEnabled.value) {
                MeshStore.enqueueOutboxMessage(
                    messageId = msgId,
                    recipientHex = normPeer,
                    payloadText = wirePayload,
                )
            }
        } else if (MeshStore.storeAndForwardEnabled.value) {
            MeshStore.enqueueOutboxMessage(
                messageId = msgId,
                recipientHex = normPeer,
                payloadText = wirePayload,
            )
        }
    }

    private fun flushOutboxForPeer(peerHex: String) {
        val norm = peerHex.lowercase(Locale.US)
        scope.launch {
            val queued = MeshStore.drainPendingOutboxForPeer(norm)
            if (queued.isEmpty()) return@launch
            val h = handleRef.get()
            for (item in queued) {
                if (h != 0L && CypherBridge.isLoaded) {
                    val resp = synchronized(engineLock) {
                        CypherBridge.nativeSendPrivateMessage(h, norm, item.payloadText, item.messageId)
                    }
                    MeshStore.recordTraffic(packets = 1, bytes = item.payloadText.length.toLong())
                    processNativeResponse(resp)
                }
                MeshStore.markMessageDelivered(item.messageId, DeliveryStatus.DELIVERED)
            }
        }
    }

    fun sendVoiceNote(file: File, durationMs: Long) {
        val durationSec = (durationMs / 1000L).coerceAtLeast(1L)
        val activeConv = MeshStore.activeConversation.value
        val now = System.currentTimeMillis()
        val rawBytes = runCatching { file.readBytes() }.getOrNull()
        val audioPayloadB64 = if (rawBytes != null && rawBytes.size <= 32_768) {
            Base64.encodeToString(rawBytes, Base64.NO_WRAP)
        } else {
            ""
        }

        val wireFrame = if (audioPayloadB64.isNotEmpty()) {
            "[VOICE_NOTE:${durationSec}s:${file.name}:$audioPayloadB64]"
        } else {
            "[VOICE_NOTE:${durationSec}s:${file.name}]"
        }

        val h = handleRef.get()
        if (activeConv.startsWith("#")) {
            val msgId = UUID.randomUUID().toString()
            MeshStore.appendMessage(
                ChatMessage(
                    id = msgId,
                    conversationKey = activeConv,
                    senderHex = MeshStore.localIdentityHex.value.ifEmpty { "self" },
                    senderLabel = MeshStore.nickname.value,
                    text = "Voice message (${formatDuration(durationSec)})",
                    timestampMs = now,
                    outgoing = true,
                    encrypted = MeshStore.isChannelPasswordProtected(activeConv),
                    hops = 0,
                    delivery = DeliveryStatus.SENT,
                    kind = MessageKind.VOICE_NOTE,
                    attachmentPath = file.absolutePath,
                    attachmentMeta = "${durationSec}s",
                ),
            )
            if (h != 0L && CypherBridge.isLoaded) {
                val resp = synchronized(engineLock) {
                    if (activeConv == MeshStore.PUBLIC_CONVERSATION && rawBytes != null && rawBytes.size <= MAX_FILE_TRANSFER_BYTES) {
                        CypherBridge.nativeSendFileBroadcast(h, file.name, "audio/mp4", rawBytes)
                    } else {
                        val channelWire = if (activeConv == MeshStore.PUBLIC_CONVERSATION) wireFrame else "[$activeConv] $wireFrame"
                        CypherBridge.nativeSendPublicMessage(h, channelWire)
                    }
                }
                MeshStore.recordTraffic(packets = 1, bytes = file.length().coerceAtLeast(128L))
                processNativeResponse(resp)
            }
        } else {
            val normPeer = activeConv.lowercase(Locale.US)
            val transferId = UUID.randomUUID().toString()
            MeshStore.appendMessage(
                ChatMessage(
                    id = transferId,
                    conversationKey = normPeer,
                    senderHex = MeshStore.localIdentityHex.value.ifEmpty { "self" },
                    senderLabel = MeshStore.nickname.value,
                    text = "Voice message (${formatDuration(durationSec)})",
                    timestampMs = now,
                    outgoing = true,
                    encrypted = true,
                    hops = 0,
                    delivery = DeliveryStatus.SENT,
                    kind = MessageKind.VOICE_NOTE,
                    attachmentPath = file.absolutePath,
                    attachmentMeta = "${durationSec}s",
                ),
            )
            if (h != 0L && CypherBridge.isLoaded && rawBytes != null && rawBytes.size <= MAX_FILE_TRANSFER_BYTES) {
                val resp = synchronized(engineLock) {
                    CypherBridge.nativeSendFilePrivate(
                        h,
                        normPeer,
                        file.name,
                        "audio/mp4",
                        rawBytes,
                        transferId,
                    )
                }
                MeshStore.recordTraffic(packets = 1, bytes = rawBytes.size.toLong())
                processNativeResponse(resp)
            } else {
                sendPrivateMessageInternal(
                    peerKeyHex = normPeer,
                    displayText = "Voice message (${formatDuration(durationSec)})",
                    wirePayload = wireFrame,
                    kind = MessageKind.VOICE_NOTE,
                    attachmentPath = file.absolutePath,
                    attachmentMeta = "${durationSec}s",
                )
            }
        }
    }

    fun sendMediaAttachment(context: Context, uri: Uri, isImage: Boolean) {
        val activeConv = MeshStore.activeConversation.value
        val now = System.currentTimeMillis()
        val (fileName, fileSize, localFile) = copyAttachmentToLocalCache(context, uri, isImage)
        val kind = if (isImage) MessageKind.IMAGE else MessageKind.FILE
        val sizeLabel = formatByteCount(fileSize)
        val displayTitle = if (isImage) "Photo ($fileName)" else "File: $fileName ($sizeLabel)"
        val mimeType = if (isImage) {
            "image/jpeg"
        } else {
            context.contentResolver.getType(uri) ?: "application/octet-stream"
        }

        val bytes = runCatching {
            localFile?.takeIf { it.length() in 1..MAX_FILE_TRANSFER_BYTES }?.readBytes()
        }.getOrNull()

        // Compact inline base64 fallback for custom #channels or when file is small
        val inlineB64 = if (isImage && localFile != null) {
            encodeCompactImagePreviewB64(localFile)
        } else {
            ""
        }
        val wireHeader = when {
            isImage && inlineB64.isNotEmpty() -> "[IMAGE:$fileName:$sizeLabel:$inlineB64]"
            isImage -> "[IMAGE:$fileName:$sizeLabel]"
            else -> "[FILE:$fileName:$sizeLabel]"
        }

        val h = handleRef.get()
        if (activeConv.startsWith("#")) {
            val msgId = UUID.randomUUID().toString()
            MeshStore.appendMessage(
                ChatMessage(
                    id = msgId,
                    conversationKey = activeConv,
                    senderHex = MeshStore.localIdentityHex.value.ifEmpty { "self" },
                    senderLabel = MeshStore.nickname.value,
                    text = displayTitle,
                    timestampMs = now,
                    outgoing = true,
                    encrypted = MeshStore.isChannelPasswordProtected(activeConv),
                    hops = 0,
                    delivery = DeliveryStatus.SENT,
                    kind = kind,
                    attachmentPath = localFile?.absolutePath ?: uri.toString(),
                    attachmentMeta = "$fileName • $sizeLabel",
                ),
            )
            if (h != 0L && CypherBridge.isLoaded) {
                val resp = synchronized(engineLock) {
                    if (bytes != null && activeConv == MeshStore.PUBLIC_CONVERSATION) {
                        CypherBridge.nativeSendFileBroadcast(h, fileName, mimeType, bytes)
                    } else {
                        val payload = if (activeConv == MeshStore.PUBLIC_CONVERSATION) wireHeader else "[$activeConv] $wireHeader"
                        CypherBridge.nativeSendPublicMessage(h, payload)
                    }
                }
                MeshStore.recordTraffic(packets = 1, bytes = fileSize.coerceAtLeast(256L))
                processNativeResponse(resp)
            }
        } else {
            val normPeer = activeConv.lowercase(Locale.US)
            val transferId = UUID.randomUUID().toString()
            MeshStore.appendMessage(
                ChatMessage(
                    id = transferId,
                    conversationKey = normPeer,
                    senderHex = MeshStore.localIdentityHex.value.ifEmpty { "self" },
                    senderLabel = MeshStore.nickname.value,
                    text = displayTitle,
                    timestampMs = now,
                    outgoing = true,
                    encrypted = true,
                    hops = 0,
                    delivery = DeliveryStatus.SENT,
                    kind = kind,
                    attachmentPath = localFile?.absolutePath ?: uri.toString(),
                    attachmentMeta = "$fileName • $sizeLabel",
                ),
            )
            if (h != 0L && CypherBridge.isLoaded && bytes != null) {
                val resp = synchronized(engineLock) {
                    CypherBridge.nativeSendFilePrivate(
                        h,
                        normPeer,
                        fileName,
                        mimeType,
                        bytes,
                        transferId,
                    )
                }
                MeshStore.recordTraffic(packets = 1, bytes = bytes.size.toLong())
                processNativeResponse(resp)
            } else if (h != 0L && CypherBridge.isLoaded) {
                val resp = synchronized(engineLock) {
                    CypherBridge.nativeSendPrivateMessage(h, normPeer, wireHeader, transferId)
                }
                processNativeResponse(resp)
            }
        }
    }

    fun sendApproximateLocation(context: Context? = appContext) {
        val ctx = context ?: appContext
        val activeConv = MeshStore.activeConversation.value
        val now = System.currentTimeMillis()

        if (ctx == null || !LocationChannelResolver.hasLocationPermission(ctx)) {
            appendSystemNotice(
                activeConv,
                "Location permission is required to share your coarse GPS coordinates.",
            )
            return
        }

        val snapshot = LocationChannelResolver.resolveBestLocation(ctx)
        if (snapshot == null) {
            appendSystemNotice(
                activeConv,
                "Waiting for a device GPS/Network location fix. Ensure Location Services are enabled.",
            )
            return
        }

        val coords = snapshot.formatCoordinates()
        val geohash7 = snapshot.geohash(7)
        val areaSuffix = if (snapshot.areaName.isNotBlank()) " • ${snapshot.areaName}" else ""
        val display = "Location (approx.)\n$coords\n#$geohash7$areaSuffix"
        val wire = "[LOCATION:$coords:#$geohash7]"

        if (activeConv.startsWith("#")) {
            val h = handleRef.get()
            val payload = if (activeConv == MeshStore.PUBLIC_CONVERSATION) wire else "[$activeConv] $wire"
            MeshStore.appendMessage(
                ChatMessage(
                    id = UUID.randomUUID().toString(),
                    conversationKey = activeConv,
                    senderHex = MeshStore.localIdentityHex.value.ifEmpty { "self" },
                    senderLabel = MeshStore.nickname.value,
                    text = display,
                    timestampMs = now,
                    outgoing = true,
                    encrypted = MeshStore.isChannelPasswordProtected(activeConv),
                    hops = 0,
                    delivery = DeliveryStatus.SENT,
                    kind = MessageKind.LOCATION,
                    attachmentMeta = coords,
                ),
            )
            if (h != 0L && CypherBridge.isLoaded) {
                val resp = synchronized(engineLock) {
                    CypherBridge.nativeSendPublicMessage(h, payload)
                }
                MeshStore.recordTraffic(packets = 1, bytes = payload.length.toLong())
                processNativeResponse(resp)
            }
        } else {
            sendPrivateMessageInternal(
                peerKeyHex = activeConv,
                displayText = display,
                wirePayload = wire,
                kind = MessageKind.LOCATION,
                attachmentMeta = coords,
            )
        }
    }

    fun sendVerificationChallenge(peerKeyHex: String) {
        val normPeer = peerKeyHex.lowercase(Locale.US)
        val localFp = MeshStore.localFingerprintHex.value
        if (normPeer.isBlank() || localFp.isBlank()) return
        val nonce = VerificationService.generateNonceHex()
        pendingChallenges[nonce] = normPeer
        val frame = VerificationService.buildChallengeFrame(nonce, localFp)
        val h = handleRef.get()
        if (h != 0L && CypherBridge.isLoaded) {
            val msgId = UUID.randomUUID().toString()
            val resp = synchronized(engineLock) {
                CypherBridge.nativeSendPrivateMessage(h, normPeer, frame, msgId)
            }
            processNativeResponse(resp)
        }
        appendSystemNotice(
            MeshStore.activeConversation.value,
            "Sent cryptographic verification challenge to ${MeshStore.resolvePeerNickname(normPeer)} (${normPeer.take(8)}…).",
        )
    }

    private fun handleSlashCommand(raw: String): Boolean {
        val parts = raw.trim().split(Regex("\\s+"))
        val cmd = parts.firstOrNull()?.lowercase(Locale.US) ?: return false
        val arg1 = parts.getOrNull(1)
        val restAfterArg1 = parts.drop(2).joinToString(" ").trim()
        val allArgs = parts.drop(1).joinToString(" ").trim()
        val activeConv = MeshStore.activeConversation.value

        return when (cmd) {
            "/j", "/join" -> {
                if (arg1.isNullOrBlank()) {
                    appendSystemNotice(activeConv, "Usage: /join #channel [password]")
                } else {
                    val password = restAfterArg1.takeIf { it.isNotBlank() }
                    val tag = Geohash.normalizeTag(arg1)
                    val ok = MeshStore.joinChannel(
                        tag = tag,
                        password = password,
                        ownerPeerHex = MeshStore.localIdentityHex.value.takeIf { it.isNotBlank() },
                    )
                    if (ok) {
                        val pwdNote = if (!password.isNullOrBlank()) " (AES-256-GCM password protected)" else ""
                        appendSystemNotice(tag, "Joined channel $tag$pwdNote.")
                    } else {
                        appendSystemNotice(activeConv, "Cannot join $tag: password does not match channel commitment.")
                    }
                }
                true
            }
            "/leave" -> {
                if (activeConv.startsWith("#") && activeConv != MeshStore.PUBLIC_CONVERSATION) {
                    val leaving = activeConv
                    MeshStore.leaveChannel(leaving)
                    appendSystemNotice(MeshStore.PUBLIC_CONVERSATION, "Left channel $leaving.")
                } else {
                    appendSystemNotice(activeConv, "Cannot leave primary #mesh broadcast channel.")
                }
                true
            }
            "/pass" -> {
                if (!activeConv.startsWith("#") || activeConv == MeshStore.PUBLIC_CONVERSATION) {
                    appendSystemNotice(activeConv, "/pass can only be used inside a custom #channel.")
                    return true
                }
                val owner = MeshStore.channelOwners.value[activeConv]
                val localId = MeshStore.localIdentityHex.value
                if (!owner.isNullOrBlank() && localId.isNotBlank() && !owner.equals(localId, ignoreCase = true)) {
                    appendSystemNotice(activeConv, "Only the channel creator (${owner.take(8)}…) can change the password.")
                    return true
                }
                if (allArgs.isBlank()) {
                    MeshStore.setChannelPassword(activeConv, null)
                    appendSystemNotice(activeConv, "Cleared password protection on $activeConv.")
                } else {
                    MeshStore.setChannelPassword(activeConv, allArgs, localId)
                    val commitment = MeshStore.channelCommitments.value[activeConv].orEmpty()
                    val h = handleRef.get()
                    if (h != 0L && CypherBridge.isLoaded && commitment.isNotBlank()) {
                        val commitFrame = "[CHANNEL_COMMIT:$activeConv:$commitment:$localId]"
                        val resp = synchronized(engineLock) {
                            CypherBridge.nativeSendPublicMessage(h, commitFrame)
                        }
                        processNativeResponse(resp)
                    }
                    appendSystemNotice(activeConv, "Password updated for $activeConv (PBKDF2-HMAC-SHA256 + AES-256-GCM).")
                }
                true
            }
            "/transfer" -> {
                if (!activeConv.startsWith("#") || activeConv == MeshStore.PUBLIC_CONVERSATION) {
                    appendSystemNotice(activeConv, "/transfer can only be used inside a custom #channel.")
                    return true
                }
                val targetPeer = resolveTargetPeerHex(arg1)
                if (targetPeer == null) {
                    appendSystemNotice(activeConv, "Peer not found. Usage: /transfer @nickname")
                } else {
                    MeshStore.transferChannelOwnership(activeConv, targetPeer)
                    appendSystemNotice(
                        activeConv,
                        "Transferred ownership of $activeConv to ${MeshStore.resolvePeerNickname(targetPeer)} (${targetPeer.take(8)}…).",
                    )
                }
                true
            }
            "/m", "/msg", "/dm" -> {
                if (arg1.isNullOrBlank()) {
                    appendSystemNotice(activeConv, "Usage: /msg @nickname [message]")
                    return true
                }
                val targetPeer = resolveTargetPeerHex(arg1)
                if (targetPeer == null) {
                    appendSystemNotice(activeConv, "No mesh peer matching '$arg1' found.")
                } else {
                    MeshStore.selectConversation(targetPeer)
                    if (restAfterArg1.isNotBlank()) {
                        sendMessage(restAfterArg1)
                    } else {
                        appendSystemNotice(
                            targetPeer,
                            "Opened encrypted direct chat with ${MeshStore.resolvePeerNickname(targetPeer)}.",
                        )
                    }
                }
                true
            }
            "/w", "/who" -> {
                val peers = MeshStore.peers.value
                val summary = if (peers.isEmpty()) {
                    "No peers currently in BLE range. Scanning and advertising are active."
                } else {
                    "Reachable mesh peers (${peers.size}):\n" + peers.joinToString("\n") { p ->
                        val rssiText = p.rssi?.let { "$it dBm" } ?: "n/a"
                        val encText = if (p.sessionEstablished) "Noise XX" else "Announced"
                        "• @${p.nickname} (${p.peerKeyHex.take(8)}…) — ${p.hops} hop(s), $rssiText, $encText"
                    }
                }
                appendSystemNotice(activeConv, summary)
                true
            }
            "/channels" -> {
                val joined = MeshStore.joinedChannels.value.sorted()
                val bookmarked = MeshStore.bookmarkedChannels.value.sorted()
                val text = buildString {
                    append("Joined channels (${joined.size}): ${joined.joinToString(", ")}")
                    if (bookmarked.isNotEmpty()) {
                        append("\nBookmarked (${bookmarked.size}): ${bookmarked.joinToString(", ")}")
                    }
                }
                appendSystemNotice(activeConv, text)
                true
            }
            "/nick" -> {
                val ctx = appContext
                if (ctx != null && !allArgs.isBlank()) {
                    updateNickname(ctx, allArgs)
                    appendSystemNotice(activeConv, "Nickname updated to @${MeshStore.nickname.value}")
                } else {
                    appendSystemNotice(activeConv, "Usage: /nick <new_nickname>")
                }
                true
            }
            "/verify" -> {
                val targetPeer = if (!arg1.isNullOrBlank()) {
                    resolveTargetPeerHex(arg1)
                } else if (!activeConv.startsWith("#")) {
                    activeConv
                } else {
                    null
                }
                if (targetPeer == null) {
                    appendSystemNotice(activeConv, "Usage: /verify @nickname (or run inside a direct message thread)")
                } else {
                    sendVerificationChallenge(targetPeer)
                }
                true
            }
            "/route" -> {
                val targetPeer = if (!arg1.isNullOrBlank()) {
                    resolveTargetPeerHex(arg1)
                } else if (!activeConv.startsWith("#")) {
                    activeConv
                } else {
                    null
                }
                if (targetPeer == null) {
                    appendSystemNotice(activeConv, "Usage: /route @nickname")
                } else {
                    val localId = MeshStore.localIdentityHex.value.ifBlank { "local" }
                    val path = RoutePlanner.shortestPath(localId, targetPeer)
                    if (path == null) {
                        val peerObj = MeshStore.peers.value.firstOrNull { it.peerKeyHex.equals(targetPeer, ignoreCase = true) }
                        if (peerObj != null) {
                            appendSystemNotice(
                                activeConv,
                                "Direct/announced path to @${peerObj.nickname}: $localId → ${targetPeer.take(8)}… (${peerObj.hops} hop(s))",
                            )
                        } else {
                            appendSystemNotice(activeConv, "No active mesh route to ${targetPeer.take(8)}…")
                        }
                    } else {
                        val formatted = path.joinToString(" → ") { hop ->
                            if (hop.equals(localId, ignoreCase = true)) {
                                "you"
                            } else {
                                "@${MeshStore.resolvePeerNickname(hop)} (${hop.take(6)})"
                            }
                        }
                        appendSystemNotice(activeConv, "Shortest mesh route (${path.size - 1} hop(s)): $formatted")
                    }
                }
                true
            }
            "/fav" -> {
                val targetPeer = if (!arg1.isNullOrBlank()) {
                    resolveTargetPeerHex(arg1)
                } else if (!activeConv.startsWith("#")) {
                    activeConv
                } else {
                    null
                }
                if (targetPeer == null) {
                    appendSystemNotice(activeConv, "Usage: /fav @nickname")
                } else {
                    MeshStore.toggleFavoritePeer(targetPeer)
                    val isFav = targetPeer.lowercase(Locale.US) in MeshStore.favoritePeers.value
                    val h = handleRef.get()
                    if (h != 0L && CypherBridge.isLoaded) {
                        synchronized(engineLock) {
                            CypherBridge.nativeSetPeerFavorite(h, targetPeer.lowercase(Locale.US), isFav)
                        }
                    }
                    appendSystemNotice(
                        activeConv,
                        if (isFav) {
                            "Added @${MeshStore.resolvePeerNickname(targetPeer)} to favorites."
                        } else {
                            "Removed @${MeshStore.resolvePeerNickname(targetPeer)} from favorites."
                        },
                    )
                }
                true
            }
            "/block" -> {
                if (arg1.isNullOrBlank()) {
                    val blocked = MeshStore.blockedPeers.value
                    if (blocked.isEmpty()) {
                        appendSystemNotice(activeConv, "No blocked peers. Usage: /block @nickname")
                    } else {
                        appendSystemNotice(
                            activeConv,
                            "Blocked peers (${blocked.size}): " + blocked.joinToString(", ") {
                                "@${MeshStore.resolvePeerNickname(it)} (${it.take(8)})"
                            },
                        )
                    }
                } else {
                    val targetPeer = resolveTargetPeerHex(arg1) ?: arg1.removePrefix("@").lowercase(Locale.US)
                    if (targetPeer !in MeshStore.blockedPeers.value) {
                        MeshStore.toggleBlockPeer(targetPeer)
                    }
                    val h = handleRef.get()
                    if (h != 0L && CypherBridge.isLoaded) {
                        synchronized(engineLock) {
                            CypherBridge.nativeBlockPeer(h, targetPeer)
                        }
                    }
                    appendSystemNotice(activeConv, "Blocked peer @${MeshStore.resolvePeerNickname(targetPeer)}.")
                }
                true
            }
            "/unblock" -> {
                if (arg1.isNullOrBlank()) {
                    appendSystemNotice(activeConv, "Usage: /unblock @nickname")
                } else {
                    val targetPeer = resolveTargetPeerHex(arg1) ?: arg1.removePrefix("@").lowercase(Locale.US)
                    if (targetPeer in MeshStore.blockedPeers.value) {
                        MeshStore.toggleBlockPeer(targetPeer)
                        val h = handleRef.get()
                        if (h != 0L && CypherBridge.isLoaded) {
                            synchronized(engineLock) {
                                CypherBridge.nativeUnblockPeer(h, targetPeer)
                            }
                        }
                        appendSystemNotice(activeConv, "Unblocked peer @${MeshStore.resolvePeerNickname(targetPeer)}.")
                    } else {
                        appendSystemNotice(activeConv, "Peer '$arg1' is not blocked.")
                    }
                }
                true
            }
            "/hug" -> {
                val target = allArgs.removePrefix("@").ifBlank { "everyone" }
                sendMessage("* @${MeshStore.nickname.value} gives @$target a warm mesh hug 🤗 *")
                true
            }
            "/slap" -> {
                val target = allArgs.removePrefix("@").ifBlank { "everyone" }
                sendMessage("* @${MeshStore.nickname.value} slaps @$target around a bit with a large trout 🐟 *")
                true
            }
            "/shrug" -> {
                val msg = if (allArgs.isBlank()) "¯\\_(ツ)_/¯" else "$allArgs ¯\\_(ツ)_/¯"
                sendMessage(msg)
                true
            }
            "/clear" -> {
                MeshStore.clearConversation(activeConv)
                true
            }
            "/ping" -> {
                pingAll()
                appendSystemNotice(activeConv, "Broadcasted BLE mesh ping frame.")
                true
            }
            "/announce" -> {
                announceNow()
                appendSystemNotice(activeConv, "Broadcasted local identity presence announcement.")
                true
            }
            "/help" -> {
                val helpLines = SLASH_COMMANDS.joinToString("\n") { spec ->
                    "• ${spec.syntax} — ${spec.description}"
                }
                appendSystemNotice(activeConv, "Available Commands:\n$helpLines")
                true
            }
            else -> false
        }
    }

    private fun resolveTargetPeerHex(token: String?): String? {
        if (token.isNullOrBlank()) return null
        val cleaned = token.trim().removePrefix("@").lowercase(Locale.US)
        MeshStore.peers.value.firstOrNull {
            it.peerKeyHex.equals(cleaned, ignoreCase = true) ||
                it.peerKeyHex.startsWith(cleaned, ignoreCase = true) ||
                it.nickname.equals(cleaned, ignoreCase = true)
        }?.let { return it.peerKeyHex.lowercase(Locale.US) }

        MeshStore.knownPeers.value.values.firstOrNull {
            it.peerKeyHex.equals(cleaned, ignoreCase = true) ||
                it.peerKeyHex.startsWith(cleaned, ignoreCase = true) ||
                it.nickname.equals(cleaned, ignoreCase = true) ||
                it.customAlias.equals(cleaned, ignoreCase = true)
        }?.let { return it.peerKeyHex.lowercase(Locale.US) }

        if (cleaned.length >= 8 && cleaned.all { it in '0'..'9' || it in 'a'..'f' }) {
            return cleaned
        }
        return null
    }

    private fun appendSystemNotice(conversationKey: String, notice: String) {
        MeshStore.appendMessage(
            ChatMessage(
                id = UUID.randomUUID().toString(),
                conversationKey = conversationKey,
                senderHex = "system",
                senderLabel = "system",
                text = notice,
                timestampMs = System.currentTimeMillis(),
                outgoing = true,
                encrypted = false,
                hops = 0,
                delivery = DeliveryStatus.SENT,
                kind = MessageKind.SYSTEM,
            ),
        )
    }

    fun pingAll() {
        val h = handleRef.get()
        if (h == 0L || !CypherBridge.isLoaded) return
        val peers = MeshStore.peers.value
        if (peers.isEmpty()) {
            announceNow()
            return
        }
        peers.forEach { p ->
            val resp = synchronized(engineLock) {
                CypherBridge.nativeSendMeshPing(h, p.peerKeyHex)
            }
            MeshStore.recordTraffic(packets = 1, bytes = 64L)
            processNativeResponse(resp)
        }
    }

    fun announceNow() {
        val h = handleRef.get()
        if (h == 0L || !CypherBridge.isLoaded) return
        val resp = synchronized(engineLock) {
            CypherBridge.nativeAnnounce(h)
        }
        MeshStore.recordTraffic(packets = 1, bytes = 96L)
        processNativeResponse(resp)
    }

    private fun refreshEngineState() {
        val h = handleRef.get()
        if (h == 0L || !CypherBridge.isLoaded) return
        val jsonBytes = synchronized(engineLock) {
            CypherBridge.nativeStateJson(h)
        } ?: return
        val jsonStr = jsonBytes.toString(Charsets.UTF_8)
        ingestEngineStateJson(jsonStr)
    }

    /**
     * Parses the JSON snapshot returned by `CypherBridge.nativeStateJson`:
     * `{ "peerId": "...", "fingerprint": "...", "nickname": "...", "peers": [ { "peerId": "...", "nickname": "...", "connected": true, "verified": false, "favorite": false, "secureSession": false, "lastSeenMs": ..., "noisePublicKey": "...", "signingPublicKey": "..." } ] }`
     * Also supports legacy keys (`identity_hex`, `peer_key`, `session_established`) for full compatibility.
     */
    internal fun ingestEngineStateJson(jsonStr: String) {
        runCatching {
            val root = JSONObject(jsonStr)
            val idHex = root.optString(
                "peerId",
                root.optString("identity_hex", root.optString("my_peer_id", "")),
            )
            val fpHex = root.optString(
                "fingerprint",
                root.optString("fingerprint_hex", root.optString("my_fingerprint", "")),
            )
            if (idHex.isNotEmpty()) {
                MeshStore.setLocalIdentity(idHex, fpHex)
            }
            val latestRadioRssi = BleLinkLayer.active?.latestRssi?.value
            val peersArr = root.optJSONArray("peers") ?: JSONArray()
            val list = ArrayList<PeerEntry>(peersArr.length())
            for (i in 0 until peersArr.length()) {
                val p = peersArr.getJSONObject(i)
                val peerKey = p.optString(
                    "peerId",
                    p.optString("peer_key", p.optString("id", "")),
                ).lowercase(Locale.US)
                if (peerKey.isBlank()) continue

                val rawNick = p.optString("nickname", "").trim()
                val nick = if (rawNick.isNotBlank() && !PeerIdentity.isLegacyAnonNickname(rawNick)) {
                    rawNick
                } else {
                    MeshStore.resolvePeerNickname(peerKey)
                }

                val noisePub = p.optString("noisePublicKey", "")
                val signingPub = p.optString("signingPublicKey", "")
                val explicitFp = p.optString("fingerprint", p.optString("fingerprint_hex", ""))
                val peerFp = when {
                    explicitFp.isNotBlank() -> explicitFp
                    noisePub.isNotBlank() -> PeerIdentity.fingerprintHex(peerKey, noisePub)
                    signingPub.isNotBlank() -> PeerIdentity.fingerprintHex(peerKey, signingPub)
                    else -> ""
                }

                val routeArr = p.optJSONArray("route_path")
                val routeList = if (routeArr != null) {
                    List(routeArr.length()) { idx -> routeArr.optString(idx, "") }.filter { it.isNotBlank() }
                } else {
                    emptyList()
                }
                val connected = p.optBoolean("connected", p.optBoolean("is_connected", false))
                val sessionEstablished = p.optBoolean(
                    "secureSession",
                    p.optBoolean("session_established", p.optBoolean("has_noise_session", false)),
                )
                val verified = p.optBoolean("verified", false)
                val lastSeenMs = p.optLong(
                    "lastSeenMs",
                    p.optLong("last_seen_ms", System.currentTimeMillis()),
                )
                val rssi = when {
                    !p.isNull("rssi") && p.has("rssi") -> p.optInt("rssi")
                    connected -> latestRadioRssi
                    else -> null
                }

                val entry = PeerEntry(
                    peerKeyHex = peerKey,
                    nickname = nick,
                    connected = connected,
                    sessionEstablished = sessionEstablished,
                    rssi = rssi,
                    hops = p.optInt("hops", 1).coerceIn(1, 7),
                    lastSeenMs = if (lastSeenMs > 0L) lastSeenMs else System.currentTimeMillis(),
                    fingerprintHex = peerFp,
                    verified = verified,
                    routePath = routeList,
                )
                list.add(entry)
                if (entry.connected) {
                    flushOutboxForPeer(entry.peerKeyHex)
                }
            }
            MeshStore.updatePeers(list)
        }.onFailure { e ->
            Log.w(TAG, "Failed parsing engine state json", e)
        }
    }

    private fun processNativeResponse(blob: ByteArray?) {
        if (blob == null || blob.isEmpty()) return
        val parsed = CypherBridge.parseResponse(blob)
        if (parsed.commands.isNotEmpty()) {
            MeshStore.recordTraffic(
                packets = parsed.commands.size,
                bytes = parsed.commands.sumOf { it.size }.toLong(),
            )
            bleLayer?.executeCommands(parsed.commands)
        }
        if (parsed.eventsJson != "[]" && parsed.eventsJson.isNotBlank()) {
            parseEngineEvents(parsed.eventsJson)
        }
    }

    internal fun parseEngineEvents(eventsJson: String) {
        var shouldRefreshPeers = false
        runCatching {
            val arr = JSONArray(eventsJson)
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val typeTag = item.optString("type", "")
                when {
                    item.has("PublicMessageReceived") || typeTag == "PublicMessageReceived" -> {
                        val ev = item.optJSONObject("PublicMessageReceived") ?: item
                        val sender = ev.optString("sender", "").lowercase(Locale.US)
                        val rawNick = ev.optString(
                            "nickname",
                            ev.optString("sender_nickname", ""),
                        ).trim()
                        // Rust EngineEvent::PublicMessageReceived serializes message body under "content"
                        val rawText = ev.optString("content", ev.optString("text", ""))
                        val ts = ev.optLong("timestamp_ms", ev.optLong("timestamp", System.currentTimeMillis()))
                        val msgId = ev.optString("message_id", "")
                        val isRelayed = ev.optBoolean("is_relayed", false)
                        val hops = if (ev.has("hops")) {
                            ev.optInt("hops", 1).coerceIn(1, 7)
                        } else if (isRelayed) {
                            2
                        } else {
                            1
                        }

                        // Skip self-echo if our own broadcast comes back from the engine
                        val localId = MeshStore.localIdentityHex.value.lowercase(Locale.US)
                        if (localId.isNotBlank() && sender == localId) {
                            continue
                        }

                        val nick = if (rawNick.isNotBlank() && !PeerIdentity.isLegacyAnonNickname(rawNick)) {
                            rawNick.removePrefix("@")
                        } else {
                            MeshStore.resolvePeerNickname(sender)
                        }

                        // Immediately record sender in MeshStore so they appear in People tab right away
                        if (sender.isNotBlank()) {
                            MeshStore.recordPeerSeenFromEvent(
                                peerKeyHex = sender,
                                nickname = nick,
                                connected = true,
                                hops = hops,
                            )
                            shouldRefreshPeers = true
                        }

                        if (rawText.startsWith("[CHANNEL_COMMIT:")) {
                            handleChannelCommitmentFrame(rawText)
                            continue
                        }

                        val (convKey, bodyText) = extractChannelPrefix(rawText)
                        val password = MeshStore.getChannelPassword(convKey)
                        val decryptedBody = if (!password.isNullOrBlank() && bodyText.startsWith(ChannelCrypto.ENCRYPTED_PREFIX)) {
                            ChannelCrypto.decryptChannelPayload(convKey, password, bodyText)
                                ?: "🔒 Encrypted channel message (set password with /pass <password>)"
                        } else if (bodyText.startsWith(ChannelCrypto.ENCRYPTED_PREFIX)) {
                            "🔒 Encrypted channel message (set password with /pass <password>)"
                        } else {
                            bodyText
                        }

                        val (replyQuote, cleanBody) = extractReplyQuote(decryptedBody)
                        val (kind, displayBody, meta, savedPath) = parseWireAttachment(cleanBody)
                        val resolvedId = msgId.ifBlank { "$sender-$ts-${rawText.hashCode()}" }

                        val msg = ChatMessage(
                            id = resolvedId,
                            conversationKey = convKey,
                            senderHex = sender,
                            senderLabel = nick,
                            text = displayBody,
                            timestampMs = ts,
                            outgoing = false,
                            encrypted = bodyText.startsWith(ChannelCrypto.ENCRYPTED_PREFIX),
                            hops = hops,
                            kind = kind,
                            attachmentPath = savedPath,
                            attachmentMeta = meta,
                            replyPreview = replyQuote,
                        )
                        MeshStore.appendMessage(msg)
                        maybePostIncomingNotification(msg)
                    }

                    item.has("NoisePayloadReceived") || typeTag == "NoisePayloadReceived" -> {
                        val ev = item.optJSONObject("NoisePayloadReceived") ?: item
                        val peer = ev.optString("peer", ev.optString("sender", "")).lowercase(Locale.US)
                        val payloadType = ev.optInt("payload_type", -1)
                        val payloadArr = ev.optJSONArray("payload")
                        val bytes = if (payloadArr != null) {
                            ByteArray(payloadArr.length()) { idx -> payloadArr.optInt(idx).toByte() }
                        } else {
                            ByteArray(0)
                        }

                        if (peer.isNotBlank()) {
                            MeshStore.recordPeerSeenFromEvent(
                                peerKeyHex = peer,
                                connected = true,
                                sessionEstablished = true,
                            )
                            shouldRefreshPeers = true
                        }

                        when (payloadType) {
                            0x01 -> {
                                // Private message TLV: [0x00, len, ...id..., 0x01, len, ...content...]
                                val decoded = decodePrivateMessage(bytes)
                                    ?: (UUID.randomUUID().toString() to bytes.toString(Charsets.UTF_8))
                                val (msgId, rawText) = decoded

                                // Acknowledge delivery immediately (0x03)
                                val h = handleRef.get()
                                if (h != 0L && CypherBridge.isLoaded && peer.isNotBlank()) {
                                    val ackResp = synchronized(engineLock) {
                                        CypherBridge.nativeSendDeliveryAck(h, peer, msgId)
                                    }
                                    processNativeResponse(ackResp)
                                    if (MeshStore.activeConversation.value.equals(peer, ignoreCase = true)) {
                                        val readResp = synchronized(engineLock) {
                                            CypherBridge.nativeSendReadReceipt(h, peer, msgId)
                                        }
                                        processNativeResponse(readResp)
                                    }
                                }

                                if (rawText.startsWith("[VERIFY_CHALLENGE:")) {
                                    handleIncomingVerifyChallenge(peer, rawText)
                                    continue
                                }
                                if (rawText.startsWith("[VERIFY_RESPONSE:")) {
                                    handleIncomingVerifyResponse(peer, rawText)
                                    continue
                                }

                                val senderNick = MeshStore.resolvePeerNickname(peer)
                                val (replyQuote, cleanBody) = extractReplyQuote(rawText)
                                val (kind, displayBody, meta, savedPath) = parseWireAttachment(cleanBody)
                                val msg = ChatMessage(
                                    id = msgId,
                                    conversationKey = peer,
                                    senderHex = peer,
                                    senderLabel = senderNick,
                                    text = displayBody,
                                    timestampMs = System.currentTimeMillis(),
                                    outgoing = false,
                                    encrypted = true,
                                    hops = 1,
                                    kind = kind,
                                    attachmentPath = savedPath,
                                    attachmentMeta = meta,
                                    replyPreview = replyQuote,
                                )
                                MeshStore.appendMessage(msg)
                                maybePostIncomingNotification(msg)
                            }
                            0x02 -> {
                                // Read receipt
                                val msgId = decodeReceiptMessageId(bytes)
                                if (msgId.isNotBlank()) {
                                    MeshStore.markMessageDelivered(msgId, DeliveryStatus.READ)
                                }
                            }
                            0x03 -> {
                                // Delivery ACK
                                val msgId = decodeReceiptMessageId(bytes)
                                if (msgId.isNotBlank()) {
                                    MeshStore.markMessageDelivered(msgId, DeliveryStatus.DELIVERED)
                                }
                            }
                            0x08 -> {
                                // Private voice burst frame
                                handleIncomingVoicePayload(peer, bytes, isPrivate = true)
                            }
                        }
                    }

                    item.has("PrivateMessageReceived") -> {
                        val ev = item.getJSONObject("PrivateMessageReceived")
                        val sender = ev.optString("sender", "").lowercase(Locale.US)
                        val rawText = ev.optString("content", ev.optString("text", ""))
                        val ts = ev.optLong("timestamp_ms", System.currentTimeMillis())
                        val msgId = ev.optString("message_id", "$sender-$ts-${rawText.hashCode()}")
                        val explicitNick = ev.optString("nickname", ev.optString("sender_nickname", "")).trim()

                        if (sender.isNotBlank()) {
                            MeshStore.recordPeerSeenFromEvent(
                                peerKeyHex = sender,
                                nickname = explicitNick.takeIf { it.isNotBlank() },
                                connected = true,
                                sessionEstablished = true,
                            )
                            shouldRefreshPeers = true
                        }

                        if (rawText.startsWith("[VERIFY_CHALLENGE:")) {
                            handleIncomingVerifyChallenge(sender, rawText)
                            continue
                        }
                        if (rawText.startsWith("[VERIFY_RESPONSE:")) {
                            handleIncomingVerifyResponse(sender, rawText)
                            continue
                        }

                        val senderNick = explicitNick.ifEmpty { MeshStore.resolvePeerNickname(sender) }
                        val (replyQuote, cleanBody) = extractReplyQuote(rawText)
                        val (kind, displayBody, meta, savedPath) = parseWireAttachment(cleanBody)
                        val msg = ChatMessage(
                            id = msgId,
                            conversationKey = sender,
                            senderHex = sender,
                            senderLabel = senderNick,
                            text = displayBody,
                            timestampMs = ts,
                            outgoing = false,
                            encrypted = true,
                            hops = 1,
                            kind = kind,
                            attachmentPath = savedPath,
                            attachmentMeta = meta,
                            replyPreview = replyQuote,
                        )
                        MeshStore.appendMessage(msg)
                        maybePostIncomingNotification(msg)
                    }

                    item.has("CourierEnvelopeOpened") -> {
                        val ev = item.getJSONObject("CourierEnvelopeOpened")
                        val msgObj = ev.optJSONObject("message") ?: ev
                        val msgId = msgObj.optString("id", UUID.randomUUID().toString())
                        val sender = msgObj.optString("sender", ev.optString("sender", "")).lowercase(Locale.US)
                        val content = msgObj.optString("content", msgObj.optString("text", ""))
                        val ts = msgObj.optLong("timestamp_ms", System.currentTimeMillis())
                        val convKey = if (sender.isNotBlank()) sender else MeshStore.PUBLIC_CONVERSATION
                        if (sender.isNotBlank()) {
                            MeshStore.recordPeerSeenFromEvent(peerKeyHex = sender, connected = true)
                            shouldRefreshPeers = true
                        }
                        val senderNick = if (sender.isNotBlank()) MeshStore.resolvePeerNickname(sender) else "courier"
                        val (replyQuote, cleanBody) = extractReplyQuote(content)
                        val (kind, displayBody, meta, savedPath) = parseWireAttachment(cleanBody)
                        val msg = ChatMessage(
                            id = msgId,
                            conversationKey = convKey,
                            senderHex = sender.ifEmpty { "courier" },
                            senderLabel = senderNick,
                            text = displayBody,
                            timestampMs = ts,
                            outgoing = false,
                            encrypted = true,
                            hops = 2,
                            kind = kind,
                            attachmentPath = savedPath,
                            attachmentMeta = meta,
                            replyPreview = replyQuote,
                        )
                        MeshStore.appendMessage(msg)
                        maybePostIncomingNotification(msg)
                    }

                    item.has("FileTransferReceived") || typeTag == "FileTransferReceived" -> {
                        val ev = item.optJSONObject("FileTransferReceived") ?: item
                        val sender = ev.optString("sender", "").lowercase(Locale.US)
                        val isPrivate = ev.optBoolean("is_private", ev.optBoolean("encrypted", false))
                        val fileObj = ev.optJSONObject("file") ?: ev
                        val transferId = ev.optString("transfer_id", fileObj.optString("transfer_id", ""))
                        val fileName = fileObj.optString("file_name", "attachment.bin")
                        val mime = fileObj.optString("mime_type", "application/octet-stream")
                        val contentArr = fileObj.optJSONArray("content")
                        val bytes = when {
                            contentArr != null -> ByteArray(contentArr.length()) { idx -> contentArr.optInt(idx).toByte() }
                            fileObj.has("content_b64") -> runCatching {
                                Base64.decode(fileObj.optString("content_b64", ""), Base64.NO_WRAP)
                            }.getOrDefault(ByteArray(0))
                            else -> ByteArray(0)
                        }

                        if (sender.isNotBlank()) {
                            MeshStore.recordPeerSeenFromEvent(
                                peerKeyHex = sender,
                                connected = true,
                                sessionEstablished = isPrivate,
                            )
                            shouldRefreshPeers = true
                        }

                        val savedPath = saveIncomingBinaryFile(fileName, bytes)
                        val lowerName = fileName.lowercase(Locale.US)
                        val kind = when {
                            mime.startsWith("image/", ignoreCase = true) ||
                                lowerName.endsWith(".jpg") ||
                                lowerName.endsWith(".jpeg") ||
                                lowerName.endsWith(".png") ||
                                lowerName.endsWith(".webp") ||
                                lowerName.endsWith(".gif") -> MessageKind.IMAGE
                            mime.startsWith("audio/", ignoreCase = true) ||
                                lowerName.endsWith(".m4a") ||
                                lowerName.endsWith(".aac") ||
                                lowerName.endsWith(".ogg") ||
                                lowerName.endsWith(".wav") ||
                                lowerName.endsWith(".mp3") -> MessageKind.VOICE_NOTE
                            else -> MessageKind.FILE
                        }
                        val sizeStr = formatByteCount(bytes.size.toLong().coerceAtLeast(fileObj.optLong("file_size", 0L)))
                        val displayTitle = when (kind) {
                            MessageKind.IMAGE -> "Photo ($fileName)"
                            MessageKind.VOICE_NOTE -> "Voice message ($sizeStr)"
                            else -> "File: $fileName ($sizeStr)"
                        }
                        val convKey = if (isPrivate && sender.isNotBlank()) sender else MeshStore.PUBLIC_CONVERSATION
                        val senderNick = if (sender.isNotBlank()) MeshStore.resolvePeerNickname(sender) else "peer"
                        val resolvedId = transferId.ifBlank {
                            "file-$sender-${System.currentTimeMillis()}-${fileName.hashCode()}"
                        }

                        val msg = ChatMessage(
                            id = resolvedId,
                            conversationKey = convKey,
                            senderHex = sender,
                            senderLabel = senderNick,
                            text = displayTitle,
                            timestampMs = System.currentTimeMillis(),
                            outgoing = false,
                            encrypted = isPrivate,
                            hops = 1,
                            kind = kind,
                            attachmentPath = savedPath,
                            attachmentMeta = "$fileName • $sizeStr",
                        )
                        MeshStore.appendMessage(msg)
                        maybePostIncomingNotification(msg)
                    }

                    item.has("PublicVoiceFrameReceived") -> {
                        val ev = item.getJSONObject("PublicVoiceFrameReceived")
                        val peer = ev.optString("peer", "").lowercase(Locale.US)
                        val burst = ev.optJSONObject("burst")
                        if (peer.isNotBlank()) {
                            MeshStore.recordPeerSeenFromEvent(peerKeyHex = peer, connected = true)
                            shouldRefreshPeers = true
                        }
                        if (burst != null) {
                            val payloadArr = burst.optJSONArray("payload")
                            val bytes = if (payloadArr != null) {
                                ByteArray(payloadArr.length()) { idx -> payloadArr.optInt(idx).toByte() }
                            } else {
                                ByteArray(0)
                            }
                            val flags = burst.optInt("flags", 0x03)
                            val burstIdArr = burst.optJSONArray("burst_id")
                            val burstKey = if (burstIdArr != null) {
                                buildString {
                                    for (k in 0 until burstIdArr.length()) {
                                        append("%02x".format(burstIdArr.optInt(k) and 0xFF))
                                    }
                                }
                            } else {
                                "$peer-voice"
                            }
                            val buf = voiceBurstBuffers.getOrPut(burstKey) { ByteArrayOutputStream() }
                            buf.write(bytes)
                            // Flag bit 0x02 = end of voice burst (or single-frame burst)
                            if ((flags and 0x02) != 0 || burst.optInt("seq", 0) == 0) {
                                val fullAudio = voiceBurstBuffers.remove(burstKey)?.toByteArray() ?: bytes
                                handleIncomingVoicePayload(peer, fullAudio, isPrivate = false)
                            }
                        }
                    }

                    item.has("MessageDeliveryStatusUpdated") -> {
                        val ev = item.getJSONObject("MessageDeliveryStatusUpdated")
                        val msgId = ev.optString("message_id", "")
                        val sending = ev.optBoolean("sending", false)
                        val failed = ev.optBoolean("failed", false)
                        if (msgId.isNotBlank()) {
                            val newStatus = when {
                                failed -> DeliveryStatus.QUEUED
                                sending -> DeliveryStatus.SENT
                                else -> DeliveryStatus.DELIVERED
                            }
                            MeshStore.markMessageDelivered(msgId, newStatus)
                        }
                    }

                    item.has("MeshPingResult") -> {
                        val ev = item.getJSONObject("MeshPingResult")
                        val peer = ev.optString("peer", "").lowercase(Locale.US)
                        val rtt = ev.optLong("rtt_ms", 0L)
                        val hops = if (ev.isNull("hops")) 1 else ev.optInt("hops", 1).coerceIn(1, 7)
                        if (peer.isNotBlank()) {
                            MeshStore.recordPeerSeenFromEvent(peerKeyHex = peer, connected = true, hops = hops)
                            shouldRefreshPeers = true
                            val nick = MeshStore.resolvePeerNickname(peer)
                            appendSystemNotice(
                                MeshStore.activeConversation.value,
                                "Mesh ping reply from @$nick (${peer.take(8)}…): ${rtt} ms • $hops hop(s)",
                            )
                        }
                    }

                    item.has("PeerConnected") -> {
                        val peerHex = extractPeerHexFromVariant(item.opt("PeerConnected"))
                        if (peerHex.isNotBlank()) {
                            MeshStore.recordPeerSeenFromEvent(peerKeyHex = peerHex, connected = true)
                            flushOutboxForPeer(peerHex)
                            shouldRefreshPeers = true
                        }
                    }

                    item.has("PeerDisconnected") -> {
                        shouldRefreshPeers = true
                    }

                    item.has("PeerListUpdated") -> {
                        val listArr = item.optJSONArray("PeerListUpdated")
                        if (listArr != null) {
                            for (k in 0 until listArr.length()) {
                                val peerHex = extractPeerHexFromVariant(listArr.opt(k))
                                if (peerHex.isNotBlank()) {
                                    MeshStore.recordPeerSeenFromEvent(peerKeyHex = peerHex, connected = true)
                                }
                            }
                        }
                        shouldRefreshPeers = true
                    }

                    item.has("SessionEstablished") -> {
                        val peerHex = extractPeerHexFromVariant(item.opt("SessionEstablished"))
                        if (peerHex.isNotBlank()) {
                            MeshStore.recordPeerSeenFromEvent(
                                peerKeyHex = peerHex,
                                connected = true,
                                sessionEstablished = true,
                            )
                            flushOutboxForPeer(peerHex)
                            shouldRefreshPeers = true
                        }
                    }

                    item.has("SessionRestored") || item.has("SessionFailed") || item.has("BluetoothStateChanged") -> {
                        shouldRefreshPeers = true
                    }

                    item.has("PeerUpdated") -> {
                        val ev = item.getJSONObject("PeerUpdated")
                        val peerHex = ev.optString("peer", "").lowercase(Locale.US)
                        val nick = ev.optString("nickname", "").takeIf { it.isNotBlank() }
                        val hops = ev.optInt("hops", 1).coerceIn(1, 7)
                        val rssi = if (ev.isNull("rssi")) null else ev.optInt("rssi")
                        val neighborsArr = ev.optJSONArray("neighbors")
                        val neighbors = if (neighborsArr != null) {
                            List(neighborsArr.length()) { idx -> neighborsArr.optString(idx, "") }
                                .filter { it.isNotBlank() }
                        } else {
                            null
                        }
                        if (peerHex.isNotBlank()) {
                            MeshStore.recordPeerSeenFromEvent(
                                peerKeyHex = peerHex,
                                nickname = nick,
                                connected = true,
                                hops = hops,
                                rssi = rssi,
                            )
                            MeshGraphService.updateFromAnnouncement(
                                originID = peerHex,
                                originNickname = nick,
                                neighbors = neighbors,
                                timestamp = System.currentTimeMillis().toULong(),
                                hops = hops,
                                rssi = rssi,
                            )
                        }
                    }
                }
            }
        }.onFailure { e ->
            Log.w(TAG, "Failed parsing engine events", e)
        }

        if (shouldRefreshPeers) {
            refreshEngineState()
        }
    }

    private fun extractPeerHexFromVariant(raw: Any?): String {
        return when (raw) {
            is String -> raw.lowercase(Locale.US)
            is JSONObject -> raw.optString("peer", raw.optString("peerId", "")).lowercase(Locale.US)
            else -> ""
        }
    }

    /**
     * Decodes a `PrivateMessagePacket` TLV (`0x00 len id · 0x01 len content`) emitted inside
     * `NoisePayloadReceived` (`payload_type == 0x01`).
     */
    internal fun decodePrivateMessage(bytes: ByteArray): Pair<String, String>? {
        var offset = if (bytes.size >= 3 && (bytes[0].toInt() and 0xFF) == 0x01 && (bytes[1].toInt() and 0xFF) == 0x00) {
            1
        } else {
            0
        }
        var id: String? = null
        var content: String? = null
        while (offset + 2 <= bytes.size) {
            val tag = bytes[offset].toInt() and 0xFF
            val len = bytes[offset + 1].toInt() and 0xFF
            offset += 2
            if (offset + len > bytes.size) return null
            val slice = String(bytes, offset, len, Charsets.UTF_8)
            offset += len
            when (tag) {
                0x00 -> id = slice
                0x01 -> content = slice
            }
        }
        return if (id != null && content != null) id to content else null
    }

    /**
     * Decodes a read receipt (`0x02`) or delivery ACK (`0x03`) message ID.
     */
    internal fun decodeReceiptMessageId(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 2) {
            val len = bytes[0].toInt() and 0xFF
            if (len in 1..(bytes.size - 1)) {
                return String(bytes, 1, len, Charsets.UTF_8)
            }
        }
        return String(bytes, Charsets.UTF_8).trim()
    }

    private fun saveIncomingBinaryFile(fileName: String, bytes: ByteArray): String? {
        val ctx = appContext ?: return null
        if (bytes.isEmpty()) return null
        return runCatching {
            val dir = File(ctx.filesDir, "incoming").apply { mkdirs() }
            val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "attachment.bin" }
            val target = File(dir, "${System.currentTimeMillis()}_$safeName")
            target.writeBytes(bytes)
            target.absolutePath
        }.getOrNull()
    }

    private fun handleIncomingVoicePayload(peerHex: String, bytes: ByteArray, isPrivate: Boolean) {
        if (bytes.isEmpty()) return
        val savedPath = saveIncomingBinaryFile("voice_${System.currentTimeMillis()}.m4a", bytes)
        val convKey = if (isPrivate && peerHex.isNotBlank()) peerHex else MeshStore.PUBLIC_CONVERSATION
        val nick = if (peerHex.isNotBlank()) MeshStore.resolvePeerNickname(peerHex) else "peer"
        val estSec = (bytes.size / 1800L).coerceAtLeast(1L)
        val msg = ChatMessage(
            id = "voice-$peerHex-${System.currentTimeMillis()}",
            conversationKey = convKey,
            senderHex = peerHex,
            senderLabel = nick,
            text = "Voice message (${formatDuration(estSec)})",
            timestampMs = System.currentTimeMillis(),
            outgoing = false,
            encrypted = isPrivate,
            hops = 1,
            kind = MessageKind.VOICE_NOTE,
            attachmentPath = savedPath,
            attachmentMeta = "${estSec}s",
        )
        MeshStore.appendMessage(msg)
        maybePostIncomingNotification(msg)
    }

    private fun extractReplyQuote(bodyText: String): Pair<String?, String> {
        if (bodyText.startsWith("> ")) {
            val newlineIdx = bodyText.indexOf('\n')
            if (newlineIdx > 2 && newlineIdx < bodyText.length - 1) {
                val quote = bodyText.substring(2, newlineIdx).trim()
                val rest = bodyText.substring(newlineIdx + 1)
                return quote to rest
            }
        }
        return null to bodyText
    }

    private fun handleChannelCommitmentFrame(rawText: String) {
        val inner = rawText.removePrefix("[CHANNEL_COMMIT:").removeSuffix("]")
        val parts = inner.split(":")
        if (parts.size >= 2) {
            val tag = Geohash.normalizeTag(parts[0])
            val commitmentHex = parts[1]
            val ownerHex = parts.getOrNull(2)
            MeshStore.registerChannelCommitment(tag, commitmentHex, ownerHex)
        }
    }

    private fun handleIncomingVerifyChallenge(senderHex: String, rawText: String) {
        val inner = rawText.removePrefix("[VERIFY_CHALLENGE:").removeSuffix("]")
        val parts = inner.split(":")
        val nonce = parts.getOrNull(0).orEmpty()
        val challengerFp = parts.getOrNull(1).orEmpty()
        val localFp = MeshStore.localFingerprintHex.value
        if (nonce.isBlank() || challengerFp.isBlank() || localFp.isBlank()) return

        val responseFrame = VerificationService.buildResponseFrame(
            nonceHex = nonce,
            responderFingerprintHex = localFp,
            challengerFingerprintHex = challengerFp,
        )
        val h = handleRef.get()
        if (h != 0L && CypherBridge.isLoaded) {
            val msgId = UUID.randomUUID().toString()
            val resp = synchronized(engineLock) {
                CypherBridge.nativeSendPrivateMessage(h, senderHex, responseFrame, msgId)
            }
            processNativeResponse(resp)
        }
    }

    private fun handleIncomingVerifyResponse(senderHex: String, rawText: String) {
        val inner = rawText.removePrefix("[VERIFY_RESPONSE:").removeSuffix("]")
        val parts = inner.split(":")
        val nonce = parts.getOrNull(0).orEmpty()
        val responderFp = parts.getOrNull(1).orEmpty()
        val digest = parts.getOrNull(2).orEmpty()
        val expectedPeer = pendingChallenges.remove(nonce) ?: return
        if (!expectedPeer.equals(senderHex, ignoreCase = true)) return

        val localFp = MeshStore.localFingerprintHex.value
        val expectedDigest = VerificationService.computeChallengeDigestHex(
            nonceHex = nonce,
            responderFingerprintHex = responderFp,
            challengerFingerprintHex = localFp,
        )
        if (expectedDigest.equals(digest, ignoreCase = true)) {
            MeshStore.markPeerVerified(
                peerHex = senderHex,
                fingerprintHex = responderFp,
                nickname = MeshStore.resolvePeerNickname(senderHex),
            )
            val h = handleRef.get()
            if (h != 0L && CypherBridge.isLoaded) {
                synchronized(engineLock) {
                    CypherBridge.nativeSetPeerVerified(h, senderHex, true)
                }
            }
            appendSystemNotice(
                senderHex,
                "Cryptographic challenge-response verified for @${MeshStore.resolvePeerNickname(senderHex)}.",
            )
        }
    }

    private data class ParsedWireAttachment(
        val kind: MessageKind,
        val displayBody: String,
        val meta: String?,
        val savedPath: String?,
    )

    private fun parseWireAttachment(bodyText: String): ParsedWireAttachment {
        return when {
            bodyText.startsWith("[VOICE_NOTE:") && bodyText.endsWith("]") -> {
                val inner = bodyText.removePrefix("[VOICE_NOTE:").removeSuffix("]")
                val parts = inner.split(":", limit = 3)
                val dur = parts.firstOrNull() ?: "1s"
                val fileName = parts.getOrNull(1) ?: "voice_${System.currentTimeMillis()}.m4a"
                val audioB64 = parts.getOrNull(2).orEmpty()
                val savedFile = if (audioB64.isNotBlank() && appContext != null) {
                    runCatching {
                        val dir = File(appContext!!.filesDir, "incoming").apply { mkdirs() }
                        val outFile = File(dir, fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_"))
                        outFile.writeBytes(Base64.decode(audioB64, Base64.NO_WRAP))
                        outFile.absolutePath
                    }.getOrNull()
                } else {
                    null
                }
                ParsedWireAttachment(MessageKind.VOICE_NOTE, "Voice message ($dur)", dur, savedFile)
            }
            bodyText.startsWith("[IMAGE:") && bodyText.endsWith("]") -> {
                val inner = bodyText.removePrefix("[IMAGE:").removeSuffix("]")
                val parts = inner.split(":", limit = 3)
                val name = parts.getOrNull(0) ?: "photo.jpg"
                val size = parts.getOrNull(1) ?: ""
                val imgB64 = parts.getOrNull(2).orEmpty()
                val savedFile = if (imgB64.isNotBlank() && appContext != null) {
                    runCatching {
                        val dir = File(appContext!!.filesDir, "incoming").apply { mkdirs() }
                        val outFile = File(dir, "${System.currentTimeMillis()}_${name.replace(Regex("[^a-zA-Z0-9._-]"), "_")}")
                        outFile.writeBytes(Base64.decode(imgB64, Base64.NO_WRAP))
                        outFile.absolutePath
                    }.getOrNull()
                } else {
                    null
                }
                val meta = if (size.isNotBlank()) "$name • $size" else name
                ParsedWireAttachment(MessageKind.IMAGE, "Photo ($name)", meta, savedFile)
            }
            bodyText.startsWith("[FILE:") && bodyText.endsWith("]") -> {
                val inner = bodyText.removePrefix("[FILE:").removeSuffix("]")
                val parts = inner.split(":")
                val name = parts.getOrNull(0) ?: "document.bin"
                val size = parts.getOrNull(1) ?: ""
                val meta = if (size.isNotBlank()) "$name • $size" else name
                ParsedWireAttachment(MessageKind.FILE, "File: $name ($size)", meta, null)
            }
            bodyText.startsWith("[LOCATION:") && bodyText.endsWith("]") -> {
                val inner = bodyText.removePrefix("[LOCATION:").removeSuffix("]")
                val parts = inner.split(":")
                val coords = parts.getOrNull(0) ?: inner
                val geohash = parts.getOrNull(1).orEmpty()
                val text = if (geohash.isNotBlank()) {
                    "Location (approx.)\n$coords\n$geohash"
                } else {
                    "Location (approx.)\n$coords"
                }
                ParsedWireAttachment(MessageKind.LOCATION, text, coords, null)
            }
            else -> ParsedWireAttachment(MessageKind.TEXT, bodyText, null, null)
        }
    }

    private fun extractChannelPrefix(rawText: String): Pair<String, String> {
        if (rawText.startsWith("[#")) {
            val closeIdx = rawText.indexOf(']')
            if (closeIdx in 2..25) {
                val tag = Geohash.normalizeTag(rawText.substring(1, closeIdx))
                val rest = rawText.substring(closeIdx + 1).trimStart()
                MeshStore.joinChannel(tag)
                return tag to rest
            }
        }
        return MeshStore.PUBLIC_CONVERSATION to rawText
    }

    /**
     * Copies an attachment into local storage. For images, downscales large photos to max 1080px
     * JPEG and names them `img_<uuid>.jpg` so they transfer quickly over BLE and qualify for
     * `PrivateMediaMessageIdentity` receipt tracking in `cypher-core`.
     */
    private fun copyAttachmentToLocalCache(
        context: Context,
        uri: Uri,
        isImage: Boolean,
    ): Triple<String, Long, File?> {
        var name = if (isImage) {
            "img_${UUID.randomUUID()}.jpg"
        } else {
            "file_${System.currentTimeMillis()}.bin"
        }
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (!isImage && nameIdx >= 0) {
                        cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { name = it }
                    }
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        size = cursor.getLong(sizeIdx)
                    }
                }
            }
        }
        val copied = runCatching {
            val dir = File(context.filesDir, "attachments").apply { mkdirs() }
            val safeName = if (isImage) {
                name
            } else {
                "${System.currentTimeMillis()}_${name.replace(Regex("[^a-zA-Z0-9._-]"), "_")}"
            }
            val dest = File(dir, safeName)
            val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (rawBytes != null) {
                if (isImage) {
                    val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                    if (bmp != null) {
                        val maxDim = max(bmp.width, bmp.height)
                        val scaled = if (maxDim > 1080) {
                            val ratio = 1080.0 / maxDim.toDouble()
                            val w = (bmp.width * ratio).roundToInt().coerceAtLeast(1)
                            val h = (bmp.height * ratio).roundToInt().coerceAtLeast(1)
                            Bitmap.createScaledBitmap(bmp, w, h, true)
                        } else {
                            bmp
                        }
                        dest.outputStream().use { out ->
                            scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
                        }
                    } else {
                        dest.writeBytes(rawBytes)
                    }
                } else {
                    dest.writeBytes(rawBytes)
                }
            }
            size = dest.length()
            dest
        }.getOrNull()
        return Triple(name, size.coerceAtLeast(512L), copied)
    }

    private fun encodeCompactImagePreviewB64(file: File): String {
        return runCatching {
            val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return ""
            val maxDim = max(bmp.width, bmp.height).coerceAtLeast(1)
            val ratio = (180.0 / maxDim.toDouble()).coerceAtMost(1.0)
            val w = (bmp.width * ratio).roundToInt().coerceAtLeast(1)
            val h = (bmp.height * ratio).roundToInt().coerceAtLeast(1)
            val thumb = Bitmap.createScaledBitmap(bmp, w, h, true)
            val out = ByteArrayOutputStream()
            thumb.compress(Bitmap.CompressFormat.JPEG, 65, out)
            val bytes = out.toByteArray()
            if (bytes.size <= 16_384) Base64.encodeToString(bytes, Base64.NO_WRAP) else ""
        }.getOrDefault("")
    }

    private fun formatByteCount(bytes: Long): String {
        return when {
            bytes >= 1_048_576L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
            bytes >= 1_024L -> String.format(Locale.US, "%.1f KB", bytes / 1_024.0)
            else -> "$bytes B"
        }
    }

    private fun formatDuration(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return "%d:%02d".format(m, s)
    }

    private fun maybePostIncomingNotification(message: ChatMessage) {
        val ctx = appContext ?: return
        if (!MeshStore.notificationsEnabled.value) return
        if (message.conversationKey in MeshStore.mutedConversations.value) return
        val localNick = MeshStore.nickname.value
        val isMention = localNick.isNotBlank() && message.text.contains("@$localNick", ignoreCase = true)
        if (isMention && !MeshStore.mentionAlertsEnabled.value) return

        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val chan = NotificationChannel(
                    MSG_NOTIFICATION_CHANNEL,
                    "Cipher Mesh Messages",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Offline encrypted mesh message alerts"
                    enableVibration(MeshStore.soundVibrationEnabled.value)
                }
                nm.createNotificationChannel(chan)
            }
            val tapIntent = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = PendingIntent.getActivity(
                ctx,
                message.conversationKey.hashCode(),
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val title = when {
                message.conversationKey == MeshStore.PUBLIC_CONVERSATION -> "Mesh Group • @${message.senderLabel}"
                message.conversationKey.startsWith("#") -> "${message.conversationKey} • @${message.senderLabel}"
                else -> "@${message.senderLabel}"
            }
            val notif = NotificationCompat.Builder(ctx, MSG_NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_cypher)
                .setContentTitle(title)
                .setContentText(message.text)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            nm.notify(message.id.hashCode(), notif)
        }
    }

    fun exportConversationTranscript(conversationKey: String): String {
        val messages = MeshStore.conversations.value[conversationKey].orEmpty()
        val header = buildString {
            appendLine("=== Cipher Offline Mesh Transcript ===")
            appendLine("Conversation: ${resolveConversationTitle(conversationKey)}")
            appendLine("Exported at: ${java.util.Date()}")
            appendLine("Messages: ${messages.size}")
            appendLine("======================================")
        }
        if (messages.isEmpty()) return "$header\n(No messages)"
        val body = messages.joinToString("\n") { msg ->
            val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(java.util.Date(msg.timestampMs))
            val lock = if (msg.encrypted) "[E2EE]" else "[MESH]"
            "[$time] $lock ${msg.senderLabel}: ${msg.text}"
        }
        return "$header\n$body"
    }

    fun resolveConversationTitle(conversationKey: String): String {
        if (conversationKey == MeshStore.PUBLIC_CONVERSATION) return "Mesh Group"
        if (conversationKey.startsWith("#")) {
            val raw = conversationKey.removePrefix("#")
            if (Geohash.isValid(raw)) {
                val level = GeohashChannelLevel.entries.firstOrNull { it.precision == raw.length }
                return if (level != null) {
                    "${level.displayName} #$raw"
                } else {
                    "#$raw"
                }
            }
            return conversationKey
        }
        return MeshStore.resolvePeerNickname(conversationKey)
    }
}
