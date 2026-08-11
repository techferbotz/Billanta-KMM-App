package com.ferbotz.billanta.render

import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.domain.model.CompanySnapshot
import com.ferbotz.billanta.domain.model.CustomerSnapshot
import com.ferbotz.billanta.domain.model.InvoiceItemRecord
import com.ferbotz.billanta.domain.model.InvoiceRecord
import com.ferbotz.billanta.render.layout.PlaceholderMode
import com.ferbotz.billanta.render.text.FakeTextShaper
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The foundation for the new section-by-section editor: the renderer has to say *where* each
 * section landed, and hold space open for one that has nothing in it yet — but only while editing,
 * never in a file the customer receives.
 */
class SectionBoundsTest {

    private val renderer = InvoiceRenderer(FakeTextShaper())

    private fun template(name: String): TemplateDoc {
        val file = listOf(
            File("src/androidHostTest/resources/templates/$name.json"),
            File("shared/src/androidHostTest/resources/templates/$name.json"),
        ).firstOrNull { it.exists() }
        assertNotNull(file, "fixture $name.json not found")
        return assertNotNull(TemplateParser.parse(file.readText()))
    }

    /** A filled-in invoice. */
    private fun full() = InvoiceRecord(
        id = "inv-1",
        invoiceNumber = "INV-1",
        invoiceDateMillis = Iso8601.epochMillisFor(2026, 8, 6),
        companySnapshot = CompanySnapshot(name = "Studio Nine", stateCode = "27", upiId = "studio@okbank"),
        customerSnapshot = CustomerSnapshot(name = "Kavya Iyer", stateCode = "27"),
        items = listOf(
            InvoiceItemRecord(
                description = "Design", quantity = "1", unitPricePaise = 100000,
                taxRatePercent = "18", lineTotalPaise = 100000, taxAmountPaise = 18000,
            ),
        ),
        notes = "Thanks!",
        updatedAtMillis = 1L,
    )

    /** A freshly created invoice: no customer, no items, no notes. */
    private fun empty() = InvoiceRecord(
        id = "inv-1",
        invoiceNumber = "INV-1",
        invoiceDateMillis = Iso8601.epochMillisFor(2026, 8, 6),
        companySnapshot = CompanySnapshot(name = "Studio Nine", stateCode = "27"),
        updatedAtMillis = 1L,
    )

    @Test
    fun every_rendered_section_reports_where_it_is() {
        val doc = template("classic")
        val document = renderer.render(doc, full())
        val bounds = document.pages.flatMap { it.sections }

        assertTrue(bounds.isNotEmpty(), "no section bounds reported at all")
        val declared = doc.sections.map { it.id }.toSet()
        bounds.forEach { section ->
            assertTrue(section.id in declared, "reported an undeclared section '${section.id}'")
            assertTrue(section.rect.width > 0f, "${section.id} has no width")
            assertTrue(section.rect.height > 0f, "${section.id} has no height")
        }
        // The sections a full invoice fills should all be reported as non-empty.
        assertTrue(
            bounds.filter { it.id == "items" }.all { !it.isEmpty },
            "the items section should not be reported empty on a filled invoice",
        )
    }

    @Test
    fun bounds_sit_inside_the_printable_area() {
        val doc = template("classic")
        val document = renderer.render(doc, full())
        document.pages.forEach { page ->
            page.sections.forEach { section ->
                assertTrue(section.rect.x >= doc.page.marginLeftPt - 0.5f, "${section.id} starts left of the margin")
                assertTrue(
                    section.rect.right <= PageSpec.A4_WIDTH_PT - doc.page.marginRightPt + 0.5f,
                    "${section.id} runs past the right margin",
                )
            }
        }
    }

    /**
     * A template that declares what its sections edit (APP-007). Written by hand because the seed
     * templates do not carry `edits` yet — this proves the mechanism now, and
     * [real_templates_agree_once_they_declare_edits] picks it up when they do.
     */
    private fun editableTemplate(): TemplateDoc = assertNotNull(
        TemplateParser.parse(
            """
            { "schemaVersion": 1, "compilerVersion": 1,
              "page": { "size": "A4", "margin": { "top": 36, "right": 36, "bottom": 36, "left": 36 },
                        "fontFamily": "Inter", "baseFontSize": 11 },
              "sections": [
                { "id": "parties", "label": "Bill to", "hidable": false, "edits": "customer" },
                { "id": "items",   "label": "Items",   "hidable": false, "edits": "items" },
                { "id": "notes",   "label": "Notes",   "hidable": true,  "edits": "notes" }
              ],
              "root": { "type": "box", "style": {}, "children": [
                { "type": "box", "section": "parties", "style": {}, "children": [
                  { "type": "text", "style": {}, "spans": [ { "value": { "kind": "literal", "text": "BILL TO" } } ] },
                  { "type": "text", "style": {}, "spans": [ { "value": { "kind": "bind", "path": "customer.name", "format": "text", "fallback": "" } } ] } ] },
                { "type": "box", "section": "items", "style": {}, "children": [
                  { "type": "text", "style": {}, "spans": [ { "value": { "kind": "literal", "text": "ITEMS" } } ] } ] },
                { "type": "box", "section": "notes", "style": {}, "children": [
                  { "type": "text", "style": {}, "spans": [ { "value": { "kind": "bind", "path": "invoice.notes", "format": "text", "fallback": "" } } ] } ] }
              ] } }
            """,
        ),
    )

