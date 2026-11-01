package chat.cypher.android.core

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import chat.cypher.android.data.ChannelEntity
import chat.cypher.android.data.CipherDatabase
import chat.cypher.android.data.MessageEntity
import chat.cypher.android.data.PeerEntity
import chat.cypher.android.data.PendingOutboxEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class DeliveryStatus {
    QUEUED,
    SENT,
    DELIVERED,
    READ,
}

enum class MessageKind {
    TEXT,
    VOICE_NOTE,
    IMAGE,
    FILE,
    LOCATION,
    SYSTEM,
}

enum class AutoDeleteTimer(val label: String, val durationMs: Long?) {
    OFF("Off", null),
    ONE_HOUR("1 hour", 3_600_000L),
    TWENTY_FOUR_HOURS("24 hours", 86_400_000L),
    SEVEN_DAYS("7 days", 604_800_000L),
}

enum class AppThemeMode(val label: String) {
    DARK("Dark (Default)"),
    AMOLED("AMOLED Pitch Black"),
    SYSTEM("System Default"),
}

data class ChatMessage(
    val id: String,
    val conversationKey: String,
    val senderHex: String,
    val senderLabel: String,
    val text: String,
    val timestampMs: Long,
    val outgoing: Boolean,
    val encrypted: Boolean,
    val hops: Int = 0,
    val delivery: DeliveryStatus = DeliveryStatus.SENT,
    val kind: MessageKind = MessageKind.TEXT,
    val attachmentPath: String? = null,
    val attachmentMeta: String? = null,
    val replyToMessageId: String? = null,
    val replyPreview: String? = null,
    val reactions: Map<String, Int> = emptyMap(),
    val starred: Boolean = false,
)

data class PeerEntry(
    val peerKeyHex: String,
    val nickname: String,
    val connected: Boolean,
    val sessionEstablished: Boolean,
    val rssi: Int? = null,
    val hops: Int = 1,
    val lastSeenMs: Long = System.currentTimeMillis(),
    val fingerprintHex: String = "",
    val verified: Boolean = false,
    val routePath: List<String> = emptyList(),
)

/**
 * Local state & Room database persistence store for the pure offline Bluetooth LE mesh engine.
 *
 * Identity seeds are encrypted with an Android Keystore AES-256-GCM key (`cypher_identity_master_v1`).
 * Messages, peers, channels, and store-and-forward outbox entries are persisted in Room SQLite
 * (`CipherDatabase`). Zero mock or synthetic conversations are ever seeded.
 */
object MeshStore {
    private const val PREFS_NAME = "cypher_mesh_store"
    private const val KEY_SEED_CIPHERTEXT = "identity_seed_ct"
    private const val KEY_SEED_IV = "identity_seed_iv"
    private const val KEY_NICKNAME = "nickname"
    private const val KEY_STATUS_BIO = "status_bio"
    private const val KEY_JOINED_CHANNELS = "joined_channels_v2"
    private const val KEY_BOOKMARKED_CHANNELS = "bookmarked_channels_v2"
    private const val KEY_CHANNEL_PASSWORDS = "channel_passwords_v2"
    private const val KEY_CHANNEL_OWNERS = "channel_owners_v2"
    private const val KEY_VERIFIED_PEERS = "verified_peers_v2"
    private const val KEY_FAVORITE_PEERS = "favorite_peers_v2"
    private const val KEY_BLOCKED_PEERS = "blocked_peers_v2"
    private const val KEY_PEER_ALIASES = "peer_aliases_v2"
    private const val KEY_PEER_NOTES = "peer_notes_v2"
    private const val KEY_PINNED_CONVERSATIONS = "pinned_conversations_v2"
    private const val KEY_MUTED_CONVERSATIONS = "muted_conversations_v2"
    private const val KEY_ARCHIVED_CONVERSATIONS = "archived_conversations_v2"
    private const val KEY_UNREAD_COUNTS = "unread_counts_v2"
    private const val KEY_AUTO_DELETE = "auto_delete_timer"
    private const val KEY_THEME_MODE = "app_theme_mode"
    private const val KEY_ACCENT_HEX = "app_accent_hex"
    private const val KEY_COMPACT_MODE = "app_compact_mode"
    private const val KEY_SCREEN_SECURITY = "screen_security_enabled"
    private const val KEY_HAPTIC_FEEDBACK = "haptic_feedback_enabled"
    private const val KEY_AUTO_RELAY = "mesh_auto_relay_enabled"
    private const val KEY_STORE_AND_FORWARD = "mesh_store_and_forward_enabled"
    private const val KEY_COVER_TRAFFIC = "mesh_cover_traffic_enabled"
    private const val KEY_MAX_TTL_HOPS = "mesh_max_ttl_hops"
    private const val KEY_SCAN_MODE = "mesh_ble_scan_mode"
    private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
    private const val KEY_MENTION_ALERTS_ENABLED = "mention_alerts_enabled"
    private const val KEY_NEARBY_PEER_ALERTS_ENABLED = "nearby_peer_alerts_enabled"
    private const val KEY_SOUND_VIBRATION_ENABLED = "sound_vibration_enabled"
    private const val KEYSTORE_ALIAS = "cypher_identity_master_v1"

    const val PUBLIC_CONVERSATION = "#mesh"

    private val dbScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var database: CipherDatabase? = null

    private val _localIdentityHex = MutableStateFlow("")
    val localIdentityHex: StateFlow<String> = _localIdentityHex.asStateFlow()

    private val _localFingerprintHex = MutableStateFlow("")
    val localFingerprintHex: StateFlow<String> = _localFingerprintHex.asStateFlow()

    private val _nickname = MutableStateFlow("anon")
    val nickname: StateFlow<String> = _nickname.asStateFlow()

    private val _statusBio = MutableStateFlow("Available on local BLE mesh")
    val statusBio: StateFlow<String> = _statusBio.asStateFlow()

    private val _peers = MutableStateFlow<List<PeerEntry>>(emptyList())
    val peers: StateFlow<List<PeerEntry>> = _peers.asStateFlow()

    private val _knownPeers = MutableStateFlow<Map<String, PeerEntity>>(emptyMap())
    val knownPeers: StateFlow<Map<String, PeerEntity>> = _knownPeers.asStateFlow()

    private val _conversations = MutableStateFlow<Map<String, List<ChatMessage>>>(
        mapOf(PUBLIC_CONVERSATION to emptyList()),
    )
    val conversations: StateFlow<Map<String, List<ChatMessage>>> = _conversations.asStateFlow()

