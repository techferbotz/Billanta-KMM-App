package com.ferbotz.billanta.render.text

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.ferbotz.billanta.render.LineHeight

/**
 * The default [TextShaper]: line breaking, kerning and glyph selection come from the platform
 * text stack; everything above stays ours.
 *
 * The density is pinned to 1 so that one pixel equals one point equals one sp. Every number that
 * comes back is therefore already in points, and the rendered document ignores the user's system
 * font-scale setting — which is what you want for something destined to be printed.
 */
class ComposeTextShaper(
    fontResolver: FontFamily.Resolver,
    private val registry: FontRegistry,
) : TextShaper {

    private val density = Density(density = 1f, fontScale = 1f)
    private val measurer = TextMeasurer(
        defaultFontFamilyResolver = fontResolver,
        defaultDensity = density,
        defaultLayoutDirection = LayoutDirection.Ltr,
        cacheSize = MEASURE_CACHE_SIZE,
    )

    override fun shape(
        runs: List<StyledRun>,
        paragraphStyle: ParagraphStyle,
        maxWidthPt: Float,
    ): ShapedParagraph {
        if (runs.isEmpty() || runs.all { it.text.isEmpty() }) return ShapedParagraph.EMPTY
        val result = measurer.measure(
            text = annotate(runs),
            style = baseStyle(paragraphStyle),
            // Constraints are integer pixels; flooring keeps text inside its box rather than
            // letting a fractional column width cause an overflow.
            constraints = Constraints(maxWidth = maxWidthPt.toInt().coerceAtLeast(0)),
        )
        return toShaped(result, runs)
    }

    override fun intrinsicWidths(
        runs: List<StyledRun>,
        paragraphStyle: ParagraphStyle,
    ): IntrinsicWidths {
        if (runs.isEmpty() || runs.all { it.text.isEmpty() }) return IntrinsicWidths(0f, 0f)
        val text = annotate(runs)
        val style = baseStyle(paragraphStyle)
        // Unbounded → the widest line if it never wraps. Width 1 → forced to wrap at every
        // opportunity, so what's left is the widest token that cannot be broken.
        val unwrapped = measurer.measure(text, style, constraints = Constraints())
        val fullyWrapped = measurer.measure(text, style, constraints = Constraints(maxWidth = 1))
        return IntrinsicWidths(
            minPt = fullyWrapped.size.width.toFloat(),
            maxPt = unwrapped.size.width.toFloat(),
        )
    }

    private fun annotate(runs: List<StyledRun>): AnnotatedString = buildAnnotatedString {
        runs.forEach { run ->
            withStyle(spanStyleOf(run.style)) { append(run.text) }
        }
    }

    private fun spanStyleOf(s: RunStyle) = SpanStyle(
        color = Color(s.colorArgb.toInt()),
        fontSize = s.fontSizePt.sp,
        fontWeight = FontWeight(s.fontWeight.coerceIn(1, 1000)),
        fontStyle = if (s.italic) FontStyle.Italic else FontStyle.Normal,
        fontFamily = registry.familyFor(s.fontFamily),
        letterSpacing = if (s.letterSpacingPt != 0f) s.letterSpacingPt.sp else TextUnit.Unspecified,
    )

    private fun baseStyle(p: ParagraphStyle) = TextStyle(
        fontSize = p.baseFontSizePt.sp,
        fontFamily = registry.bundled,
        lineHeight = when (val lh = p.lineHeight) {
            is LineHeight.Multiplier -> (p.baseFontSizePt * lh.v).sp
            is LineHeight.Pt -> lh.v.sp
            null -> TextUnit.Unspecified
        },
        textAlign = when (p.align) {
            TextAlignment.Center -> TextAlign.Center
            TextAlignment.Right -> TextAlign.Right
            TextAlignment.Justify -> TextAlign.Justify
            TextAlignment.Left -> TextAlign.Left
        },
    )

    /**
     * Converts the platform layout into our logical model, splitting each visual line back into
     * the styled runs it covers. The PDF writer reads this; the painter reuses the platform
     * handle instead, so text is only ever shaped once.
     */
    private fun toShaped(result: TextLayoutResult, runs: List<StyledRun>): ShapedParagraph {
        val starts = IntArray(runs.size + 1)
        var acc = 0
        runs.forEachIndexed { i, run ->
            starts[i] = acc
            acc += run.text.length
        }
        starts[runs.size] = acc

        val lines = ArrayList<ShapedLine>(result.lineCount)
        for (line in 0 until result.lineCount) {
            val lineStart = result.getLineStart(line)
            val lineEnd = result.getLineEnd(line, visibleEnd = true)
            val lineRuns = ArrayList<ShapedRun>()
            for (i in runs.indices) {
                val from = maxOf(lineStart, starts[i])
                val to = minOf(lineEnd, starts[i + 1])
                if (from >= to) continue
                val x = result.getHorizontalPosition(from, usePrimaryDirection = true)
                val xEnd = result.getHorizontalPosition(to, usePrimaryDirection = true)
                lineRuns += ShapedRun(
                    text = runs[i].text.substring(from - starts[i], to - starts[i]),
                    style = runs[i].style,
                    xPt = x,
                    widthPt = xEnd - x,
                )
            }
            lines += ShapedLine(
                runs = lineRuns,
                topPt = result.getLineTop(line),
                baselinePt = result.getLineBaseline(line),
                heightPt = result.getLineBottom(line) - result.getLineTop(line),
            )
        }

        return ShapedParagraph(
            lines = lines,
            widthPt = result.size.width.toFloat(),
            heightPt = result.size.height.toFloat(),
        ).also { it.platformHandle = result }
    }

    private companion object {
        const val MEASURE_CACHE_SIZE = 64
    }
}
