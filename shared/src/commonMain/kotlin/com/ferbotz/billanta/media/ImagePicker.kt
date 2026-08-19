package com.ferbotz.billanta.media

import com.ferbotz.billanta.core.AppError
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.core.asFailure

/** An image the user chose, already read into memory and ready for `POST /media`. */
class PickedImage(
    val fileName: String,
    val contentType: String,
    val bytes: ByteArray,
)

/**
 * Runs the platform photo picker.
 *
 * Success with `null` means the user backed out — a decision, not a fault, and the UI should say
 * nothing. A failure is something that actually went wrong and is worth showing.
 */
fun interface ImagePicker {
    suspend fun pickImage(): AppResult<PickedImage?>
}

/**
 * Bridge between shared UI and the platform picker, mirroring how sign-in is wired.
 *
 * On Android the picker needs an Activity to register its result launcher against, so MainActivity
 * installs one for its own lifetime; a platform that has not wired one reports the feature as
 * unavailable rather than failing at the call site.
 */
class ImagePickerCoordinator {
    var picker: ImagePicker? = null

    val isAvailable: Boolean get() = picker != null

    suspend fun pick(): AppResult<PickedImage?> =
        picker?.pickImage()
            ?: AppError.Validation("Choosing an image isn't available in this build").asFailure()

    companion object {
        /** `POST /media` rejects anything larger, so refuse before spending the upload. */
        const val MAX_BYTES = 8 * 1024 * 1024

        /**
         * What the server decodes (BE-013). It accepts any image type up to 8 MB and always returns
         * WebP, so this list is only a client-side sanity check — the server remains the authority.
         */
        val ACCEPTED_CONTENT_TYPES =
            setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif")

        /** Null when the image is fine; otherwise why it cannot be uploaded. */
        fun rejectionReason(image: PickedImage): String? = when {
            image.bytes.isEmpty() -> "That image is empty."
            image.bytes.size > MAX_BYTES ->
                "That image is ${image.bytes.size / (1024 * 1024)} MB. The limit is 8 MB."
            // Unknown types are allowed through: the picker's reported type is not always right,
            // and the server is the authority. Only a clearly non-image is refused here.
            !image.contentType.startsWith("image/") -> "That file isn't an image."
            else -> null
        }
    }
}
