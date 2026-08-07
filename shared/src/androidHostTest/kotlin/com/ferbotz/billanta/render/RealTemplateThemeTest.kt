package com.ferbotz.billanta.render

import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.domain.model.CompanySnapshot
import com.ferbotz.billanta.domain.model.CustomerSnapshot
import com.ferbotz.billanta.domain.model.InvoiceItemRecord
import com.ferbotz.billanta.domain.model.InvoiceRecord
import com.ferbotz.billanta.render.layout.DrawCommand
import com.ferbotz.billanta.render.layout.RenderedDocument
import com.ferbotz.billanta.render.layout.flattenCommands
import com.ferbotz.billanta.render.text.FakeTextShaper
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Theming against the **real** compiled trees the backend serves (BE-003), rather than a synthetic
 * fixture of the shape we asked for. `TemplateThemeTest` proves the mechanism; this proves we and
 * the backend actually agree — the failure mode a two-laptop split invites.
 *
 * Fixtures under `androidHostTest/resources/templates` are v2 of each seed template, fetched from
 * `GET /templates/:id/compiled`. Refresh them when a new template version is published.
 */
class RealTemplateThemeTest {

    private val renderer = InvoiceRenderer(FakeTextShaper())

    private fun template(name: String): TemplateDoc {
        val file = listOf(
            File("src/androidHostTest/resources/templates/$name.json"),
            File("shared/src/androidHostTest/resources/templates/$name.json"),
        ).firstOrNull { it.exists() }
        assertNotNull(file, "fixture $name.json not found")
        return assertNotNull(TemplateParser.parse(file.readText()), "failed to parse $name.json")
    }

    private val record = InvoiceRecord(
        id = "inv-1",
        invoiceNumber = "INV-2026-0042",
        invoiceDateMillis = Iso8601.epochMillisFor(2026, 8, 6),
        currency = "INR",
        companySnapshot = CompanySnapshot(
            name = "Studio Nine",
            stateCode = "27",
            upiId = "studionine@okhdfcbank",
            bankName = "HDFC Bank",
            accountNumber = "50100234567821",
        ),
        customerSnapshot = CustomerSnapshot(name = "Kavya Iyer", stateCode = "27"),
        items = listOf(
            InvoiceItemRecord(
                description = "Brand identity design",
                quantity = "1",
                unitPricePaise = 6800000,
                taxRatePercent = "18",
                lineTotalPaise = 6800000,
                taxAmountPaise = 1224000,
            ),
        ),
        subtotalPaise = 6800000,
        taxTotalPaise = 1224000,
        grandTotalPaise = 8024000,
        notes = "Payable within 14 days.",
        updatedAtMillis = 1L,
    )

    private fun fillColours(document: RenderedDocument): List<Long> =
        document.pages.flatMap { it.commands.flattenCommands() }
            .filterIsInstance<DrawCommand.Fill>()
            .map { it.colorArgb }

    private fun textOf(document: RenderedDocument): String =
        document.pages.flatMap { it.commands.flattenCommands() }
            .filterIsInstance<DrawCommand.Text>()
            .flatMap { it.paragraph.lines }
            .flatMap { line -> line.runs.map { it.text } }
            .joinToString(" ")

    /**
     * Colours of the glyphs themselves. A token declared on a container is *inherited* down to the
     * text that draws it, so a gap in tagging shows up here rather than in the fills.
     */
    private fun textColours(document: RenderedDocument): List<Long> =
        document.pages.flatMap { it.commands.flattenCommands() }
            .filterIsInstance<DrawCommand.Text>()
            .flatMap { it.paragraph.lines }
            .flatMap { line -> line.runs.map { it.style.colorArgb } }

    @Test
    fun the_shipped_classic_template_declares_the_theme_we_expect() {
        val doc = template("classic")
        assertTrue(doc.isCustomisable, "classic v2 should be customisable")

        val accent = assertNotNull(
            doc.themeTokens.firstOrNull { it.name == "accent" },
            "expected an 'accent' token, got ${doc.themeTokens.map { it.name }}",
        )
        assertEquals(ACCENT_DEFAULT, accent.defaultArgb, "accent default should be #2b3648")
        assertTrue(accent.label.isNotBlank(), "token needs a label for the UI")

        val hidable = doc.sections.filter { it.hidable }.map { it.id }.toSet()
        assertEquals(setOf("payment", "notes"), hidable, "unexpected hidable set: $hidable")
        assertTrue(
            doc.sections.filterNot { it.hidable }.map { it.id }.containsAll(listOf("header", "items")),
            "header and items must not be hidable — an invoice without them is not an invoice",
        )
    }

    @Test
    fun overriding_the_accent_recolours_the_real_template() {
        val doc = template("classic")
        val plain = renderer.render(doc, record)
        val recoloured = renderer.render(
            doc,
            record,
            InvoiceTheme(colorOverrides = mapOf("accent" to NEW_ACCENT)),
        )

        assertTrue(
            fillColours(plain).any { it == ACCENT_DEFAULT },
            "the untouched template should paint its own accent somewhere",
        )
        assertTrue(
            fillColours(recoloured).any { it == NEW_ACCENT },
            "the override should appear in the rendered fills",
        )
        assertTrue(
            fillColours(recoloured).none { it == ACCENT_DEFAULT },
            "no fill should still carry the template's original accent",
        )
        // Recolouring must not disturb anything else.
        assertEquals(fillColours(plain).size, fillColours(recoloured).size)
        assertEquals(textOf(plain), textOf(recoloured))
    }

