package com.ferbotz.billanta.data.api

import com.ferbotz.billanta.config.AppIdentity
import com.ferbotz.billanta.core.BillantaJson
import com.ferbotz.billanta.session.TokenPair
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json

data class BillantaApiConfig(
    val baseUrl: String,
    val enableHttpLogging: Boolean = false,
) {
    /** Normalized to end with `/` so relative paths resolve under it. */
    val normalizedBaseUrl: String = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    /** The API's host — the only one that is sent the app's identity headers. */
    val apiHost: String get() = Url(normalizedBaseUrl).host
}

/** Supplies/rotates tokens for the authed client. Implemented by the session TokenManager. */
interface TokenRefresher {
    suspend fun currentTokens(): TokenPair?

    /**
     * Rotates the refresh token (single-flight). [staleAccessToken] is the access token that just
     * got a 401 — if the stored one already differs, another caller refreshed first and the stored
     * pair is returned as-is. Returns null when the session is unrecoverable.
     */
    suspend fun refresh(staleAccessToken: String?): TokenPair?
}

private fun TokenPair.toBearer() = BearerTokens(accessToken, refreshToken)

/** For the auth endpoints — no bearer, no refresh (avoids recursion into itself). */
fun createAuthlessHttpClient(
    config: BillantaApiConfig,
    identity: AppIdentity? = null,
): HttpClient = HttpClient {
    expectSuccess = false
    install(ContentNegotiation) { json(BillantaJson) }
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        requestTimeoutMillis = 60_000
    }
    if (config.enableHttpLogging) {
        install(Logging) { level = LogLevel.INFO }
    }
    identity?.let { installAppHeaders(it, config.apiHost) }
    defaultRequest { url(config.normalizedBaseUrl) }
}

/**
 * The main client. Attaches `Authorization: Bearer` when a session exists (template browsing
 * works signed-out) and transparently rotates tokens on 401 via [TokenRefresher].
 */
fun createAuthedHttpClient(
    config: BillantaApiConfig,
    refresher: TokenRefresher,
    identity: AppIdentity? = null,
): HttpClient = HttpClient {
    expectSuccess = false
    install(ContentNegotiation) { json(BillantaJson) }
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        requestTimeoutMillis = 60_000
    }
    if (config.enableHttpLogging) {
        install(Logging) { level = LogLevel.INFO }
    }
    install(Auth) {
        bearer {
            loadTokens { refresher.currentTokens()?.toBearer() }
            refreshTokens { refresher.refresh(oldTokens?.accessToken)?.toBearer() }
            // All requests go to our API, so always attach the token on the first attempt.
            sendWithoutRequest { true }
        }
    }
    identity?.let { installAppHeaders(it, config.apiHost) }
    defaultRequest { url(config.normalizedBaseUrl) }
}

/**
 * The targeting headers (BE-014), on every request to the Billanta API — and on no other.
 *
 * These same clients fetch logo, signature and template images straight from S3, and a per-install
 * id has no business in a third party's access logs. So the host is checked at send time, once the
 * base URL has been applied, rather than the headers being stamped on everything the client sends.
 */
internal fun HttpClientConfig<*>.installAppHeaders(identity: AppIdentity, apiHost: String) {
    install(
        createClientPlugin("BillantaAppHeaders") {
            on(Send) { request ->
                if (request.url.host == apiHost) {
                    request.headers[AppIdentity.HEADER_PLATFORM] = identity.platform
                    request.headers[AppIdentity.HEADER_VERSION] = identity.version
                    // "0" would read as a real build to a version rule; unknown is better left unsaid.
                    if (identity.build > 0) request.headers[AppIdentity.HEADER_BUILD] = identity.build.toString()
                    if (identity.deviceId.isNotBlank()) request.headers[AppIdentity.HEADER_DEVICE_ID] = identity.deviceId
                }
                proceed(request)
            }
        },
    )
}
