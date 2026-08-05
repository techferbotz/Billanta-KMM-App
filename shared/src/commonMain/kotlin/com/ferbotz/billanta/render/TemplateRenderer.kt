package com.ferbotz.billanta.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import coil3.compose.AsyncImage
import com.ferbotz.billanta.domain.model.InvoiceRecord

/**
 * Draws a compiled Billanta template for one invoice, on a fixed A4 canvas where 1pt = 1dp.
 * The tree is fully resolved (cascade applied, absolute units), so this is a direct walk:
 * no CSS knowledge, no inheritance, no unit math beyond pt→dp/sp.
 */
@Composable
fun RenderedInvoicePage(
    doc: TemplateDoc,
    record: InvoiceRecord,
    modifier: Modifier = Modifier,
) {
    val ctx = remember(record) { BindingContext(bindingDataFor(record), record.currency) }
    val density = LocalDensity.current
    val env = remember(doc, ctx, density) {
        RenderEnv(
            page = doc.page,
            ctx = ctx,
            // pt → sp via dp so the document ignores the user's font-scale setting (it's print).
            ptToSp = { pt -> with(density) { pt.dp.toSp() } },
        )
    }
    Column(
        modifier
            .requiredSize(PageSpec.A4_WIDTH_PT.dp, PageSpec.A4_HEIGHT_PT.dp)
            .background(Color.White)
            .padding(
                start = doc.page.marginLeftPt.dp,
                top = doc.page.marginTopPt.dp,
                end = doc.page.marginRightPt.dp,
                bottom = doc.page.marginBottomPt.dp,
            ),
    ) {
        RenderNode(doc.root, env, emptyMap(), Modifier.fillMaxWidth())
    }
}

internal class RenderEnv(
    val page: PageSpec,
    val ctx: BindingContext,
    val ptToSp: (Float) -> TextUnit,
)

@Composable
internal fun RenderNode(
    node: TNode,
    env: RenderEnv,
    aliases: Map<String, Any?>,
    modifier: Modifier = Modifier,
) {
    if (node.style.isHidden) return
    when (node) {
        is TRepeat -> {
            val elements = env.ctx.resolveRaw(node.path, aliases) as? List<*> ?: emptyList<Any?>()
            elements.forEach { element ->
                RenderNode(node.child, env, aliases + (node.alias to element), modifier)
            }
        }
        is TConditional -> {
            if (env.ctx.isTruthy(node.path, aliases)) RenderNode(node.child, env, aliases, modifier)
        }
        is TBox -> RenderBox(node, env, aliases, modifier)
        is TText -> RenderText(node, env, aliases, modifier)
        is TImage -> RenderImage(node, env, aliases, modifier)
        is TDivider -> RenderDivider(node, modifier)
        is TTable -> RenderTable(node, env, aliases, modifier)
        // Rows/cells outside a table shouldn't occur; render their children defensively.
        is TRow -> Row(modifier.boxStyle(node.style)) {
            node.cells.forEach { RenderNode(it, env, aliases, Modifier.weight(1f)) }
        }
        is TCell -> Column(modifier.boxStyle(node.style)) {
            node.children.forEach { RenderNode(it, env, aliases, Modifier.fillMaxWidth()) }
        }
    }
}

// ---- containers --------------------------------------------------------------------------------

@Composable
private fun RenderBox(node: TBox, env: RenderEnv, aliases: Map<String, Any?>, modifier: Modifier) {
    val s = node.style
    val isRow = s.display == "flex" && (s.flexDirection ?: "row") == "row"
    if (isRow) {
        Row(
            modifier = modifier.boxStyle(s),
            horizontalArrangement = rowArrangement(s),
            verticalAlignment = when (s.alignItems) {
                "center" -> Alignment.CenterVertically
                "flex-end" -> Alignment.Bottom
                else -> Alignment.Top
            },
        ) {
            node.children.forEach { child ->
                if (child.style.isHidden) return@forEach
                if (child.style.marginLeft == Dim.Auto) Spacer(Modifier.weight(1f))
                RenderNode(child, env, aliases, rowChildModifier(child.style))
                if (child.style.marginRight == Dim.Auto) Spacer(Modifier.weight(1f))
            }
        }
    } else {
        Column(
            modifier = modifier.boxStyle(s),
            verticalArrangement = columnArrangement(s),
        ) {
            node.children.forEach { child ->
                if (child.style.isHidden) return@forEach
                RenderNode(child, env, aliases, columnChildModifier(child))
            }
        }
    }
}

