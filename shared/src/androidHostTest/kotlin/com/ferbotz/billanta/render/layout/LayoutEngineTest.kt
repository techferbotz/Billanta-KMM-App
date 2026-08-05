package com.ferbotz.billanta.render.layout

import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.domain.model.CompanySnapshot
import com.ferbotz.billanta.domain.model.CustomerSnapshot
import com.ferbotz.billanta.domain.model.InvoiceItemRecord
import com.ferbotz.billanta.domain.model.InvoiceRecord
import com.ferbotz.billanta.render.BindingContext
import com.ferbotz.billanta.render.PageSpec
import com.ferbotz.billanta.render.TemplateDoc
import com.ferbotz.billanta.render.TemplateParser
import com.ferbotz.billanta.render.bindingDataFor
import com.ferbotz.billanta.render.text.FakeTextShaper
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Golden geometry tests over the real compiled templates served by the backend
 * (`GET /templates/{classic,minimal}/compiled`, stored under `androidHostTest/resources`).
 *
 * A [FakeTextShaper] supplies deterministic metrics, so these assert exact numbers without
 * depending on a font, a device, or the host's text stack.
 */
class LayoutEngineTest {

    private val shaper = FakeTextShaper()
    private val engine = LayoutEngine(shaper)

    private fun loadTemplate(name: String): TemplateDoc {
        val candidates = listOf(
            File("src/androidHostTest/resources/templates/$name.json"),
            File("shared/src/androidHostTest/resources/templates/$name.json"),
        )
        val file = candidates.firstOrNull { it.exists() }
        assertNotNull(file, "fixture $name.json not found")
        return assertNotNull(TemplateParser.parse(file.readText()), "failed to parse $name.json")
    }

