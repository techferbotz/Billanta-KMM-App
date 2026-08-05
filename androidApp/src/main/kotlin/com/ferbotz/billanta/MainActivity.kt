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

        setContent {
            App(container)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val coordinator = (application as BillantaApplication).container.signInCoordinator
        if ((coordinator.provider as? GoogleCredentialTokenProvider)?.activity === this) {
            coordinator.provider = null
        }
    }
}
