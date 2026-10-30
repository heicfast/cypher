package chat.cypher.android.core

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import chat.cypher.android.ui.theme.ThemePreference
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

enum class DeliveryState {
    SENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED,
}

enum class AttachmentKind(val wireCode: Int) {
    IMAGE(0),
    AUDIO(1),
    FILE(2);

    companion object {
        fun fromWire(code: Int): AttachmentKind = when (code) {
            0 -> IMAGE
            1 -> AUDIO
            else -> FILE
        }
    }
}

data class MediaAttachment(
    val kind: AttachmentKind,
    val fileName: String,
    val mimeType: String,
    val localPath: String,
    val sizeBytes: Int,
    val durationMs: Long = 0L,
)

data class ReplyReference(
    val messageId: String,
    val senderNickname: String,
    val previewText: String,
)

data class ChatMessage(
    val id: String,
    val conversationKey: String,
    val senderPeerHex: String,
    val senderNickname: String,
    val text: String,
    val timestampMs: Long,
    val isOutgoing: Boolean,
    val isPrivate: Boolean,
    val delivery: DeliveryState = DeliveryState.SENT,
    val attachment: MediaAttachment? = null,
    val isSystem: Boolean = false,
    val replyTo: ReplyReference? = null,
    val reaction: String? = null,
)

data class ConversationBackup(
    val conversationKey: String,
    val messages: List<ChatMessage>,
    val unreadCount: Int,
    val wasPinned: Boolean,
    val wasMuted: Boolean,
)

/**
 * Persistent storage for Cypher identity seed (AES-GCM Keystore wrapped),
 * conversations, peer metadata, verified fingerprints, and UI preferences.
 */
class MeshStore(private val context: Context) {
    companion object {
        private const val PREFS_NAME = "cypher_mesh_store"
        private const val KEYSTORE_ALIAS = "cypher_identity_aes_key"

        private const val KEY_SEED_B64 = "identity_seed_b64"
        private const val KEY_SEED_IV_B64 = "identity_seed_iv_b64"
        private const val KEY_SEED_PLAIN_FALLBACK = "identity_seed_plain_b64"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_FAVORITES = "favorite_peers"
        private const val KEY_VERIFIED = "verified_peers"
        private const val KEY_BLOCKED = "blocked_peers"
        private const val KEY_PEER_NAMES = "peer_names_json"
        private const val KEY_PINNED_CONVS = "pinned_conversations"
        private const val KEY_MUTED_CONVS = "muted_conversations"
        private const val KEY_MARKED_UNREAD = "marked_unread_conversations"
        private const val KEY_THEME_PREF = "theme_preference"
        private const val KEY_BG_SERVICE_ENABLED = "background_service_enabled"
        private const val KEY_BATTERY_OPT_SKIPPED = "battery_optimization_skipped"

        const val BROADCAST_CONVERSATION_KEY = "#mesh"
        private const val MAX_MESSAGES_PER_CONVERSATION = 500
    }

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val historyFile: File = File(context.filesDir, "conversations_v2.json")
    private val legacyHistoryFile: File = File(context.filesDir, "conversations_v1.json")
    private val mediaDir: File = File(context.filesDir, "media").apply { mkdirs() }

    private val _conversations = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val conversations: StateFlow<Map<String, List<ChatMessage>>> = _conversations.asStateFlow()

    private val _unreadCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadCounts: StateFlow<Map<String, Int>> = _unreadCounts.asStateFlow()

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _verifiedPeers = MutableStateFlow<Map<String, String>>(emptyMap())
    val verifiedPeers: StateFlow<Map<String, String>> = _verifiedPeers.asStateFlow()

    private val _blockedPeers = MutableStateFlow<Set<String>>(emptySet())
    val blockedPeers: StateFlow<Set<String>> = _blockedPeers.asStateFlow()

    private val _knownPeerNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val knownPeerNames: StateFlow<Map<String, String>> = _knownPeerNames.asStateFlow()

