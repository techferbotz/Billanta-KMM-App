package com.ferbotz.billanta.thumbs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import com.ferbotz.billanta.render.text.FontRegistry

/**
 * Built from the bundled bytes rather than compose-resources' `Font`, which answers with a stand-in
 * face until the real one has loaded. Null until all four faces are read.
 */
@Composable
actual fun rememberThumbnailFontFamily(): FontFamily? {
    val family by produceState<FontFamily?>(initialValue = null) {
        suspend fun face(path: String, weight: FontWeight, style: FontStyle) =
            Font(identity = path, data = FontRegistry.loadBytes(path), weight = weight, style = style)
        value = FontFamily(
            face(FontRegistry.REGULAR, FontWeight.Normal, FontStyle.Normal),
            face(FontRegistry.ITALIC, FontWeight.Normal, FontStyle.Italic),
            face(FontRegistry.BOLD, FontWeight.Bold, FontStyle.Normal),
            face(FontRegistry.BOLD_ITALIC, FontWeight.Bold, FontStyle.Italic),
        )
    }
    return family
}
