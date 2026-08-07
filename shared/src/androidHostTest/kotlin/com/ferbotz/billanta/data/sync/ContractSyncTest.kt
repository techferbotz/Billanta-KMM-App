package com.ferbotz.billanta.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.ferbotz.billanta.core.AlwaysOnlineConnectivity
import com.ferbotz.billanta.core.BillantaJson
import com.ferbotz.billanta.core.EpochClock
import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.data.api.BillantaApi
import com.ferbotz.billanta.data.api.CustomerDto
import com.ferbotz.billanta.data.api.Envelope
import com.ferbotz.billanta.data.api.PageDto
import com.ferbotz.billanta.data.api.ProductDto
import com.ferbotz.billanta.data.api.SettingsDto
import com.ferbotz.billanta.data.api.SyncResponseDto
import com.ferbotz.billanta.data.api.TemplateDto
import com.ferbotz.billanta.data.db.BillantaDb
import com.ferbotz.billanta.data.local.CustomerLocalDataSource
import com.ferbotz.billanta.data.local.InvoiceLocalDataSource
import com.ferbotz.billanta.data.local.ProductLocalDataSource
import com.ferbotz.billanta.data.local.ProfileLocalDataSource
import com.ferbotz.billanta.data.local.SyncMetaLocal
import com.ferbotz.billanta.data.local.TemplateLocalDataSource
import com.ferbotz.billanta.data.repo.TemplateRepository
import com.ferbotz.billanta.domain.model.InvoiceRecord
import com.ferbotz.billanta.domain.model.ProductRecord
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the behaviours the backend changelog asked for, at the level the wire actually sees them:
 * BE-001 (client delete time on a tombstone), BE-002 (products replayed per row) and BE-006
 * (paginated template catalogue).
 */
class ContractSyncTest {

    private val fixedNow = Iso8601.epochMillisFor(2026, 8, 6, 12, 0)
    private val clock = EpochClock { fixedNow }

    private class Captured(
        val method: HttpMethod,
        val path: String,
        val query: String,
        val body: String?,
    )

    private class Harness(
        val db: BillantaDb,
        val api: BillantaApi,
        val requests: MutableList<Captured>,
        val dispatcher: CoroutineDispatcher,
    ) {
        val invoiceLocal get() = InvoiceLocalDataSource(db, dispatcher)
        val customerLocal get() = CustomerLocalDataSource(db, dispatcher)
        val productLocal get() = ProductLocalDataSource(db, dispatcher)
        val profileLocal get() = ProfileLocalDataSource(db, dispatcher)
        val templateLocal get() = TemplateLocalDataSource(db, dispatcher)
        val syncMeta get() = SyncMetaLocal(db, dispatcher)
    }

    /** [products] and [templatePages] let a test script what the server returns. */
    private fun harness(
        dispatcher: CoroutineDispatcher,
        products: List<ProductDto> = emptyList(),
        templatePages: List<PageDto<TemplateDto>> = listOf(PageDto(emptyList(), null, false)),
    ): Harness {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        BillantaDb.Schema.create(driver)
        val db = BillantaDb(driver)
        val captured = mutableListOf<Captured>()
        var templateCall = 0
        // A POSTed product must come back on the next GET, as a real server would — otherwise the
        // pull looks like the row was deleted elsewhere.
        val serverProducts = products.toMutableList()

        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            captured += Captured(
                request.method,
                path,
                request.url.encodedQuery,
                (request.body as? TextContent)?.text,
            )
            val jsonHeaders = headersOf("Content-Type", "application/json")
            fun ok(body: String) = respond(body, HttpStatusCode.OK, jsonHeaders)
            when {
                path == "/templates" && request.method == HttpMethod.Get -> {
                    val page = templatePages.getOrElse(templateCall++) { PageDto(emptyList(), null, false) }
                    ok(BillantaJson.encodeToString(Envelope(success = true, data = page)))
                }
                path == "/products" && request.method == HttpMethod.Post -> {
                    val posted = BillantaJson.decodeFromString<ProductDto>((request.body as TextContent).text)
                    serverProducts.removeAll { it.id == posted.id }
                    serverProducts += posted
                    ok(BillantaJson.encodeToString(Envelope(success = true, data = posted)))
                }
                path == "/products" && request.method == HttpMethod.Get ->
                    ok(BillantaJson.encodeToString(Envelope(success = true, data = PageDto(serverProducts.toList(), null, false))))
                path == "/customers" && request.method == HttpMethod.Get ->
                    ok(BillantaJson.encodeToString(Envelope(success = true, data = PageDto<CustomerDto>(emptyList(), null, false))))
                path == "/invoices/sync" && request.method == HttpMethod.Post ->
                    ok(BillantaJson.encodeToString(Envelope(success = true, data = SyncResponseDto())))
                path == "/company" && request.method == HttpMethod.Get -> ok("""{"success":true,"data":null}""")
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
        return Harness(db, BillantaApi(client), captured, dispatcher)
    }

    private fun syncManager(h: Harness, scope: kotlinx.coroutines.CoroutineScope) = SyncManager(
        scope = scope,
        api = h.api,
        authState = MutableStateFlow<AuthState>(AuthState.SignedIn(UserAccount(id = "u1", email = "a@b.com"))),
        invoiceLocal = h.invoiceLocal,
        customerLocal = h.customerLocal,
        productLocal = h.productLocal,
        profileLocal = h.profileLocal,
        syncMeta = h.syncMeta,
        connectivity = AlwaysOnlineConnectivity,
        clock = clock,
    )

    // ---- BE-001 --------------------------------------------------------------------------------

    /**
     * A delete can sit in the queue for hours; the tombstone has to carry the moment the user
     * deleted it, not the moment we got around to pushing.
     */
    @Test
    fun a_queued_tombstone_carries_the_client_delete_time() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val h = harness(dispatcher)
        val deletedAt = Iso8601.epochMillisFor(2026, 8, 6, 9, 30)

        h.invoiceLocal.upsert(
            InvoiceRecord(
                id = "inv-1",
                invoiceNumber = "INV-1",
                invoiceDateMillis = Iso8601.epochMillisFor(2026, 8, 1),
                updatedAtMillis = deletedAt,
                deletedAtMillis = deletedAt,
                pendingSync = true,
            ),
            dirty = true,
        )

        syncManager(h, backgroundScope).syncNow()

        val delete = assertNotNull(
            h.requests.firstOrNull { it.method == HttpMethod.Delete && it.path == "/invoices/inv-1" },
            "the tombstone should have been pushed as a DELETE",
        )
        val body = assertNotNull(delete.body, "DELETE should carry a body")
        assertEquals(
            Iso8601.format(deletedAt),
            BillantaJson.parseToJsonElement(body).jsonObject["updatedAt"]?.jsonPrimitive?.content,
            "the body should carry the local delete time",
        )
        // The row is gone once the server has it.
        assertEquals(null, h.invoiceLocal.getById("inv-1"))
    }

