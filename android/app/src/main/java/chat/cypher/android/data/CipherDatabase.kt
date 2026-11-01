package chat.cypher.android.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversationKey", "timestampMs"]),
        Index(value = ["timestampMs"]),
    ],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationKey: String,
    val senderHex: String,
    val senderLabel: String,
    val text: String,
    val timestampMs: Long,
    val outgoing: Boolean,
    val encrypted: Boolean,
    val hops: Int,
    val delivery: String,
    val kind: String,
    val attachmentPath: String? = null,
    val attachmentMeta: String? = null,
    val replyToMessageId: String? = null,
    val replyPreview: String? = null,
    val reactionsSummary: String = "",
    val starred: Boolean = false,
)

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val peerKeyHex: String,
    val nickname: String,
    val fingerprintHex: String = "",
    val verified: Boolean = false,
    val favorite: Boolean = false,
    val blocked: Boolean = false,
    val customAlias: String = "",
    val privateNote: String = "",
    val lastSeenMs: Long = 0L,
    val lastRssi: Int? = null,
    val lastHops: Int = 1,
    val routePathCsv: String = "",
)

@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey val tag: String,
    val joined: Boolean = true,
    val bookmarked: Boolean = false,
    val ownerPeerHex: String? = null,
    val passwordProtected: Boolean = false,
    val passwordSaltHex: String? = null,
    val passwordCommitmentHex: String? = null,
    val lastActiveMs: Long = 0L,
)

@Entity(
    tableName = "pending_outbox",
    indices = [Index(value = ["recipientHex", "createdAtMs"])],
)
data class PendingOutboxEntity(
    @PrimaryKey val messageId: String,
    val recipientHex: String,
    val payloadText: String,
    val createdAtMs: Long,
    val attempts: Int = 0,
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY timestampMs ASC")
    fun observeAllMessages(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationKey = :conversationKey ORDER BY timestampMs ASC")
    fun observeMessagesForConversation(conversationKey: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestampMs ASC")
    suspend fun getAllMessagesSnapshot(): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(messages: List<MessageEntity>)

    @Query("UPDATE messages SET delivery = :delivery WHERE id = :messageId")
    suspend fun updateDelivery(messageId: String, delivery: String)

    @Query("UPDATE messages SET starred = :starred WHERE id = :messageId")
    suspend fun updateStarred(messageId: String, starred: Boolean)

    @Query("UPDATE messages SET reactionsSummary = :reactionsSummary WHERE id = :messageId")
    suspend fun updateReactions(messageId: String, reactionsSummary: String)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessageById(messageId: String)

    @Query("DELETE FROM messages WHERE conversationKey = :conversationKey")
    suspend fun deleteConversationMessages(conversationKey: String)

    @Query("DELETE FROM messages WHERE timestampMs < :cutoffMs")
    suspend fun deleteMessagesOlderThan(cutoffMs: Long)

    @Query("DELETE FROM messages")
    suspend fun clearAllMessages()
}

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY favorite DESC, lastSeenMs DESC")
    fun observeAllPeers(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers")
    suspend fun getAllPeersSnapshot(): List<PeerEntity>

    @Query("SELECT * FROM peers WHERE peerKeyHex = :peerKeyHex LIMIT 1")
    suspend fun getPeerByKey(peerKeyHex: String): PeerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeer(peer: PeerEntity)

    @Query("DELETE FROM peers WHERE peerKeyHex = :peerKeyHex")
    suspend fun deletePeer(peerKeyHex: String)

    @Query("DELETE FROM peers")
    suspend fun clearAllPeers()
}

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels ORDER BY lastActiveMs DESC")
    fun observeAllChannels(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels")
    suspend fun getAllChannelsSnapshot(): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE tag = :tag LIMIT 1")
    suspend fun getChannelByTag(tag: String): ChannelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChannel(channel: ChannelEntity)

    @Query("DELETE FROM channels WHERE tag = :tag")
    suspend fun deleteChannel(tag: String)

    @Query("DELETE FROM channels")
    suspend fun clearAllChannels()
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM pending_outbox ORDER BY createdAtMs ASC")
    fun observeOutbox(): Flow<List<PendingOutboxEntity>>

    @Query("SELECT * FROM pending_outbox WHERE recipientHex = :recipientHex ORDER BY createdAtMs ASC")
    suspend fun getPendingForRecipient(recipientHex: String): List<PendingOutboxEntity>

    @Query("SELECT * FROM pending_outbox ORDER BY createdAtMs ASC")
    suspend fun getAllPendingSnapshot(): List<PendingOutboxEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPending(entry: PendingOutboxEntity)

    @Query("DELETE FROM pending_outbox WHERE messageId = :messageId")
    suspend fun deletePending(messageId: String)

    @Query("DELETE FROM pending_outbox WHERE recipientHex = :recipientHex")
    suspend fun deletePendingForRecipient(recipientHex: String)

    @Query("DELETE FROM pending_outbox")
    suspend fun clearAllPending()
}

@Database(
    entities = [
        MessageEntity::class,
        PeerEntity::class,
        ChannelEntity::class,
        PendingOutboxEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class CipherDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun peerDao(): PeerDao
    abstract fun channelDao(): ChannelDao
    abstract fun outboxDao(): OutboxDao

    companion object {
        @Volatile
        private var INSTANCE: CipherDatabase? = null

        fun getDatabase(context: Context): CipherDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    CipherDatabase::class.java,
                    "cipher_mesh.db",
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        fun createInMemory(context: Context): CipherDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                CipherDatabase::class.java,
            )
                .allowMainThreadQueries()
                .build()
        }

        fun setInstanceForTesting(db: CipherDatabase?) {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = db
            }
        }
    }
}
