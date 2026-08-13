package com.ferbotz.billanta.render.layout

import com.ferbotz.billanta.render.Dim
import com.ferbotz.billanta.render.TStyle

/**
 * A style resolved against a concrete containing block.
 *
 * Sizes follow the CSS default `box-sizing: content-box` — `width` is the *content* width and
 * padding/border sit outside it. The authoring subset has no `box-sizing` property, so this is
 * what the author saw in the browser they designed the template against.
 */
data class BoxMetrics(
    val margin: EdgesPt,
    val marginAutoLeft: Boolean,
    val marginAutoRight: Boolean,
    val border: EdgesPt,
    val borderColors: EdgeColors,
    val borderStyles: EdgeStyles,
    val padding: EdgesPt,
    val radiusPt: Float,
    val backgroundArgb: Long?,
    val opacity: Float?,
    val contentWidth: Float?,
    val contentHeight: Float?,
    val minWidth: Float?,
    val maxWidth: Float?,
    val minHeight: Float?,
    val maxHeight: Float?,
) {
    /** Everything between the outer (margin) edge and the content box, horizontally. */
    val frameHorizontal: Float get() = margin.horizontal + border.horizontal + padding.horizontal
    val frameVertical: Float get() = margin.vertical + border.vertical + padding.vertical

    /** Border box inset from the margin box. */
    val borderBoxInset: EdgesPt get() = margin

    /** Content inset from the border box. */
    val contentInset: EdgesPt get() = border + padding

    fun clampWidth(value: Float): Float {
        var v = value
        minWidth?.let { v = maxOf(v, it) }
        maxWidth?.let { v = minOf(v, it) }
        return v.coerceAtLeast(0f)
    }

    fun clampHeight(value: Float): Float {
        var v = value
        minHeight?.let { v = maxOf(v, it) }
        maxHeight?.let { v = minOf(v, it) }
        return v.coerceAtLeast(0f)
    }

    companion object {
        /**
         * Resolves [style] against a containing block of [containingWidth] points.
         *
         * Percentages always resolve against the containing block's *width*, including vertical
         * ones — that is genuine CSS behaviour, not an approximation.
         */
        fun of(style: TStyle, containingWidth: Float): BoxMetrics = BoxMetrics(
            margin = EdgesPt(
                top = style.marginTop.toPt(containingWidth),
                right = style.marginRight.toPt(containingWidth),
                bottom = style.marginBottom.toPt(containingWidth),
                left = style.marginLeft.toPt(containingWidth),
            ),
            marginAutoLeft = style.marginLeft == Dim.Auto,
            marginAutoRight = style.marginRight == Dim.Auto,
            border = EdgesPt(
                top = style.borderTopWidthPt ?: 0f,
                right = style.borderRightWidthPt ?: 0f,
                bottom = style.borderBottomWidthPt ?: 0f,
                left = style.borderLeftWidthPt ?: 0f,
            ),
            borderColors = EdgeColors(
                top = style.borderTopColor,
                right = style.borderRightColor,
                bottom = style.borderBottomColor,
                left = style.borderLeftColor,
            ),
            borderStyles = EdgeStyles(
                top = style.borderTopStyle,
                right = style.borderRightStyle,
                bottom = style.borderBottomStyle,
                left = style.borderLeftStyle,
            ),
            padding = EdgesPt(
                top = style.paddingTopPt ?: 0f,
                right = style.paddingRightPt ?: 0f,
                bottom = style.paddingBottomPt ?: 0f,
                left = style.paddingLeftPt ?: 0f,
            ),
            radiusPt = style.borderRadiusPt ?: 0f,
            backgroundArgb = style.backgroundColor,
            opacity = style.opacity,
            contentWidth = style.width.resolve(containingWidth),
            contentHeight = style.height.resolve(containingWidth),
            minWidth = style.minWidth.resolve(containingWidth),
            maxWidth = style.maxWidth.resolve(containingWidth),
            minHeight = style.minHeight.resolve(containingWidth),
            maxHeight = style.maxHeight.resolve(containingWidth),
        )
    }
}

/** `auto` contributes no space of its own; the parent decides what to do with the free space. */
private fun Dim?.toPt(containingWidth: Float): Float = when (this) {
    is Dim.Pt -> v
    is Dim.Percent -> containingWidth * v / 100f
    Dim.Auto, null -> 0f
}

internal fun Dim?.resolve(containingWidth: Float): Float? = when (this) {
    is Dim.Pt -> v
    is Dim.Percent -> containingWidth * v / 100f
    Dim.Auto, null -> null
}
