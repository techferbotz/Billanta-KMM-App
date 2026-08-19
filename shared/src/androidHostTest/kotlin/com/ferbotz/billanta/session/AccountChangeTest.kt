package com.ferbotz.billanta.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.ferbotz.billanta.core.AppError
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.core.BillantaJson
import com.ferbotz.billanta.core.EpochClock
import com.ferbotz.billanta.core.KeyValueStore
import com.ferbotz.billanta.data.api.AuthApi
import com.ferbotz.billanta.data.api.BillantaApi
import com.ferbotz.billanta.data.db.BillantaDb
import com.ferbotz.billanta.data.local.CustomerLocalDataSource
import com.ferbotz.billanta.data.local.InvoiceLocalDataSource
import com.ferbotz.billanta.data.local.ProductLocalDataSource
import com.ferbotz.billanta.data.local.ProfileLocalDataSource
import com.ferbotz.billanta.data.local.SyncMetaLocal
import com.ferbotz.billanta.domain.model.CustomerRecord
import com.ferbotz.billanta.domain.model.InvoiceRecord
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What happens to a device full of invoices when the account behind it changes.
 *
 * This is the sharpest data-loss edge in the app. BE-010 revealed that a session can outlive its
 * account: writes then fail, sync stops, and the local database becomes the *only* copy of the
 * user's invoices — which is exactly the moment the old "different user id → wipe" rule would have
 * deleted them on the next sign-in.
 */
class AccountChangeTest {

