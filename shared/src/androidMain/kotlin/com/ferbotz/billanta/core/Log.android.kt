package com.ferbotz.billanta.core

import android.util.Log

actual fun logWarn(tag: String, message: String) {
    try {
        Log.w("Billanta/$tag", message)
    } catch (_: Throwable) {
        // A diagnostic must never be the reason a code path fails. `android.util.Log` is a stub on
        // the JVM unit-test classpath and throws "not mocked", which — because the sync pass is
        // wrapped in a catch-all — silently aborted the pass and left rows dirty. Logging is
        // observation; it does not get to change what the program does.
        println("W/Billanta/$tag: $message")
    }
}
