package com.ferbotz.billanta.di

import com.ferbotz.billanta.core.UserDefaultsKeyValueStore
import com.ferbotz.billanta.data.api.BillantaApiConfig
import com.ferbotz.billanta.data.db.IosDatabaseDriverFactory
import com.ferbotz.billanta.share.IosFileShareService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import com.ferbotz.billanta.config.AppIdentity
import platform.Foundation.NSBundle
import platform.Foundation.NSURL
import platform.SafariServices.SFSafariViewController
import platform.UIKit.UIApplication

fun createAppContainer(
    baseUrl: String,
    enableHttpLogging: Boolean = false,
): AppContainer = AppContainer(
    driverFactory = IosDatabaseDriverFactory(),
    keyValueStore = UserDefaultsKeyValueStore(),
    config = BillantaApiConfig(baseUrl, enableHttpLogging),
    ioDispatcher = Dispatchers.IO,
    openUrl = { url ->
        NSURL.URLWithString(url)?.let {
            UIApplication.sharedApplication.openURL(it, options = emptyMap<Any?, Any>(), completionHandler = null)
        }
    },
    openInAppBrowser = { url ->
        NSURL.URLWithString(url)?.let { page ->
            // Present over whatever is on top, so a page opened from a sheet appears above it.
            var top = UIApplication.sharedApplication.keyWindow?.rootViewController
            while (top?.presentedViewController != null) top = top.presentedViewController
            if (top != null) {
                top.presentViewController(SFSafariViewController(uRL = page), animated = true, completion = null)
            } else {
                UIApplication.sharedApplication.openURL(page, options = emptyMap<Any?, Any>(), completionHandler = null)
            }
        }
    },
    shareService = IosFileShareService(),
    platformIdentity = iosIdentity(),
)

/** CFBundleShortVersionString is the display version; CFBundleVersion is the integer build. */
private fun iosIdentity(): AppIdentity {
    val bundle = NSBundle.mainBundle
    return AppIdentity(
        platform = AppIdentity.IOS,
        version = bundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "unknown",
        // A dotted build ("1.0.3") is not an integer: reported as unknown, which is never blocked.
        build = (bundle.objectForInfoDictionaryKey("CFBundleVersion") as? String)?.toLongOrNull() ?: 0L,
    )
}
