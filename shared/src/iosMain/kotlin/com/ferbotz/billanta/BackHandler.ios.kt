package com.ferbotz.billanta

import androidx.compose.runtime.Composable

// iOS relies on the on-screen back controls; no system back to intercept here.
@Composable
actual fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit) {
}
