package chat.cypher.android.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Local persistence for the UI layer — the Kotlin-side twin of bitchat's
 * conversation repository: messages survive restarts, the engine remains
 * the source of truth for the wire.
 *
 * Layout:
 *  - SharedPreferences `cypher_store`: nickname, read peer IDs, favorites,
 *    blocked peers, verified fingerprints (re-applied to the engine on boot).
 *  - `messages/mesh.jsonl` + `messages/dm_<peerId>.jsonl`: one JSON object
 *    per line, capped per conversation (bitchat caps 1000/conversation).
 */
object MeshStore {

    private const val PREFS = "cypher_store"
    private const val KEY_NICKNAME = "nickname"
    private const val KEY_FAVORITES = "favorites"
    private const val KEY_BLOCKED = "blocked"
    private const val KEY_READ = "read_senders"
    private const val KEY_ONBOARDING_DONE = "onboarding_complete"

    private const val MESSAGES_DIR = "messages"
    private const val MESH_FILE = "mesh.jsonl"
    private const val PER_CONVERSATION_CAP = 500

    private lateinit var appContext: Context
    private val persistLock = Any()

    fun init(context: Context) {
        appContext = context.applicationContext
        File(messagesDir(), MESSAGES_DIR).mkdirs()
    }

    // ---- identity / preferences ------------------------------------------------

    /** The persisted nickname, generating an `anon####` on first run. */
    fun nickname(): String {
        val prefs = prefs()
        val saved = prefs.getString(KEY_NICKNAME, null)
        if (!saved.isNullOrBlank()) return saved
        val generated = "anon${(1000..9999).random()}"
        prefs.edit().putString(KEY_NICKNAME, generated).apply()
        return generated
    }

    fun setNickname(nickname: String) {
        prefs().edit().putString(KEY_NICKNAME, nickname).apply()
    }

    var onboardingComplete: Boolean
        get() = prefs().getBoolean(KEY_ONBOARDING_DONE, false)
        set(value) = prefs().edit().putBoolean(KEY_ONBOARDING_DONE, value).apply()

    fun favorites(): Set<String> = prefs().getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()

    fun setFavorite(peerId: String, favorite: Boolean) {
        val current = favorites().toMutableSet()
        if (favorite) current.add(peerId) else current.remove(peerId)
        prefs().edit().putStringSet(KEY_FAVORITES, current).apply()
    }

    fun blocked(): Set<String> = prefs().getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet()

    fun setBlocked(peerId: String, blocked: Boolean) {
        val current = blocked().toMutableSet()
        if (blocked) current.add(peerId) else current.remove(peerId)
        prefs().edit().putStringSet(KEY_BLOCKED, current).apply()
    }

    /** Peers whose DM conversations have been opened (no unread badge). */
    fun readPeers(): Set<String> = prefs().getStringSet(KEY_READ, emptySet()) ?: emptySet()

    fun markPeerRead(peerId: String) {
        val current = readPeers().toMutableSet()
        current.add(peerId)
        prefs().edit().putStringSet(KEY_READ, current).apply()
    }

    // ---- messages ---------------------------------------------------------------

    /**
     * The persisted message record. `kind` discriminates the UI rendering;
     * `localPath` points at stored media for received files.
     */
    data class StoredMessage(
        val id: String,
        val senderPeerId: String,
        val senderNickname: String,
        val content: String,
        val kind: String, // text | image | file | voice | system
        val timestampMs: Long,
        val mine: Boolean,
        val peerId: String, // conversation key: "mesh" or the DM peer id
        val status: String, // sending | sent | delivered | read | failed
        val fileName: String? = null,
        val mimeType: String? = null,
        val localPath: String? = null,
    )

    private val cache = ConcurrentHashMap<String, MutableList<StoredMessage>>()

    fun conversationFile(peerId: String): File {
        val name = if (peerId == "mesh") MESH_FILE else "dm_$peerId.jsonl"
        return File(File(messagesDir(), MESSAGES_DIR), name)
    }

