package com.ferbotz.billanta.render.layout

import com.ferbotz.billanta.render.BindingContext
import com.ferbotz.billanta.render.InvoiceTheme
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

    /** Section this node belongs to, carried through so layout can report where it landed. */
    val section: String? get() = null
}

data class LBox(
    override val style: TStyle,
    val children: List<LNode>,
    override val section: String? = null,
) : LNode

/**
 * Stands in for a section that resolved to nothing, holding open enough height for the editor to
 * draw a "tap to add" box over it. Only produced when rendering for editing — an export collapses
 * the empty section exactly as before, so a shared invoice never shows a gap.
 */
data class LPlaceholder(
    override val style: TStyle,
    override val section: String?,
    val heightPt: Float,
) : LNode

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
            is LText, is LDivider, is LPlaceholder -> Unit
        }
    }
    visit(this)
    return urls.distinct()
}

object TemplateFlattener {

    fun flatten(
        doc: TemplateDoc,
        ctx: BindingContext,
        theme: InvoiceTheme = InvoiceTheme.NONE,
        placeholders: PlaceholderMode = PlaceholderMode.None,
    ): LNode {
        val nodes = flattenNode(doc.root, ctx, emptyMap(), doc.page, theme, placeholders)
        return nodes.singleOrNull() ?: LBox(doc.root.style, nodes)
    }

    /**
     * Replaces the colours the user has overridden. The template's own hex stays in `style`, so a
     * node with no token mapping — or a token the user has not touched — is untouched.
     */
    private fun themed(style: TStyle, tokens: Map<String, String>?, theme: InvoiceTheme): TStyle {
        if (tokens.isNullOrEmpty() || theme.colorOverrides.isEmpty()) return style
        var themed = style
        tokens.forEach { (styleKey, token) ->
            val color = theme.colorOverrides[token] ?: return@forEach
            themed = when (styleKey) {
                "color" -> themed.copy(color = color)
                "backgroundColor" -> themed.copy(backgroundColor = color)
                "borderTopColor" -> themed.copy(borderTopColor = color)
                "borderRightColor" -> themed.copy(borderRightColor = color)
                "borderBottomColor" -> themed.copy(borderBottomColor = color)
                "borderLeftColor" -> themed.copy(borderLeftColor = color)
                else -> themed // unknown style key: ignore, same as the parser does
            }
        }
        return themed
    }

    /**
     * A tagged section that produced no content becomes a placeholder while editing, so the page
     * has somewhere to show "nothing here yet". Everywhere else it stays collapsed.
     */
    private fun placeholderFor(
        section: String?,
        placeholders: PlaceholderMode,
    ): LNode? {
        if (section == null) return null
        val reserve = placeholders as? PlaceholderMode.Reserve ?: return null
        if (section !in reserve.sections) return null
        return LPlaceholder(TStyle.EMPTY, section, reserve.heightPt)
    }

    /**
     * Strips the box model off a text node, keeping only what actually describes the text.
     *
     * The compiler copies an element's entire computed style onto the `text` node it synthesises
     * for that element's content, so a table cell and its text both carry the same padding,
     * background and borders. The element itself is already represented by the parent `box`/`cell`
     * node, so honouring them here would inset the text twice and paint the background twice —
     * the second copy landing on top in the template's original colour, which is how this surfaced.
     */
    private fun textOnly(style: TStyle) = TStyle(
        display = style.display,
        color = style.color,
        fontSizePt = style.fontSizePt,
        fontWeight = style.fontWeight,
        fontStyleItalic = style.fontStyleItalic,
        fontFamily = style.fontFamily,
        lineHeight = style.lineHeight,
        textAlign = style.textAlign,
        textTransform = style.textTransform,
        letterSpacingPt = style.letterSpacingPt,
        opacity = style.opacity,
    )

