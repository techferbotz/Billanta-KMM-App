package com.ferbotz.billanta.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `GET /config` read leaf by leaf (BE-014).
 *
 * The rules under test are the protocol's: unknown keys are ignored, a missing leaf keeps its
 * compiled-in default *on its own* — never its whole section — and a garbled answer must never make
 * the app worse off than the document it already had.
 */
class RemoteConfigParseTest {

    /** Production's answer on 2026-09-24, verbatim (BE-015 changed only `supportEmail`). */
    private val live = """
        { "success": true, "data": {
          "schemaVersion": 1,
          "variant": "default",
          "revision": "23e0a1c074d1",
          "ttlSeconds": 3600,
          "config": {
            "features": { "googleLogin": true, "cloudSync": true, "templateStore": true,
                          "premiumTemplates": true, "productCatalogue": true, "mediaUpload": true },
            "update": { "minSupportedBuild": 0, "recommendedBuild": 0, "androidStoreUrl": null,
                        "iosStoreUrl": null, "message": null },
            "maintenance": { "enabled": false, "message": null },
            "limits": { "maxMediaBytes": 8388608, "maxSyncBatchInvoices": 500, "maxPageSize": 100 },
            "links": { "privacyPolicy": "https://billanta.ferbotz.com/privacy",
                       "terms": "https://billanta.ferbotz.com/terms",
                       "deleteAccount": "https://billanta.ferbotz.com/delete-account",
                       "supportEmail": "support@ferbotz.com" }
          }
        } }
    """.trimIndent()

    private fun envelope(config: String) = """{ "success": true, "data": { "config": $config } }"""

    @Test
    fun the_live_document_reads_back_exactly() {
        val doc = assertNotNull(parseRemoteConfig(live))
        assertEquals(1, doc.schemaVersion)
        assertEquals("default", doc.variant)
        assertEquals("23e0a1c074d1", doc.revision)
        assertEquals(3600, doc.ttlSeconds)
        assertEquals("support@ferbotz.com", doc.config.links.supportEmail)
        assertEquals(8_388_608, doc.config.limits.maxMediaBytes)
    }

    /**
     * The protocol's own requirement: adopting remote config must change nothing until a value is
     * deliberately flipped. So the compiled-in defaults have to *be* production's document — and a
     * first launch that never reaches /config then behaves exactly like one that did.
     */
    @Test
    fun the_compiled_in_defaults_are_the_live_document() {
        assertEquals(RemoteConfig(), assertNotNull(parseRemoteConfig(live)).config)
    }

    @Test
    fun a_partial_document_flips_exactly_the_leaf_it_names() {
        val doc = assertNotNull(parseRemoteConfig(envelope("""{ "features": { "cloudSync": false } }""")))
        assertFalse(doc.config.features.cloudSync)
        // Every other feature in the *same section* keeps its default: defaulting the section
        // wholesale would have wiped them the moment the server sent a partial one.
        assertEquals(RemoteConfig.Features(cloudSync = false), doc.config.features)
        assertEquals(RemoteConfig().links, doc.config.links)
        assertEquals(RemoteConfigDocument.COMPILED_IN, doc.revision)
    }

    @Test
    fun unknown_keys_are_ignored_at_every_level() {
        val doc = assertNotNull(
            parseRemoteConfig(
                """{ "success": true, "brandNew": 1, "data": { "someday": [1, 2], "config": {
                     "features": { "cloudSync": false, "voiceInvoices": true },
                     "experiments": { "anything": "at all" } } } }""",
            ),
        )
        assertFalse(doc.config.features.cloudSync)
    }

    /** One bad value must not stop a kill switch in the same document from applying. */
    @Test
    fun a_garbled_leaf_defaults_on_its_own_and_the_rest_still_apply() {
        val doc = assertNotNull(
            parseRemoteConfig(
                envelope(
                    """{ "features": { "cloudSync": false, "googleLogin": "nope" },
                         "limits": { "maxPageSize": "100", "maxSyncBatchInvoices": 250 } }""",
                ),
            ),
        )
        assertFalse(doc.config.features.cloudSync)
        assertTrue(doc.config.features.googleLogin, "a string is not a boolean: keep the default")
        assertEquals(100, doc.config.limits.maxPageSize, "a quoted number is not a number")
        assertEquals(250, doc.config.limits.maxSyncBatchInvoices)
    }

    @Test
    fun a_quoted_boolean_is_a_wrong_type_not_a_value() {
        val doc = assertNotNull(parseRemoteConfig(envelope("""{ "features": { "cloudSync": "false" } }""")))
        assertTrue(doc.config.features.cloudSync)
    }

    @Test
    fun anything_but_a_successful_envelope_is_rejected_whole() {
        assertNull(parseRemoteConfig("not json"))
        assertNull(parseRemoteConfig("[]"))
        assertNull(parseRemoteConfig("""{ "success": false, "message": "down" }"""))
        assertNull(parseRemoteConfig("""{ "success": true }"""))
        assertNull(parseRemoteConfig("""{ "success": "true", "data": {} }"""))
    }

    @Test
    fun an_explicit_null_message_is_no_message_and_an_absent_one_is_the_default() {
        val withMessage = assertNotNull(
            parseRemoteConfig(envelope("""{ "maintenance": { "enabled": true, "message": "Back at 6" } }""")),
        )
        assertEquals("Back at 6", withMessage.config.maintenance.message)
        val explicitNull = assertNotNull(
            parseRemoteConfig(envelope("""{ "maintenance": { "enabled": true, "message": null } }""")),
        )
        assertNull(explicitNull.config.maintenance.message)
        assertTrue(explicitNull.config.maintenance.enabled)
    }

    /** Links are opened as given, so a value that is not an absolute address cannot be used. */
    @Test
    fun a_link_that_is_not_absolute_keeps_the_default() {
        val doc = assertNotNull(
            parseRemoteConfig(
                envelope(
                    """{ "links": { "terms": "/terms", "privacyPolicy": "https://example.org/p",
                                    "supportEmail": "not an address" } }""",
                ),
            ),
        )
        assertEquals("https://billanta.ferbotz.com/terms", doc.config.links.terms)
        assertEquals("https://example.org/p", doc.config.links.privacyPolicy)
        assertEquals("support@ferbotz.com", doc.config.links.supportEmail)
    }

    /** A zero cap or TTL would break every request it governs, so it cannot be what was meant. */
    @Test
    fun caps_and_ttl_that_could_not_be_meant_keep_their_defaults() {
        val doc = assertNotNull(
            parseRemoteConfig(
                """{ "success": true, "data": { "ttlSeconds": 0, "config": {
                     "limits": { "maxPageSize": 0, "maxSyncBatchInvoices": -5, "maxMediaBytes": 0 } } } }""",
            ),
        )
        assertEquals(RemoteConfigDocument.DEFAULT_TTL_SECONDS, doc.ttlSeconds)
        assertEquals(RemoteConfig.Limits(), doc.config.limits)
    }
}
