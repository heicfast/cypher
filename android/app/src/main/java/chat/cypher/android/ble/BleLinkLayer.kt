package chat.cypher.android.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import chat.cypher.android.core.CypherBridge
import chat.cypher.android.core.MeshRuntime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The radio link layer — the exact role CoreBluetooth plays for the iOS
 * core. Owns the scanner, advertiser, GATT client, and GATT server; feeds
 * raw events into the Rust engine and executes the engine's commands.
 *
 * Interop: same service/characteristic UUIDs as bitchat, so Cypher nodes
 * appear on the mesh as bitchat peers.
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

@SuppressLint("MissingPermission")
class BleLinkLayer(private val context: Context) {

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager.adapter

    // ---- identifiers ----------------------------------------------------------

    private val linkCounter = AtomicLong(1)
    private val peripheralIds = ConcurrentHashMap<String, Long>() // device address -> id
    private val gattClients = ConcurrentHashMap<Long, BluetoothGatt>() // central link -> gatt
    private val writeCharacteristics = ConcurrentHashMap<Long, BluetoothGattCharacteristic>()

    private var gattServer: BluetoothGattServer? = null
    private var serverCharacteristic: BluetoothGattCharacteristic? = null

    // Central-role scanning.
    private var scanning = false

    // Peripheral-role advertising.
    private var advertising = false
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {}
        override fun onStartFailure(errorCode: Int) {
            // Retry with backoff is handled by the service's watchdog.
            advertising = false
        }
    }

    // ---- lifecycle --------------------------------------------------------------

    fun start() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            // The engine reacts to the state event; the UI prompts the user.
            pushEvent(EVENT_RADIO_STATE, argInt = 4)
            return
        }
        pushEvent(EVENT_RADIO_STATE, argInt = 5)
        openGattServer()
        startScanInternal()
    }

    fun stop() {
        stopScanInternal()
        stopAdvertisingInternal()
        gattClients.values.forEach { it.disconnect() }
        gattClients.clear()
        writeCharacteristics.clear()
        gattServer?.close()
        gattServer = null
    }

    // ---- engine event feed --------------------------------------------------------

    private fun handle(): Long = MeshRuntime.engineHandle() ?: return

    private fun pushEvent(
        type: Int,
        link: Long = 0,
        direction: Byte = DIRECTION_CENTRAL,
        bytes: ByteArray? = null,
        argInt: Long = 0,
    ) {
        val h = handle() ?: return
        val response = CypherBridge.nativeHandleEvent(h, type, link, direction, bytes, argInt)
        processResponse(response)
    }

    private fun processResponse(blob: ByteArray?) {
        val parsed = CypherBridge.parseResponse(blob)
        parsed.commands.forEach { command ->
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
        // Events JSON surfaces through the shared event bus.
        if (parsed.eventsJson != "[]") {
            MeshEvents.publish(parsed.eventsJson)
        }
    }

    /** Engine tick pump (driven by the foreground service). */
    fun tick(): String {
        val h = handle() ?: return "[]"
        val response = CypherBridge.nativeTick(h)
        val parsed = CypherBridge.parseResponse(response)
        parsed.commands.forEach { command ->
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
        return parsed.eventsJson
    }

    // ---- scanning (central role) ----------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val id = peripheralIds.computeIfAbsent(result.device.address) { linkCounter.andIncrement }
            // RSSI rides the link field; connectable flag in the direction byte.
            pushEvent(EVENT_DISCOVERED, link = result.rssi.toLong(), direction = if (result.isConnectable) 1 else 0, argInt = id)
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
        }
    }

    @Synchronized
    private fun startScan(allowDuplicates: Boolean) {
        val adapter = bluetoothAdapter ?: return
        if (scanning || !adapter.isEnabled) return
        val scanner = adapter.bluetoothLeScanner ?: return
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(GattIds.SERVICE)).build())
        val settings = ScanSettings.Builder()
            .setScanMode(if (allowDuplicates) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()
        try {
            scanner.startScan(filters, settings, scanCallback)
            scanning = true
        } catch (_: SecurityException) {
        }
    }

    @Synchronized
    fun stopScanInternal() {
        val adapter = bluetoothAdapter ?: return
        if (!scanning) return
        try {
            adapter.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
        }
        scanning = false
    }

    // ---- connections (central role) ---------------------------------------------------

    private fun addressFor(peripheral: Long): String? =
        peripheralIds.entries.firstOrNull { it.value == peripheral }?.key

    private fun connect(peripheral: Long) {
        val adapter = bluetoothAdapter ?: return
        val address = addressFor(peripheral) ?: return
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            return
        }
        try {
            device.connectGatt(context, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            gattClients[peripheral] = gatt
                            gatt.discoverServices()
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            gattClients.remove(peripheral)
                            writeCharacteristics.remove(peripheral)
                            gatt.close()
                            pushEvent(EVENT_LINK_DOWN, link = peripheral, direction = DIRECTION_CENTRAL)
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    val characteristic = gatt
                        .getService(GattIds.SERVICE)
                        ?.getCharacteristic(GattIds.CHARACTERISTIC) ?: run {
                        gatt.disconnect()
                        return
                    }
                    writeCharacteristics[peripheral] = characteristic
                    gatt.setCharacteristicNotification(characteristic, true)
                    val descriptor = characteristic.getDescriptor(GattIds.CCCD)
                    if (descriptor != null) {
                        if (android.os.Build.VERSION.SDK_INT >= 33) {
                            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION")
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(descriptor)
                        }
                    } else {
                        pushEvent(EVENT_LINK_UP, link = peripheral, direction = DIRECTION_CENTRAL)
                    }
                }

                override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                    if (descriptor.uuid == GattIds.CCCD) {
                        pushEvent(EVENT_LINK_UP, link = peripheral, direction = DIRECTION_CENTRAL)
                    }
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    if (characteristic.uuid == GattIds.CHARACTERISTIC) {
                        pushEvent(EVENT_BYTES, link = peripheral, direction = DIRECTION_CENTRAL, bytes = value)
                    }
                }

                @Deprecated("pre-33 callback")
                @Suppress("DEPRECATION")
                override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                    if (android.os.Build.VERSION.SDK_INT < 33) {
                        characteristic.value?.let {
                            pushEvent(EVENT_BYTES, link = peripheral, direction = DIRECTION_CENTRAL, bytes = it)
                        }
                    }
                }
            }, BluetoothDevice.TRANSPORT_LE)
        } catch (_: SecurityException) {
        }
    }

    private fun disconnect(peripheral: Long) {
        gattClients.remove(peripheral)?.disconnect()
    }

    // ---- GATT server (peripheral role) --------------------------------------------------

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            // Link ids for server-side connections are per-address.
            val id = peripheralIds.computeIfAbsent(device.address) { linkCounter.andIncrement }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                pushEvent(EVENT_LINK_DOWN, link = id, direction = DIRECTION_PERIPHERAL)
            }
        }

        override fun onDescriptorWrite(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (descriptor.uuid == GattIds.CCCD && value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                val id = peripheralIds.computeIfAbsent(device.address) { linkCounter.andIncrement }
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, byteArrayOf())
                pushEvent(EVENT_CENTRAL_SUBSCRIBED, link = id)
            } else if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, byteArrayOf())
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
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, byteArrayOf())
            }
            if (characteristic.uuid == GattIds.CHARACTERISTIC) {
                val id = peripheralIds.computeIfAbsent(device.address) { linkCounter.andIncrement }
                pushEvent(EVENT_BYTES, link = id, direction = DIRECTION_PERIPHERAL, bytes = value)
            }
        }
    }

    private fun openGattServer() {
        if (gattServer != null) return
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
        val service = BluetoothGattService(GattIds.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)
        serverCharacteristic = characteristic
        gattServer = bluetoothManager.openGattServer(context, serverCallback)?.also {
            it.clearServices()
            it.addService(service)
        }
    }

    // ---- advertising (peripheral role) ----------------------------------------------------

    private fun startAdvertisingInternal() {
        val adapter = bluetoothAdapter ?: return
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        if (advertising) return
        // Maximum privacy: the service UUID only — no name, no TX power, no
        // manufacturer data (identity rides the announce packet, encrypted
        // channel first).
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
        } catch (_: SecurityException) {
        }
    }

    private fun stopAdvertisingInternal() {
        val adapter = bluetoothAdapter ?: return
        if (!advertising) return
        try {
            adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (_: SecurityException) {
        }
        advertising = false
    }

    // ---- writes -----------------------------------------------------------------------------

    private fun write(command: CypherBridge.RadioCommand.Write) {
        if (command.direction == DIRECTION_CENTRAL.toInt()) {
            val gatt = gattClients[command.link] ?: return
            val characteristic = writeCharacteristics[command.link] ?: return
            try {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    gatt.writeCharacteristic(
                        characteristic,
                        command.bytes,
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    @Suppress("DEPRECATION")
                    characteristic.value = command.bytes
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(characteristic)
                }
            } catch (_: SecurityException) {
            }
        } else {
            val server = gattServer ?: return
            val characteristic = serverCharacteristic ?: return
            val address = addressFor(command.link) ?: return
            val device = try {
                bluetoothAdapter?.getRemoteDevice(address)
            } catch (_: IllegalArgumentException) {
                null
            } ?: return
            try {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    server.notifyCharacteristicChanged(device, characteristic, false, command.bytes)
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = command.bytes
                    @Suppress("DEPRECATION")
                    server.notifyCharacteristicChanged(device, characteristic, false)
                }
            } catch (_: SecurityException) {
            }
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
