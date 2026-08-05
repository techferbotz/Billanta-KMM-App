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
        is DrawCommand.Borders -> drawBorders(command.rect, command.widths, command.colors, command.radiusPt)
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
) {
    val uniformWidth = widths.top
    val uniform = widths.right == uniformWidth && widths.bottom == uniformWidth && widths.left == uniformWidth
    val distinctColors = listOfNotNull(colors.top, colors.right, colors.bottom, colors.left).distinct()

    if (uniform && uniformWidth > 0f && distinctColors.size <= 1 && radiusPt > 0f) {
        drawRoundRect(
            color = Color((distinctColors.firstOrNull() ?: DEFAULT_BORDER).toInt()),
            topLeft = Offset(rect.x + uniformWidth / 2f, rect.y + uniformWidth / 2f),
            size = Size(rect.width - uniformWidth, rect.height - uniformWidth),
            cornerRadius = CornerRadius(radiusPt, radiusPt),
            style = Stroke(width = uniformWidth),
        )
        return
    }

    // Each edge is drawn as a filled band rather than a stroked line so that adjacent cells with
    // hairline rules meet exactly, with no half-pixel seam between them.
    fun edge(width: Float, argb: Long?, x: Float, y: Float, w: Float, h: Float) {
        if (width <= 0f || w <= 0f || h <= 0f) return
        drawRect(Color((argb ?: DEFAULT_BORDER).toInt()), Offset(x, y), Size(w, h))
    }
    edge(widths.top, colors.top, rect.x, rect.y, rect.width, widths.top)
    edge(widths.bottom, colors.bottom, rect.x, rect.bottom - widths.bottom, rect.width, widths.bottom)
    edge(widths.left, colors.left, rect.x, rect.y, widths.left, rect.height)
    edge(widths.right, colors.right, rect.right - widths.right, rect.y, widths.right, rect.height)
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
