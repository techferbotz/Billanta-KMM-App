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
    /** Colours the user may change. Empty on templates that predate theming. */
    val themeTokens: List<ThemeToken> = emptyList(),
    /** Blocks the user may switch off. Empty on templates that predate theming. */
    val sections: List<TemplateSection> = emptyList(),
    /**
     * The controls the edit sheet should offer, in the order the template lists them. Empty when
     * the template does not declare any, in which case [controls] synthesises a sensible default.
     */
    val declaredControls: List<CustomisationControl> = emptyList(),
) {
    val isCustomisable: Boolean get() = themeTokens.isNotEmpty() || sections.any { it.hidable }

    fun defaultColorFor(token: String): Long? =
        themeTokens.firstOrNull { it.name == token }?.defaultArgb

    /**
     * What the edit sheet actually renders. A template that declares its own controls decides the
     * order, the titles and which of its tokens and sections are exposed at all; one that does not
     * falls back to everything it declared under [themeTokens] and [sections], which is what the
     * app did before templates could describe their own controls.
     */
    val controls: List<CustomisationControl>
        get() = declaredControls.ifEmpty {
            buildList {
                add(CustomisationControl.TemplatePicker("Template"))
                themeTokens.forEach { add(CustomisationControl.Color(it.label, it.name)) }
                sections.filter { it.hidable }
                    .forEach { add(CustomisationControl.SectionToggle(it.label, it.id)) }
            }
        }
}

/**
 * A control the template asks the app to show, so that what is customisable travels with the
 * template rather than being hard-coded per template in the app.
 *
 * The template says *what* is needed and what to call it; the app decides *how* to present it —
 * which swatches are in the palette, which templates are on offer. An unrecognised `type` is
 * dropped at parse time, so a backend can introduce a new kind of control before the app can
 * render it without breaking anything.
 */
sealed interface CustomisationControl {
    val title: String

    /** Pick a colour for the named theme token. */
    data class Color(override val title: String, val token: String) : CustomisationControl

    /** Show or hide the named section. */
    data class SectionToggle(override val title: String, val section: String) : CustomisationControl

    /** Switch to another template. The list of templates comes from the app. */
    data class TemplatePicker(override val title: String) : CustomisationControl
}

/** A named colour the template exposes, e.g. `accent`. */
data class ThemeToken(val name: String, val defaultArgb: Long, val label: String)

/** A named block of the page, e.g. `payment`. [hidable] false means the invoice needs it. */
data class TemplateSection(
    val id: String,
    val label: String,
    val hidable: Boolean,
    /** What editing this section actually changes. [SectionEdits.None] means it is display-only. */
    val edits: SectionEdits = SectionEdits.None,
) {
    val isEditable: Boolean get() = edits != SectionEdits.None
}

/**
 * The data behind a section, so the app knows which editor to open for it (APP-007).
 *
 * An absent or unrecognised value is [None] rather than an error, so the backend can introduce a
 * new kind of editor before this build knows how to show it.
 */
enum class SectionEdits(val wireName: String) {
    Customer("customer"),
    InvoiceDetails("invoiceDetails"),
    Items("items"),
    Discount("discount"),
    Notes("notes"),
    Company("company"),
    None("none"),
    ;

    companion object {
        fun fromWire(value: String?): SectionEdits =
            entries.firstOrNull { it.wireName == value } ?: None
    }
}

/**
 * The user's customisation of a template for one invoice: replacement colours by token name, and
 * the sections they have switched off. Applied when the tree is flattened.
 */
data class InvoiceTheme(
    val colorOverrides: Map<String, Long> = emptyMap(),
    val hiddenSections: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = colorOverrides.isEmpty() && hiddenSections.isEmpty()

    companion object {
        val NONE = InvoiceTheme()
    }
}

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

data class TSpan(
    val value: TValue,
    val style: TStyle?,
    /** Style key → token name for this run, overriding the text node's (BE-007). */
    val tokens: Map<String, String>? = null,
)

sealed interface TNode {
    val style: TStyle

    /**
     * Id of the section this node belongs to, when the template declares one — the unit the user
     * can switch off. Absent on templates that predate theming, which simply cannot be customised.
     */
    val section: String? get() = null

    /**
     * Style key → theme token name, for the keys whose colour the user may change. `style` still
     * carries the resolved hex, so a renderer that ignores this draws the template's own colours.
     */
    val tokens: Map<String, String>? get() = null
}

data class TBox(
    override val style: TStyle,
    val children: List<TNode>,
    override val section: String? = null,
    override val tokens: Map<String, String>? = null,
) : TNode

data class TText(
    override val style: TStyle,
    val spans: List<TSpan>,
    override val section: String? = null,
    override val tokens: Map<String, String>? = null,
) : TNode

data class TImage(
    override val style: TStyle,
    val source: TValue,
    val fit: String,
    override val section: String? = null,
    override val tokens: Map<String, String>? = null,
) : TNode

data class TDivider(
    override val style: TStyle,
    override val section: String? = null,
    override val tokens: Map<String, String>? = null,
) : TNode

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
    override val section: String? = null,
    override val tokens: Map<String, String>? = null,
) : TNode

data class TRow(
    override val style: TStyle,
    val cells: List<TCell>,
    override val section: String? = null,
    override val tokens: Map<String, String>? = null,
) : TNode

data class TCell(
    override val style: TStyle,
    val colSpan: Int,
    val children: List<TNode>,
    override val section: String? = null,
    override val tokens: Map<String, String>? = null,
) : TNode

data class TRepeat(val path: String, val alias: String, val child: TNode) : TNode {
    override val style: TStyle get() = TStyle.EMPTY
}

data class TConditional(val path: String, val child: TNode) : TNode {
    override val style: TStyle get() = TStyle.EMPTY
}
