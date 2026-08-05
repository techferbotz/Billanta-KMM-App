package com.ferbotz.billanta.render.layout

import com.ferbotz.billanta.render.BindingContext
import com.ferbotz.billanta.render.PageSpec
import com.ferbotz.billanta.render.TBox
import com.ferbotz.billanta.render.TCell
import com.ferbotz.billanta.render.TColumn
import com.ferbotz.billanta.render.TConditional
import com.ferbotz.billanta.render.TDivider
import com.ferbotz.billanta.render.TImage
import com.ferbotz.billanta.render.TNode
import com.ferbotz.billanta.render.TRepeat
import com.ferbotz.billanta.render.TRow
import com.ferbotz.billanta.render.TStyle
import com.ferbotz.billanta.render.TTable
import com.ferbotz.billanta.render.TTableBody
import com.ferbotz.billanta.render.TText
import com.ferbotz.billanta.render.TValue
import com.ferbotz.billanta.render.TemplateDoc
import com.ferbotz.billanta.render.text.ParagraphStyle
import com.ferbotz.billanta.render.text.RunStyle
import com.ferbotz.billanta.render.text.StyledRun
import com.ferbotz.billanta.render.text.TextAlignment

/**
 * The tree after data has been applied: `repeat` expanded, `conditional` gates decided and every
 * binding turned into a literal string. Layout below this point is pure geometry with no
 * knowledge of invoices, which is what makes it testable in isolation.
 */
sealed interface LNode {
    val style: TStyle
}

data class LBox(override val style: TStyle, val children: List<LNode>) : LNode

data class LText(
    override val style: TStyle,
    val runs: List<StyledRun>,
    val paragraphStyle: ParagraphStyle,
) : LNode

data class LImage(override val style: TStyle, val url: String, val cover: Boolean) : LNode

data class LDivider(override val style: TStyle) : LNode

data class LTable(
    override val style: TStyle,
    val columns: List<TColumn>,
    val header: List<LRow>,
    val body: List<LRow>,
    val footer: List<LRow>,
) : LNode {
    val allRows: List<LRow> get() = header + body + footer
}

data class LRow(override val style: TStyle, val cells: List<LCell>) : LNode

data class LCell(override val style: TStyle, val colSpan: Int, val children: List<LNode>) : LNode

/** Every image URL in the tree, so the export path can preload them before laying out. */
fun LNode.imageUrls(): List<String> {
    val urls = ArrayList<String>()
    fun visit(node: LNode) {
        when (node) {
            is LImage -> urls += node.url
            is LBox -> node.children.forEach(::visit)
            is LCell -> node.children.forEach(::visit)
            is LRow -> node.cells.forEach(::visit)
            is LTable -> node.allRows.forEach(::visit)
            is LText, is LDivider -> Unit
        }
    }
    visit(this)
    return urls.distinct()
}

object TemplateFlattener {

    fun flatten(doc: TemplateDoc, ctx: BindingContext): LNode {
        val nodes = flattenNode(doc.root, ctx, emptyMap(), doc.page)
        return nodes.singleOrNull() ?: LBox(doc.root.style, nodes)
    }

    /** Returns a list because `repeat` expands to many nodes and `conditional` may drop to none. */
    private fun flattenNode(
        node: TNode,
        ctx: BindingContext,
        aliases: Map<String, Any?>,
        page: PageSpec,
    ): List<LNode> {
        if (node.style.isHidden) return emptyList()
        return when (node) {
            is TRepeat -> {
                val items = ctx.resolveRaw(node.path, aliases) as? List<*> ?: emptyList<Any?>()
                items.flatMap { item ->
                    flattenNode(node.child, ctx, aliases + (node.alias to item), page)
                }
            }

            is TConditional ->
                if (ctx.isTruthy(node.path, aliases)) flattenNode(node.child, ctx, aliases, page)
                else emptyList()

            is TBox -> listOf(LBox(node.style, node.children.flatMap { flattenNode(it, ctx, aliases, page) }))

            is TText -> {
                val runs = buildRuns(node, ctx, aliases, page)
                // An element whose text resolves to nothing generates no line box in CSS, so it
                // must not leave a gap behind — this is how absent optional fields disappear.
                if (runs.isEmpty()) emptyList() else listOf(LText(node.style, runs, paragraphStyleOf(node.style, page)))
            }

            is TImage -> {
                val url = when (val source = node.source) {
                    is TValue.Literal -> source.text
                    is TValue.Bind -> ctx.resolveRaw(source.path, aliases) as? String ?: source.fallback
                }
                if (url.isBlank()) emptyList() else listOf(LImage(node.style, url, node.fit == "cover"))
            }

            is TDivider -> listOf(LDivider(node.style))

            is TTable -> listOf(
                LTable(
                    style = node.style,
                    columns = node.columns,
                    header = node.header.mapNotNull { flattenRow(it, ctx, aliases, page) },
                    body = flattenBody(node.body, ctx, aliases, page),
                    footer = node.footer.mapNotNull { flattenRow(it, ctx, aliases, page) },
                ),
            )

            is TRow -> listOfNotNull(flattenRow(node, ctx, aliases, page))
            is TCell -> listOf(flattenCell(node, ctx, aliases, page))
        }
    }

