package com.ferbotz.billanta.render

import com.ferbotz.billanta.core.BillantaJson
import com.ferbotz.billanta.fields.Be021Fixtures
import com.ferbotz.billanta.fields.FieldValues
import com.ferbotz.billanta.render.layout.DrawCommand
import com.ferbotz.billanta.render.layout.LCell
import com.ferbotz.billanta.render.layout.LTable
import com.ferbotz.billanta.render.layout.LText
import com.ferbotz.billanta.render.layout.LBox
import com.ferbotz.billanta.render.layout.PlaceholderMode
import com.ferbotz.billanta.render.layout.RenderedDocument
import com.ferbotz.billanta.render.layout.TemplateFlattener
import com.ferbotz.billanta.render.layout.flattenCommands
import com.ferbotz.billanta.render.text.FakeTextShaper
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The v2 renderer against the backend's own example: Classic drawn with `render-context.json`,
 * which the backend rendered into `template-classic.thumbnail.png` (FIELDS_APP_GUIDE.md §11).
 */
class Be021RenderTest {

    private val renderer = InvoiceRenderer(FakeTextShaper())
    private val classic = assertNotNull(TemplateParser.parse(Be021Fixtures.text("template-classic.compiled.json")))
    private val context = BillantaJson.parseToJsonElement(Be021Fixtures.text("render-context.json")).jsonObject
    private val optionLists = Be021Fixtures.forms.optionLists

    private fun textOf(document: RenderedDocument): String = document.pages
        .flatMap { it.commands.flattenCommands() }
        .filterIsInstance<DrawCommand.Text>()
        .flatMap { it.paragraph.lines }
        .joinToString("\n") { line -> line.runs.joinToString("") { it.text } }

    private fun render(
        ctx: JsonObject = context,
        theme: InvoiceTheme = InvoiceTheme.NONE,
    ) = textOf(renderer.render(classic, ctx, optionLists, theme))

    private fun JsonObject.with(vararg pairs: Pair<String, String>) =
        JsonObject(this + pairs.map { (k, v) -> k to JsonPrimitive(v) })

    @Test
    fun classic_draws_the_example_invoice() {
        val text = render()
        for (expected in listOf(
            "Kaveri Craft Studio", "INV-0042", "Meridian Home Stores LLP", "GSTIN: 29PQRSX5678K1Z3",
            "Brand identity design", "₹45,000.00", "₹12.50", "₹78,500.00", "₹13,025.00", "₹89,025.00",
            "Discount", "₹2,500.00", "UPI: kavericraft@example", "15 Sep 2026",
        )) {
            assertTrue(expected in text, "missing \"$expected\" in:\n$text")
        }
        // `notes` is in the context and its section is not hidden, so it prints.
        assertTrue("Thank you for your business" in text)
    }

    @Test
    fun a_zero_discount_hides_the_discount_row() {
        val text = render(context.with("discount_total" to "0"))
        assertFalse("Discount" in text)
        assertTrue("₹89,025.00" in text)
    }

    @Test
    fun only_hidable_sections_can_be_hidden() {
        // `header` is not hidable, so the invoice number survives; `notes` is, so it goes.
        val text = render(theme = InvoiceTheme(hiddenSections = setOf("notes", "header")))
        assertTrue("INV-0042" in text)
        assertFalse("Thank you for your business" in text)
    }

    @Test
    fun the_logo_is_bound_through_its_url() {
        assertEquals(
            listOf("https://billanta-media.s3.ap-south-1.amazonaws.com/media/00000000-0000-4000-8000-0000000000aa/company_logo.webp"),
            renderer.imageUrlsFor(classic, context),
        )
    }

    @Test
    fun empty_sections_are_judged_by_their_data_fields() {
        assertEquals(emptySet(), emptySectionsFor(classic, context))

        val blank = renderContextOf(
            values = FieldValues.EMPTY.withString("invoice_number", "INV-1"),
            lines = emptyList(),
            totals = mapOf("grand_total" to "0"),
        )
        // The totals block is made of a discount and a computed total: never "missing".
        assertEquals(setOf("business", "parties", "items", "payment", "notes"), emptySectionsFor(classic, blank))
    }

    // ---- synthetic trees for what Classic does not exercise ----------------------------------

