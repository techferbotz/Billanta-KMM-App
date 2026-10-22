package com.ferbotz.billanta.render

import com.ferbotz.billanta.fields.FieldValues
import com.ferbotz.billanta.records.Invoice
import com.ferbotz.billanta.records.InvoiceLine
import com.ferbotz.billanta.render.layout.DrawCommand
import com.ferbotz.billanta.render.layout.PlaceholderMode
import com.ferbotz.billanta.render.layout.RenderedDocument
import com.ferbotz.billanta.render.layout.RenderedPage
import com.ferbotz.billanta.render.text.FakeTextShaper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The foundation of the section-by-section editor: the renderer has to say *where* each section
 * landed, and hold space open for one that has nothing in it yet — but only while editing, never in
 * a file the customer receives.
 */
class SectionBoundsTest {

    private val renderer = InvoiceRenderer(FakeTextShaper())
    private val classic get() = TestInvoices.classic

    /** A filled-in invoice. */
    private fun full(): Invoice = Invoice(
        id = "inv-1",
        values = FieldValues.EMPTY
            .withString("invoice_number", "INV-1")
            .withString("invoice_date", "2026-08-06")
            .withString("company_name", "Studio Nine")
            .withString("company_state", "27")
            .withString("company_upi_id", "studio@okbank")
            .withString("customer", "c1")
            .withString("customer_name", "Kavya Iyer")
            .withString("customer_state", "27")
            .withString("notes", "Thanks!"),
        lines = listOf(
            InvoiceLine(
                "l1",
                FieldValues.EMPTY.withString("description", "Design").withString("quantity", "1")
                    .withString("unit_price", "100000").withString("tax_rate", "18"),
            ),
        ),
        updatedAtMillis = 1L,
    ).recomputed()

    /** A freshly created invoice: no customer, no items, no notes. */
    private fun empty(): Invoice = Invoice(
        id = "inv-1",
        values = FieldValues.EMPTY
            .withString("invoice_number", "INV-1")
            .withString("invoice_date", "2026-08-06")
            .withString("company_name", "Studio Nine")
            .withString("company_state", "27"),
        updatedAtMillis = 1L,
    ).recomputed()

    private fun render(
        doc: TemplateDoc,
        invoice: Invoice,
        theme: InvoiceTheme = InvoiceTheme.NONE,
        placeholders: PlaceholderMode = PlaceholderMode.None,
    ): RenderedDocument = renderer.render(doc, invoice.renderContext(), TestInvoices.optionLists, theme, placeholders)

    private fun editing(doc: TemplateDoc, invoice: Invoice, heightPt: Float = 56f) = render(
        doc,
        invoice,
        placeholders = PlaceholderMode.Reserve(emptySectionsFor(doc, invoice.renderContext()), heightPt),
    )

    private fun textOf(document: RenderedDocument) = document.pages.flatMap { it.commands }
        .filterIsInstance<DrawCommand.Text>()
        .flatMap { it.paragraph.lines }.flatMap { line -> line.runs.map { it.text } }

    @Test
    fun every_rendered_section_reports_where_it_is() {
        val bounds = render(classic, full()).pages.flatMap { it.sections }

        assertTrue(bounds.isNotEmpty(), "no section bounds reported at all")
        val declared = classic.sections.map { it.id }.toSet()
        bounds.forEach { section ->
            assertTrue(section.id in declared, "reported an undeclared section '${section.id}'")
            assertTrue(section.rect.width > 0f, "${section.id} has no width")
            assertTrue(section.rect.height > 0f, "${section.id} has no height")
        }
        // Not `all {}` — that passes vacuously when nothing reports at all.
        val items = bounds.filter { it.id == "items" }
        assertTrue(items.isNotEmpty(), "the items section reported no bounds on a filled invoice")
        assertTrue(items.none { it.isEmpty }, "items has content, so it must not be reported empty")
    }

    /** Tapping the invoice has to land somewhere for every section the user can edit. */
    @Test
    fun every_editable_section_reports_bounds_on_a_filled_invoice() {
        val reported = render(classic, full()).pages.flatMap { it.sections }.map { it.id }.toSet()
        classic.sections.filter { it.isEditable }.forEach { section ->
            assertTrue(section.id in reported, "'${section.id}' is editable but reported no bounds, so it cannot be tapped")
        }
    }