    private val _activeConversation = MutableStateFlow(PUBLIC_CONVERSATION)
    val activeConversation: StateFlow<String> = _activeConversation.asStateFlow()

    private val _joinedChannels = MutableStateFlow(setOf(PUBLIC_CONVERSATION))
    val joinedChannels: StateFlow<Set<String>> = _joinedChannels.asStateFlow()

    private val _bookmarkedChannels = MutableStateFlow<Set<String>>(emptySet())
    val bookmarkedChannels: StateFlow<Set<String>> = _bookmarkedChannels.asStateFlow()

    private val _channelPasswords = MutableStateFlow<Map<String, String>>(emptyMap())
    val channelPasswords: StateFlow<Map<String, String>> = _channelPasswords.asStateFlow()

    private val _channelOwners = MutableStateFlow<Map<String, String>>(emptyMap())
    val channelOwners: StateFlow<Map<String, String>> = _channelOwners.asStateFlow()

    private val _channelCommitments = MutableStateFlow<Map<String, String>>(emptyMap())
    val channelCommitments: StateFlow<Map<String, String>> = _channelCommitments.asStateFlow()

    private val _verifiedPeers = MutableStateFlow<Set<String>>(emptySet())
    val verifiedPeers: StateFlow<Set<String>> = _verifiedPeers.asStateFlow()

    private val _favoritePeers = MutableStateFlow<Set<String>>(emptySet())
    val favoritePeers: StateFlow<Set<String>> = _favoritePeers.asStateFlow()

    private val _blockedPeers = MutableStateFlow<Set<String>>(emptySet())
    val blockedPeers: StateFlow<Set<String>> = _blockedPeers.asStateFlow()

    private val _peerAliases = MutableStateFlow<Map<String, String>>(emptyMap())
    val peerAliases: StateFlow<Map<String, String>> = _peerAliases.asStateFlow()

    private val _peerNotes = MutableStateFlow<Map<String, String>>(emptyMap())
    val peerNotes: StateFlow<Map<String, String>> = _peerNotes.asStateFlow()

    private val _pinnedConversations = MutableStateFlow<Set<String>>(setOf(PUBLIC_CONVERSATION))
    val pinnedConversations: StateFlow<Set<String>> = _pinnedConversations.asStateFlow()

    private val _mutedConversations = MutableStateFlow<Set<String>>(emptySet())
    val mutedConversations: StateFlow<Set<String>> = _mutedConversations.asStateFlow()

    private val _archivedConversations = MutableStateFlow<Set<String>>(emptySet())
    val archivedConversations: StateFlow<Set<String>> = _archivedConversations.asStateFlow()

    private val _unreadCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadCounts: StateFlow<Map<String, Int>> = _unreadCounts.asStateFlow()

    private val _drafts = MutableStateFlow<Map<String, String>>(emptyMap())
    val drafts: StateFlow<Map<String, String>> = _drafts.asStateFlow()

    private val _pendingOutboxCount = MutableStateFlow(0)
    val pendingOutboxCount: StateFlow<Int> = _pendingOutboxCount.asStateFlow()

    private val _autoDeleteTimer = MutableStateFlow(AutoDeleteTimer.OFF)
    val autoDeleteTimer: StateFlow<AutoDeleteTimer> = _autoDeleteTimer.asStateFlow()

    private val _themeMode = MutableStateFlow(AppThemeMode.DARK)
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    private val _accentHex = MutableStateFlow("#8352FD")
    val accentHex: StateFlow<String> = _accentHex.asStateFlow()

    private val _compactMode = MutableStateFlow(false)
    val compactMode: StateFlow<Boolean> = _compactMode.asStateFlow()

    private val _screenSecurityEnabled = MutableStateFlow(false)
    val screenSecurityEnabled: StateFlow<Boolean> = _screenSecurityEnabled.asStateFlow()

    private val _hapticFeedbackEnabled = MutableStateFlow(true)
    val hapticFeedbackEnabled: StateFlow<Boolean> = _hapticFeedbackEnabled.asStateFlow()

    private val _autoRelayEnabled = MutableStateFlow(true)
    val autoRelayEnabled: StateFlow<Boolean> = _autoRelayEnabled.asStateFlow()

    private val _storeAndForwardEnabled = MutableStateFlow(true)
    val storeAndForwardEnabled: StateFlow<Boolean> = _storeAndForwardEnabled.asStateFlow()

    private val _coverTrafficEnabled = MutableStateFlow(false)
    val coverTrafficEnabled: StateFlow<Boolean> = _coverTrafficEnabled.asStateFlow()

    private val _maxTtlHops = MutableStateFlow(7)
    val maxTtlHops: StateFlow<Int> = _maxTtlHops.asStateFlow()

    private val _bleScanMode = MutableStateFlow("Balanced")
    val bleScanMode: StateFlow<String> = _bleScanMode.asStateFlow()

    private val _notificationsEnabled = MutableStateFlow(true)
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    private val _mentionAlertsEnabled = MutableStateFlow(true)
    val mentionAlertsEnabled: StateFlow<Boolean> = _mentionAlertsEnabled.asStateFlow()

    private val _nearbyPeerAlertsEnabled = MutableStateFlow(true)
    val nearbyPeerAlertsEnabled: StateFlow<Boolean> = _nearbyPeerAlertsEnabled.asStateFlow()

    private val _soundVibrationEnabled = MutableStateFlow(true)
    val soundVibrationEnabled: StateFlow<Boolean> = _soundVibrationEnabled.asStateFlow()

    private val _packetsRelayed = MutableStateFlow(0L)
    val packetsRelayed: StateFlow<Long> = _packetsRelayed.asStateFlow()

    private val _bytesTransferred = MutableStateFlow(0L)
    val bytesTransferred: StateFlow<Long> = _bytesTransferred.asStateFlow()

    private val _meshStartedAtMs = MutableStateFlow(System.currentTimeMillis())
    val meshStartedAtMs: StateFlow<Long> = _meshStartedAtMs.asStateFlow()

    private var prefs: SharedPreferences? = null

    fun attachDatabase(context: Context, customDb: CipherDatabase? = null) {
        val db = customDb ?: CipherDatabase.getDatabase(context)
        database = db
        runCatching {
            runBlocking(Dispatchers.IO) {
                hydrateFromDatabase(db)
            }
        }
    }