    /** Returns a list because `repeat` expands to many nodes and `conditional` may drop to none. */
    private fun flattenNode(
        node: TNode,
        ctx: BindingContext,
        aliases: Map<String, Any?>,
        page: PageSpec,
        theme: InvoiceTheme,
        placeholders: PlaceholderMode,
    ): List<LNode> {
        if (node.style.isHidden) return emptyList()
        // A section the user switched off disappears along with everything inside it.
        if (node.section != null && node.section in theme.hiddenSections) return emptyList()
        return when (node) {
            is TRepeat -> {
                val items = ctx.resolveRaw(node.path, aliases) as? List<*> ?: emptyList<Any?>()
                items.flatMap { item ->
                    flattenNode(node.child, ctx, aliases + (node.alias to item), page, theme, placeholders)
                }
            }

            is TConditional ->
                if (ctx.isTruthy(node.path, aliases)) flattenNode(node.child, ctx, aliases, page, theme, placeholders)
                else emptyList()

            is TBox -> {
                // A section the caller flagged as empty is replaced wholesale, label and all —
                // otherwise the user would see a lone "BILL TO" heading with nothing under it and
                // no obvious way to fill it in.
                placeholderFor(node.section, placeholders)?.let { return listOf(it) }
                val children = node.children.flatMap { flattenNode(it, ctx, aliases, page, theme, placeholders) }
                listOf(LBox(themed(node.style, node.tokens, theme), children, node.section))
            }

            is TText -> {
                val style = themed(node.style, node.tokens, theme)
                val runs = buildRuns(node, style, ctx, aliases, page, theme)
                // An element whose text resolves to nothing generates no line box in CSS, so it
                // must not leave a gap behind — this is how absent optional fields disappear.
                if (runs.isEmpty()) emptyList()
                else listOf(LText(textOnly(style), runs, paragraphStyleOf(style, page)))
            }

            is TImage -> {
                val url = when (val source = node.source) {
                    is TValue.Literal -> source.text
                    is TValue.Bind -> ctx.resolveRaw(source.path, aliases) as? String ?: source.fallback
                }
                if (url.isBlank()) emptyList() else listOf(LImage(node.style, url, node.fit == "cover"))
            }

            is TDivider -> listOf(LDivider(themed(node.style, node.tokens, theme)))

            is TTable -> listOf(
                LTable(
                    style = themed(node.style, node.tokens, theme),
                    columns = node.columns,
                    header = node.header.mapNotNull { flattenRow(it, ctx, aliases, page, theme, placeholders) },
                    body = flattenBody(node.body, ctx, aliases, page, theme, placeholders),
                    footer = node.footer.mapNotNull { flattenRow(it, ctx, aliases, page, theme, placeholders) },
                ),
            )

            is TRow -> listOfNotNull(flattenRow(node, ctx, aliases, page, theme, placeholders))
            is TCell -> listOf(flattenCell(node, ctx, aliases, page, theme, placeholders))
        }
    }

    private fun flattenBody(
        body: TTableBody?,
        ctx: BindingContext,
        aliases: Map<String, Any?>,
        page: PageSpec,
        theme: InvoiceTheme,
        placeholders: PlaceholderMode,
    ): List<LRow> = when (body) {
        null -> emptyList()
        is TTableBody.Rows -> body.rows.mapNotNull { flattenRow(it, ctx, aliases, page, theme, placeholders) }
        is TTableBody.Repeat -> {
            val items = ctx.resolveRaw(body.path, aliases) as? List<*> ?: emptyList<Any?>()
            items.mapNotNull { item ->
                flattenRow(body.row, ctx, aliases + (body.alias to item), page, theme, placeholders)
            }
        }
    }

    private fun flattenRow(
        row: TRow,
        ctx: BindingContext,
        aliases: Map<String, Any?>,
        page: PageSpec,
        theme: InvoiceTheme,
        placeholders: PlaceholderMode,
    ): LRow? {
        if (row.style.isHidden) return null
        if (row.section != null && row.section in theme.hiddenSections) return null
        return LRow(
            themed(row.style, row.tokens, theme),
            row.cells.map { flattenCell(it, ctx, aliases, page, theme, placeholders) },
        )
    }

