package com.ferbotz.billanta.render

import com.ferbotz.billanta.core.BillantaJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Hand-rolled walk of the compiled tree. Manual (rather than polymorphic serialization) so the
 * forward-compat rule is airtight: an unknown node `type` renders nothing, an unknown style key
 * or malformed value is skipped, and only a structurally-broken document fails as a whole.
 */
object TemplateParser {

    fun parse(json: String): TemplateDoc? = try {
        val root = BillantaJson.parseToJsonElement(json).jsonObject
        val rootNode = root["root"]?.let { parseNode(it.jsonObject) } ?: return null
        TemplateDoc(
            schemaVersion = root["schemaVersion"]?.jsonPrimitive?.intOrNull ?: 1,
            compilerVersion = root["compilerVersion"]?.jsonPrimitive?.intOrNull ?: 1,
            page = parsePage(root["page"] as? JsonObject),
            root = rootNode,
            themeTokens = parseThemeTokens(root["theme"] as? JsonObject),
            sections = parseSections(root["sections"] as? JsonArray),
            declaredControls = parseControls(root["customisation"] as? JsonArray),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    private fun parsePage(obj: JsonObject?): PageSpec {
        if (obj == null) return PageSpec()
        val margin = obj["margin"] as? JsonObject
        return PageSpec(
            size = obj.str("size") ?: "A4",
            marginTopPt = margin?.num("top") ?: 36f,
            marginRightPt = margin?.num("right") ?: 36f,
            marginBottomPt = margin?.num("bottom") ?: 36f,
            marginLeftPt = margin?.num("left") ?: 36f,
            fontFamily = obj.str("fontFamily") ?: "Inter",
            baseFontSizePt = obj.num("baseFontSize") ?: 11f,
        )
    }

    /**
     * Colour tokens a template exposes. Absent on templates compiled before theming existed, which
     * simply means nothing is customisable — never an error.
     */
    private fun parseThemeTokens(obj: JsonObject?): List<ThemeToken> {
        val tokens = obj?.get("tokens") as? JsonObject ?: return emptyList()
        return tokens.mapNotNull { (name, value) ->
            val entry = value as? JsonObject ?: return@mapNotNull null
            val argb = entry.str("default")?.let { parseHexColor(it) } ?: return@mapNotNull null
            ThemeToken(name = name, defaultArgb = argb, label = entry.str("label") ?: name)
        }
    }

    private fun parseSections(array: JsonArray?): List<TemplateSection> =
        array?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.str("id") ?: return@mapNotNull null
            TemplateSection(
                id = id,
                label = obj.str("label") ?: id,
                hidable = (obj["hidable"] as? JsonPrimitive)?.booleanOrNull ?: true,
                edits = SectionEdits.fromWire(obj.str("edits")),
            )
        } ?: emptyList()

    /**
     * The controls a template asks the edit sheet to show. A `type` this build does not recognise
     * is skipped rather than treated as an error — the same forward-compatibility rule that governs
     * node types, so the backend can ship a new kind of control ahead of the app.
     */
    private fun parseControls(array: JsonArray?): List<CustomisationControl> =
        array?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val title = obj.str("title").orEmpty()
            when (obj.str("type")) {
                "color" -> obj.str("token")?.let {
                    CustomisationControl.Color(title.ifEmpty { it }, it)
                }
                "section" -> obj.str("section")?.let {
                    CustomisationControl.SectionToggle(title.ifEmpty { it }, it)
                }
                "template" -> CustomisationControl.TemplatePicker(title.ifEmpty { "Template" })
                else -> null
            }
        } ?: emptyList()

    /** Style key → token name, for the colours the user may change. */
    private fun parseTokens(obj: JsonObject): Map<String, String>? {
        val tokens = obj["tokens"] as? JsonObject ?: return null
        val mapped = tokens.mapNotNull { (key, value) ->
            val name = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            if (name == null) null else key to name
        }.toMap()
        return mapped.ifEmpty { null }
    }