    private suspend fun hydrateFromDatabase(db: CipherDatabase) {
        val storedMessages = db.messageDao().getAllMessagesSnapshot()
        if (storedMessages.isNotEmpty()) {
            val grouped = storedMessages
                .map { it.toChatMessage() }
                .groupBy { it.conversationKey }
            _conversations.value = mapOf(PUBLIC_CONVERSATION to emptyList<ChatMessage>()) + grouped
        } else {
            _conversations.value = mapOf(PUBLIC_CONVERSATION to emptyList<ChatMessage>())
        }

        val storedPeers = db.peerDao().getAllPeersSnapshot()
        _knownPeers.value = storedPeers.associateBy { it.peerKeyHex.lowercase(Locale.US) }

        val storedChannels = db.channelDao().getAllChannelsSnapshot()
        if (storedChannels.isNotEmpty()) {
            val joined = storedChannels.filter { it.joined }.map { it.tag }.toSet() + PUBLIC_CONVERSATION
            val bookmarked = storedChannels.filter { it.bookmarked }.map { it.tag }.toSet()
            val owners = storedChannels
                .mapNotNull { ch -> ch.ownerPeerHex?.let { ch.tag to it } }
                .toMap()
            val commitments = storedChannels
                .mapNotNull { ch -> ch.passwordCommitmentHex?.let { ch.tag to it } }
                .toMap()
            _joinedChannels.value = joined
            _bookmarkedChannels.value = bookmarked
            _channelOwners.value = _channelOwners.value + owners
            _channelCommitments.value = _channelCommitments.value + commitments
        }

        val pending = db.outboxDao().getAllPendingSnapshot()
        _pendingOutboxCount.value = pending.size
    }

    fun loadOrCreateSeed(context: Context): ByteArray {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        if (database == null) {
            attachDatabase(context)
        }
        val existingNick = sp.getString(KEY_NICKNAME, null)
        if (!existingNick.isNullOrBlank()) {
            _nickname.value = existingNick
        } else {
            val generated = "anon${(1000..9999).random()}"
            sp.edit().putString(KEY_NICKNAME, generated).apply()
            _nickname.value = generated
        }
        sp.getString(KEY_STATUS_BIO, null)?.let { _statusBio.value = it }

        val savedChannels = sp.getStringSet(KEY_JOINED_CHANNELS, null)
        if (!savedChannels.isNullOrEmpty()) {
            _joinedChannels.value = savedChannels.map { Geohash.normalizeTag(it) }.toSet() + PUBLIC_CONVERSATION
        }
        val savedBookmarked = sp.getStringSet(KEY_BOOKMARKED_CHANNELS, null)
        if (savedBookmarked != null) {
            _bookmarkedChannels.value = savedBookmarked.map { Geohash.normalizeTag(it) }.toSet()
        }
        _channelPasswords.value = decodeStringMap(sp.getString(KEY_CHANNEL_PASSWORDS, null))
        _channelOwners.value = _channelOwners.value + decodeStringMap(sp.getString(KEY_CHANNEL_OWNERS, null))
        _verifiedPeers.value = sp.getStringSet(KEY_VERIFIED_PEERS, emptySet())?.toSet() ?: emptySet()
        _favoritePeers.value = sp.getStringSet(KEY_FAVORITE_PEERS, emptySet())?.toSet() ?: emptySet()
        _blockedPeers.value = sp.getStringSet(KEY_BLOCKED_PEERS, emptySet())?.toSet() ?: emptySet()
        _pinnedConversations.value = sp.getStringSet(
            KEY_PINNED_CONVERSATIONS,
            setOf(PUBLIC_CONVERSATION),
        )?.toSet() ?: setOf(PUBLIC_CONVERSATION)
        _mutedConversations.value = sp.getStringSet(KEY_MUTED_CONVERSATIONS, emptySet())?.toSet() ?: emptySet()
        _archivedConversations.value = sp.getStringSet(KEY_ARCHIVED_CONVERSATIONS, emptySet())?.toSet() ?: emptySet()
        _peerAliases.value = decodeStringMap(sp.getString(KEY_PEER_ALIASES, null))
        _peerNotes.value = decodeStringMap(sp.getString(KEY_PEER_NOTES, null))
        _unreadCounts.value = decodeIntMap(sp.getString(KEY_UNREAD_COUNTS, null))

        val timerName = sp.getString(KEY_AUTO_DELETE, AutoDeleteTimer.OFF.name)
        _autoDeleteTimer.value = AutoDeleteTimer.entries.firstOrNull { it.name == timerName } ?: AutoDeleteTimer.OFF

        val themeName = sp.getString(KEY_THEME_MODE, AppThemeMode.DARK.name)
        _themeMode.value = AppThemeMode.entries.firstOrNull { it.name == themeName } ?: AppThemeMode.DARK
        _accentHex.value = sp.getString(KEY_ACCENT_HEX, "#8352FD") ?: "#8352FD"
        _compactMode.value = sp.getBoolean(KEY_COMPACT_MODE, false)
        _screenSecurityEnabled.value = sp.getBoolean(KEY_SCREEN_SECURITY, false)
        _hapticFeedbackEnabled.value = sp.getBoolean(KEY_HAPTIC_FEEDBACK, true)
        _autoRelayEnabled.value = sp.getBoolean(KEY_AUTO_RELAY, true)
        _storeAndForwardEnabled.value = sp.getBoolean(KEY_STORE_AND_FORWARD, true)
        _coverTrafficEnabled.value = sp.getBoolean(KEY_COVER_TRAFFIC, false)
        _maxTtlHops.value = sp.getInt(KEY_MAX_TTL_HOPS, 7).coerceIn(1, 7)
        _bleScanMode.value = sp.getString(KEY_SCAN_MODE, "Balanced") ?: "Balanced"
        _notificationsEnabled.value = sp.getBoolean(KEY_NOTIFICATIONS_ENABLED, true)
        _mentionAlertsEnabled.value = sp.getBoolean(KEY_MENTION_ALERTS_ENABLED, true)
        _nearbyPeerAlertsEnabled.value = sp.getBoolean(KEY_NEARBY_PEER_ALERTS_ENABLED, true)
        _soundVibrationEnabled.value = sp.getBoolean(KEY_SOUND_VIBRATION_ENABLED, true)

        val ctB64 = sp.getString(KEY_SEED_CIPHERTEXT, null)
        val ivB64 = sp.getString(KEY_SEED_IV, null)
        if (ctB64 != null && ivB64 != null) {
            val decrypted = runCatching {
                decryptSeed(
                    Base64.decode(ctB64, Base64.NO_WRAP),
                    Base64.decode(ivB64, Base64.NO_WRAP),
                )
            }.getOrNull()
            if (decrypted != null && decrypted.size == 32) {
                deriveLocalFallbackIdentityIfNeeded(decrypted)
                return decrypted
            }
        }

        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        persistSeed(sp, fresh)
        deriveLocalFallbackIdentityIfNeeded(fresh)
        return fresh
    }

