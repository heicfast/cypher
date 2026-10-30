package chat.cypher.android.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import chat.cypher.android.core.CypherBridge
import chat.cypher.android.core.MeshRuntime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The radio link layer — the exact role CoreBluetooth plays for the iOS
 * core. Owns the scanner, advertiser, GATT client, and GATT server; feeds
 * raw events into the Rust engine and executes the engine's commands.
 *
 * Interop: same service/characteristic UUIDs as bitchat, so Cypher nodes
 * appear on the mesh as bitchat peers.
 *
 * Connection sequence (mirrors bitchat-android's BluetoothGattClientManager):
 * connectGatt → onConnectionStateChange(CONNECTED) → requestMtu(517) →
 * onMtuChanged(SUCCESS) → discoverServices → onServicesDiscovered →
 * subscribe CCCD → onDescriptorWrite → LinkUp.
 */
object GattIds {
    val SERVICE: UUID = UUID.fromString("F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C")
    val CHARACTERISTIC: UUID = UUID.fromString("A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

/** Direction mirror of the engine's LinkDirection. */
const val DIRECTION_CENTRAL: Byte = 0
const val DIRECTION_PERIPHERAL: Byte = 1

/** Event discriminators (must match the Rust side). */
private const val EVENT_RADIO_STATE = 0
private const val EVENT_BYTES = 1
private const val EVENT_LINK_UP = 2
private const val EVENT_LINK_DOWN = 3
private const val EVENT_DISCOVERED = 4
private const val EVENT_CENTRAL_SUBSCRIBED = 5

/**
 * MTU to request as GATT client before any data transfer (bitchat-android
 * requests 517; the engine's fragment budget is 512). Must be requested
 * before service discovery; the usable payload is MTU − 3 ATT bytes.
 */
private const val REQUESTED_MTU = 517

/** Send-queue limits — the bitchat BluetoothPacketBroadcaster constants. */
private const val MAX_PENDING_SENDS_PER_LINK = 256
private const val MAX_PENDING_BYTES_PER_LINK = 1_048_576
private const val SEND_RETRY_DELAY_MS = 15L
private const val MAX_CALLBACK_RETRIES = 3

/** Delay before closing a disconnected GATT (bitchat CLEANUP_DELAY). */
private const val GATT_CLOSE_DELAY_MS = 500L

@SuppressLint("MissingPermission")
class BleLinkLayer(private val context: Context) {

    companion object {
        /** The live radio layer — lets the controller re-arm the engine
         *  after a panic wipe recreates it with a fresh identity. */
        @Volatile
        var active: BleLinkLayer? = null
            private set
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    /** Central-role ids: discovered device address -> id (also the link id). */
    private val peripheralIds = ConcurrentHashMap<String, Long>()

    /** Peripheral-role ids: server link id -> device address. */
    private val serverLinks = ConcurrentHashMap<Long, String>()

    /** Reverse of [peripheralIds]: central link id -> address. */
    private val centralLinks = ConcurrentHashMap<Long, String>()

    private val linkCounter = AtomicLong(1)

    /** Central-role connections: link id -> gatt (set once connected). */
    private val gattClients = ConcurrentHashMap<Long, BluetoothGatt>()

    /** In-flight connection attempts: address -> gatt (until established). */
    private val connecting = ConcurrentHashMap<String, BluetoothGatt>()

    /** Established central links: link id -> write characteristic. */
    private val writeCharacteristics = ConcurrentHashMap<Long, BluetoothGattCharacteristic>()

    private val _connectedAddresses = MutableStateFlow<Set<String>>(emptySet())
    val connectedAddresses: StateFlow<Set<String>> = _connectedAddresses.asStateFlow()

    private val _latestRssi = MutableStateFlow<Int?>(null)
    val latestRssi: StateFlow<Int?> = _latestRssi.asStateFlow()

    private var gattServer: BluetoothGattServer? = null
    private var serverCharacteristic: BluetoothGattCharacteristic? = null

    /** Dedicated scheduler for send-queue retries and deferred closes. */
    private val schedulerThread = HandlerThread("cypher-ble-sched").apply { start() }
    private val scheduler = Handler(schedulerThread.looper)

    // Central-role scanning.
    private var scanning = false

    // Peripheral-role advertising.
    private var advertising = false
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {}
        override fun onStartFailure(errorCode: Int) {
            advertising = false
        }
    }

    private class PendingSend(val data: ByteArray, var callbackFailures: Int = 0)

    private class LinkSendState {
        val pending = ArrayDeque<PendingSend>()
        var pendingBytes = 0
        var inFlight = false
        var retryScheduled = false
    }

    /** Key: link id (low bit clear = client write, set = server notification). */
    private val sendStates = ConcurrentHashMap<Long, LinkSendState>()
    private val sendLock = Any()

    private fun clientKey(link: Long) = link shl 1
    private fun serverKey(link: Long) = (link shl 1) or 1L

    private fun refreshConnectedAddresses() {
        val all = HashSet<String>()
        all.addAll(centralLinks.values)
        all.addAll(serverLinks.values)
        _connectedAddresses.value = all
    }

    private fun enqueueSend(key: Long, data: ByteArray): Boolean {
        val startNow = synchronized(sendLock) {
            val state = sendStates.getOrPut(key) { LinkSendState() }
            if (state.pending.size >= MAX_PENDING_SENDS_PER_LINK ||
                state.pendingBytes + data.size > MAX_PENDING_BYTES_PER_LINK
            ) {
                return false
            }
            state.pending.addLast(PendingSend(data.copyOf()))
            state.pendingBytes += data.size
            if (!state.inFlight && !state.retryScheduled) {
                state.inFlight = true
                true
            } else {
                false
            }
        }
        if (startNow) startHead(key)
        return true
    }

    private fun startHead(key: Long) {
        val request = synchronized(sendLock) { sendStates[key]?.pending?.firstOrNull() } ?: return
        val link = key ushr 1
        val accepted = try {
            if (key and 1L == 0L) {
                // Client write (write-without-response).
                val gatt = gattClients[link] ?: return dropKey(key)
                val characteristic = writeCharacteristics[link] ?: return dropKey(key)
                if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeCharacteristic(
                        characteristic,
                        request.data,
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.writeType =
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    @Suppress("DEPRECATION")
                    characteristic.value = request.data
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(characteristic)
                }
            } else {
                // Server notification.
                val server = gattServer ?: return dropKey(key)
                val characteristic = serverCharacteristic ?: return dropKey(key)
                val address = addressForServerLink(link) ?: return dropKey(key)
                val device = try {
                    bluetoothAdapter?.getRemoteDevice(address)
                } catch (_: IllegalArgumentException) {
                    null
                } ?: return dropKey(key)
                if (Build.VERSION.SDK_INT >= 33) {
                    server.notifyCharacteristicChanged(
                        device,
                        characteristic,
                        false,
                        request.data,
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = request.data
                    @Suppress("DEPRECATION")
                    server.notifyCharacteristicChanged(device, characteristic, false)
                }
            }
        } catch (_: Throwable) {
            false
        }
        if (!accepted) rejectStart(key)
    }

    /** The link died under us: drop the whole queue for the key. */
    private fun dropKey(key: Long) {
        synchronized(sendLock) { sendStates.remove(key) }
    }

    /** The stack rejected the op (another in flight): retry shortly. */
    private fun rejectStart(key: Long) {
        val schedule = synchronized(sendLock) {
            val state = sendStates[key] ?: return
            state.inFlight = false
            if (state.retryScheduled || state.pending.isEmpty()) {
                false
            } else {
                state.retryScheduled = true
                true
            }
        }
        if (schedule) {
            scheduler.postDelayed({
                val retry = synchronized(sendLock) {
                    val state = sendStates[key] ?: return@postDelayed
                    state.retryScheduled = false
                    if (!state.inFlight && state.pending.isNotEmpty()) {
                        state.inFlight = true
                        true
                    } else {
                        false
                    }
                }
                if (retry) startHead(key)
            }, SEND_RETRY_DELAY_MS)
        }
    }

    /** Called from onCharacteristicWrite / onNotificationSent. */
    private fun completeSend(key: Long, status: Int) {
        var retry = false
        val startNext = synchronized(sendLock) {
            val state = sendStates[key] ?: return
            val head = state.pending.firstOrNull() ?: run {
                sendStates.remove(key)
                return
            }
            state.inFlight = false
            if (status != BluetoothGatt.GATT_SUCCESS &&
                head.callbackFailures < MAX_CALLBACK_RETRIES
            ) {
                head.callbackFailures++
                retry = true
                false
            } else {
                state.pending.removeFirst()
                state.pendingBytes -= head.data.size
                if (state.pending.isEmpty()) {
                    sendStates.remove(key)
                    false
                } else {
                    state.inFlight = true
                    true
                }
            }
        }
        if (retry) rejectStart(key) else if (startNext) startHead(key)
    }

    /** Drop both send queues for a link that went down. */
    private fun dropLinkQueues(link: Long) {
        synchronized(sendLock) {
            sendStates.remove(clientKey(link))
            sendStates.remove(serverKey(link))
        }
    }

    // ---- lifecycle --------------------------------------------------------------

    fun start(): Boolean {
        active = this
        val adapter = bluetoothAdapter ?: run {
            pushEvent(EVENT_RADIO_STATE, argInt = 2)
            return false
        }
        val isEnabled = runCatching { adapter.isEnabled }.getOrDefault(false)
        if (!isEnabled) {
            // The engine reacts to the state event; the UI prompts the user.
            pushEvent(EVENT_RADIO_STATE, argInt = 4)
            return false
        }
        pushEvent(EVENT_RADIO_STATE, argInt = 5)
        openGattServer()
        startScan(allowDuplicates = true)
        startAdvertisingInternal()
        return true
    }

    /**
     * Push a radio-state transition to the engine (0 unknown, 1 resetting,
     * 2 unsupported, 3 unauthorized, 4 poweredOff, 5 poweredOn) — used by
     * the adapter-state receiver when the user toggles Bluetooth at runtime.
     */
    fun pushRadioState(state: Long) {
        pushEvent(EVENT_RADIO_STATE, argInt = state)
        if (state == 5L) {
            openGattServer()
            startScan(allowDuplicates = true)
            startAdvertisingInternal()
        }
    }

    fun stop() {
        if (active === this) {
            active = null
        }
        stopScanInternal()
        stopAdvertisingInternal()
        gattClients.values.forEach {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gattClients.clear()
        connecting.values.forEach {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        connecting.clear()
        writeCharacteristics.clear()
        centralLinks.clear()
        serverLinks.clear()
        refreshConnectedAddresses()
        synchronized(sendLock) { sendStates.clear() }
        runCatching { gattServer?.close() }
        gattServer = null
        scheduler.removeCallbacksAndMessages(null)
    }

    // ---- engine event feed --------------------------------------------------------

    private fun handle(): Long? = MeshRuntime.engineHandle()

    private fun pushEvent(
        type: Int,
        link: Long = 0,
        direction: Byte = DIRECTION_CENTRAL,
        bytes: ByteArray? = null,
        argInt: Long = 0,
    ) {
        val h = handle() ?: return
        val response = runCatching {
            CypherBridge.nativeHandleEvent(h, type, link, direction, bytes, argInt)
        }.getOrNull()
        processResponse(response)
    }

    fun processResponse(blob: ByteArray?) {
        val parsed = CypherBridge.parseResponse(blob)
        executeCommands(parsed.commands)
        if (parsed.eventsJson != "[]") {
            MeshEvents.publish(parsed.eventsJson)
        }
    }

    fun executeCommands(commands: List<ByteArray>) {
        commands.forEach { command ->
            when (val decoded = CypherBridge.decodeCommand(command)) {
                is CypherBridge.RadioCommand.StartScan -> startScan(decoded.allowDuplicates)
                CypherBridge.RadioCommand.StopScan -> stopScanInternal()
                CypherBridge.RadioCommand.StartAdvertising -> startAdvertisingInternal()
                CypherBridge.RadioCommand.StopAdvertising -> stopAdvertisingInternal()
                is CypherBridge.RadioCommand.Connect -> connect(decoded.peripheral)
                is CypherBridge.RadioCommand.Disconnect -> disconnect(decoded.peripheral)
                is CypherBridge.RadioCommand.Write -> write(decoded)
                null -> {}
            }
        }
    }

    /** Engine tick pump (driven by the foreground service or controller). */
    fun tick(): String {
        val h = handle() ?: return "[]"
        val response = runCatching { CypherBridge.nativeTick(h) }.getOrNull()
        val parsed = CypherBridge.parseResponse(response)
        executeCommands(parsed.commands)
        if (parsed.eventsJson != "[]") {
            MeshEvents.publish(parsed.eventsJson)
        }
        return parsed.eventsJson
    }

    // ---- scanning (central role) ----------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val id = peripheralIds.computeIfAbsent(result.device.address) { linkCounter.andIncrement }
            _latestRssi.value = result.rssi
            // RSSI rides the link field; connectable flag in the direction byte.
            pushEvent(
                EVENT_DISCOVERED,
                link = result.rssi.toLong(),
                direction = if (result.isConnectable) 1 else 0,
                argInt = id,
            )
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
        }
    }

    @Synchronized
    private fun startScan(allowDuplicates: Boolean) {
        val adapter = bluetoothAdapter ?: return
        if (scanning || !runCatching { adapter.isEnabled }.getOrDefault(false)) return
        val scanner = runCatching { adapter.bluetoothLeScanner }.getOrNull() ?: return
        val filters =
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(GattIds.SERVICE)).build())
        val settings = ScanSettings.Builder()
            .setScanMode(if (allowDuplicates) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()
        try {
            scanner.startScan(filters, settings, scanCallback)
            scanning = true
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    private fun stopScanInternal() {
        val adapter = bluetoothAdapter ?: return
        if (!scanning) return
        try {
            adapter.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Throwable) {
        }
        scanning = false
    }

    // ---- connections (central role) ---------------------------------------------------

    private fun addressFor(peripheral: Long): String? =
        centralLinks[peripheral] ?: peripheralIds.entries.firstOrNull { it.value == peripheral }?.key

    private fun addressForServerLink(link: Long): String? = serverLinks[link]

    private fun connect(peripheral: Long) {
        val adapter = bluetoothAdapter ?: return
        val address = addressFor(peripheral) ?: return
        if (connecting.containsKey(address) || gattClients.containsKey(peripheral)) return
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            return
        }
        try {
            val gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            connecting[address] = gatt
                            gattClients[peripheral] = gatt
                            centralLinks[peripheral] = address
                            refreshConnectedAddresses()
                            runCatching { gatt.requestMtu(REQUESTED_MTU) }
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            connecting.remove(address)
                            gattClients.remove(peripheral)
                            centralLinks.remove(peripheral)
                            writeCharacteristics.remove(peripheral)
                            refreshConnectedAddresses()
                            dropLinkQueues(peripheral)
                            pushEvent(EVENT_LINK_DOWN, link = peripheral, direction = DIRECTION_CENTRAL)
                            scheduler.postDelayed({
                                try {
                                    gatt.close()
                                } catch (_: Exception) {
                                }
                            }, GATT_CLOSE_DELAY_MS)
                        }
                    }
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        runCatching { gatt.discoverServices() }
                    } else {
                        runCatching { gatt.disconnect() }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        runCatching { gatt.disconnect() }
                        return
                    }
                    val characteristic = gatt
                        .getService(GattIds.SERVICE)
                        ?.getCharacteristic(GattIds.CHARACTERISTIC) ?: run {
                        runCatching { gatt.disconnect() }
                        return
                    }
                    writeCharacteristics[peripheral] = characteristic
                    runCatching { gatt.setCharacteristicNotification(characteristic, true) }
                    val descriptor = characteristic.getDescriptor(GattIds.CCCD)
                    if (descriptor != null) {
                        try {
                            if (Build.VERSION.SDK_INT >= 33) {
                                gatt.writeDescriptor(
                                    descriptor,
                                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                                )
                            } else {
                                @Suppress("DEPRECATION")
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                @Suppress("DEPRECATION")
                                gatt.writeDescriptor(descriptor)
                            }
                        } catch (_: Throwable) {
                        }
                    } else {
                        pushEvent(EVENT_LINK_UP, link = peripheral, direction = DIRECTION_CENTRAL)
                    }
                }

                override fun onDescriptorWrite(
                    gatt: BluetoothGatt,
                    descriptor: BluetoothGattDescriptor,
                    status: Int,
                ) {
                    if (descriptor.uuid == GattIds.CCCD) {
                        pushEvent(EVENT_LINK_UP, link = peripheral, direction = DIRECTION_CENTRAL)
                    }
                }

                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    if (characteristic.uuid == GattIds.CHARACTERISTIC) {
                        completeSend(clientKey(peripheral), status)
                    }
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    if (characteristic.uuid == GattIds.CHARACTERISTIC) {
                        pushEvent(
                            EVENT_BYTES,
                            link = peripheral,
                            direction = DIRECTION_CENTRAL,
                            bytes = value,
                        )
                    }
                }

                @Deprecated("pre-33 callback")
                @Suppress("DEPRECATION")
                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                ) {
                    if (Build.VERSION.SDK_INT < 33) {
                        characteristic.value?.let {
                            pushEvent(
                                EVENT_BYTES,
                                link = peripheral,
                                direction = DIRECTION_CENTRAL,
                                bytes = it,
                            )
                        }
                    }
                }
            }, BluetoothDevice.TRANSPORT_LE)
            if (gatt != null) {
                connecting[address] = gatt
            } else {
                connecting.remove(address)
            }
        } catch (_: Throwable) {
            connecting.remove(address)
        }
    }

    private fun disconnect(peripheral: Long) {
        runCatching {
            gattClients.remove(peripheral)?.disconnect()
                ?: connecting.remove(addressFor(peripheral))?.disconnect()
        }
    }

    // ---- GATT server (peripheral role) --------------------------------------------------

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val stale = serverLinks.entries.filter { it.value == device.address }
                stale.forEach { (id, _) ->
                    serverLinks.remove(id)
                    dropLinkQueues(id)
                    pushEvent(EVENT_LINK_DOWN, link = id, direction = DIRECTION_PERIPHERAL)
                }
                refreshConnectedAddresses()
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                refreshConnectedAddresses()
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {}

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (descriptor.uuid == GattIds.CCCD &&
                value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            ) {
                val existing = serverLinks.entries.firstOrNull { it.value == device.address }?.key
                val id = existing ?: linkCounter.andIncrement.also {
                    serverLinks[it] = device.address
                }
                refreshConnectedAddresses()
                runCatching {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        byteArrayOf(),
                    )
                }
                pushEvent(EVENT_CENTRAL_SUBSCRIBED, link = id)
            } else if (responseNeeded) {
                runCatching {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        byteArrayOf(),
                    )
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (responseNeeded) {
                runCatching {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        byteArrayOf(),
                    )
                }
            }
            if (characteristic.uuid == GattIds.CHARACTERISTIC) {
                val existing = serverLinks.entries.firstOrNull { it.value == device.address }?.key
                val id = existing ?: linkCounter.andIncrement.also {
                    serverLinks[it] = device.address
                }
                refreshConnectedAddresses()
                pushEvent(
                    EVENT_BYTES,
                    link = id,
                    direction = DIRECTION_PERIPHERAL,
                    bytes = value,
                )
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val id = serverLinks.entries.firstOrNull { it.value == device.address }?.key ?: return
            completeSend(serverKey(id), status)
        }
    }

    private fun openGattServer() {
        if (gattServer != null) return
        val bm = bluetoothManager ?: return
        try {
            val characteristic = BluetoothGattCharacteristic(
                GattIds.CHARACTERISTIC,
                BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ or
                    BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
            val descriptor = BluetoothGattDescriptor(
                GattIds.CCCD,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            )
            characteristic.addDescriptor(descriptor)
            val service =
                BluetoothGattService(GattIds.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(characteristic)
            serverCharacteristic = characteristic
            gattServer = bm.openGattServer(context, serverCallback)?.also {
                it.clearServices()
                it.addService(service)
            }
        } catch (_: Throwable) {
        }
    }

    // ---- advertising (peripheral role) ----------------------------------------------------

    private fun startAdvertisingInternal() {
        val adapter = bluetoothAdapter ?: return
        val advertiser = runCatching { adapter.bluetoothLeAdvertiser }.getOrNull() ?: return
        if (advertising) return
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(GattIds.SERVICE))
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .build()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        try {
            advertiser.startAdvertising(settings, data, advertiseCallback)
            advertising = true
        } catch (_: Throwable) {
        }
    }

    private fun stopAdvertisingInternal() {
        val adapter = bluetoothAdapter ?: return
        if (!advertising) return
        try {
            adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Throwable) {
        }
        advertising = false
    }

    // ---- writes -----------------------------------------------------------------------------

    private fun write(command: CypherBridge.RadioCommand.Write) {
        if (command.direction == DIRECTION_CENTRAL.toInt()) {
            enqueueSend(clientKey(command.link), command.bytes)
        } else {
            enqueueSend(serverKey(command.link), command.bytes)
        }
    }
}

/** Shared event bus between the radio layer and the UI. */
object MeshEvents {
    private val listeners = mutableListOf<(String) -> Unit>()

    @Synchronized
    fun subscribe(listener: (String) -> Unit): () -> Unit {
        listeners.add(listener)
        return { synchronized(this) { listeners.remove(listener) } }
    }

    @Synchronized
    fun publish(eventsJson: String) {
        listeners.toList().forEach { it(eventsJson) }
    }
}