    private fun invoice(itemCount: Int) = InvoiceRecord(
        id = "inv-1",
        invoiceNumber = "INV-2026-0042",
        invoiceDateMillis = Iso8601.epochMillisFor(2026, 8, 5),
        dueDateMillis = Iso8601.epochMillisFor(2026, 8, 19),
        currency = "INR",
        companySnapshot = CompanySnapshot(
            name = "Studio Nine",
            gstin = "27ABCDE1234F1Z5",
            addressLine1 = "A-901, Oberoi Springs",
            city = "Mumbai",
            pincode = "400053",
            stateCode = "27",
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
            stateCode = "27",
        ),
        items = (1..itemCount).map { i ->
            InvoiceItemRecord(
                description = "Brand identity design work, phase $i",
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
        notes = "Payable within 14 days via UPI or bank transfer.",
        updatedAtMillis = 1L,
    )

    private fun layout(templateName: String, itemCount: Int): Pair<TemplateDoc, LayoutBox> {
        val doc = loadTemplate(templateName)
        val record = invoice(itemCount)
        val ctx = BindingContext(bindingDataFor(record), record.currency)
        val tree = TemplateFlattener.flatten(doc, ctx)
        return doc to engine.layout(tree, LayoutEngine.pageContentWidth(doc.page))
    }

    private class Placement(val box: LayoutBox, val x: Float, val y: Float)

    private fun collect(box: LayoutBox, x: Float = 0f, y: Float = 0f): List<Placement> {
        val out = ArrayList<Placement>()
        fun walk(b: LayoutBox, px: Float, py: Float) {
            out += Placement(b, px, py)
            b.children.forEach { walk(it.box, px + it.dx, py + it.dy) }
        }
        walk(box, x, y)
        return out
    }

    private fun List<Placement>.tables() = filter { it.box.node is LTable }
    private fun List<Placement>.rows() = filter { it.box.node is LRow }

    // ---- the defects this engine was built to fix ----------------------------------------------

    /**
     * Every column in `classic` is `"auto"`. The previous renderer gave each an equal Compose
     * weight, so "Description" was as narrow as "Qty".
     */
    @Test
    fun auto_columns_are_sized_to_their_content_not_evenly() {
        val (_, root) = layout("classic", itemCount = 3)
        val table = collect(root).tables().firstOrNull()
        assertNotNull(table, "classic should contain a table")

        val bodyRow = collect(table.box).rows().drop(1).firstOrNull()
        assertNotNull(bodyRow, "expected at least one body row")
        val cells = bodyRow.box.children.map { it.box.size.width }
        assertEquals(4, cells.size, "classic's item table has four columns")

        val description = cells[0]
        val others = cells.drop(1)
        assertTrue(
            description > others.max() * 1.5f,
            "description column ($description) should dominate the numeric columns ($others)",
        )
        assertTrue(cells.distinct().size > 1, "columns must not all be the same width")
    }

    /**
     * The compiler copies a cell's computed style onto the text node it synthesises for the
     * cell's content, so applying the box model to both inset the text twice.
     */
    @Test
    fun cell_padding_is_applied_once() {
        val (_, root) = layout("classic", itemCount = 3)
        val table = assertNotNull(collect(root).tables().firstOrNull())
        val placements = collect(table.box, table.x, table.y)

        val bodyRow = assertNotNull(placements.rows().drop(1).firstOrNull())
        val firstCell = assertNotNull(bodyRow.box.children.firstOrNull())
        val cellPlacement = collect(firstCell.box, bodyRow.x + firstCell.dx, bodyRow.y + firstCell.dy)

        val cellBox = cellPlacement.first()
        val text = assertNotNull(
            cellPlacement.firstOrNull { it.box.node is LText },
            "body cell should contain a text node",
        )
        val cellPadding = cellBox.box.metrics.padding
        assertTrue(cellPadding.left > 0f, "fixture should exercise a cell with padding")

        // The text's own box contributes no padding, so its offset inside the cell is exactly
        // one padding-left — not two.
        val inset = text.x - cellBox.x
        assertEquals(cellPadding.left, inset, 0.01f, "text should be inset by exactly one padding")
    }

    @Test
    fun nothing_overflows_the_page_width() {
        listOf("classic", "minimal").forEach { name ->
            val (doc, root) = layout(name, itemCount = 6)
            val contentWidth = LayoutEngine.pageContentWidth(doc.page)
            assertTrue(
                root.size.width <= contentWidth + 0.5f,
                "$name root is ${root.size.width}pt wide, exceeding ${contentWidth}pt of content width",
            )
            collect(root).forEach { placement ->
                assertTrue(
                    placement.x + placement.box.size.width <= contentWidth + 1f,
                    "$name: a ${placement.box.node::class.simpleName} overflows to " +
                        "${placement.x + placement.box.size.width}pt",
                )
            }
        }
    }

    /** More items must produce a taller flow — the input pagination will later slice. */
    @Test
    fun content_height_grows_with_item_count_and_overflows_one_page() {
        val (doc, small) = layout("classic", itemCount = 3)
        val (_, large) = layout("classic", itemCount = 40)
        val pageContentHeight = LayoutEngine.pageContentHeight(doc.page)

        assertTrue(large.size.height > small.size.height, "40 items should be taller than 3")
        assertTrue(
            small.size.height <= pageContentHeight,
            "3 items should fit one page (was ${small.size.height} of $pageContentHeight)",
        )
        assertTrue(
            large.size.height > pageContentHeight,
            "40 items must overflow a single page, which is what pagination exists to handle",
        )
    }

    @Test
    fun repeat_expands_one_row_per_item() {
        listOf(1, 5, 12).forEach { count ->
            val (_, root) = layout("classic", itemCount = count)
            val table = assertNotNull(collect(root).tables().firstOrNull())
            val node = table.box.node as LTable
            assertEquals(count, node.body.size, "expected one body row per item")
        }
    }

    /** An empty optional binding must not leave a blank line behind. */
    @Test
    fun empty_bindings_leave_no_gap() {
        val doc = loadTemplate("classic")
        val withAddress = invoice(2)
        val withoutAddress = withAddress.copy(
            customerSnapshot = withAddress.customerSnapshot?.copy(
                addressLine1 = null,
                city = null,
                pincode = null,
                gstin = null,
            ),
        )
        fun heightOf(record: InvoiceRecord): Float {
            val ctx = BindingContext(bindingDataFor(record), record.currency)
            val tree = TemplateFlattener.flatten(doc, ctx)
            return engine.layout(tree, LayoutEngine.pageContentWidth(doc.page)).size.height
        }
        assertTrue(
            heightOf(withoutAddress) < heightOf(withAddress),
            "dropping address lines should shorten the document, not leave empty line boxes",
        )
    }

    @Test
    fun renders_a_display_list_with_text_and_fills() {
        val doc = loadTemplate("classic")
        val record = invoice(3)
        val ctx = BindingContext(bindingDataFor(record), record.currency)
        val tree = TemplateFlattener.flatten(doc, ctx)
        val document = engine.render(tree, doc.page)

        assertEquals(1, document.pageCount)
        val commands = document.pages.single().commands.flattenCommands()
        val texts = commands.filterIsInstance<DrawCommand.Text>()
        val fills = commands.filterIsInstance<DrawCommand.Fill>()

        assertTrue(texts.size > 10, "expected many text runs, got ${texts.size}")
        assertTrue(fills.isNotEmpty(), "classic paints a dark table header, so fills must exist")
        assertTrue(
            commands.filterIsInstance<DrawCommand.Borders>().isNotEmpty(),
            "classic rules every table row with a bottom border",
        )

        // Everything is inside the printable area.
        texts.forEach { text ->
            assertTrue(text.xPt >= doc.page.marginLeftPt - 0.5f, "text starts left of the margin")
            assertTrue(
                text.xPt <= PageSpec.A4_WIDTH_PT - doc.page.marginRightPt + 0.5f,
                "text starts right of the printable area",
            )
        }
    }

    @Test
    fun invoice_number_and_totals_reach_the_page() {
        val doc = loadTemplate("classic")
        val record = invoice(2)
        val ctx = BindingContext(bindingDataFor(record), record.currency)
        val tree = TemplateFlattener.flatten(doc, ctx)
        val commands = engine.render(tree, doc.page).pages.single().commands.flattenCommands()

        val allText = commands.filterIsInstance<DrawCommand.Text>()
            .flatMap { it.paragraph.lines }
            .flatMap { line -> line.runs.map { it.text } }
            .joinToString(" ")

        assertTrue(allText.contains("INV-2026-0042"), "invoice number missing from the render")
        assertTrue(allText.contains("Kavya Iyer"), "customer name missing from the render")
        assertTrue(allText.contains("Studio Nine"), "company name missing from the render")
        assertTrue(allText.contains("₹"), "amounts should be formatted in rupees")
    }
}
