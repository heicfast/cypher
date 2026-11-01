package chat.cypher.android.core

import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Cryptographic fingerprint, RSSI distance estimation, and peer identity utilities.
 *
 * Computes deterministic SHA-256 fingerprints over peer static Noise XX keys
 * (or peer ID fallback prior to key exchange) for out-of-band safety verification.
 */
object PeerIdentity {

    /**
     * Returns a 64-character lowercase hex SHA-256 digest of [publicKeyHex] (if non-blank)
     * or [peerIdHex].
     */
    fun fingerprintHex(peerIdHex: String, publicKeyHex: String = ""): String {
        val source = publicKeyHex.trim().ifEmpty { peerIdHex.trim() }.lowercase()
        // If already a 64-char hex fingerprint, preserve it directly
        if (source.length == 64 && source.all { it in '0'..'9' || it in 'a'..'f' }) {
            return source
        }
        val rawBytes = hexToBytesOrNull(source) ?: source.encodeToByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(rawBytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Formats a 64-char hex fingerprint into 4 lines of 4 groups of 4 uppercase hex chars:
     * `26C0 A264 57A1 895C`
     */
    fun formatFingerprintGrid(fingerprintHex: String): List<String> {
        val clean = fingerprintHex.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }
            .padEnd(64, '0')
            .take(64)
        return clean.chunked(4).chunked(4).map { row -> row.joinToString(" ") }
    }

    /**
     * Numeric 12-digit safety code (grouped as `12345 67890 ...`) derived from both peers'
     * fingerprints in canonical lexicographical order so both devices show the exact same code.
     */
    fun mutualSafetyNumber(myFingerprintHex: String, peerFingerprintHex: String): String {
        val ordered = listOf(myFingerprintHex.lowercase(), peerFingerprintHex.lowercase()).sorted()
        val combined = (ordered[0] + ":" + ordered[1]).encodeToByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(combined)
        val groups = (0 until 6).map { idx ->
            val offset = idx * 4
            val v = ((hash[offset].toInt() and 0xFF) shl 24) or
                ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                (hash[offset + 3].toInt() and 0xFF)
            "%05d".format(abs(v.toLong()) % 100_000L)
        }
        return groups.chunked(3).joinToString("\n") { it.joinToString("  ") }
    }

    /**
     * Estimates distance in meters from BLE RSSI using the log-distance path loss model
     * (`TxPower = -59 dBm`, path loss exponent `n = 2.2`), scaled by multi-hop relay count.
     */
    fun estimateDistanceMeters(rssi: Int?, hops: Int = 1): Int {
        val safeHops = hops.coerceIn(1, 7)
        if (rssi == null) {
            return (14 * safeHops).coerceIn(5, 120)
        }
        val txPower = -59.0
        val pathLossExponent = 2.2
        val singleHopMeters = 10.0.pow((txPower - rssi.toDouble()) / (10.0 * pathLossExponent))
        return (singleHopMeters * (0.75 + 0.25 * safeHops)).roundToInt().coerceIn(2, 150)
    }

    /**
     * Formats peer distance and hop count exactly as `~ 12 m • 1 hop` or `~ 35 m • 2 hops`.
     */
    fun formatDistanceAndHops(distanceMeters: Int, hops: Int): String {
        val safeHops = hops.coerceIn(1, 7)
        val hopWord = if (safeHops == 1) "1 hop" else "$safeHops hops"
        return "~ $distanceMeters m • $hopWord"
    }

    /** Short `@nickname#a1b2` handle used in headers and mentions. */
    fun formatHandle(nickname: String, peerIdHex: String): String {
        val cleanNick = nickname.trim().ifEmpty { "peer-${peerIdHex.take(4)}" }
        if (cleanNick.startsWith("@") || cleanNick.contains("#")) return cleanNick
        val suffix = peerIdHex.takeLast(4).lowercase()
        return "@$cleanNick#$suffix"
    }

    /** Short `a1b2 · c3d4` display ID. */
    fun shortId(peerIdHex: String): String {
        val clean = peerIdHex.trim().lowercase()
        if (clean.length <= 8) return clean
        return "${clean.take(4)}…${clean.takeLast(4)}"
    }

    /** Relative human-readable time since [epochMs]. */
    fun relativeTime(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        if (epochMs <= 0L) return "never"
        val diffSec = ((nowMs - epochMs) / 1000L).coerceAtLeast(0L)
        return when {
            diffSec < 15L -> "now"
            diffSec < 60L -> "${diffSec}s"
            diffSec < 3600L -> "${diffSec / 60L}m"
            diffSec < 86_400L -> "${diffSec / 3600L}h"
            diffSec < 604_800L -> "${diffSec / 86_400L}d"
            else -> "${diffSec / 604_800L}w"
        }
    }

    private fun hexToBytesOrNull(hex: String): ByteArray? {
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi == -1 || lo == -1) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
