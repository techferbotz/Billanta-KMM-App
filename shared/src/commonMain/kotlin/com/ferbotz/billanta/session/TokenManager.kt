package com.ferbotz.billanta.session

import com.ferbotz.billanta.core.AppError
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.core.EpochClock
import com.ferbotz.billanta.data.api.AuthApi
import com.ferbotz.billanta.data.api.AuthResponseDto
import com.ferbotz.billanta.data.api.TokenRefresher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the token lifecycle. Refresh is single-flight and rotation-safe: the server revokes the
 * whole session chain if a rotated refresh token is ever presented twice, so concurrent 401s must
 * collapse into ONE /auth/refresh call — the second caller gets the first caller's result.
 */
class TokenManager(
    private val tokenStore: TokenStore,
    private val authApi: AuthApi,
    private val clock: EpochClock,
) : TokenRefresher {

    private val mutex = Mutex()

    /** Set by UserManager; fired when the refresh token is rejected and the session is dead. */
    var onSessionExpired: (suspend () -> Unit)? = null

    override suspend fun currentTokens(): TokenPair? = tokenStore.get()

    override suspend fun refresh(staleAccessToken: String?): TokenPair? = mutex.withLock {
        val current = tokenStore.get() ?: return@withLock null
        // Someone else rotated while we waited on the lock — their pair is the live one.
        if (staleAccessToken != null && current.accessToken != staleAccessToken) {
            return@withLock current
        }
        when (val result = authApi.refresh(current.refreshToken)) {
            is AppResult.Success -> saveFromAuthResponse(result.value)
            is AppResult.Failure -> {
                val error = result.error
                if (error is AppError.Http && error.status == 401) {
                    // Invalid/expired/reused — the chain is gone; sign the user out.
                    tokenStore.clear()
                    onSessionExpired?.invoke()
                }
                // On network errors keep the pair: the request may never have reached the server.
                null
            }
        }
    }

    /** Persists a fresh pair from any auth response. Must complete before the pair is used. */
    fun saveFromAuthResponse(response: AuthResponseDto): TokenPair {
        val pair = TokenPair(
            accessToken = response.accessToken,
            refreshToken = response.refreshToken,
            expiresAtMillis = clock.nowMillis() + response.expiresIn * 1000,
        )
        tokenStore.save(pair)
        return pair
    }

    fun clear() = tokenStore.clear()

    fun hasSession(): Boolean = tokenStore.get() != null
}
