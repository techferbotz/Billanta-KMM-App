package com.ferbotz.billanta

import androidx.compose.runtime.Composable

/**
 * Platform back handling. Android wires this to the system back gesture/button via activity-compose;
 * iOS is a no-op (the app relies on its on-screen back controls). Kept as expect/actual so the
 * common code has one call site in [App].
 */
@Composable
expect fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit)
