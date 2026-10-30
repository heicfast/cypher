package chat.cypher.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.concurrent.withLock

/**
 * Encrypted local history: conversations + messages + unread counts, written
 * as one JSON blob sealed with an AndroidKeyStore AES-256-GCM key.
 *
 * (Parity note: bitchat persists DMs in an encrypted SQLite store; Cypher's
 * v0.4 uses an encrypted JSON snapshot — wire security is the Noise session;
 * at-rest security is the Keystore-wrapped AES-GCM seal.)
 *
 * On-disk format: `iv (12 bytes) ‖ AES-GCM(ciphertext+tag)`.
 */
class SecureHistoryStore(context: Context) {
    private val file: File = File(context.filesDir, "history.bin")
    private val lock = ReentrantLock()
    private val rng = SecureRandom()

    data class Snapshot(
        val messages: List<CypherMessage>,
        val unread: Map<String, Int>,
        val drafts: Map<String, String>,
    )

    fun load(): Snapshot = lock.withLock {
        if (!file.exists()) return Snapshot(emptyList(), emptyMap(), emptyMap())
        try {
            val blob = file.readBytes()
            require(blob.size > 12) { "truncated" }
            val iv = blob.copyOfRange(0, 12)
            val ct = blob.copyOfRange(12, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            val plain = cipher.doFinal(ct)
            parseSnapshot(JSONObject(String(plain, Charsets.UTF_8)))
        } catch (_: Exception) {
            Snapshot(emptyList(), emptyMap(), emptyMap()) // corrupted → fresh start
        }
    }

    fun save(messages: List<CypherMessage>, unread: Map<String, Int>, drafts: Map<String, String>) {
        lock.withLock {
            try {
                val json = JSONObject()
                    .put("v", 1)
                    .put("messages", JSONArray().apply { messages.forEach { put(it.toJson()) } })
                    .put("unread", JSONObject(unread))
                    .put("drafts", JSONObject(drafts))
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key())
                val ct = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
                val tmp = File(file.parentFile, "history.tmp")
                tmp.writeBytes(cipher.iv + ct)
                if (file.exists()) file.delete()
                tmp.renameTo(file)
            } catch (_: Exception) {
                // History persistence is best-effort; never crash the mesh.
            }
        }
    }

    fun wipe() = lock.withLock {
        file.delete()
    }

    // ---- JSON codec ----------------------------------------------------------

    private fun CypherMessage.toJson() = JSONObject()
        .put("id", id)
        .put("c", conversationId)
        .put("s", senderId)
        .put("n", senderNickname)
        .put("m", content)
        .put("t", timestampMs)
        .put("o", isOwn)
        .put("k", kind.name)
        .put("d", delivery?.name ?: JSONObject.NULL)
        .put("fn", fileName ?: JSONObject.NULL)
        .put("mt", mimeType ?: JSONObject.NULL)
        .put("lp", localPath ?: JSONObject.NULL)
        .put("bs", byteSize)

    private fun parseSnapshot(root: JSONObject): Snapshot {
        val messages = ArrayList<CypherMessage>()
        val arr = root.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val deliveryName = o.optString("d")
            messages.add(
                CypherMessage(
                    id = o.optString("id"),
                    conversationId = o.optString("c"),
                    senderId = o.optString("s"),
                    senderNickname = o.optString("n"),
                    content = o.optString("m"),
                    timestampMs = o.optLong("t"),
                    isOwn = o.optBoolean("o"),
                    kind = runCatching { MessageKind.valueOf(o.optString("k")) }
                        .getOrDefault(MessageKind.TEXT),
                    delivery = if (deliveryName.isNotEmpty() && deliveryName != "null") {
                        runCatching { Delivery.valueOf(deliveryName) }.getOrNull()
                    } else null,
                    fileName = o.optString("fn").takeIf { it.isNotEmpty() && it != "null" },
                    mimeType = o.optString("mt").takeIf { it.isNotEmpty() && it != "null" },
                    localPath = o.optString("lp").takeIf { it.isNotEmpty() && it != "null" },
                    byteSize = o.optLong("bs"),
                )
            )
        }
        val unread = mutableMapOf<String, Int>()
        val u = root.optJSONObject("unread")
        u?.keys()?.forEach { k -> u.optInt(k).let { if (it > 0) unread[k] = it } }
        val drafts = mutableMapOf<String, String>()
        val d = root.optJSONObject("drafts")
        d?.keys()?.forEach { k -> d.optString(k).takeIf { it.isNotEmpty() }?.let { drafts[k] = it } }
        return Snapshot(messages, unread, drafts)
    }

    // ---- AndroidKeyStore AES-256-GCM key ------------------------------------

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ALIAS = "cypher_history_key"
    }
}
