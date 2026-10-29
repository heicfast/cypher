package chat.cypher.android.core

import java.util.Locale

/**
 * Canonical, presentation-neutral identity used to derive a peer hue.
 *
 * Callers cannot construct arbitrary color seeds. Every identity is
 * normalized and namespaced here so the same mesh user resolves to the same
 * color on every surface (byte-identical to the bitchat derivation).
 */
@JvmInline
value class PeerIdentity private constructor(internal val stableKey: String) {
    companion object {
        fun mesh(peerID: String): PeerIdentity =
            PeerIdentity("noise:${normalize(peerID)}")

        /** Last-resort identity for mentions that carry no stable peer ID. */
        fun nickname(nickname: String): PeerIdentity =
            PeerIdentity(normalize(nickname))

        private fun normalize(value: String): String =
            value.trim().lowercase(Locale.ROOT)
    }
}
