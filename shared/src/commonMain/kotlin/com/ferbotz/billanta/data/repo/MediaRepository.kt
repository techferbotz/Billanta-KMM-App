package com.ferbotz.billanta.data.repo

import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.data.api.BillantaApi
import com.ferbotz.billanta.data.api.MediaDto

/** Image upload (logo/signature/QR). Requires connectivity — media has no offline queue in v1. */
class MediaRepository(private val api: BillantaApi) {

    suspend fun uploadImage(
        bytes: ByteArray,
        fileName: String = "image.jpg",
        contentType: String = "image/jpeg",
    ): AppResult<MediaDto> = api.uploadMedia(fileName, contentType, bytes)
}
