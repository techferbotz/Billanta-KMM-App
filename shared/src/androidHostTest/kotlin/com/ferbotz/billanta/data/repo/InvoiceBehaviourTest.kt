package com.ferbotz.billanta.data.repo

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.core.BillantaJson
import com.ferbotz.billanta.core.EpochClock
import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.data.db.BillantaDb
import com.ferbotz.billanta.data.local.CustomerValuesLocal
import com.ferbotz.billanta.data.local.InvoiceValuesLocal
import com.ferbotz.billanta.data.local.ProductValuesLocal
import com.ferbotz.billanta.data.local.RememberedValuesLocal
import com.ferbotz.billanta.data.local.SingletonValuesLocal
import com.ferbotz.billanta.domain.model.InvoiceDocStatus
import com.ferbotz.billanta.fields.Be021Fixtures
import com.ferbotz.billanta.fields.FieldDef
import com.ferbotz.billanta.fields.FieldParser
import com.ferbotz.billanta.fields.FieldSection
import com.ferbotz.billanta.fields.FieldValues
import com.ferbotz.billanta.records.Invoice
import com.ferbotz.billanta.records.Product
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FIELDS_APP_GUIDE.md §11's behaviour items, against the real repository and an in-memory database. */
class InvoiceBehaviourTest {

    // Mid-morning UTC, so "today" is the same date in every time zone a test machine is likely in.
    private val now = Iso8601.epochMillisFor(2026, 10, 22, 6, 0)
    private val clock = EpochClock { now }
    private val today = "2026-10-22"

    private val classic: List<FieldSection> = FieldParser.parseSections(
        (BillantaJson.parseToJsonElement(Be021Fixtures.text("template-classic.compiled.json")) as JsonObject)["sections"],
        hidableByDefault = true,
    )
    private val settingsForm = Be021Fixtures.forms.settings
    private val exampleValues = BillantaJson.parseToJsonElement(Be021Fixtures.text("render-context.json")).jsonObject

    private fun field(sections: List<FieldSection>, id: String): FieldDef =
        sections.flatMap { it.fields }.single { it.id == id }

    private val lineColumns get() = field(classic, "line_items").columns

    private class World(
        val singletons: SingletonValuesLocal,
        val customers: CustomerValuesLocal,
        val products: ProductValuesLocal,
        val invoices: InvoiceValuesLocal,
        val remembered: RememberedValuesLocal,
        val repo: InvoiceRepository,
        val profiles: ProfileRepository,
    )

    private fun world(dispatcher: kotlinx.coroutines.CoroutineDispatcher): World {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        BillantaDb.Schema.create(driver)
        val db = BillantaDb(driver)
        val singletons = SingletonValuesLocal(db, dispatcher)
        val customers = CustomerValuesLocal(db, dispatcher)
        val products = ProductValuesLocal(db, dispatcher)
        val invoices = InvoiceValuesLocal(db, dispatcher)
        val remembered = RememberedValuesLocal(db, dispatcher)
        var ids = 0
        val repo = InvoiceRepository(invoices, customers, products, singletons, remembered, clock, {}, newId = { "id-${++ids}" })
        val profiles = ProfileRepository(singletons, clock, {}, onProfileChanged = { repo.refreshDrafts() })
        return World(singletons, customers, products, invoices, remembered, repo, profiles)
    }

    /** The example's business profile and settings, as a pull would leave them. */
    private suspend fun World.seed(settings: FieldValues = defaultSettings()) {
        singletons.applyServerProfile(
            FieldValues.of(JsonObject(exampleValues.filterKeys { it.startsWith("company_") })),
            createdAtMillis = null,
            updatedAtMillis = now - 1,
        )
        singletons.applyServerSettings(settings, createdAtMillis = null, updatedAtMillis = now - 1)
    }

    private fun defaultSettings() = FieldValues.EMPTY
        .withString("settings_invoice_prefix", "INV-")
        .withString("settings_next_invoice_number", "7")
        .withString("settings_default_due_days", "14")
        .withString("settings_default_notes", "Thank you!")
        .withString("settings_default_tax_rate", "18")

    private fun AppResult<Invoice>.value() = (this as AppResult.Success).value