    private val _pinnedConversations = MutableStateFlow<Set<String>>(emptySet())
    val pinnedConversations: StateFlow<Set<String>> = _pinnedConversations.asStateFlow()

    private val _mutedConversations = MutableStateFlow<Set<String>>(emptySet())
    val mutedConversations: StateFlow<Set<String>> = _mutedConversations.asStateFlow()

    private val _markedUnreadConversations = MutableStateFlow<Set<String>>(emptySet())
    val markedUnreadConversations: StateFlow<Set<String>> = _markedUnreadConversations.asStateFlow()

    private val _drafts = MutableStateFlow<Map<String, String>>(emptyMap())
    val drafts: StateFlow<Map<String, String>> = _drafts.asStateFlow()

    private val _themePreference = MutableStateFlow(ThemePreference.DARK)
    val themePreference: StateFlow<ThemePreference> = _themePreference.asStateFlow()

    private val _backgroundServiceEnabled = MutableStateFlow(true)
    val backgroundServiceEnabled: StateFlow<Boolean> = _backgroundServiceEnabled.asStateFlow()

    private val _batteryOptimizationSkipped = MutableStateFlow(false)
    val batteryOptimizationSkipped: StateFlow<Boolean> = _batteryOptimizationSkipped.asStateFlow()

    init {
        loadMetadata()
        loadHistoryFromDisk()
    }

    fun loadOrNullIdentitySeed(): ByteArray? {
        val cipherB64 = prefs.getString(KEY_SEED_B64, null)
        val ivB64 = prefs.getString(KEY_SEED_IV_B64, null)
        if (cipherB64 != null && ivB64 != null) {
            val decrypted = runCatching {
                val cipherBytes = Base64.decode(cipherB64, Base64.NO_WRAP)
                val iv = Base64.decode(ivB64, Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
                cipher.doFinal(cipherBytes)
            }.getOrNull()
            if (decrypted != null && decrypted.size == 32) return decrypted
        }
        val plainB64 = prefs.getString(KEY_SEED_PLAIN_FALLBACK, null) ?: return null
        return runCatching { Base64.decode(plainB64, Base64.NO_WRAP) }
            .getOrNull()
            ?.takeIf { it.size == 32 }
    }

    fun persistIdentitySeed(seed32: ByteArray) {
        if (seed32.size != 32) return
        val encrypted = runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            val iv = cipher.iv
            val out = cipher.doFinal(seed32)
            prefs.edit()
                .putString(KEY_SEED_B64, Base64.encodeToString(out, Base64.NO_WRAP))
                .putString(KEY_SEED_IV_B64, Base64.encodeToString(iv, Base64.NO_WRAP))
                .remove(KEY_SEED_PLAIN_FALLBACK)
                .apply()
            true
        }.getOrDefault(false)

        if (!encrypted) {
            prefs.edit()
                .putString(KEY_SEED_PLAIN_FALLBACK, Base64.encodeToString(seed32, Base64.NO_WRAP))
                .apply()
        }
    }

    fun loadNickname(): String {
        val existing = prefs.getString(KEY_NICKNAME, null)
        if (!existing.isNullOrBlank()) return existing
        val generated = "cypher-${(1000..9999).random()}"
        saveNickname(generated)
        return generated
    }

    fun saveNickname(nickname: String) {
        prefs.edit().putString(KEY_NICKNAME, nickname.trim()).apply()
    }

    fun setThemePreference(preference: ThemePreference) {
        _themePreference.value = preference
        prefs.edit().putString(KEY_THEME_PREF, preference.name).apply()
    }

    fun setBackgroundServiceEnabled(enabled: Boolean) {
        _backgroundServiceEnabled.value = enabled
        prefs.edit().putBoolean(KEY_BG_SERVICE_ENABLED, enabled).apply()
    }

    fun setBatteryOptimizationSkipped(skipped: Boolean) {
        _batteryOptimizationSkipped.value = skipped
        prefs.edit().putBoolean(KEY_BATTERY_OPT_SKIPPED, skipped).apply()
    }

