package chat.cypher.android.core

import androidx.compose.ui.graphics.Color
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

data class PeerUiModel(
    val peerKey: String,
    val nickname: String,
    val connected: Boolean,
    val sessionEstablished: Boolean,
    val rssi: Int? = null,
    val hops: Int = 1,
    val verified: Boolean = false,
    val distanceMeters: Int? = null,
)

data class CreativeProfileIdentity(
    val displayName: String,
    val username: String,
)

/**
 * Cryptographic fingerprint, deterministic peer color, RSSI distance estimation,
 * creative mesh moniker generation, and peer identity utilities.
 */
object PeerIdentity {

    private val CreativeAdjectives = listOf(
        "cipher", "neon", "shadow", "quantum", "cobalt",
        "aether", "velvet", "solaris", "onyx", "arctic",
        "nova", "stellar", "prism", "vortex", "obsidian",
        "zenith", "lunar", "ember", "crimson", "astral",
    )

    private val CreativeNouns = listOf(
        "lynx", "raven", "fox", "falcon", "voyager",
        "drifter", "nomad", "specter", "sentinel", "orbit",
        "vector", "warden", "spark", "atlas", "phoenix",
        "pilot", "pulse", "geist", "wolf", "horizon",
    )

    val PresetAvatars: List<Pair<String, String>> = listOf(
        "preset:neon_lynx" to "🦊",
        "preset:cipher_falcon" to "🦅",
        "preset:quantum_wolf" to "🐺",
        "preset:astral_owl" to "🦉",
        "preset:solaris_dragon" to "🐉",
        "preset:arctic_tiger" to "🐯",
        "preset:onyx_panther" to "🐆",
        "preset:nova_orbit" to "🪐",
    )

    private val DarkPeerPalette = listOf(
        Color(0xFFA78BFA), // Lavender Purple
        Color(0xFFF6AD55), // Warm Amber
        Color(0xFF4ADE80), // Emerald Green
        Color(0xFFC084FC), // Violet
        Color(0xFFF87171), // Coral Rose
        Color(0xFF60A5FA), // Royal Blue
        Color(0xFF38BDF8), // Ocean Cyan
        Color(0xFFF472B6), // Pink
    )

    private val LightPeerPalette = listOf(
        Color(0xFF6D28D9),
        Color(0xFFD97706),
        Color(0xFF15803D),
        Color(0xFF7E22CE),
        Color(0xFFDC2626),
        Color(0xFF1D4ED8),
        Color(0xFF0284C7),
        Color(0xFFBE185D),
    )

    /**
     * Generates a creative, memorable default profile identity (both Display Name and `@username`)
     * from a 32-byte seed or random entropy, replacing generic `anon1234` handles.
     */
    fun generateCreativeProfileIdentity(seed: ByteArray? = null): CreativeProfileIdentity {
        val adjIdx: Int
        val nounIdx: Int
        val tagNum: Int
        if (seed != null && seed.size >= 6) {
            val b0 = (seed[0].toInt() and 0xFF)
            val b1 = (seed[1].toInt() and 0xFF)
            val b2 = ((seed[2].toInt() and 0xFF) shl 8) or (seed[3].toInt() and 0xFF)
            adjIdx = b0 % CreativeAdjectives.size
            nounIdx = b1 % CreativeNouns.size
            tagNum = 10 + (b2 % 89)
        } else {
            val rng = SecureRandom()
            adjIdx = rng.nextInt(CreativeAdjectives.size)
            nounIdx = rng.nextInt(CreativeNouns.size)
            tagNum = 10 + rng.nextInt(89)
        }
        val adj = CreativeAdjectives[adjIdx]
        val noun = CreativeNouns[nounIdx]
        val display = "${adj.replaceFirstChar { it.titlecase(Locale.US) }} ${noun.replaceFirstChar { it.titlecase(Locale.US) }}"
        val username = "${adj}_${noun}_$tagNum"
        return CreativeProfileIdentity(displayName = display, username = username)
    }

