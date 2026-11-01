package chat.cypher.android.core

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.PriorityQueue
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Live BLE mesh topology graph, Dijkstra shortest-path route planner,
 * PBKDF2 + AES-256-GCM password-protected channel encryption, and
 * cryptographic peer verification service ported from bitchat-android.
 */
object MeshGraphService {
    data class GraphNode(
        val peerID: String,
        val nickname: String?,
        val hops: Int = 1,
        val rssi: Int? = null,
    )

    data class GraphEdge(
        val a: String,
        val b: String,
        val isConfirmed: Boolean,
        val announcedBy: String? = null,
    )

    data class GraphSnapshot(
        val nodes: List<GraphNode> = emptyList(),
        val edges: List<GraphEdge> = emptyList(),
    )

    private val lock = Any()
    private val nicknames = mutableMapOf<String, String?>()
    private val nodeHops = mutableMapOf<String, Int>()
    private val nodeRssi = mutableMapOf<String, Int?>()
    private val latestAnnouncementNeighbors = mutableMapOf<String, Set<String>>()
    private val lastTimestamp = mutableMapOf<String, ULong>()

    private val _graphState = MutableStateFlow(GraphSnapshot())
    val graphState: StateFlow<GraphSnapshot> = _graphState.asStateFlow()

    fun updateFromAnnouncement(
        originID: String,
        originNickname: String?,
        neighbors: List<String>?,
        timestamp: ULong,
        hops: Int = 1,
        rssi: Int? = null,
    ) {
        val id = originID.lowercase(Locale.US)
        synchronized(lock) {
            val prevTs = lastTimestamp[id]
            if (prevTs != null && prevTs > timestamp) return
            lastTimestamp[id] = timestamp
            if (!originNickname.isNullOrBlank()) {
                nicknames[id] = originNickname
            } else if (!nicknames.containsKey(id)) {
                nicknames[id] = null
            }
            nodeHops[id] = hops.coerceIn(1, 7)
            if (rssi != null) {
                nodeRssi[id] = rssi
            }
            if (neighbors != null) {
                val cleaned = neighbors
                    .map { it.lowercase(Locale.US) }
                    .filter { it != id && it.isNotBlank() }
                    .toSet()
                latestAnnouncementNeighbors[id] = cleaned
                cleaned.forEach { n ->
                    if (!nicknames.containsKey(n)) {
                        nicknames[n] = null
                    }
                    if (!nodeHops.containsKey(n)) {
                        nodeHops[n] = (hops + 1).coerceIn(1, 7)
                    }
                }
            }
            publishLocked()
        }
    }

    fun syncPeersSnapshot(
        localPeerId: String,
        localNickname: String,
        peers: List<PeerEntry>,
    ) {
        val localId = localPeerId.lowercase(Locale.US)
        synchronized(lock) {
            if (localId.isNotBlank()) {
                nicknames[localId] = localNickname
                nodeHops[localId] = 0
            }
            val directNeighbors = mutableSetOf<String>()
            for (peer in peers) {
                val pid = peer.peerKeyHex.lowercase(Locale.US)
                if (pid.isBlank()) continue
                nicknames[pid] = peer.nickname
                nodeHops[pid] = peer.hops.coerceIn(1, 7)
                nodeRssi[pid] = peer.rssi
                if (peer.hops <= 1) {
                    directNeighbors.add(pid)
                }
                if (peer.routePath.isNotEmpty()) {
                    val neighborsForPeer = latestAnnouncementNeighbors[pid]?.toMutableSet() ?: mutableSetOf()
                    peer.routePath.forEach { hopId ->
                        val normHop = hopId.lowercase(Locale.US)
                        if (normHop.isNotBlank() && normHop != pid) {
                            neighborsForPeer.add(normHop)
                        }
                    }
                    latestAnnouncementNeighbors[pid] = neighborsForPeer
                }
            }
            if (localId.isNotBlank()) {
                latestAnnouncementNeighbors[localId] = directNeighbors
                directNeighbors.forEach { pid ->
                    val set = latestAnnouncementNeighbors[pid]?.toMutableSet() ?: mutableSetOf()
                    set.add(localId)
                    latestAnnouncementNeighbors[pid] = set
                }
            }
            publishLocked()
        }
    }

    fun removePeer(peerID: String) {
        val id = peerID.lowercase(Locale.US)
        synchronized(lock) {
            latestAnnouncementNeighbors.remove(id)
            lastTimestamp.remove(id)
            nicknames.remove(id)
            nodeHops.remove(id)
            nodeRssi.remove(id)
            latestAnnouncementNeighbors.keys.toList().forEach { k ->
                val set = latestAnnouncementNeighbors[k] ?: return@forEach
                if (id in set) {
                    latestAnnouncementNeighbors[k] = set - id
                }
            }
            publishLocked()
        }
    }