    private class Store : KeyValueStore {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) { values[key] = value }
        override fun getLong(key: String): Long? = values[key]?.toLongOrNull()
        override fun putLong(key: String, value: Long) { values[key] = value.toString() }
        override fun getBoolean(key: String): Boolean? = values[key]?.toBooleanStrictOrNull()
        override fun putBoolean(key: String, value: Boolean) { values[key] = value.toString() }
        override fun remove(key: String) { values.remove(key) }
        override fun clear() = values.clear()
    }

    private class Harness(dispatcher: CoroutineDispatcher) {
        private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val db = BillantaDb(driver).also { BillantaDb.Schema.create(driver) }
        val invoices = InvoiceLocalDataSource(db, dispatcher)
        val customers = CustomerLocalDataSource(db, dispatcher)
        val products = ProductLocalDataSource(db, dispatcher)
        val profile = ProfileLocalDataSource(db, dispatcher)
        val syncMeta = SyncMetaLocal(db, dispatcher)
        val store = Store()
        var wiped = 0
        var reowned = 0

        /** Signs in whoever the mock server says, so a test can change identity between calls. */
        var serverUser: Pair<String, String> = "user-1" to "asha@example.com"

        private val client = HttpClient(
            MockEngine {
                respond(
                    content = """
                        { "success": true, "data": {
                            "accessToken": "access", "refreshToken": "refresh", "expiresIn": 900,
                            "user": { "id": "${serverUser.first}", "email": "${serverUser.second}",
                                      "name": "Asha", "photoUrl": null, "isPremium": false } } }
                    """.trimIndent(),
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    status = HttpStatusCode.OK,
                )
            },
        ) {
            expectSuccess = false
            install(ContentNegotiation) { json(BillantaJson) }
            defaultRequest { url("https://test.local/") }
        }

        val userManager = UserManager(
            authApi = AuthApi(client),
            api = BillantaApi(client),
            tokenManager = TokenManager(TokenStore(store), AuthApi(client), EpochClock { 1_000L }),
            profileLocal = profile,
            keyValueStore = store,
            wipeLocalData = {
                wiped++
                invoices.clearAll(); customers.clearAll(); products.clearAll()
                profile.clearProfile(); syncMeta.clearAll()
            },
            reownLocalData = {
                reowned++
                syncMeta.clearAll()
                invoices.markAllDirty(); customers.markAllDirty(); products.markAllDirty()
                profile.markProfileDirty()
            },
            clock = EpochClock { 1_000L },
        )

        suspend fun seedWork() {
            invoices.upsert(
                InvoiceRecord(id = "inv-1", invoiceNumber = "INV-1", invoiceDateMillis = 0L, updatedAtMillis = 1L),
                dirty = false,
            )
            customers.upsertLocal(CustomerRecord(id = "cust-1", name = "Kavya Iyer", updatedAtMillis = 1L))
            // Pretend the old account had already synced them. Both marks are guarded by
            // updatedAt, so they must match the rows written above or they silently do nothing.
            invoices.markClean("inv-1", 1L)
            customers.markSynced("cust-1", 1L)
            syncMeta.put(SyncMetaLocal.KEY_INVOICE_CURSOR, "cursor-from-the-old-account")
        }
    }

    private fun harness() = Harness(UnconfinedTestDispatcher())

    @Test
    fun signing_back_into_the_same_account_changes_nothing() = runTest {
        val h = harness()
        assertTrue(h.userManager.signInWithGoogle("token") is AppResult.Success)
        h.seedWork()

        assertTrue(h.userManager.signInWithGoogle("token") is AppResult.Success)

        assertEquals(0, h.wiped, "the same account must never wipe")
        assertEquals(0, h.reowned, "the same account does not need re-owning either")
        assertNotNull(h.invoices.getById("inv-1"))
    }

    /**
     * The BE-010 recovery. The account was deleted server-side, so signing in again mints a new id
     * for the same person — and their invoices, which never synced, exist only on this device.
     */
    @Test
    fun the_same_person_with_a_new_server_id_keeps_their_work() = runTest {
        val h = harness()
        assertTrue(h.userManager.signInWithGoogle("token") is AppResult.Success)
        h.seedWork()

        h.serverUser = "user-2" to "asha@example.com" // same human, fresh account
        assertTrue(h.userManager.signInWithGoogle("token") is AppResult.Success)

        assertEquals(0, h.wiped, "this would have destroyed the only copy of their invoices")
        assertEquals(1, h.reowned)
        assertNotNull(h.invoices.getById("inv-1"), "the invoice was deleted")
        assertNotNull(h.customers.getById("cust-1"), "the customer was deleted")
    }

    /** Email match is case-insensitive — Google is not consistent about it, and people are not. */
    @Test
    fun the_email_match_ignores_case() = runTest {
        val h = harness()
        assertTrue(h.userManager.signInWithGoogle("token") is AppResult.Success)
        h.seedWork()

        h.serverUser = "user-2" to "Asha@Example.com"
        assertTrue(h.userManager.signInWithGoogle("token") is AppResult.Success)

        assertEquals(0, h.wiped, "the same address in different case is the same person")
        assertEquals(1, h.reowned)
    }

    /** The guard that must survive: one person's invoices must never appear in another's account. */
    @Test
    fun a_genuinely_different_account_still_wipes() = runTest {
        val h = harness()
        assertTrue(h.userManager.signInWithGoogle("token") is AppResult.Success)
        h.seedWork()

        h.serverUser = "user-2" to "ravi@example.com"
        assertTrue(h.userManager.signInWithGoogle("token") is AppResult.Success)

        assertEquals(1, h.wiped, "a different person must not inherit the previous one's data")
        assertEquals(0, h.reowned)
        assertEquals(null, h.invoices.getById("inv-1"))
    }

    /**
     * Re-owning has to make the data pushable again. Rows the old account had synced are still
     * marked synced against ids the new account has never seen — and the pull's "deleted elsewhere"
     * reconcile would then delete them locally, which is the same loss by a slower route.
     */
    @Test
    fun re_owned_rows_are_queued_to_push_rather_than_left_looking_synced() = runTest {
        val h = harness()
        assertTrue(h.userManager.signInWithGoogle("token") is AppResult.Success)
        h.seedWork()
        // Precondition: they really do look synced-and-clean to the reconcile.
        assertTrue("cust-1" in h.customers.syncedCleanIds(), "test setup did not mark it synced")

        h.serverUser = "user-2" to "asha@example.com"
        assertTrue(h.userManager.signInWithGoogle("token") is AppResult.Success)

        assertTrue(
            h.customers.syncedCleanIds().isEmpty(),
            "a re-owned customer still looks synced, so the pull would delete it",
        )
        assertEquals(listOf("inv-1"), h.invoices.dirtyRecords().map { it.id }, "the invoice must re-push")
        assertEquals(listOf("cust-1"), h.customers.dirtyRows().map { it.id }, "the customer must re-push")
        assertEquals(
            null,
            h.syncMeta.get(SyncMetaLocal.KEY_INVOICE_CURSOR),
            "the old account's cursor would skip this account's history",
        )
    }

    @Test
    fun a_vanished_account_ends_the_session_without_touching_local_data() = runTest {
        val h = harness()
        assertTrue(h.userManager.signInWithGoogle("token") is AppResult.Success)
        h.seedWork()

        h.userManager.onAccountVanished()

        assertEquals(AuthState.SignedOut, h.userManager.authState.value)
        assertFalse(h.userManager.isSignedIn)
        assertEquals(0, h.wiped, "signing out must never delete anything")
        assertNotNull(h.invoices.getById("inv-1"))
    }

    @Test
    fun the_server_error_that_means_the_account_is_gone_is_recognised() {
        assertTrue(AppError.Http(401, "ACCOUNT_NOT_FOUND", "gone").isAccountGone)
        // Everything else is an ordinary 401 that a refresh may still fix.
        assertFalse(AppError.Http(401, "REFRESH_TOKEN_REUSED", "reused").isAccountGone)
        assertFalse(AppError.Http(401, null, null).isAccountGone)
        assertFalse(AppError.Http(500, "ACCOUNT_NOT_FOUND", null).isAccountGone)
    }
}
