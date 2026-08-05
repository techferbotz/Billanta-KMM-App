package com.ferbotz.billanta.render.text

import com.ferbotz.billanta.render.LineHeight

/**
 * A deterministic stand-in for the platform shaper, so layout tests assert exact geometry without
 * depending on a real font, a device, or the host's Skia build.
 *
 * Every character is [advanceRatio] × fontSize wide and lines break greedily at spaces. Tokens
 * that are adjacent with no whitespace between them — `₹` followed by `500` in separate runs —
 * stay on one line together, which is what makes min-content column widths meaningful.
 */
class FakeTextShaper(
    private val advanceRatio: Float = 0.5f,
    private val defaultLineHeightRatio: Float = 1.2f,
) : TextShaper {

    override fun shape(
        runs: List<StyledRun>,
        paragraphStyle: ParagraphStyle,
        maxWidthPt: Float,
    ): ShapedParagraph {
        val groups = groupWords(runs)
        if (groups.isEmpty()) return ShapedParagraph.EMPTY

        val lineHeight = lineHeightOf(paragraphStyle, runs)
        val lines = ArrayList<ShapedLine>()
        var current = ArrayList<Piece>()
        var currentWidth = 0f
        var widest = 0f

        fun flush() {
            if (current.isEmpty()) return
            val trimmed = current.dropLastWhile { it.isSpace }
            var x = 0f
            val shaped = trimmed.map { piece ->
                ShapedRun(piece.text, piece.style, x, piece.width).also { x += piece.width }
            }
            widest = maxOf(widest, x)
            lines += ShapedLine(
                runs = mergeAdjacent(shaped),
                topPt = lines.size * lineHeight,
                baselinePt = lines.size * lineHeight + lineHeight * BASELINE_FRACTION,
                heightPt = lineHeight,
            )
            current = ArrayList()
            currentWidth = 0f
        }

        groups.forEach { group ->
            val groupWidth = group.sumOf { it.width.toDouble() }.toFloat()
            val allSpaces = group.all { it.isSpace }
            if (!allSpaces && current.isNotEmpty() && currentWidth + groupWidth > maxWidthPt) flush()
            if (allSpaces && current.isEmpty()) return@forEach
            current.addAll(group)
            currentWidth += groupWidth
        }
        flush()

        return ShapedParagraph(
            lines = lines,
            widthPt = minOf(widest, maxWidthPt),
            heightPt = lines.size * lineHeight,
        )
    }

    override fun intrinsicWidths(
        runs: List<StyledRun>,
        paragraphStyle: ParagraphStyle,
    ): IntrinsicWidths {
        val groups = groupWords(runs)
        if (groups.isEmpty()) return IntrinsicWidths(0f, 0f)
        val max = groups.sumOf { g -> g.sumOf { it.width.toDouble() } }.toFloat()
        val min = groups
            .filterNot { g -> g.all { it.isSpace } }
            .maxOfOrNull { g -> g.sumOf { it.width.toDouble() }.toFloat() }
            ?: 0f
        return IntrinsicWidths(minPt = min, maxPt = max)
    }

    private class Piece(val text: String, val style: RunStyle, val isSpace: Boolean) {
        var width: Float = 0f
    }

    /** Splits runs into whitespace/non-whitespace pieces, then groups unbreakable neighbours. */
    private fun groupWords(runs: List<StyledRun>): List<List<Piece>> {
        val pieces = ArrayList<Piece>()
        runs.forEach { run ->
            if (run.text.isEmpty()) return@forEach
            var start = 0
            var isSpace = run.text[0] == ' '
            for (i in 1..run.text.length) {
                val boundary = i == run.text.length || (run.text[i] == ' ') != isSpace
                if (!boundary) continue
                val text = run.text.substring(start, i)
                pieces += Piece(text, run.style, isSpace).also {
                    it.width = text.length * advanceRatio * run.style.fontSizePt +
                        text.length * run.style.letterSpacingPt
                }
                if (i < run.text.length) {
                    start = i
                    isSpace = run.text[i] == ' '
                }
            }
        }

        val groups = ArrayList<List<Piece>>()
        var run = ArrayList<Piece>()
        pieces.forEach { piece ->
            if (piece.isSpace) {
                if (run.isNotEmpty()) { groups += run; run = ArrayList() }
                groups += listOf(piece)
            } else {
                run.add(piece)
            }
        }
        if (run.isNotEmpty()) groups += run
        return groups
    }

    /** Keeps output tidy: consecutive pieces sharing a style collapse into one run. */
    private fun mergeAdjacent(runs: List<ShapedRun>): List<ShapedRun> {
        if (runs.size < 2) return runs
        val merged = ArrayList<ShapedRun>(runs.size)
        runs.forEach { next ->
            val last = merged.lastOrNull()
            if (last != null && last.style == next.style) {
                merged[merged.size - 1] = last.copy(
                    text = last.text + next.text,
                    widthPt = last.widthPt + next.widthPt,
                )
            } else {
                merged += next
            }
        }
        return merged
    }

    private fun lineHeightOf(p: ParagraphStyle, runs: List<StyledRun>): Float {
        val fontSize = runs.maxOfOrNull { it.style.fontSizePt } ?: p.baseFontSizePt
        return when (val lh = p.lineHeight) {
            is LineHeight.Multiplier -> fontSize * lh.v
            is LineHeight.Pt -> lh.v
            null -> fontSize * defaultLineHeightRatio
        }
    }

    private companion object {
        /** Where the baseline sits inside the line box; only needs to be consistent. */
        const val BASELINE_FRACTION = 0.8f
    }
}
