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
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Dual-role Bluetooth Low Energy link layer for Cypher.
 */
@SuppressLint("MissingPermission")
class BleLinkLayer(
    private val context: Context,
    private val onFrameReceived: (address: String, bytes: ByteArray) -> Unit,
    private val onNeighborChanged: (address: String, connected: Boolean, rssi: Int?) -> Unit,
) {
    companion object {
        private const val TAG = "CypherBle"

        val SERVICE_UUID: UUID = UUID.fromString("F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val REQUESTED_MTU = 517
        private const val MAX_CLIENT_CONNECTIONS = 7
        private const val RECONNECT_COOLDOWN_MS = 4_000L
        private const val WRITE_DRAIN_SAFETY_MS = 250L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    private val started = AtomicBoolean(false)

    private val clientGatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val readyClientGatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val serverSubscribers = ConcurrentHashMap<String, BluetoothDevice>()
    private val lastConnectAttemptMs = ConcurrentHashMap<String, Long>()
    private val connectBackoffMs = ConcurrentHashMap<String, Long>()

    private data class QueuedWrite(val gatt: BluetoothGatt, val frame: ByteArray)

    private val writeQueue = ConcurrentLinkedQueue<QueuedWrite>()
    private val writeInFlight = AtomicBoolean(false)
    private var writeWatchdogJob: Job? = null

    private val _connectedAddresses = MutableStateFlow<Set<String>>(emptySet())
    val connectedAddresses: StateFlow<Set<String>> = _connectedAddresses.asStateFlow()

    private val _isRadioActive = MutableStateFlow(false)
    val isRadioActive: StateFlow<Boolean> = _isRadioActive.asStateFlow()

    fun start(): Boolean {
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            Log.w(TAG, "Bluetooth adapter unavailable or disabled")
            _isRadioActive.value = false
            return false
        }
        if (!started.compareAndSet(false, true)) {
            return true
        }
        startGattServer()
        startAdvertising(bt)
        startScanning(bt)
        _isRadioActive.value = true
        Log.i(TAG, "Cypher BLE link layer started")
        return true
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        _isRadioActive.value = false
        writeWatchdogJob?.cancel()
        writeQueue.clear()
        writeInFlight.set(false)

        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        runCatching { scanner?.stopScan(scanCallback) }
        clientGatts.values.forEach { gatt ->
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        clientGatts.clear()
        readyClientGatts.clear()
        serverSubscribers.clear()
        runCatching { gattServer?.close() }
        gattServer = null
        updateConnectedSet()
        Log.i(TAG, "Cypher BLE link layer stopped")
    }

    fun broadcastFrame(frame: ByteArray): Int {
        if (!started.get() || frame.isEmpty()) return 0
        var enqueued = 0

        val server = gattServer
        val characteristic = txCharacteristic
        if (server != null && characteristic != null) {
            for (device in serverSubscribers.values) {
                val sent = notifyDevice(server, device, characteristic, frame)
                if (sent) enqueued++
            }
        }

        for ((address, gatt) in readyClientGatts) {
            if (serverSubscribers.containsKey(address)) continue
            writeQueue.add(QueuedWrite(gatt, frame))
            enqueued++
        }
        drainNextWrite()
        return enqueued
    }

    private fun startGattServer() {
        val mgr = bluetoothManager ?: return
        val server = runCatching { mgr.openGattServer(context, gattServerCallback) }.getOrNull() ?: return
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_WRITE or
                BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val cccd = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        characteristic.addDescriptor(cccd)
        service.addCharacteristic(characteristic)
        server.addService(service)
        gattServer = server
        txCharacteristic = characteristic
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val addr = device.address ?: return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                serverSubscribers[addr] = device
                updateConnectedSet()
                onNeighborChanged(addr, true, null)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                serverSubscribers.remove(addr)
                updateConnectedSet()
                if (!readyClientGatts.containsKey(addr)) {
                    onNeighborChanged(addr, false, null)
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
            value: ByteArray?,
        ) {
            if (responseNeeded) {
                runCatching {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
            if (characteristic.uuid == CHARACTERISTIC_UUID && value != null && value.isNotEmpty()) {
                val addr = device.address ?: "unknown"
                serverSubscribers.putIfAbsent(addr, device)
                updateConnectedSet()
                onFrameReceived(addr, value.copyOf())
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                val addr = device.address
                if (addr != null) {
                    serverSubscribers[addr] = device
                    updateConnectedSet()
                }
            }
            if (responseNeeded) {
                runCatching {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }
    }

    private fun startAdvertising(bt: BluetoothAdapter) {
        val adv = bt.bluetoothLeAdvertiser ?: run {
            Log.w(TAG, "Device does not support BLE peripheral advertising")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        runCatching { adv.startAdvertising(settings, data, advertiseCallback) }
            .onSuccess { advertiser = adv }
            .onFailure { Log.e(TAG, "startAdvertising failed", it) }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "BLE advertising active")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "BLE advertise start failure code=$errorCode")
        }
    }

    private fun startScanning(bt: BluetoothAdapter) {
        val leScanner = bt.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .build()
        runCatching { leScanner.startScan(listOf(filter), settings, scanCallback) }
            .onSuccess { scanner = leScanner }
            .onFailure { Log.e(TAG, "startScan failed", it) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val addr = device.address ?: return
            onNeighborChanged(addr, isConnected(addr), result.rssi)

            if (clientGatts.containsKey(addr) || clientGatts.size >= MAX_CLIENT_CONNECTIONS) {
                return
            }
            val now = System.currentTimeMillis()
            val last = lastConnectAttemptMs[addr] ?: 0L
            val cooldown = connectBackoffMs[addr] ?: RECONNECT_COOLDOWN_MS
            if (now - last < cooldown) return
            lastConnectAttemptMs[addr] = now

            connectToDevice(device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed errorCode=$errorCode")
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        val addr = device.address ?: return
        val gatt = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, clientGattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, clientGattCallback)
            }
        }.getOrNull() ?: return
        clientGatts[addr] = gatt
    }

    private val clientGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val addr = gatt.device?.address ?: return
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connectBackoffMs[addr] = RECONNECT_COOLDOWN_MS
                runCatching { gatt.requestMtu(REQUESTED_MTU) }
                    .onFailure { runCatching { gatt.discoverServices() } }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                clientGatts.remove(addr)
                readyClientGatts.remove(addr)
                runCatching { gatt.close() }
                val prev = connectBackoffMs[addr] ?: RECONNECT_COOLDOWN_MS
                connectBackoffMs[addr] = (prev * 2).coerceAtMost(30_000L)
                updateConnectedSet()
                if (!serverSubscribers.containsKey(addr)) {
                    onNeighborChanged(addr, false, null)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            runCatching { gatt.discoverServices() }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val addr = gatt.device?.address ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = gatt.getService(SERVICE_UUID) ?: return
            val ch = service.getCharacteristic(CHARACTERISTIC_UUID) ?: return

            runCatching { gatt.setCharacteristicNotification(ch, true) }
            val cccd = ch.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                writeDescriptorCompat(gatt, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }
            readyClientGatts[addr] = gatt
            updateConnectedSet()
            onNeighborChanged(addr, true, null)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == CHARACTERISTIC_UUID && value.isNotEmpty()) {
                val addr = gatt.device?.address ?: "unknown"
                onFrameReceived(addr, value.copyOf())
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                val bytes = characteristic.value ?: return
                if (characteristic.uuid == CHARACTERISTIC_UUID && bytes.isNotEmpty()) {
                    val addr = gatt.device?.address ?: "unknown"
                    onFrameReceived(addr, bytes.copyOf())
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            writeWatchdogJob?.cancel()
            writeInFlight.set(false)
            drainNextWrite()
        }
    }

    private fun drainNextWrite() {
        if (!writeInFlight.compareAndSet(false, true)) return
        val next = writeQueue.poll()
        if (next == null) {
            writeInFlight.set(false)
            return
        }
        val service = next.gatt.getService(SERVICE_UUID)
        val ch = service?.getCharacteristic(CHARACTERISTIC_UUID)
        if (ch == null) {
            writeInFlight.set(false)
            drainNextWrite()
            return
        }
        val ok = writeCharacteristicCompat(next.gatt, ch, next.frame)
        if (!ok) {
            writeInFlight.set(false)
            drainNextWrite()
        } else {
            writeWatchdogJob?.cancel()
            writeWatchdogJob = scope.launch {
                delay(WRITE_DRAIN_SAFETY_MS)
                if (writeInFlight.compareAndSet(true, false)) {
                    drainNextWrite()
                }
            }
        }
    }

    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray,
    ): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }.getOrDefault(false)

    private fun writeDescriptorCompat(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        bytes: ByteArray,
    ) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, bytes)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = bytes
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        }
    }

    private fun notifyDevice(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray,
    ): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.notifyCharacteristicChanged(device, characteristic, false, bytes) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            @Suppress("DEPRECATION")
            server.notifyCharacteristicChanged(device, characteristic, false)
        }
    }.getOrDefault(false)

    private fun isConnected(addr: String): Boolean =
        readyClientGatts.containsKey(addr) || serverSubscribers.containsKey(addr)

    private fun updateConnectedSet() {
        _connectedAddresses.value = (readyClientGatts.keys + serverSubscribers.keys).toSet()
    }
}
