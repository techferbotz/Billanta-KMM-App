package com.ferbotz.billanta

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as BillantaApplication).container
        // The credential UI needs an Activity, so the provider lives for this activity's lifetime.
        container.signInCoordinator.provider = GoogleCredentialTokenProvider(this)
        // Registering a result launcher has to happen before the activity starts, so this is
        // constructed here rather than lazily on first use.
        container.imagePickerCoordinator.picker = AndroidImagePicker(this)

        setContent {
            App(container, onExit = { finish() })
        }
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
}
