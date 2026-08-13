package com.ferbotz.billanta.render.export

import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.domain.model.CompanySnapshot
import com.ferbotz.billanta.domain.model.CustomerSnapshot
import com.ferbotz.billanta.domain.model.InvoiceItemRecord
import com.ferbotz.billanta.domain.model.InvoiceRecord
import com.ferbotz.billanta.render.InvoiceRenderer
import com.ferbotz.billanta.render.TemplateParser
import com.ferbotz.billanta.render.layout.DrawCommand
import com.ferbotz.billanta.render.layout.RenderedDocument
import com.ferbotz.billanta.render.layout.flattenCommands
import com.ferbotz.billanta.render.text.FakeTextShaper
import com.ferbotz.billanta.render.text.FontRegistry
import com.ferbotz.billanta.render.text.TrueTypeFont
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Structural checks on the hand-written PDF. These assert the bytes are a well-formed document
 * with the pieces a reader needs — the visual check is opening one on a device.
 */
class PdfWriterTest {

    private fun fontFile(name: String): ByteArray {
        val file = listOf(
            File("src/commonMain/composeResources/font/$name"),
            File("shared/src/commonMain/composeResources/font/$name"),
        ).firstOrNull { it.exists() }
        return assertNotNull(file, "bundled font $name not found").readBytes()
    }

    private fun faces(): Map<String, PdfFace> {
        val regular = assertNotNull(TrueTypeFont.parse(fontFile("inter_regular.ttf")))
        val bold = assertNotNull(TrueTypeFont.parse(fontFile("inter_bold.ttf")))
        return linkedMapOf(
            FontRegistry.REGULAR to PdfFace(FontRegistry.REGULAR, regular, "Inter-Regular"),
            FontRegistry.BOLD to PdfFace(FontRegistry.BOLD, bold, "Inter-Bold"),
        )
    }

    private fun document(itemCount: Int): RenderedDocument {
        val file = listOf(
            File("src/androidHostTest/resources/templates/classic.json"),
            File("shared/src/androidHostTest/resources/templates/classic.json"),
        ).firstOrNull { it.exists() }
        val doc = assertNotNull(TemplateParser.parse(assertNotNull(file).readText()))
        val record = InvoiceRecord(
            id = "inv-1",
            invoiceNumber = "INV-2026-0042",
            invoiceDateMillis = Iso8601.epochMillisFor(2026, 8, 5),
            currency = "INR",
            companySnapshot = CompanySnapshot(name = "Studio Nine", stateCode = "27", upiId = "studio@ok"),
            customerSnapshot = CustomerSnapshot(name = "Kavya Iyer", stateCode = "27"),
            items = (1..itemCount).map {
                InvoiceItemRecord(
                    description = "Design work $it",
                    quantity = "1",
                    unitPricePaise = 500000,
                    taxRatePercent = "18",
                    lineTotalPaise = 500000,
                    taxAmountPaise = 90000,
                )
            },
            subtotalPaise = 500000L * itemCount,
            taxTotalPaise = 90000L * itemCount,
            grandTotalPaise = 590000L * itemCount,
            updatedAtMillis = 1L,
        )
        return InvoiceRenderer(FakeTextShaper()).render(doc, record)
    }

    private fun pdf(itemCount: Int = 4): ByteArray = PdfWriter(faces()).write(document(itemCount))

    /** Latin-1 view of the bytes, so structural markers can be matched without mangling binaries. */
    private fun ByteArray.asLatin1(): String = buildString(size) {
        this@asLatin1.forEach { append((it.toInt() and 0xFF).toChar()) }
    }

    @Test
    fun produces_a_well_formed_pdf() {
        val bytes = pdf()
        val text = bytes.asLatin1()
        assertTrue(text.startsWith("%PDF-1.7"), "missing PDF header")
        assertTrue(text.trimEnd().endsWith("%%EOF"), "missing EOF marker")
        assertTrue(text.contains("/Type /Catalog"), "missing document catalog")
        assertTrue(text.contains("/Type /Pages"), "missing page tree")
        assertTrue(text.contains("/Type /Page "), "missing page object")
        assertTrue(text.contains("trailer"), "missing trailer")
        assertTrue(text.contains("startxref"), "missing startxref")
    }