    @Test
    fun bounds_sit_inside_the_printable_area() {
        render(classic, full()).pages.forEach { page ->
            page.sections.forEach { section ->
                assertTrue(section.rect.x >= classic.page.marginLeftPt - 0.5f, "${section.id} starts left of the margin")
                assertTrue(
                    section.rect.right <= PageSpec.A4_WIDTH_PT - classic.page.marginRightPt + 0.5f,
                    "${section.id} runs past the right margin",
                )
            }
        }
    }

    /** A small template whose sections list their fields, so the assertions read on their own. */
    private fun editableTemplate(): TemplateDoc = assertNotNull(
        TemplateParser.parse(
            """
            { "schemaVersion": 2, "compilerVersion": 2,
              "page": { "size": "A4", "margin": { "top": 36, "right": 36, "bottom": 36, "left": 36 },
                        "fontFamily": "Inter", "baseFontSize": 11 },
              "sections": [
                { "id": "parties", "label": "Bill to", "hidable": false, "fields": [
                    { "id": "customer", "owner": "invoice", "type": "customer", "label": "Customer" },
                    { "id": "customer_name", "owner": "customer", "type": "text", "label": "Name" } ] },
                { "id": "items", "label": "Items", "hidable": false, "fields": [
                    { "id": "line_items", "owner": "invoice", "type": "line_items", "label": "Items", "columns": [
                        { "id": "description", "owner": "line", "type": "text", "label": "Item" } ] } ] },
                { "id": "notes", "label": "Notes", "hidable": true, "fields": [
                    { "id": "notes", "owner": "invoice", "type": "multiline", "label": "Notes" } ] }
              ],
              "root": { "type": "box", "style": {}, "children": [
                { "type": "box", "section": "parties", "style": {}, "children": [
                  { "type": "text", "style": {}, "spans": [ { "value": { "kind": "literal", "text": "BILL TO" } } ] },
                  { "type": "text", "style": {}, "spans": [ { "value": { "kind": "bind", "path": "customer_name", "format": "text", "fallback": "" } } ] } ] },
                { "type": "box", "section": "items", "style": {}, "children": [
                  { "type": "text", "style": {}, "spans": [ { "value": { "kind": "literal", "text": "ITEMS" } } ] } ] },
                { "type": "box", "section": "notes", "style": {}, "children": [
                  { "type": "text", "style": {}, "spans": [ { "value": { "kind": "bind", "path": "notes", "format": "text", "fallback": "" } } ] } ] }
              ] } }
            """,
        ),
    )

    /** Editing needs somewhere to put the "tap to add" box. */
    @Test
    fun an_empty_section_reserves_space_only_when_editing() {
        val doc = editableTemplate()
        assertEquals(
            setOf("parties", "items", "notes"),
            emptySectionsFor(doc, empty().renderContext()),
            "a brand-new invoice has no customer, items or notes",
        )
        val editing = editing(doc, empty())
        val reserved = editing.pages.flatMap { it.sections }.filter { it.isEmpty }
        assertTrue(reserved.isNotEmpty(), "an empty invoice should reserve at least one placeholder")
        reserved.forEach { assertTrue(it.rect.height >= 56f - 0.5f, "${it.id} reserved only ${it.rect.height}pt") }

        // The heading is replaced outright, so the user sees one box to tap rather than a stranded
        // "BILL TO" label with nothing under it.
        assertTrue(textOf(editing).none { it.contains("BILL TO") }, "an empty section should not keep its heading")

        val exported = render(doc, empty())
        assertTrue(exported.pages.flatMap { it.sections }.none { it.isEmpty }, "an export must never hold space open for an empty section")
        // The export keeps whatever the template does draw for an empty section.
        assertTrue(textOf(exported).any { it.contains("BILL TO") }, "the exported invoice should still render the template's own heading")
    }

    @Test
    fun a_filled_invoice_reserves_nothing_even_when_editing() {
        val empties = editing(editableTemplate(), full()).pages.flatMap { it.sections }.filter { it.isEmpty }.map { it.id }
        assertTrue(empties.none { it in setOf("items", "parties") }, "items and parties have content, so they must not be placeholders: $empties")
    }

