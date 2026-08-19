package com.ferbotz.billanta.session

import com.ferbotz.billanta.core.AppError
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.core.EpochClock
import com.ferbotz.billanta.core.KeyValueStore
import com.ferbotz.billanta.core.asFailure
import com.ferbotz.billanta.data.api.AuthApi
import com.ferbotz.billanta.data.api.BillantaApi
import com.ferbotz.billanta.data.api.toDomain
import com.ferbotz.billanta.data.local.ProfileLocalDataSource
import com.ferbotz.billanta.domain.model.UserAccount
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

sealed interface AuthState {
    /** Session being restored from disk at startup. */
    data object Restoring : AuthState

    /** Usable offline/guest — everything works locally, nothing syncs. */
    data object SignedOut : AuthState

    data class SignedIn(val user: UserAccount) : AuthState
}

/**
 * The session manager. Owns sign-in/out, the persisted profile, and the account-switch guard:
 * local data belongs to ONE account (`ownerUserId`), and signing into a different account wipes
 * it before the new session starts — the two users' invoices must never mix.
 */
class UserManager(
    private val authApi: AuthApi,
    private val api: BillantaApi,
    private val tokenManager: TokenManager,
    private val profileLocal: ProfileLocalDataSource,
    private val keyValueStore: KeyValueStore,
    private val wipeLocalData: suspend () -> Unit,
    /** Hands the existing local rows to a new server identity; see the sign-in path below. */
    private val reownLocalData: suspend () -> Unit,
    private val clock: EpochClock,
) {
    private val _authState = MutableStateFlow<AuthState>(AuthState.Restoring)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _sessionExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emitted when the refresh chain dies (e.g. token theft response) — UI should show sign-in. */
    val sessionExpired: SharedFlow<Unit> = _sessionExpired.asSharedFlow()

    val isSignedIn: Boolean get() = _authState.value is AuthState.SignedIn
    val currentUser: UserAccount? get() = (_authState.value as? AuthState.SignedIn)?.user

    init {
        tokenManager.onSessionExpired = {
            _authState.value = AuthState.SignedOut
            _sessionExpired.tryEmit(Unit)
        }
    }

    /** Called once at startup: restores the session from disk without touching the network. */
    suspend fun restore() {
        val account = profileLocal.getAccount()
        _authState.value = if (tokenManager.hasSession() && account != null) {
            AuthState.SignedIn(account)
        } else {
            AuthState.SignedOut
        }
    }

    /**
     * Completes sign-in with a Google idToken (obtained by platform UI). Verifies server-side,
     * stores the rotated token pair, and persists the profile.
     */
    suspend fun signInWithGoogle(idToken: String): AppResult<UserAccount> {
        return when (val result = authApi.googleSignIn(idToken)) {
            is AppResult.Failure -> result
            is AppResult.Success -> {
                val response = result.value
                val user = response.user.toDomain()

                val previousOwner = keyValueStore.getString(KEY_OWNER_USER_ID)
                val previousEmail = keyValueStore.getString(KEY_OWNER_EMAIL)
                if (previousOwner != null && previousOwner != user.id) {
                    if (previousEmail != null && previousEmail.equals(user.email, ignoreCase = true)) {
                        // Same person, new server identity — their account was deleted or the
                        // database was reset, so signing in again minted a fresh id (BE-010).
                        // Wiping here would destroy the only copy of their invoices, since this is
                        // exactly the situation in which sync had stopped working. Re-own instead.
                        reownLocalData()
                    } else {
                        // A different person on the same device: their data must not mix.
                        wipeLocalData()
                    }
                }
                keyValueStore.putString(KEY_OWNER_USER_ID, user.id)
                keyValueStore.putString(KEY_OWNER_EMAIL, user.email)

                tokenManager.saveFromAuthResponse(response)
                profileLocal.saveAccount(user)
                _authState.value = AuthState.SignedIn(user)
                AppResult.Success(user)
            }
        }
    }

    /**
     * The session is valid-looking but its account is gone (BE-010). A refresh cannot rescue it, so
     * drop straight to signed-out and let the user sign in again — their local data is kept, and
     * the next sign-in re-owns it.
     */
    suspend fun onAccountVanished() {
        if (_authState.value is AuthState.SignedOut) return
        tokenManager.clear()
        _authState.value = AuthState.SignedOut
        _sessionExpired.tryEmit(Unit)
    }

    /** Revokes the refresh token (best effort) and drops the session. Local data stays. */
    suspend fun signOut() {
        tokenManager.currentTokens()?.let { authApi.logout(it.refreshToken) }
        tokenManager.clear()
        _authState.value = AuthState.SignedOut
    }

    /** `DELETE /users/me` — the server cascades everything; mirror it locally. */
    suspend fun deleteAccount(): AppResult<Unit> {
        val result = api.deleteMe()
        if (result is AppResult.Success) {
            tokenManager.clear()
            wipeLocalData()
            keyValueStore.remove(KEY_OWNER_USER_ID)
            _authState.value = AuthState.SignedOut
        }
        return result
    }

    /** Re-fetches the profile (e.g. to pick up a premium upgrade). */
    suspend fun refreshProfile(): AppResult<UserAccount> {
        if (!isSignedIn) return AppError.SessionExpired.asFailure()
        return when (val result = api.getMe()) {
            is AppResult.Failure -> result
            is AppResult.Success -> {
                val user = result.value.toDomain()
                profileLocal.saveAccount(user)
                _authState.value = AuthState.SignedIn(user)
                AppResult.Success(user)
            }
        }
    }

    /** PATCH /users/me. Pass `clearPhoto = true` to explicitly null the photo out. */
    suspend fun updateProfile(name: String? = null, photoUrl: String? = null, clearPhoto: Boolean = false): AppResult<UserAccount> {
        val patch = buildJsonObject {
            name?.let { put("name", JsonPrimitive(it)) }
            when {
                clearPhoto -> put("photoUrl", JsonNull)
                photoUrl != null -> put("photoUrl", JsonPrimitive(photoUrl))
            }
        }
        return when (val result = api.patchMe(patch)) {
            is AppResult.Failure -> result
            is AppResult.Success -> {
                val user = result.value.toDomain()
                profileLocal.saveAccount(user)
                _authState.value = AuthState.SignedIn(user)
                AppResult.Success(user)
            }
        }
    }

    private companion object {
        const val KEY_OWNER_USER_ID = "session.ownerUserId"

        /**
         * Who the local data belongs to, by email rather than by id.
         *
         * The id changes if the account is deleted and remade; the person does not. This is what
         * lets a re-sign-in after BE-010's ACCOUNT_NOT_FOUND keep the user's invoices while a
         * genuinely different account still wipes them.
         */
        const val KEY_OWNER_EMAIL = "session.ownerEmail"
    }
}
