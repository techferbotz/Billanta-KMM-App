package com.ferbotz.billanta.core

actual fun logWarn(tag: String, message: String) {
    println("W/Billanta/$tag: $message")
}
