package com.ferbotz.billanta

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.ferbotz.billanta.di.AppContainer
import com.ferbotz.billanta.di.createAppContainer

class BillantaApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = createAppContainer(
            context = this,
            // Debug builds may point at another host (local.properties `billanta.debugBaseUrl`).
            baseUrl = BuildConfig.API_BASE_URL_OVERRIDE.ifEmpty { BillantaConfig.BASE_URL },
            enableHttpLogging = BuildConfig.DEBUG,
        )
        // Before any push can arrive: FCM's background notifications go to this channel by id.
        Notifications.createChannels(this)
        logPushTokenForTesting()
    }

    /**
     * Firebase starts itself from google-services.json. Without that file it stays off and every
     * Firebase call throws, so this checks it actually started. Debug builds only: the token is
     * what the console's "Send test message" needs to reach this one device.
     */
    private fun logPushTokenForTesting() {
        if (!BuildConfig.DEBUG) return
        if (FirebaseApp.getApps(this).isEmpty()) {
            Log.w(BillantaMessagingService.TAG, "Firebase is not configured (no google-services.json): push and crash reporting are off")
            return
        }
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            Log.d(BillantaMessagingService.TAG, "FCM registration token: $token")
        }
    }
}

val Application.appContainer: AppContainer
    get() = (this as BillantaApplication).container
