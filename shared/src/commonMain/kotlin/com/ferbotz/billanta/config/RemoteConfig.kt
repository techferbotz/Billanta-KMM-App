package com.ferbotz.billanta.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The behaviour switches the backend hands out from `GET /config` (BE-014, House of Apps
 * protocol 11): kill switches, update policy, maintenance, the caps the server enforces, and the
 * legal links (BE-015 / BE-017).
 *
 * Every default here is deliberately the value production serves today, so adopting remote config
 * changes nothing until someone flips a value on the server — and a first launch that has never
 * reached `/config` still behaves exactly like one that has.
 */
data class RemoteConfig(
    val features: Features = Features(),
    val update: UpdatePolicy = UpdatePolicy(),
    val maintenance: Maintenance = Maintenance(),
    val limits: Limits = Limits(),
    val links: Links = Links(),
) {
    /** UI entry points only: the data layer and the routes keep working whatever these say. */
    data class Features(
        val googleLogin: Boolean = true,
        /** The one switch that reaches below the UI: off stops syncing app-wide. */
        val cloudSync: Boolean = true,
        val templateStore: Boolean = true,
        val premiumTemplates: Boolean = true,
        val productCatalogue: Boolean = true,
        val mediaUpload: Boolean = true,
    )

    /** Compared against the integer build number, never a version string. 0 means no requirement. */
    data class UpdatePolicy(
        val minSupportedBuild: Long = 0,
        val recommendedBuild: Long = 0,
        /** Null until the store listing exists — then there is a message but no button. */
        val androidStoreUrl: String? = null,
        val iosStoreUrl: String? = null,
        val message: String? = null,
    )

    data class Maintenance(val enabled: Boolean = false, val message: String? = null)

    /** Mirrors of what the server already enforces, checked so a doomed request is never sent. */
    data class Limits(
        val maxMediaBytes: Long = 8_388_608,
        val maxSyncBatchInvoices: Int = 500,
        val maxPageSize: Int = 100,
        /** The largest one record's `values` map may be, as JSON bytes (BE-021). */
        val maxValuesBytes: Int = 65_536,
        /** The largest one invoice line's `values` may be, as JSON bytes. */
        val maxLineValuesBytes: Int = 8_192,
        val maxLineItems: Int = 500,
    )

    /** Absolute URLs, so the legal pages can move without an app release. */
    data class Links(
        val privacyPolicy: String = "https://billanta.ferbotz.com/privacy",
        val terms: String = "https://billanta.ferbotz.com/terms",
        val deleteAccount: String = "https://billanta.ferbotz.com/delete-account",
        val supportEmail: String = "support@ferbotz.com",
    )

    /**
     * What the app may offer right now.
     *
     * Maintenance means "show the message instead of the cloud features", so it switches off every
     * feature that needs the backend. The product catalogue is local data and stays; invoicing on
     * this phone carries on regardless — that is the point of being offline-first.
     */
    val effectiveFeatures: Features
        get() = if (!maintenance.enabled) features else features.copy(
            googleLogin = false,
            cloudSync = false,
            templateStore = false,
            premiumTemplates = false,
            mediaUpload = false,
        )
}

/** One `/config` answer: the config, and what the server says about it. */
data class RemoteConfigDocument(
    val schemaVersion: Int = 1,
    /** Which targeting rules applied — `"default"` when none did. */
    val variant: String = "default",
    /** A fingerprint of `config`; changes whenever any value does. */
    val revision: String = COMPILED_IN,
    val ttlSeconds: Long = DEFAULT_TTL_SECONDS,
    val config: RemoteConfig = RemoteConfig(),
) {
    companion object {
        /** The revision of a document nobody fetched: the defaults compiled into this build. */
        const val COMPILED_IN = "compiled-in"
        const val DEFAULT_TTL_SECONDS = 3_600L
    }
}

/**
 * Reads a `GET /config` response body — the whole envelope — leaf by leaf.
 *
 * A leaf that is present and of the right type is taken; anything else keeps its compiled-in
 * default, one leaf at a time. That is the protocol's rule and it has two consequences:
 * `{"features":{"cloudSync":false}}` flips exactly that one switch, and a key the server adds
 * tomorrow is simply never read.
 *
 * It is deliberately more forgiving than decoding into data classes. There, one garbled value
 * rejects the entire document — which would stop a kill switch sent in the same document from
 * ever applying, exactly when it is needed.
 *
 * Returns null only when the body is not a successful envelope at all; the caller then keeps the
 * document it already has.
 */
