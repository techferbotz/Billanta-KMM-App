package com.ferbotz.billanta.domain.money

import com.ferbotz.billanta.core.BillantaJson
import com.ferbotz.billanta.fields.Be021Fixtures
import com.ferbotz.billanta.fields.DiscountValue
import com.ferbotz.billanta.fields.FieldValues
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Our totals for the backend's example invoice must equal the totals the server returned for it,
 * to the paise (FIELDS_APP_GUIDE.md §11). If they ever differ, the money implementation has a bug.
 */
class InvoiceMathFixtureTest {

    private val roundTrip = BillantaJson.parseToJsonElement(Be021Fixtures.text("invoice.json"))
        .jsonObject.getValue("POST /invoices").jsonObject
    private val request = roundTrip.getValue("request").jsonObject
    private val response = roundTrip.getValue("response").jsonObject

    private fun JsonObject.strings(): Map<String, String> =
        mapValues { (_, v) -> (v as JsonPrimitive).content }

    @Test
    fun our_totals_equal_the_servers_for_the_example_invoice() {
        val computed = InvoiceMath.compute(
            values = FieldValues.of(request.getValue("values").jsonObject),
            lines = request.getValue("lineItems").jsonArray.map { FieldValues.of(it.jsonObject.getValue("values").jsonObject) },
        )

        assertEquals(response.getValue("computed").jsonObject.strings(), computed.totalsMap())
        assertEquals(
            response.getValue("lineItems").jsonArray.map { it.jsonObject.getValue("computed").jsonObject.strings() },
            computed.lines.map { it.toMap() },
        )
    }

    @Test
    fun an_empty_draft_totals_to_zero_with_igst() {
        val computed = InvoiceMath.compute(FieldValues.EMPTY, listOf(FieldValues.EMPTY))
        assertEquals(0L, computed.grandTotal)
        assertEquals(LineComputed(0, 0), computed.lines.single())
    }

    @Test
    fun malformed_inputs_read_as_absent_instead_of_failing() {
        val line = FieldValues.EMPTY
            .withString("quantity", "two")
            .withString("unit_price", "lots")
            .withString("tax_rate", "180")
        val values = FieldValues.EMPTY.with("discount", DiscountValue(DiscountValue.Kind.Percent, "ten").toJson())
        assertEquals(0L, InvoiceMath.compute(values, listOf(line)).grandTotal)
    }

    @Test
    fun same_state_splits_into_cgst_and_sgst_with_the_odd_paise_on_sgst() {
        val line = FieldValues.EMPTY.withString("quantity", "1").withString("unit_price", "1000").withString("tax_rate", "0.5")
        val same = FieldValues.EMPTY.withString("company_state", "29").withString("customer_state", "29")
        val computed = InvoiceMath.compute(same, listOf(line))
        assertEquals(5L, computed.taxTotal)
        assertEquals(2L to 3L, computed.cgstTotal to computed.sgstTotal)

        val other = same.withString("customer_state", "27")
        assertEquals(5L, InvoiceMath.compute(other, listOf(line)).igstTotal)
    }
}
