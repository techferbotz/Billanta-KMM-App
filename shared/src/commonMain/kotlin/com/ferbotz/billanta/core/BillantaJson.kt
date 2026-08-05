package com.ferbotz.billanta.core

import kotlinx.serialization.json.Json

/**
 * One Json config for the whole app. `ignoreUnknownKeys` is load-bearing: the backend is free to
 * add fields (and new template capabilities) without breaking older clients.
 */
val BillantaJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
    isLenient = false
}
