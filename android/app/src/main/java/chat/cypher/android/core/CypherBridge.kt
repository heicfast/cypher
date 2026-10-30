package chat.cypher.android.core

import android.util.Log

/**
 * Thin Kotlin wrapper over `libcypher.so` (crate: `cypher-android`).
 *
 * Every function has a 1:1 counterpart in `crates/cypher-android/src/lib.rs`.
 * Keep this file dependency-free so unit tests can mock [MeshBridge].
 */
object CypherBridge {
    private const val TAG = "CypherBridge"

    @Volatile
    var isLibraryLoaded: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("cypher")
            isLibraryLoaded = true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load libcypher.so", t)
        }
    }

    external fun nativeVersion(): String

    external fun nativeCreateNode(seed32: ByteArray?, nickname: String): Long

    external fun nativeDestroyNode(handle: Long)

    external fun nativePeerIdHex(handle: Long): String

    external fun nativeStaticPublicKey(handle: Long): ByteArray

    external fun nativeIdentitySeed(handle: Long): ByteArray

    external fun nativeSetNickname(handle: Long, nickname: String)

    external fun nativeAnnounce(handle: Long): Int

    external fun nativeSendBroadcastText(handle: Long, text: String): String

    external fun nativeSendPrivateMessage(
        handle: Long,
        recipientHex: String,
        content: String,
    ): String

    external fun nativeSendDeliveryAck(
        handle: Long,
        recipientHex: String,
        messageId: String,
    ): Boolean

    external fun nativeSendReadReceipt(
        handle: Long,
        recipientHex: String,
        messageId: String,
    ): Boolean

    external fun nativeSendMeshPing(handle: Long, peerHex: String): Boolean

    external fun nativeSendVoiceBurst(
        handle: Long,
        recipientHex: String?,
        streamId: Long,
        seq: Int,
        totalFrames: Int,
        durationMs: Int,
        codec: Int,
        opusFrame: ByteArray,
    ): Int

    external fun nativeSendFileBytes(
        handle: Long,
        recipientHex: String?,
        kind: Int,
        fileName: String,
        mimeType: String,
        payloadBytes: ByteArray,
    ): String

    external fun nativeIngestLinkFrame(
        handle: Long,
        linkPeerHex: String?,
        rawFrame: ByteArray,
    ): Array<String>

    external fun nativeTick(handle: Long): Int

    external fun nativeDrainOutbound(handle: Long): Array<ByteArray>

    external fun nativePeersJson(handle: Long): String

    external fun nativePanicWipe(handle: Long)
}
