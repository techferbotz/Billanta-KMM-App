package com.ferbotz.billanta.share

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.writeToFile
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.popoverPresentationController

/** Writes to the temp dir and presents the system share sheet from the root view controller. */
class IosFileShareService : FileShareService {

    override suspend fun share(bytes: ByteArray, fileName: String, mimeType: String) {
        val path = NSTemporaryDirectory() + fileName
        bytes.toNSData().writeToFile(path, atomically = true)

        withContext(Dispatchers.Main) {
            val url = NSURL.fileURLWithPath(path)
            val controller = UIActivityViewController(
                activityItems = listOf(url),
                applicationActivities = null,
            )
            val root = UIApplication.sharedApplication.keyWindow?.rootViewController
            controller.popoverPresentationController?.sourceView = root?.view
            root?.presentViewController(controller, animated = true, completion = null)
        }
    }
}
