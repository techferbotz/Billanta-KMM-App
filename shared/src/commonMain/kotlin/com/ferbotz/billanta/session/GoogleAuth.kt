package com.ferbotz.billanta.session

import com.ferbotz.billanta.core.AppError
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.core.asFailure

/** Runs the platform sign-in UI and yields the Google idToken `POST /auth/google` verifies. */
fun interface GoogleIdTokenProvider {
    suspend fun requestIdToken(): AppResult<String>
}

/**
 * Bridge between shared UI and the platform sign-in flow. On Android the provider needs an
 * Activity, so MainActivity registers one for its lifetime; iOS can set one at startup.
 */
class SignInCoordinator {
    var provider: GoogleIdTokenProvider? = null

    suspend fun requestIdToken(): AppResult<String> =
        provider?.requestIdToken()
            ?: AppError.Validation("Google Sign-In isn't available in this build").asFailure()
}
