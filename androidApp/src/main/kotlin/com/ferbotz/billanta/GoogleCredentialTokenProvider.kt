package com.ferbotz.billanta

import androidx.activity.ComponentActivity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.ferbotz.billanta.core.AppError
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.core.asFailure
import com.ferbotz.billanta.session.GoogleIdTokenProvider
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * Credential Manager flow → Google idToken. The server verifies the token's `aud` against its
 * GOOGLE_CLIENT_ID env var, so [WEB_CLIENT_ID] must be the SAME web client id the backend uses.
 */
class GoogleCredentialTokenProvider(internal val activity: ComponentActivity) : GoogleIdTokenProvider {

    override suspend fun requestIdToken(): AppResult<String> {
        if (WEB_CLIENT_ID.startsWith("REPLACE")) {
            return AppError.Validation(
                "Google Sign-In isn't configured — set WEB_CLIENT_ID in GoogleCredentialTokenProvider.",
            ).asFailure()
        }
        return try {
            val option = GetGoogleIdOption.Builder()
                .setServerClientId(WEB_CLIENT_ID)
                .setFilterByAuthorizedAccounts(false)
                .build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val credential = CredentialManager.create(activity).getCredential(activity, request).credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                AppResult.Success(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } else {
                AppError.Unexpected("Unexpected credential type").asFailure()
            }
        } catch (_: GetCredentialCancellationException) {
            AppError.Validation("Sign-in cancelled").asFailure()
        } catch (e: GetCredentialException) {
            AppError.Unexpected(e.message ?: "Sign-in failed").asFailure()
        }
    }

    companion object {
        const val WEB_CLIENT_ID = "452978864976-s12ek778jagovgqkdtobadj0hhb316m3.apps.googleusercontent.com"
    }
}
