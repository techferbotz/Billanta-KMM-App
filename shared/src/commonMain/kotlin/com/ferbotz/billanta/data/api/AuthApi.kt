package com.ferbotz.billanta.data.api

import com.ferbotz.billanta.core.AppResult
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/** The auth routes — always on the auth-less client (these must not recurse into token refresh). */
class AuthApi(private val client: HttpClient) {

    suspend fun googleSignIn(idToken: String): AppResult<AuthResponseDto> = apiCall {
        client.post("auth/google") {
            contentType(ContentType.Application.Json)
            setBody(GoogleSignInRequest(idToken))
        }
    }

    /** Rotates the pair; reuse of an already-rotated token → 401 `REFRESH_TOKEN_REUSED`. */
    suspend fun refresh(refreshToken: String): AppResult<AuthResponseDto> = apiCall {
        client.post("auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshTokenRequest(refreshToken))
        }
    }

    /** Always 200, silent about unknown tokens. */
    suspend fun logout(refreshToken: String): AppResult<Unit> = apiCallUnit {
        client.post("auth/logout") {
            contentType(ContentType.Application.Json)
            setBody(RefreshTokenRequest(refreshToken))
        }
    }
}
