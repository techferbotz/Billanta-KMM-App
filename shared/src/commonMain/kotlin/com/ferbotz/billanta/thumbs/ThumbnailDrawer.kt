package com.ferbotz.billanta.thumbs

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import com.ferbotz.billanta.core.InvoiceDateFormat
import com.ferbotz.billanta.fields.FieldOption
import com.ferbotz.billanta.records.Invoice
import com.ferbotz.billanta.render.InvoiceRenderer
import com.ferbotz.billanta.render.TemplateDoc
import com.ferbotz.billanta.render.layout.PlaceholderMode
import com.ferbotz.billanta.render.paint.intrinsicSizes
import com.ferbotz.billanta.render.paint.rasterizePage
import com.ferbotz.billanta.render.text.TextShaper
import com.ferbotz.billanta.share.encodeJpeg
import kotlin.time.TimeSource

/** A drawn thumbnail, and how long each part of drawing it took. */
class ThumbnailDrawing(
    val jpeg: ByteArray,
    val layoutMillis: Long,
    val paintMillis: Long,
    val encodeMillis: Long,
) {
    val totalMillis: Long get() = layoutMillis + paintMillis + encodeMillis
}

/**
 * Draws page 1 of an invoice for the list exactly as an export looks: editor-only nodes dropped,
 * hidden sections and colour overrides applied (FIELDS_APP_GUIDE.md §12.1). It is the renderer the
 * preview and the PDF use, so the picture is the invoice rather than a sketch of it.
 *
 * Not thread-safe: [shaper] keeps a measuring cache of its own, so a drawer draws one at a time.
 */
class ThumbnailDrawer(
    private val shaper: TextShaper,
    /** The page's images, or null while any is out of reach; see `InvoiceImageLoader.loadComplete`. */
    private val loadImages: suspend (List<String>) -> Map<String, ImageBitmap>?,
) {

    /** Null when an image couldn't be reached: a picture is only ever saved whole. */
    suspend fun draw(
        invoice: Invoice,
        doc: TemplateDoc,
        optionLists: Map<String, List<FieldOption>>,
        dateFormat: InvoiceDateFormat,
    ): ThumbnailDrawing? {
        val context = invoice.renderContext()
        val urls = InvoiceRenderer(shaper).imageUrlsFor(doc, context)
        val images = if (urls.isEmpty()) emptyMap() else loadImages(urls) ?: return null

        val start = TimeSource.Monotonic.markNow()
        val document = InvoiceRenderer(shaper, images.intrinsicSizes())
            .render(doc, context, optionLists, invoice.renderTheme(), PlaceholderMode.None, dateFormat)
        val page = document.pages.firstOrNull() ?: return null
        val laidOut = start.elapsedNow()

        val painters = images.mapValues { (_, bitmap) -> BitmapPainter(bitmap) }
        val bitmap = rasterizePage(
            page = page,
            pageWidthPt = document.pageWidthPt,
            pageHeightPt = document.pageHeightPt,
            scale = WIDTH_PX / document.pageWidthPt,
            imageFor = { painters[it] },
        )
        val painted = start.elapsedNow()
        val jpeg = encodeJpeg(bitmap, JPEG_QUALITY)
        val encoded = start.elapsedNow()
        if (jpeg.isEmpty()) return null

        return ThumbnailDrawing(
            jpeg = jpeg,
            layoutMillis = laidOut.inWholeMilliseconds,
            paintMillis = (painted - laidOut).inWholeMilliseconds,
            encodeMillis = (encoded - painted).inWholeMilliseconds,
        )
    }

    companion object {
        /** What the guide asks for; a card in the two-column grid is about this many pixels wide. */
        const val WIDTH_PX = 360f
        const val JPEG_QUALITY = 80
    }
}
