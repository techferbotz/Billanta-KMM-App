package com.ferbotz.billanta.share

import androidx.compose.ui.graphics.ImageBitmap
import com.ferbotz.billanta.core.AppError
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.core.asFailure
import com.ferbotz.billanta.render.PageSpec
import kotlinx.coroutines.CancellationException

enum class ExportFormat(val extension: String, val mimeType: String, val label: String) {
    PDF("pdf", "application/pdf", "PDF"),
    PNG("png", "image/png", "PNG"),
    JPEG("jpg", "image/jpeg", "JPG"),
}

/**
 * The app's core deliverable: turn the captured invoice page into a shareable file.
 * The capture comes from the on-screen renderer, so what the user shares is exactly
 * what they previewed.
 */
class InvoiceExporter(private val shareService: FileShareService) {

    suspend fun export(bitmap: ImageBitmap, format: ExportFormat, baseName: String): AppResult<Unit> = try {
        val safeName = baseName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "invoice" }
        val bytes = when (format) {
            ExportFormat.PNG -> encodePng(bitmap)
            ExportFormat.JPEG -> encodeJpeg(bitmap, quality = 92)
            ExportFormat.PDF -> imageToPdf(
                pngBytes = encodePng(bitmap),
                pageWidthPt = PageSpec.A4_WIDTH_PT,
                pageHeightPt = PageSpec.A4_HEIGHT_PT,
            )
        }
        if (bytes.isEmpty()) {
            AppError.Unexpected("Could not encode the invoice").asFailure()
        } else {
            shareService.share(bytes, "$safeName.${format.extension}", format.mimeType)
            AppResult.Success(Unit)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        AppError.Unexpected(e.message ?: "Export failed").asFailure()
    }
}