    @Test
    fun a_new_classic_invoice_gets_its_starting_values() = runTest {
        val w = world(UnconfinedTestDispatcher(testScheduler))
        w.seed()

        val invoice = w.repo.create("classic", 1, classic, settingsForm)
        val values = invoice.values

        // The complete company copy, not just the printed fields.
        assertEquals(exampleValues.keys.filter { it.startsWith("company_") }.toSet(), values.keys.filter { it.startsWith("company_") }.toSet())
        assertEquals("INV-7", values.string("invoice_number"))
        assertEquals("INR", values.string("currency"))
        assertEquals(today, values.string("invoice_date"))
        assertEquals("2026-11-05", values.string("due_date"), "settings_default_due_days the first time")
        assertEquals("Thank you!", values.string("notes"))
        assertTrue(values.keys.none { it.startsWith("customer") }, "customer fields wait for a customer")
        assertEquals("8", w.singletons.getSettings()!!.values.string("settings_next_invoice_number"))

        // Choosing a 30-day due date is remembered as a gap, and the next invoice starts with it.
        w.repo.setField(invoice.id, field(classic, "due_date"), JsonPrimitive("2026-11-21"))
        val next = w.repo.create("classic", 1, classic, settingsForm)
        assertEquals("2026-11-21", next.values.string("due_date"))
        assertEquals("INV-8", next.values.string("invoice_number"))
    }

    @Test
    fun the_number_never_goes_below_one_already_used_here() = runTest {
        val w = world(UnconfinedTestDispatcher(testScheduler))
        w.seed(defaultSettings().withString("settings_next_invoice_number", "5"))
        w.invoices.upsert(Invoice(id = "old", values = FieldValues.EMPTY.withString("invoice_number", "INV-41"), updatedAtMillis = now), dirty = false)
        assertEquals("INV-42", w.repo.create("classic", 1, classic, settingsForm).values.string("invoice_number"))
    }

    @Test
    fun offline_first_launch_uses_the_settings_forms_defaults() = runTest {
        val w = world(UnconfinedTestDispatcher(testScheduler))
        // Nothing seeded: no profile, no settings.
        val invoice = w.repo.create("classic", 1, classic, settingsForm)
        assertEquals("INV-1", invoice.values.string("invoice_number"))
        assertEquals("INR", invoice.values.string("currency"))
    }

    @Test
    fun a_remembered_value_that_no_longer_fits_is_ignored() = runTest {
        val w = world(UnconfinedTestDispatcher(testScheduler))
        val sections = FieldParser.parseSections(
            BillantaJson.parseToJsonElement(
                """[{"id":"extra","label":"Extra","fields":[
                   {"id":"po_number","owner":"invoice","type":"text","label":"PO","remember":true,"default":{"kind":"value","value":"PO-?"}}]}]""",
            ),
            hidableByDefault = true,
        )
        // Another template made po_number a switch, and remembered a boolean.
        w.remembered.put("po_number", JsonPrimitive(true))
        assertEquals("PO-?", w.repo.create("t", 1, sections, settingsForm).values.string("po_number"))
    }

    @Test
    fun editing_the_ifsc_inside_an_invoice_updates_the_profile() = runTest {
        val w = world(UnconfinedTestDispatcher(testScheduler))
        w.seed()
        val invoice = w.repo.create("classic", 1, classic, settingsForm)

        w.repo.setField(invoice.id, field(classic, "company_bank_ifsc"), JsonPrimitive("HDFC0001234"))

        val profile = w.singletons.getProfile()!!
        assertEquals("HDFC0001234", profile.values.string("company_bank_ifsc"))
        assertEquals(setOf("company_bank_ifsc"), profile.dirtyKeys, "only the edited key goes up")
    }

    @Test
    fun a_customer_field_with_none_picked_creates_the_customer() = runTest {
        val w = world(UnconfinedTestDispatcher(testScheduler))
        w.seed()
        val invoice = w.repo.create("classic", 1, classic, settingsForm)

        val named = w.repo.setField(invoice.id, field(classic, "customer_name"), JsonPrimitive("Zed Traders")).value()
        val customerId = assertNotNull(named.values.string("customer"))
        assertEquals("Zed Traders", w.customers.getById(customerId)!!.name)

        // From then on, edits write back to that customer.
        w.repo.setField(invoice.id, field(classic, "customer_gstin"), JsonPrimitive("27AAJCB1111C1Z2"))
        assertEquals("27AAJCB1111C1Z2", w.customers.getById(customerId)!!.values.string("customer_gstin"))
        assertEquals(customerId, w.invoices.getById(invoice.id)!!.values.string("customer"))
    }

