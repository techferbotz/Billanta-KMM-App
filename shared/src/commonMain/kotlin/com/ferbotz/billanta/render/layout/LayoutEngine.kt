package com.ferbotz.billanta.render.layout

import com.ferbotz.billanta.render.PageSpec
import com.ferbotz.billanta.render.text.ShapedParagraph
import com.ferbotz.billanta.render.text.TextShaper

/** A measured node: outer (margin-box) size plus its children placed relative to that box. */
class LayoutBox(
    val node: LNode,
    val metrics: BoxMetrics,
    val size: SizePt,
    val children: List<Placed> = emptyList(),
    val paragraph: ShapedParagraph? = null,
    /** Distance from the outer top edge to the first text baseline, for `align-items: baseline`. */
    val firstBaseline: Float? = null,
)

/** A child positioned relative to its parent's outer top-left corner. */
class Placed(val box: LayoutBox, val dx: Float, val dy: Float)

private enum class SizeMode { Fill, ShrinkToFit }

/**
 * Turns a flattened tree into positioned geometry, then into a display list.
 *
 * Deliberately free of any Compose dependency: text measurement enters through [TextShaper] and
 * image dimensions through [imageSizes], so the whole engine runs — and is tested — off-device.
 */
class LayoutEngine(
    private val shaper: TextShaper,
    /** Intrinsic sizes of preloaded images, keyed by URL. Missing entries fall back to a square. */
    private val imageSizes: Map<String, SizePt> = emptyMap(),
) {

    /** Lays the tree out into one continuous flow; pagination slices it afterwards. */
    fun layout(root: LNode, contentWidthPt: Float): LayoutBox =
        measure(root, contentWidthPt, SizeMode.Fill)

    /** Convenience for the single-page case and for tests. */
    fun render(root: LNode, page: PageSpec): RenderedDocument {
        val contentWidth = pageContentWidth(page)
        val box = layout(root, contentWidth)
        val commands = ArrayList<DrawCommand>()
        val sections = ArrayList<SectionBounds>()
        paint(box, page.marginLeftPt, page.marginTopPt, commands, sections)
        return RenderedDocument(
            pageWidthPt = PageSpec.A4_WIDTH_PT,
            pageHeightPt = PageSpec.A4_HEIGHT_PT,
            pages = listOf(RenderedPage(commands, sections)),
        )
    }

    // ---- measurement ---------------------------------------------------------------------------

    private fun measure(node: LNode, availableWidth: Float, mode: SizeMode): LayoutBox {
        val m = BoxMetrics.of(node.style, availableWidth)
        val fillWidth = (availableWidth - m.frameHorizontal).coerceAtLeast(0f)
        val contentWidth = m.clampWidth(
            when {
                m.contentWidth != null -> m.contentWidth
                mode == SizeMode.ShrinkToFit -> minOf(maxContentWidth(node, availableWidth), fillWidth)
                else -> fillWidth
            },
        )

        return when (node) {
            is LText -> measureText(node, m, contentWidth)
            is LImage -> measureImage(node, m, contentWidth)
            is LPlaceholder -> finish(node, m, contentWidth, contentHeight = node.heightPt)
            is LDivider -> finish(node, m, contentWidth, contentHeight = 0f)
            is LBox -> measureBox(node, m, contentWidth)
            is LTable -> measureTable(node, m, contentWidth)
            is LRow -> measureBox(LBox(node.style, node.cells, node.section), m, contentWidth)
            is LCell -> measureBox(LBox(node.style, node.children, node.section), m, contentWidth)
        }
    }

    private fun measureText(node: LText, m: BoxMetrics, contentWidth: Float): LayoutBox {
        val paragraph = shaper.shape(node.runs, node.paragraphStyle, contentWidth)
        val baseline = paragraph.lines.firstOrNull()?.baselinePt
        return LayoutBox(
            node = node,
            metrics = m,
            size = SizePt(
                width = contentWidth + m.frameHorizontal,
                height = m.clampHeight(m.contentHeight ?: paragraph.heightPt) + m.frameVertical,
            ),
            paragraph = paragraph,
            firstBaseline = baseline?.let { it + m.margin.top + m.border.top + m.padding.top },
        )
    }

    private fun measureImage(node: LImage, m: BoxMetrics, contentWidth: Float): LayoutBox {
        val intrinsic = imageSizes[node.url]
        val aspect = if (intrinsic != null && intrinsic.height > 0f) {
            intrinsic.width / intrinsic.height
        } else {
            1f
        }
        val width = m.contentWidth ?: intrinsic?.width ?: DEFAULT_IMAGE_SIDE
        val height = m.contentHeight ?: (width / aspect)
        return finish(node, m, m.clampWidth(width), m.clampHeight(height))
    }

    private fun measureBox(node: LBox, m: BoxMetrics, contentWidth: Float): LayoutBox {
        val isFlex = node.style.display == "flex"
        val isRow = (node.style.flexDirection ?: "row") == "row"
        return if (isFlex && isRow) {
            measureFlexRow(node, m, contentWidth)
        } else {
            measureStack(node, m, contentWidth, gap = if (isFlex) node.style.gapPt ?: 0f else 0f)
        }
    }

    /** Block flow, and column-direction flex — both stack children and stretch them to fit. */
    private fun measureStack(node: LBox, m: BoxMetrics, contentWidth: Float, gap: Float): LayoutBox {
        val originX = m.margin.left + m.border.left + m.padding.left
        val originY = m.margin.top + m.border.top + m.padding.top

        val placed = ArrayList<Placed>(node.children.size)
        var y = originY
        node.children.forEachIndexed { index, child ->
            if (index > 0) y += gap
            val childMetrics = BoxMetrics.of(child.style, contentWidth)
            // A child with auto side margins shrinks to its content so the margins can centre or
            // right-align it — that is how `margin-left: auto` pushes a summary panel right.
            val shrink = childMetrics.marginAutoLeft || childMetrics.marginAutoRight
            val box = measure(child, contentWidth, if (shrink) SizeMode.ShrinkToFit else SizeMode.Fill)
            val free = (contentWidth - box.size.width).coerceAtLeast(0f)
            val dx = when {
                childMetrics.marginAutoLeft && childMetrics.marginAutoRight -> free / 2f
                childMetrics.marginAutoLeft -> free
                else -> 0f
            }
            placed += Placed(box, originX + dx, y)
            y += box.size.height
        }

        val contentHeight = (y - originY).coerceAtLeast(0f)
        return LayoutBox(
            node = node,
            metrics = m,
            size = SizePt(
                width = contentWidth + m.frameHorizontal,
                height = m.clampHeight(m.contentHeight ?: contentHeight) + m.frameVertical,
            ),
            children = placed,
            firstBaseline = placed.firstNotNullOfOrNull { p -> p.box.firstBaseline?.plus(p.dy) },
        )
    }

    private fun measureFlexRow(node: LBox, m: BoxMetrics, contentWidth: Float): LayoutBox {
        val gap = node.style.gapPt ?: 0f
        val items = node.children
        if (items.isEmpty()) return finish(node, m, contentWidth, 0f)

        val itemMetrics = items.map { BoxMetrics.of(it.style, contentWidth) }
        // Flex base size: an explicit width wins, otherwise the item is sized to its content.
        val bases = items.mapIndexed { i, child ->
            val explicit = itemMetrics[i].contentWidth
            if (explicit != null) explicit + itemMetrics[i].frameHorizontal
            else minOf(maxContentWidth(child, contentWidth), contentWidth)
        }

        val totalGap = gap * (items.size - 1)
        var free = contentWidth - bases.sum() - totalGap
        val widths = bases.toMutableList()

        val grows = items.map { it.style.flexGrow ?: 0f }
        val growTotal = grows.sum()
        if (free > 0f && growTotal > 0f) {
            val share = free
            items.indices.forEach { i -> widths[i] += share * (grows[i] / growTotal) }
            free = 0f
        } else if (free < 0f) {
            // Shrink proportionally to base size, never below each item's min-content width.
            val floors = items.mapIndexed { i, child -> minContentWidth(child, contentWidth) }
            val shrinkable = items.indices.sumOf { (widths[it] - floors[it]).coerceAtLeast(0f).toDouble() }
            if (shrinkable > 0.0) {
                var deficit = -free
                items.indices.forEach { i ->
                    val room = (widths[i] - floors[i]).coerceAtLeast(0f)
                    val take = (deficit * (room / shrinkable)).toFloat()
                    widths[i] = (widths[i] - take).coerceAtLeast(floors[i])
                }
                deficit = 0f
            }
            free = (contentWidth - widths.sum() - totalGap)
        }

        // Auto side margins absorb whatever is still free, before justify-content sees it.
        val autoSlots = itemMetrics.sumOf {
            (if (it.marginAutoLeft) 1 else 0) + (if (it.marginAutoRight) 1 else 0)
        }
        val autoShare = if (autoSlots > 0 && free > 0f) free / autoSlots else 0f
        if (autoSlots > 0) free = 0f

        val boxes = items.mapIndexed { i, child -> measureAtOuterWidth(child, widths[i], contentWidth) }
        val lineHeight = boxes.maxOfOrNull { it.size.height } ?: 0f
        val maxBaseline = boxes.mapNotNull { it.firstBaseline }.maxOrNull()

        val originX = m.margin.left + m.border.left + m.padding.left
        val originY = m.margin.top + m.border.top + m.padding.top
        val used = widths.sum() + totalGap + autoShare * autoSlots
        val leftover = (contentWidth - used).coerceAtLeast(0f)

        var x = originX + when (node.style.justifyContent) {
            "center" -> leftover / 2f
            "flex-end" -> leftover
            else -> 0f
        }
        val between = when (node.style.justifyContent) {
            "space-between" -> if (items.size > 1) leftover / (items.size - 1) else 0f
            "space-around" -> if (items.isNotEmpty()) leftover / items.size else 0f
            "space-evenly" -> if (items.isNotEmpty()) leftover / (items.size + 1) else 0f
            else -> 0f
        }
        if (node.style.justifyContent == "space-around") x += between / 2f
        if (node.style.justifyContent == "space-evenly") x += between

        val placed = ArrayList<Placed>(items.size)
        boxes.forEachIndexed { i, box ->
            if (i > 0) x += gap + between
            if (itemMetrics[i].marginAutoLeft) x += autoShare
            val dy = originY + crossOffset(node.style.alignItems, box, lineHeight, maxBaseline)
            placed += Placed(box, x, dy)
            x += box.size.width
            if (itemMetrics[i].marginAutoRight) x += autoShare
        }

        return LayoutBox(
            node = node,
            metrics = m,
            size = SizePt(
                width = contentWidth + m.frameHorizontal,
                height = m.clampHeight(m.contentHeight ?: lineHeight) + m.frameVertical,
            ),
            children = placed,
            firstBaseline = maxBaseline?.let { it + originY },
        )
    }

    private fun crossOffset(
        alignItems: String?,
        box: LayoutBox,
        lineHeight: Float,
        maxBaseline: Float?,
    ): Float = when (alignItems) {
        "center" -> (lineHeight - box.size.height) / 2f
        "flex-end" -> lineHeight - box.size.height
        // Baseline alignment shifts each item so its first text baseline lands on the shared one.
        "baseline" -> if (maxBaseline != null && box.firstBaseline != null) {
            maxBaseline - box.firstBaseline
        } else {
            0f
        }
        else -> 0f
    }

    /** Re-measures a child once its flex width is final. */
    private fun measureAtOuterWidth(node: LNode, outerWidth: Float, containingWidth: Float): LayoutBox {
        val m = BoxMetrics.of(node.style, containingWidth)
        val contentWidth = m.clampWidth((outerWidth - m.frameHorizontal).coerceAtLeast(0f))
        return when (node) {
            is LText -> measureText(node, m, contentWidth)
            is LImage -> measureImage(node, m, contentWidth)
            is LPlaceholder -> finish(node, m, contentWidth, node.heightPt)
            is LDivider -> finish(node, m, contentWidth, 0f)
            is LBox -> measureBox(node, m, contentWidth)
            is LTable -> measureTable(node, m, contentWidth)
            is LRow -> measureBox(LBox(node.style, node.cells, node.section), m, contentWidth)
            is LCell -> measureBox(LBox(node.style, node.children, node.section), m, contentWidth)
        }
    }

    private fun finish(node: LNode, m: BoxMetrics, contentWidth: Float, contentHeight: Float) =
        LayoutBox(
            node = node,
            metrics = m,
            size = SizePt(
                width = contentWidth + m.frameHorizontal,
                height = m.clampHeight(m.contentHeight ?: contentHeight) + m.frameVertical,
            ),
        )

    // ---- tables --------------------------------------------------------------------------------

    /**
     * Column widths follow CSS automatic table layout: measure every row's cells, honour fixed
     * columns, then share what is left among the `auto` columns in proportion to their content.
     * Every column in the shipped templates is `auto`, so this is the common path, not a corner.
     */
    private fun measureTable(node: LTable, m: BoxMetrics, contentWidth: Float): LayoutBox {
        val rows = node.allRows
        val columnCount = maxOf(
            node.columns.size,
            rows.maxOfOrNull { row -> row.cells.sumOf { it.colSpan } } ?: 0,
        )
        if (columnCount == 0 || rows.isEmpty()) return finish(node, m, contentWidth, 0f)

        val columnWidths = solveColumns(node, rows, columnCount, contentWidth)

        val originX = m.margin.left + m.border.left + m.padding.left
        val originY = m.margin.top + m.border.top + m.padding.top
        val placed = ArrayList<Placed>(rows.size)
        var y = originY

        rows.forEach { row ->
            val rowMetrics = BoxMetrics.of(row.style, contentWidth)
            val rowOriginX = rowMetrics.margin.left + rowMetrics.border.left + rowMetrics.padding.left
            val rowOriginY = rowMetrics.margin.top + rowMetrics.border.top + rowMetrics.padding.top

            var column = 0
            var x = rowOriginX
            val cells = ArrayList<Placed>(row.cells.size)
            row.cells.forEach { cell ->
                val span = cell.colSpan.coerceAtMost((columnCount - column).coerceAtLeast(1))
                val width = (column until minOf(column + span, columnCount))
                    .sumOf { columnWidths[it].toDouble() }
                    .toFloat()
                val box = measureAtOuterWidth(cell, width, contentWidth)
                cells += Placed(box, x, rowOriginY)
                x += width
                column += span
            }

            val rowContentHeight = cells.maxOfOrNull { it.box.size.height } ?: 0f
            // Every cell in a row shares the row's height so backgrounds and rules line up.
            val stretched = cells.map { placedCell ->
                Placed(stretchTo(placedCell.box, rowContentHeight), placedCell.dx, placedCell.dy)
            }
            val rowBox = LayoutBox(
                node = row,
                metrics = rowMetrics,
                size = SizePt(
                    width = contentWidth,
                    height = rowContentHeight + rowMetrics.frameVertical,
                ),
                children = stretched,
            )
            placed += Placed(rowBox, originX, y)
            y += rowBox.size.height
        }

        val tableHeight = (y - originY).coerceAtLeast(0f)
        return LayoutBox(
            node = node,
            metrics = m,
            size = SizePt(
                width = contentWidth + m.frameHorizontal,
                height = m.clampHeight(m.contentHeight ?: tableHeight) + m.frameVertical,
            ),
            children = placed,
        )
    }

    private fun solveColumns(
        node: LTable,
        rows: List<LRow>,
        columnCount: Int,
        available: Float,
    ): FloatArray {
        val minContent = FloatArray(columnCount)
        val maxContent = FloatArray(columnCount)

        rows.forEach { row ->
            var column = 0
            row.cells.forEach { cell ->
                val span = cell.colSpan.coerceAtMost((columnCount - column).coerceAtLeast(1))
                val cellMin = minContentWidth(cell, available)
                val cellMax = maxContentWidth(cell, available)
                if (span == 1 && column < columnCount) {
                    minContent[column] = maxOf(minContent[column], cellMin)
                    maxContent[column] = maxOf(maxContent[column], cellMax)
                } else {
                    // A spanning cell only has to fit across the columns it covers, so spread its
                    // demand evenly rather than forcing any single column wide.
                    val share = span.coerceAtLeast(1)
                    for (i in column until minOf(column + span, columnCount)) {
                        minContent[i] = maxOf(minContent[i], cellMin / share)
                        maxContent[i] = maxOf(maxContent[i], cellMax / share)
                    }
                }
                column += span
            }
        }

        val widths = FloatArray(columnCount)
        val isAuto = BooleanArray(columnCount) { index ->
            node.columns.getOrNull(index)?.isAuto ?: true
        }
        var fixedTotal = 0f
        for (i in 0 until columnCount) {
            if (!isAuto[i]) {
                widths[i] = node.columns[i].widthPt ?: 0f
                fixedTotal += widths[i]
            }
        }

        val autoIndices = (0 until columnCount).filter { isAuto[it] }
        if (autoIndices.isEmpty()) return widths

        val remaining = (available - fixedTotal).coerceAtLeast(0f)
        val autoMaxTotal = autoIndices.sumOf { maxContent[it].toDouble() }.toFloat()
        val autoMinTotal = autoIndices.sumOf { minContent[it].toDouble() }.toFloat()

        when {
            // Everything fits: give each column its content width, then share the slack so the
            // table still fills its container.
            autoMaxTotal <= remaining && autoMaxTotal > 0f -> {
                val slack = remaining - autoMaxTotal
                autoIndices.forEach { i ->
                    widths[i] = maxContent[i] + slack * (maxContent[i] / autoMaxTotal)
                }
            }
            // Too wide: interpolate between min and max so wide columns give up the most.
            autoMaxTotal > remaining && autoMaxTotal > autoMinTotal -> {
                val scale = ((remaining - autoMinTotal) / (autoMaxTotal - autoMinTotal)).coerceIn(0f, 1f)
                autoIndices.forEach { i ->
                    widths[i] = minContent[i] + (maxContent[i] - minContent[i]) * scale
                }
            }
            else -> {
                val even = remaining / autoIndices.size
                autoIndices.forEach { i -> widths[i] = even }
            }
        }
        return widths
    }

    /** Grows a cell box to the row's height so its background and borders cover the full row. */
    private fun stretchTo(box: LayoutBox, contentHeight: Float): LayoutBox =
        if (box.size.height >= contentHeight) box
        else LayoutBox(
            node = box.node,
            metrics = box.metrics,
            size = SizePt(box.size.width, contentHeight),
            children = box.children,
            paragraph = box.paragraph,
            firstBaseline = box.firstBaseline,
        )

    // ---- intrinsic widths ----------------------------------------------------------------------

    private fun maxContentWidth(node: LNode, containing: Float): Float {
        val m = BoxMetrics.of(node.style, containing)
        m.contentWidth?.let { return m.clampWidth(it) + m.frameHorizontal }
        val inner = when (node) {
            is LText -> shaper.intrinsicWidths(node.runs, node.paragraphStyle).maxPt
            is LImage -> imageSizes[node.url]?.width ?: DEFAULT_IMAGE_SIDE
            is LPlaceholder -> 0f
            is LDivider -> 0f
            is LBox ->
                if (node.style.display == "flex" && (node.style.flexDirection ?: "row") == "row") {
                    node.children.sumOf { maxContentWidth(it, containing).toDouble() }.toFloat() +
                        (node.style.gapPt ?: 0f) * (node.children.size - 1).coerceAtLeast(0)
                } else {
                    node.children.maxOfOrNull { maxContentWidth(it, containing) } ?: 0f
                }
            is LCell -> node.children.maxOfOrNull { maxContentWidth(it, containing) } ?: 0f
            is LRow -> node.cells.sumOf { maxContentWidth(it, containing).toDouble() }.toFloat()
            is LTable -> node.allRows.maxOfOrNull { maxContentWidth(it, containing) } ?: 0f
        }
        return m.clampWidth(inner) + m.frameHorizontal
    }

    private fun minContentWidth(node: LNode, containing: Float): Float {
        val m = BoxMetrics.of(node.style, containing)
        m.contentWidth?.let { return m.clampWidth(it) + m.frameHorizontal }
        val inner = when (node) {
            is LText -> shaper.intrinsicWidths(node.runs, node.paragraphStyle).minPt
            is LImage -> imageSizes[node.url]?.width ?: DEFAULT_IMAGE_SIDE
            is LPlaceholder -> 0f
            is LDivider -> 0f
            is LBox -> node.children.maxOfOrNull { minContentWidth(it, containing) } ?: 0f
            is LCell -> node.children.maxOfOrNull { minContentWidth(it, containing) } ?: 0f
            is LRow -> node.cells.sumOf { minContentWidth(it, containing).toDouble() }.toFloat()
            is LTable -> node.allRows.maxOfOrNull { minContentWidth(it, containing) } ?: 0f
        }
        return m.clampWidth(inner) + m.frameHorizontal
    }

    // ---- painting ------------------------------------------------------------------------------

    /**
     * Emits draw commands for [box] with its outer top-left corner at ([x], [y]).
     *
     * [sections], when given, collects where each tagged section landed so the editor can overlay
     * a placeholder on an empty one and know what was tapped.
     */
    fun paint(
        box: LayoutBox,
        x: Float,
        y: Float,
        out: MutableList<DrawCommand>,
        sections: MutableList<SectionBounds>? = null,
    ) {
        val m = box.metrics
        val borderBox = RectPt(
            x = x + m.margin.left,
            y = y + m.margin.top,
            width = (box.size.width - m.margin.horizontal).coerceAtLeast(0f),
            height = (box.size.height - m.margin.vertical).coerceAtLeast(0f),
        )

        box.node.section?.let { id ->
            sections?.add(SectionBounds(id, borderBox, isEmpty = box.node is LPlaceholder))
        }

        val own = ArrayList<DrawCommand>()
        m.backgroundArgb?.let { own += DrawCommand.Fill(borderBox, it, m.radiusPt) }
        if (!m.border.isEmpty && !m.borderColors.isEmpty) {
            own += DrawCommand.Borders(borderBox, m.border, m.borderColors, m.radiusPt, m.borderStyles)
        }

        val content = borderBox.deflate(m.contentInset)
        when (val node = box.node) {
            is LText -> box.paragraph?.let { own += DrawCommand.Text(it, content.x, content.y) }
            is LImage -> own += DrawCommand.Image(content, node.url, node.cover)
            else -> Unit
        }

        box.children.forEach { child -> paint(child.box, x + child.dx, y + child.dy, own, sections) }

        val alpha = m.opacity
        if (alpha != null && alpha < 1f) out += DrawCommand.Group(alpha.coerceIn(0f, 1f), own)
        else out += own
    }

    companion object {
        /** Used when a template gives an image no size and it has not been preloaded yet. */
        const val DEFAULT_IMAGE_SIDE = 48f

        fun pageContentWidth(page: PageSpec): Float =
            PageSpec.A4_WIDTH_PT - page.marginLeftPt - page.marginRightPt

        fun pageContentHeight(page: PageSpec): Float =
            PageSpec.A4_HEIGHT_PT - page.marginTopPt - page.marginBottomPt
    }
}
