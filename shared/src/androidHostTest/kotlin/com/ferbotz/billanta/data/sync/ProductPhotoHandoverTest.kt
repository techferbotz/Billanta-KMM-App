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
import com.ferbotz.billanta.data.api.SettingsDto
import com.ferbotz.billanta.data.api.SyncResponseDto
import com.ferbotz.billanta.data.db.BillantaDb
import com.ferbotz.billanta.data.local.CustomerLocalDataSource
import com.ferbotz.billanta.data.local.InvoiceLocalDataSource
import com.ferbotz.billanta.data.local.ProductLocalDataSource
import com.ferbotz.billanta.data.local.ProfileLocalDataSource
import com.ferbotz.billanta.data.local.SyncMetaLocal
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BE-020's handover, end to end. Until the backend stored product photos it dropped the keys it
 * was sent, so a photo pushed before its deploy comes back now as `null`. The first pull that
 * carries the keys must re-send such a photo rather than read the `null` as a removal; after that
 * one pull, a `null` from the server is a genuine removal and is obeyed.
 */
class ProductPhotoHandoverTest {

    private val full = "https://billanta-media.s3.ap-south-1.amazonaws.com/u/1/full.webp"
    private val thumb = "https://billanta-media.s3.ap-south-1.amazonaws.com/u/1/thumb.webp"
    private val clock = EpochClock { Iso8601.epochMillisFor(2026, 9, 26, 12, 0) }

    /** A server that remembers each product's photo, starting from "never stored". */
    private class FakeServer {
        var imageUrl: String? = null
        var imageThumbnailUrl: String? = null
        val patches = mutableListOf<JsonObject>()

        fun productJson(): String {
            fun q(v: String?) = v?.let { "\"$it\"" } ?: "null"
            return """{ "id": "p1", "name": "Logo design", "unitPrice": "150000", "taxRatePercent": "18",
                "updatedAt": "2026-09-26T00:00:00.000Z",
                "imageUrl": ${q(imageUrl)}, "imageThumbnailUrl": ${q(imageThumbnailUrl)} }"""
        }
    }

    private class World(val products: ProductLocalDataSource, val meta: SyncMetaLocal, val sync: SyncManager)

    private fun world(server: FakeServer, scope: CoroutineScope): World {
        val dispatcher = UnconfinedTestDispatcher()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        BillantaDb.Schema.create(driver)
        val db = BillantaDb(driver)
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            fun ok(body: String) = respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            when {
                path == "/products" && request.method == HttpMethod.Get ->
                    ok("""{ "success": true, "data": { "items": [ ${server.productJson()} ], "nextCursor": null, "hasMore": false } }""")
                path == "/products/p1" && request.method == HttpMethod.Patch -> {
                    val body = BillantaJson.parseToJsonElement((request.body as TextContent).text).jsonObject
                    server.patches += body
                    // Partial, as BE-020 says: only the keys that are present change anything.
                    body["imageUrl"]?.let { server.imageUrl = (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
                    body["imageThumbnailUrl"]?.let { server.imageThumbnailUrl = (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
                    ok("""{ "success": true, "data": ${server.productJson()} }""")
                }
                path == "/customers" && request.method == HttpMethod.Get ->
                    ok(BillantaJson.encodeToString(Envelope(success = true, data = PageDto<CustomerDto>(emptyList(), null, false))))
                path == "/company" && request.method == HttpMethod.Get -> ok("""{"success":true,"data":null}""")
                path == "/settings" && request.method == HttpMethod.Get ->
                    ok(BillantaJson.encodeToString(Envelope(success = true, data = SettingsDto())))
                path == "/invoices/sync" -> ok(BillantaJson.encodeToString(Envelope(success = true, data = SyncResponseDto())))
                else -> ok("""{"success":true,"data":{}}""")
            }
        }
        val client = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(BillantaJson) }
            defaultRequest { url("https://test.local/") }
        }
        val products = ProductLocalDataSource(db, dispatcher)
        val meta = SyncMetaLocal(db, dispatcher)
        val sync = SyncManager(
            scope = scope,
            api = BillantaApi(client),
            authState = MutableStateFlow<AuthState>(AuthState.SignedIn(UserAccount(id = "user-1", email = "a@b.com"))),
            invoiceLocal = InvoiceLocalDataSource(db, dispatcher),
            customerLocal = CustomerLocalDataSource(db, dispatcher),
            productLocal = products,
            profileLocal = ProfileLocalDataSource(db, dispatcher),
            syncMeta = meta,
            connectivity = AlwaysOnlineConnectivity,
            clock = clock,
        )
        return World(products, meta, sync)
    }

    @Test
    fun a_photo_the_old_server_dropped_is_sent_again_and_later_removals_are_obeyed() = runTest {
        val server = FakeServer()
        val w = world(server, backgroundScope)
        // Pushed before the deploy: clean and synced on the phone, never stored on the server.
        w.products.upsert(
            ProductRecord(id = "p1", name = "Logo design", unitPricePaise = 150_000, imageUrl = full, imageThumbnailUrl = thumb),
            dirty = false,
            isSynced = true,
        )

        // Pass 1: the pull sees the keys, both null. The photo stays and is queued to go again.
        assertTrue(w.sync.syncNow().isSuccess)
        val afterPull = assertNotNull(w.products.getById("p1"))
        assertEquals(full, afterPull.imageUrl, "a null from the handover must not delete the photo")
        assertTrue(afterPull.pendingSync, "the kept photo is queued to be sent")
        assertNotNull(w.meta.get(SyncMetaLocal.KEY_PRODUCT_PHOTOS_RECONCILED), "one pull with the keys is enough")

        // Pass 2: the push sends it as a PATCH carrying both keys, and the server now has it.
        assertTrue(w.sync.syncNow().isSuccess)
        val patch = server.patches.single()
        assertEquals(JsonPrimitive(full), patch["imageUrl"])
        assertEquals(JsonPrimitive(thumb), patch["imageThumbnailUrl"])
        assertEquals(full, server.imageUrl)
        val synced = assertNotNull(w.products.getById("p1"))
        assertEquals(full, synced.imageUrl)
        assertFalse(synced.pendingSync)

        // Another device removes the photo. The handover is over, so this null is believed.
        server.imageUrl = null
        server.imageThumbnailUrl = null
        assertTrue(w.sync.syncNow().isSuccess)
        val removed = assertNotNull(w.products.getById("p1"))
        assertNull(removed.imageUrl)
        assertNull(removed.imageThumbnailUrl)
        assertEquals(1, server.patches.size, "a removal from elsewhere is not pushed back")
    }
}
