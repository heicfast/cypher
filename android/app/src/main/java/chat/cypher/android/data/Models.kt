package chat.cypher.android.data

/** Message delivery states (bitchat `DeliveryStatus` parity, premium skin). */
enum class Delivery(val rank: Int) {
    SENDING(0),
    SENT(1),
    DELIVERED(2),
    READ(3),
    FAILED(4);
}

enum class MessageKind { TEXT, SYSTEM, IMAGE, FILE, VOICE }

/**
 * A single chat message, mirrored from the Rust engine events.
 * Conversations are keyed: "mesh" | "#channel" | "dm:<peerHex>".
 */
data class CypherMessage(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val senderNickname: String,
    val content: String,
    val timestampMs: Long,
    val isOwn: Boolean,
    val kind: MessageKind = MessageKind.TEXT,
    val delivery: Delivery? = if (isOwn) Delivery.SENDING else null,
)

enum class ConversationKind { MESH, CHANNEL, DIRECT }

/** A chats-list entry. */
data class Conversation(
    val id: String,
    val kind: ConversationKind,
    val displayName: String,
    /** Peer hex id for DMs; null for mesh/channel. */
    val peerId: String?,
    val lastMessage: CypherMessage? = null,
    val unreadCount: Int = 0,
    val online: Boolean = false,
    val verified: Boolean = false,
    val favorite: Boolean = false,
    val secureSession: Boolean = false,
    val draft: String = "",
    val lastActivityMs: Long = lastMessage?.timestampMs ?: 0L,
)

/** A peer known to the mesh (connected or recently seen). */
data class PeerUi(
    val peerId: String,
    val nickname: String,
    val connected: Boolean,
    val secureSession: Boolean,
    val verified: Boolean,
    val favorite: Boolean,
    val lastSeenMs: Long,
    /** Short suffix peers display (bitchat parity: name#suffix). */
    val suffix: String = peerId.takeLast(4),
    val fingerprint: String = "",
)

/** Local identity snapshot from the engine. */
data class MyIdentity(
    val peerId: String,
    val nickname: String,
    val fingerprint: String,
)