    private val gstTable = """
        {"schemaVersion":2,"compilerVersion":2,"page":{"size":"A4"},
         "sections":[{"id":"items","label":"Items","hidable":false,"fields":[
            {"id":"line_items","owner":"invoice","type":"line_items","label":"Items","columns":[
              {"id":"unit","owner":"line","type":"select","label":"Unit","options":[{"value":"hr","label":"Hours"}]}]},
            {"id":"customer_state","owner":"customer","type":"select","label":"State","options":"indian_states"}]}],
         "root":{"type":"box","style":{},"children":[
           {"type":"text","style":{},"spans":[{"value":{"kind":"bind","path":"customer_state","format":"text","fallback":""}}]},
           {"type":"table","style":{},
            "columns":[{"width":"auto"},{"width":45,"visibleIf":{"path":"hide_gst_column","negate":true}},{"width":70}],
            "header":[{"type":"row","style":{},"cells":[
               {"type":"cell","style":{},"colSpan":1,"children":[{"type":"text","style":{},"spans":[{"value":{"kind":"literal","text":"Item"}}]}]},
               {"type":"cell","style":{},"colSpan":1,"children":[{"type":"text","style":{},"spans":[{"value":{"kind":"literal","text":"GST"}}]}]},
               {"type":"cell","style":{},"colSpan":1,"children":[{"type":"text","style":{},"spans":[{"value":{"kind":"literal","text":"Amount"}}]}]}]}],
            "body":{"repeat":{"path":"line_items","as":"item"},"row":{"type":"row","style":{},"cells":[
               {"type":"cell","style":{},"colSpan":1,"children":[{"type":"text","style":{},"spans":[{"value":{"kind":"bind","path":"item.unit","format":"text","fallback":""}}]}]},
               {"type":"cell","style":{},"colSpan":1,"children":[{"type":"text","style":{},"spans":[{"value":{"kind":"bind","path":"item.tax_rate","format":"number","fallback":""}}]}]},
               {"type":"cell","style":{},"colSpan":1,"children":[{"type":"text","style":{},"spans":[{"value":{"kind":"bind","path":"item.line_total","format":"currency","fallback":""}}]}]}]}},
            "footer":[{"type":"row","style":{},"cells":[
               {"type":"cell","style":{},"colSpan":2,"children":[{"type":"text","style":{},"spans":[{"value":{"kind":"literal","text":"Total"}}]}]},
               {"type":"cell","style":{},"colSpan":1,"children":[{"type":"text","style":{},"spans":[{"value":{"kind":"bind","path":"grand_total","format":"currency","fallback":""}}]}]}]}]}]}}
    """.trimIndent()

    private fun gstContext(hideGst: Boolean, state: String = "29") = renderContextOf(
        values = FieldValues.EMPTY
            .withString("customer_state", state)
            .withBool("hide_gst_column", hideGst),
        lines = listOf(
            RenderLine(
                FieldValues.EMPTY.withString("unit", "hr").withString("tax_rate", "18.0"),
                mapOf("line_total" to "100000"),
            ),
        ),
        totals = mapOf("grand_total" to "118000"),
    )

    private fun flattenGst(ctx: JsonObject): LTable {
        val doc = assertNotNull(TemplateParser.parse(gstTable))
        val bindings = BindingContext(ctx, "INR", selectLabels = SelectLabels(doc.sections, optionLists))
        val root = TemplateFlattener.flatten(doc, bindings, InvoiceTheme.NONE, PlaceholderMode.None) as LBox
        return root.children.filterIsInstance<LTable>().single()
    }

    private fun LCell.text(): String = children.filterIsInstance<LText>().joinToString { t -> t.runs.joinToString("") { it.text } }

    @Test
    fun a_hidden_column_drops_its_cells_and_shrinks_spans() {
        val shown = flattenGst(gstContext(hideGst = false))
        assertEquals(3, shown.columns.size)
        assertEquals(listOf("Item", "GST", "Amount"), shown.header.single().cells.map { it.text() })
        assertEquals(listOf(2, 1), shown.footer.single().cells.map { it.colSpan })

        val hidden = flattenGst(gstContext(hideGst = true))
        assertEquals(listOf(null, 70f), hidden.columns.map { it.widthPt })
        assertEquals(listOf("Item", "Amount"), hidden.header.single().cells.map { it.text() })
        assertEquals(listOf("Hours", "₹1,000.00"), hidden.body.single().cells.map { it.text() })
        // "Total" spanned Item + GST; with GST gone it spans Item alone and still sits left of the amount.
        assertEquals(listOf(1, 1), hidden.footer.single().cells.map { it.colSpan })
        assertEquals(listOf("Total", "₹1,180.00"), hidden.footer.single().cells.map { it.text() })
    }

    @Test
    fun a_select_prints_its_label_and_an_unknown_value_prints_raw() {
        fun stateText(ctx: JsonObject): String {
            val doc = assertNotNull(TemplateParser.parse(gstTable))
            val bindings = BindingContext(ctx, "INR", selectLabels = SelectLabels(doc.sections, optionLists))
            val root = TemplateFlattener.flatten(doc, bindings) as LBox
            return (root.children.first() as LText).runs.joinToString("") { it.text }
        }
        assertEquals("Karnataka", stateText(gstContext(hideGst = false, state = "29")))
        assertEquals("99", stateText(gstContext(hideGst = false, state = "99")))
        // A line column's inline options resolve inside the repeat, and a number trims its zeros.
        val body = flattenGst(gstContext(hideGst = false)).body.single().cells.map { it.text() }
        assertEquals(listOf("Hours", "18", "₹1,000.00"), body)
    }
}
