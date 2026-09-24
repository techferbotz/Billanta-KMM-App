package com.ferbotz.billanta

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Firebase Cloud Messaging's way into the app.
 *
 * There is no backend endpoint to register a device's token with yet, so pushes are sent from the
 * Firebase console — to every install, or to one token for testing. Targeting a particular user
 * (say, "your invoice synced") needs that endpoint first, as a contract request.
 */
class BillantaMessagingService : FirebaseMessagingService() {

    /**
     * A fresh registration token: first launch, reinstall, restored data, or Firebase rotating it.
     * Debug builds log it, which is what the console's "Send test message" asks for; release builds
     * do not, since a token is an identifier for this install.
     */
    override fun onNewToken(token: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "FCM registration token: $token")
    }

    /**
     * Called for a notification message only while the app is in the foreground — in the
     * background FCM posts those itself, from the manifest's channel, icon and colour. So this is
     * the half that would otherwise show nothing at all. Data-only messages are not used yet.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        val notification = message.notification ?: return
        Notifications.show(this, notification.title, notification.body)
    }

    companion object {
        const val TAG = "Billanta/Push"
    }
}
