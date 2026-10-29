package chat.cypher.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import chat.cypher.android.ble.BleLinkLayer
import chat.cypher.android.core.MeshRuntime

/**
 * Foreground service keeping the mesh alive: pumps the engine clock and
 * drives the BLE radio, mirroring bitchat's `MeshForegroundService`.
 */
class MeshForegroundService : Service() {

    private var linkLayer: BleLinkLayer? = null
    private lateinit var pumpThread: HandlerThread
    private lateinit var pump: Handler

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nickname = intent?.getStringExtra(EXTRA_NICKNAME) ?: "cypher"
        MeshRuntime.start(this, nickname)
        if (linkLayer == null) {
            linkLayer = BleLinkLayer(this).also { it.start() }
        }
        startPump()
        return START_STICKY
    }

    override fun onDestroy() {
        pumpThread.quitSafely()
        linkLayer?.stop()
        MeshRuntime.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startPump() {
        if (::pumpThread.isInitialized) return
        pumpThread = HandlerThread("cypher-mesh-pump").apply { start() }
        pump = Handler(pumpThread.looper)
        // 250 ms pump: jittered relays (8–220 ms) and the 5 s maintenance
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

    private fun buildNotification(): Notification {
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
            .setContentText(getString(R.string.mesh_notification_text))
            .setOngoing(true)
            .setContentIntent(intent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "cypher_mesh"
        private const val NOTIFICATION_ID = 1
        private const val PUMP_INTERVAL_MS = 250L
        private const val EXTRA_NICKNAME = "nickname"

        fun start(context: Context, nickname: String) {
            val intent = Intent(context, MeshForegroundService::class.java)
                .putExtra(EXTRA_NICKNAME, nickname)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