    fun loadConversation(peerId: String): List<StoredMessage> {
        cache[peerId]?.let { return it.toList() }
        val file = conversationFile(peerId)
        if (!file.exists()) return emptyList()
        val messages = mutableListOf<StoredMessage>()
        file.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            runCatching { messages.add(messageFromJson(JSONObject(line))) }
        }
        val trimmed = if (messages.size > PER_CONVERSATION_CAP) {
            messages.takeLast(PER_CONVERSATION_CAP)
        } else {
            messages
        }
        cache[peerId] = trimmed.toMutableList()
        return trimmed
    }

    fun append(message: StoredMessage) {
        synchronized(persistLock) {
            val list = cache.getOrPut(message.peerId) {
                loadConversation(message.peerId).toMutableList()
            }
            // Idempotent append (echo + self-sync replay can double-deliver).
            if (list.any { it.id == message.id }) return
            list.add(message)
            if (list.size > PER_CONVERSATION_CAP) {
                while (list.size > PER_CONVERSATION_CAP) list.removeAt(0)
            }
            writeConversation(message.peerId, list)
        }
    }

    fun updateStatus(peerId: String, messageId: String, status: String) {
        synchronized(persistLock) {
            val list = cache.getOrPut(peerId) { mutableListOf() }
            val index = list.indexOfFirst { it.id == messageId }
            if (index >= 0) {
                list[index] = list[index].copy(status = status)
                writeConversation(peerId, list)
            }
        }
    }

    /** All DM conversation peer ids that have persisted history. */
    fun conversationPeerIds(): List<String> {
        val dir = File(messagesDir(), MESSAGES_DIR)
        return dir.listFiles { f -> f.name.startsWith("dm_") }
            ?.map { it.name.removePrefix("dm_").removeSuffix(".jsonl") }
            ?: emptyList()
    }

    fun deleteConversation(peerId: String) {
        synchronized(persistLock) {
            cache.remove(peerId)
            conversationFile(peerId).delete()
        }
    }

    /** Panic wipe: every stored message + preference (identity is wiped by
     *  the caller — a fresh file means a fresh peer id). */
    fun wipeAll() {
        synchronized(persistLock) {
            cache.clear()
            File(messagesDir(), MESSAGES_DIR).deleteRecursively()
            prefs().edit().clear().apply()
        }
    }

    // ---- internals -----------------------------------------------------------

    private fun writeConversation(peerId: String, messages: List<StoredMessage>) {
        val file = conversationFile(peerId)
        val tmp = File(file.absolutePath + ".tmp")
        tmp.bufferedWriter().use { writer ->
            for (message in messages) {
                writer.write(messageToJson(message).toString())
                writer.write("\n")
            }
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private fun messageToJson(m: StoredMessage): JSONObject = JSONObject().apply {
        put("id", m.id)
        put("senderPeerId", m.senderPeerId)
        put("senderNickname", m.senderNickname)
        put("content", m.content)
        put("kind", m.kind)
        put("timestampMs", m.timestampMs)
        put("mine", m.mine)
        put("peerId", m.peerId)
        put("status", m.status)
        m.fileName?.let { put("fileName", it) }
        m.mimeType?.let { put("mimeType", it) }
        m.localPath?.let { put("localPath", it) }
    }

    private fun messageFromJson(o: JSONObject): StoredMessage = StoredMessage(
        id = o.optString("id"),
        senderPeerId = o.optString("senderPeerId"),
        senderNickname = o.optString("senderNickname"),
        content = o.optString("content"),
        kind = o.optString("kind", "text"),
        timestampMs = o.optLong("timestampMs"),
        mine = o.optBoolean("mine"),
        peerId = o.optString("peerId"),
        status = o.optString("status", "sent"),
        fileName = if (o.has("fileName")) o.optString("fileName") else null,
        mimeType = if (o.has("mimeType")) o.optString("mimeType") else null,
        localPath = if (o.has("localPath")) o.optString("localPath") else null,
    )

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun messagesDir(): File = appContext.filesDir
}