private fun RowScope.rowChildModifier(cs: TStyle): Modifier {
    var m: Modifier = Modifier
    val grow = cs.flexGrow
    if (grow != null && grow > 0f) m = m.weight(grow)
    return m
}

/**
 * CSS default in a column flow is stretch: children fill the container width unless they carry
 * their own width or are content-sized (images). Auto side margins map to alignment.
 */
private fun ColumnScope.columnChildModifier(child: TNode): Modifier {
    val cs = child.style
    var m: Modifier = Modifier
    m = when {
        cs.marginLeft == Dim.Auto && cs.marginRight == Dim.Auto -> m.align(Alignment.CenterHorizontally)
        cs.marginLeft == Dim.Auto -> m.align(Alignment.End)
        cs.marginRight == Dim.Auto -> m.align(Alignment.Start)
        else -> m
    }
    val hasAutoMargin = cs.marginLeft == Dim.Auto || cs.marginRight == Dim.Auto
    if (cs.width == null && child !is TImage && !hasAutoMargin) {
        m = m.fillMaxWidth()
    }
    return m
}

private fun rowArrangement(s: TStyle): Arrangement.Horizontal = when (s.justifyContent) {
    "center" -> Arrangement.Center
    "flex-end" -> Arrangement.End
    "space-between" -> Arrangement.SpaceBetween
    "space-around" -> Arrangement.SpaceAround
    "space-evenly" -> Arrangement.SpaceEvenly
    else -> s.gapPt?.let { Arrangement.spacedBy(it.dp) } ?: Arrangement.Start
}

private fun columnArrangement(s: TStyle): Arrangement.Vertical = when (s.justifyContent) {
    "center" -> Arrangement.Center
    "flex-end" -> Arrangement.Bottom
    "space-between" -> Arrangement.SpaceBetween
    "space-around" -> Arrangement.SpaceAround
    "space-evenly" -> Arrangement.SpaceEvenly
    else -> s.gapPt?.let { Arrangement.spacedBy(it.dp) } ?: Arrangement.Top
}

// ---- text --------------------------------------------------------------------------------------

@Composable
private fun RenderText(node: TText, env: RenderEnv, aliases: Map<String, Any?>, modifier: Modifier) {
    val s = node.style
    val annotated: AnnotatedString = buildAnnotatedString {
        node.spans.forEach { span ->
            val raw = when (val v = span.value) {
                is TValue.Literal -> v.text
                is TValue.Bind -> env.ctx.resolveText(v, aliases)
            }
            val text = applyTransform(raw, span.style?.textTransform ?: s.textTransform)
            val spanStyle = span.style?.let { spanStyleOf(it, env) }
            if (spanStyle != null) withStyle(spanStyle) { append(text) } else append(text)
        }
    }
    if (annotated.isEmpty()) return

    val fontSize = env.ptToSp(s.fontSizePt ?: env.page.baseFontSizePt)
    Text(
        text = annotated,
        modifier = modifier.boxStyle(s),
        style = TextStyle(
            color = colorOf(s.color) ?: Color(0xFF1A1A1A),
            fontSize = fontSize,
            fontWeight = s.fontWeight?.let { FontWeight(it.coerceIn(1, 1000)) } ?: FontWeight.Normal,
            fontStyle = if (s.fontStyleItalic == true) FontStyle.Italic else FontStyle.Normal,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = s.letterSpacingPt?.let { env.ptToSp(it) } ?: TextUnit.Unspecified,
            lineHeight = when (val lh = s.lineHeight) {
                is LineHeight.Multiplier -> lh.v.em
                is LineHeight.Pt -> env.ptToSp(lh.v)
                null -> TextUnit.Unspecified
            },
            textAlign = when (s.textAlign) {
                "center" -> TextAlign.Center
                "right" -> TextAlign.Right
                "justify" -> TextAlign.Justify
                else -> TextAlign.Start
            },
        ),
    )
}

private fun spanStyleOf(s: TStyle, env: RenderEnv): SpanStyle = SpanStyle(
    color = colorOf(s.color) ?: Color.Unspecified,
    fontSize = s.fontSizePt?.let { env.ptToSp(it) } ?: TextUnit.Unspecified,
    fontWeight = s.fontWeight?.let { FontWeight(it.coerceIn(1, 1000)) },
    fontStyle = if (s.fontStyleItalic == true) FontStyle.Italic else null,
    letterSpacing = s.letterSpacingPt?.let { env.ptToSp(it) } ?: TextUnit.Unspecified,
)

