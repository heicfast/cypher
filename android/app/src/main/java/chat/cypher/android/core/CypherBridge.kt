package chat.cypher.android.core

import android.content.Context

/**
 * JNI surface into the Rust mesh core (`libcypher.so`).
 *
 * The engine owns all protocol state; this bridge feeds it radio events and
 * returns framed command batches + JSON events. The radio layer
 * [chat.cypher.android.ble.BleLinkLayer] executes the commands; the UI
 * collects the events.
 */
object CypherBridge {
    init {
        System.loadLibrary("cypher")
    }

    /** Create an engine (loads or generates the device identity). */
    external fun engineCreate(dataDir: String, nickname: String): Long

    external fun engineDestroy(handle: Long)

    /**
     * Feed a radio event.
     *
     * Event discriminators:
     *  - `0` RadioState(state: Int) — 0 unknown, 1 resetting, 2 unsupported,
     *    3 unauthorized, 4 poweredOff, 5 poweredOn
     *  - `1` BytesReceived(link, direction, bytes)
     *  - `2` LinkUp(link, direction)
     *  - `3` LinkDown(link, direction)
     *  - `4` Discovered(peripheral, rssi, connectable)
     *  - `5` CentralSubscribed(link)
     *
     * Direction: 0 central (we scanned+connected), 1 peripheral (peer
     * connected to our GATT server).
     *
     * Returns the framed response (commands + events JSON).
     */
    external fun nativeHandleEvent(
        handle: Long,
        eventType: Int,
        link: Long,
        direction: Byte,
        bytes: ByteArray?,
        argInt: Long,
    ): ByteArray?

    /** Clock tick: run due timers (relays, maintenance, announce cadence). */
    external fun nativeTick(handle: Long): ByteArray?

    external fun nativeSendPublicMessage(handle: Long, content: String): ByteArray?

    external fun nativeSendPrivateMessage(
        handle: Long,
        peerHex: String,
        content: String,
        messageId: String,
    ): ByteArray?

    external fun nativeSendMeshPing(handle: Long, peerHex: String): ByteArray?

    external fun nativeAnnounce(handle: Long): ByteArray?

    /** JSON snapshot: local identity + peers (for the UI). */
    external fun nativeStateJson(handle: Long): ByteArray?

    // ---- response framing ---------------------------------------------------

    /**
     * A parsed engine response: radio commands (raw, tag-prefixed) plus the
     * deserialized UI events JSON.
     */
    data class EngineResponse(val commands: List<ByteArray>, val eventsJson: String)

    fun parseResponse(blob: ByteArray?): EngineResponse {
        if (blob == null || blob.size < 4) return EngineResponse(emptyList(), "[]")
        var offset = 0
        fun u32(): Int {
            val v = (blob[offset].toInt() and 0xFF) or
                ((blob[offset + 1].toInt() and 0xFF) shl 8) or
                ((blob[offset + 2].toInt() and 0xFF) shl 16) or
                ((blob[offset + 3].toInt() and 0xFF) shl 24)
            offset += 4
            return v
        }
        val commandCount = u32()
        val commands = ArrayList<ByteArray>(commandCount)
        repeat(commandCount) {
            val length = u32()
            if (offset + length > blob.size) return EngineResponse(commands.toList(), "[]")
            commands.add(blob.copyOfRange(offset, offset + length))
            offset += length
        }
        if (offset + 4 > blob.size) return EngineResponse(commands.toList(), "[]")
        val jsonLength = u32()
        val json = if (offset + jsonLength <= blob.size) {
            String(blob, offset, jsonLength, Charsets.UTF_8)
        } else {
            "[]"
        }
        return EngineResponse(commands.toList(), json)
    }

    // ---- command decoding -----------------------------------------------------

    /** A decoded radio command. */
    sealed interface RadioCommand {
        data class StartScan(val allowDuplicates: Boolean) : RadioCommand
        object StopScan : RadioCommand
        object StartAdvertising : RadioCommand
        object StopAdvertising : RadioCommand
        data class Connect(val peripheral: Long) : RadioCommand
        data class Disconnect(val peripheral: Long) : RadioCommand
        data class Write(
            val link: Long,
            val direction: Int,
            val bytes: ByteArray,
            val priority: Int,
        ) : RadioCommand
    }

    /**
     * Decode a command blob: `op:u8` then op-specific payload (all LE).
     * 0x01 StartScan{allow:u8} 0x02 StopScan 0x03 StartAdvertising
     * 0x04 StopAdvertising 0x05 Connect{peripheral:u64}
     * 0x06 Disconnect{peripheral:u64}
     * 0x07 Write{link:u64, direction:u8, priority:u8, len:u32, bytes}
     */
    fun decodeCommand(command: ByteArray): RadioCommand? {
        if (command.isEmpty()) return null
        var offset = 1
        fun u8(): Int = command[offset++].toInt() and 0xFF
        fun u32(): Long {
            val v = (command[offset].toLong() and 0xFF) or
                ((command[offset + 1].toLong() and 0xFF) shl 8) or
                ((command[offset + 2].toLong() and 0xFF) shl 16) or
                ((command[offset + 3].toLong() and 0xFF) shl 24)
            offset += 4
            return v
        }
        fun u64(): Long {
            var v = 0L
            for (i in 7 downTo 0) {
                v = (v shl 8) or (command[offset + i].toLong() and 0xFF)
            }
            offset += 8
            return v
        }
        return when (command[0].toInt() and 0xFF) {
            0x01 -> RadioCommand.StartScan(u8() != 0)
            0x02 -> RadioCommand.StopScan
            0x03 -> RadioCommand.StartAdvertising
            0x04 -> RadioCommand.StopAdvertising
            0x05 -> RadioCommand.Connect(u64())
            0x06 -> RadioCommand.Disconnect(u64())
            0x07 -> {
                val link = u64()
                val direction = u8()
                val priority = u8()
                val length = u32().toInt()
                if (offset + length > command.size) return null
                RadioCommand.Write(link, direction, command.copyOfRange(offset, offset + length), priority)
            }
            else -> null
        }
    }

    // ---- helpers ---------------------------------------------------------------

    /** Run a block with the engine handle on the calling thread. */
    inline fun <T> withEngine(context: Context, block: (Long) -> T?): T? {
        val handle = MeshRuntime.engineHandle() ?: return null
        return block(handle)
    }
}

/**
 * Process-wide mesh runtime: one engine, one radio, one event pump.
 */
object MeshRuntime {
    @Volatile
    private var handle: Long = 0L

    fun engineHandle(): Long? = handle.takeIf { it != 0L }

    @Synchronized
    fun start(context: Context, nickname: String): Long {
        if (handle != 0L) return handle
        System.loadLibrary("cypher") // idempotent
        handle = CypherBridge.engineCreate(context.filesDir.absolutePath, nickname)
        return handle
    }

    @Synchronized
    fun stop() {
        if (handle != 0L) {
            CypherBridge.engineDestroy(handle)
            handle = 0L
        }
    }
}
