package com.ferbotz.billanta.render.paint

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import com.ferbotz.billanta.render.BorderStyle
import com.ferbotz.billanta.render.layout.EdgeStyles
import com.ferbotz.billanta.render.layout.dashPatternFor
import com.ferbotz.billanta.render.layout.dashRuns
import androidx.compose.ui.graphics.PathEffect
import com.ferbotz.billanta.render.layout.DrawCommand
import com.ferbotz.billanta.render.layout.EdgeColors
import com.ferbotz.billanta.render.layout.EdgesPt
import com.ferbotz.billanta.render.layout.RectPt
import com.ferbotz.billanta.render.layout.RenderedPage

/**
 * Draws one page of the display list.
 *
 * The caller sets up the transform so that one unit of the draw scope equals one point; this then
 * walks the commands in order. Text reuses the paragraph shaped during layout, so glyphs are laid
 * out exactly once no matter how many times a page is redrawn or exported.
 */
fun DrawScope.drawRenderedPage(
    page: RenderedPage,
    imageFor: (String) -> Painter? = { null },
) {
    page.commands.forEach { drawCommand(it, imageFor) }
}

private fun DrawScope.drawCommand(command: DrawCommand, imageFor: (String) -> Painter?) {
    when (command) {
        is DrawCommand.Fill -> drawFill(command)
        is DrawCommand.Borders ->
            drawBorders(command.rect, command.widths, command.colors, command.radiusPt, command.styles)
        is DrawCommand.Text -> drawParagraph(command)
        is DrawCommand.Image -> drawImage(command, imageFor)
        is DrawCommand.Group -> {
            // `opacity` composites the whole subtree at once, so the layer has to wrap it.
            drawContext.canvas.saveLayer(
                bounds = androidx.compose.ui.geometry.Rect(Offset.Zero, size),
                paint = androidx.compose.ui.graphics.Paint().apply { alpha = command.alpha },
            )
            command.children.forEach { drawCommand(it, imageFor) }
            drawContext.canvas.restore()
        }
    }
}

private fun DrawScope.drawFill(command: DrawCommand.Fill) {
    val color = Color(command.colorArgb.toInt())
    val topLeft = Offset(command.rect.x, command.rect.y)
    val size = Size(command.rect.width, command.rect.height)
    if (command.radiusPt > 0f) {
        drawRoundRect(color, topLeft, size, CornerRadius(command.radiusPt, command.radiusPt))
    } else {
        drawRect(color, topLeft, size)
    }
}

/**
 * Sides are stroked independently because templates set them independently — every row rule in
 * the shipped templates is a lone `border-bottom`. Only a fully uniform border can take the
 * rounded-rectangle path.
 */
private fun DrawScope.drawBorders(
    rect: RectPt,
    widths: EdgesPt,
    colors: EdgeColors,
    radiusPt: Float,
    styles: EdgeStyles,
) {
    val uniformWidth = widths.top
    val uniform = widths.right == uniformWidth && widths.bottom == uniformWidth && widths.left == uniformWidth
    val distinctColors = listOfNotNull(colors.top, colors.right, colors.bottom, colors.left).distinct()
    // Absent means solid, so a box that only names the style on some edges still counts as
    // uniform and keeps the single-stroke path it used before styles were honoured at all.
    val distinctStyles = listOf(styles.top, styles.right, styles.bottom, styles.left)
        .map { it ?: BorderStyle.Solid }.distinct()

    if (uniform && uniformWidth > 0f && distinctColors.size <= 1 && distinctStyles.size == 1 && radiusPt > 0f) {
        // A rounded box strokes as one path so its dashes run round the corners instead of stopping
        // square at them, which is what a dashed empty-state box needs to look right.
        val pattern = dashPatternFor(uniformWidth, distinctStyles.single())
        drawRoundRect(
            color = Color((distinctColors.firstOrNull() ?: DEFAULT_BORDER).toInt()),
            topLeft = Offset(rect.x + uniformWidth / 2f, rect.y + uniformWidth / 2f),
            size = Size(rect.width - uniformWidth, rect.height - uniformWidth),
            cornerRadius = CornerRadius(radiusPt, radiusPt),
            style = Stroke(
                width = uniformWidth,
                pathEffect = pattern?.let { PathEffect.dashPathEffect(it, 0f) },
            ),
        )
        return
    }

    // Each edge is drawn as a filled band rather than a stroked line so that adjacent cells with
    // hairline rules meet exactly, with no half-pixel seam between them. A dashed edge becomes a
    // run of shorter bands, so it stays seam-free and matches the PDF exactly.
    fun edge(
        width: Float,
        argb: Long?,
        style: BorderStyle?,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        horizontal: Boolean,
    ) {
        if (width <= 0f || w <= 0f || h <= 0f) return
        val color = Color((argb ?: DEFAULT_BORDER).toInt())
        val pattern = dashPatternFor(width, style)
        if (pattern == null) {
            drawRect(color, Offset(x, y), Size(w, h))
            return
        }
        dashRuns(if (horizontal) w else h, pattern).forEach { (start, len) ->
            if (horizontal) drawRect(color, Offset(x + start, y), Size(len, h))
            else drawRect(color, Offset(x, y + start), Size(w, len))
        }
    }
    edge(widths.top, colors.top, styles.top, rect.x, rect.y, rect.width, widths.top, true)
    edge(widths.bottom, colors.bottom, styles.bottom, rect.x, rect.bottom - widths.bottom, rect.width, widths.bottom, true)
    edge(widths.left, colors.left, styles.left, rect.x, rect.y, widths.left, rect.height, false)
    edge(widths.right, colors.right, styles.right, rect.right - widths.right, rect.y, widths.right, rect.height, false)
}

private fun DrawScope.drawParagraph(command: DrawCommand.Text) {
    val layout = command.paragraph.platformHandle as? TextLayoutResult ?: return
    drawText(layout, topLeft = Offset(command.xPt, command.yPt))
}

private fun DrawScope.drawImage(command: DrawCommand.Image, imageFor: (String) -> Painter?) {
    val painter = imageFor(command.url) ?: return
    val rect = command.rect
    if (rect.width <= 0f || rect.height <= 0f) return
    translate(rect.x, rect.y) {
        with(painter) { draw(Size(rect.width, rect.height)) }
    }
}

private const val DEFAULT_BORDER = 0xFF000000
