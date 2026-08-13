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
import kotlin.test.assertNull
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
        // Not `all {}` — that passes vacuously when nothing reports at all, which is exactly how
        // the missing table bounds hid: `items` is tagged on the `table` node, and LTable carried
        // no section, so a filled invoice reported no items rect for the editor to hit-test.
        val items = bounds.filter { it.id == "items" }
        assertTrue(items.isNotEmpty(), "the items section reported no bounds on a filled invoice")
        assertTrue(items.none { it.isEmpty }, "items has content, so it must not be reported empty")
    }

    /**
     * Tapping the invoice has to land somewhere for every section the user can edit, whatever node
     * type the template tagged — a box, a table or a row.
     */
    @Test
    fun every_editable_section_reports_bounds_on_a_filled_invoice() {
        listOf("classic", "minimal").forEach { name ->
            val doc = template(name)
            val document = renderer.render(doc, full())
            val reported = document.pages.flatMap { it.sections }.map { it.id }.toSet()

            doc.sections.filter { it.isEditable }.forEach { section ->
                assertTrue(
                    section.id in reported,
                    "$name: '${section.id}' is editable but reported no bounds, so it cannot be tapped",
                )
            }
        }
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
     * Every empty section must end up somewhere the user can see and tap — whatever node type the
     * template tagged it on, and whichever side supplied the empty state.
     *
     * This asserts the outcome rather than the mechanism, because since BE-009 there are two ways
     * to get there: the template authors its own empty state (classic's items), or the app
     * synthesises one (everything else). Both are correct; a section with neither is the bug.
     *
     * The regression it was written for: `items` used to be tagged on the `table` node, and the
     * placeholder check only ran for boxes, so a new invoice offered a dashed box for "Bill to" and
     * nothing at all for the items — the section it most needs to offer.
     */
    @Test
    fun every_empty_section_lands_somewhere_tappable() {
        listOf("classic", "minimal").forEach { name ->
            val doc = template(name)
            val expected = emptySectionsFor(doc, empty())
            assertTrue(expected.isNotEmpty(), "$name: a blank invoice should have empty sections")

            val document = renderer.render(
                doc,
                empty(),
                placeholders = PlaceholderMode.Reserve(expected, heightPt = 56f),
            )
            val bounds = document.pages.flatMap { it.sections }

            expected.forEach { id ->
                val forSection = bounds.filter { it.id == id }
                assertTrue(forSection.isNotEmpty(), "$name: '$id' is empty but landed nowhere to tap")
                assertTrue(
                    forSection.any { it.rect.width > 0f && it.rect.height > 0f },
                    "$name: '$id' reported a zero-sized rect, so it cannot be tapped",
                )
            }
            // A synthesised placeholder still has to be big enough to hit comfortably.
            bounds.filter { it.isEmpty }.forEach {
                assertTrue(it.rect.height >= 56f - 0.5f, "$name: ${it.id} reserved only ${it.rect.height}pt")
                assertTrue(it.rect.width > 0f, "$name: ${it.id} reserved no width")
            }
        }
    }

    /** The items section specifically — the one a new invoice most needs to offer. */
    @Test
    fun an_invoice_with_no_items_can_still_reach_the_items_section() {
        listOf("classic", "minimal").forEach { name ->
            val doc = template(name)
            val items = doc.sections.firstOrNull { it.edits == SectionEdits.Items }
            assertNotNull(items, "$name declares no items section")

            val document = renderer.render(
                doc,
                empty(),
                placeholders = PlaceholderMode.Reserve(setOf(items.id), heightPt = 56f),
            )
            val bounds = document.pages.flatMap { it.sections }.filter { it.id == items.id }
            assertTrue(bounds.isNotEmpty(), "$name: the items section reported nothing to tap")
            assertTrue(
                bounds.any { it.rect.height > 0f },
                "$name: the items section collapsed to nothing on an empty invoice",
            )
        }
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

    // ---- tapping the invoice --------------------------------------------------------------------

    /** Mirrors the preview's hit test: the smallest rect containing the point wins. */
    private fun com.ferbotz.billanta.render.layout.RenderedPage.sectionAt(x: Float, y: Float) =
        sections.filter { x >= it.rect.x && x <= it.rect.right && y >= it.rect.y && y <= it.rect.bottom }
            .minByOrNull { it.rect.width * it.rect.height }

    /**
     * Classic tags `totals` on a row *inside* the items table, so a tap on the totals row falls in
     * both rects. Picking the larger one would send the user to the item list instead of the
     * discount editor, which is why the hit test takes the smallest.
     */
    @Test
    fun a_tap_resolves_to_the_most_specific_section() {
        val doc = template("classic")
        val document = renderer.render(doc, full())
        val page = document.pages.first()

        val totals = page.sections.firstOrNull { it.id == "totals" }
        assertNotNull(totals, "classic should report bounds for its totals row")
        val items = page.sections.firstOrNull { it.id == "items" }
        assertNotNull(items, "classic should report bounds for its items table")

        // Only meaningful if they really do overlap — otherwise this proves nothing.
        val nested = totals.rect.x >= items.rect.x - 0.5f && totals.rect.right <= items.rect.right + 0.5f &&
            totals.rect.y >= items.rect.y - 0.5f && totals.rect.bottom <= items.rect.bottom + 0.5f
        if (nested) {
            val hit = page.sectionAt(totals.rect.x + totals.rect.width / 2, totals.rect.y + totals.rect.height / 2)
            assertEquals("totals", hit?.id, "a tap on the totals row resolved to the enclosing section")
        }

        // A tap in the middle of the items table, above the totals row, still means items.
        val insideItems = page.sectionAt(items.rect.x + items.rect.width / 2, items.rect.y + 1f)
        assertEquals("items", insideItems?.id, "a tap inside the items table should mean items")
    }

    /** A tap in the page margin belongs to no section, so the caller falls back to the list. */
    @Test
    fun a_tap_outside_every_section_resolves_to_nothing() {
        val doc = template("classic")
        val page = renderer.render(doc, full()).pages.first()
        assertNull(page.sectionAt(2f, 2f), "the top-left page margin should not resolve to a section")
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
