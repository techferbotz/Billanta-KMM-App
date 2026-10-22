package com.ferbotz.billanta.render.layout

import com.ferbotz.billanta.records.Invoice
import com.ferbotz.billanta.render.InvoiceRenderer
import com.ferbotz.billanta.render.PageSpec
import com.ferbotz.billanta.render.TemplateDoc
import com.ferbotz.billanta.render.TestInvoices
import com.ferbotz.billanta.render.text.FakeTextShaper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Pagination over the backend's own Classic: long invoices must page, not clip. */
class PaginatorTest {

    private val renderer = InvoiceRenderer(FakeTextShaper())

    private val classic: TemplateDoc get() = TestInvoices.classic

    private fun invoice(itemCount: Int): Invoice =
        TestInvoices.invoice(itemCount, notes = null) { "Line item number $it for the engagement" }

    private fun render(doc: TemplateDoc, invoice: Invoice) =
        renderer.render(doc, invoice.renderContext(), TestInvoices.optionLists)

    private fun textOf(page: RenderedPage): String =
        page.commands.flattenCommands()
            .filterIsInstance<DrawCommand.Text>()
            .flatMap { it.paragraph.lines }
            .flatMap { line -> line.runs.map { it.text } }
            .joinToString(" ")

    @Test
    fun short_invoice_stays_on_one_page() {
        val document = render(classic, invoice(3))
        assertEquals(1, document.pageCount)
    }

    @Test
    fun long_invoice_paginates_instead_of_clipping() {
        val document = render(classic, invoice(60))
        assertTrue(document.pageCount >= 2, "60 items should need more than one page")
        assertTrue(document.pageCount < 20, "60 items should not explode into ${document.pageCount} pages")
    }

    /** The failure this replaces: content past the first page was silently dropped. */
    @Test
    fun no_item_is_lost_across_the_page_break() {
        val itemCount = 60
        val document = render(classic, invoice(itemCount))
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
        val document = render(classic, invoice(60))
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
        val doc = classic
        val document = render(doc, invoice(60))
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
        val doc = classic
        val document = render(doc, invoice(60))
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
        val small = render(classic, invoice(10)).pageCount
        val large = render(classic, invoice(120)).pageCount
        assertTrue(large > small, "120 items should need more pages than 10 (got $large vs $small)")
    }
}