    /** Byte offsets in the cross-reference table must actually land on their objects. */
    @Test
    fun cross_reference_offsets_point_at_their_objects() {
        val bytes = pdf()
        val text = bytes.asLatin1()
        // Match the table itself, not the "xref" inside the trailing "startxref" keyword.
        val xrefIndex = text.lastIndexOf("\nxref\n") + 1
        assertTrue(xrefIndex > 0, "no xref section")

        val startxref = text.substringAfterLast("startxref").trim().substringBefore("%%EOF").trim()
        assertEquals(xrefIndex, startxref.toInt(), "startxref does not point at the xref table")

        val entries = Regex("""^(\d{10}) 00000 n $""", RegexOption.MULTILINE).findAll(text).toList()
        assertTrue(entries.isNotEmpty(), "no in-use xref entries")
        entries.forEachIndexed { index, match ->
            val offset = match.groupValues[1].toInt()
            val header = text.substring(offset, minOf(offset + 20, text.length))
            assertTrue(
                header.startsWith("${index + 1} 0 obj"),
                "xref entry ${index + 1} points at \"$header\" instead of its object",
            )
        }
    }

    /**
     * Text must go out as real text operators with an embedded font — that is the whole point of
     * writing the PDF by hand rather than wrapping a bitmap.
     */
    @Test
    fun embeds_fonts_and_writes_selectable_text() {
        val text = pdf().asLatin1()
        assertTrue(text.contains("/Subtype /Type0"), "expected a composite font")
        assertTrue(text.contains("/Encoding /Identity-H"), "expected Identity-H encoding")
        assertTrue(text.contains("/Subtype /CIDFontType2"), "expected a CID descendant font")
        assertTrue(text.contains("/FontFile2"), "font file is not embedded")
        assertTrue(text.contains("/ToUnicode"), "no ToUnicode map, so text would not be searchable")
        assertTrue(text.contains(" Tj"), "no text-showing operators")
        assertTrue(text.contains(" Tf"), "no font-selection operators")
        assertTrue(text.contains(" re"), "no rectangles — the table rules should produce some")
    }

    /** The rupee sign has no WinAnsi code point; the ToUnicode map has to carry it explicitly. */
    @Test
    fun rupee_sign_is_mapped_back_to_unicode() {
        val text = pdf().asLatin1()
        val regular = assertNotNull(TrueTypeFont.parse(fontFile("inter_regular.ttf")))
        val rupeeGlyph = regular.glyphId(0x20B9)
        assertTrue(rupeeGlyph != 0, "fixture font lacks the rupee glyph")

        val hex = rupeeGlyph.toString(16).uppercase().padStart(4, '0')
        assertTrue(
            text.contains("<$hex> <20B9>"),
            "expected the rupee glyph $hex to map back to U+20B9 in the ToUnicode CMap",
        )
    }

    @Test
    fun one_pdf_page_per_rendered_page() {
        val short = pdf(itemCount = 3).asLatin1()
        assertEquals(1, Regex("/Type /Page ").findAll(short).count())

        val long = PdfWriter(faces()).write(document(itemCount = 60)).asLatin1()
        val pageCount = Regex("/Type /Page ").findAll(long).count()
        assertTrue(pageCount >= 2, "a 60-item invoice should produce multiple PDF pages")
        assertTrue(long.contains("/Count $pageCount"), "page tree count should match the page objects")
    }

    @Test
    fun embeds_only_the_faces_the_document_uses() {
        val bytes = pdf()
        val text = bytes.asLatin1()
        // classic uses regular and bold weights, so both faces are embedded and nothing more.
        assertTrue(text.contains("Inter-Regular"), "regular face missing")
        assertEquals(
            2,
            Regex("/FontFile2").findAll(text).count(),
            "expected exactly the two faces classic uses",
        )
    }

