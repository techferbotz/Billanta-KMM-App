package com.ferbotz.billanta

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform