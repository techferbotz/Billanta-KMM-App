package com.ferbotz.billanta.share

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import com.ferbotz.billanta.core.AppError
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.core.asFailure
import com.ferbotz.billanta.render.export.PdfFace
import com.ferbotz.billanta.render.export.PdfImage
import com.ferbotz.billanta.render.export.PdfWriter
import com.ferbotz.billanta.render.layout.DrawCommand
import com.ferbotz.billanta.render.layout.RenderedDocument
import com.ferbotz.billanta.render.layout.flattenCommands
import com.ferbotz.billanta.render.paint.rasterizeDocument
import com.ferbotz.billanta.render.text.FontRegistry
import com.ferbotz.billanta.render.text.TrueTypeFont
import kotlinx.coroutines.CancellationException

enum class ExportFormat(val extension: String, val mimeType: String, val label: String) {
    PDF("pdf", "application/pdf", "PDF"),
    PNG("png", "image/png", "PNG"),
    JPEG("jpg", "image/jpeg", "JPG"),
}

/**
 * Turns a rendered invoice into a shareable file.
 *
 * All three formats consume the same display list, so a PDF and a PNG of the same invoice are the
 * same document — one drawn with vector operators, the other rasterised. PDF keeps real pages and
 * selectable text; the image formats stack the pages into one tall picture.
 */
class InvoiceExporter(
    private val shareService: FileShareService,
    private val fontBytesLoader: suspend (String) -> ByteArray = { FontRegistry.loadBytes(it) },
) {

    suspend fun export(
        document: RenderedDocument,
        format: ExportFormat,
        baseName: String,
        images: Map<String, ImageBitmap> = emptyMap(),
    ): AppResult<Unit> = try {
        if (document.pages.isEmpty()) {
            AppError.Validation("There is nothing to share yet").asFailure()
        } else {
            val bytes = when (format) {
                ExportFormat.PDF -> writePdf(document, images)
                ExportFormat.PNG -> encodePng(rasterize(document, images))
                ExportFormat.JPEG -> encodeJpeg(rasterize(document, images), quality = JPEG_QUALITY)
            }
            if (bytes.isEmpty()) {
                AppError.Unexpected("Could not encode the invoice").asFailure()
            } else {
                shareService.share(bytes, "${safeName(baseName)}.${format.extension}", format.mimeType)
                AppResult.Success(Unit)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        AppError.Unexpected(e.message ?: "Export failed").asFailure()
    }

    private fun rasterize(document: RenderedDocument, images: Map<String, ImageBitmap>): ImageBitmap {
        val painters: Map<String, Painter> = images.mapValues { (_, bitmap) -> BitmapPainter(bitmap) }
        return rasterizeDocument(document, scale = RASTER_SCALE) { painters[it] }
    }

    /**
     * Embeds only the faces the document actually uses — a two-weight invoice carries two fonts
     * rather than the whole family.
     */
    private suspend fun writePdf(
        document: RenderedDocument,
        images: Map<String, ImageBitmap>,
    ): ByteArray {
        val faces = LinkedHashMap<String, PdfFace>()
        usedFaceResources(document).forEach { resource ->
            val font = TrueTypeFont.parse(fontBytesLoader(resource)) ?: return@forEach
            faces[resource] = PdfFace(resource, font, postScriptNameFor(resource))
        }
        if (faces.isEmpty()) throw IllegalStateException("no embeddable font for this document")

        val pdfImages = images.mapNotNull { (url, bitmap) ->
            val jpeg = encodeJpeg(bitmap, quality = IMAGE_QUALITY)
            if (jpeg.isEmpty()) null else url to PdfImage(jpeg, bitmap.width, bitmap.height)
        }.toMap()

        return PdfWriter(faces, pdfImages).write(document)
    }

    private fun usedFaceResources(document: RenderedDocument): Set<String> {
        val used = LinkedHashSet<String>()
        document.pages.forEach { page ->
            page.commands.flattenCommands()
                .filterIsInstance<DrawCommand.Text>()
                .forEach { text ->
                    text.paragraph.lines.forEach { line ->
                        line.runs.forEach { run ->
                            used += FontRegistry.resourcePathFor(run.style.fontWeight, run.style.italic)
                        }
                    }
                }
        }
        return used.ifEmpty { setOf(FontRegistry.REGULAR) }
    }

    private fun postScriptNameFor(resource: String): String = when (resource) {
        FontRegistry.BOLD -> "Inter-Bold"
        FontRegistry.ITALIC -> "Inter-Italic"
        FontRegistry.BOLD_ITALIC -> "Inter-BoldItalic"
        else -> "Inter-Regular"
    }

    private fun safeName(baseName: String): String =
        baseName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "invoice" }

    private companion object {
        /** 4x of 72dpi ≈ 288dpi, comfortably past what a phone screen or a print needs. */
        const val RASTER_SCALE = 4f
        const val JPEG_QUALITY = 92
        const val IMAGE_QUALITY = 88
    }
}
