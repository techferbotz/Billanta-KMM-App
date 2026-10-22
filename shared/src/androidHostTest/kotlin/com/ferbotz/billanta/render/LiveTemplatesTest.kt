package com.ferbotz.billanta.render

import com.ferbotz.billanta.fields.FieldValues
import com.ferbotz.billanta.records.Invoice
import com.ferbotz.billanta.render.layout.DrawCommand
import com.ferbotz.billanta.render.layout.LBox
import com.ferbotz.billanta.render.layout.LCell
import com.ferbotz.billanta.render.layout.LNode
import com.ferbotz.billanta.render.layout.LPlaceholder
import com.ferbotz.billanta.render.layout.LRow
import com.ferbotz.billanta.render.layout.LTable
import com.ferbotz.billanta.render.layout.LText
import com.ferbotz.billanta.render.layout.LayoutEngine
import com.ferbotz.billanta.render.layout.PlaceholderMode
import com.ferbotz.billanta.render.layout.TemplateFlattener
import com.ferbotz.billanta.render.layout.flattenCommands
import com.ferbotz.billanta.render.text.FakeTextShaper
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The templates production published when BE-021 went live (BE-022): amber, amethyst and cobalt,
 * each at version 1 with `schemaVersion: 2`. Stored under `androidHostTest/resources/be022` as
 * `GET /templates/{id}/compiled` served them, minus the envelope, as the app caches them. Classic
 * was only the contract's example; these are the templates people actually pick.
 */
class LiveTemplatesTest {

    private val renderer = InvoiceRenderer(FakeTextShaper())

    private fun doc(id: String): TemplateDoc {
        val file = listOf(
            File("src/androidHostTest/resources/be022/$id.compiled.json"),
            File("shared/src/androidHostTest/resources/be022/$id.compiled.json"),
        ).first { it.exists() }
        return assertNotNull(TemplateParser.parse(file.readText()), "$id did not parse")
    }

    private val invoice = TestInvoices.invoice(itemCount = 2)

    private fun textOf(doc: TemplateDoc, invoice: Invoice): String =
        renderer.render(doc, invoice.renderContext(), TestInvoices.optionLists).pages
            .flatMap { it.commands.flattenCommands() }
            .filterIsInstance<DrawCommand.Text>()
            .flatMap { it.paragraph.lines }
            .joinToString("\n") { line -> line.runs.joinToString("") { it.text } }

    @Test
    fun each_template_lists_its_sections_in_editor_order() {
        assertEquals(
            listOf("header", "business", "parties", "items", "totals", "notes", "payment", "terms", "signature"),
            doc("amber").sections.map { it.id },
        )
        assertEquals(
            listOf("header", "business", "parties", "items", "totals", "payment", "signature"),
            doc("amethyst").sections.map { it.id },
        )
        assertEquals(
            listOf("header", "business", "parties", "items", "totals", "notes", "payment", "terms"),
            doc("cobalt").sections.map { it.id },
        )
    }

    @Test
    fun each_template_draws_a_whole_invoice() {
        for (id in listOf("amber", "amethyst", "cobalt")) {
            // Narrow columns wrap the description, so compare words rather than lines.
            val text = textOf(doc(id), invoice).replace('\n', ' ')
            for (expected in listOf(
                "Studio Nine", "INV-2026-0042", "Kavya Iyer", "Brand identity design work, phase 2", "₹68,000.00",
                "HDFC0000123", "Maharashtra",
            )) {
                assertTrue(expected in text, "$id is missing \"$expected\" in:\n$text")
            }
            // Amethyst has no UPI field; the other two print it (BE-022: "a UPI-only business now
            // shows its UPI line").
            assertEquals(id != "amethyst", "studionine@okhdfcbank" in text, "$id's UPI line")
        }
    }

    /** BE-022 names Amethyst as the template to test column hiding against. */
    @Test
    fun amethyst_drops_its_tax_column_when_asked() {
        val doc = doc("amethyst")

        fun table(invoice: Invoice): LTable =
            assertNotNull(tableIn(TemplateFlattener.flatten(doc, TestInvoices.bindings(doc, invoice))))

        val shown = table(invoice)
        assertEquals(5, shown.columns.size)
        assertEquals(listOf("SERVICES", "QTY.", "RATE", "TAX", "AMOUNT"), shown.header.single().cellTexts())
        assertTrue(shown.body.all { "18%" in it.cellTexts() }, "every line should show its tax rate")

        val hidden = table(invoice.copy(values = invoice.values.with("hide_gst_column", JsonPrimitive(true))))
        assertEquals(4, hidden.columns.size)
        assertEquals(listOf("SERVICES", "QTY.", "RATE", "AMOUNT"), hidden.header.single().cellTexts())
        assertFalse(hidden.body.any { "18%" in it.cellTexts() }, "the tax rate went with its column")
        assertEquals(shown.body.size, hidden.body.size, "hiding a column keeps every line")
    }

