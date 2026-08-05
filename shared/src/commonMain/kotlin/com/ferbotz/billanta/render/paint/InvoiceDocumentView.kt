package com.ferbotz.billanta.render.paint

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.ferbotz.billanta.render.InvoiceRenderer
import com.ferbotz.billanta.render.layout.RenderedDocument
import com.ferbotz.billanta.render.layout.RenderedPage
import com.ferbotz.billanta.render.layout.SizePt
import com.ferbotz.billanta.render.text.ComposeTextShaper
import com.ferbotz.billanta.render.text.rememberFontRegistry
import kotlin.math.roundToInt

/**
 * Builds a renderer wired to the bundled fonts and the platform text stack.
 *
 * The font resolver only exists inside a composition, which is why the renderer is created here
 * and then handed to whatever needs it — including the export path, so preview and file share the
 * exact same measurements.
 */
@Composable
fun rememberInvoiceRenderer(imageSizes: Map<String, SizePt> = emptyMap()): InvoiceRenderer {
    val registry = rememberFontRegistry()
    val resolver = LocalFontFamilyResolver.current
    return remember(registry, resolver, imageSizes) {
        InvoiceRenderer(ComposeTextShaper(resolver, registry), imageSizes)
    }
}

/**
 * One page, scaled to whatever width it is given. The canvas transform does the scaling, so the
 * page is drawn from the same point-space display list at any size, with no re-layout.
 */
@Composable
fun InvoicePageView(
    page: RenderedPage,
    pageWidthPt: Float,
    pageHeightPt: Float,
    modifier: Modifier = Modifier,
    imageFor: (String) -> Painter? = { null },
) {
    Canvas(modifier.aspectRatio(pageWidthPt / pageHeightPt)) {
        drawRect(Color.White, Offset.Zero, size)
        val scale = size.width / pageWidthPt
        scale(scale, pivot = Offset.Zero) {
            drawRenderedPage(page, imageFor)
        }
    }
}

/**
 * Draws a page into an off-screen bitmap at [scale] times its natural size — 1 gives 72 dpi,
 * so roughly 4 gives print-quality 288 dpi. Needs no composition, so exports do not depend on
 * what is currently on screen or on the device's pixel density.
 */
fun rasterizePage(
    page: RenderedPage,
    pageWidthPt: Float,
    pageHeightPt: Float,
    scale: Float,
    imageFor: (String) -> Painter? = { null },
): ImageBitmap {
    val width = (pageWidthPt * scale).roundToInt().coerceAtLeast(1)
    val height = (pageHeightPt * scale).roundToInt().coerceAtLeast(1)
    val bitmap = ImageBitmap(width, height)
    val canvasSize = Size(width.toFloat(), height.toFloat())

    CanvasDrawScope().draw(
        density = Density(density = 1f, fontScale = 1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = Canvas(bitmap),
        size = canvasSize,
    ) {
        drawRect(Color.White, Offset.Zero, canvasSize)
        scale(scale, pivot = Offset.Zero) {
            drawRenderedPage(page, imageFor)
        }
    }
    return bitmap
}

/**
 * All pages stacked into one tall image, which is how a picture of a multi-page invoice is
 * normally shared. PDF remains the format that keeps real pages.
 */
fun rasterizeDocument(
    document: RenderedDocument,
    scale: Float,
    imageFor: (String) -> Painter? = { null },
): ImageBitmap {
    if (document.pages.size == 1) {
        return rasterizePage(
            page = document.pages.single(),
            pageWidthPt = document.pageWidthPt,
            pageHeightPt = document.pageHeightPt,
            scale = scale,
            imageFor = imageFor,
        )
    }

    val width = (document.pageWidthPt * scale).roundToInt().coerceAtLeast(1)
    val pageHeight = (document.pageHeightPt * scale).roundToInt().coerceAtLeast(1)
    val gap = (PAGE_GAP_PT * scale).roundToInt().coerceAtLeast(1)
    val totalHeight = (pageHeight * document.pages.size + gap * (document.pages.size - 1))
        .coerceAtLeast(1)

    val bitmap = ImageBitmap(width, totalHeight)
    val canvasSize = Size(width.toFloat(), totalHeight.toFloat())
    CanvasDrawScope().draw(
        density = Density(density = 1f, fontScale = 1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = Canvas(bitmap),
        size = canvasSize,
    ) {
        drawRect(Color.White, Offset.Zero, canvasSize)
        document.pages.forEachIndexed { index, page ->
            translate(top = (pageHeight + gap).toFloat() * index) {
                scale(scale, pivot = Offset.Zero) {
                    drawRenderedPage(page, imageFor)
                }
            }
        }
    }
    return bitmap
}

/** Separator between stacked pages in a stitched image, in points. */
private const val PAGE_GAP_PT = 12f
