package chat.cypher.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Keeps the Cypher BLE mesh link layer running while the user switches apps or locks the screen.
 */
class MeshForegroundService : Service() {
    companion object {
        private const val TAG = "MeshForegroundService"
        private const val CHANNEL_ID = "cypher_mesh_channel"
        private const val NOTIFICATION_ID = 4101
        const val ACTION_STOP = "chat.cypher.android.action.STOP_MESH"

        fun start(context: Context) {
            val intent = Intent(context, MeshForegroundService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { err ->
                Log.w(TAG, "Unable to start foreground service: ${err.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        val notification = buildNotification()
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        }.getOrElse { err ->
            Log.w(TAG, "startForeground not allowed in current permission state: ${err.message}")
            false
        }
        if (!started) {
            stopSelf()
            return
        }
        (application as? CypherApplication)?.meshController?.startMesh()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as? CypherApplication)?.meshController?.stopMesh()
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
            return START_NOT_STICKY
        }
        (application as? CypherApplication)?.meshController?.startMesh()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.mesh_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.mesh_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.mesh_service_title))
            .setContentText(getString(R.string.mesh_service_text))
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
