package com.ferbotz.billanta.render.text

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parses the actual bundled Inter faces. Expected values were taken from the font binaries
 * independently, so this catches a bad download or a regression in the table walk.
 */
class TrueTypeFontTest {

    private fun load(name: String): TrueTypeFont {
        val candidates = listOf(
            File("src/commonMain/composeResources/font/$name"),
            File("shared/src/commonMain/composeResources/font/$name"),
        )
        val file = candidates.firstOrNull { it.exists() }
        assertNotNull(file, "bundled font $name not found (looked in ${candidates.map { it.absolutePath }})")
        return assertNotNull(TrueTypeFont.parse(file.readBytes()), "failed to parse $name")
    }

    @Test
    fun parses_inter_regular_metrics() {
        val font = load("inter_regular.ttf")
        assertEquals(2048, font.unitsPerEm)
        assertEquals(1984, font.ascenderFUnits)
        assertEquals(-494, font.descenderFUnits)
        assertTrue(font.numGlyphs > 1000, "expected a full glyph set, got ${font.numGlyphs}")
    }

    /**
     * The whole invoice is denominated in rupees, so a missing U+20B9 would put a blank box next
     * to every amount — and PDF embedding needs its glyph id specifically.
     */
    @Test
    fun every_bundled_face_has_the_rupee_sign() {
        listOf("inter_regular.ttf", "inter_italic.ttf", "inter_bold.ttf", "inter_bold_italic.ttf")
            .forEach { name ->
                val font = load(name)
                assertTrue(font.hasGlyph(RUPEE), "$name is missing U+20B9")
                assertTrue(font.advanceFUnits(font.glyphId(RUPEE)) > 0, "$name has a zero-width rupee")
            }
    }

    @Test
    fun maps_codepoints_to_glyphs() {
        val font = load("inter_regular.ttf")
        assertEquals(2, font.glyphId('A'.code))
        assertEquals(1315, font.glyphId(RUPEE))
        assertTrue(font.hasGlyph('0'.code))
        assertTrue(font.hasGlyph(' '.code))
        // Devanagari is outside Inter's coverage — confirms misses report as .notdef rather than
        // silently returning a wrong glyph.
        assertEquals(0, font.glyphId(0x0915))
        assertTrue(!font.hasGlyph(0x0915))
    }

    @Test
    fun advances_scale_to_points_and_pdf_glyph_space() {
        val font = load("inter_regular.ttf")
        val gid = font.glyphId('M'.code)
        val fUnits = font.advanceFUnits(gid)
        assertTrue(fUnits > 0)

        // PDF measures glyph space in 1/1000 em regardless of the font's own unitsPerEm.
        assertEquals((fUnits * 1000.0 / 2048).toInt(), font.advanceMilliEm(gid))

        // An 'M' at 10pt should be roughly two thirds of the em, well inside these bounds.
        val pt = font.advancePt(gid, fontSizePt = 10f)
        assertTrue(pt in 5f..12f, "unexpected advance ${pt}pt for M at 10pt")
    }

    @Test
    fun glyphs_past_the_metric_array_reuse_the_last_advance() {
        val font = load("inter_regular.ttf")
        // Never throws, even far beyond numGlyphs.
        assertTrue(font.advanceFUnits(font.numGlyphs + 5000) >= 0)
    }

    @Test
    fun rejects_non_font_bytes() {
        assertNull(TrueTypeFont.parse(ByteArray(0)))
        assertNull(TrueTypeFont.parse("not a font at all".encodeToByteArray()))
        assertNull(TrueTypeFont.parse(ByteArray(64) { 0x7F }))
    }

    private companion object {
        const val RUPEE = 0x20B9
    }
}
