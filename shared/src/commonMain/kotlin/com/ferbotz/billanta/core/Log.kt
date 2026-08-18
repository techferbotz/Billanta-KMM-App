package com.ferbotz.billanta.core

/**
 * Diagnostics for things the user is deliberately not shown.
 *
 * Sync runs silently — there is no banner, no badge, nothing on screen when it fails. That is the
 * intended product behaviour, but it would otherwise leave a failure completely invisible, so the
 * detail goes here instead: Logcat on Android, the Xcode console on iOS.
 */
expect fun logWarn(tag: String, message: String)
