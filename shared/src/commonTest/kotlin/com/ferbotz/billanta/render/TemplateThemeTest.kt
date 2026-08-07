package com.ferbotz.billanta.render

import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.domain.model.CompanySnapshot
import com.ferbotz.billanta.domain.model.CustomerSnapshot
import com.ferbotz.billanta.domain.model.InvoiceRecord
import com.ferbotz.billanta.render.layout.DrawCommand
import com.ferbotz.billanta.render.layout.LayoutEngine
import com.ferbotz.billanta.render.layout.TemplateFlattener
import com.ferbotz.billanta.render.layout.flattenCommands
import com.ferbotz.billanta.render.text.FakeTextShaper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Colour tokens and hidable sections — the customisation requested as APP-003. */
class TemplateThemeTest {

    private val themedTemplate = """
    {
      "schemaVersion": 1, "compilerVersion": 2,
      "page": { "size": "A4", "margin": { "top": 36, "right": 36, "bottom": 36, "left": 36 },
                "fontFamily": "Inter", "baseFontSize": 10 },
      "theme": { "tokens": {
        "accent": { "default": "#2b3648", "label": "Accent" },
        "ink":    { "default": "#1f2430", "label": "Text" }
      } },
      "sections": [
        { "id": "header",  "label": "Header",          "hidable": false },
        { "id": "payment", "label": "Payment details", "hidable": true  },
        { "id": "notes",   "label": "Notes",           "hidable": true  }
      ],
      "root": { "type": "box", "style": {}, "children": [
        { "type": "box", "section": "header",
          "style": { "backgroundColor": "#2b3648", "paddingTop": 8 },
          "tokens": { "backgroundColor": "accent" },
          "children": [
            { "type": "text", "style": { "color": "#1f2430", "fontSize": 12 }, "tokens": { "color": "ink" },
              "spans": [ { "value": { "kind": "literal", "text": "ACME" } } ] } ] },
        { "type": "box", "section": "payment", "style": {}, "children": [
            { "type": "text", "style": { "fontSize": 10 },
              "spans": [ { "value": { "kind": "literal", "text": "Pay by bank transfer" } } ] } ] },
        { "type": "box", "section": "notes", "style": {}, "children": [
            { "type": "text", "style": { "fontSize": 10 },
              "spans": [ { "value": { "kind": "literal", "text": "Thanks for your business" } } ] } ] }
      ] }
    }
    """

    private val record = InvoiceRecord(
        id = "i1",
        invoiceNumber = "INV-1",
        invoiceDateMillis = Iso8601.epochMillisFor(2026, 8, 6),
        currency = "INR",
        companySnapshot = CompanySnapshot(name = "ACME", stateCode = "27"),
        customerSnapshot = CustomerSnapshot(name = "Buyer", stateCode = "27"),
        updatedAtMillis = 1L,
    )

    private fun renderText(theme: InvoiceTheme): String {
        val doc = assertNotNull(TemplateParser.parse(themedTemplate))
        return InvoiceRenderer(FakeTextShaper()).render(doc, record, theme)
            .pages.flatMap { it.commands.flattenCommands() }
            .filterIsInstance<DrawCommand.Text>()
            .flatMap { it.paragraph.lines }
            .flatMap { line -> line.runs.map { it.text } }
            .joinToString(" ")
    }

    private fun fills(theme: InvoiceTheme): List<Long> {
        val doc = assertNotNull(TemplateParser.parse(themedTemplate))
        return InvoiceRenderer(FakeTextShaper()).render(doc, record, theme)
            .pages.flatMap { it.commands.flattenCommands() }
            .filterIsInstance<DrawCommand.Fill>()
            .map { it.colorArgb }
    }

    @Test
    fun parses_theme_tokens_and_sections() {
        val doc = assertNotNull(TemplateParser.parse(themedTemplate))
        assertEquals(2, doc.themeTokens.size)
        assertEquals(0xFF2B3648L, doc.defaultColorFor("accent"))
        assertEquals("Accent", doc.themeTokens.first { it.name == "accent" }.label)
        assertEquals(3, doc.sections.size)
        assertEquals(2, doc.sections.count { it.hidable })
        assertTrue(doc.isCustomisable)
    }

    /** Templates compiled before theming must keep working and simply offer nothing. */
    @Test
    fun untagged_templates_are_not_customisable() {
        val plain = assertNotNull(
            TemplateParser.parse(
                """{ "root": { "type": "box", "style": {}, "children": [] } }""",
            ),
        )
        assertTrue(plain.themeTokens.isEmpty())
        assertTrue(plain.sections.isEmpty())
        assertFalse(plain.isCustomisable)
    }

    @Test
    fun overriding_a_token_recolours_only_that_token() {
        val defaults = fills(InvoiceTheme.NONE)
        assertTrue(defaults.contains(0xFF2B3648L), "expected the template's own accent fill")

        val recoloured = fills(InvoiceTheme(colorOverrides = mapOf("accent" to 0xFFC2410CL)))
        assertTrue(recoloured.contains(0xFFC2410CL), "accent fill should follow the override")
        assertFalse(recoloured.contains(0xFF2B3648L), "the old accent should be gone")
    }

    @Test
    fun an_untouched_token_keeps_the_template_colour() {
        // Only `accent` is overridden, so the `ink` text colour must not move.
        val doc = assertNotNull(TemplateParser.parse(themedTemplate))
        val runs = InvoiceRenderer(FakeTextShaper())
            .render(doc, record, InvoiceTheme(colorOverrides = mapOf("accent" to 0xFFC2410CL)))
            .pages.flatMap { it.commands.flattenCommands() }
            .filterIsInstance<DrawCommand.Text>()
            .flatMap { it.paragraph.lines }
            .flatMap { it.runs }
        assertTrue(runs.any { it.style.colorArgb == 0xFF1F2430L }, "ink should still be the template's")
    }

    @Test
    fun hiding_a_section_removes_it_and_its_contents() {
        assertTrue(renderText(InvoiceTheme.NONE).contains("Pay by bank transfer"))

        val hidden = renderText(InvoiceTheme(hiddenSections = setOf("payment")))
        assertFalse(hidden.contains("Pay by bank transfer"), "hidden section still rendered")
        assertTrue(hidden.contains("ACME"), "unrelated sections must survive")
        assertTrue(hidden.contains("Thanks for your business"))
    }

    @Test
    fun hiding_several_sections_at_once() {
        val hidden = renderText(InvoiceTheme(hiddenSections = setOf("payment", "notes")))
        assertFalse(hidden.contains("Pay by bank transfer"))
        assertFalse(hidden.contains("Thanks for your business"))
        assertTrue(hidden.contains("ACME"))
    }

    @Test
    fun an_unknown_section_id_changes_nothing() {
        val before = renderText(InvoiceTheme.NONE)
        val after = renderText(InvoiceTheme(hiddenSections = setOf("does-not-exist")))
        assertEquals(before, after)
    }

    @Test
    fun colours_round_trip_through_hex() {
        assertEquals("#c2410c", TemplateParser.formatHexColor(0xFFC2410CL))
        assertEquals(0xFFC2410CL, TemplateParser.parseHexColor("#c2410c"))
    }
}