    @Test
    fun hiding_the_payment_section_drops_it_from_the_real_template() {
        val doc = template("classic")
        val full = textOf(renderer.render(doc, record))
        assertTrue(full.contains("studionine@okhdfcbank"), "fixture should render the UPI id to begin with")

        val hidden = textOf(
            renderer.render(doc, record, InvoiceTheme(hiddenSections = setOf("payment"))),
        )
        assertTrue(!hidden.contains("studionine@okhdfcbank"), "payment details should be gone")
        assertTrue(hidden.contains("Kavya Iyer"), "hiding payment must not take the rest with it")
        assertTrue(hidden.contains("INV-2026-0042"), "the invoice number must survive")
        assertTrue(hidden.length < full.length, "hiding a section should shorten the document")
    }

    @Test
    fun every_hidable_section_actually_disappears() {
        val doc = template("classic")
        val baseline = textOf(renderer.render(doc, record))
        doc.sections.filter { it.hidable }.forEach { section ->
            val hidden = textOf(renderer.render(doc, record, InvoiceTheme(hiddenSections = setOf(section.id))))
            assertTrue(
                hidden.length < baseline.length,
                "section '${section.id}' is declared hidable but hiding it changed nothing — " +
                    "the toggle would be dead in the UI",
            )
        }
    }

    /**
     * The gap BE-004 closed: text drew in the accent colour while its container held the token, so
     * an override recoloured the boxes and left the glyphs behind.
     */
    @Test
    fun no_glyph_keeps_the_old_colour_after_an_override() {
        listOf("classic", "minimal").forEach { name ->
            val doc = template(name)
            val token = assertNotNull(doc.themeTokens.firstOrNull(), "$name declares no token")
            val recoloured = renderer.render(
                doc,
                record,
                InvoiceTheme(colorOverrides = mapOf(token.name to NEW_ACCENT)),
            )
            val stale = textColours(recoloured).count { it == token.defaultArgb }
            assertEquals(
                0,
                stale,
                "$name: $stale text run(s) still drawn in the template's original ${token.name}",
            )
            assertTrue(
                textColours(recoloured).any { it == NEW_ACCENT },
                "$name: the override should reach the glyphs, not just the fills",
            )
        }
    }

    /** classic v3 declares its own controls; the sheet must follow that order and those titles. */
    @Test
    fun the_shipped_template_declares_its_customisation_controls() {
        val doc = template("classic")
        assertTrue(doc.declaredControls.isNotEmpty(), "classic v3 should declare controls")
        assertEquals(
            listOf("Template", "Accent colour", "Payment details", "Notes"),
            doc.controls.map { it.title },
        )
        assertEquals("accent", assertNotNull(doc.controls.filterIsInstance<CustomisationControl.Color>().firstOrNull()).token)
        assertEquals(
            listOf("payment", "notes"),
            doc.controls.filterIsInstance<CustomisationControl.SectionToggle>().map { it.section },
        )
        // Every control must resolve against something the template actually declares, or the
        // sheet would render a toggle that does nothing.
        doc.controls.forEach { control ->
            when (control) {
                is CustomisationControl.Color ->
                    assertTrue(doc.themeTokens.any { it.name == control.token }, "unknown token ${control.token}")
                is CustomisationControl.SectionToggle ->
                    assertTrue(
                        doc.sections.any { it.id == control.section && it.hidable },
                        "control points at ${control.section}, which is not a hidable section",
                    )
                is CustomisationControl.TemplatePicker -> Unit
            }
        }
    }

    @Test
    fun minimal_is_themeable_too() {
        val doc = template("minimal")
        assertTrue(doc.isCustomisable, "minimal v2 should be customisable")
        assertTrue(doc.themeTokens.isNotEmpty(), "minimal should declare at least one token")

        val token = doc.themeTokens.first()
        val recoloured = renderer.render(
            doc,
            record,
            InvoiceTheme(colorOverrides = mapOf(token.name to NEW_ACCENT)),
        )
        assertTrue(recoloured.pageCount >= 1)
        assertTrue(textOf(recoloured).contains("INV-2026-0042"))
    }

    /** Both customisations at once, which is what the edit sheet actually produces. */
    @Test
    fun colour_and_hidden_sections_combine() {
        val doc = template("classic")
        val themed = renderer.render(
            doc,
            record,
            InvoiceTheme(
                colorOverrides = mapOf("accent" to NEW_ACCENT),
                hiddenSections = setOf("payment", "notes"),
            ),
        )
        assertTrue(fillColours(themed).any { it == NEW_ACCENT })
        assertTrue(fillColours(themed).none { it == ACCENT_DEFAULT })
        val text = textOf(themed)
        assertTrue(!text.contains("studionine@okhdfcbank"))
        assertTrue(!text.contains("Payable within 14 days"))
        assertTrue(text.contains("Brand identity design"), "the items table must be untouched")
    }

    private companion object {
        /** classic v2's own accent, #2b3648. */
        const val ACCENT_DEFAULT = 0xFF2B3648
        const val NEW_ACCENT = 0xFFC2410C
    }
}
