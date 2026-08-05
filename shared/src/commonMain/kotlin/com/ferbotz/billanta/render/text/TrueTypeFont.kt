package com.ferbotz.billanta.render.text

/**
 * A minimal TrueType reader: just the tables needed to embed a font in a PDF and to reason about
 * its metrics — `head`, `hhea`, `hmtx`, `maxp`, `cmap`, `OS/2`, `post`.
 *
 * Glyph outlines are deliberately NOT parsed. Screen drawing goes through the platform text
 * stack; the PDF embeds the font file whole and only needs a codepoint→glyph map, per-glyph
 * advance widths, and the descriptor metrics.
 */
class TrueTypeFont private constructor(
    /** The original file bytes, embedded verbatim as the PDF `FontFile2` stream. */
    val data: ByteArray,
    val unitsPerEm: Int,
    val numGlyphs: Int,
    val ascenderFUnits: Int,
    val descenderFUnits: Int,
    val capHeightFUnits: Int,
    val italicAngle: Float,
    val xMinFUnits: Int,
    val yMinFUnits: Int,
    val xMaxFUnits: Int,
    val yMaxFUnits: Int,
    private val advancesFUnits: IntArray,
    private val codepointToGlyph: Map<Int, Int>,
) {

    /** Glyph index for a codepoint, or 0 (`.notdef`) when the font has no coverage. */
    fun glyphId(codePoint: Int): Int = codepointToGlyph[codePoint] ?: 0

    fun hasGlyph(codePoint: Int): Boolean = (codepointToGlyph[codePoint] ?: 0) != 0

    /** Advance in font units. Glyphs past the `hmtx` metric array reuse the final advance. */
    fun advanceFUnits(glyphId: Int): Int = when {
        advancesFUnits.isEmpty() -> 0
        glyphId < advancesFUnits.size -> advancesFUnits[glyphId]
        else -> advancesFUnits[advancesFUnits.size - 1]
    }

    /** PDF expresses glyph space in 1/1000 em regardless of the font's own unitsPerEm. */
    fun advanceMilliEm(glyphId: Int): Int = toMilliEm(advanceFUnits(glyphId))

    fun toMilliEm(fUnits: Int): Int =
        if (unitsPerEm == 0) 0 else (fUnits * 1000.0 / unitsPerEm).toInt()

    fun advancePt(glyphId: Int, fontSizePt: Float): Float =
        if (unitsPerEm == 0) 0f else advanceFUnits(glyphId) * fontSizePt / unitsPerEm

    companion object {
        /** Returns null if the bytes aren't a TrueType/OpenType file we can read. */
        fun parse(bytes: ByteArray): TrueTypeFont? {
            if (bytes.size < 12) return null
            val r = ByteReader(bytes)
            val tag = r.u32(0)
            // 0x00010000 = TrueType outlines, 'true' = legacy Apple, 'OTTO' = CFF outlines.
            if (tag != 0x00010000L && tag != 0x74727565L && tag != 0x4F54544FL) return null

            val numTables = r.u16(4)
            val tables = HashMap<String, Int>(numTables)
            for (i in 0 until numTables) {
                val rec = 12 + i * 16
                if (rec + 16 > bytes.size) return null
                val name = buildString {
                    for (j in 0 until 4) append(bytes[rec + j].toInt().toChar())
                }
                tables[name] = r.u32(rec + 8).toInt()
            }

            val head = tables["head"] ?: return null
            val hhea = tables["hhea"] ?: return null
            val maxp = tables["maxp"] ?: return null
            val hmtx = tables["hmtx"] ?: return null
            val cmap = tables["cmap"] ?: return null

            val unitsPerEm = r.u16(head + 18)
            if (unitsPerEm == 0) return null
            val xMin = r.i16(head + 36)
            val yMin = r.i16(head + 38)
            val xMax = r.i16(head + 40)
            val yMax = r.i16(head + 42)

            val ascender = r.i16(hhea + 4)
            val descender = r.i16(hhea + 6)
            val numberOfHMetrics = r.u16(hhea + 34)
            val numGlyphs = r.u16(maxp + 4)

            val advances = IntArray(numberOfHMetrics)
            for (i in 0 until numberOfHMetrics) {
                val off = hmtx + i * 4
                if (off + 2 > bytes.size) break
                advances[i] = r.u16(off)
            }

            // OS/2 `sCapHeight` only exists from version 2; fall back to a typical 0.7em.
            val os2 = tables["OS/2"]
            val capHeight = if (os2 != null && r.u16(os2) >= 2 && os2 + 90 <= bytes.size) {
                r.i16(os2 + 88)
            } else {
                (unitsPerEm * 0.7f).toInt()
            }

            val post = tables["post"]
            val italicAngle = if (post != null && post + 8 <= bytes.size) {
                r.i32(post + 4) / 65536f // Fixed 16.16
            } else {
                0f
            }

            return TrueTypeFont(
                data = bytes,
                unitsPerEm = unitsPerEm,
                numGlyphs = numGlyphs,
                ascenderFUnits = ascender,
                descenderFUnits = descender,
                capHeightFUnits = capHeight,
                italicAngle = italicAngle,
                xMinFUnits = xMin,
                yMinFUnits = yMin,
                xMaxFUnits = xMax,
                yMaxFUnits = yMax,
                advancesFUnits = advances,
                codepointToGlyph = parseCmap(r, cmap) ?: return null,
            )
        }

        /**
         * Reads the best available character map. Prefers a format 12 subtable (full Unicode)
         * over format 4 (Basic Multilingual Plane only) — ₹ U+20B9 is in the BMP, so either works,
         * but format 12 keeps us correct if a template ever uses an emoji or supplementary glyph.
         */
        private fun parseCmap(r: ByteReader, cmapOffset: Int): Map<Int, Int>? {
            val numSubtables = r.u16(cmapOffset + 2)
            var best = -1
            var bestFormat = -1
            for (i in 0 until numSubtables) {
                val rec = cmapOffset + 4 + i * 8
                val subtable = cmapOffset + r.u32(rec + 4).toInt()
                if (subtable + 2 > r.size) continue
                when (r.u16(subtable)) {
                    12 -> if (bestFormat < 12) { best = subtable; bestFormat = 12 }
                    4 -> if (bestFormat < 4) { best = subtable; bestFormat = 4 }
                    6 -> if (bestFormat < 4) { best = subtable; bestFormat = 6 }
                }
            }
            if (best < 0) return null
            return when (bestFormat) {
                12 -> parseFormat12(r, best)
                4 -> parseFormat4(r, best)
                else -> parseFormat6(r, best)
            }
        }

        private fun parseFormat4(r: ByteReader, o: Int): Map<Int, Int> {
            val segCountX2 = r.u16(o + 6)
            val segCount = segCountX2 / 2
            val endsAt = o + 14
            val startsAt = endsAt + segCountX2 + 2 // +2 skips reservedPad
            val deltasAt = startsAt + segCountX2
            val rangesAt = deltasAt + segCountX2

            val map = HashMap<Int, Int>(segCount * 8)
            for (seg in 0 until segCount) {
                val end = r.u16(endsAt + seg * 2)
                val start = r.u16(startsAt + seg * 2)
                if (start > end) continue
                val delta = r.i16(deltasAt + seg * 2)
                val rangeOffset = r.u16(rangesAt + seg * 2)
                for (cp in start..end) {
                    if (cp == 0xFFFF) continue
                    val gid = if (rangeOffset == 0) {
                        (cp + delta) and 0xFFFF
                    } else {
                        val addr = rangesAt + seg * 2 + rangeOffset + (cp - start) * 2
                        if (addr + 2 > r.size) continue
                        val g = r.u16(addr)
                        if (g == 0) 0 else (g + delta) and 0xFFFF
                    }
                    if (gid != 0) map[cp] = gid
                }
            }
            return map
        }

        private fun parseFormat12(r: ByteReader, o: Int): Map<Int, Int> {
            val numGroups = r.u32(o + 12).toInt()
            val map = HashMap<Int, Int>(numGroups * 4)
            for (g in 0 until numGroups) {
                val rec = o + 16 + g * 12
                if (rec + 12 > r.size) break
                val start = r.u32(rec).toInt()
                val end = r.u32(rec + 4).toInt()
                val startGid = r.u32(rec + 8).toInt()
                if (start > end || end - start > MAX_GROUP_SPAN) continue
                for (cp in start..end) map[cp] = startGid + (cp - start)
            }
            return map
        }

        private fun parseFormat6(r: ByteReader, o: Int): Map<Int, Int> {
            val first = r.u16(o + 6)
            val count = r.u16(o + 8)
            val map = HashMap<Int, Int>(count)
            for (i in 0 until count) {
                val gid = r.u16(o + 10 + i * 2)
                if (gid != 0) map[first + i] = gid
            }
            return map
        }

        /** Guards against a corrupt group claiming a multi-million codepoint span. */
        private const val MAX_GROUP_SPAN = 0x10FFFF
    }
}

/** Big-endian reader over the font file. */
private class ByteReader(private val b: ByteArray) {
    val size: Int get() = b.size

    fun u8(i: Int): Int = b[i].toInt() and 0xFF
    fun u16(i: Int): Int = if (i + 2 > b.size) 0 else (u8(i) shl 8) or u8(i + 1)
    fun i16(i: Int): Int = u16(i).let { if (it > 0x7FFF) it - 0x10000 else it }
    fun u32(i: Int): Long =
        if (i + 4 > b.size) 0L
        else (u8(i).toLong() shl 24) or (u8(i + 1).toLong() shl 16) or
            (u8(i + 2).toLong() shl 8) or u8(i + 3).toLong()
    fun i32(i: Int): Int = u32(i).toInt()
}
