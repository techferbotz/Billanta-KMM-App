package com.ferbotz.billanta.di

import com.ferbotz.billanta.core.UserDefaultsKeyValueStore
import com.ferbotz.billanta.data.api.BillantaApiConfig
import com.ferbotz.billanta.data.db.IosDatabaseDriverFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

fun createAppContainer(
    baseUrl: String,
    enableHttpLogging: Boolean = false,
): AppContainer = AppContainer(
    driverFactory = IosDatabaseDriverFactory(),
    keyValueStore = UserDefaultsKeyValueStore(),
    config = BillantaApiConfig(baseUrl, enableHttpLogging),
    ioDispatcher = Dispatchers.IO,
)
