package com.ferbotz.billanta.share

import androidx.compose.ui.graphics.ImageBitmap

/** Lossless PNG of a rasterised invoice. */
expect fun encodePng(bitmap: ImageBitmap): ByteArray

/** JPEG, flattened onto white since the format carries no alpha. Quality is 0–100. */
expect fun encodeJpeg(bitmap: ImageBitmap, quality: Int): ByteArray