    /**
     * Returns true if a nickname looks like an auto-generated placeholder (`anon`, `anon1234`, `anon#...`).
     */
    fun isLegacyAnonNickname(nickname: String?): Boolean {
        if (nickname.isNullOrBlank()) return true
        val cleaned = nickname.trim().removePrefix("@").lowercase(Locale.US)
        if (cleaned == "anon" || cleaned == "anonymous") return true
        if (cleaned.matches(Regex("^anon[#_\\-]?[0-9a-f]{1,8}$"))) return true
        return false
    }

    /**
     * Deterministically derives a clean, creative handle for a remote peer when its announced
     * nickname is empty or generic `anon`.
     */
    fun creativeHandleForPeer(peerIdHex: String): String {
        val norm = peerIdHex.trim().lowercase(Locale.US)
        if (norm.isBlank()) return "cipher_node"
        var h1 = 2166136261L
        for (ch in norm) {
            h1 = (h1 xor ch.code.toLong()) * 16777619L
        }
        val adj = CreativeAdjectives[(abs(h1) % CreativeAdjectives.size).toInt()]
        val noun = CreativeNouns[(abs(h1 ushr 8) % CreativeNouns.size).toInt()]
        val suffix = norm.take(3)
        return "${adj}_${noun}#$suffix"
    }

    fun formatDisplayNameFromUsername(username: String): String {
        val clean = username.trim().removePrefix("@")
        if (clean.isBlank()) return "Cipher Node"
        val withoutHash = clean.substringBefore('#')
        val parts = withoutHash.split('_', '-', '.', ' ').filter { it.isNotBlank() }
        if (parts.isEmpty()) return clean
        val words = parts
            .filterNot { it.all { ch -> ch.isDigit() } && parts.size > 2 }
            .map { part -> part.replaceFirstChar { it.titlecase(Locale.US) } }
        return words.joinToString(" ").ifBlank { clean }
    }

    fun colorForPeer(peerIdHex: String, isDark: Boolean = true): Color {
        val palette = if (isDark) DarkPeerPalette else LightPeerPalette
        if (peerIdHex.isBlank()) return palette[0]
        var hash = 5381
        for (ch in peerIdHex.lowercase()) {
            hash = ((hash shl 5) + hash) + ch.code
        }
        return palette[abs(hash) % palette.size]
    }

    fun fingerprintHex(peerIdHex: String, publicKeyHex: String = ""): String {
        val source = publicKeyHex.trim().ifEmpty { peerIdHex.trim() }.lowercase()
        if (source.length == 64 && source.all { it in '0'..'9' || it in 'a'..'f' }) {
            return source
        }
        val rawBytes = hexToBytesOrNull(source) ?: source.encodeToByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(rawBytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun formatFingerprintGrid(fingerprintHex: String): List<String> {
        val clean = fingerprintHex.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }
            .padEnd(64, '0')
            .take(64)
        return clean.chunked(4).chunked(4).map { row -> row.joinToString(" ") }
    }

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

    fun formatDistanceAndHops(distanceMeters: Int, hops: Int): String {
        val safeHops = hops.coerceIn(1, 7)
        val hopWord = if (safeHops == 1) "1 hop" else "$safeHops hops"
        return "~ $distanceMeters m • $hopWord"
    }

    fun formatHandle(nickname: String, peerIdHex: String): String {
        val cleanNick = nickname.trim().ifEmpty { creativeHandleForPeer(peerIdHex) }
        if (cleanNick.startsWith("@") || cleanNick.contains("#")) return cleanNick
        val suffix = peerIdHex.takeLast(4).lowercase()
        return "@$cleanNick#$suffix"
    }

    fun shortId(peerIdHex: String): String {
        val clean = peerIdHex.trim().lowercase()
        if (clean.length <= 8) return clean
        return "${clean.take(4)}…${clean.takeLast(4)}"
    }

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