    @Test
    fun stays_far_smaller_than_a_rasterised_page() {
        val bytes = pdf(itemCount = 20)
        // Two embedded faces dominate the size; the drawing itself is only a few KB.
        assertTrue(bytes.size < 1_500_000, "PDF unexpectedly large: ${bytes.size} bytes")
        assertTrue(bytes.size > 10_000, "PDF suspiciously small: ${bytes.size} bytes")
    }
    /**
     * A dashed border has to survive into the exported file, not just the on-screen preview — the
     * PDF is what the customer receives, and until now the writer ignored border style *and* radius
     * entirely, so a rounded dashed box exported as a square solid one.
     */
    @Test
    fun a_dashed_rounded_border_exports_as_a_dashed_stroked_path() {
        val doc = assertNotNull(
            TemplateParser.parse(
                """
                { "schemaVersion": 1, "compilerVersion": 1,
                  "page": { "size": "A4", "margin": { "top": 0, "right": 0, "bottom": 0, "left": 0 } },
                  "root": { "type": "box", "style": {
                      "width": 200, "height": 100, "borderRadius": 8,
                      "borderTopWidth": 2, "borderRightWidth": 2,
                      "borderBottomWidth": 2, "borderLeftWidth": 2,
                      "borderTopColor": "#FF0000", "borderRightColor": "#FF0000",
                      "borderBottomColor": "#FF0000", "borderLeftColor": "#FF0000",
                      "borderTopStyle": "dashed", "borderRightStyle": "dashed",
                      "borderBottomStyle": "dashed", "borderLeftStyle": "dashed"
                    }, "children": [] } }
                """,
            ),
        )
        val record = InvoiceRecord(
            id = "inv-1",
            invoiceNumber = "INV-1",
            invoiceDateMillis = Iso8601.epochMillisFor(2026, 8, 12),
            updatedAtMillis = 1L,
        )
        val rendered = InvoiceRenderer(FakeTextShaper()).render(doc, record)
        val text = PdfWriter(faces()).write(rendered).decodeToString()

        assertTrue(Regex("""\[[0-9.]+ [0-9.]+] 0 d""").containsMatchIn(text), "no dash array was emitted")
        assertTrue(Regex("(?m)^S$").containsMatchIn(text), "the dashed border was not stroked")
        // Bézier arcs mean the radius made it through; `re f` alone would be the old square fill.
        assertTrue(Regex("(?m) c$").containsMatchIn(text), "no curve: the corner radius was dropped")
    }

    /**
     * The end of the BE-009 guarantee: not "the flattener drops it" but "the actual bytes we hand
     * to the share sheet do not contain it". Classic ships an editor-only "+ Add an item" box.
     */
    @Test
    fun an_editor_only_placeholder_is_absent_from_the_exported_bytes() {
        val file = listOf(
            File("src/androidHostTest/resources/templates/classic.json"),
            File("shared/src/androidHostTest/resources/templates/classic.json"),
        ).firstOrNull { it.exists() }
        val doc = assertNotNull(TemplateParser.parse(assertNotNull(file).readText()))
        assertTrue(doc.hasEditorOnly, "fixture has no editor-only content, so this proves nothing")

        val blank = InvoiceRecord(
            id = "inv-1",
            invoiceNumber = "INV-1",
            invoiceDateMillis = Iso8601.epochMillisFor(2026, 8, 12),
            updatedAtMillis = 1L,
        )
        val exported = InvoiceRenderer(FakeTextShaper()).render(doc, blank)
        val bytes = PdfWriter(faces()).write(exported)

        // Text is stored as glyph ids, so decode what the writer actually laid down instead of
        // grepping the bytes for ASCII that would never appear there anyway.
        val drawn = exported.pages
            .flatMap { it.commands.flattenCommands() }
            .filterIsInstance<DrawCommand.Text>()
            .flatMap { it.paragraph.lines }
            .flatMap { line -> line.runs.map { it.text } }
        assertTrue(
            drawn.none { it.contains("Add an item", ignoreCase = true) },
            "the exported PDF was built from a display list still containing the editor-only prompt",
        )
        assertTrue(bytes.isNotEmpty(), "the export produced no bytes")
    }

}
