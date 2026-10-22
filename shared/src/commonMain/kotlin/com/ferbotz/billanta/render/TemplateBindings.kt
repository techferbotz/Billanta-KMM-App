package com.ferbotz.billanta.render

import com.ferbotz.billanta.core.InvoiceDateFormat
import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.core.parsePaise
import com.ferbotz.billanta.fields.FieldDef
import com.ferbotz.billanta.fields.FieldOption
import com.ferbotz.billanta.fields.FieldOptions
import com.ferbotz.billanta.fields.FieldType
import com.ferbotz.billanta.fields.FieldValues
import com.ferbotz.billanta.model.formatPaise
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.ferbotz.billanta.fields.isTruthy as truthy

/**
 * Resolves a template's bindings against one invoice's render context: a JSON map walked by path. Values
 * arrive as the wire carries them (money as paise strings, dates as `YYYY-MM-DD`), and the binding's
 * format hint turns them into text here, on the client, as TEMPLATE_JSON.md requires.
 */
class BindingContext(
    private val root: JsonObject,
    private val currency: String,
    /** How `format: "date"` bindings are written. Passed in, so one invoice renders one way. */
    private val dateFormat: InvoiceDateFormat = InvoiceDateFormat.Default,
    /** Prints a select's option label instead of its stored value; null when nothing applies. */
    private val selectLabels: SelectLabels? = null,
) {

    /** The value at [path]; the first segment may be a repeat alias bound in [aliases]. */
    fun resolve(path: String, aliases: Map<String, JsonElement> = emptyMap()): JsonElement? {
        val parts = path.split('.')
        var current: JsonElement? = aliases[parts.first()] ?: root[parts.first()]
        for (part in parts.drop(1)) {
            current = (current as? JsonObject)?.get(part) ?: return null
        }
        return current?.takeUnless { it is JsonNull }
    }

    /** Formats a binding for display, falling back to the template's literal fallback. */
    fun resolveText(bind: TValue.Bind, aliases: Map<String, JsonElement> = emptyMap()): String {
        val value = resolve(bind.path, aliases) as? JsonPrimitive
        val formatted = when {
            value == null -> ""
            value.isString -> formatString(bind, value.content, inRepeat = bind.path.substringBefore('.') in aliases)
            // Never printed by a v2 tree; a v1 `true` printed as the word, `false` as nothing.
            else -> value.booleanOrNull?.let { if (it) "true" else "" } ?: value.content
        }
        return formatted.ifBlank { bind.fallback }
    }

    /** FIELDS.md §6.2 truthiness, which also makes a zero money value falsy. */
    fun isTruthy(path: String, aliases: Map<String, JsonElement> = emptyMap()): Boolean =
        truthy(resolve(path, aliases))

    private fun formatString(bind: TValue.Bind, text: String, inRepeat: Boolean): String = when (bind.format) {
        "currency" -> paiseOrNull(text)?.let(::formatCurrency) ?: text
        "date" -> Iso8601.parseOrNull(text)?.let(dateFormat::format) ?: text
        "number" -> trimNumber(text)
        else -> selectLabels?.labelFor(bind.path, inRepeat, text) ?: text
    }

    private fun formatCurrency(paise: Long): String =
        if (currency.equals("INR", ignoreCase = true) || currency.isBlank()) {
            paise.formatPaise()
        } else {
            "$currency ${paise.formatPaise(withSymbol = false)}"
        }

    private fun paiseOrNull(text: String): Long? = try {
        parsePaise(text)
    } catch (_: IllegalArgumentException) {
        null
    }

    /** `"2.50"` → `"2.5"`, `"2.0"` → `"2"` — quantities read naturally. */
    private fun trimNumber(text: String): String =
        if (text.contains('.')) text.trimEnd('0').trimEnd('.') else text
}

/**
 * Prints a `select` field's option label rather than its stored value (FIELDS.md §6.2):
 * `company_state` = `"29"` prints "Karnataka". The definition comes from the template's own
 * sections; a named option list is looked up in `/forms` `optionLists`.
 */
class SelectLabels(sections: List<TemplateSection>, private val optionLists: Map<String, List<FieldOption>>) {

    private val fields: Map<String, FieldDef> =
        sections.flatMap { it.fields }.mapNotNull { f -> f.id?.let { it to f } }.toMap()

    private val columns: Map<String, FieldDef> =
        sections.flatMap { it.fields }.flatMap { it.columns }.mapNotNull { f -> f.id?.let { it to f } }.toMap()

    /**
     * The label for [value] at [path], or null when the path is not a select. A value missing from
     * the options also gets null, so the raw value prints (FIELDS_APP_GUIDE.md §10).
     */
    fun labelFor(path: String, inRepeat: Boolean, value: String): String? {
        val parts = path.split('.')
        val field = when {
            inRepeat && parts.size == 2 -> columns[parts[1]]
            !inRepeat && parts.size == 1 -> fields[parts[0]]
            else -> null
        } ?: return null
        if (field.effectiveType != FieldType.Select) return null
        val options = when (val o = field.options) {
            null -> emptyList()
            is FieldOptions.Inline -> o.options
            is FieldOptions.Named -> optionLists[o.list].orEmpty()
        }
        return options.firstOrNull { it.value == value }?.label
    }
}

/** One line as a template sees it: its values, plus what was calculated for it. */
data class RenderLine(val values: FieldValues, val computed: Map<String, String> = emptyMap())

/**
 * The flat map a v2 template is drawn with (FIELDS.md §8.1):
 * `{ ...invoice.values, line_items: [{ ...line.values, line_total, tax_amount }], subtotal, … }`.
 */
fun renderContextOf(
    values: FieldValues,
    lines: List<RenderLine>,
    totals: Map<String, String>,
): JsonObject = buildJsonObject {
    values.json.forEach { (id, value) -> put(id, value) }
    put(
        "line_items",
        buildJsonArray {
            lines.forEach { line ->
                add(
                    buildJsonObject {
                        line.values.json.forEach { (id, value) -> put(id, value) }
                        line.computed.forEach { (id, value) -> put(id, value) }
                    },
                )
            }
        },
    )
    totals.forEach { (id, value) -> put(id, value) }
}
