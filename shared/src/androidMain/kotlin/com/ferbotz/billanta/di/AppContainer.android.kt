package com.ferbotz.billanta.di

import android.content.Context
import android.content.Intent
import android.net.Uri
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
    val appContext = context.applicationContext
    return AppContainer(
        driverFactory = AndroidDatabaseDriverFactory(appContext),
        keyValueStore = SharedPrefsKeyValueStore(appContext),
        config = BillantaApiConfig(baseUrl, enableHttpLogging),
        connectivity = AndroidConnectivityObserver(appContext, appScope),
        ioDispatcher = Dispatchers.IO,
        appScope = appScope,
        openUrl = { url ->
            runCatching {
                appContext.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        },
    )
}
