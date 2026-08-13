package com.ferbotz.billanta.render

import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.domain.model.CompanySnapshot
import com.ferbotz.billanta.domain.model.CustomerSnapshot
import com.ferbotz.billanta.domain.model.InvoiceItemRecord
import com.ferbotz.billanta.domain.model.InvoiceRecord
import com.ferbotz.billanta.render.layout.DrawCommand
import com.ferbotz.billanta.render.layout.PlaceholderMode
import com.ferbotz.billanta.render.layout.RenderedDocument
import com.ferbotz.billanta.render.layout.flattenCommands
import com.ferbotz.billanta.render.text.FakeTextShaper
import java.io.File
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

    private fun template(name: String): TemplateDoc {
        val file = listOf(
            File("src/androidHostTest/resources/templates/$name.json"),
            File("shared/src/androidHostTest/resources/templates/$name.json"),
        ).firstOrNull { it.exists() }
        assertNotNull(file, "fixture $name.json not found")
        return assertNotNull(TemplateParser.parse(file.readText()))
    }

    private fun empty() = InvoiceRecord(
        id = "inv-1",
        invoiceNumber = "INV-1",
        invoiceDateMillis = Iso8601.epochMillisFor(2026, 8, 12),
        companySnapshot = CompanySnapshot(name = "Studio Nine", stateCode = "27"),
        updatedAtMillis = 1L,
    )

    private fun full() = empty().copy(
        customerSnapshot = CustomerSnapshot(name = "Kavya Iyer", stateCode = "27"),
        items = listOf(
            InvoiceItemRecord(
                description = "Design", quantity = "1", unitPricePaise = 100000,
                taxRatePercent = "18", lineTotalPaise = 100000, taxAmountPaise = 18000,
            ),
        ),
        notes = "Thanks!",
    )

    private fun textOf(document: RenderedDocument): List<String> = document.pages
        .flatMap { it.commands.flattenCommands() }
        .filterIsInstance<DrawCommand.Text>()
        .flatMap { it.paragraph.lines }
        .flatMap { line -> line.runs.map { it.text } }

    private fun editing(doc: TemplateDoc, record: InvoiceRecord) = renderer.render(
        doc,
        record,
        placeholders = PlaceholderMode.Reserve(emptySectionsFor(doc, record)),
    )

    @Test
    fun the_seed_template_ships_an_empty_state() {
        val doc = template("classic")
        assertTrue(doc.hasEditorOnly, "classic should carry the BE-009 items empty state")
    }

    @Test
    fun an_authored_empty_state_shows_while_editing() {
        val shown = textOf(editing(template("classic"), empty()))
        assertTrue(
            shown.any { it.contains("Add an item", ignoreCase = true) },
            "the template's own empty state did not render while editing: $shown",
        )
    }

    /** The guarantee the whole feature rests on. */
    @Test
    fun an_authored_empty_state_never_reaches_an_export() {
        listOf(empty(), full()).forEach { record ->
            val exported = renderer.render(template("classic"), record, placeholders = PlaceholderMode.None)
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
        val doc = template("classic")
        val document = editing(doc, empty())

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
     * A template that does not author an empty state must keep the app's synthesised one, or the
     * user is left with a section they cannot see and cannot reach.
     */
    @Test
    fun a_template_without_an_empty_state_keeps_the_synthesised_one() {
        val doc = template("minimal")
        val record = empty()
        val expected = emptySectionsFor(doc, record)
        assertTrue(expected.isNotEmpty(), "a blank invoice should still report empty sections")

        val reserved = editing(doc, record).pages.flatMap { it.sections }.filter { it.isEmpty }.map { it.id }
        expected.forEach {
            assertTrue(it in reserved, "minimal: '$it' lost its placeholder and has no authored one")
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
                    { "type": "conditional", "path": "invoice.notes", "child": {
                        "type": "text", "style": {},
                        "spans": [ { "value": { "kind": "literal", "text": "HAS NOTES" } } ] } },
                    { "type": "conditional", "path": "invoice.notes", "negate": true, "child": {
                        "type": "text", "style": {},
                        "spans": [ { "value": { "kind": "literal", "text": "NO NOTES" } } ] } }
                  ] } }
                """,
            ),
        )

        val withNotes = textOf(renderer.render(doc, empty().copy(notes = "Thanks!")))
        assertTrue(withNotes.any { it.contains("HAS NOTES") }, "the plain gate should have opened")
        assertTrue(withNotes.none { it.contains("NO NOTES") }, "the negated gate should have stayed shut")

        val without = textOf(renderer.render(doc, empty().copy(notes = null)))
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