    fun reset() {
        synchronized(lock) {
            nicknames.clear()
            nodeHops.clear()
            nodeRssi.clear()
            latestAnnouncementNeighbors.clear()
            lastTimestamp.clear()
            _graphState.value = GraphSnapshot()
        }
    }

    private fun publishLocked() {
        val allPeers = mutableSetOf<String>()
        allPeers.addAll(nicknames.keys)
        latestAnnouncementNeighbors.forEach { (a, set) ->
            allPeers.add(a)
            allPeers.addAll(set)
        }

        data class PairKey(val x: String, val y: String) {
            init {
                require(x <= y)
            }
        }

        fun key(a: String, b: String): PairKey =
            if (a <= b) PairKey(a, b) else PairKey(b, a)

        val seenPairs = mutableSetOf<PairKey>()
        val edges = mutableListOf<GraphEdge>()

        for ((a, setA) in latestAnnouncementNeighbors) {
            for (b in setA) {
                if (a == b) continue
                val k = key(a, b)
                if (!seenPairs.add(k)) continue
                val setB = latestAnnouncementNeighbors[b]
                val bHasA = setB?.contains(a) == true
                if (bHasA) {
                    edges.add(GraphEdge(a = k.x, b = k.y, isConfirmed = true, announcedBy = null))
                } else {
                    edges.add(GraphEdge(a = k.x, b = k.y, isConfirmed = false, announcedBy = a))
                }
            }
        }

        val nodes = allPeers.map { pid ->
            GraphNode(
                peerID = pid,
                nickname = nicknames[pid],
                hops = nodeHops[pid] ?: 1,
                rssi = nodeRssi[pid],
            )
        }.sortedBy { it.peerID }
        val sortedEdges = edges.sortedWith(compareBy<GraphEdge>({ it.a }, { it.b }))
        _graphState.value = GraphSnapshot(nodes = nodes, edges = sortedEdges)
    }
}

object RoutePlanner {
    /**
     * Computes shortest path between src and dst using Dijkstra over the current MeshGraphService snapshot.
     * Confirmed edges have weight 1, unconfirmed edges have weight 2.
     */
    fun shortestPath(
        src: String,
        dst: String,
        snapshot: MeshGraphService.GraphSnapshot = MeshGraphService.graphState.value,
    ): List<String>? {
        val s = src.lowercase(Locale.US)
        val d = dst.lowercase(Locale.US)
        if (s == d) return listOf(s)

        val adj = mutableMapOf<String, MutableList<Pair<String, Int>>>()
        snapshot.nodes.forEach { node -> adj[node.peerID] = mutableListOf() }
        snapshot.edges.forEach { edge ->
            val w = if (edge.isConfirmed) 1 else 2
            adj.getOrPut(edge.a) { mutableListOf() }.add(edge.b to w)
            adj.getOrPut(edge.b) { mutableListOf() }.add(edge.a to w)
        }

        if (!adj.containsKey(s) || !adj.containsKey(d)) return null

        val dist = mutableMapOf<String, Int>().withDefault { Int.MAX_VALUE }
        val prev = mutableMapOf<String, String?>()
        val pq = PriorityQueue<Pair<String, Int>>(compareBy { it.second })

        dist[s] = 0
        pq.add(s to 0)

        while (pq.isNotEmpty()) {
            val (u, du) = pq.poll() ?: break
            if (du > dist.getValue(u)) continue
            if (u == d) break
            val neighbors = adj[u] ?: emptyList()
            for ((v, w) in neighbors) {
                val alt = if (du == Int.MAX_VALUE) Int.MAX_VALUE else du + w
                if (alt < dist.getValue(v)) {
                    dist[v] = alt
                    prev[v] = u
                    pq.add(v to alt)
                }
            }
        }

        if (dist.getValue(d) == Int.MAX_VALUE) return null
        val path = ArrayDeque<String>()
        var cur: String? = d
        while (cur != null) {
            path.addFirst(cur)
            cur = prev[cur]
        }
        return path.toList()
    }
}

/**
 * Password-protected channel encryption using PBKDF2-HMAC-SHA256 + AES-256-GCM,
 * matching bitchat-android's ChannelManager.
 */
object ChannelCrypto {
    private const val PBKDF2_ITERATIONS = 60_000
    private const val KEY_BITS = 256
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    const val ENCRYPTED_PREFIX = "ENCv1:"

    fun deriveChannelKey(channelTag: String, password: String): SecretKeySpec {
        val normalizedTag = Geohash.normalizeTag(channelTag)
        val salt = "cipher-channel-v1:$normalizedTag".toByteArray(StandardCharsets.UTF_8)
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val raw = factory.generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }

    fun computeCommitmentHex(channelTag: String, password: String): String {
        val key = deriveChannelKey(channelTag, password).encoded
        val digest = MessageDigest.getInstance("SHA-256").digest(key)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun encryptChannelPayload(channelTag: String, password: String, plaintext: String): String {
        val key = deriveChannelKey(channelTag, password)
        val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val aad = Geohash.normalizeTag(channelTag).toByteArray(StandardCharsets.UTF_8)
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        val b64 = Base64.encodeToString(combined, Base64.NO_WRAP)
        return "$ENCRYPTED_PREFIX$b64"
    }

    fun decryptChannelPayload(channelTag: String, password: String, wirePayload: String): String? {
        if (!wirePayload.startsWith(ENCRYPTED_PREFIX)) return wirePayload
        return runCatching {
            val encoded = wirePayload.removePrefix(ENCRYPTED_PREFIX)
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size <= GCM_IV_BYTES) return null
            val iv = combined.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = combined.copyOfRange(GCM_IV_BYTES, combined.size)
            val key = deriveChannelKey(channelTag, password)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val aad = Geohash.normalizeTag(channelTag).toByteArray(StandardCharsets.UTF_8)
            cipher.updateAAD(aad)
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        }.getOrNull()
    }
}

/**
 * Cryptographic out-of-band QR verification and over-the-air challenge-response protocol,
 * modeled on bitchat-android's VerificationService.
 */
object VerificationService {
    data class VerificationQR(
        val v: Int = 1,
        val peerIdHex: String,
        val fingerprintHex: String,
        val nickname: String,
        val ts: Long = System.currentTimeMillis() / 1000L,
    ) {
        fun toUrlString(): String {
            return Uri.Builder()
                .scheme("cipher")
                .authority("verify")
                .appendQueryParameter("v", v.toString())
                .appendQueryParameter("peer", peerIdHex.lowercase(Locale.US))
                .appendQueryParameter("fp", fingerprintHex.uppercase(Locale.US))
                .appendQueryParameter("nick", nickname)
                .appendQueryParameter("ts", ts.toString())
                .build()
                .toString()
        }

        companion object {
            fun fromUrlString(raw: String, maxAgeSeconds: Long = 86_400L): VerificationQR? {
                return runCatching {
                    val uri = Uri.parse(raw.trim())
                    val scheme = uri.scheme?.lowercase(Locale.US)
                    if (scheme != "cipher" && scheme != "bitchat") return null
                    if (uri.authority?.lowercase(Locale.US) != "verify") return null
                    val v = uri.getQueryParameter("v")?.toIntOrNull() ?: 1
                    val peer = uri.getQueryParameter("peer")?.trim().orEmpty()
                    val fp = uri.getQueryParameter("fp")?.trim().orEmpty()
                    val nick = uri.getQueryParameter("nick")?.trim().orEmpty()
                    val ts = uri.getQueryParameter("ts")?.toLongOrNull() ?: 0L
                    if (peer.isBlank() || fp.length < 16) return null
                    val nowSec = System.currentTimeMillis() / 1000L
                    if (ts > 0L && kotlin.math.abs(nowSec - ts) > maxAgeSeconds) return null
                    VerificationQR(
                        v = v,
                        peerIdHex = peer.lowercase(Locale.US),
                        fingerprintHex = fp.uppercase(Locale.US),
                        nickname = nick,
                        ts = ts,
                    )
                }.getOrNull()
            }
        }
    }

    fun generateNonceHex(byteCount: Int = 16): String {
        val bytes = ByteArray(byteCount)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun computeChallengeDigestHex(
        nonceHex: String,
        responderFingerprintHex: String,
        challengerFingerprintHex: String,
    ): String {
        val cleanNonce = nonceHex.trim().lowercase(Locale.US)
        val cleanResp = responderFingerprintHex.replace(" ", "").uppercase(Locale.US)
        val cleanChal = challengerFingerprintHex.replace(" ", "").uppercase(Locale.US)
        val material = "cipher-verify-v1:$cleanNonce:$cleanResp:$cleanChal"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    fun buildChallengeFrame(nonceHex: String, challengerFingerprintHex: String): String {
        val cleanFp = challengerFingerprintHex.replace(" ", "").uppercase(Locale.US)
        return "[VERIFY_CHALLENGE:$nonceHex:$cleanFp]"
    }

    fun buildResponseFrame(
        nonceHex: String,
        responderFingerprintHex: String,
        challengerFingerprintHex: String,
    ): String {
        val cleanRespFp = responderFingerprintHex.replace(" ", "").uppercase(Locale.US)
        val digest = computeChallengeDigestHex(nonceHex, cleanRespFp, challengerFingerprintHex)
        return "[VERIFY_RESPONSE:$nonceHex:$cleanRespFp:$digest]"
    }
}
