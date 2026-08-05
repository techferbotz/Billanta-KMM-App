package com.ferbotz.billanta.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface ConnectivityObserver {
    val isOnline: StateFlow<Boolean>
}

/**
 * Fallback used where no platform observer is wired (e.g. iOS v1): assume online and let
 * failed requests fall back to the offline path.
 */
object AlwaysOnlineConnectivity : ConnectivityObserver {
    override val isOnline: StateFlow<Boolean> = MutableStateFlow(true)
}
