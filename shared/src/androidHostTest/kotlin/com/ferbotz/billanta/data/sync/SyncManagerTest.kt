package com.ferbotz.billanta.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.ferbotz.billanta.core.AlwaysOnlineConnectivity
import com.ferbotz.billanta.core.BillantaJson
import com.ferbotz.billanta.core.EpochClock
import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.data.api.BillantaApi
import com.ferbotz.billanta.data.api.CustomerDto
import com.ferbotz.billanta.data.api.Envelope
import com.ferbotz.billanta.data.api.InvoiceDto
import com.ferbotz.billanta.data.api.PageDto
import com.ferbotz.billanta.data.api.ProductDto
import com.ferbotz.billanta.data.api.SettingsDto
import com.ferbotz.billanta.data.api.SyncConflictDto
import com.ferbotz.billanta.data.api.SyncRequestDto
import com.ferbotz.billanta.data.api.SyncResponseDto
import com.ferbotz.billanta.data.db.BillantaDb
import com.ferbotz.billanta.data.local.CustomerLocalDataSource
import com.ferbotz.billanta.data.local.ProductLocalDataSource
import com.ferbotz.billanta.data.local.InvoiceLocalDataSource
import com.ferbotz.billanta.data.local.ProfileLocalDataSource
import com.ferbotz.billanta.data.local.SyncMetaLocal
import com.ferbotz.billanta.domain.model.InvoiceDocStatus
import com.ferbotz.billanta.domain.model.InvoiceItemRecord
import com.ferbotz.billanta.domain.model.InvoiceRecord
import com.ferbotz.billanta.domain.model.UserAccount
import com.ferbotz.billanta.session.AuthState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.content.TextContent
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end sync over an in-memory DB and a scripted mock server: pushes dirty rows, applies
 * server-recomputed totals, persists the cursor, records conflicts, and respects last-write-wins
 * when an edit lands mid-flight.
 */
class SyncManagerTest {

    private val fixedNow = Iso8601.epochMillisFor(2026, 8, 5, 12, 0)
    private val clock = EpochClock { fixedNow }

    /** Settable after the harness exists, so it can close over the local data sources. */
    private class SyncResponder {
        var fn: suspend (SyncRequestDto) -> SyncResponseDto = { SyncResponseDto() }
    }

    private class Harness(
        val invoiceLocal: InvoiceLocalDataSource,
        val customerLocal: CustomerLocalDataSource,
        val productLocal: ProductLocalDataSource,
        val profileLocal: ProfileLocalDataSource,
        val syncMeta: SyncMetaLocal,
        val api: BillantaApi,
        val capturedSyncRequests: MutableList<SyncRequestDto>,
    )

