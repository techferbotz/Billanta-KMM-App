package com.ferbotz.billanta.render.layout

import com.ferbotz.billanta.render.BorderStyle
import kotlin.math.roundToInt

/**
 * The dash and gap lengths for a stroked edge, in points, or null when the edge is solid.
 *
 * Both painters take the pattern from here rather than each picking its own, because the preview
 * and the exported PDF have to be the same picture. A dash pattern that differed between them would
 * show up in the one artefact the user actually sends to a customer.
 *
 * The multiples follow CSS convention closely enough to look right: a dash is three times the
 * border width, a dot is square.
 */
fun dashPatternFor(widthPt: Float, style: BorderStyle?): FloatArray? {
    val w = widthPt.coerceAtLeast(0.25f)
    return when (style) {
        BorderStyle.Dashed -> floatArrayOf(w * 3f, w * 2f)
        BorderStyle.Dotted -> floatArrayOf(w, w)
        BorderStyle.Solid, null -> null
    }
}

/**
 * Splits an edge of [lengthPt] into the filled runs its dashes are made of, as (start, length).
 *
 * The nominal pattern is scaled so a whole number of dashes fits with one at each end — otherwise a
 * box's corners end in a stub, which reads as a rendering fault rather than a style.
 */
fun dashRuns(lengthPt: Float, pattern: FloatArray): List<Pair<Float, Float>> {
    val dash = pattern.getOrElse(0) { 0f }
    val gap = pattern.getOrElse(1) { 0f }
    if (lengthPt <= 0f || dash <= 0f) return emptyList()
    val period = dash + gap
    if (period <= 0f || lengthPt <= dash) return listOf(0f to lengthPt)

    val count = ((lengthPt + gap) / period).roundToInt().coerceAtLeast(1)
    // n dashes and n-1 gaps have to add up to exactly the edge length.
    val scale = lengthPt / (count * dash + (count - 1) * gap)
    val d = dash * scale
    val g = gap * scale
    return (0 until count).map { i -> (i * (d + g)) to d }
}
