package com.ferbotz.billanta

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.ferbotz.billanta.core.AppError
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.core.asFailure
import com.ferbotz.billanta.core.asSuccess
import com.ferbotz.billanta.core.logWarn
import com.ferbotz.billanta.media.ImagePicker
import com.ferbotz.billanta.media.ImagePickerCoordinator
import com.ferbotz.billanta.media.PickedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

/**
 * The system photo picker, via `PickVisualMedia`.
 *
 * Chosen over a `GET_CONTENT` intent because it needs no storage permission at all — on Android 13+
 * it is the OS picker, and below that it falls back to a Google Play version of the same thing. An
 * invoice app asking for "access all your photos" to set a signature would be a poor trade.
 *
 * The launcher must be registered before the activity starts, so this is constructed in onCreate.
 */
class AndroidImagePicker(internal val activity: ComponentActivity) : ImagePicker {

    /** Only one picker can be open at a time; the launcher callback resumes whoever is waiting. */
    private var pending: Continuation<Uri?>? = null

    private val launcher =
        activity.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            pending?.also { pending = null }?.resume(uri)
        }

    override suspend fun pickImage(): AppResult<PickedImage?> {
        if (pending != null) return AppError.Validation("A picker is already open").asFailure()

        val uri = suspendCancellableCoroutine<Uri?> { continuation ->
            pending = continuation
            // Leaving the coroutine suspended forever would wedge every later attempt.
            continuation.invokeOnCancellation { pending = null }
            runCatching {
                launcher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            }.onFailure {
                pending = null
                continuation.resume(null)
            }
        } ?: return null.asSuccess() // backed out of the picker

        return readImage(uri)
    }

    /** Reads the bytes off the IO dispatcher — a photo can be several megabytes. */
    private suspend fun readImage(uri: Uri): AppResult<PickedImage?> = withContext(Dispatchers.IO) {
        val resolver = activity.contentResolver
        val bytes = try {
            resolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Throwable) {
            logWarn(LOG_TAG, "could not read $uri: ${e::class.simpleName} ${e.message}")
            null
        } ?: return@withContext AppError.Validation("That image could not be read.").asFailure()

        val contentType = resolver.getType(uri) ?: "image/jpeg"
        val picked = PickedImage(fileName = displayName(uri, contentType), contentType = contentType, bytes = bytes)

        ImagePickerCoordinator.rejectionReason(picked)?.let {
            return@withContext AppError.Validation(it).asFailure()
        }
        picked.asSuccess()
    }

    /** The picker's own name when it has one, so an upload is recognisable server-side. */
    private fun displayName(uri: Uri, contentType: String): String {
        val fromProvider = runCatching {
            activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        return fromProvider?.takeIf { it.isNotBlank() }
            ?: "image.${contentType.substringAfterLast('/', "jpg")}"
    }

    private companion object {
        const val LOG_TAG = "ImagePicker"
    }
}
