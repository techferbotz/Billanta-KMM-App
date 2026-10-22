package com.ferbotz.billanta.render.export

import com.ferbotz.billanta.core.BillantaJson
import com.ferbotz.billanta.fields.Be021Fixtures
import kotlinx.serialization.json.jsonObject
import com.ferbotz.billanta.render.InvoiceRenderer
import com.ferbotz.billanta.render.TemplateParser
import com.ferbotz.billanta.render.TestInvoices
import com.ferbotz.billanta.render.text.FakeTextShaper
import com.ferbotz.billanta.render.text.FontRegistry
import com.ferbotz.billanta.render.text.TrueTypeFont
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Writes sample PDFs to `build/samples/` so they can be opened in a real viewer.
 *
 * Structural assertions live in [PdfWriterTest]; the only thing that proves a PDF is genuinely
 * valid is a reader parsing it, so this keeps artefacts around for that check.
 */
class PdfSampleDumpTest {

    private fun resource(vararg candidates: String): File =
        assertNotNull(candidates.map(::File).firstOrNull { it.exists() }, "missing ${candidates.first()}")

    private fun faces(): Map<String, PdfFace> {
        val regular = assertNotNull(
            TrueTypeFont.parse(
                resource(
                    "src/commonMain/composeResources/font/inter_regular.ttf",
                    "shared/src/commonMain/composeResources/font/inter_regular.ttf",
                ).readBytes(),
            ),
        )
        val bold = assertNotNull(
            TrueTypeFont.parse(
                resource(
                    "src/commonMain/composeResources/font/inter_bold.ttf",
                    "shared/src/commonMain/composeResources/font/inter_bold.ttf",
                ).readBytes(),
            ),
        )
        return linkedMapOf(
            FontRegistry.REGULAR to PdfFace(FontRegistry.REGULAR, regular, "Inter-Regular"),
            FontRegistry.BOLD to PdfFace(FontRegistry.BOLD, bold, "Inter-Bold"),
        )
    }

    /**
     * BE-021's Classic drawn with the backend's example context. Open it beside
     * `be021/template-classic.thumbnail.png`, the server's own render of the same pair: the layout
     * and the values should match, though the pixels will not.
     */
    @Test
    fun dumps_the_be021_classic_example() {
        val doc = assertNotNull(TemplateParser.parse(Be021Fixtures.text("template-classic.compiled.json")))
        val context = BillantaJson.parseToJsonElement(Be021Fixtures.text("render-context.json")).jsonObject
        val document = InvoiceRenderer(FakeTextShaper()).render(doc, context, Be021Fixtures.forms.optionLists)
        val file = File(File("build/samples").apply { mkdirs() }, "invoice-classic-v2.pdf")
        file.writeBytes(PdfWriter(faces()).write(document))
        assertTrue(file.length() > 0)
        println("[pdf-sample] classic v2: ${file.absolutePath} (${document.pageCount} pages)")
    }

    @Test
    fun dumps_single_and_multi_page_samples() {
        val renderer = InvoiceRenderer(FakeTextShaper())
        val outputDir = File("build/samples").apply { mkdirs() }

        listOf("single" to 4, "multipage" to 45).forEach { (label, itemCount) ->
            val invoice = TestInvoices.invoice(itemCount) { "Brand identity design — phase $it" }
            val document = renderer.render(TestInvoices.classic, invoice.renderContext(), TestInvoices.optionLists)
            val bytes = PdfWriter(faces()).write(document)
            val file = File(outputDir, "invoice-$label.pdf")
            file.writeBytes(bytes)
            assertTrue(file.length() > 0, "wrote an empty PDF for $label")
            println("[pdf-sample] $label: ${file.absolutePath} (${bytes.size} bytes, ${document.pageCount} pages)")
        }
    }
}
