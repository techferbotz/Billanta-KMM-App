package com.ferbotz.billanta.di

import android.content.Context
import com.ferbotz.billanta.core.AndroidConnectivityObserver
import com.ferbotz.billanta.core.SharedPrefsKeyValueStore
import com.ferbotz.billanta.data.api.BillantaApiConfig
import com.ferbotz.billanta.data.db.AndroidDatabaseDriverFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

fun createAppContainer(
    context: Context,
    baseUrl: String,
    enableHttpLogging: Boolean = false,
): AppContainer {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    return AppContainer(
        driverFactory = AndroidDatabaseDriverFactory(context),
        keyValueStore = SharedPrefsKeyValueStore(context),
        config = BillantaApiConfig(baseUrl, enableHttpLogging),
        connectivity = AndroidConnectivityObserver(context, appScope),
        ioDispatcher = Dispatchers.IO,
        appScope = appScope,
    )
}