    /** Returns null for unknown/broken nodes — the caller renders nothing for them. */
    fun parseNode(obj: JsonObject): TNode? {
        val style = parseStyle(obj["style"] as? JsonObject)
        val section = obj.str("section")
        val tokens = parseTokens(obj)
        return when (obj.str("type")) {
            "box" -> TBox(style, parseChildren(obj["children"]), section, tokens)
            "text" -> TText(style, parseSpans(obj["spans"]), section, tokens)
            "image" -> {
                val source = (obj["source"] as? JsonObject)?.let { parseValue(it) } ?: return null
                TImage(style, source, obj.str("fit") ?: "contain", section, tokens)
            }
            "divider" -> TDivider(style, section, tokens)
            "table" -> parseTable(obj, style)
            "row" -> parseRow(obj)
            "cell" -> parseCell(obj)
            "repeat" -> {
                val child = (obj["child"] as? JsonObject)?.let { parseNode(it) } ?: return null
                TRepeat(obj.str("path") ?: return null, obj.str("as") ?: return null, child)
            }
            "conditional" -> {
                val child = (obj["child"] as? JsonObject)?.let { parseNode(it) } ?: return null
                TConditional(obj.str("path") ?: return null, child)
            }
            else -> null
        }
    }

    private fun parseChildren(el: kotlinx.serialization.json.JsonElement?): List<TNode> =
        (el as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::parseNode) } ?: emptyList()

    private fun parseSpans(el: kotlinx.serialization.json.JsonElement?): List<TSpan> =
        (el as? JsonArray)?.mapNotNull { spanEl ->
            val span = spanEl as? JsonObject ?: return@mapNotNull null
            val value = (span["value"] as? JsonObject)?.let { parseValue(it) } ?: return@mapNotNull null
            TSpan(value, (span["style"] as? JsonObject)?.let { parseStyle(it) }, parseTokens(span))
        } ?: emptyList()

    private fun parseValue(obj: JsonObject): TValue? = when (obj.str("kind")) {
        "literal" -> TValue.Literal(obj.str("text") ?: "")
        "bind" -> TValue.Bind(
            path = obj.str("path") ?: return null,
            format = obj.str("format") ?: "text",
            fallback = obj.str("fallback") ?: "",
        )
        else -> null
    }

    private fun parseTable(obj: JsonObject, style: TStyle): TTable {
        val columns = (obj["columns"] as? JsonArray)?.mapNotNull { colEl ->
            val col = colEl as? JsonObject ?: return@mapNotNull null
            when (val w = col["width"]) {
                is JsonPrimitive -> if (w.isString) TColumn(null) else TColumn(w.floatOrNull)
                else -> TColumn(null)
            }
        } ?: emptyList()

        val body = (obj["body"] as? JsonObject)?.let { bodyObj ->
            val repeat = bodyObj["repeat"] as? JsonObject
            val rowObj = bodyObj["row"] as? JsonObject
            when {
                repeat != null && rowObj != null -> {
                    val row = parseRow(rowObj)
                    val path = repeat.str("path")
                    val alias = repeat.str("as")
                    if (row != null && path != null && alias != null) TTableBody.Repeat(path, alias, row) else null
                }
                else -> TTableBody.Rows(parseRows(bodyObj["rows"]))
            }
        }

        return TTable(
            style = style,
            columns = columns,
            header = parseRows(obj["header"]),
            body = body,
            footer = parseRows(obj["footer"]),
            section = obj.str("section"),
            tokens = parseTokens(obj),
        )
    }

    private fun parseRows(el: kotlinx.serialization.json.JsonElement?): List<TRow> =
        (el as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::parseRow) } ?: emptyList()

    private fun parseRow(obj: JsonObject): TRow? {
        if (obj.str("type") != "row") return null
        val cells = (obj["cells"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::parseCell) } ?: emptyList()
        return TRow(parseStyle(obj["style"] as? JsonObject), cells, obj.str("section"), parseTokens(obj))
    }

    private fun parseCell(obj: JsonObject): TCell? {
        if (obj.str("type") != "cell") return null
        return TCell(
            style = parseStyle(obj["style"] as? JsonObject),
            colSpan = (obj["colSpan"]?.jsonPrimitive?.intOrNull ?: 1).coerceIn(1, 64),
            children = parseChildren(obj["children"]),
            section = obj.str("section"),
            tokens = parseTokens(obj),
        )
    }

    // ---- style ---------------------------------------------------------------------------------

    fun parseStyle(obj: JsonObject?): TStyle {
        if (obj == null) return TStyle.EMPTY
        return TStyle(
            display = obj.str("display"),
            flexDirection = obj.str("flexDirection"),
            justifyContent = obj.str("justifyContent"),
            alignItems = obj.str("alignItems"),
            gapPt = obj.num("gap"),
            flexGrow = obj.str("flex")?.trim()?.split(" ")?.firstOrNull()?.toFloatOrNull()
                ?: obj.num("flex"),
            paddingTopPt = obj.num("paddingTop"),
            paddingRightPt = obj.num("paddingRight"),
            paddingBottomPt = obj.num("paddingBottom"),
            paddingLeftPt = obj.num("paddingLeft"),
            marginTop = obj.dim("marginTop"),
            marginRight = obj.dim("marginRight"),
            marginBottom = obj.dim("marginBottom"),
            marginLeft = obj.dim("marginLeft"),
            width = obj.dim("width"),
            height = obj.dim("height"),
            minWidth = obj.dim("minWidth"),
            maxWidth = obj.dim("maxWidth"),
            minHeight = obj.dim("minHeight"),
            maxHeight = obj.dim("maxHeight"),
            borderTopWidthPt = obj.num("borderTopWidth"),
            borderRightWidthPt = obj.num("borderRightWidth"),
            borderBottomWidthPt = obj.num("borderBottomWidth"),
            borderLeftWidthPt = obj.num("borderLeftWidth"),
            borderTopColor = obj.color("borderTopColor"),
            borderRightColor = obj.color("borderRightColor"),
            borderBottomColor = obj.color("borderBottomColor"),
            borderLeftColor = obj.color("borderLeftColor"),
            borderRadiusPt = obj.num("borderRadius"),
            backgroundColor = obj.color("backgroundColor"),
            color = obj.color("color"),
            fontSizePt = obj.num("fontSize"),
            fontWeight = obj["fontWeight"]?.jsonPrimitive?.intOrNull,
            fontStyleItalic = obj.str("fontStyle")?.let { it == "italic" },
            fontFamily = obj.str("fontFamily"),
            lineHeight = obj["lineHeight"]?.jsonPrimitive?.floatOrNull?.let { v ->
                // Unitless multiplier vs a points value: the compiler emits multipliers small
                // (≤ ~4) and pt values large — disambiguate on that.
                if (v <= 4f) LineHeight.Multiplier(v) else LineHeight.Pt(v)
            },
            textAlign = obj.str("textAlign"),
            textTransform = obj.str("textTransform"),
            letterSpacingPt = obj.num("letterSpacing"),
            opacity = obj["opacity"]?.jsonPrimitive?.floatOrNull,
        )
    }

    // ---- json helpers --------------------------------------------------------------------------

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.num(key: String): Float? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.floatOrNull

    private fun JsonObject.dim(key: String): Dim? {
        val el = this[key] as? JsonPrimitive ?: return null
        return if (el.isString) {
            val text = el.contentOrNull?.trim() ?: return null
            when {
                text == "auto" -> Dim.Auto
                text.endsWith("%") -> text.dropLast(1).toFloatOrNull()?.let { Dim.Percent(it) }
                else -> null
            }
        } else {
            el.floatOrNull?.let { Dim.Pt(it) }
        }
    }

    private fun JsonObject.color(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.let { parseHexColor(it) }

    /** ARGB → `#rrggbb`, the form the backend and our own stored overrides use. */
    fun formatHexColor(argb: Long): String {
        val rgb = (argb and 0xFFFFFF).toString(16).padStart(6, '0')
        return "#$rgb"
    }

    /** `#rrggbb` or `#rrggbbaa` (canonical lowercase per the contract) → ARGB. */
    fun parseHexColor(hex: String): Long? {
        val t = hex.trim().removePrefix("#")
        return when (t.length) {
            6 -> t.toLongOrNull(16)?.let { 0xFF000000L or it }
            8 -> t.toLongOrNull(16)?.let { rgba ->
                val alpha = rgba and 0xFFL
                val rgb = (rgba shr 8) and 0xFFFFFFL
                (alpha shl 24) or rgb
            }
            else -> null
        }
    }

    @Suppress("unused")
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}
