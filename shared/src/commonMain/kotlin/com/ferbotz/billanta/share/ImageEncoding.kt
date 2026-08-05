package com.ferbotz.billanta.share

import androidx.compose.ui.graphics.ImageBitmap

/** Lossless PNG of the captured invoice page. */
expect fun encodePng(bitmap: ImageBitmap): ByteArray

/** JPEG (white background — JPEG has no alpha). Quality 0–100. */
expect fun encodeJpeg(bitmap: ImageBitmap, quality: Int): ByteArray

/**
 * Wraps an already-encoded PNG into a single-page PDF whose page is `pageWidthPt × pageHeightPt`
 * (PDF native units are points, so an A4 tree yields a true A4 page).
 */
expect fun imageToPdf(pngBytes: ByteArray, pageWidthPt: Float, pageHeightPt: Float): ByteArray
