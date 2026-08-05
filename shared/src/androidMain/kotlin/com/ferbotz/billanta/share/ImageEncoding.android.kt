package com.ferbotz.billanta.share

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

actual fun encodePng(bitmap: ImageBitmap): ByteArray =
    ByteArrayOutputStream().use { out ->
        bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }

actual fun encodeJpeg(bitmap: ImageBitmap, quality: Int): ByteArray {
    val src = bitmap.asAndroidBitmap()
    // JPEG has no alpha — flatten onto white so transparent areas don't turn black.
    val flattened = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    Canvas(flattened).apply {
        drawColor(android.graphics.Color.WHITE)
        drawBitmap(src, 0f, 0f, null)
    }
    return ByteArrayOutputStream().use { out ->
        flattened.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
        out.toByteArray()
    }
}

actual fun imageToPdf(pngBytes: ByteArray, pageWidthPt: Float, pageHeightPt: Float): ByteArray {
    val image = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size) ?: return ByteArray(0)
    val widthPt = pageWidthPt.roundToInt()
    val heightPt = pageHeightPt.roundToInt()
    val document = PdfDocument()
    try {
        // PdfDocument pages are measured in points, so the A4 tree becomes a true A4 page.
        val page = document.startPage(PdfDocument.PageInfo.Builder(widthPt, heightPt, 1).create())
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        page.canvas.drawBitmap(image, null, Rect(0, 0, widthPt, heightPt), paint)
        document.finishPage(page)
        return ByteArrayOutputStream().use { out ->
            document.writeTo(out)
            out.toByteArray()
        }
    } finally {
        document.close()
    }
}
