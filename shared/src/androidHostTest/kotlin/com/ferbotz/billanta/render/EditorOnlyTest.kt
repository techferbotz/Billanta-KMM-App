package com.ferbotz.billanta.render

import com.ferbotz.billanta.fields.FieldValues
import com.ferbotz.billanta.records.Invoice
import com.ferbotz.billanta.records.InvoiceLine
import com.ferbotz.billanta.render.layout.DrawCommand
import com.ferbotz.billanta.render.layout.PlaceholderMode
import com.ferbotz.billanta.render.layout.RenderedDocument
import com.ferbotz.billanta.render.layout.flattenCommands
import com.ferbotz.billanta.render.text.FakeTextShaper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * BE-009: a template can author its own empty state, gated on `data-unless` and marked
 * `editorOnly`. The app renders it while editing and must drop it from anything the customer
 * receives — the same compiled tree feeds the preview and the PDF, so that is the whole safety
 * story for this feature.
 */
class EditorOnlyTest {

    private val renderer = InvoiceRenderer(FakeTextShaper())

    private val classic get() = TestInvoices.classic

    private fun empty() = Invoice(
        id = "inv-1",
        values = FieldValues.EMPTY
            .withString("invoice_number", "INV-1")
            .withString("invoice_date", "2026-08-12")
            .withString("company_name", "Studio Nine")
            .withString("company_state", "27"),
        updatedAtMillis = 1L,
    ).recomputed()

    private fun full() = empty().let {
        it.copy(
            values = it.values.withAll(TestInvoices.customer).withString("notes", "Thanks!"),
            lines = listOf(
                InvoiceLine(
                    "l1",
                    FieldValues.EMPTY.withString("description", "Design").withString("quantity", "1")
                        .withString("unit_price", "100000").withString("tax_rate", "18"),
                ),
            ),
        ).recomputed()
    }

    private fun textOf(document: RenderedDocument): List<String> = document.pages
        .flatMap { it.commands.flattenCommands() }
        .filterIsInstance<DrawCommand.Text>()
        .flatMap { it.paragraph.lines }
        .flatMap { line -> line.runs.map { it.text } }

    private fun render(doc: TemplateDoc, invoice: Invoice, placeholders: PlaceholderMode = PlaceholderMode.None) =
        renderer.render(doc, invoice.renderContext(), TestInvoices.optionLists, placeholders = placeholders)

    private fun editing(doc: TemplateDoc, invoice: Invoice) =
        render(doc, invoice, PlaceholderMode.Reserve(emptySectionsFor(doc, invoice.renderContext())))

    @Test
    fun the_seed_template_ships_an_empty_state() {
        assertTrue(classic.hasEditorOnly, "classic should carry the BE-009 items empty state")
    }

    @Test
    fun an_authored_empty_state_shows_while_editing() {
        val shown = textOf(editing(classic, empty()))
        assertTrue(
            shown.any { it.contains("Add an item", ignoreCase = true) },
            "the template's own empty state did not render while editing: $shown",
        )
    }

    /** The guarantee the whole feature rests on. */
    @Test
    fun an_authored_empty_state_never_reaches_an_export() {
        listOf(empty(), full()).forEach { invoice ->
            val exported = render(classic, invoice)
            val text = textOf(exported)
            assertTrue(
                text.none { it.contains("Add an item", ignoreCase = true) },
                "an editor-only prompt was printed on an exported invoice: $text",
            )
        }
    }

    /**
     * The app used to replace an empty section wholesale with its own grey box. Now that the
     * template can style its own, the app's must stand down — otherwise the author's design is
     * silently discarded.
     */
    @Test
    fun the_apps_generic_placeholder_defers_to_the_templates_own() {
        val document = editing(classic, empty())

        val itemsBounds = document.pages.flatMap { it.sections }.filter { it.id == "items" }
        assertTrue(itemsBounds.isNotEmpty(), "the items section reported no bounds")
        assertTrue(
            itemsBounds.none { it.isEmpty },
            "the app synthesised a placeholder over the template's own empty state",
        )
        assertTrue(
            textOf(document).any { it.contains("Add an item", ignoreCase = true) },
            "the template's empty state was replaced rather than kept",
        )
    }

    /**
     * A section without an authored empty state must keep the app's synthesised one, or the user is
     * left with a section they cannot see and cannot reach. Classic authors one for its items only.
     */
    @Test
    fun a_section_without_an_empty_state_keeps_the_synthesised_one() {
        val invoice = empty()
        val expected = emptySectionsFor(classic, invoice.renderContext())
        assertTrue("parties" in expected && "notes" in expected, "a blank invoice should report its empty sections: $expected")

        val reserved = editing(classic, invoice).pages.flatMap { it.sections }.filter { it.isEmpty }.map { it.id }
        listOf("parties", "notes").forEach {
            assertTrue(it in reserved, "classic: '$it' lost its placeholder and has no authored one")
        }
    }

    @Test
    fun a_negated_gate_is_the_inverse_of_a_plain_one() {
        val doc = assertNotNull(
            TemplateParser.parse(
                """
                { "schemaVersion": 1, "compilerVersion": 1,
                  "page": { "size": "A4", "margin": { "top": 0, "right": 0, "bottom": 0, "left": 0 } },
                  "root": { "type": "box", "style": {}, "children": [
                    { "type": "conditional", "path": "notes", "child": {
                        "type": "text", "style": {},
                        "spans": [ { "value": { "kind": "literal", "text": "HAS NOTES" } } ] } },
                    { "type": "conditional", "path": "notes", "negate": true, "child": {
                        "type": "text", "style": {},
                        "spans": [ { "value": { "kind": "literal", "text": "NO NOTES" } } ] } }
                  ] } }
                """,
            ),
        )

        val withNotes = textOf(render(doc, empty().let { it.copy(values = it.values.withString("notes", "Thanks!")) }))
        assertTrue(withNotes.any { it.contains("HAS NOTES") }, "the plain gate should have opened")
        assertTrue(withNotes.none { it.contains("NO NOTES") }, "the negated gate should have stayed shut")

        val without = textOf(render(doc, empty()))
        assertTrue(without.any { it.contains("NO NOTES") }, "the negated gate should have opened")
        assertTrue(without.none { it.contains("HAS NOTES") }, "the plain gate should have stayed shut")
    }

    @Test
    fun editor_only_is_off_unless_the_template_says_true() {
        val doc = assertNotNull(
            TemplateParser.parse(
                """
                { "root": { "type": "box", "style": {}, "children": [
                  { "type": "box", "style": {}, "children": [] },
                  { "type": "box", "editorOnly": "yes", "style": {}, "children": [] },
                  { "type": "box", "editorOnly": false, "style": {}, "children": [] }
                ] } }
                """,
            ),
        )
        val children = (doc.root as TBox).children
        assertEquals(false, children[0].editorOnly, "absent means visible everywhere")
        // A non-boolean must not hide content from an export by accident.
        assertEquals(false, children[1].editorOnly, "an unrecognised value must not mean editor-only")
        assertEquals(false, children[2].editorOnly)
        assertTrue(!doc.hasEditorOnly, "no node here is genuinely editor-only")
    }
}