private fun applyTransform(text: String, transform: String?): String = when (transform) {
    "uppercase" -> text.uppercase()
    "lowercase" -> text.lowercase()
    "capitalize" -> text.split(" ").joinToString(" ") { part ->
        part.replaceFirstChar { it.uppercase() }
    }
    else -> text
}

// ---- image / divider ---------------------------------------------------------------------------

@Composable
private fun RenderImage(node: TImage, env: RenderEnv, aliases: Map<String, Any?>, modifier: Modifier) {
    val url = when (val src = node.source) {
        is TValue.Literal -> src.text
        is TValue.Bind -> env.ctx.resolveRaw(src.path, aliases) as? String
    }
    if (url.isNullOrBlank()) return
    val s = node.style
    var m = modifier.boxStyle(s)
    if (s.width == null && s.height == null) m = m.height(48.dp)
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = if (node.fit == "cover") ContentScale.Crop else ContentScale.Fit,
        modifier = m,
    )
}

@Composable
private fun RenderDivider(node: TDivider, modifier: Modifier) {
    val s = node.style
    val thickness = s.borderTopWidthPt?.takeIf { it > 0f } ?: 1f
    val color = colorOf(s.borderTopColor) ?: Color(0xFFE5E7EB)
    Box(
        modifier
            .marginsOf(s)
            .fillMaxWidth()
            .height(thickness.dp)
            .background(color),
    )
}

// ---- table -------------------------------------------------------------------------------------

@Composable
private fun RenderTable(node: TTable, env: RenderEnv, aliases: Map<String, Any?>, modifier: Modifier) {
    Column(modifier.boxStyle(node.style)) {
        node.header.forEach { RenderTableRow(it, node.columns, env, aliases) }
        when (val body = node.body) {
            is TTableBody.Repeat -> {
                val elements = env.ctx.resolveRaw(body.path, aliases) as? List<*> ?: emptyList<Any?>()
                elements.forEach { element ->
                    RenderTableRow(body.row, node.columns, env, aliases + (body.alias to element))
                }
            }
            is TTableBody.Rows -> body.rows.forEach { RenderTableRow(it, node.columns, env, aliases) }
            null -> Unit
        }
        node.footer.forEach { RenderTableRow(it, node.columns, env, aliases) }
    }
}

@Composable
private fun RenderTableRow(row: TRow, columns: List<TColumn>, env: RenderEnv, aliases: Map<String, Any?>) {
    Row(Modifier.fillMaxWidth().boxStyle(row.style), verticalAlignment = Alignment.Top) {
        var columnIndex = 0
        row.cells.forEach { cell ->
            val consumed = columns.drop(columnIndex).take(cell.colSpan)
            columnIndex += cell.colSpan
            val fixedPt = consumed.mapNotNull { it.widthPt }.sum()
            // Columns beyond the declared list behave as auto.
            val autoCount = consumed.count { it.isAuto } + (cell.colSpan - consumed.size)
            val cellModifier = when {
                autoCount > 0 -> Modifier.weight(autoCount.toFloat())
                fixedPt > 0f -> Modifier.width(fixedPt.dp)
                else -> Modifier.weight(1f)
            }
            Column(cellModifier.boxStyle(cell.style)) {
                cell.children.forEach { child ->
                    RenderNode(child, env, aliases, columnChildModifier(child))
                }
            }
        }
    }
}

// ---- style → modifiers -------------------------------------------------------------------------

internal fun colorOf(argb: Long?): Color? = argb?.let { Color(it.toInt()) }

private fun roundedCornerShapeOrNull(radiusPt: Float?): RoundedCornerShape? =
    radiusPt?.takeIf { it > 0f }?.let { RoundedCornerShape(it.dp) }

/** Point margins only — `auto` is handled by the parent's alignment logic. */
private fun Modifier.marginsOf(s: TStyle): Modifier {
    val top = (s.marginTop as? Dim.Pt)?.v ?: 0f
    val bottom = (s.marginBottom as? Dim.Pt)?.v ?: 0f
    val left = (s.marginLeft as? Dim.Pt)?.v ?: 0f
    val right = (s.marginRight as? Dim.Pt)?.v ?: 0f
    return if (top == 0f && bottom == 0f && left == 0f && right == 0f) this
    else padding(start = left.dp, top = top.dp, end = right.dp, bottom = bottom.dp)
}