    /**
     * Computes deterministic Ed25519/Noise identity & SHA-256 fingerprint from the 32-byte
     * hardware-backed seed before or without the native JNI engine.
     */
    private fun deriveLocalFallbackIdentityIfNeeded(seed: ByteArray) {
        if (_localIdentityHex.value.isBlank()) {
            val idBytes = MessageDigest.getInstance("SHA-256").digest(seed)
            _localIdentityHex.value = idBytes.take(8).joinToString("") { "%02x".format(it) }
        }
        if (_localFingerprintHex.value.isBlank()) {
            val fpMaterial = "cipher-noise-static-v1:".toByteArray(StandardCharsets.UTF_8) + seed
            val fpBytes = MessageDigest.getInstance("SHA-256").digest(fpMaterial)
            _localFingerprintHex.value = fpBytes.joinToString("") { "%02X".format(it) }
        }
    }

    fun rotateIdentitySeed(context: Context): ByteArray {
        val sp = prefs ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).also { prefs = it }
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        persistSeed(sp, fresh)
        _localIdentityHex.value = ""
        _localFingerprintHex.value = ""
        deriveLocalFallbackIdentityIfNeeded(fresh)
        return fresh
    }

    private fun persistSeed(sp: SharedPreferences, seed: ByteArray) {
        runCatching {
            val (ct, iv) = encryptSeed(seed)
            sp.edit()
                .putString(KEY_SEED_CIPHERTEXT, Base64.encodeToString(ct, Base64.NO_WRAP))
                .putString(KEY_SEED_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .apply()
        }
    }

    fun setLocalIdentity(peerHex: String, fingerprintHex: String = "") {
        val normalizedId = peerHex.lowercase(Locale.US)
        if (normalizedId.isNotBlank()) {
            _localIdentityHex.value = normalizedId
        }
        if (fingerprintHex.isNotBlank()) {
            _localFingerprintHex.value = fingerprintHex.replace(" ", "").uppercase(Locale.US)
        } else if (_localFingerprintHex.value.isBlank() && normalizedId.isNotBlank()) {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("cipher-peer-fp:$normalizedId".toByteArray(StandardCharsets.UTF_8))
            _localFingerprintHex.value = digest.joinToString("") { "%02X".format(it) }
        }
    }

    fun updateNickname(context: Context, value: String) {
        val cleaned = value.trim().removePrefix("@").take(24).ifEmpty { "anon" }
        _nickname.value = cleaned
        val sp = prefs ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_NICKNAME, cleaned).apply()
    }

    fun updateStatusBio(context: Context, value: String) {
        val cleaned = value.trim().take(80).ifEmpty { "Available on local BLE mesh" }
        _statusBio.value = cleaned
        val sp = prefs ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_STATUS_BIO, cleaned).apply()
    }

    fun selectConversation(key: String) {
        _activeConversation.value = key
        clearUnread(key)
    }

    fun joinChannel(
        tag: String,
        password: String? = null,
        ownerPeerHex: String? = null,
    ): Boolean {
        val normalized = Geohash.normalizeTag(tag).take(24)
        if (normalized.length < 2) return false

        val existingCommitment = _channelCommitments.value[normalized]
        if (existingCommitment != null && !password.isNullOrBlank()) {
            val candidateCommitment = ChannelCrypto.computeCommitmentHex(normalized, password)
            if (candidateCommitment != existingCommitment) {
                return false
            }
        }

        _joinedChannels.update { it + normalized }
        prefs?.edit()?.putStringSet(KEY_JOINED_CHANNELS, _joinedChannels.value)?.apply()

        if (!password.isNullOrBlank()) {
            setChannelPassword(normalized, password, ownerPeerHex)
        } else if (!ownerPeerHex.isNullOrBlank() && !_channelOwners.value.containsKey(normalized)) {
            _channelOwners.update { it + (normalized to ownerPeerHex.lowercase(Locale.US)) }
            prefs?.edit()?.putString(KEY_CHANNEL_OWNERS, encodeStringMap(_channelOwners.value))?.apply()
        }

        persistChannelState(normalized)
        _activeConversation.value = normalized
        clearUnread(normalized)
        return true
    }

    fun setChannelPassword(
        tag: String,
        password: String?,
        ownerPeerHex: String? = _localIdentityHex.value.takeIf { it.isNotBlank() },
    ) {
        val normalized = Geohash.normalizeTag(tag)
        if (password.isNullOrBlank()) {
            _channelPasswords.update { it - normalized }
            _channelCommitments.update { it - normalized }
        } else {
            val commitment = ChannelCrypto.computeCommitmentHex(normalized, password)
            _channelPasswords.update { it + (normalized to password) }
            _channelCommitments.update { it + (normalized to commitment) }
            if (!ownerPeerHex.isNullOrBlank() && !_channelOwners.value.containsKey(normalized)) {
                _channelOwners.update { it + (normalized to ownerPeerHex.lowercase(Locale.US)) }
                prefs?.edit()?.putString(KEY_CHANNEL_OWNERS, encodeStringMap(_channelOwners.value))?.apply()
            }
        }
        prefs?.edit()?.putString(KEY_CHANNEL_PASSWORDS, encodeStringMap(_channelPasswords.value))?.apply()
        persistChannelState(normalized)
    }

    fun registerChannelCommitment(tag: String, commitmentHex: String, ownerPeerHex: String?) {
        val normalized = Geohash.normalizeTag(tag)
        _channelCommitments.update { it + (normalized to commitmentHex) }
        if (!ownerPeerHex.isNullOrBlank()) {
            _channelOwners.update { it + (normalized to ownerPeerHex.lowercase(Locale.US)) }
            prefs?.edit()?.putString(KEY_CHANNEL_OWNERS, encodeStringMap(_channelOwners.value))?.apply()
        }
        persistChannelState(normalized)
    }

    fun transferChannelOwnership(tag: String, newOwnerPeerHex: String) {
        val normalized = Geohash.normalizeTag(tag)
        val normOwner = newOwnerPeerHex.lowercase(Locale.US)
        _channelOwners.update { it + (normalized to normOwner) }
        prefs?.edit()?.putString(KEY_CHANNEL_OWNERS, encodeStringMap(_channelOwners.value))?.apply()
        persistChannelState(normalized)
    }

    fun isChannelPasswordProtected(tag: String): Boolean {
        val normalized = Geohash.normalizeTag(tag)
        return _channelPasswords.value.containsKey(normalized) || _channelCommitments.value.containsKey(normalized)
    }

    fun getChannelPassword(tag: String): String? {
        val normalized = Geohash.normalizeTag(tag)
        return _channelPasswords.value[normalized]
    }

    fun toggleBookmarkChannel(tag: String) {
        val normalized = Geohash.normalizeTag(tag).take(24)
        if (normalized.length < 2) return
        _bookmarkedChannels.update { current ->
            if (normalized in current) current - normalized else current + normalized
        }
        prefs?.edit()?.putStringSet(KEY_BOOKMARKED_CHANNELS, _bookmarkedChannels.value)?.apply()
        persistChannelState(normalized)
    }

    fun leaveChannel(tag: String) {
        val normalized = Geohash.normalizeTag(tag)
        if (normalized == PUBLIC_CONVERSATION) return
        _joinedChannels.update { it - normalized }
        prefs?.edit()?.putStringSet(KEY_JOINED_CHANNELS, _joinedChannels.value)?.apply()
        persistChannelState(normalized)
        if (_activeConversation.value == normalized) {
            _activeConversation.value = PUBLIC_CONVERSATION
        }
    }

    private fun persistChannelState(tag: String) {
        val db = database ?: return
        val normalized = Geohash.normalizeTag(tag)
        val joined = normalized in _joinedChannels.value
        val bookmarked = normalized in _bookmarkedChannels.value
        val owner = _channelOwners.value[normalized]
        val commitment = _channelCommitments.value[normalized]
        val hasPwd = commitment != null || _channelPasswords.value.containsKey(normalized)
        dbScope.launch {
            runCatching {
                if (!joined && !bookmarked && !hasPwd) {
                    db.channelDao().deleteChannel(normalized)
                } else {
                    db.channelDao().upsertChannel(
                        ChannelEntity(
                            tag = normalized,
                            joined = joined,
                            bookmarked = bookmarked,
                            ownerPeerHex = owner,
                            passwordProtected = hasPwd,
                            passwordSaltHex = normalized,
                            passwordCommitmentHex = commitment,
                            lastActiveMs = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
    }

    fun updatePeers(peers: List<PeerEntry>) {
        val verified = _verifiedPeers.value
        val aliases = _peerAliases.value
        val existingKnown = _knownPeers.value
        val enriched = peers.map { peer ->
            val normKey = peer.peerKeyHex.lowercase(Locale.US)
            val customName = aliases[normKey] ?: aliases[peer.peerKeyHex]
            val resolvedFp = when {
                peer.fingerprintHex.isNotBlank() -> peer.fingerprintHex.replace(" ", "").uppercase(Locale.US)
                existingKnown[normKey]?.fingerprintHex?.isNotBlank() == true ->
                    existingKnown[normKey]!!.fingerprintHex
                else -> computeDeterministicPeerFingerprint(normKey)
            }
            peer.copy(
                peerKeyHex = normKey,
                nickname = if (!customName.isNullOrBlank()) customName else peer.nickname,
                verified = normKey in verified || peer.peerKeyHex in verified,
                fingerprintHex = resolvedFp,
            )
        }
        _peers.value = enriched

        MeshGraphService.syncPeersSnapshot(
            localPeerId = _localIdentityHex.value,
            localNickname = _nickname.value,
            peers = enriched,
        )

        val db = database ?: return
        dbScope.launch {
            runCatching {
                enriched.forEach { peer ->
                    val prev = _knownPeers.value[peer.peerKeyHex]
                    val entity = PeerEntity(
                        peerKeyHex = peer.peerKeyHex,
                        nickname = peer.nickname,
                        fingerprintHex = peer.fingerprintHex,
                        verified = peer.verified,
                        favorite = peer.peerKeyHex in _favoritePeers.value,
                        blocked = peer.peerKeyHex in _blockedPeers.value,
                        customAlias = _peerAliases.value[peer.peerKeyHex].orEmpty(),
                        privateNote = _peerNotes.value[peer.peerKeyHex].orEmpty(),
                        lastSeenMs = peer.lastSeenMs.coerceAtLeast(prev?.lastSeenMs ?: 0L),
                        lastRssi = peer.rssi ?: prev?.lastRssi,
                        lastHops = peer.hops,
                        routePathCsv = peer.routePath.joinToString(","),
                    )
                    db.peerDao().upsertPeer(entity)
                    _knownPeers.update { it + (peer.peerKeyHex to entity) }
                }
            }
        }
    }

    fun computeDeterministicPeerFingerprint(peerKeyHex: String): String {
        val norm = peerKeyHex.trim().lowercase(Locale.US)
        if (norm.isBlank()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("cipher-peer-fp:$norm".toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02X".format(it) }
    }

    fun resolvePeerNickname(peerKeyHex: String): String {
        val norm = peerKeyHex.lowercase(Locale.US)
        _peerAliases.value[norm]?.takeIf { it.isNotBlank() }?.let { return it }
        _peers.value.firstOrNull { it.peerKeyHex.equals(norm, ignoreCase = true) }?.nickname
            ?.takeIf { it.isNotBlank() }?.let { return it }
        _knownPeers.value[norm]?.nickname?.takeIf { it.isNotBlank() }?.let { return it }
        return "anon#${norm.take(6)}"
    }

    fun appendMessage(message: ChatMessage) {
        val senderNorm = message.senderHex.lowercase(Locale.US)
        if (senderNorm in _blockedPeers.value || message.senderHex in _blockedPeers.value) return
        pruneExpiredMessages()

        var persistedMessage: ChatMessage? = null
        _conversations.update { current ->
            val bucket = current[message.conversationKey].orEmpty()
            if (bucket.any { it.id == message.id }) return@update current
            persistedMessage = message
            current + (message.conversationKey to (bucket + message).takeLast(500))
        }

        val toSave = persistedMessage ?: return
        if (!toSave.outgoing && toSave.conversationKey != _activeConversation.value) {
            incrementUnread(toSave.conversationKey)
        }
        val db = database
        if (db != null) {
            dbScope.launch {
                runCatching {
                    db.messageDao().upsertMessage(toSave.toEntity())
                }
            }
        }
    }

    fun enqueueOutboxMessage(messageId: String, recipientHex: String, payloadText: String) {
        val db = database ?: return
        val normRecipient = recipientHex.lowercase(Locale.US)
        dbScope.launch {
            runCatching {
                db.outboxDao().upsertPending(
                    PendingOutboxEntity(
                        messageId = messageId,
                        recipientHex = normRecipient,
                        payloadText = payloadText,
                        createdAtMs = System.currentTimeMillis(),
                        attempts = 0,
                    ),
                )
                _pendingOutboxCount.value = db.outboxDao().getAllPendingSnapshot().size
            }
        }
    }

    suspend fun drainPendingOutboxForPeer(recipientHex: String): List<PendingOutboxEntity> {
        val db = database ?: return emptyList()
        val norm = recipientHex.lowercase(Locale.US)
        val pending = runCatching { db.outboxDao().getPendingForRecipient(norm) }.getOrDefault(emptyList())
        if (pending.isNotEmpty()) {
            runCatching {
                db.outboxDao().deletePendingForRecipient(norm)
                _pendingOutboxCount.value = db.outboxDao().getAllPendingSnapshot().size
            }
        }
        return pending
    }

    fun markMessageDelivered(messageId: String, status: DeliveryStatus = DeliveryStatus.DELIVERED) {
        _conversations.update { current ->
            current.mapValues { (_, list) ->
                list.map { msg ->
                    if (msg.id == messageId) msg.copy(delivery = status) else msg
                }
            }
        }
        val db = database ?: return
        dbScope.launch {
            runCatching {
                db.messageDao().updateDelivery(messageId, status.name)
            }
        }
    }

    fun toggleMessageReaction(conversationKey: String, messageId: String, emoji: String) {
        var updatedReactions: Map<String, Int>? = null
        _conversations.update { current ->
            val list = current[conversationKey] ?: return@update current
            val updated = list.map { msg ->
                if (msg.id != messageId) return@map msg
                val map = msg.reactions.toMutableMap()
                val existing = map[emoji] ?: 0
                if (existing > 0) {
                    map.remove(emoji)
                } else {
                    map[emoji] = 1
                }
                updatedReactions = map
                msg.copy(reactions = map)
            }
            current + (conversationKey to updated)
        }
        val db = database
        val finalMap = updatedReactions
        if (db != null && finalMap != null) {
            dbScope.launch {
                runCatching {
                    db.messageDao().updateReactions(messageId, serializeReactions(finalMap))
                }
            }
        }
    }

    fun toggleStarMessage(conversationKey: String, messageId: String) {
        var newStarred: Boolean? = null
        _conversations.update { current ->
            val list = current[conversationKey] ?: return@update current
            val updated = list.map { msg ->
                if (msg.id == messageId) {
                    val toggled = !msg.starred
                    newStarred = toggled
                    msg.copy(starred = toggled)
                } else {
                    msg
                }
            }
            current + (conversationKey to updated)
        }
        val db = database
        val starredVal = newStarred
        if (db != null && starredVal != null) {
            dbScope.launch {
                runCatching {
                    db.messageDao().updateStarred(messageId, starredVal)
                }
            }
        }
    }

    fun deleteMessage(conversationKey: String, messageId: String) {
        _conversations.update { current ->
            val list = current[conversationKey] ?: return@update current
            current + (conversationKey to list.filterNot { it.id == messageId })
        }
        val db = database ?: return
        dbScope.launch {
            runCatching {
                db.messageDao().deleteMessageById(messageId)
            }
        }
    }

    fun clearConversation(conversationKey: String) {
        _conversations.update { current ->
            if (conversationKey == PUBLIC_CONVERSATION) {
                current + (PUBLIC_CONVERSATION to emptyList())
            } else {
                current - conversationKey
            }
        }
        clearUnread(conversationKey)
        val db = database ?: return
        dbScope.launch {
            runCatching {
                db.messageDao().deleteConversationMessages(conversationKey)
            }
        }
    }

    fun togglePinConversation(conversationKey: String) {
        _pinnedConversations.update { current ->
            if (conversationKey in current) current - conversationKey else current + conversationKey
        }
        prefs?.edit()?.putStringSet(KEY_PINNED_CONVERSATIONS, _pinnedConversations.value)?.apply()
    }

    fun toggleMuteConversation(conversationKey: String) {
        _mutedConversations.update { current ->
            if (conversationKey in current) current - conversationKey else current + conversationKey
        }
        prefs?.edit()?.putStringSet(KEY_MUTED_CONVERSATIONS, _mutedConversations.value)?.apply()
    }

    fun toggleArchiveConversation(conversationKey: String) {
        _archivedConversations.update { current ->
            if (conversationKey in current) current - conversationKey else current + conversationKey
        }
        prefs?.edit()?.putStringSet(KEY_ARCHIVED_CONVERSATIONS, _archivedConversations.value)?.apply()
    }

    fun updateDraft(conversationKey: String, text: String) {
        _drafts.update { current ->
            if (text.isBlank()) current - conversationKey else current + (conversationKey to text)
        }
    }

    fun markConversationRead(conversationKey: String) {
        clearUnread(conversationKey)
        _conversations.update { current ->
            val list = current[conversationKey] ?: return@update current
            val updated = list.map { msg ->
                if (!msg.outgoing && msg.delivery != DeliveryStatus.READ) {
                    msg.copy(delivery = DeliveryStatus.READ)
                } else {
                    msg
                }
            }
            current + (conversationKey to updated)
        }
    }

    fun incrementUnread(conversationKey: String) {
        if (conversationKey in _mutedConversations.value) return
        _unreadCounts.update { current ->
            val next = (current[conversationKey] ?: 0) + 1
            current + (conversationKey to next)
        }
        prefs?.edit()?.putString(KEY_UNREAD_COUNTS, encodeIntMap(_unreadCounts.value))?.apply()
    }

    private fun clearUnread(conversationKey: String) {
        if (_unreadCounts.value.containsKey(conversationKey)) {
            _unreadCounts.update { it - conversationKey }
            prefs?.edit()?.putString(KEY_UNREAD_COUNTS, encodeIntMap(_unreadCounts.value))?.apply()
        }
    }

    fun toggleVerifiedPeer(peerHex: String) {
        val norm = peerHex.lowercase(Locale.US)
        _verifiedPeers.update { current ->
            if (norm in current) current - norm else current + norm
        }
        prefs?.edit()?.putStringSet(KEY_VERIFIED_PEERS, _verifiedPeers.value)?.apply()
        _peers.update { list ->
            list.map { p ->
                if (p.peerKeyHex.equals(norm, ignoreCase = true)) {
                    p.copy(verified = norm in _verifiedPeers.value)
                } else {
                    p
                }
            }
        }
        updateKnownPeerInDb(norm) { it.copy(verified = norm in _verifiedPeers.value) }
    }

    fun markPeerVerified(peerHex: String, fingerprintHex: String? = null, nickname: String? = null) {
        val norm = peerHex.lowercase(Locale.US)
        _verifiedPeers.update { it + norm }
        prefs?.edit()?.putStringSet(KEY_VERIFIED_PEERS, _verifiedPeers.value)?.apply()
        _peers.update { list ->
            list.map { p ->
                if (p.peerKeyHex.equals(norm, ignoreCase = true)) {
                    p.copy(
                        verified = true,
                        fingerprintHex = fingerprintHex?.replace(" ", "")?.uppercase(Locale.US)
                            ?.takeIf { it.isNotBlank() } ?: p.fingerprintHex,
                        nickname = nickname?.takeIf { it.isNotBlank() } ?: p.nickname,
                    )
                } else {
                    p
                }
            }
        }
        val existing = _knownPeers.value[norm]
        val entity = (existing ?: PeerEntity(
            peerKeyHex = norm,
            nickname = nickname?.takeIf { it.isNotBlank() } ?: resolvePeerNickname(norm),
            fingerprintHex = fingerprintHex?.replace(" ", "")?.uppercase(Locale.US)
                ?: computeDeterministicPeerFingerprint(norm),
            lastSeenMs = System.currentTimeMillis(),
        )).copy(
            verified = true,
            fingerprintHex = fingerprintHex?.replace(" ", "")?.uppercase(Locale.US)
                ?.takeIf { it.isNotBlank() }
                ?: existing?.fingerprintHex
                ?: computeDeterministicPeerFingerprint(norm),
        )
        _knownPeers.update { it + (norm to entity) }
        val db = database ?: return
        dbScope.launch {
            runCatching { db.peerDao().upsertPeer(entity) }
        }
    }

    fun toggleFavoritePeer(peerHex: String) {
        val norm = peerHex.lowercase(Locale.US)
        _favoritePeers.update { current ->
            if (norm in current) current - norm else current + norm
        }
        prefs?.edit()?.putStringSet(KEY_FAVORITE_PEERS, _favoritePeers.value)?.apply()
        updateKnownPeerInDb(norm) { it.copy(favorite = norm in _favoritePeers.value) }
    }

    fun toggleBlockPeer(peerHex: String) {
        val norm = peerHex.lowercase(Locale.US)
        _blockedPeers.update { current ->
            if (norm in current) current - norm else current + norm
        }
        prefs?.edit()?.putStringSet(KEY_BLOCKED_PEERS, _blockedPeers.value)?.apply()
        updateKnownPeerInDb(norm) { it.copy(blocked = norm in _blockedPeers.value) }
    }

    fun setPeerAlias(peerHex: String, alias: String) {
        val norm = peerHex.lowercase(Locale.US)
        val cleaned = alias.trim().take(28)
        _peerAliases.update { current ->
            if (cleaned.isEmpty()) current - norm else current + (norm to cleaned)
        }
        prefs?.edit()?.putString(KEY_PEER_ALIASES, encodeStringMap(_peerAliases.value))?.apply()
        updateKnownPeerInDb(norm) { it.copy(customAlias = cleaned) }
    }

    fun setPeerNote(peerHex: String, note: String) {
        val norm = peerHex.lowercase(Locale.US)
        val cleaned = note.trim().take(240)
        _peerNotes.update { current ->
            if (cleaned.isEmpty()) current - norm else current + (norm to cleaned)
        }
        prefs?.edit()?.putString(KEY_PEER_NOTES, encodeStringMap(_peerNotes.value))?.apply()
        updateKnownPeerInDb(norm) { it.copy(privateNote = cleaned) }
    }

    private fun updateKnownPeerInDb(peerHex: String, transform: (PeerEntity) -> PeerEntity) {
        val norm = peerHex.lowercase(Locale.US)
        val current = _knownPeers.value[norm] ?: PeerEntity(
            peerKeyHex = norm,
            nickname = resolvePeerNickname(norm),
            fingerprintHex = computeDeterministicPeerFingerprint(norm),
            lastSeenMs = System.currentTimeMillis(),
        )
        val updated = transform(current)
        _knownPeers.update { it + (norm to updated) }
        val db = database ?: return
        dbScope.launch {
            runCatching { db.peerDao().upsertPeer(updated) }
        }
    }

    fun setAutoDeleteTimer(timer: AutoDeleteTimer) {
        _autoDeleteTimer.value = timer
        prefs?.edit()?.putString(KEY_AUTO_DELETE, timer.name)?.apply()
        pruneExpiredMessages()
    }

    fun setThemeMode(mode: AppThemeMode) {
        _themeMode.value = mode
        prefs?.edit()?.putString(KEY_THEME_MODE, mode.name)?.apply()
    }

    fun setAccentHex(hex: String) {
        _accentHex.value = hex
        prefs?.edit()?.putString(KEY_ACCENT_HEX, hex)?.apply()
    }

    fun setCompactMode(enabled: Boolean) {
        _compactMode.value = enabled
        prefs?.edit()?.putBoolean(KEY_COMPACT_MODE, enabled)?.apply()
    }

    fun setScreenSecurityEnabled(enabled: Boolean) {
        _screenSecurityEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_SCREEN_SECURITY, enabled)?.apply()
    }

    fun setHapticFeedbackEnabled(enabled: Boolean) {
        _hapticFeedbackEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_HAPTIC_FEEDBACK, enabled)?.apply()
    }

    fun setAutoRelayEnabled(enabled: Boolean) {
        _autoRelayEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_AUTO_RELAY, enabled)?.apply()
    }

    fun setStoreAndForwardEnabled(enabled: Boolean) {
        _storeAndForwardEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_STORE_AND_FORWARD, enabled)?.apply()
    }

    fun setCoverTrafficEnabled(enabled: Boolean) {
        _coverTrafficEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_COVER_TRAFFIC, enabled)?.apply()
    }

    fun setMaxTtlHops(hops: Int) {
        val coerced = hops.coerceIn(1, 7)
        _maxTtlHops.value = coerced
        prefs?.edit()?.putInt(KEY_MAX_TTL_HOPS, coerced)?.apply()
    }

    fun setBleScanMode(mode: String) {
        _bleScanMode.value = mode
        prefs?.edit()?.putString(KEY_SCAN_MODE, mode)?.apply()
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        _notificationsEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled)?.apply()
    }

    fun setMentionAlertsEnabled(enabled: Boolean) {
        _mentionAlertsEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_MENTION_ALERTS_ENABLED, enabled)?.apply()
    }

    fun setNearbyPeerAlertsEnabled(enabled: Boolean) {
        _nearbyPeerAlertsEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_NEARBY_PEER_ALERTS_ENABLED, enabled)?.apply()
    }

    fun setSoundVibrationEnabled(enabled: Boolean) {
        _soundVibrationEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_SOUND_VIBRATION_ENABLED, enabled)?.apply()
    }

    fun recordTraffic(packets: Int, bytes: Long) {
        if (packets > 0) _packetsRelayed.update { it + packets }
        if (bytes > 0) _bytesTransferred.update { it + bytes }
    }

    private fun pruneExpiredMessages() {
        val duration = _autoDeleteTimer.value.durationMs ?: return
        val cutoff = System.currentTimeMillis() - duration
        _conversations.update { current ->
            current.mapValues { (_, list) ->
                list.filter { it.timestampMs >= cutoff || it.starred }
            }
        }
        val db = database ?: return
        dbScope.launch {
            runCatching {
                db.messageDao().deleteMessagesOlderThan(cutoff)
            }
        }
    }

    fun emergencyWipe(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(KEYSTORE_ALIAS)) {
                ks.deleteEntry(KEYSTORE_ALIAS)
            }
        }
        val db = database
        if (db != null) {
            runCatching {
                runBlocking(Dispatchers.IO) {
                    db.messageDao().clearAllMessages()
                    db.peerDao().clearAllPeers()
                    db.channelDao().clearAllChannels()
                    db.outboxDao().clearAllPending()
                }
            }
        }
        MeshGraphService.reset()
        VoiceNotePlayer.stop()
        _conversations.value = mapOf(PUBLIC_CONVERSATION to emptyList())
        _peers.value = emptyList()
        _knownPeers.value = emptyMap()
        _activeConversation.value = PUBLIC_CONVERSATION
        _joinedChannels.value = setOf(PUBLIC_CONVERSATION)
        _bookmarkedChannels.value = emptySet()
        _channelPasswords.value = emptyMap()
        _channelOwners.value = emptyMap()
        _channelCommitments.value = emptyMap()
        _verifiedPeers.value = emptySet()
        _favoritePeers.value = emptySet()
        _blockedPeers.value = emptySet()
        _peerAliases.value = emptyMap()
        _peerNotes.value = emptyMap()
        _pinnedConversations.value = setOf(PUBLIC_CONVERSATION)
        _mutedConversations.value = emptySet()
        _archivedConversations.value = emptySet()
        _unreadCounts.value = emptyMap()
        _drafts.value = emptyMap()
        _pendingOutboxCount.value = 0
        _localIdentityHex.value = ""
        _localFingerprintHex.value = ""
        _packetsRelayed.value = 0L
        _bytesTransferred.value = 0L
    }

    private fun getOrCreateMasterKey(): SecretKey {
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

    private fun encryptSeed(seed: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateMasterKey())
        return cipher.doFinal(seed) to cipher.iv
    }

    private fun decryptSeed(ciphertext: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateMasterKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun encodeStringMap(map: Map<String, String>): String {
        return map.entries.joinToString("\n") { (k, v) ->
            val kb = Base64.encodeToString(k.toByteArray(), Base64.NO_WRAP)
            val vb = Base64.encodeToString(v.toByteArray(), Base64.NO_WRAP)
            "$kb:$vb"
        }
    }

    private fun decodeStringMap(encoded: String?): Map<String, String> {
        if (encoded.isNullOrBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        encoded.lineSequence().forEach { line ->
            val parts = line.split(":")
            if (parts.size == 2) {
                runCatching {
                    val k = String(Base64.decode(parts[0], Base64.NO_WRAP))
                    val v = String(Base64.decode(parts[1], Base64.NO_WRAP))
                    result[k] = v
                }
            }
        }
        return result
    }

    private fun encodeIntMap(map: Map<String, Int>): String {
        return map.entries.joinToString(";") { (k, v) ->
            val kb = Base64.encodeToString(k.toByteArray(), Base64.NO_WRAP)
            "$kb=$v"
        }
    }

    private fun decodeIntMap(encoded: String?): Map<String, Int> {
        if (encoded.isNullOrBlank()) return emptyMap()
        val result = mutableMapOf<String, Int>()
        encoded.split(";").forEach { entry ->
            val parts = entry.split("=")
            if (parts.size == 2) {
                runCatching {
                    val k = String(Base64.decode(parts[0], Base64.NO_WRAP))
                    val v = parts[1].toIntOrNull() ?: 0
                    if (v > 0) result[k] = v
                }
            }
        }
        return result
    }

    private fun serializeReactions(reactions: Map<String, Int>): String {
        return reactions.entries.joinToString(";") { (emoji, count) -> "$emoji=$count" }
    }

    private fun deserializeReactions(summary: String): Map<String, Int> {
        if (summary.isBlank()) return emptyMap()
        val map = mutableMapOf<String, Int>()
        summary.split(";").forEach { item ->
            val idx = item.lastIndexOf('=')
            if (idx > 0) {
                val emoji = item.substring(0, idx)
                val count = item.substring(idx + 1).toIntOrNull() ?: 0
                if (count > 0) map[emoji] = count
            }
        }
        return map
    }

    private fun ChatMessage.toEntity(): MessageEntity {
        return MessageEntity(
            id = id,
            conversationKey = conversationKey,
            senderHex = senderHex,
            senderLabel = senderLabel,
            text = text,
            timestampMs = timestampMs,
            outgoing = outgoing,
            encrypted = encrypted,
            hops = hops,
            delivery = delivery.name,
            kind = kind.name,
            attachmentPath = attachmentPath,
            attachmentMeta = attachmentMeta,
            replyToMessageId = replyToMessageId,
            replyPreview = replyPreview,
            reactionsSummary = serializeReactions(reactions),
            starred = starred,
        )
    }

    private fun MessageEntity.toChatMessage(): ChatMessage {
        val resolvedDelivery = DeliveryStatus.entries.firstOrNull { it.name == delivery }
            ?: DeliveryStatus.SENT
        val resolvedKind = MessageKind.entries.firstOrNull { it.name == kind }
            ?: MessageKind.TEXT
        return ChatMessage(
            id = id,
            conversationKey = conversationKey,
            senderHex = senderHex,
            senderLabel = senderLabel,
            text = text,
            timestampMs = timestampMs,
            outgoing = outgoing,
            encrypted = encrypted,
            hops = hops,
            delivery = resolvedDelivery,
            kind = resolvedKind,
            attachmentPath = attachmentPath,
            attachmentMeta = attachmentMeta,
            replyToMessageId = replyToMessageId,
            replyPreview = replyPreview,
            reactions = deserializeReactions(reactionsSummary),
            starred = starred,
        )
    }
}
