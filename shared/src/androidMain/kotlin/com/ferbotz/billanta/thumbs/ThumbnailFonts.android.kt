package com.ferbotz.billanta.thumbs

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import com.ferbotz.billanta.render.text.rememberFontRegistry

/** Resource fonts are read from the APK's assets on first use, synchronously: nothing to wait for. */
@Composable
actual fun rememberThumbnailFontFamily(): FontFamily? = rememberFontRegistry().bundled
