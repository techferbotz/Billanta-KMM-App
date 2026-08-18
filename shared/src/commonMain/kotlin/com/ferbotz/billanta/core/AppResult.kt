package com.ferbotz.billanta.core

/**
 * The single error vocabulary of the data layer. API failures, IO failures and validation
 * failures all funnel into this so repositories/UI never handle raw exceptions.
 */
sealed class AppError {
    /** No connectivity / DNS / timeout — worth retrying, the request may not have arrived. */
    data class Network(val detail: String? = null) : AppError()

    /** The server answered with a non-2xx envelope. */
    data class Http(val status: Int, val code: String? = null, val serverMessage: String? = null) : AppError() {
        val isUnauthorized: Boolean get() = status == 401
        val isConflict: Boolean get() = status == 409
        val isPremiumRequired: Boolean get() = code == "PREMIUM_REQUIRED"
    }

    /** Locally-detected bad input (e.g. malformed quantity, out-of-bounds money). */
    data class Validation(val message: String) : AppError()

    /** The session is gone (refresh token rejected/reused) — user must sign in again. */
    data object SessionExpired : AppError()

    data class Unexpected(val detail: String? = null) : AppError()

    fun userMessage(): String = when (this) {
        is Network -> "You're offline. Changes are saved on this device and will sync later."
        is Http -> serverMessage ?: "Something went wrong (HTTP $status)."
        is Validation -> message
        is SessionExpired -> "Your session has expired. Please sign in again."
        is Unexpected -> "Something went wrong." + (detail?.let { " ($it)" } ?: "")
    }

    /**
     * Everything known about the failure, for logs rather than for people.
     *
     * [userMessage] deliberately hides the status code and drops the server's error code once it
     * has a message to show — which is exactly what you need when diagnosing one.
     */
    fun diagnostic(): String = when (this) {
        is Network -> "Network(${detail ?: "unreachable"})"
        is Http -> "HTTP $status" + (code?.let { " [$it]" } ?: "") + (serverMessage?.let { " $it" } ?: "")
        is Validation -> "Validation($message)"
        is SessionExpired -> "SessionExpired"
        is Unexpected -> "Unexpected(${detail ?: "no detail"})"
    }
}

sealed class AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>()
    data class Failure(val error: AppError) : AppResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    fun getOrNull(): T? = (this as? Success)?.value
    fun errorOrNull(): AppError? = (this as? Failure)?.error

    inline fun <R> map(transform: (T) -> R): AppResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    inline fun onSuccess(block: (T) -> Unit): AppResult<T> {
        if (this is Success) block(value)
        return this
    }

    inline fun onFailure(block: (AppError) -> Unit): AppResult<T> {
        if (this is Failure) block(error)
        return this
    }
}

inline fun <T, R> AppResult<T>.flatMap(transform: (T) -> AppResult<R>): AppResult<R> = when (this) {
    is AppResult.Success -> transform(value)
    is AppResult.Failure -> this
}

fun <T> T.asSuccess(): AppResult<T> = AppResult.Success(this)
fun AppError.asFailure(): AppResult.Failure = AppResult.Failure(this)
