package com.ferbotz.billanta.di

import com.ferbotz.billanta.core.UserDefaultsKeyValueStore
import com.ferbotz.billanta.data.api.BillantaApiConfig
import com.ferbotz.billanta.data.db.IosDatabaseDriverFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSURL
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
)
