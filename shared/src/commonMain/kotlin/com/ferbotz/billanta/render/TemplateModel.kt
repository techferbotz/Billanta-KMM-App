package com.ferbotz.billanta.render

/**
 * Typed model of the Billanta Template JSON render tree (TEMPLATE_JSON.md). The tree arrives
 * fully resolved — absolute styles, points, hex colors — so this model is deliberately dumb.
 *
 * Forward-compat contract: unknown node types and unknown style keys are DROPPED at parse time,
 * never treated as errors — that's what lets the backend ship new template capabilities without
 * an app release.
 */
data class TemplateDoc(
    val schemaVersion: Int,
    val compilerVersion: Int,
    val page: PageSpec,
    val root: TNode,
)

data class PageSpec(
    val size: String = "A4",
    val marginTopPt: Float = 36f,
    val marginRightPt: Float = 36f,
    val marginBottomPt: Float = 36f,
    val marginLeftPt: Float = 36f,
    val fontFamily: String = "Inter",
    val baseFontSizePt: Float = 11f,
) {
    companion object {
        /** A4 in points (the tree's only page size for schema v1). */
        const val A4_WIDTH_PT = 595f
        const val A4_HEIGHT_PT = 842f
    }
}

/** A length that may be absolute points, a parent-relative percentage, or `auto` (margins). */
sealed interface Dim {
    data class Pt(val v: Float) : Dim
    data class Percent(val v: Float) : Dim
    data object Auto : Dim
}

sealed interface LineHeight {
    data class Multiplier(val v: Float) : LineHeight
    data class Pt(val v: Float) : LineHeight
}

/** Flat resolved style. Null = property absent (renderer default applies). */
data class TStyle(
    val display: String? = null,            // flex | block | none
    val flexDirection: String? = null,      // row | column
    val justifyContent: String? = null,
    val alignItems: String? = null,
    val gapPt: Float? = null,
    val flexGrow: Float? = null,            // first number of the `flex` shorthand
    val paddingTopPt: Float? = null,
    val paddingRightPt: Float? = null,
    val paddingBottomPt: Float? = null,
    val paddingLeftPt: Float? = null,
    val marginTop: Dim? = null,
    val marginRight: Dim? = null,
    val marginBottom: Dim? = null,
    val marginLeft: Dim? = null,
    val width: Dim? = null,
    val height: Dim? = null,
    val minWidth: Dim? = null,
    val maxWidth: Dim? = null,
    val minHeight: Dim? = null,
    val maxHeight: Dim? = null,
    val borderTopWidthPt: Float? = null,
    val borderRightWidthPt: Float? = null,
    val borderBottomWidthPt: Float? = null,
    val borderLeftWidthPt: Float? = null,
    val borderTopColor: Long? = null,       // ARGB
    val borderRightColor: Long? = null,
    val borderBottomColor: Long? = null,
    val borderLeftColor: Long? = null,
    val borderRadiusPt: Float? = null,
    val backgroundColor: Long? = null,      // ARGB
    val color: Long? = null,                // ARGB
    val fontSizePt: Float? = null,
    val fontWeight: Int? = null,            // 100–900
    val fontStyleItalic: Boolean? = null,
    val fontFamily: String? = null,
    val lineHeight: LineHeight? = null,
    val textAlign: String? = null,          // left | right | center | justify
    val textTransform: String? = null,      // uppercase | lowercase | capitalize | none
    val letterSpacingPt: Float? = null,
    val opacity: Float? = null,
) {
    val isHidden: Boolean get() = display == "none"

    companion object {
        val EMPTY = TStyle()
    }
}

/** A dynamic value: a literal string or a typed data binding. */
sealed interface TValue {
    data class Literal(val text: String) : TValue

    /** `format` is a hint: text | currency | date | number. Formatting happens client-side. */
    data class Bind(val path: String, val format: String, val fallback: String) : TValue
}

data class TSpan(val value: TValue, val style: TStyle?)

sealed interface TNode {
    val style: TStyle
}

data class TBox(override val style: TStyle, val children: List<TNode>) : TNode
data class TText(override val style: TStyle, val spans: List<TSpan>) : TNode
data class TImage(override val style: TStyle, val source: TValue, val fit: String) : TNode
data class TDivider(override val style: TStyle) : TNode

data class TColumn(val widthPt: Float?) {
    val isAuto: Boolean get() = widthPt == null
}

sealed interface TTableBody {
    data class Repeat(val path: String, val alias: String, val row: TRow) : TTableBody
    data class Rows(val rows: List<TRow>) : TTableBody
}

data class TTable(
    override val style: TStyle,
    val columns: List<TColumn>,
    val header: List<TRow>,
    val body: TTableBody?,
    val footer: List<TRow>,
) : TNode

data class TRow(override val style: TStyle, val cells: List<TCell>) : TNode
data class TCell(override val style: TStyle, val colSpan: Int, val children: List<TNode>) : TNode

data class TRepeat(val path: String, val alias: String, val child: TNode) : TNode {
    override val style: TStyle get() = TStyle.EMPTY
}

data class TConditional(val path: String, val child: TNode) : TNode {
    override val style: TStyle get() = TStyle.EMPTY
}