fun parseRemoteConfig(body: String): RemoteConfigDocument? {
    val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
    if ((root["success"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull != true) return null
    val data = root["data"] as? JsonObject ?: return null

    val base = RemoteConfigDocument()
    val defaults = base.config
    val config = data.obj("config")

    val features = config.obj("features")
    val update = config.obj("update")
    val maintenance = config.obj("maintenance")
    val limits = config.obj("limits")
    val links = config.obj("links")

    return RemoteConfigDocument(
        schemaVersion = data.int("schemaVersion", base.schemaVersion),
        variant = data.string("variant", base.variant),
        revision = data.string("revision", base.revision),
        ttlSeconds = data.long("ttlSeconds", base.ttlSeconds).takeIf { it > 0 } ?: base.ttlSeconds,
        config = RemoteConfig(
            features = defaults.features.let { d ->
                RemoteConfig.Features(
                    googleLogin = features.bool("googleLogin", d.googleLogin),
                    cloudSync = features.bool("cloudSync", d.cloudSync),
                    templateStore = features.bool("templateStore", d.templateStore),
                    premiumTemplates = features.bool("premiumTemplates", d.premiumTemplates),
                    productCatalogue = features.bool("productCatalogue", d.productCatalogue),
                    mediaUpload = features.bool("mediaUpload", d.mediaUpload),
                )
            },
            update = defaults.update.let { d ->
                RemoteConfig.UpdatePolicy(
                    minSupportedBuild = update.long("minSupportedBuild", d.minSupportedBuild).coerceAtLeast(0),
                    recommendedBuild = update.long("recommendedBuild", d.recommendedBuild).coerceAtLeast(0),
                    androidStoreUrl = update.nullableString("androidStoreUrl", d.androidStoreUrl),
                    iosStoreUrl = update.nullableString("iosStoreUrl", d.iosStoreUrl),
                    message = update.nullableString("message", d.message),
                )
            },
            maintenance = defaults.maintenance.let { d ->
                RemoteConfig.Maintenance(
                    enabled = maintenance.bool("enabled", d.enabled),
                    message = maintenance.nullableString("message", d.message),
                )
            },
            // A cap of zero or less would make every request it governs invalid, so it cannot be
            // what anyone meant: treat it as garbled and keep the default.
            limits = defaults.limits.let { d ->
                RemoteConfig.Limits(
                    maxMediaBytes = limits.long("maxMediaBytes", d.maxMediaBytes).takeIf { it > 0 } ?: d.maxMediaBytes,
                    maxSyncBatchInvoices = limits.int("maxSyncBatchInvoices", d.maxSyncBatchInvoices)
                        .takeIf { it > 0 } ?: d.maxSyncBatchInvoices,
                    maxPageSize = limits.int("maxPageSize", d.maxPageSize).takeIf { it > 0 } ?: d.maxPageSize,
                    maxValuesBytes = limits.int("maxValuesBytes", d.maxValuesBytes).takeIf { it > 0 } ?: d.maxValuesBytes,
                    maxLineValuesBytes = limits.int("maxLineValuesBytes", d.maxLineValuesBytes)
                        .takeIf { it > 0 } ?: d.maxLineValuesBytes,
                    maxLineItems = limits.int("maxLineItems", d.maxLineItems).takeIf { it > 0 } ?: d.maxLineItems,
                )
            },
            // Links are opened as given, so only an absolute web URL (or an address) is accepted.
            links = defaults.links.let { d ->
                RemoteConfig.Links(
                    privacyPolicy = links.string("privacyPolicy", d.privacyPolicy).takeIf(::isWebUrl) ?: d.privacyPolicy,
                    terms = links.string("terms", d.terms).takeIf(::isWebUrl) ?: d.terms,
                    deleteAccount = links.string("deleteAccount", d.deleteAccount).takeIf(::isWebUrl) ?: d.deleteAccount,
                    supportEmail = links.string("supportEmail", d.supportEmail).takeIf(::isEmailAddress) ?: d.supportEmail,
                )
            },
        ),
    )
}

private fun isWebUrl(value: String) = value.startsWith("https://") || value.startsWith("http://")

private fun isEmailAddress(value: String) =
    value.count { it == '@' } == 1 && !value.startsWith("@") && !value.endsWith("@") && value.none(Char::isWhitespace)

private fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject

/** A non-string primitive, so `"true"` in quotes is a wrong type rather than a yes. */
private fun JsonObject?.literal(key: String): JsonPrimitive? =
    (this?.get(key) as? JsonPrimitive)?.takeUnless { it.isString || it is JsonNull }

private fun JsonObject?.bool(key: String, default: Boolean): Boolean = literal(key)?.booleanOrNull ?: default

private fun JsonObject?.long(key: String, default: Long): Long = literal(key)?.longOrNull ?: default

private fun JsonObject?.int(key: String, default: Int): Int = literal(key)?.intOrNull ?: default

private fun JsonObject?.string(key: String, default: String): String =
    (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: default

/** Absent keeps the default; an explicit `null` is a value in its own right ("no message"). */
private fun JsonObject?.nullableString(key: String, default: String?): String? {
    val element = this?.get(key) ?: return default
    if (element is JsonNull) return null
    return (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: default
}
