package com.ferbotz.billanta.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.ferbotz.billanta.config.RemoteConfig
import com.ferbotz.billanta.core.AlwaysOnlineConnectivity
import com.ferbotz.billanta.core.BillantaJson
import com.ferbotz.billanta.core.EpochClock
import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.data.api.BillantaApi
import com.ferbotz.billanta.data.api.EntityValuesDto
import com.ferbotz.billanta.data.api.Envelope
import com.ferbotz.billanta.data.api.InvoiceValuesDto
import com.ferbotz.billanta.data.api.InvoiceValuesSyncRequestDto
import com.ferbotz.billanta.data.api.InvoiceValuesSyncResponseDto
import com.ferbotz.billanta.data.api.PageDto
import com.ferbotz.billanta.data.api.SyncConflictDto
import com.ferbotz.billanta.data.api.ValuesBodyDto
import com.ferbotz.billanta.data.api.ValuesRecordDto
import com.ferbotz.billanta.data.db.BillantaDb
import com.ferbotz.billanta.data.local.CustomerValuesLocal
import com.ferbotz.billanta.data.local.InvoiceValuesLocal
import com.ferbotz.billanta.data.local.ProductValuesLocal
import com.ferbotz.billanta.data.local.RememberedValuesLocal
import com.ferbotz.billanta.data.local.SingletonValuesLocal
import com.ferbotz.billanta.data.local.SyncMetaLocal
import com.ferbotz.billanta.domain.model.UserAccount
import com.ferbotz.billanta.domain.money.InvoiceMath
import com.ferbotz.billanta.fields.FieldValues
import com.ferbotz.billanta.records.Customer
import com.ferbotz.billanta.records.Invoice
import com.ferbotz.billanta.records.InvoiceLine
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** BE-021's sync, against a fake server that keeps state the way the real one does. */
class SyncManagerTest {

    private val now = Iso8601.epochMillisFor(2026, 10, 22, 12, 0)
    private val clock = EpochClock { now }

    private class Captured(val method: HttpMethod, val path: String, val body: String?)

    /** Merges a PATCH: a key set to null is removed, an absent key is untouched (FIELDS.md §7.1). */
    private fun merge(base: JsonObject, patch: JsonObject) =
        JsonObject((base + patch).filterValues { it != JsonNull })

