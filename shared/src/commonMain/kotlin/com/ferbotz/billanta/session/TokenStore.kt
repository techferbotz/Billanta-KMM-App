package com.ferbotz.billanta.session

import com.ferbotz.billanta.core.KeyValueStore

data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
)

/**
 * Persists the session tokens. The refresh token is single-use (the server rotates it on every
 * refresh and treats reuse as theft), so writes must land before the new pair is ever used.
 */
class TokenStore(private val store: KeyValueStore) {

    fun get(): TokenPair? {
        val access = store.getString(KEY_ACCESS) ?: return null
        val refresh = store.getString(KEY_REFRESH) ?: return null
        return TokenPair(access, refresh, store.getLong(KEY_EXPIRES_AT) ?: 0L)
    }

    fun save(tokens: TokenPair) {
        store.putString(KEY_ACCESS, tokens.accessToken)
        store.putString(KEY_REFRESH, tokens.refreshToken)
        store.putLong(KEY_EXPIRES_AT, tokens.expiresAtMillis)
    }

    fun clear() {
        store.remove(KEY_ACCESS)
        store.remove(KEY_REFRESH)
        store.remove(KEY_EXPIRES_AT)
    }

    private companion object {
        const val KEY_ACCESS = "session.accessToken"
        const val KEY_REFRESH = "session.refreshToken"
        const val KEY_EXPIRES_AT = "session.expiresAtMillis"
    }
}