    private fun flattenBody(
        body: TTableBody?,
        ctx: BindingContext,
        aliases: Map<String, Any?>,
        page: PageSpec,
    ): List<LRow> = when (body) {
        null -> emptyList()
        is TTableBody.Rows -> body.rows.mapNotNull { flattenRow(it, ctx, aliases, page) }
        is TTableBody.Repeat -> {
            val items = ctx.resolveRaw(body.path, aliases) as? List<*> ?: emptyList<Any?>()
            items.mapNotNull { item ->
                flattenRow(body.row, ctx, aliases + (body.alias to item), page)
            }
        }
    }

    private fun flattenRow(
        row: TRow,
        ctx: BindingContext,
        aliases: Map<String, Any?>,
        page: PageSpec,
    ): LRow? {
        if (row.style.isHidden) return null
        return LRow(row.style, row.cells.map { flattenCell(it, ctx, aliases, page) })
    }

    private fun flattenCell(
        cell: TCell,
        ctx: BindingContext,
        aliases: Map<String, Any?>,
        page: PageSpec,
    ) = LCell(
        style = cell.style,
        colSpan = cell.colSpan.coerceAtLeast(1),
        children = cell.children.flatMap { flattenNode(it, ctx, aliases, page) },
    )

    /**
     * Builds the styled runs of a paragraph, resolving each span's binding and merging the span's
     * own style over the text node's.
     */
    private fun buildRuns(
        node: TText,
        ctx: BindingContext,
        aliases: Map<String, Any?>,
        page: PageSpec,
    ): List<StyledRun> {
        val runs = ArrayList<StyledRun>(node.spans.size)
        node.spans.forEach { span ->
            val raw = when (val value = span.value) {
                is TValue.Literal -> value.text
                is TValue.Bind -> ctx.resolveText(value, aliases)
            }
            val transform = span.style?.textTransform ?: node.style.textTransform
            val text = applyTransform(raw, transform)
            if (text.isEmpty()) return@forEach
            runs += StyledRun(text, runStyleOf(node.style, span.style, page))
        }
        // A paragraph of nothing but whitespace still occupies a line, but one of pure empties
        // does not — mirroring how the browser the template was authored against behaves.
        return if (runs.all { it.text.isEmpty() }) emptyList() else runs
    }

    private fun runStyleOf(nodeStyle: TStyle, spanStyle: TStyle?, page: PageSpec) = RunStyle(
        fontFamily = spanStyle?.fontFamily ?: nodeStyle.fontFamily ?: page.fontFamily,
        fontSizePt = spanStyle?.fontSizePt ?: nodeStyle.fontSizePt ?: page.baseFontSizePt,
        fontWeight = spanStyle?.fontWeight ?: nodeStyle.fontWeight ?: 400,
        italic = spanStyle?.fontStyleItalic ?: nodeStyle.fontStyleItalic ?: false,
        colorArgb = spanStyle?.color ?: nodeStyle.color ?: DEFAULT_INK,
        letterSpacingPt = spanStyle?.letterSpacingPt ?: nodeStyle.letterSpacingPt ?: 0f,
    )

    private fun paragraphStyleOf(style: TStyle, page: PageSpec) = ParagraphStyle(
        align = when (style.textAlign) {
            "center" -> TextAlignment.Center
            "right" -> TextAlignment.Right
            "justify" -> TextAlignment.Justify
            else -> TextAlignment.Left
        },
        lineHeight = style.lineHeight,
        baseFontSizePt = style.fontSizePt ?: page.baseFontSizePt,
    )

    private fun applyTransform(text: String, transform: String?): String = when (transform) {
        "uppercase" -> text.uppercase()
        "lowercase" -> text.lowercase()
        "capitalize" -> text.split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercase() }
        }
        else -> text
    }

    private const val DEFAULT_INK = 0xFF000000
}
