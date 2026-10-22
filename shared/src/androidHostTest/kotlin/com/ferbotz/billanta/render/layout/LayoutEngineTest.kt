package com.ferbotz.billanta.render.layout

import com.ferbotz.billanta.records.Invoice
import com.ferbotz.billanta.render.PageSpec
import com.ferbotz.billanta.render.TemplateDoc
import com.ferbotz.billanta.render.TestInvoices
import com.ferbotz.billanta.render.text.FakeTextShaper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Golden geometry tests over the backend's own compiled Classic (BE-021's `examples/`, stored under
 * `androidHostTest/resources/be021`).
 *
 * A [FakeTextShaper] supplies deterministic metrics, so these assert exact numbers without
 * depending on a font, a device, or the host's text stack.
 */
class LayoutEngineTest {

    private val shaper = FakeTextShaper()
    private val engine = LayoutEngine(shaper)

    private fun invoice(itemCount: Int) = TestInvoices.invoice(itemCount)

    private fun layout(itemCount: Int): Pair<TemplateDoc, LayoutBox> = layoutOf(invoice(itemCount))

    private fun layoutOf(invoice: Invoice): Pair<TemplateDoc, LayoutBox> {
        val doc = TestInvoices.classic
        val tree = TemplateFlattener.flatten(doc, TestInvoices.bindings(doc, invoice))
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
        val (_, root) = layout(itemCount = 3)
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
        val (_, root) = layout(itemCount = 3)
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

        // The assertion above only sees the cell's padding — the text box's own padding would
        // shift its *contents*, not its origin. So check the rule at the source: a text node
        // contributes nothing but text, because the element's box is already the parent cell.
        val textMetrics = text.box.metrics
        assertEquals(EdgesPt.ZERO, textMetrics.padding, "a text node must not re-apply padding")
        assertEquals(EdgesPt.ZERO, textMetrics.border, "a text node must not re-apply borders")
        assertEquals(null, textMetrics.backgroundArgb, "a text node must not re-paint the background")
    }

    /**
     * Classic right-aligns Qty, Rate and Amount. The device shaper used to lay a line that fits out
     * at its own width, which left `textAlign: right` no room to act: every number sat on the left.
     */
    @Test
    fun right_aligned_cells_end_at_their_right_padding() {
        val (_, root) = layout(itemCount = 3)
        val table = assertNotNull(collect(root).tables().firstOrNull())
        val rows = collect(table.box, table.x, table.y).rows().filter { it.box.children.size == 4 }
        assertTrue(rows.size >= 4, "expected the header and three item rows, got ${rows.size}")

        var movedAny = false
        rows.forEach { row ->
            row.box.children.forEachIndexed { column, cell ->
                val cellX = row.x + cell.dx
                val text = assertNotNull(
                    collect(cell.box, cellX, row.y + cell.dy).firstOrNull { it.box.node is LText },
                )
                val tm = text.box.metrics
                val drawX = text.x + tm.margin.left + tm.border.left + tm.padding.left
                val line = assertNotNull(text.box.paragraph).lines.single()
                val start = drawX + line.runs.first().xPt
                val end = drawX + line.runs.last().let { it.xPt + it.widthPt }

                val cm = cell.box.metrics
                if (column == 0) {
                    val inset = cm.margin.left + cm.border.left + cm.padding.left
                    assertEquals(inset, start - cellX, 0.01f, "the description column stays left-aligned")
                } else {
                    val inset = cm.margin.right + cm.border.right + cm.padding.right
                    assertEquals(inset, cellX + cell.box.size.width - end, 0.01f, "column $column ends at its right padding")
                    if (start - cellX > cm.padding.left + 0.5f) movedAny = true
                }
            }
        }
        assertTrue(movedAny, "no numeric cell was narrower than its column, so alignment went untested")
    }

    /**
     * The header cells of `classic` are painted; their text children carry a copy of that same
     * background. Painting both stacks a second, un-themed rectangle over the first.
     */
    @Test
    fun a_cells_background_is_painted_once() {
        val (doc, root) = layout(itemCount = 3)
        val commands = ArrayList<DrawCommand>()
        LayoutEngine(shaper).paint(root, doc.page.marginLeftPt, doc.page.marginTopPt, commands)

        val fills = commands.flattenCommands().filterIsInstance<DrawCommand.Fill>()
        val duplicated = fills.groupBy { it.rect to it.colorArgb }.filterValues { it.size > 1 }
        assertTrue(
            duplicated.isEmpty(),
            "the same rectangle is filled more than once: ${duplicated.keys.take(3)}",
        )
    }

    @Test
    fun nothing_overflows_the_page_width() {
        listOf("classic").forEach { name ->
            val (doc, root) = layout(itemCount = 6)
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
        val (doc, small) = layout(itemCount = 3)
        val (_, large) = layout(itemCount = 40)
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
            val (_, root) = layout(itemCount = count)
            val table = assertNotNull(collect(root).tables().firstOrNull())
            val node = table.box.node as LTable
            assertEquals(count, node.body.size, "expected one body row per item")
        }
    }

    /** An empty optional binding must not leave a blank line behind. */
    @Test
    fun empty_bindings_leave_no_gap() {
        val withAddress = invoice(2)
        val withoutAddress = TestInvoices.invoice(
            2,
            customer = TestInvoices.customer.without("customer_address_line1").without("customer_gstin"),
        )
        fun heightOf(invoice: Invoice): Float = layoutOf(invoice).second.size.height
        assertTrue(
            heightOf(withoutAddress) < heightOf(withAddress),
            "dropping address lines should shorten the document, not leave empty line boxes",
        )
    }

    @Test
    fun renders_a_display_list_with_text_and_fills() {
        val doc = TestInvoices.classic
        val tree = TemplateFlattener.flatten(doc, TestInvoices.bindings(doc, invoice(3)))
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
        val doc = TestInvoices.classic
        val tree = TemplateFlattener.flatten(doc, TestInvoices.bindings(doc, invoice(2)))
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
