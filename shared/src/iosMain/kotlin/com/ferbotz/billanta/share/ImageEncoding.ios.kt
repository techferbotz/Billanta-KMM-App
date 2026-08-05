package com.ferbotz.billanta.share

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSMutableData
import platform.Foundation.create
import platform.UIKit.UIGraphicsBeginPDFContextToData
import platform.UIKit.UIGraphicsBeginPDFPage
import platform.UIKit.UIGraphicsEndPDFContext
import platform.UIKit.UIImage
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData()
    else usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = size.toULong()) }

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val result = ByteArray(length.toInt())
    if (result.isNotEmpty()) {
        result.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    }
    return result
}

actual fun encodePng(bitmap: ImageBitmap): ByteArray =
    Image.makeFromBitmap(bitmap.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes ?: ByteArray(0)

actual fun encodeJpeg(bitmap: ImageBitmap, quality: Int): ByteArray =
    Image.makeFromBitmap(bitmap.asSkiaBitmap())
        .encodeToData(EncodedImageFormat.JPEG, quality.coerceIn(1, 100))?.bytes ?: ByteArray(0)

@OptIn(ExperimentalForeignApi::class)
actual fun imageToPdf(pngBytes: ByteArray, pageWidthPt: Float, pageHeightPt: Float): ByteArray {
    if (pngBytes.isEmpty()) return ByteArray(0)
    val image = UIImage(data = pngBytes.toNSData())
    val pdfData = NSMutableData()
    val bounds = CGRectMake(0.0, 0.0, pageWidthPt.toDouble(), pageHeightPt.toDouble())
    UIGraphicsBeginPDFContextToData(pdfData, bounds, null)
    UIGraphicsBeginPDFPage()
    image.drawInRect(bounds)
    UIGraphicsEndPDFContext()
    return pdfData.toByteArray()
}
