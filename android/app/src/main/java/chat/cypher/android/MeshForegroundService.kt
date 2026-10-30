package chat.cypher.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import chat.cypher.android.ble.BleLinkLayer
import chat.cypher.android.core.MeshController
import chat.cypher.android.core.MeshRuntime
import chat.cypher.android.core.MeshStore

/**
 * Foreground service keeping the mesh alive: pumps the engine clock,
 * drives the BLE radio, watches the Bluetooth adapter state, and mirrors
 * the peer count in its notification (bitchat's MeshForegroundService).
 */
class MeshForegroundService : Service() {

    private var linkLayer: BleLinkLayer? = null
    private lateinit var pumpThread: HandlerThread
    private lateinit var pump: Handler

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val handle = MeshRuntime.engineHandle() ?: return
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_OFF -> {
                        linkLayer?.pushRadioState(4L) // poweredOff
                    }
                    BluetoothAdapter.STATE_ON -> {
                        linkLayer?.pushRadioState(5L) // poweredOn
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(0))
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                adapterReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            registerReceiver(adapterReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nickname = MeshStore.nickname()
        MeshRuntime.start(this, nickname)
        MeshController.onEngineStarted()
        if (linkLayer == null) {
            linkLayer = BleLinkLayer(this).also { it.start() }
        }
        startPump()
        updateNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        if (::pumpThread.isInitialized) {
            pumpThread.quitSafely()
        }
        runCatching { unregisterReceiver(adapterReceiver) }
        linkLayer?.stop()
        MeshRuntime.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startPump() {
        if (::pumpThread.isInitialized) return
        pumpThread = HandlerThread("cypher-mesh-pump").apply { start() }
        pump = Handler(pumpThread.looper)
        // 250 ms pump: jittered relays (8–220 ms) and the maintenance
        // cadence all resolve well within this budget.
        pump.post(object : Runnable {
            override fun run() {
                try {
                    linkLayer?.tick()
                } catch (_: Exception) {
                }
                pump.postDelayed(this, PUMP_INTERVAL_MS)
            }
        })
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.mesh_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            channel.description = getString(R.string.mesh_channel_description)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(peers: Int): Notification {
        val intent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder =
            if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
            else Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.mesh_notification_title))
            .setContentText(
                if (peers == 0) getString(R.string.mesh_notification_text)
                else getString(R.string.mesh_notification_peers, peers),
            )
            .setOngoing(true)
            .setContentIntent(intent)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        runCatching {
            manager.notify(
                NOTIFICATION_ID,
                buildNotification(MeshController.peers.count { it.connected }),
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "cypher_mesh"
        private const val NOTIFICATION_ID = 1
        private const val PUMP_INTERVAL_MS = 250L

        fun start(context: Context) {
            val intent = Intent(context, MeshForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
