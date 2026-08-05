package com.ferbotz.billanta.render.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.ferbotz.billanta.resources.Res
import com.ferbotz.billanta.resources.inter_bold
import com.ferbotz.billanta.resources.inter_bold_italic
import com.ferbotz.billanta.resources.inter_italic
import com.ferbotz.billanta.resources.inter_regular
import org.jetbrains.compose.resources.Font

/**
 * Maps the five family names the template compiler may emit onto the faces we actually bundle.
 *
 * Only Inter ships today, so every family resolves to it. Because we bundle exactly two weights
 * (400 and 700) in upright and italic, Compose's nearest-weight matching handles the full
 * 100–900 range the contract allows: anything below 600 renders regular, 600 and above bold.
 * Adding a real face later means dropping the TTFs in and extending [BUNDLED_FAMILIES] — no
 * change to the layout engine or the PDF writer.
 */
class FontRegistry(val bundled: FontFamily) {

    /** All contract families currently collapse onto the one bundled family. */
    fun familyFor(name: String?): FontFamily = bundled

    /** True when the requested family is genuinely available rather than substituted. */
    fun isSubstituted(name: String?): Boolean =
        name != null && !name.equals("Inter", ignoreCase = true) && name.lowercase() !in GENERIC_FAMILIES

    companion object {
        val BUNDLED_FAMILIES = setOf("Inter")
        private val GENERIC_FAMILIES = setOf("sans-serif", "sansserif")

        /** Bundled resource paths, used by the PDF writer to embed the font file itself. */
        const val REGULAR = "font/inter_regular.ttf"
        const val ITALIC = "font/inter_italic.ttf"
        const val BOLD = "font/inter_bold.ttf"
        const val BOLD_ITALIC = "font/inter_bold_italic.ttf"

        /** Mirrors Compose's nearest-weight matching over the two weights we bundle. */
        fun resourcePathFor(weight: Int, italic: Boolean): String = when {
            weight >= 600 && italic -> BOLD_ITALIC
            weight >= 600 -> BOLD
            italic -> ITALIC
            else -> REGULAR
        }

        /** Loads a bundled face's raw bytes (for [TrueTypeFont] and PDF `FontFile2`). */
        suspend fun loadBytes(resourcePath: String): ByteArray = Res.readBytes(resourcePath)
    }
}

@Composable
fun rememberFontRegistry(): FontRegistry {
    val inter = FontFamily(
        Font(Res.font.inter_regular, FontWeight.Normal, FontStyle.Normal),
        Font(Res.font.inter_italic, FontWeight.Normal, FontStyle.Italic),
        Font(Res.font.inter_bold, FontWeight.Bold, FontStyle.Normal),
        Font(Res.font.inter_bold_italic, FontWeight.Bold, FontStyle.Italic),
    )
    return remember(inter) { FontRegistry(inter) }
}
