package com.ferbotz.billanta.data.api

import com.ferbotz.billanta.core.AppError
import com.ferbotz.billanta.core.logWarn
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.core.asFailure
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.request
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Every JSON endpoint answers `{ "success": true, "data": … }` or
 * `{ "success": false, "message": "…", "code"?: "…" }` — the HTTP status carries the category.
 */
@Serializable
data class Envelope<T>(
    val success: Boolean,
    val data: T? = null,
    val message: String? = null,
    val code: String? = null,
)

@Serializable
data class ErrorEnvelope(
    val success: Boolean = false,
    val message: String? = null,
    val code: String? = null,
)

suspend fun httpErrorOf(response: HttpResponse): AppError.Http {
    val err = try {
        response.body<ErrorEnvelope>()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }
    val error = AppError.Http(response.status.value, err?.code, err?.message)
    // Every HTTP failure in the app funnels through here, so this is the one place that can name
    // the exact call. Sync is silent by design and its step names cover several endpoints each —
    // without the method and path, a 500 says only "something, somewhere, failed".
    // Method, path and status only: never headers or bodies, which carry tokens and customer data.
    logWarn(
        "Api",
        "${response.request.method.value} ${response.request.url.encodedPath} → ${error.diagnostic()}",
    )
    return error
}

/** Runs a request and unwraps the envelope. `data` must be present on success. */
suspend inline fun <reified T : Any> apiCall(crossinline request: suspend () -> HttpResponse): AppResult<T> {
    val response = try {
        request()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        return AppError.Network(e.message).asFailure()
    }
    return try {
        if (!response.status.isSuccess()) return httpErrorOf(response).asFailure()
        val env = response.body<Envelope<T>>()
        when {
            !env.success -> AppError.Http(response.status.value, env.code, env.message).asFailure()
            env.data != null -> AppResult.Success(env.data)
            else -> AppError.Unexpected("empty response data").asFailure()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        AppError.Unexpected(e.message).asFailure()
    }
}

/** For endpoints where `data: null` is a legitimate answer (e.g. GET /company before setup). */
suspend inline fun <reified T : Any> apiCallNullable(crossinline request: suspend () -> HttpResponse): AppResult<T?> {
    val response = try {
        request()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        return AppError.Network(e.message).asFailure()
    }
    return try {
        if (!response.status.isSuccess()) return httpErrorOf(response).asFailure()
        val env = response.body<Envelope<T>>()
        if (!env.success) AppError.Http(response.status.value, env.code, env.message).asFailure()
        else AppResult.Success(env.data)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        AppError.Unexpected(e.message).asFailure()
    }
}

/** For endpoints whose `data` payload carries nothing (logout, deletes). */
suspend inline fun apiCallUnit(crossinline request: suspend () -> HttpResponse): AppResult<Unit> {
    val response = try {
        request()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        return AppError.Network(e.message).asFailure()
    }
    return try {
        if (!response.status.isSuccess()) return httpErrorOf(response).asFailure()
        val env = response.body<Envelope<JsonElement>>()
        if (!env.success) AppError.Http(response.status.value, env.code, env.message).asFailure()
        else AppResult.Success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        AppError.Unexpected(e.message).asFailure()
    }
}