/**
 * Applies the resolved box model in CSS order: margin → size → opacity → radius clip →
 * border → background → padding.
 */
internal fun Modifier.boxStyle(s: TStyle): Modifier {
    var m = marginsOf(s)

    when (val w = s.width) {
        is Dim.Pt -> m = m.width(w.v.dp)
        is Dim.Percent -> m = m.fillMaxWidth(w.v / 100f)
        else -> Unit
    }
    when (val h = s.height) {
        is Dim.Pt -> m = m.height(h.v.dp)
        else -> Unit
    }
    val minW = (s.minWidth as? Dim.Pt)?.v
    val maxW = (s.maxWidth as? Dim.Pt)?.v
    if (minW != null || maxW != null) {
        m = m.widthIn(min = minW?.dp ?: 0.dp, max = maxW?.dp ?: androidx.compose.ui.unit.Dp.Infinity)
    }
    val minH = (s.minHeight as? Dim.Pt)?.v
    val maxH = (s.maxHeight as? Dim.Pt)?.v
    if (minH != null || maxH != null) {
        m = m.heightIn(min = minH?.dp ?: 0.dp, max = maxH?.dp ?: androidx.compose.ui.unit.Dp.Infinity)
    }

    s.opacity?.let { m = m.alpha(it.coerceIn(0f, 1f)) }

    val radius = s.borderRadiusPt?.takeIf { it > 0f }
    val shape = roundedCornerShapeOrNull(radius)
    if (shape != null) m = m.clip(shape)

    m = m.bordersOf(s, radius)

    colorOf(s.backgroundColor)?.let { bg ->
        m = if (shape != null) m.background(bg, shape) else m.background(bg)
    }

    val pt = s.paddingTopPt ?: 0f
    val pr = s.paddingRightPt ?: 0f
    val pb = s.paddingBottomPt ?: 0f
    val pl = s.paddingLeftPt ?: 0f
    if (pt != 0f || pr != 0f || pb != 0f || pl != 0f) {
        m = m.padding(start = pl.dp, top = pt.dp, end = pr.dp, bottom = pb.dp)
    }
    return m
}

/** Uniform borders use Compose's border; single-sided ones (table rules) are drawn as lines. */
private fun Modifier.bordersOf(s: TStyle, radiusPt: Float?): Modifier {
    val widths = listOf(s.borderTopWidthPt, s.borderRightWidthPt, s.borderBottomWidthPt, s.borderLeftWidthPt)
    if (widths.all { it == null || it == 0f }) return this

    val colors = listOf(s.borderTopColor, s.borderRightColor, s.borderBottomColor, s.borderLeftColor)
    val uniform = widths.distinct().size == 1 && colors.filterNotNull().distinct().size <= 1
    if (uniform) {
        val width = widths.first() ?: return this
        val color = colorOf(colors.firstOrNull { it != null }) ?: Color.Black
        val shape = roundedCornerShapeOrNull(radiusPt)
        return if (shape != null) border(width.dp, color, shape) else border(width.dp, color)
    }

    return drawBehind {
        fun line(widthPt: Float?, argb: Long?, start: Offset, end: Offset, strokePx: Float) {
            if (widthPt == null || widthPt <= 0f) return
            drawLine(colorOf(argb) ?: Color.Black, start, end, strokeWidth = strokePx)
        }
        val topPx = (s.borderTopWidthPt ?: 0f).dp.toPx()
        val rightPx = (s.borderRightWidthPt ?: 0f).dp.toPx()
        val bottomPx = (s.borderBottomWidthPt ?: 0f).dp.toPx()
        val leftPx = (s.borderLeftWidthPt ?: 0f).dp.toPx()
        line(s.borderTopWidthPt, s.borderTopColor, Offset(0f, topPx / 2), Offset(size.width, topPx / 2), topPx)
        line(
            s.borderBottomWidthPt, s.borderBottomColor,
            Offset(0f, size.height - bottomPx / 2), Offset(size.width, size.height - bottomPx / 2), bottomPx,
        )
        line(s.borderLeftWidthPt, s.borderLeftColor, Offset(leftPx / 2, 0f), Offset(leftPx / 2, size.height), leftPx)
        line(
            s.borderRightWidthPt, s.borderRightColor,
            Offset(size.width - rightPx / 2, 0f), Offset(size.width - rightPx / 2, size.height), rightPx,
        )
    }
}
