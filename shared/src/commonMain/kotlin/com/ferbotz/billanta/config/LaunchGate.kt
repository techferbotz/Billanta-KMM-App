package com.ferbotz.billanta.config

/**
 * Who is calling: the targeting inputs `GET /config` reads (BE-014).
 *
 * Sent as headers on every request to the API — not only to `/config` — because a build that
 * never sent them can never be targeted later.
 */
data class AppIdentity(
    /** `"android"` or `"ios"`. */
    val platform: String,
    /** The display version, e.g. `1.4.2`. Informational only. */
    val version: String,
    /** The integer build number (versionCode / CFBundleVersion); 0 when it could not be read. */
    val build: Long,
    /** A stable per-install id: the experiment-bucketing key, and read by nothing else. */
    val deviceId: String = "",
) {
    companion object {
        const val ANDROID = "android"
        const val IOS = "ios"

        const val HEADER_PLATFORM = "X-App-Platform"
        const val HEADER_VERSION = "X-App-Version"
        const val HEADER_BUILD = "X-App-Build"
        const val HEADER_DEVICE_ID = "X-Device-Id"

        /** For tests and previews: nothing is known, so nothing can ever be blocked. */
        val Unknown = AppIdentity(platform = "unknown", version = "0", build = 0)
    }
}

/**
 * The one gate at the root, evaluated in the protocol's order: maintenance, then a forced update,
 * then a recommended one. The first that applies is the only one shown.
 */
sealed interface LaunchGate {
    data object Open : LaunchGate

    /** Invoicing carries on; the cloud features give way to [message]. */
    data class Maintenance(val message: String?) : LaunchGate

    /** This build may not be used. [storeUrl] is null until there is a listing to send people to. */
    data class UpdateRequired(val message: String?, val storeUrl: String?) : LaunchGate

    /** A newer build is recommended; the user may dismiss this. */
    data class UpdateRecommended(val message: String?, val storeUrl: String?) : LaunchGate
}

/**
 * Where this build stands against [config].
 *
 * Builds are compared as integers — `"1.10.0" < "1.9.0"` as strings. And a build of 0 is never
 * blocked: it means this install could not read its own build number, and refusing to run over
 * that would be the app's fault, not the user's.
 */
fun launchGate(config: RemoteConfig, identity: AppIdentity): LaunchGate {
    val update = config.update
    val build = identity.build
    val storeUrl = when (identity.platform) {
        AppIdentity.ANDROID -> update.androidStoreUrl
        AppIdentity.IOS -> update.iosStoreUrl
        else -> null
    }
    return when {
        config.maintenance.enabled -> LaunchGate.Maintenance(config.maintenance.message)
        build > 0 && update.minSupportedBuild > 0 && build < update.minSupportedBuild ->
            LaunchGate.UpdateRequired(update.message, storeUrl)
        build > 0 && update.recommendedBuild > 0 && build < update.recommendedBuild ->
            LaunchGate.UpdateRecommended(update.message, storeUrl)
        else -> LaunchGate.Open
    }
}