    fun rememberPeerName(peerHex: String, nickname: String) {
        val cleanNick = nickname.trim()
        if (peerHex.isBlank() || cleanNick.isBlank()) return
        val key = peerHex.lowercase()
        if (_knownPeerNames.value[key] == cleanNick) return
        _knownPeerNames.update { it + (key to cleanNick) }
        val obj = JSONObject()
        _knownPeerNames.value.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString(KEY_PEER_NAMES, obj.toString()).apply()
    }

    fun toggleFavorite(peerHex: String) {
        val key = peerHex.lowercase()
        _favorites.update { current ->
            if (current.contains(key)) current - key else current + key
        }
        prefs.edit().putStringSet(KEY_FAVORITES, _favorites.value).apply()
    }

    fun markPeerVerified(peerHex: String, fingerprintHex: String) {
        val key = peerHex.lowercase()
        _verifiedPeers.update { it + (key to fingerprintHex.lowercase()) }
        persistVerifiedPeers()
    }

    fun unverifyPeer(peerHex: String) {
        val key = peerHex.lowercase()
        _verifiedPeers.update { it - key }
        persistVerifiedPeers()
    }

    fun toggleBlocked(peerHex: String): Boolean {
        val key = peerHex.lowercase()
        var nowBlocked = false
        _blockedPeers.update { current ->
            if (current.contains(key)) {
                nowBlocked = false
                current - key
            } else {
                nowBlocked = true
                current + key
            }
        }
        prefs.edit().putStringSet(KEY_BLOCKED, _blockedPeers.value).apply()
        return nowBlocked
    }

    fun isBlocked(peerHex: String): Boolean = _blockedPeers.value.contains(peerHex.lowercase())

    fun togglePinConversation(conversationKey: String): Boolean {
        if (conversationKey == BROADCAST_CONVERSATION_KEY) return true
        var pinned = false
        _pinnedConversations.update { current ->
            if (current.contains(conversationKey)) {
                pinned = false
                current - conversationKey
            } else {
                pinned = true
                current + conversationKey
            }
        }
        prefs.edit().putStringSet(KEY_PINNED_CONVS, _pinnedConversations.value).apply()
        return pinned
    }

    fun toggleMuteConversation(conversationKey: String): Boolean {
        var muted = false
        _mutedConversations.update { current ->
            if (current.contains(conversationKey)) {
                muted = false
                current - conversationKey
            } else {
                muted = true
                current + conversationKey
            }
        }
        prefs.edit().putStringSet(KEY_MUTED_CONVS, _mutedConversations.value).apply()
        return muted
    }

    fun toggleMarkUnread(conversationKey: String): Boolean {
        var marked = false
        val currentUnread = (_unreadCounts.value[conversationKey] ?: 0) > 0 ||
            _markedUnreadConversations.value.contains(conversationKey)
        if (currentUnread) {
            _unreadCounts.update { it - conversationKey }
            _markedUnreadConversations.update { it - conversationKey }
            marked = false
        } else {
            _markedUnreadConversations.update { it + conversationKey }
            _unreadCounts.update { it + (conversationKey to 1) }
            marked = true
        }
        prefs.edit().putStringSet(KEY_MARKED_UNREAD, _markedUnreadConversations.value).apply()
        return marked
    }

    fun updateDraft(conversationKey: String, text: String) {
        _drafts.update { current ->
            if (text.isBlank()) current - conversationKey else current + (conversationKey to text)
        }
    }

    fun appendMessage(message: ChatMessage, activeConversationKey: String?) {
        var added = false
        _conversations.update { map ->
            val existing = map[message.conversationKey].orEmpty()
            if (existing.any { it.id == message.id }) {
                return@update map
            }
            added = true
            val updated = (existing + message).takeLast(MAX_MESSAGES_PER_CONVERSATION)
            map + (message.conversationKey to updated)
        }
        if (added && !message.isOutgoing && !message.isSystem &&
            activeConversationKey != message.conversationKey
        ) {
            _unreadCounts.update { counts ->
                counts + (message.conversationKey to ((counts[message.conversationKey] ?: 0) + 1))
            }
        }
        if (added) {
            schedulePersistHistory()
        }
    }

    fun markConversationRead(conversationKey: String): List<ChatMessage> {
        _unreadCounts.update { it - conversationKey }
        if (_markedUnreadConversations.value.contains(conversationKey)) {
            _markedUnreadConversations.update { it - conversationKey }
            prefs.edit().putStringSet(KEY_MARKED_UNREAD, _markedUnreadConversations.value).apply()
        }
        return _conversations.value[conversationKey].orEmpty()
            .filter { !it.isOutgoing && it.isPrivate && !it.isSystem }
    }

    fun updateDeliveryState(messageId: String, newState: DeliveryState) {
        var changed = false
        _conversations.update { map ->
            map.mapValues { (_, list) ->
                list.map { msg ->
                    if (msg.id.equals(messageId, ignoreCase = true) &&
                        msg.delivery.ordinal < newState.ordinal
                    ) {
                        changed = true
                        msg.copy(delivery = newState)
                    } else {
                        msg
                    }
                }
            }
        }
        if (changed) {
            schedulePersistHistory()
        }
    }

    fun toggleMessageReaction(conversationKey: String, messageId: String, emoji: String) {
        var changed = false
        _conversations.update { map ->
            val list = map[conversationKey] ?: return@update map
            val updated = list.map { msg ->
                if (msg.id == messageId) {
                    changed = true
                    val nextReaction = if (msg.reaction == emoji) null else emoji
                    msg.copy(reaction = nextReaction)
                } else {
                    msg
                }
            }
            map + (conversationKey to updated)
        }
        if (changed) {
            schedulePersistHistory()
        }
    }

    fun clearConversationMessages(conversationKey: String) {
        _conversations.update { map ->
            if (conversationKey == BROADCAST_CONVERSATION_KEY) {
                map + (BROADCAST_CONVERSATION_KEY to emptyList())
            } else {
                map + (conversationKey to emptyList())
            }
        }
        _unreadCounts.update { it - conversationKey }
        schedulePersistHistory()
    }

    fun deleteConversationWithBackup(conversationKey: String): ConversationBackup? {
        val key = conversationKey.lowercase()
        if (key == BROADCAST_CONVERSATION_KEY) {
            val msgs = _conversations.value[BROADCAST_CONVERSATION_KEY].orEmpty()
            clearConversationMessages(BROADCAST_CONVERSATION_KEY)
            return ConversationBackup(
                conversationKey = BROADCAST_CONVERSATION_KEY,
                messages = msgs,
                unreadCount = 0,
                wasPinned = true,
                wasMuted = _mutedConversations.value.contains(BROADCAST_CONVERSATION_KEY),
            )
        }
        val messages = _conversations.value[key].orEmpty()
        val unread = _unreadCounts.value[key] ?: 0
        val wasPinned = _pinnedConversations.value.contains(key)
        val wasMuted = _mutedConversations.value.contains(key)

        _conversations.update { it - key }
        _unreadCounts.update { it - key }
        _drafts.update { it - key }
        _pinnedConversations.update { it - key }
        _markedUnreadConversations.update { it - key }
        prefs.edit()
            .putStringSet(KEY_PINNED_CONVS, _pinnedConversations.value)
            .putStringSet(KEY_MARKED_UNREAD, _markedUnreadConversations.value)
            .apply()
        schedulePersistHistory()

        return ConversationBackup(
            conversationKey = key,
            messages = messages,
            unreadCount = unread,
            wasPinned = wasPinned,
            wasMuted = wasMuted,
        )
    }

