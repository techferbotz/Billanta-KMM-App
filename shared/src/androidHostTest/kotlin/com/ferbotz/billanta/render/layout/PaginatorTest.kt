package com.ferbotz.billanta.render.layout

import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.domain.model.CompanySnapshot
import com.ferbotz.billanta.domain.model.CustomerSnapshot
import com.ferbotz.billanta.domain.model.InvoiceItemRecord
import com.ferbotz.billanta.domain.model.InvoiceRecord
import com.ferbotz.billanta.render.InvoiceRenderer
import com.ferbotz.billanta.render.PageSpec
import com.ferbotz.billanta.render.TemplateDoc
import com.ferbotz.billanta.render.TemplateParser
import com.ferbotz.billanta.render.text.FakeTextShaper
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Pagination over the real `classic` template: long invoices must page, not clip. */
class PaginatorTest {

    private val renderer = InvoiceRenderer(FakeTextShaper())

    private fun template(name: String): TemplateDoc {
        val file = listOf(
            File("src/androidHostTest/resources/templates/$name.json"),
            File("shared/src/androidHostTest/resources/templates/$name.json"),
        ).firstOrNull { it.exists() }
        assertNotNull(file, "fixture $name.json not found")
        return assertNotNull(TemplateParser.parse(file.readText()))
    }

    private fun invoice(itemCount: Int) = InvoiceRecord(
        id = "inv-1",
        invoiceNumber = "INV-2026-0042",
        invoiceDateMillis = Iso8601.epochMillisFor(2026, 8, 5),
        currency = "INR",
        companySnapshot = CompanySnapshot(name = "Studio Nine", stateCode = "27", upiId = "studio@ok"),
        customerSnapshot = CustomerSnapshot(name = "Kavya Iyer", stateCode = "27"),
        items = (1..itemCount).map { i ->
            InvoiceItemRecord(
                description = "Line item number $i for the engagement",
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

    private fun textOf(page: RenderedPage): String =
        page.commands.flattenCommands()
            .filterIsInstance<DrawCommand.Text>()
            .flatMap { it.paragraph.lines }
            .flatMap { line -> line.runs.map { it.text } }
            .joinToString(" ")

    @Test
    fun short_invoice_stays_on_one_page() {
        val document = renderer.render(template("classic"), invoice(3))
        assertEquals(1, document.pageCount)
    }

    @Test
    fun long_invoice_paginates_instead_of_clipping() {
        val document = renderer.render(template("classic"), invoice(60))
        assertTrue(document.pageCount >= 2, "60 items should need more than one page")
        assertTrue(document.pageCount < 20, "60 items should not explode into ${document.pageCount} pages")
    }

    /** The failure this replaces: content past the first page was silently dropped. */
    @Test
    fun no_item_is_lost_across_the_page_break() {
        val itemCount = 60
        val document = renderer.render(template("classic"), invoice(itemCount))
        val everything = document.pages.joinToString(" ") { textOf(it) }
        (1..itemCount).forEach { i ->
            assertTrue(
                everything.contains("Line item number $i "),
                "item $i is missing from the rendered pages",
            )
        }
    }

    @Test
    fun table_headers_repeat_on_continuation_pages() {
        val document = renderer.render(template("classic"), invoice(60))
        assertTrue(document.pageCount >= 2)
        document.pages.forEachIndexed { index, page ->
            assertTrue(
                textOf(page).contains("Description"),
                "page ${index + 1} of ${document.pageCount} lost the table header",
            )
        }
    }

    @Test
    fun every_page_stays_inside_the_printable_area() {
        val doc = template("classic")
        val document = renderer.render(doc, invoice(60))
        val bottomLimit = PageSpec.A4_HEIGHT_PT - doc.page.marginBottomPt

        document.pages.forEachIndexed { index, page ->
            page.commands.flattenCommands().forEach { command ->
                val bottom = when (command) {
                    is DrawCommand.Text -> command.yPt + command.paragraph.heightPt
                    is DrawCommand.Fill -> command.rect.bottom
                    is DrawCommand.Borders -> command.rect.bottom
                    is DrawCommand.Image -> command.rect.bottom
                    is DrawCommand.Group -> 0f
                }
                assertTrue(
                    bottom <= bottomLimit + 1f,
                    "page ${index + 1}: content reaches ${bottom}pt, past the ${bottomLimit}pt margin",
                )
            }
        }
    }

    @Test
    fun content_starts_below_the_top_margin_on_every_page() {
        val doc = template("classic")
        val document = renderer.render(doc, invoice(60))
        document.pages.forEachIndexed { index, page ->
            val top = page.commands.flattenCommands().minOfOrNull { command ->
                when (command) {
                    is DrawCommand.Text -> command.yPt
                    is DrawCommand.Fill -> command.rect.y
                    is DrawCommand.Borders -> command.rect.y
                    is DrawCommand.Image -> command.rect.y
                    is DrawCommand.Group -> Float.MAX_VALUE
                }
            } ?: return@forEachIndexed
            assertTrue(
                top >= doc.page.marginTopPt - 1f,
                "page ${index + 1} starts at ${top}pt, above the ${doc.page.marginTopPt}pt margin",
            )
        }
    }

    @Test
    fun page_count_grows_with_the_item_count() {
        val small = renderer.render(template("classic"), invoice(10)).pageCount
        val large = renderer.render(template("classic"), invoice(120)).pageCount
        assertTrue(large > small, "120 items should need more pages than 10 (got $large vs $small)")
    }

    @Test
    fun minimal_template_paginates_too() {
        val document = renderer.render(template("minimal"), invoice(60))
        assertTrue(document.pageCount >= 2)
        val everything = document.pages.joinToString(" ") { textOf(it) }
        assertTrue(everything.contains("Line item number 60 "), "last item missing from minimal")
    }
}
