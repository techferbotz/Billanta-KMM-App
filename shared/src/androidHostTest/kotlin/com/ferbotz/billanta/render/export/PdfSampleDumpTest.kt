package com.ferbotz.billanta.render.export

import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.domain.model.CompanySnapshot
import com.ferbotz.billanta.domain.model.CustomerSnapshot
import com.ferbotz.billanta.domain.model.InvoiceItemRecord
import com.ferbotz.billanta.domain.model.InvoiceRecord
import com.ferbotz.billanta.render.InvoiceRenderer
import com.ferbotz.billanta.render.TemplateParser
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

    private fun record(itemCount: Int) = InvoiceRecord(
        id = "inv-1",
        invoiceNumber = "INV-2026-0042",
        invoiceDateMillis = Iso8601.epochMillisFor(2026, 8, 5),
        dueDateMillis = Iso8601.epochMillisFor(2026, 8, 19),
        currency = "INR",
        companySnapshot = CompanySnapshot(
            name = "Studio Nine",
            gstin = "27ABCDE1234F1Z5",
            addressLine1 = "A-901, Oberoi Springs, Andheri West",
            city = "Mumbai",
            pincode = "400053",
            state = "Maharashtra",
            stateCode = "27",
            phone = "+91 98765 43210",
            email = "hello@studionine.in",
            upiId = "studionine@okhdfcbank",
            bankName = "HDFC Bank",
            accountNumber = "50100234567821",
            ifsc = "HDFC0000123",
        ),
        customerSnapshot = CustomerSnapshot(
            name = "Kavya Iyer",
            gstin = "27AAJCB1111C1Z2",
            addressLine1 = "12 Carter Road, Bandra West",
            city = "Mumbai",
            pincode = "400050",
            state = "Maharashtra",
            stateCode = "27",
        ),
        items = (1..itemCount).map { i ->
            InvoiceItemRecord(
                description = "Brand identity design — phase $i",
                hsnSac = "998311",
                quantity = "1",
                unitPricePaise = 6800000,
                taxRatePercent = "18",
                lineTotalPaise = 6800000,
                taxAmountPaise = 1224000,
            )
        },
        subtotalPaise = 6800000L * itemCount,
        taxTotalPaise = 1224000L * itemCount,
        grandTotalPaise = 8024000L * itemCount,
        notes = "Thanks for your business. Payable within 14 days via UPI or bank transfer.",
        updatedAtMillis = 1L,
    )

    @Test
    fun dumps_single_and_multi_page_samples() {
        val templateFile = resource(
            "src/androidHostTest/resources/templates/classic.json",
            "shared/src/androidHostTest/resources/templates/classic.json",
        )
        val doc = assertNotNull(TemplateParser.parse(templateFile.readText()))
        val renderer = InvoiceRenderer(FakeTextShaper())
        val outputDir = File("build/samples").apply { mkdirs() }

        listOf("single" to 4, "multipage" to 45).forEach { (label, itemCount) ->
            val document = renderer.render(doc, record(itemCount))
            val bytes = PdfWriter(faces()).write(document)
            val file = File(outputDir, "invoice-$label.pdf")
            file.writeBytes(bytes)
            assertTrue(file.length() > 0, "wrote an empty PDF for $label")
            println("[pdf-sample] $label: ${file.absolutePath} (${bytes.size} bytes, ${document.pageCount} pages)")
        }
    }
}
