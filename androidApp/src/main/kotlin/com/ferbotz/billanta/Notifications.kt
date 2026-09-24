package com.ferbotz.billanta

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * The app's one notification channel, and posting to it.
 *
 * Firebase Cloud Messaging shows a notification by itself only while the app is in the background,
 * using the channel, icon and colour named in the manifest. While the app is open the message
 * comes to [BillantaMessagingService] instead, and nothing appears unless the app posts it — this is
 * that half.
 */
object Notifications {

    /** Also the manifest's `default_notification_channel_id`, so both paths land in one channel. */
    const val CHANNEL_GENERAL = "general"

    /** Creating a channel that exists is a no-op, so this is safe on every start. */
    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_GENERAL,
            context.getString(R.string.notification_channel_general),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.notification_channel_general_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Posts a notification that opens the app when tapped. Does nothing when the user has turned
     * notifications off (or, on Android 13+, not granted them) — that is their answer, not an error.
     *
     * The permission lint is suppressed because the check is [NotificationManagerCompat.areNotificationsEnabled],
     * which covers POST_NOTIFICATIONS on 13+ but is not one lint recognises.
     */
    @SuppressLint("MissingPermission")
    fun show(context: Context, title: String?, body: String?) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_GENERAL)
            .setSmallIcon(R.drawable.ic_stat_billanta)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(title ?: context.getString(R.string.app_name))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
