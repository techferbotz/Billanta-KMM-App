package com.ferbotz.billanta

import android.app.Application
import com.ferbotz.billanta.di.AppContainer
import com.ferbotz.billanta.di.createAppContainer

class BillantaApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = createAppContainer(
            context = this,
            baseUrl = BASE_URL,
            enableHttpLogging = BuildConfig.DEBUG,
        )
    }

    companion object {
        // TODO: point at the real deployment origin (see DEPLOY.md) before release.
        const val BASE_URL = "https://billanta.ferbotz.com"
    }
}

val Application.appContainer: AppContainer
    get() = (this as BillantaApplication).container
