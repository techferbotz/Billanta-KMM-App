package com.ferbotz.billanta

import androidx.compose.ui.window.ComposeUIViewController
import com.ferbotz.billanta.di.AppContainer
import com.ferbotz.billanta.di.createAppContainer

private val container: AppContainer by lazy { createAppContainer(baseUrl = BillantaConfig.BASE_URL) }

// Google Sign-In on iOS needs the GoogleSignIn SDK wired from the Xcode side; until then the
// coordinator reports sign-in as unavailable and the app runs in offline/guest mode.
fun MainViewController() = ComposeUIViewController { App(container) }
