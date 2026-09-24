package com.ferbotz.billanta.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The root gate: maintenance, then a forced update, then a recommended one (BE-014). */
class LaunchGateTest {

    private val android = AppIdentity(platform = AppIdentity.ANDROID, version = "1.0", build = 7)

    private fun config(
        min: Long = 0,
        recommended: Long = 0,
        maintenance: Boolean = false,
    ) = RemoteConfig(
        update = RemoteConfig.UpdatePolicy(
            minSupportedBuild = min,
            recommendedBuild = recommended,
            androidStoreUrl = "https://play.google.com/store/apps/details?id=com.ferbotz.billanta",
            iosStoreUrl = "https://apps.apple.com/app/id1",
            message = "Please update",
        ),
        maintenance = RemoteConfig.Maintenance(enabled = maintenance, message = "Back soon"),
    )

    @Test
    fun zero_on_the_server_means_no_requirement() {
        assertEquals(LaunchGate.Open, launchGate(config(), android))
    }

    @Test
    fun a_build_below_the_minimum_is_blocked_with_its_own_platforms_store() {
        val gate = assertIs<LaunchGate.UpdateRequired>(launchGate(config(min = 8), android))
        assertTrue(gate.storeUrl!!.contains("play.google.com"))
        val ios = assertIs<LaunchGate.UpdateRequired>(launchGate(config(min = 8), android.copy(platform = AppIdentity.IOS)))
        assertTrue(ios.storeUrl!!.contains("apps.apple.com"))
    }

    @Test
    fun the_minimum_itself_is_supported() {
        assertEquals(LaunchGate.Open, launchGate(config(min = 7), android))
    }

    @Test
    fun a_build_below_the_recommendation_is_nudged_not_blocked() {
        assertIs<LaunchGate.UpdateRecommended>(launchGate(config(recommended = 9), android))
    }

    /** A forced update outranks a recommended one: both apply, only the stronger is shown. */
    @Test
    fun a_forced_update_wins_over_a_recommended_one() {
        assertIs<LaunchGate.UpdateRequired>(launchGate(config(min = 8, recommended = 9), android))
    }

    @Test
    fun maintenance_comes_first() {
        assertIs<LaunchGate.Maintenance>(launchGate(config(min = 8, maintenance = true), android))
    }

    /** Refusing to run because the app could not read its own build would be the app's fault. */
    @Test
    fun an_unknown_build_is_never_blocked_or_nagged() {
        assertEquals(LaunchGate.Open, launchGate(config(min = 99, recommended = 99), android.copy(build = 0)))
    }

    @Test
    fun maintenance_switches_off_exactly_the_features_that_need_the_backend() {
        val down = config(maintenance = true).effectiveFeatures
        assertFalse(down.googleLogin)
        assertFalse(down.cloudSync)
        assertFalse(down.templateStore)
        assertFalse(down.premiumTemplates)
        assertFalse(down.mediaUpload)
        // The catalogue is local data; invoicing carries on during an outage.
        assertTrue(down.productCatalogue)
        assertEquals(RemoteConfig.Features(), config().effectiveFeatures)
    }
}
