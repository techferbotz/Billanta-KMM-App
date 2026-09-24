package com.ferbotz.billanta.di

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.pm.PackageInfoCompat
import com.ferbotz.billanta.config.AppIdentity
import com.ferbotz.billanta.core.AndroidConnectivityObserver
import com.ferbotz.billanta.core.SharedPrefsKeyValueStore
import com.ferbotz.billanta.data.api.BillantaApiConfig
import com.ferbotz.billanta.data.db.AndroidDatabaseDriverFactory
import com.ferbotz.billanta.share.AndroidFileShareService
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
                val uri = Uri.parse(url)
                // SENDTO is the intent only mail apps answer, so "Contact support" never offers a
                // browser or a notes app.
                val action = if (uri.scheme == "mailto") Intent.ACTION_SENDTO else Intent.ACTION_VIEW
                appContext.startActivity(Intent(action, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        },
        openInAppBrowser = { url ->
            runCatching {
                val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
                // Launched from the application context, so it needs a task of its own; back from
                // the tab still returns to Billanta. Without a Custom Tabs browser this is a plain
                // VIEW intent, so the page opens in whatever browser there is.
                tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                tab.launchUrl(appContext, Uri.parse(url))
            }
        },
        shareService = AndroidFileShareService(appContext),
        platformIdentity = androidIdentity(appContext),
    )
}

/** Version and build as the store knows them: versionName, and versionCode as the integer build. */
private fun androidIdentity(context: Context): AppIdentity {
    val info = runCatching {
        @Suppress("DEPRECATION") // the flags overload is API 33+; this one is fine for a lookup of ourselves
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()
    return AppIdentity(
        platform = AppIdentity.ANDROID,
        version = info?.versionName ?: "unknown",
        build = info?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L,
    )
}
