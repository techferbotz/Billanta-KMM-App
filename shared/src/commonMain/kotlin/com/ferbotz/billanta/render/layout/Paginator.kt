package com.ferbotz.billanta.render.layout

import com.ferbotz.billanta.render.PageSpec

/**
 * Slices one continuous laid-out flow into pages.
 *
 * Splitting happens between children, never through them: a paragraph or an image is atomic and
 * moves whole to the next page. Containers are cut open, and when a table is cut its header rows
 * are repeated at the top of the continuation so a long item list stays readable.
 */
class Paginator(private val engine: LayoutEngine) {

    fun paginate(root: LayoutBox, page: PageSpec): RenderedDocument {
        val budget = LayoutEngine.pageContentHeight(page)
        val pages = ArrayList<RenderedPage>()
        var remainder: LayoutBox? = root
        var guard = 0

        while (remainder != null && guard++ < MAX_PAGES) {
            val current = remainder
            val split = split(current, budget, atPageStart = true)
            val head = split.head ?: current
            val commands = ArrayList<DrawCommand>()
            val sections = ArrayList<SectionBounds>()
            engine.paint(head, page.marginLeftPt, page.marginTopPt, commands, sections)
            pages += RenderedPage(commands, sections)
            remainder = split.tail
        }

        return RenderedDocument(
            pageWidthPt = PageSpec.A4_WIDTH_PT,
            pageHeightPt = PageSpec.A4_HEIGHT_PT,
            pages = pages,
        )
    }

    private class Split(val head: LayoutBox?, val tail: LayoutBox?)

    /**
     * Cuts [box] so its first fragment fits inside [available] points.
     *
     * [atPageStart] means there is nothing above it on this page, so something must be emitted
     * even if it overflows — otherwise an oversized block would bounce between pages forever.
     */
    private fun split(box: LayoutBox, available: Float, atPageStart: Boolean): Split {
        if (box.size.height <= available + EPSILON) return Split(box, null)

        // Atomic content moves down whole, or — if it could never fit anywhere — is allowed to
        // overflow rather than bounce between pages forever. A table row counts as atomic even
        // though it has children: cutting one open would strand half of each cell on each page.
        if (box.children.isEmpty() || box.node is LRow || box.node is LCell) {
            return if (atPageStart) Split(box, null) else Split(null, box)
        }

        val m = box.metrics
        val topInset = m.margin.top + m.border.top + m.padding.top
        val bottomInset = m.margin.bottom + m.border.bottom + m.padding.bottom

        val headChildren = ArrayList<Placed>()
        val tailChildren = ArrayList<Placed>()
        var splitY = topInset
        var crossed = false

        box.children.forEachIndexed { index, child ->
            val childTop = child.dy
            val childBottom = childTop + child.box.size.height

            when {
                crossed -> tailChildren += child

                childBottom <= available + EPSILON -> {
                    headChildren += child
                    splitY = childBottom
                }

                else -> {
                    crossed = true
                    val childBudget = available - childTop
                    val startsThisPage = atPageStart && headChildren.isEmpty()
                    val inner = split(child.box, childBudget, startsThisPage)

                    if (inner.head != null) {
                        headChildren += Placed(inner.head, child.dx, child.dy)
                        splitY = childTop + inner.head.size.height
                    } else {
                        splitY = childTop
                    }
                    if (inner.tail != null) tailChildren += Placed(inner.tail, child.dx, childTop)
                }
            }
        }

        if (tailChildren.isEmpty()) return Split(box, null)
        if (headChildren.isEmpty() && !atPageStart) return Split(null, box)

        // A split container keeps its top edge on the first fragment and its bottom edge on the
        // last, so a bordered box that spans a break isn't closed off twice.
        val headBox = LayoutBox(
            node = box.node,
            metrics = m.copy(
                border = m.border.copy(bottom = 0f),
                padding = m.padding.copy(bottom = 0f),
                margin = m.margin.copy(bottom = 0f),
            ),
            size = SizePt(box.size.width, splitY),
            children = headChildren,
            paragraph = box.paragraph,
            firstBaseline = box.firstBaseline,
        )

        val repeated = repeatedHeaderRows(box)
        val headerHeight = repeated.sumOf { it.box.size.height.toDouble() }.toFloat()

        val rebased = tailChildren.mapIndexed { index, placed ->
            // The fragment that was cut open resumes at the top of the next page; everything
            // after it keeps its original spacing relative to the cut.
            val isResumedFragment = index == 0 && crossed && placed.dy < splitY
            val dy = if (isResumedFragment) topInset else topInset + (placed.dy - splitY)
            Placed(placed.box, placed.dx, dy + headerHeight)
        }

        val tailContentHeight = rebased.maxOfOrNull { it.dy + it.box.size.height - topInset } ?: 0f
        val tailBox = LayoutBox(
            node = box.node,
            metrics = m.copy(
                border = m.border.copy(top = 0f),
                padding = m.padding.copy(top = 0f),
                margin = m.margin.copy(top = 0f),
            ),
            size = SizePt(box.size.width, topInset + tailContentHeight + bottomInset),
            children = repeated + rebased,
            paragraph = box.paragraph,
            firstBaseline = box.firstBaseline,
        )

        return Split(headBox, tailBox)
    }

    /**
     * Header rows of a table, re-placed at the top of a continuation fragment. Without this a
     * multi-page item list loses its column captions after page one.
     */
    private fun repeatedHeaderRows(box: LayoutBox): List<Placed> {
        val table = box.node as? LTable ?: return emptyList()
        if (table.header.isEmpty()) return emptyList()

        val m = box.metrics
        val originX = m.margin.left + m.border.left + m.padding.left
        var y = m.margin.top + m.border.top + m.padding.top
        return box.children.take(table.header.size).map { header ->
            Placed(header.box, originX, y).also { y += header.box.size.height }
        }
    }

    private companion object {
        /** Float comparison slack, well below a printer's resolution. */
        const val EPSILON = 0.01f

        /** Backstop against a pathological template that never consumes its content. */
        const val MAX_PAGES = 200
    }
}
