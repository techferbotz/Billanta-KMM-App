package com.ferbotz.billanta.data.api

import com.ferbotz.billanta.core.BillantaJson
import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.domain.model.InvoiceDocStatus
import com.ferbotz.billanta.fields.Be021Fixtures
import com.ferbotz.billanta.fields.FieldValues
import com.ferbotz.billanta.records.Customer
import com.ferbotz.billanta.records.Invoice
import com.ferbotz.billanta.records.InvoiceLine
import com.ferbotz.billanta.records.Product
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What we send must be exactly what the backend's own examples send (`records.json`,
 * `invoice.json`), compared as JSON so key order does not matter but every key and value does.
 */
class ValuesWireTest {

    private val records = BillantaJson.parseToJsonElement(Be021Fixtures.text("records.json")).jsonObject
    private val invoiceTrip = BillantaJson.parseToJsonElement(Be021Fixtures.text("invoice.json"))
        .jsonObject.getValue("POST /invoices").jsonObject

    private fun request(route: String) = records.getValue(route).jsonObject.getValue("request").jsonObject

    @Test
    fun customer_and_product_creates_match_the_examples() {
        val customerRequest = request("POST /customers")
        val customer = Customer(
            id = customerRequest.getValue("id").jsonPrimitive.content,
            values = FieldValues.of(customerRequest.getValue("values").jsonObject),
        )
        assertEquals(customerRequest, BillantaJson.encodeToJsonElement(EntityValuesDto.serializer(), customer.toDto()))

        val productRequest = request("POST /products")
        val product = Product(
            id = productRequest.getValue("id").jsonPrimitive.content,
            values = FieldValues.of(productRequest.getValue("values").jsonObject),
        )
        assertEquals(productRequest, BillantaJson.encodeToJsonElement(EntityValuesDto.serializer(), product.toDto()))
    }

    @Test
    fun a_settings_patch_carries_only_the_changed_keys() {
        val patch = request("PATCH /settings")
        val changed = patch.getValue("values").jsonObject
        // The device holds the whole map; only the three keys the user changed go up.
        val local = FieldValues.of(changed)
            .withString("settings_invoice_prefix", "INV-")
            .withString("settings_next_invoice_number", "7")
        assertEquals(patch, BillantaJson.encodeToJsonElement(ValuesBodyDto.serializer(), local.patchBody(changed.keys)))
    }

    @Test
    fun a_cleared_key_patches_as_null() {
        val body = FieldValues.EMPTY.withString("company_name", "Acme").patchBody(setOf("company_logo", "company_name"))
        assertEquals(
            BillantaJson.parseToJsonElement("""{"values":{"company_logo":null,"company_name":"Acme"}}"""),
            BillantaJson.encodeToJsonElement(ValuesBodyDto.serializer(), body),
        )
    }

    @Test
    fun the_invoice_post_matches_the_example() {
        val request = invoiceTrip.getValue("request").jsonObject
        val invoice = Invoice(
            id = request.getValue("id").jsonPrimitive.content,
            status = InvoiceDocStatus.Pending,
            templateId = "classic",
            templateVersion = 1,
            values = FieldValues.of(request.getValue("values").jsonObject),
            lines = request.getValue("lineItems").jsonArray.map {
                InvoiceLine(it.jsonObject.getValue("id").jsonPrimitive.content, FieldValues.of(it.jsonObject.getValue("values").jsonObject))
            },
            hiddenSections = setOf("notes"),
            themeOverrides = mapOf("accent" to "#0f766e"),
            updatedAtMillis = Iso8601.parseOrNull(request.getValue("updatedAt").jsonPrimitive.content)!!,
        )
        assertEquals(request, BillantaJson.encodeToJsonElement(InvoiceValuesDto.serializer(), invoice.toDto()))
    }

    @Test
    fun the_invoice_response_reads_back_with_the_servers_totals() {
        val response = invoiceTrip.getValue("response").jsonObject
        val invoice = BillantaJson.decodeFromJsonElement(InvoiceValuesDto.serializer(), response).toInvoice(0L)

        fun JsonObject.strings() = mapValues { (_, v) -> (v as JsonPrimitive).content }
        assertEquals(response.getValue("computed").jsonObject.strings(), invoice.computed.totalsMap())
        assertEquals(
            response.getValue("lineItems").jsonArray.map { it.jsonObject.getValue("computed").jsonObject.strings() },
            invoice.lines.map { it.computed!!.toMap() },
        )
        assertEquals(InvoiceDocStatus.Pending, invoice.status)
        assertEquals(setOf("notes"), invoice.hiddenSections)
        assertEquals(mapOf("accent" to "#0f766e"), invoice.themeOverrides)
        assertEquals(44, invoice.values.size)
        assertEquals("INV-0042", invoice.invoiceNumber)
    }

    @Test
    fun line_items_never_travel_as_a_value() {
        val invoice = Invoice(id = "i", values = FieldValues.EMPTY.withString("notes", "x"))
        val withStray = invoice.copy(values = invoice.values.with("line_items", JsonPrimitive("oops")))
        assertEquals(setOf("notes"), withStray.toDto().values.keys)
    }
}
