package com.ferbotz.billanta.share

/** Hands a finished file to the platform share sheet. */
interface FileShareService {
    suspend fun share(bytes: ByteArray, fileName: String, mimeType: String)
}

object NoopFileShareService : FileShareService {
    override suspend fun share(bytes: ByteArray, fileName: String, mimeType: String) = Unit
}