    // ---- the editor's "tap to add" boxes on a brand-new invoice ----------------------------------

    /** What a first invoice looks like before anything is filled in: no profile, customer or lines. */
    private val newInvoice = Invoice(
        id = "inv-new",
        values = FieldValues.EMPTY
            .withString("invoice_number", "INV-1")
            .withString("invoice_date", "2026-10-22")
            .withString("currency", "INR"),
        updatedAtMillis = 1L,
    )

    private fun editing(doc: TemplateDoc, invoice: Invoice): LNode = TemplateFlattener.flatten(
        doc,
        TestInvoices.bindings(doc, invoice),
        placeholders = PlaceholderMode.Reserve(emptySectionsFor(doc, invoice.renderContext())),
    )

    private fun placeholdersIn(node: LNode): List<String> = when (node) {
        is LPlaceholder -> listOfNotNull(node.section)
        is LBox -> node.children.flatMap(::placeholdersIn)
        is LTable -> node.allRows.flatMap(::placeholdersIn)
        is LRow -> node.cells.flatMap(::placeholdersIn)
        is LCell -> node.children.flatMap(::placeholdersIn)
        else -> emptyList()
    }

    /**
     * Amethyst nests its `header` (number and date) inside `business`. Replacing the empty business
     * block wholesale took the invoice number off a new user's first invoice.
     */
    @Test
    fun an_empty_business_block_does_not_take_the_invoice_number_with_it() {
        val doc = doc("amethyst")
        val texts = textsIn(editing(doc, newInvoice)).joinToString("\n")
        assertTrue("Invoice No.: INV-1" in texts, "the number is missing:\n$texts")
        assertTrue("TAX INVOICE" in texts, "the business block itself should still draw:\n$texts")
    }

    @Test
    fun a_section_with_its_own_empty_state_gets_no_second_prompt() {
        val doc = doc("amethyst")
        val tree = editing(doc, newInvoice)
        // The template's prompt sits beside the signature block, not inside it.
        assertTrue("signature" in doc.sectionsWithOwnEmptyState)
        assertTrue("+ Add a signature" in textsIn(tree).joinToString("\n"))
        assertFalse("signature" in placeholdersIn(tree), "the app drew its own signature box as well")
    }

    @Test
    fun each_empty_section_gets_at_most_one_prompt() {
        for (id in listOf("amber", "amethyst", "cobalt")) {
            val doc = doc(id)
            val placeholders = placeholdersIn(editing(doc, newInvoice))
            assertEquals(placeholders.distinct(), placeholders, "$id repeats a placeholder")
            assertTrue(
                placeholders.none { it in doc.sectionsWithOwnEmptyState },
                "$id doubles the template's own empty state: $placeholders",
            )
        }
        // Cobalt tags both its company block and its logo `business`.
        assertEquals(1, placeholdersIn(editing(doc("cobalt"), newInvoice)).count { it == "business" })
    }

    /** Cobalt's company block is 58% of a flex row; a placeholder with no width of its own vanished. */
    @Test
    fun a_placeholder_keeps_the_width_of_the_block_it_stands_in_for() {
        val doc = doc("cobalt")
        val page = LayoutEngine(FakeTextShaper()).render(editing(doc, newInvoice), doc.page).pages.single()
        val business = page.sections.single { it.id == "business" && it.isEmpty }
        val contentWidth = LayoutEngine.pageContentWidth(doc.page)
        assertTrue(
            business.rect.width in contentWidth * 0.5f..contentWidth * 0.6f,
            "the business placeholder is ${business.rect.width}pt of a ${contentWidth}pt page",
        )
    }

    private fun tableIn(node: LNode): LTable? = when (node) {
        is LTable -> node
        is LBox -> node.children.firstNotNullOfOrNull(::tableIn)
        is LRow -> node.cells.firstNotNullOfOrNull(::tableIn)
        is LCell -> node.children.firstNotNullOfOrNull(::tableIn)
        else -> null
    }

    private fun LRow.cellTexts(): List<String> = cells.map { cell -> textsIn(cell).joinToString(" ") }

    private fun textsIn(node: LNode): List<String> = when (node) {
        is LText -> listOf(node.runs.joinToString("") { it.text })
        is LBox -> node.children.flatMap(::textsIn)
        is LTable -> node.allRows.flatMap(::textsIn)
        is LRow -> node.cells.flatMap(::textsIn)
        is LCell -> node.children.flatMap(::textsIn)
        else -> emptyList()
    }
}
