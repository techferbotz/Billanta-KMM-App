package com.ferbotz.billanta

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
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
class AndroidImagePicker(
    internal val activity: ComponentActivity,
    /** `limits.maxMediaBytes` from `GET /config`, read at pick time so a raised cap applies at once. */
    private val maxBytes: () -> Long = { ImagePickerCoordinator.MAX_BYTES.toLong() },
) : ImagePicker {

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
        val original = PickedImage(fileName = displayName(uri, contentType), contentType = contentType, bytes = bytes)
        val picked = shrinkForUpload(original) ?: original

        ImagePickerCoordinator.rejectionReason(picked, maxBytes())?.let {
            return@withContext AppError.Validation(it).asFailure()
        }
        picked.asSuccess()
    }

    /**
     * A photo straight off a phone camera can be 8,000 px across and well over the upload cap, and
     * the server keeps at most 1280 px of it anyway (BE-020). So anything larger than
     * [UPLOAD_LONG_EDGE] or the cap is decoded smaller and re-encoded before it is sent: faster, far
     * less data, and no "too large" refusal for an ordinary photo.
     *
     * Transparency decides the format: an image with an alpha channel — a signature on a clear
     * background — stays PNG, because JPEG would fill the clear parts with black. Everything else
     * becomes JPEG. Returns null to mean "send the original": small enough already, a format
     * [ImageDecoder] cannot read, or an Android version without it (before 9), where the size check
     * still applies as before.
     */
    private fun shrinkForUpload(image: PickedImage): PickedImage? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, bounds)
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (longEdge in 1..UPLOAD_LONG_EDGE && image.bytes.size <= maxBytes()) return null
        return runCatching {
            // ImageDecoder applies the photo's EXIF rotation, so a portrait shot stays upright once
            // the metadata that described the rotation is gone. Power-of-two sampling keeps the
            // aspect ratio whatever that rotation is.
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(image.bytes))) { decoder, info, _ ->
                val edge = maxOf(info.size.width, info.size.height)
                var sample = 1
                while (edge / sample > UPLOAD_LONG_EDGE) sample *= 2
                decoder.setTargetSampleSize(sample)
                // Software memory, because a hardware bitmap cannot be compressed back to bytes.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val png = bitmap.hasAlpha()
            val out = ByteArrayOutputStream()
            bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            bitmap.recycle()
            val extension = if (png) "png" else "jpg"
            PickedImage(
                fileName = "${image.fileName.substringBeforeLast('.')}.$extension",
                contentType = if (png) "image/png" else "image/jpeg",
                bytes = out.toByteArray(),
            )
        }.onFailure {
            logWarn(LOG_TAG, "could not shrink a ${image.contentType} of ${image.bytes.size} bytes: ${it::class.simpleName}")
        }.getOrNull()
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

        /** Room for the server's 1280 px copy, with nothing sent that it would throw away. */
        const val UPLOAD_LONG_EDGE = 2048

        /** Ignored for PNG, which is lossless. */
        const val JPEG_QUALITY = 85
    }
}
