package com.ferbotz.billanta.thumbs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import com.ferbotz.billanta.render.text.ComposeTextShaper
import com.ferbotz.billanta.render.text.FontRegistry

/**
 * The bundled faces for drawing thumbnails off screen, or null until they are really loaded.
 *
 * A thumbnail is kept for good, so it must never be drawn with a stand-in font. On iOS,
 * compose-resources hands one back while the real face loads, and a picture drawn in that moment
 * would be filed under a key that stays valid. Android loads resource fonts synchronously.
 */
@Composable
expect fun rememberThumbnailFontFamily(): FontFamily?

/**
 * Gives the thumbnail service a text shaper of its own once the fonts are in. Its own, because a
 * shaper's measuring cache isn't thread-safe and the service draws away from the main thread.
 */
@Composable
fun ProvideThumbnailShaper(thumbnails: InvoiceThumbnails) {
    val family = rememberThumbnailFontFamily() ?: return
    val resolver = LocalFontFamilyResolver.current
    LaunchedEffect(thumbnails, family, resolver) {
        thumbnails.attach(ComposeTextShaper(resolver, FontRegistry(family)))
    }
}
