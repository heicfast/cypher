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
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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

    fun rotateIdentity(context: Context) {
        val appCtx = context.applicationContext
        MeshStore.rotateIdentitySeed(appCtx)
        val nick = MeshStore.nickname.value
        if (CypherBridge.isLoaded) {
            val newHandle = synchronized(engineLock) {
                MeshRuntime.panicReset(appCtx, nick)
            }
            handleRef.set(newHandle)
            refreshEngineState()
            announceNow()
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

            if (h != 0L && CypherBridge.isLoaded) {
                val resp = synchronized(engineLock) {
                    CypherBridge.nativeSendPublicMessage(h, payloadWithChannel)
                }
                MeshStore.recordTraffic(packets = 1, bytes = payloadWithChannel.length.toLong())
                processNativeResponse(resp)
            }
            val msgId = UUID.randomUUID().toString()
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

        if (peerOnline && h != 0L && CypherBridge.isLoaded) {
            val resp = synchronized(engineLock) {
                CypherBridge.nativeSendPrivateMessage(h, normPeer, wirePayload, msgId)
            }
            MeshStore.recordTraffic(packets = 1, bytes = wirePayload.length.toLong())
            processNativeResponse(resp)
        } else if (MeshStore.storeAndForwardEnabled.value) {
            MeshStore.enqueueOutboxMessage(
                messageId = msgId,
                recipientHex = normPeer,
                payloadText = wirePayload,
            )
        }

        val initialStatus = when {
            peerOnline -> DeliveryStatus.DELIVERED
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
        val audioPayloadB64 = runCatching {
            val bytes = file.readBytes()
            if (bytes.size <= 24_576) {
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            } else {
                ""
            }
        }.getOrDefault("")

        val wireFrame = if (audioPayloadB64.isNotEmpty()) {
            "[VOICE_NOTE:${durationSec}s:${file.name}:$audioPayloadB64]"
        } else {
            "[VOICE_NOTE:${durationSec}s:${file.name}]"
        }

        if (activeConv.startsWith("#")) {
            val h = handleRef.get()
            val channelWire = if (activeConv == MeshStore.PUBLIC_CONVERSATION) {
                wireFrame
            } else {
                "[$activeConv] $wireFrame"
            }
            if (h != 0L && CypherBridge.isLoaded) {
                val resp = synchronized(engineLock) {
                    CypherBridge.nativeSendPublicMessage(h, channelWire)
                }
                MeshStore.recordTraffic(packets = 1, bytes = file.length())
                processNativeResponse(resp)
            }
            MeshStore.appendMessage(
                ChatMessage(
                    id = UUID.randomUUID().toString(),
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
        } else {
            sendPrivateMessageInternal(
                peerKeyHex = activeConv,
                displayText = "Voice message (${formatDuration(durationSec)})",
                wirePayload = wireFrame,
                kind = MessageKind.VOICE_NOTE,
                attachmentPath = file.absolutePath,
                attachmentMeta = "${durationSec}s",
            )
        }
    }

    fun sendMediaAttachment(context: Context, uri: Uri, isImage: Boolean) {
        val activeConv = MeshStore.activeConversation.value
        val now = System.currentTimeMillis()
        val (fileName, fileSize, localFile) = copyAttachmentToLocalCache(context, uri, isImage)
        val kind = if (isImage) MessageKind.IMAGE else MessageKind.FILE
        val sizeLabel = formatByteCount(fileSize)
        val displayTitle = if (isImage) "Photo ($fileName)" else "File: $fileName ($sizeLabel)"
        val wireHeader = if (isImage) "[IMAGE:$fileName:$sizeLabel]" else "[FILE:$fileName:$sizeLabel]"
        val mimeType = if (isImage) "image/jpeg" else "application/octet-stream"

        if (activeConv.startsWith("#")) {
            val h = handleRef.get()
            val payload = if (activeConv == MeshStore.PUBLIC_CONVERSATION) {
                wireHeader
            } else {
                "[$activeConv] $wireHeader"
            }
            if (h != 0L && CypherBridge.isLoaded) {
                val resp = synchronized(engineLock) {
                    val bytes = localFile?.takeIf { it.length() <= 32_768L }?.readBytes()
                    if (bytes != null && activeConv == MeshStore.PUBLIC_CONVERSATION) {
                        CypherBridge.nativeSendFileBroadcast(h, fileName, mimeType, bytes)
                    } else {
                        CypherBridge.nativeSendPublicMessage(h, payload)
                    }
                }
                MeshStore.recordTraffic(packets = 1, bytes = fileSize.coerceAtLeast(256L))
                processNativeResponse(resp)
            }
            MeshStore.appendMessage(
                ChatMessage(
                    id = UUID.randomUUID().toString(),
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
        } else {
            val h = handleRef.get()
            val bytes = localFile?.takeIf { it.length() <= 32_768L }?.readBytes()
            if (h != 0L && CypherBridge.isLoaded && bytes != null) {
                val transferId = UUID.randomUUID().toString()
                val resp = synchronized(engineLock) {
                    CypherBridge.nativeSendFilePrivate(h, activeConv.lowercase(Locale.US), fileName, mimeType, bytes, transferId)
                }
                processNativeResponse(resp)
            }
            sendPrivateMessageInternal(
                peerKeyHex = activeConv,
                displayText = displayTitle,
                wirePayload = wireHeader,
                kind = kind,
                attachmentPath = localFile?.absolutePath ?: uri.toString(),
                attachmentMeta = "$fileName • $sizeLabel",
            )
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
            if (h != 0L && CypherBridge.isLoaded) {
                val resp = synchronized(engineLock) {
                    CypherBridge.nativeSendPublicMessage(h, payload)
                }
                MeshStore.recordTraffic(packets = 1, bytes = payload.length.toLong())
                processNativeResponse(resp)
            }
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
        runCatching {
            val root = JSONObject(jsonStr)
            val idHex = root.optString("identity_hex", root.optString("my_peer_id", ""))
            val fpHex = root.optString("fingerprint_hex", root.optString("my_fingerprint", ""))
            if (idHex.isNotEmpty()) {
                MeshStore.setLocalIdentity(idHex, fpHex)
            }
            val peersArr = root.optJSONArray("peers") ?: JSONArray()
            val list = ArrayList<PeerEntry>(peersArr.length())
            for (i in 0 until peersArr.length()) {
                val p = peersArr.getJSONObject(i)
                val peerKey = p.optString("peer_key", p.optString("id", "")).lowercase(Locale.US)
                if (peerKey.isBlank()) continue
                val nick = p.optString("nickname", "").ifEmpty { "anon#${peerKey.take(6)}" }
                val peerFp = p.optString("fingerprint_hex", p.optString("fingerprint", ""))
                val routeArr = p.optJSONArray("route_path")
                val routeList = if (routeArr != null) {
                    List(routeArr.length()) { idx -> routeArr.optString(idx, "") }.filter { it.isNotBlank() }
                } else {
                    emptyList()
                }
                val entry = PeerEntry(
                    peerKeyHex = peerKey,
                    nickname = nick,
                    connected = p.optBoolean("connected", p.optBoolean("is_connected", false)),
                    sessionEstablished = p.optBoolean("session_established", p.optBoolean("has_noise_session", false)),
                    rssi = if (p.isNull("rssi")) null else p.optInt("rssi"),
                    hops = p.optInt("hops", 1).coerceIn(1, 7),
                    fingerprintHex = peerFp,
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

    private fun parseEngineEvents(eventsJson: String) {
        runCatching {
            val arr = JSONArray(eventsJson)
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                when {
                    item.has("PublicMessageReceived") -> {
                        val ev = item.getJSONObject("PublicMessageReceived")
                        val sender = ev.optString("sender", "").lowercase(Locale.US)
                        val nick = ev.optString("sender_nickname", "").ifEmpty {
                            MeshStore.resolvePeerNickname(sender)
                        }
                        val rawText = ev.optString("text", "")
                        val ts = ev.optLong("timestamp_ms", System.currentTimeMillis())
                        val hops = ev.optInt("hops", 1).coerceIn(1, 7)

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

                        val (kind, displayBody, meta, savedPath) = parseWireAttachment(decryptedBody)
                        val msg = ChatMessage(
                            id = "$sender-$ts-${rawText.hashCode()}",
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
                        )
                        MeshStore.appendMessage(msg)
                        maybePostIncomingNotification(msg)
                    }
                    item.has("PrivateMessageReceived") -> {
                        val ev = item.getJSONObject("PrivateMessageReceived")
                        val sender = ev.optString("sender", "").lowercase(Locale.US)
                        val rawText = ev.optString("text", "")
                        val ts = ev.optLong("timestamp_ms", System.currentTimeMillis())

                        if (rawText.startsWith("[VERIFY_CHALLENGE:")) {
                            handleIncomingVerifyChallenge(sender, rawText)
                            continue
                        }
                        if (rawText.startsWith("[VERIFY_RESPONSE:")) {
                            handleIncomingVerifyResponse(sender, rawText)
                            continue
                        }

                        val senderNick = MeshStore.resolvePeerNickname(sender)
                        val (kind, displayBody, meta, savedPath) = parseWireAttachment(rawText)
                        val msg = ChatMessage(
                            id = "$sender-$ts-${rawText.hashCode()}",
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
                        )
                        MeshStore.appendMessage(msg)
                        maybePostIncomingNotification(msg)
                    }
                    item.has("SessionEstablished") -> {
                        val ev = item.getJSONObject("SessionEstablished")
                        val peerHex = ev.optString("peer", "").lowercase(Locale.US)
                        if (peerHex.isNotBlank()) {
                            flushOutboxForPeer(peerHex)
                        }
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
                        val dir = File(appContext!!.cacheDir, "voice_notes").apply { mkdirs() }
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
                val parts = inner.split(":")
                val name = parts.getOrNull(0) ?: "photo.jpg"
                val size = parts.getOrNull(1) ?: ""
                ParsedWireAttachment(MessageKind.IMAGE, "Photo ($name)", "$name • $size", null)
            }
            bodyText.startsWith("[FILE:") && bodyText.endsWith("]") -> {
                val inner = bodyText.removePrefix("[FILE:").removeSuffix("]")
                val parts = inner.split(":")
                val name = parts.getOrNull(0) ?: "document.bin"
                val size = parts.getOrNull(1) ?: ""
                ParsedWireAttachment(MessageKind.FILE, "File: $name ($size)", "$name • $size", null)
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

    private fun copyAttachmentToLocalCache(
        context: Context,
        uri: Uri,
        isImage: Boolean,
    ): Triple<String, Long, File?> {
        var name = if (isImage) "image_${System.currentTimeMillis()}.jpg" else "file_${System.currentTimeMillis()}.bin"
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIdx >= 0) {
                        cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { name = it }
                    }
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        size = cursor.getLong(sizeIdx)
                    }
                }
            }
        }
        val copied = runCatching {
            val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
            val safeName = "${System.currentTimeMillis()}_${name.replace(Regex("[^a-zA-Z0-9._-]"), "_")}"
            val dest = File(dir, safeName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (size <= 0L) size = dest.length()
            dest
        }.getOrNull()
        return Triple(name, size.coerceAtLeast(1024L), copied)
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