    private inner class FakeServer {
        var company: JsonObject? = null
        var settings = JsonObject(mapOf("settings_invoice_prefix" to JsonPrimitive("INV-")))
        val customers = linkedMapOf<String, JsonObject>()
        val products = linkedMapOf<String, JsonObject>()
        val invoices = linkedMapOf<String, InvoiceValuesDto>()
        /** Invoices another device pushed: the next pull returns them in `changed`. */
        val elsewhere = mutableListOf<InvoiceValuesDto>()
        /** When set, every call answers that the signed-in account no longer exists (BE-010). */
        var accountGone = false
        /** Runs while a sync push is in flight, before the server answers it. */
        var duringSync: (suspend () -> Unit)? = null
        /** When set, the server reports these totals, to prove its figures win over ours. */
        var totalsOverride: Map<String, String>? = null
        val requests = mutableListOf<Captured>()

        private fun stamp() = Iso8601.format(now)

        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val body = (request.body as? TextContent)?.text
            requests += Captured(request.method, path, body)
            fun ok(data: String) = respond("""{"success":true,"data":$data}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            fun values(): JsonObject = BillantaJson.decodeFromString(ValuesBodyDto.serializer(), body!!).values
            val method = request.method
            if (accountGone) {
                return@MockEngine respond(
                    """{"success":false,"message":"gone","code":"ACCOUNT_NOT_FOUND"}""",
                    HttpStatusCode.Unauthorized,
                    headersOf("Content-Type", "application/json"),
                )
            }
            when {
                path == "/company" && method == HttpMethod.Patch -> {
                    company = merge(company ?: JsonObject(emptyMap()), values())
                    ok(BillantaJson.encodeToString(ValuesRecordDto.serializer(), ValuesRecordDto(company!!, stamp(), stamp())))
                }
                path == "/company" && method == HttpMethod.Get ->
                    ok(company?.let { BillantaJson.encodeToString(ValuesRecordDto.serializer(), ValuesRecordDto(it, stamp(), stamp())) } ?: "null")
                path == "/settings" && method == HttpMethod.Patch -> {
                    settings = merge(settings, values())
                    ok(BillantaJson.encodeToString(ValuesRecordDto.serializer(), ValuesRecordDto(settings, stamp(), stamp())))
                }
                path == "/settings" && method == HttpMethod.Get ->
                    ok(BillantaJson.encodeToString(ValuesRecordDto.serializer(), ValuesRecordDto(settings, stamp(), stamp())))
                path == "/customers" && method == HttpMethod.Post -> {
                    val dto = BillantaJson.decodeFromString(EntityValuesDto.serializer(), body!!)
                    customers[dto.id] = dto.values
                    ok(BillantaJson.encodeToString(EntityValuesDto.serializer(), dto.copy(createdAt = stamp(), updatedAt = stamp())))
                }
                path.startsWith("/customers/") && method == HttpMethod.Patch -> {
                    val id = path.removePrefix("/customers/")
                    val current = customers[id]
                    if (current == null) {
                        respond("""{"success":false,"message":"not found"}""", HttpStatusCode.NotFound, headersOf("Content-Type", "application/json"))
                    } else {
                        customers[id] = merge(current, values())
                        ok(BillantaJson.encodeToString(EntityValuesDto.serializer(), EntityValuesDto(id, customers[id]!!, stamp(), stamp())))
                    }
                }
                (path == "/customers" || path == "/products") && method == HttpMethod.Get -> {
                    val source = if (path == "/customers") customers else products
                    val items = source.map { (id, v) -> EntityValuesDto(id, v, stamp(), stamp()) }
                    ok(BillantaJson.encodeToString(PageDto.serializer(EntityValuesDto.serializer()), PageDto(items, null, false)))
                }
                path == "/products" && method == HttpMethod.Post -> {
                    val dto = BillantaJson.decodeFromString(EntityValuesDto.serializer(), body!!)
                    products[dto.id] = dto.values
                    ok(BillantaJson.encodeToString(EntityValuesDto.serializer(), dto.copy(createdAt = stamp(), updatedAt = stamp())))
                }
                path.startsWith("/invoices/") && method == HttpMethod.Delete -> {
                    invoices.remove(path.removePrefix("/invoices/"))
                    ok("{}")
                }
                path == "/invoices/sync" && method == HttpMethod.Post -> {
                    duringSync?.invoke()
                    duringSync = null
                    val pushed = BillantaJson.decodeFromString(InvoiceValuesSyncRequestDto.serializer(), body!!).invoices
                    val conflicts = mutableListOf<SyncConflictDto>()
                    val changed = mutableListOf<InvoiceValuesDto>()
                    pushed.forEach { dto ->
                        val number = (dto.values["invoice_number"] as? JsonPrimitive)?.content.orEmpty()
                        val clash = number.isNotEmpty() && invoices.values.any {
                            it.id != dto.id && (it.values["invoice_number"] as? JsonPrimitive)?.content == number
                        }
                        if (clash) {
                            conflicts += SyncConflictDto(dto.id, "Invoice number $number is already used")
                        } else {
                            val computed = InvoiceMath.compute(
                                FieldValues.of(dto.values),
                                dto.lineItems.map { FieldValues.of(it.values) },
                            )
                            val stored = dto.copy(
                                computed = totalsOverride ?: computed.totalsMap(),
                                lineItems = dto.lineItems.mapIndexed { i, line -> line.copy(computed = computed.lines[i].toMap()) },
                                createdAt = stamp(),
                            )
                            invoices[dto.id] = stored
                            changed += stored
                        }
                    }
                    changed += elsewhere
                    elsewhere.clear()
                    ok(
                        BillantaJson.encodeToString(
                            InvoiceValuesSyncResponseDto.serializer(),
                            InvoiceValuesSyncResponseDto(changed, conflicts, nextCursor = "c1", hasMore = false),
                        ),
                    )
                }
                else -> ok("{}")
            }
        }
    }

    private class Harness(val server: Any, val db: BillantaDb, val api: BillantaApi, val dispatcher: kotlinx.coroutines.CoroutineDispatcher) {
        val singletons = SingletonValuesLocal(db, dispatcher)
        val customers = CustomerValuesLocal(db, dispatcher)
        val products = ProductValuesLocal(db, dispatcher)
        val invoices = InvoiceValuesLocal(db, dispatcher)
        val remembered = RememberedValuesLocal(db, dispatcher)
        val syncMeta = SyncMetaLocal(db, dispatcher)
    }

    private fun harness(dispatcher: kotlinx.coroutines.CoroutineDispatcher, server: FakeServer): Harness {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        BillantaDb.Schema.create(driver)
        val client = HttpClient(server.engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(BillantaJson) }
            defaultRequest { url("https://test.local/") }
        }
        return Harness(server, BillantaDb(driver), BillantaApi(client), dispatcher)
    }

    private fun sync(
        h: Harness,
        scope: CoroutineScope,
        limits: RemoteConfig.Limits = RemoteConfig.Limits(),
        onAccountVanished: suspend () -> Unit = {},
    ) = SyncManager(
        scope = scope,
        api = h.api,
        authState = MutableStateFlow<AuthState>(AuthState.SignedIn(UserAccount(id = "u1", email = "a@b.com"))),
        singletons = h.singletons,
        customers = h.customers,
        products = h.products,
        invoices = h.invoices,
        syncMeta = h.syncMeta,
        connectivity = AlwaysOnlineConnectivity,
        clock = clock,
        onAccountVanished = onAccountVanished,
        limits = { limits },
    )

    private fun invoice(id: String, number: String, updatedAt: Long = now) = Invoice(
        id = id,
        templateId = "classic",
        templateVersion = 1,
        values = FieldValues.EMPTY.withString("invoice_number", number).withString("currency", "INR"),
        lines = listOf(
            InvoiceLine("$id-l1", FieldValues.EMPTY.withString("quantity", "2").withString("unit_price", "1000").withString("tax_rate", "18")),
        ),
        updatedAtMillis = updatedAt,
        pendingSync = true,
    ).recomputed()

    @Test
    fun profile_edits_go_up_as_a_patch_of_the_changed_keys_only() = runTest {
        val server = FakeServer()
        val h = harness(UnconfinedTestDispatcher(testScheduler), server)
        h.singletons.applyServerProfile(
            FieldValues.EMPTY.withString("company_name", "Acme").withString("company_city", "Pune"),
            createdAtMillis = null,
            updatedAtMillis = now - 1000,
        )
        server.company = h.singletons.getProfile()!!.values.json

        // Clear the city, add a GSTIN; the name is untouched.
        h.singletons.saveProfileLocal(
            FieldValues.EMPTY.withString("company_name", "Acme").withString("company_gstin", "29ABCDE1234F1Z5"),
            updatedAtMillis = now,
        )
        sync(h, backgroundScope).syncNow()

        val patch = assertNotNull(server.requests.singleOrNull { it.method == HttpMethod.Patch && it.path == "/company" })
        assertEquals(
            BillantaJson.parseToJsonElement("""{"values":{"company_city":null,"company_gstin":"29ABCDE1234F1Z5"}}"""),
            BillantaJson.parseToJsonElement(patch.body!!),
        )
        assertEquals(emptySet(), h.singletons.getProfile()!!.dirtyKeys)
        assertEquals(setOf("company_name", "company_gstin"), server.company!!.keys)
    }

    @Test
    fun settings_are_patched_never_replaced() = runTest {
        val server = FakeServer()
        val h = harness(UnconfinedTestDispatcher(testScheduler), server)
        h.singletons.saveSettingsLocal(FieldValues.EMPTY.withString("settings_next_invoice_number", "8"), updatedAtMillis = now)
        sync(h, backgroundScope).syncNow()

        assertTrue(server.requests.none { it.method == HttpMethod.Put })
        assertTrue(server.requests.any { it.method == HttpMethod.Patch && it.path == "/settings" })
        // The prefix the server had survives: a merge, not a replace.
        assertEquals(setOf("settings_invoice_prefix", "settings_next_invoice_number"), server.settings.keys)
    }

    @Test
    fun a_new_customer_posts_whole_then_edits_patch_only_what_changed() = runTest {
        val server = FakeServer()
        val h = harness(UnconfinedTestDispatcher(testScheduler), server)
        val sync = sync(h, backgroundScope)
        h.customers.saveLocal(Customer("c1", FieldValues.EMPTY.withString("customer_name", "Bob").withString("customer_phone", "1"), updatedAtMillis = now))
        sync.syncNow()
        val post = assertNotNull(server.requests.singleOrNull { it.method == HttpMethod.Post && it.path == "/customers" })
        assertEquals(setOf("customer_name", "customer_phone"), BillantaJson.parseToJsonElement(post.body!!).jsonObject.getValue("values").jsonObject.keys)

        server.requests.clear()
        val saved = h.customers.getById("c1")!!
        h.customers.saveLocal(saved.copy(values = saved.values.withString("customer_phone", "2"), updatedAtMillis = now + 1))
        sync.syncNow()
        val patch = assertNotNull(server.requests.singleOrNull { it.method == HttpMethod.Patch && it.path == "/customers/c1" })
        assertEquals(BillantaJson.parseToJsonElement("""{"values":{"customer_phone":"2"}}"""), BillantaJson.parseToJsonElement(patch.body!!))
        assertEquals("2", (server.customers.getValue("c1")["customer_phone"] as JsonPrimitive).content)
    }

    @Test
    fun the_servers_totals_replace_ours_after_a_sync() = runTest {
        val server = FakeServer().apply { totalsOverride = mapOf("subtotal" to "2000", "grand_total" to "999") }
        val h = harness(UnconfinedTestDispatcher(testScheduler), server)
        h.invoices.upsert(invoice("i1", "INV-1"), dirty = true)
        assertEquals(2360L, h.invoices.getById("i1")!!.computed.grandTotal)

        sync(h, backgroundScope).syncNow()

        val stored = h.invoices.getById("i1")!!
        assertEquals(999L, stored.computed.grandTotal)
        assertTrue(!stored.pendingSync)
    }

    @Test
    fun invoice_pushes_are_split_under_the_batch_cap() = runTest {
        val server = FakeServer()
        val h = harness(UnconfinedTestDispatcher(testScheduler), server)
        (1..5).forEach { h.invoices.upsert(invoice("i$it", "INV-$it"), dirty = true) }

        sync(h, backgroundScope, RemoteConfig.Limits(maxSyncBatchInvoices = 2)).syncNow()

        val pushes = server.requests.filter { it.path == "/invoices/sync" }
            .map { BillantaJson.decodeFromString(InvoiceValuesSyncRequestDto.serializer(), it.body!!).invoices.size }
        assertEquals(listOf(2, 2, 1), pushes)
        assertEquals(5, server.invoices.size)
    }

    @Test
    fun a_conflict_marks_the_invoice_and_stops_retrying_until_it_changes() = runTest {
        val server = FakeServer()
        val h = harness(UnconfinedTestDispatcher(testScheduler), server)
        val sync = sync(h, backgroundScope)
        h.invoices.upsert(invoice("first", "INV-1"), dirty = true)
        sync.syncNow()
        h.invoices.upsert(invoice("second", "INV-1"), dirty = true)
        sync.syncNow()
        assertEquals("Invoice number INV-1 is already used", h.invoices.getById("second")!!.syncError)

        server.requests.clear()
        sync.syncNow()
        val pushedIds = server.requests.filter { it.path == "/invoices/sync" }
            .flatMap { BillantaJson.decodeFromString(InvoiceValuesSyncRequestDto.serializer(), it.body!!).invoices.map { dto -> dto.id } }
        assertTrue("second" !in pushedIds, "a rejected invoice is not re-sent until the user changes it")
    }

    @Test
    fun an_invoice_over_the_line_limit_is_held_back_with_a_reason() = runTest {
        val server = FakeServer()
        val h = harness(UnconfinedTestDispatcher(testScheduler), server)
        h.invoices.upsert(invoice("big", "INV-9"), dirty = true)
        sync(h, backgroundScope, RemoteConfig.Limits(maxLineItems = 0)).syncNow()
        assertEquals("More than 0 lines", h.invoices.getById("big")!!.syncError)
        assertTrue(server.invoices.isEmpty())
    }

    @Test
    fun remembered_values_never_reach_the_wire() = runTest {
        val server = FakeServer()
        val h = harness(UnconfinedTestDispatcher(testScheduler), server)
        h.remembered.put("po_number", JsonPrimitive("PO-REMEMBERED-ON-THIS-PHONE"))
        h.singletons.saveProfileLocal(FieldValues.EMPTY.withString("company_name", "Acme"), now)
        h.customers.saveLocal(Customer("c1", FieldValues.EMPTY.withString("customer_name", "Bob"), updatedAtMillis = now))
        h.invoices.upsert(invoice("i1", "INV-1"), dirty = true)

        sync(h, backgroundScope).syncNow()

        assertTrue(server.requests.isNotEmpty())
        assertTrue(server.requests.none { it.body?.contains("PO-REMEMBERED-ON-THIS-PHONE") == true })
    }

    // ---- carried over from the v1 contract tests -------------------------------------------------

    /** BE-001: a queued delete carries the moment the user deleted, not the moment it was pushed. */
    @Test
    fun a_queued_tombstone_carries_the_client_delete_time() = runTest {
        val server = FakeServer()
        val h = harness(UnconfinedTestDispatcher(testScheduler), server)
        val deletedAt = Iso8601.epochMillisFor(2026, 10, 22, 9, 30)
        h.invoices.upsert(invoice("i1", "INV-1"), dirty = false)
        h.invoices.softDelete("i1", deletedAt)

        sync(h, backgroundScope).syncNow()

        val delete = assertNotNull(server.requests.firstOrNull { it.method == HttpMethod.Delete && it.path == "/invoices/i1" })
        assertEquals(
            Iso8601.format(deletedAt),
            (BillantaJson.parseToJsonElement(delete.body!!).jsonObject["updatedAt"] as JsonPrimitive).content,
        )
        assertEquals(null, h.invoices.getByIdIncludingDeleted("i1"), "the row goes once the server has the delete")
    }

    /** Hidden sections and colour overrides travel and come back intact, so a pull never undoes them. */
    @Test
    fun a_sync_round_trip_preserves_the_invoice_customisation() = runTest {
        val server = FakeServer()
        val h = harness(UnconfinedTestDispatcher(testScheduler), server)
        h.invoices.upsert(
            invoice("i1", "INV-1").copy(hiddenSections = setOf("notes"), themeOverrides = mapOf("accent" to "#c2410c")),
            dirty = true,
        )

        sync(h, backgroundScope).syncNow()

        val stored = h.invoices.getById("i1")!!
        assertEquals(setOf("notes"), stored.hiddenSections)
        assertEquals(mapOf("accent" to "#c2410c"), stored.themeOverrides)
        assertEquals(listOf("notes"), server.invoices.getValue("i1").hiddenSections)
    }

    /** An edit made while a push is in flight stays dirty, and another device's invoice lands here. */
    @Test
    fun a_mid_flight_edit_survives_and_the_pull_brings_in_new_invoices() = runTest {
        val server = FakeServer()
        val h = harness(UnconfinedTestDispatcher(testScheduler), server)
        val original = invoice("i1", "INV-1")
        h.invoices.upsert(original, dirty = true)
        server.elsewhere += InvoiceValuesDto(
            id = "i2", templateId = "classic", templateVersion = 1, status = "Draft",
            values = FieldValues.EMPTY.withString("invoice_number", "INV-2").json, updatedAt = Iso8601.format(now),
        )
        // The user edits i1 while its push is on the wire: that edit is newer than what was sent.
        server.duringSync = {
            h.invoices.upsert(
                original.copy(values = original.values.withString("notes", "edited"), updatedAtMillis = now + 5_000),
                dirty = true,
            )
        }

        sync(h, backgroundScope).syncNow()

        val i1 = h.invoices.getById("i1")!!
        assertEquals("edited", i1.values.string("notes"), "the server's echo of the older copy must not overwrite the edit")
        assertTrue(i1.pendingSync, "the edit is still waiting to go up")
        assertNotNull(h.invoices.getById("i2"), "the invoice made on another device should have been pulled")
        assertEquals("c1", h.syncMeta.get(SyncMetaLocal.KEY_INVOICE_CURSOR), "the cursor should be stored")
    }

    @Test
    fun products_from_the_server_land_and_ones_it_no_longer_has_are_dropped() = runTest {
        val server = FakeServer()
        val h = harness(UnconfinedTestDispatcher(testScheduler), server)
        server.products["p-new"] = FieldValues.EMPTY.withString("description", "Design hour").withString("unit_price", "150000").json
        h.products.saveLocal(com.ferbotz.billanta.records.Product("p-old", FieldValues.EMPTY.withString("description", "Retired"), updatedAtMillis = now))
        h.products.markSynced("p-old", now)

        sync(h, backgroundScope).syncNow()

        assertEquals("150000", h.products.getById("p-new")?.values?.string("unit_price"))
        assertEquals(null, h.products.getById("p-old"), "a synced, clean product missing from the server was deleted elsewhere")
    }

    /** BE-010: when the account behind the session is gone, sync ends the session instead of retrying. */
    @Test
    fun a_vanished_account_ends_the_session() = runTest {
        val server = FakeServer().apply { accountGone = true }
        val h = harness(UnconfinedTestDispatcher(testScheduler), server)
        h.singletons.saveProfileLocal(FieldValues.EMPTY.withString("company_name", "Acme"), now)
        var vanished = 0

        sync(h, backgroundScope, onAccountVanished = { vanished++ }).syncNow()

        assertEquals(1, vanished)
        assertEquals(1, server.requests.size, "the pass stops at the first sign the account is gone")
        assertEquals(setOf("company_name"), h.singletons.getProfile()!!.dirtyKeys, "nothing local is lost")
    }
}