    /** Mock backend: empty customers, default settings, no company, [responder] for /invoices/sync. */
    private fun harness(dispatcher: CoroutineDispatcher, responder: SyncResponder): Harness {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        BillantaDb.Schema.create(driver)
        val db = BillantaDb(driver)

        val captured = mutableListOf<SyncRequestDto>()
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val jsonHeaders = headersOf("Content-Type", "application/json")
            fun ok(body: String) = respond(body, HttpStatusCode.OK, jsonHeaders)
            when {
                path == "/invoices/sync" && request.method == HttpMethod.Post -> {
                    val body = BillantaJson.decodeFromString<SyncRequestDto>((request.body as TextContent).text)
                    captured += body
                    ok(BillantaJson.encodeToString(Envelope(success = true, data = responder.fn(body))))
                }
                path == "/products" && request.method == HttpMethod.Get ->
                    ok(BillantaJson.encodeToString(Envelope(success = true, data = PageDto<ProductDto>(emptyList(), null, false))))
                path == "/customers" && request.method == HttpMethod.Get ->
                    ok(BillantaJson.encodeToString(Envelope(success = true, data = PageDto<CustomerDto>(emptyList(), null, false))))
                path == "/company" && request.method == HttpMethod.Get ->
                    ok("""{"success":true,"data":null}""")
                path == "/settings" && request.method == HttpMethod.Get ->
                    ok(BillantaJson.encodeToString(Envelope(success = true, data = SettingsDto())))
                else -> ok("""{"success":true,"data":{}}""")
            }
        }
        val client = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(BillantaJson) }
            defaultRequest { url("https://test.local/") }
        }
        return Harness(
            invoiceLocal = InvoiceLocalDataSource(db, dispatcher),
            customerLocal = CustomerLocalDataSource(db, dispatcher),
            productLocal = ProductLocalDataSource(db, dispatcher),
            profileLocal = ProfileLocalDataSource(db, dispatcher),
            syncMeta = SyncMetaLocal(db, dispatcher),
            api = BillantaApi(client),
            capturedSyncRequests = captured,
        )
    }

    private fun syncManagerFor(h: Harness, scope: CoroutineScope): SyncManager = SyncManager(
        scope = scope,
        api = h.api,
        authState = MutableStateFlow<AuthState>(AuthState.SignedIn(UserAccount(id = "user-1", email = "a@b.com"))),
        invoiceLocal = h.invoiceLocal,
        customerLocal = h.customerLocal,
        productLocal = h.productLocal,
        profileLocal = h.profileLocal,
        syncMeta = h.syncMeta,
        connectivity = AlwaysOnlineConnectivity,
        clock = clock,
    )

    private fun localInvoice(
        id: String = "inv-1",
        number: String = "INV-1",
        updatedAt: Long = fixedNow,
    ) = InvoiceRecord(
        id = id,
        invoiceNumber = number,
        invoiceDateMillis = Iso8601.epochMillisFor(2026, 8, 1),
        currency = "INR",
        status = InvoiceDocStatus.Pending,
        items = listOf(
            InvoiceItemRecord(
                description = "Widget", quantity = "2", unitPricePaise = 1000,
                taxRatePercent = "18", lineTotalPaise = 2000, taxAmountPaise = 360,
            ),
        ),
        subtotalPaise = 2000, taxTotalPaise = 360, grandTotalPaise = 2360,
        updatedAtMillis = updatedAt,
        pendingSync = true,
    )

    @Test
    fun push_marks_clean_applies_server_totals_and_stores_cursor() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val responder = SyncResponder()
        val h = harness(dispatcher, responder)
        responder.fn = { request ->
            // Server echoes the pushed invoice with its recomputed totals and a new cursor.
            val echoed = request.invoices.map { it.copy(subtotal = "2000", taxTotal = "360", grandTotal = "2360") }
            SyncResponseDto(changed = echoed, conflicts = emptyList(), nextCursor = "cursor-1", hasMore = false)
        }
        h.invoiceLocal.upsert(localInvoice(), dirty = true)

        val result = syncManagerFor(h, backgroundScope).syncNow()

        assertTrue(result.isSuccess, "sync failed: ${result.errorOrNull()}")
        val pushed = h.capturedSyncRequests.first { it.invoices.isNotEmpty() }.invoices.single()
        assertEquals("INV-1", pushed.invoiceNumber)
        assertEquals(Iso8601.format(fixedNow), pushed.updatedAt) // LWW edit time on the wire
        assertEquals("2", pushed.items.single().quantity)

        val local = h.invoiceLocal.getById("inv-1")
        assertNotNull(local)
        assertFalse(local.pendingSync, "accepted push must clear dirty")
        assertEquals(2360, local.grandTotalPaise)
        assertEquals("cursor-1", h.syncMeta.get(SyncMetaLocal.KEY_INVOICE_CURSOR))
    }

    @Test
    fun conflicts_stop_retrying_and_carry_the_reason() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val responder = SyncResponder()
        val h = harness(dispatcher, responder)
        responder.fn = { request ->
            SyncResponseDto(
                conflicts = request.invoices.map { SyncConflictDto(it.id, "Invoice number already used") },
            )
        }
        h.invoiceLocal.upsert(localInvoice(), dirty = true)

        syncManagerFor(h, backgroundScope).syncNow()

        val local = h.invoiceLocal.getById("inv-1")
        assertNotNull(local)
        assertFalse(local.pendingSync, "conflicted row must not retry forever")
        assertEquals("Invoice number already used", local.syncError)
    }

    @Test
    fun mid_flight_edit_survives_and_pull_inserts_new_invoices() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val responder = SyncResponder()
        val h = harness(dispatcher, responder)
        h.invoiceLocal.upsert(localInvoice(id = "inv-2", number = "INV-2-LOCAL"), dirty = true)

        responder.fn = { _ ->
            // The user edits inv-2 while the sync request is in flight…
            h.invoiceLocal.upsert(
                localInvoice(id = "inv-2", number = "INV-2-EDITED", updatedAt = fixedNow + 60_000),
                dirty = true,
            )
            // …and the server answers with an older inv-2 plus a brand-new inv-3 from another device.
            SyncResponseDto(
                changed = listOf(
                    InvoiceDto(
                        id = "inv-2", invoiceNumber = "INV-2-STALE",
                        invoiceDate = Iso8601.format(Iso8601.epochMillisFor(2026, 8, 1)),
                        updatedAt = Iso8601.format(fixedNow - 10_000),
                    ),
                    InvoiceDto(
                        id = "inv-3", invoiceNumber = "INV-3",
                        invoiceDate = Iso8601.format(Iso8601.epochMillisFor(2026, 8, 2)),
                        grandTotal = "5000",
                        updatedAt = Iso8601.format(fixedNow - 5_000),
                    ),
                ),
            )
        }

        syncManagerFor(h, backgroundScope).syncNow()

        val inv2 = h.invoiceLocal.getById("inv-2")
        assertNotNull(inv2)
        // markClean was guarded by updatedAt, and LWW skipped the stale server copy:
        assertEquals("INV-2-EDITED", inv2.invoiceNumber)
        assertTrue(inv2.pendingSync, "the mid-flight edit still needs pushing")
        // The genuinely-new invoice landed:
        assertEquals(5000, h.invoiceLocal.getById("inv-3")?.grandTotalPaise)
        assertNull(h.invoiceLocal.getById("inv-3")?.syncError)
    }
}
