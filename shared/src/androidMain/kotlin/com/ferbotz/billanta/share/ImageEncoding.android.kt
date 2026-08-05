package com.ferbotz.billanta.share

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream

actual fun encodePng(bitmap: ImageBitmap): ByteArray =
    ByteArrayOutputStream().use { out ->
        bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }

actual fun encodeJpeg(bitmap: ImageBitmap, quality: Int): ByteArray {
    val source = bitmap.asAndroidBitmap()
    // JPEG has no alpha channel, so anything transparent would otherwise come out black.
    val flattened = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    Canvas(flattened).apply {
        drawColor(android.graphics.Color.WHITE)
        drawBitmap(source, 0f, 0f, null)
    }
    return ByteArrayOutputStream().use { out ->
        flattened.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
        out.toByteArray()
    }
}
