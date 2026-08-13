package com.ferbotz.billanta.render.layout

import com.ferbotz.billanta.render.BorderStyle

/**
 * Geometry for the layout engine. Every value is absolute points (1pt = 1/72in) in page space —
 * there are no pixels, no dp and no density anywhere below this line.
 */
data class RectPt(val x: Float, val y: Float, val width: Float, val height: Float) {
    val right: Float get() = x + width
    val bottom: Float get() = y + height

    fun translate(dx: Float, dy: Float) = RectPt(x + dx, y + dy, width, height)

    fun deflate(edges: EdgesPt) = RectPt(
        x = x + edges.left,
        y = y + edges.top,
        width = (width - edges.horizontal).coerceAtLeast(0f),
        height = (height - edges.vertical).coerceAtLeast(0f),
    )

    companion object {
        val ZERO = RectPt(0f, 0f, 0f, 0f)
    }
}

data class SizePt(val width: Float, val height: Float) {
    companion object {
        val ZERO = SizePt(0f, 0f)
    }
}

data class EdgesPt(
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
    val left: Float = 0f,
) {
    val horizontal: Float get() = left + right
    val vertical: Float get() = top + bottom

    val isEmpty: Boolean get() = top == 0f && right == 0f && bottom == 0f && left == 0f

    operator fun plus(other: EdgesPt) = EdgesPt(
        top + other.top,
        right + other.right,
        bottom + other.bottom,
        left + other.left,
    )

    companion object {
        val ZERO = EdgesPt()
    }
}

/** Per-side border colours as ARGB, null meaning "no colour given" (defaults to black). */
/** Per-edge stroke pattern. Null means the template said nothing, so the edge is solid. */
data class EdgeStyles(
    val top: BorderStyle? = null,
    val right: BorderStyle? = null,
    val bottom: BorderStyle? = null,
    val left: BorderStyle? = null,
) {
    companion object {
        val SOLID = EdgeStyles()
    }
}

data class EdgeColors(
    val top: Long? = null,
    val right: Long? = null,
    val bottom: Long? = null,
    val left: Long? = null,
) {
    val isEmpty: Boolean get() = top == null && right == null && bottom == null && left == null

    companion object {
        val NONE = EdgeColors()
    }
}