    fun restoreConversationBackup(backup: ConversationBackup) {
        _conversations.update { it + (backup.conversationKey to backup.messages) }
        if (backup.unreadCount > 0) {
            _unreadCounts.update { it + (backup.conversationKey to backup.unreadCount) }
        }
        if (backup.wasPinned && backup.conversationKey != BROADCAST_CONVERSATION_KEY) {
            _pinnedConversations.update { it + backup.conversationKey }
            prefs.edit().putStringSet(KEY_PINNED_CONVS, _pinnedConversations.value).apply()
        }
        if (backup.wasMuted) {
            _mutedConversations.update { it + backup.conversationKey }
            prefs.edit().putStringSet(KEY_MUTED_CONVS, _mutedConversations.value).apply()
        }
        schedulePersistHistory()
    }

    fun saveIncomingMedia(
        messageId: String,
        kind: AttachmentKind,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): MediaAttachment {
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "attachment.bin" }
        val outFile = File(mediaDir, "${messageId.take(16)}_$safeName")
        runCatching { outFile.writeBytes(bytes) }
        return MediaAttachment(
            kind = kind,
            fileName = safeName,
            mimeType = mimeType,
            localPath = outFile.absolutePath,
            sizeBytes = bytes.size,
        )
    }

    fun wipeAll() {
        prefs.edit().clear().apply()
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(KEYSTORE_ALIAS)) {
                ks.deleteEntry(KEYSTORE_ALIAS)
            }
        }
        runCatching { historyFile.delete() }
        runCatching { legacyHistoryFile.delete() }
        runCatching { mediaDir.deleteRecursively(); mediaDir.mkdirs() }
        _conversations.value = emptyMap()
        _unreadCounts.value = emptyMap()
        _favorites.value = emptySet()
        _verifiedPeers.value = emptyMap()
        _blockedPeers.value = emptySet()
        _knownPeerNames.value = emptyMap()
        _pinnedConversations.value = emptySet()
        _mutedConversations.value = emptySet()
        _markedUnreadConversations.value = emptySet()
        _drafts.value = emptyMap()
    }

    private fun loadMetadata() {
        _favorites.value = prefs.getStringSet(KEY_FAVORITES, emptySet()).orEmpty().toSet()
        _blockedPeers.value = prefs.getStringSet(KEY_BLOCKED, emptySet()).orEmpty().toSet()
        _pinnedConversations.value = prefs.getStringSet(KEY_PINNED_CONVS, emptySet()).orEmpty().toSet()
        _mutedConversations.value = prefs.getStringSet(KEY_MUTED_CONVS, emptySet()).orEmpty().toSet()
        _markedUnreadConversations.value = prefs.getStringSet(KEY_MARKED_UNREAD, emptySet()).orEmpty().toSet()
        _markedUnreadConversations.value.forEach { key ->
            _unreadCounts.update { it + (key to (it[key] ?: 1)) }
        }
        _themePreference.value = ThemePreference.fromStorage(
            prefs.getString(KEY_THEME_PREF, ThemePreference.DARK.name)
        )
        _backgroundServiceEnabled.value = prefs.getBoolean(KEY_BG_SERVICE_ENABLED, true)
        _batteryOptimizationSkipped.value = prefs.getBoolean(KEY_BATTERY_OPT_SKIPPED, false)

        val verifiedJson = prefs.getString(KEY_VERIFIED, null)
        if (!verifiedJson.isNullOrBlank()) {
            runCatching {
                val obj = JSONObject(verifiedJson)
                val map = mutableMapOf<String, String>()
                obj.keys().forEach { k -> map[k] = obj.optString(k, "") }
                _verifiedPeers.value = map
            }
        }

        val namesJson = prefs.getString(KEY_PEER_NAMES, null)
        if (!namesJson.isNullOrBlank()) {
            runCatching {
                val obj = JSONObject(namesJson)
                val map = mutableMapOf<String, String>()
                obj.keys().forEach { k -> map[k] = obj.optString(k, "") }
                _knownPeerNames.value = map
            }
        }
    }

    private fun persistVerifiedPeers() {
        val obj = JSONObject()
        _verifiedPeers.value.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putStringSet(KEY_FAVORITES, _favorites.value)
            .putString(KEY_VERIFIED, obj.toString())
            .apply()
    }

    private fun schedulePersistHistory() {
        val snapshot = _conversations.value
        ioScope.launch {
            runCatching {
                val root = JSONObject()
                for ((convKey, messages) in snapshot) {
                    val arr = JSONArray()
                    for (m in messages.takeLast(MAX_MESSAGES_PER_CONVERSATION)) {
                        val item = JSONObject()
                            .put("id", m.id)
                            .put("conversationKey", m.conversationKey)
                            .put("senderPeerHex", m.senderPeerHex)
                            .put("senderNickname", m.senderNickname)
                            .put("text", m.text)
                            .put("timestampMs", m.timestampMs)
                            .put("isOutgoing", m.isOutgoing)
                            .put("isPrivate", m.isPrivate)
                            .put("delivery", m.delivery.name)
                            .put("isSystem", m.isSystem)
                        m.reaction?.let { item.put("reaction", it) }
                        m.replyTo?.let { r ->
                            item.put(
                                "replyTo",
                                JSONObject()
                                    .put("messageId", r.messageId)
                                    .put("senderNickname", r.senderNickname)
                                    .put("previewText", r.previewText),
                            )
                        }
                        m.attachment?.let { att ->
                            item.put(
                                "attachment",
                                JSONObject()
                                    .put("kind", att.kind.wireCode)
                                    .put("fileName", att.fileName)
                                    .put("mimeType", att.mimeType)
                                    .put("localPath", att.localPath)
                                    .put("sizeBytes", att.sizeBytes)
                                    .put("durationMs", att.durationMs),
                            )
                        }
                        arr.put(item)
                    }
                    root.put(convKey, arr)
                }
                historyFile.writeText(root.toString())
            }
        }
    }

    private fun loadHistoryFromDisk() {
        val sourceFile = when {
            historyFile.exists() -> historyFile
            legacyHistoryFile.exists() -> legacyHistoryFile
            else -> return
        }
        runCatching {
            val root = JSONObject(sourceFile.readText())
            val loaded = mutableMapOf<String, List<ChatMessage>>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val arr = root.optJSONArray(key) ?: continue
                val list = ArrayList<ChatMessage>(arr.length())
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val attObj = item.optJSONObject("attachment")
                    val attachment = attObj?.let {
                        MediaAttachment(
                            kind = AttachmentKind.fromWire(it.optInt("kind", 2)),
                            fileName = it.optString("fileName", "attachment"),
                            mimeType = it.optString("mimeType", "application/octet-stream"),
                            localPath = it.optString("localPath", ""),
                            sizeBytes = it.optInt("sizeBytes", 0),
                            durationMs = it.optLong("durationMs", 0L),
                        )
                    }
                    val replyObj = item.optJSONObject("replyTo")
                    val replyTo = replyObj?.let {
                        ReplyReference(
                            messageId = it.optString("messageId", ""),
                            senderNickname = it.optString("senderNickname", ""),
                            previewText = it.optString("previewText", ""),
                        )
                    }
                    val delivery = runCatching {
                        DeliveryState.valueOf(item.optString("delivery", DeliveryState.SENT.name))
                    }.getOrDefault(DeliveryState.SENT)
                    val reaction = item.optString("reaction", "").takeIf { it.isNotBlank() }

                    list.add(
                        ChatMessage(
                            id = item.optString("id"),
                            conversationKey = item.optString("conversationKey", key),
                            senderPeerHex = item.optString("senderPeerHex"),
                            senderNickname = item.optString("senderNickname"),
                            text = item.optString("text"),
                            timestampMs = item.optLong("timestampMs"),
                            isOutgoing = item.optBoolean("isOutgoing"),
                            isPrivate = item.optBoolean("isPrivate"),
                            delivery = delivery,
                            attachment = attachment,
                            isSystem = item.optBoolean("isSystem", false),
                            replyTo = replyTo,
                            reaction = reaction,
                        ),
                    )
                }
                loaded[key] = list
            }
            _conversations.value = loaded
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}
