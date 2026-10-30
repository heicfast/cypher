package chat.cypher.android.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import chat.cypher.android.MainActivity
import chat.cypher.android.R

/**
 * DM notifications (bitchat parity: per-sender notifications that deep-link
 * back into the conversation) + process-wide foreground/background bookkeeping.
 */
class Notifier(private val context: Context) {

    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val CHANNEL_DM = "cypher_dm"
        const val EXTRA_OPEN_CONVERSATION = "open_conversation"
        private const val GROUP_DM = "cypher_dm_group"
    }

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_DM,
                    context.getString(R.string.dm_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.dm_channel_description)
                }
            )
        }
    }

    fun notifyDm(peerId: String, nickname: String, content: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val deepLink = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_CONVERSATION, "dm:$peerId")
        }
        val pending = PendingIntent.getActivity(
            context,
            peerId.hashCode(),
            deepLink,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_DM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(nickname)
            .setContentText(content)
            .setGroup(GROUP_DM)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        runCatching { manager.notify(peerId.hashCode(), notification) }
    }

    fun cancelDm(peerId: String) {
        runCatching { manager.cancel(peerId.hashCode()) }
    }

    fun cancelAllDms() {
        runCatching { manager.cancelAll() }
    }
}
