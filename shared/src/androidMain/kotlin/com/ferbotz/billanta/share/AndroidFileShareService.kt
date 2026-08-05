package com.ferbotz.billanta.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Writes the file into the app's share cache and opens the system share sheet through a
 * FileProvider URI (declared in the androidApp manifest as `<applicationId>.fileprovider`).
 */
class AndroidFileShareService(context: Context) : FileShareService {

    private val appContext = context.applicationContext

    override suspend fun share(bytes: ByteArray, fileName: String, mimeType: String) {
        val uri = withContext(Dispatchers.IO) {
            val dir = File(appContext.cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, fileName)
            file.writeBytes(bytes)
            FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share invoice").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(chooser)
    }
}