    // ---- BE-002 --------------------------------------------------------------------------------

    @Test
    fun a_new_product_is_posted_with_paise_and_then_marked_clean() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val h = harness(dispatcher)
        h.productLocal.upsert(
            ProductRecord(
                id = "p1",
                name = "Brand identity",
                hsnSac = "998311",
                unitPricePaise = 6800000,
                taxRatePercent = "18",
                updatedAtMillis = fixedNow,
            ),
            dirty = true,
            isSynced = false,
        )

        syncManager(h, backgroundScope).syncNow()

        val post = assertNotNull(
            h.requests.firstOrNull { it.method == HttpMethod.Post && it.path == "/products" },
            "a never-synced product should be POSTed",
        )
        val body = BillantaJson.parseToJsonElement(assertNotNull(post.body)).jsonObject
        assertEquals("p1", body["id"]?.jsonPrimitive?.content, "the client id must travel with it")
        assertEquals("Brand identity", body["name"]?.jsonPrimitive?.content)
        assertEquals("6800000", body["unitPrice"]?.jsonPrimitive?.content, "money goes out as paise")
        assertEquals("18", body["taxRatePercent"]?.jsonPrimitive?.content)

        assertTrue(h.productLocal.dirtyRows().isEmpty(), "an accepted push should clear dirty")
    }

    @Test
    fun products_from_the_server_land_locally() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val h = harness(
            dispatcher,
            products = listOf(
                ProductDto(
                    id = "p-remote",
                    name = "Consulting hour",
                    unitPrice = "250000",
                    taxRatePercent = "18",
                    unit = "hour",
                    updatedAt = Iso8601.format(fixedNow),
                ),
            ),
        )

        syncManager(h, backgroundScope).syncNow()

        val stored = assertNotNull(
            h.productLocal.byNameKey(ProductRecord.nameKeyOf("Consulting hour")),
            "the pulled product should be in the catalogue",
        )
        assertEquals("p-remote", stored.id)
        assertEquals(250000, stored.unitPricePaise)
        assertEquals("hour", stored.unit)
    }

    /** A product deleted on another device should not linger here. */
    @Test
    fun a_product_the_server_no_longer_has_is_dropped() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val h = harness(dispatcher, products = emptyList())
        h.productLocal.upsert(
            ProductRecord(id = "p-old", name = "Retired service", updatedAtMillis = fixedNow),
            dirty = false,
            isSynced = true,
        )

        syncManager(h, backgroundScope).syncNow()

        assertEquals(
            null,
            h.productLocal.byNameKey(ProductRecord.nameKeyOf("Retired service")),
            "a synced-and-clean product missing from the server was deleted elsewhere",
        )
    }

    // ---- BE-006 --------------------------------------------------------------------------------

    @Test
    fun the_template_catalogue_drains_every_page() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        fun template(id: String) = TemplateDto(id = id, name = id.replaceFirstChar { it.uppercase() })
        val h = harness(
            dispatcher,
            templatePages = listOf(
                PageDto(listOf(template("classic"), template("minimal")), nextCursor = "c1", hasMore = true),
                PageDto(listOf(template("bold")), nextCursor = null, hasMore = false),
            ),
        )
        val repository = TemplateRepository(h.templateLocal, h.api, clock)

        val result = repository.refreshCatalogue()

        assertTrue(result.isSuccess, "refresh failed: ${result.errorOrNull()}")
        assertEquals(
            listOf("classic", "minimal", "bold"),
            result.getOrNull()?.map { it.id },
            "stopping at page one would silently hide templates",
        )
        assertEquals(
            2,
            h.requests.count { it.path == "/templates" },
            "the second page should have been requested with the cursor",
        )
        assertTrue(
            h.requests.last { it.path == "/templates" }.query.contains("cursor=c1"),
            "the follow-up call must pass the cursor the first page returned",
        )
    }
}