    @Test
    fun a_hidden_section_reports_no_bounds() {
        val document = render(classic, full(), theme = InvoiceTheme(hiddenSections = setOf("payment")))
        assertTrue(
            document.pages.flatMap { it.sections }.none { it.id == "payment" },
            "a section switched off should not be reported, or the editor would offer to fill it",
        )
    }

    /**
     * Every empty section must end up somewhere the user can see and tap — whichever side supplied
     * the empty state: Classic authors its own for the items, and the app synthesises the rest.
     */
    @Test
    fun every_empty_section_lands_somewhere_tappable() {
        val expected = emptySectionsFor(classic, empty().renderContext())
        assertTrue(expected.isNotEmpty(), "a blank invoice should have empty sections")

        val bounds = editing(classic, empty()).pages.flatMap { it.sections }
        expected.forEach { id ->
            val forSection = bounds.filter { it.id == id }
            assertTrue(forSection.isNotEmpty(), "'$id' is empty but landed nowhere to tap")
            assertTrue(forSection.any { it.rect.width > 0f && it.rect.height > 0f }, "'$id' reported a zero-sized rect")
        }
        bounds.filter { it.isEmpty }.forEach {
            assertTrue(it.rect.height >= 56f - 0.5f, "${it.id} reserved only ${it.rect.height}pt")
            assertTrue(it.rect.width > 0f, "${it.id} reserved no width")
        }
    }

    /** The items section specifically — the one a new invoice most needs to offer. */
    @Test
    fun an_invoice_with_no_items_can_still_reach_the_items_section() {
        val bounds = editing(classic, empty()).pages.flatMap { it.sections }.filter { it.id == "items" }
        assertTrue(bounds.isNotEmpty(), "the items section reported nothing to tap")
        assertTrue(bounds.any { it.rect.height > 0f }, "the items section collapsed to nothing on an empty invoice")
    }

    /** Every section reported empty must have something to edit, or the "add" box leads nowhere. */
    @Test
    fun only_sections_with_fields_are_ever_reported_empty() {
        val editable = classic.sections.filter { it.isEditable }.map { it.id }
        emptySectionsFor(classic, empty().renderContext()).forEach { id ->
            assertTrue(id in editable, "reported '$id' empty although it lists no fields")
        }
    }

    // ---- tapping the invoice --------------------------------------------------------------------

    /** Mirrors the preview's hit test: the smallest rect containing the point wins. */
    private fun RenderedPage.sectionAt(x: Float, y: Float) =
        sections.filter { x >= it.rect.x && x <= it.rect.right && y >= it.rect.y && y <= it.rect.bottom }
            .minByOrNull { it.rect.width * it.rect.height }

    /**
     * Classic tags `totals` on rows inside the items table, so a tap on a totals row falls in both
     * rects; the hit test takes the smallest, which is the one the user aimed at.
     */
    @Test
    fun a_tap_resolves_to_the_most_specific_section() {
        val page = render(classic, full()).pages.first()
        val totals = assertNotNull(page.sections.firstOrNull { it.id == "totals" }, "classic should report bounds for its totals")
        val items = assertNotNull(page.sections.firstOrNull { it.id == "items" }, "classic should report bounds for its items")

        val nested = totals.rect.x >= items.rect.x - 0.5f && totals.rect.right <= items.rect.right + 0.5f &&
            totals.rect.y >= items.rect.y - 0.5f && totals.rect.bottom <= items.rect.bottom + 0.5f
        if (nested) {
            val hit = page.sectionAt(totals.rect.x + totals.rect.width / 2, totals.rect.y + totals.rect.height / 2)
            assertEquals("totals", hit?.id, "a tap on the totals resolved to the enclosing section")
        }
        val insideItems = page.sectionAt(items.rect.x + items.rect.width / 2, items.rect.y + 1f)
        assertEquals("items", insideItems?.id, "a tap at the top of the items table should mean items")
    }

    /** A tap in the page margin belongs to no section, so the caller falls back to the list. */
    @Test
    fun a_tap_outside_every_section_resolves_to_nothing() {
        assertNull(render(classic, full()).pages.first().sectionAt(2f, 2f), "the top-left page margin should not resolve to a section")
    }
}