    private fun flattenCell(
        cell: TCell,
        ctx: BindingContext,
        aliases: Map<String, Any?>,
        page: PageSpec,
        theme: InvoiceTheme,
        placeholders: PlaceholderMode,
    ) = LCell(
        style = themed(cell.style, cell.tokens, theme),
        colSpan = cell.colSpan.coerceAtLeast(1),
        children = cell.children.flatMap { flattenNode(it, ctx, aliases, page, theme, placeholders) },
    )

    /**
     * Builds the styled runs of a paragraph, resolving each span's binding and merging the span's
     * own style over the text node's.
     */
    private fun buildRuns(
        node: TText,
        nodeStyle: TStyle,
        ctx: BindingContext,
        aliases: Map<String, Any?>,
        page: PageSpec,
        theme: InvoiceTheme,
    ): List<StyledRun> {
        val runs = ArrayList<StyledRun>(node.spans.size)
        node.spans.forEach { span ->
            val raw = when (val value = span.value) {
                is TValue.Literal -> value.text
                is TValue.Bind -> ctx.resolveText(value, aliases)
            }
            val transform = span.style?.textTransform ?: nodeStyle.textTransform
            val text = applyTransform(raw, transform)
            if (text.isEmpty()) return@forEach
            val spanStyle = themed(span.style ?: TStyle.EMPTY, span.tokens, theme)
                .takeIf { span.style != null || span.tokens != null }
            runs += StyledRun(text, runStyleOf(node.style, nodeStyle, spanStyle, page))
        }
        // A paragraph of nothing but whitespace still occupies a line, but one of pure empties
        // does not — mirroring how the browser the template was authored against behaves.
        return if (runs.all { it.text.isEmpty() }) emptyList() else runs
    }

    private fun runStyleOf(
        originalNodeStyle: TStyle,
        nodeStyle: TStyle,
        spanStyle: TStyle?,
        page: PageSpec,
    ) = RunStyle(
        fontFamily = spanStyle?.fontFamily ?: nodeStyle.fontFamily ?: page.fontFamily,
        fontSizePt = spanStyle?.fontSizePt ?: nodeStyle.fontSizePt ?: page.baseFontSizePt,
        fontWeight = spanStyle?.fontWeight ?: nodeStyle.fontWeight ?: 400,
        italic = spanStyle?.fontStyleItalic ?: nodeStyle.fontStyleItalic ?: false,
        colorArgb = spanColorOf(originalNodeStyle, nodeStyle, spanStyle),
        letterSpacingPt = spanStyle?.letterSpacingPt ?: nodeStyle.letterSpacingPt ?: 0f,
    )

    /**
     * A span with its own `tokens` (BE-007) has already been recoloured by the caller, so its value
     * is exact and simply wins.
     *
     * The fallback below covers spans the compiler has not tagged: one whose colour merely restates
     * what it inherited was not expressing a choice, so it follows the themed colour. A span that
     * genuinely differs is left alone.
     */
    private fun spanColorOf(originalNodeStyle: TStyle, nodeStyle: TStyle, spanStyle: TStyle?): Long {
        val spanColor = spanStyle?.color ?: return nodeStyle.color ?: DEFAULT_INK
        val inherited = originalNodeStyle.color
        return if (inherited != null && spanColor == inherited) {
            nodeStyle.color ?: spanColor
        } else {
            spanColor
        }
    }

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

/**
 * Whether an empty section should hold space open.
 *
 * Editing reserves room so a dashed "tap to add" box has somewhere to sit; exporting never does, so
 * the file a customer receives has no gaps where optional sections were left blank.
 */
sealed interface PlaceholderMode {
    data object None : PlaceholderMode

    /**
     * Replace each of [sections] with an empty box [heightPt] tall.
     *
     * Which sections are empty is a question about the invoice's *data*, not about the tree — a
     * template's "Bill to" block still renders its heading when no customer has been chosen, so the
     * layout alone cannot tell. The caller decides; see `emptySectionsFor`.
     */
    data class Reserve(val sections: Set<String>, val heightPt: Float = 56f) : PlaceholderMode
}