    @Test
    fun a_draft_follows_the_profile_and_an_issued_invoice_does_not() = runTest {
        val w = world(UnconfinedTestDispatcher(testScheduler))
        w.seed()
        val draft = w.repo.create("classic", 1, classic, settingsForm)
        val issued = w.repo.create("classic", 1, classic, settingsForm)
        w.repo.markIssued(issued.id)

        val profile = w.singletons.getProfile()!!.values
        w.profiles.saveProfile(profile.withString("company_name", "Kaveri Crafts LLP"))

        assertEquals("Kaveri Crafts LLP", w.invoices.getById(draft.id)!!.values.string("company_name"))
        val frozen = w.invoices.getById(issued.id)!!
        assertEquals(InvoiceDocStatus.Pending, frozen.status)
        assertEquals("Kaveri Craft Studio", frozen.values.string("company_name"))
    }

    @Test
    fun switching_template_keeps_every_value_and_starts_the_new_fields() = runTest {
        val w = world(UnconfinedTestDispatcher(testScheduler))
        w.seed(defaultSettings().withString("settings_default_terms", "Due on receipt."))
        val invoice = w.repo.create("classic", 1, classic, settingsForm)
        w.repo.setField(invoice.id, field(classic, "notes"), JsonPrimitive("Handle with care"))

        val other = FieldParser.parseSections(
            BillantaJson.parseToJsonElement(
                """[{"id":"fine_print","label":"Fine print","fields":[
                   {"id":"terms","owner":"invoice","type":"multiline","label":"Terms","default":{"kind":"setting","id":"settings_default_terms"}}]}]""",
            ),
            hidableByDefault = true,
        )
        val switched = w.repo.setTemplate(invoice.id, "other", 3, other, settingsForm).value()

        assertEquals("other", switched.templateId)
        assertEquals("Handle with care", switched.values.string("notes"), "kept, though the new template does not print it")
        assertEquals("Due on receipt.", switched.values.string("terms"))
    }

    @Test
    fun picking_a_product_copies_all_its_values_and_a_line_edit_never_writes_back() = runTest {
        val w = world(UnconfinedTestDispatcher(testScheduler))
        w.seed()
        val product = w.products.saveLocal(
            Product(
                id = "p1",
                values = FieldValues.EMPTY.withString("description", "Design hour").withString("unit", "hour")
                    .withString("unit_price", "150000").withString("tax_rate", "18"),
                updatedAtMillis = now,
            ),
        )
        val invoice = w.repo.create("classic", 1, classic, settingsForm)

        val lineId = (w.repo.addLine(invoice.id, lineColumns, product, settingsForm) as AppResult.Success).value
        val line = w.invoices.getById(invoice.id)!!.lines.single()
        assertEquals(lineId, line.id)
        // Classic has no Unit column, but the product's unit still travels with the line.
        assertEquals("hour", line.values.string("unit"))
        assertEquals("1", line.values.string("quantity"), "quantity gets its column default")

        w.repo.setLineField(invoice.id, lineId, lineColumns.single { it.id == "unit_price" }, JsonPrimitive("99900"))
        assertEquals("150000", w.products.getById("p1")!!.values.string("unit_price"))
        assertEquals(1L, w.products.getById("p1")!!.usageCount)
        // 1 × ₹999.00 at 18%: the edit recomputed the totals.
        assertEquals(117_882L, w.invoices.getById(invoice.id)!!.computed.grandTotal)
    }

    @Test
    fun issuing_adds_new_line_descriptions_to_the_catalogue_and_leaves_known_ones_alone() = runTest {
        val w = world(UnconfinedTestDispatcher(testScheduler))
        w.seed()
        w.products.saveLocal(Product("known", FieldValues.EMPTY.withString("description", "Logo design").withString("unit_price", "500000"), updatedAtMillis = now))
        val invoice = w.repo.create("classic", 1, classic, settingsForm)
        val description = lineColumns.single { it.id == "description" }
        val price = lineColumns.single { it.id == "unit_price" }
        listOf("Logo design" to "1", "Business cards" to "2500").forEach { (name, rate) ->
            val lineId = (w.repo.addLine(invoice.id, lineColumns, null, settingsForm) as AppResult.Success).value
            w.repo.setLineField(invoice.id, lineId, description, JsonPrimitive(name))
            w.repo.setLineField(invoice.id, lineId, price, JsonPrimitive(rate))
        }

        w.repo.markIssued(invoice.id)

        assertEquals("500000", w.products.getById("known")!!.values.string("unit_price"), "an existing product is never changed")
        val added = assertNotNull(w.products.byNameKey("business cards"))
        assertEquals("2500", added.values.string("unit_price"))
        assertNull(added.values["quantity"], "a product never carries a quantity")
    }
}
