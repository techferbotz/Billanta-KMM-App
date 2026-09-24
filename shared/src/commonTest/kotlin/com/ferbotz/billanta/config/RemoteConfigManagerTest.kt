package com.ferbotz.billanta.config

import com.ferbotz.billanta.core.AppError
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.core.InMemoryKeyValueStore
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "Never block launch and never regress": there is always a document, and a failed or garbled
 * fetch can only ever leave the current one in place.
 */
class RemoteConfigManagerTest {

    private fun body(revision: String, cloudSync: Boolean) = """
        { "success": true, "data": { "revision": "$revision", "ttlSeconds": 3600,
          "config": { "features": { "cloudSync": $cloudSync } } } }
    """.trimIndent()

    private fun manager(
        store: InMemoryKeyValueStore,
        answer: () -> AppResult<String>,
    ) = RemoteConfigManager(fetch = { answer() }, store = store, scope = TestScope())

    @Test
    fun a_first_launch_with_nothing_cached_runs_on_the_compiled_in_defaults() {
        val config = manager(InMemoryKeyValueStore()) { AppResult.Failure(AppError.Network()) }
        assertEquals(RemoteConfig(), config.current)
        assertEquals(RemoteConfigDocument.COMPILED_IN, config.document.value.revision)
    }

    /** A kill switch has to hold from the first frame of the next launch, before any network. */
    @Test
    fun the_last_document_is_restored_synchronously_on_the_next_launch() = runTest {
        val store = InMemoryKeyValueStore()
        manager(store) { AppResult.Success(body("r1", cloudSync = false)) }.refresh()

        val nextLaunch = manager(store) { AppResult.Failure(AppError.Network()) }
        assertFalse(nextLaunch.current.features.cloudSync, "restored before any fetch was even attempted")
        assertEquals("r1", nextLaunch.document.value.revision)
    }

    @Test
    fun offline_keeps_what_is_there() = runTest {
        val store = InMemoryKeyValueStore()
        manager(store) { AppResult.Success(body("r1", cloudSync = false)) }.refresh()

        val config = manager(store) { AppResult.Failure(AppError.Network()) }
        assertFalse(config.refresh())
        assertEquals("r1", config.document.value.revision)
    }

    @Test
    fun a_malformed_answer_keeps_what_is_there_and_is_not_cached() = runTest {
        val store = InMemoryKeyValueStore()
        var answer: AppResult<String> = AppResult.Success(body("r1", cloudSync = false))
        val config = manager(store) { answer }
        assertTrue(config.refresh())

        answer = AppResult.Success("<html>502 Bad Gateway</html>")
        assertFalse(config.refresh())
        assertEquals("r1", config.document.value.revision)
        // And the garbage did not replace the cache either: the next launch still gets r1.
        assertEquals("r1", manager(store) { AppResult.Failure(AppError.Network()) }.document.value.revision)
    }

    @Test
    fun a_new_document_replaces_the_old_one() = runTest {
        var answer = AppResult.Success(body("r1", cloudSync = false))
        val config = manager(InMemoryKeyValueStore()) { answer }
        config.refresh()
        answer = AppResult.Success(body("r2", cloudSync = true))
        assertTrue(config.refresh())
        assertEquals("r2", config.document.value.revision)
        assertTrue(config.current.features.cloudSync)
    }
}
