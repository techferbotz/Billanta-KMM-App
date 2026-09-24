package com.ferbotz.billanta

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.ferbotz.billanta.di.AppContainer
import com.google.firebase.FirebaseApp
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {

    /** The answer is the user's to give; nothing needs doing with it here. */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as BillantaApplication).container
        // The credential UI needs an Activity, so the provider lives for this activity's lifetime.
        container.signInCoordinator.provider = GoogleCredentialTokenProvider(this)
        // Registering a result launcher has to happen before the activity starts, so this is
        // constructed here rather than lazily on first use.
        container.imagePickerCoordinator.picker =
            AndroidImagePicker(this) { container.remoteConfig.current.limits.maxMediaBytes }

        setContent {
            App(container, onExit = { finish() })
        }
        askForNotificationsOnce(container)
    }

    /**
     * Android 13+ shows no notification until the app asks and the user agrees.
     *
     * Asked once per install, and only when Firebase is actually configured: prompting for a
     * feature that cannot deliver anything yet would spend the one chance for nothing. After a
     * refusal the switch lives in the system settings, where the user left it.
     */
    private fun askForNotificationsOnce(container: AppContainer) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (FirebaseApp.getApps(this).isEmpty()) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted || container.prefs.getBoolean(KEY_ASKED_NOTIFICATIONS) == true) return
        container.prefs.putBoolean(KEY_ASKED_NOTIFICATIONS, true)
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onDestroy() {
        super.onDestroy()
        val container = (application as BillantaApplication).container
        if ((container.signInCoordinator.provider as? GoogleCredentialTokenProvider)?.activity === this) {
            container.signInCoordinator.provider = null
        }
        if ((container.imagePickerCoordinator.picker as? AndroidImagePicker)?.activity === this) {
            container.imagePickerCoordinator.picker = null
        }
    }

    private companion object {
        const val KEY_ASKED_NOTIFICATIONS = "notifications.permissionAsked"
    }
}
