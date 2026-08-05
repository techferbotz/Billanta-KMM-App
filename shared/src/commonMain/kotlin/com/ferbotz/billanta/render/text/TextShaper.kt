package com.ferbotz.billanta.render.text

import com.ferbotz.billanta.render.LineHeight

/** Resolved style for one run of text. Every value is absolute — no cascade, no inheritance. */
data class RunStyle(
    val fontFamily: String,
    val fontSizePt: Float,
    val fontWeight: Int = 400,
    val italic: Boolean = false,
    val colorArgb: Long = 0xFF000000,
    val letterSpacingPt: Float = 0f,
)

/** One styled run entering the shaper. A paragraph is a list of these. */
data class StyledRun(val text: String, val style: RunStyle)

enum class TextAlignment { Left, Right, Center, Justify }

/** Paragraph-level settings that affect line breaking and line boxes. */
data class ParagraphStyle(
    val align: TextAlignment = TextAlignment.Left,
    val lineHeight: LineHeight? = null,
    val baseFontSizePt: Float = 11f,
)

data class ShapedRun(
    val text: String,
    val style: RunStyle,
    /** Offset from the paragraph's left edge, in points. */
    val xPt: Float,
    val widthPt: Float,
)

data class ShapedLine(
    val runs: List<ShapedRun>,
    /** Offsets from the paragraph's top edge, in points. */
    val topPt: Float,
    val baselinePt: Float,
    val heightPt: Float,
)

/**
 * A laid-out paragraph. [lines] is the logical model the layout engine and the PDF writer read;
 * [platformHandle] optionally carries the platform's own layout object so painting can reuse it
 * instead of shaping a second time. The handle is deliberately outside the constructor so it
 * takes no part in equality — golden tests compare geometry only.
 */
data class ShapedParagraph(
    val lines: List<ShapedLine>,
    val widthPt: Float,
    val heightPt: Float,
) {
    var platformHandle: Any? = null

    companion object {
        val EMPTY = ShapedParagraph(emptyList(), 0f, 0f)
    }
}

/** Min-content (longest unbreakable word) and max-content (never wrapped) widths. */
data class IntrinsicWidths(val minPt: Float, val maxPt: Float)

/**
 * The one seam between our layout engine and platform text handling.
 *
 * We own everything above this line — where paragraphs sit, how boxes stack, how table columns
 * size, where pages break. Below it, glyph selection, kerning and line breaking are delegated to
 * the platform text stack, because reimplementing shaping means parsing glyph outlines and
 * hinting for a strictly worse result.
 */
interface TextShaper {

    fun shape(
        runs: List<StyledRun>,
        paragraphStyle: ParagraphStyle,
        maxWidthPt: Float,
    ): ShapedParagraph

    /** Used by table auto-column sizing, which must know content widths before placing anything. */
    fun intrinsicWidths(runs: List<StyledRun>, paragraphStyle: ParagraphStyle): IntrinsicWidths
}

/** Concatenated text of a run list, used for measuring and for `.notdef` checks. */
fun List<StyledRun>.plainText(): String = joinToString(separator = "") { it.text }