    /** Editing needs somewhere to put the "tap to add" box. */
    @Test
    fun an_empty_section_reserves_space_only_when_editing() {
        val doc = editableTemplate()

        val record = empty()
        val emptySections = emptySectionsFor(doc, record)
        assertEquals(
            setOf("parties", "items", "notes"),
            emptySections,
            "a brand-new invoice has no customer, items or notes",
        )
        val editing = renderer.render(
            doc,
            record,
            placeholders = PlaceholderMode.Reserve(emptySections, heightPt = 56f),
        )
        val reserved = editing.pages.flatMap { it.sections }.filter { it.isEmpty }
        assertTrue(reserved.isNotEmpty(), "an empty invoice should reserve at least one placeholder")
        reserved.forEach {
            assertTrue(it.rect.height >= 56f - 0.5f, "${it.id} reserved only ${it.rect.height}pt")
        }

        // The heading is replaced outright, so the user sees one box to tap rather than a
        // stranded "BILL TO" label with nothing under it.
        val editingText = editing.pages.flatMap { it.commands }
            .filterIsInstance<com.ferbotz.billanta.render.layout.DrawCommand.Text>()
            .flatMap { it.paragraph.lines }.flatMap { l -> l.runs.map { it.text } }
        assertTrue(editingText.none { it.contains("BILL TO") }, "an empty section should not keep its heading")

        val exported = renderer.render(doc, empty(), placeholders = PlaceholderMode.None)
        assertTrue(
            exported.pages.flatMap { it.sections }.none { it.isEmpty },
            "an export must never hold space open for an empty section",
        )
        // The export keeps whatever the template does draw for an empty section — replacing it is
        // an editing affordance only, and must never change the file the customer receives.
        val exportedText = exported.pages.flatMap { it.commands }
            .filterIsInstance<com.ferbotz.billanta.render.layout.DrawCommand.Text>()
            .flatMap { it.paragraph.lines }.flatMap { l -> l.runs.map { it.text } }
        assertTrue(
            exportedText.any { it.contains("BILL TO") },
            "the exported invoice should still render the template's own heading",
        )
    }

    @Test
    fun a_filled_invoice_reserves_nothing_even_when_editing() {
        val doc = editableTemplate()
        val document = renderer.render(
            doc,
            full(),
            placeholders = PlaceholderMode.Reserve(emptySectionsFor(doc, full())),
        )
        val empties = document.pages.flatMap { it.sections }.filter { it.isEmpty }.map { it.id }
        assertTrue(
            empties.none { it in setOf("items", "parties") },
            "items and parties have content, so they must not be placeholders: $empties",
        )
    }

    @Test
    fun a_hidden_section_reports_no_bounds() {
        val doc = template("classic")
        val document = renderer.render(
            doc,
            full(),
            theme = InvoiceTheme(hiddenSections = setOf("payment")),
            placeholders = PlaceholderMode.Reserve(emptySectionsFor(doc, full())),
        )
        assertTrue(
            document.pages.flatMap { it.sections }.none { it.id == "payment" },
            "a section switched off should not be reported, or the editor would offer to fill it",
        )
    }

    /**
     * Tolerant on purpose: the seed templates do not declare `edits` yet (APP-007 is open), so this
     * asserts consistency for whatever they do declare rather than demanding a value that has not
     * shipped. It starts doing real work the moment the backend tags them.
     */
    @Test
    fun real_templates_agree_once_they_declare_edits() {
        listOf("classic", "minimal").forEach { name ->
            val doc = template(name)
            val editable = doc.sections.filter { it.isEditable }
            val empties = emptySectionsFor(doc, empty())
            empties.forEach { id ->
                assertTrue(
                    editable.any { it.id == id },
                    "$name: reported '$id' empty although it declares nothing to edit",
                )
            }
        }
    }

    // ---- APP-007: what each section edits -------------------------------------------------------

    @Test
    fun an_absent_or_unknown_edits_value_is_not_editable() {
        val parsed = assertNotNull(
            TemplateParser.parse(
                """
                { "root": { "type": "box", "style": {}, "children": [] },
                  "sections": [
                    { "id": "a", "label": "A" },
                    { "id": "b", "label": "B", "edits": "hologram" },
                    { "id": "c", "label": "C", "edits": "items" }
                  ] }
                """,
            ),
        )
        assertEquals(SectionEdits.None, parsed.sections[0].edits, "absent means not editable")
        assertEquals(SectionEdits.None, parsed.sections[1].edits, "unknown must not be an error")
        assertEquals(SectionEdits.Items, parsed.sections[2].edits)
        assertEquals(listOf("c"), parsed.sections.filter { it.isEditable }.map { it.id })
    }

    @Test
    fun the_editor_vocabulary_round_trips() {
        SectionEdits.entries.forEach { kind ->
            assertEquals(kind, SectionEdits.fromWire(kind.wireName), "${kind.wireName} did not round-trip")
        }
    }
}
