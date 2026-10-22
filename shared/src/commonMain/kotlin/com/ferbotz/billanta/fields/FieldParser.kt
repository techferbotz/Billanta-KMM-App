package com.ferbotz.billanta.fields

import com.ferbotz.billanta.core.BillantaJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Reads field definitions and sections. One parser serves both places they arrive — a compiled
 * template's `sections` and `GET /forms` — because the backend sends them in the same shape.
 *
 * Forward compatibility is the rule throughout (FIELDS.md §4.6): an unknown attribute is ignored,
 * an unknown `type` keeps the field with a null [FieldDef.type], an unknown `owner` reads as
 * invoice, and an unknown `default.kind` reads as no default. Only a definition that cannot be
 * used at all, such as a stored field without an id, is dropped. Nothing here throws.
 */
object FieldParser {

    fun parseSections(array: JsonElement?, hidableByDefault: Boolean): List<FieldSection> =
        (array as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let { obj -> parseSection(obj, hidableByDefault) } }
            ?: emptyList()

    private fun parseSection(obj: JsonObject, hidableByDefault: Boolean): FieldSection? {
        val id = obj.stringOrNull("id")?.takeIf { it.isNotBlank() } ?: return null
        return FieldSection(
            id = id,
            label = obj.stringOrNull("label") ?: id,
            hidable = obj.boolOrNull("hidable") ?: hidableByDefault,
            fields = parseFields(obj["fields"]),
        )
    }

    fun parseFields(array: JsonElement?): List<FieldDef> =
        (array as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::parseField) } ?: emptyList()

    fun parseField(obj: JsonObject): FieldDef? {
        val rawType = obj.stringOrNull("type").orEmpty()
        val type = FieldType.fromWire(rawType)
        val id = obj.stringOrNull("id")?.takeIf { it.isNotBlank() }
        // Every field but `info` names the value it edits. Without an id there is nothing to show
        // or store, whatever the type turns out to be.
        if (id == null && type != FieldType.Info) return null
        return FieldDef(
            id = id,
            owner = FieldOwner.fromWire(obj.stringOrNull("owner")),
            rawType = rawType,
            type = type,
            fallbackType = FieldType.fromWire(obj.stringOrNull("fallbackType")),
            label = obj.stringOrNull("label") ?: id.orEmpty(),
            placeholder = obj.stringOrNull("placeholder"),
            help = obj.stringOrNull("help"),
            default = parseDefault(obj["default"]),
            remember = obj.boolOrNull("remember") ?: false,
            visibleIf = parseGate(obj["visibleIf"]),
            options = parseOptions(obj["options"]),
            maxLength = obj.intOrNull("maxLength"),
            min = obj.doubleOrNull("min"),
            max = obj.doubleOrNull("max"),
            decimals = obj.intOrNull("decimals"),
            relativeTo = obj.stringOrNull("relativeTo"),
            presets = (obj["presets"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: emptyList(),
            columns = parseFields(obj["columns"]),
            format = obj.stringOrNull("format"),
            text = obj.stringOrNull("text"),
        )
    }

    fun parseGate(element: JsonElement?): Gate? {
        val obj = element as? JsonObject ?: return null
        val path = obj.stringOrNull("path")?.takeIf { it.isNotBlank() } ?: return null
        return Gate(path, negate = obj.boolOrNull("negate") ?: false)
    }

    private fun parseDefault(element: JsonElement?): FieldDefault? {
        val obj = element as? JsonObject ?: return null
        return when (obj.stringOrNull("kind")) {
            "value" -> obj["value"]?.takeUnless { it is JsonNull }?.let { FieldDefault.Value(it) }
            "today" -> FieldDefault.Today
            "setting" -> obj.stringOrNull("id")?.let { FieldDefault.Setting(it) }
            else -> null
        }
    }

    private fun parseOptions(element: JsonElement?): FieldOptions? = when (element) {
        is JsonPrimitive -> element.takeIf { it.isString }?.content?.let { FieldOptions.Named(it) }
        is JsonArray -> FieldOptions.Inline(parseOptionList(element))
        else -> null
    }

    fun parseOptionList(element: JsonElement?): List<FieldOption> =
        (element as? JsonArray)?.mapNotNull { option ->
            val obj = option as? JsonObject ?: return@mapNotNull null
            val value = (obj["value"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
                ?: return@mapNotNull null
            FieldOption(value, obj.stringOrNull("label") ?: value)
        } ?: emptyList()

    private fun JsonObject.boolOrNull(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

    private fun JsonObject.intOrNull(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

    private fun JsonObject.doubleOrNull(key: String): Double? =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
}

/**
 * `GET /forms` (FIELDS.md §5): the business profile, customer, product and settings screens, plus
 * the option lists a select may name. The app builds those four screens from this alone.
 */
data class FormsDoc(
    val schemaVersion: Int,
    val optionLists: Map<String, List<FieldOption>>,
    val company: List<FieldSection>,
    val customer: List<FieldSection>,
    val product: List<FieldSection>,
    val settings: List<FieldSection>,
) {
    /** The options a select offers, whether listed inline or named. Empty when unknown. */
    fun optionsFor(options: FieldOptions?): List<FieldOption> = when (options) {
        null -> emptyList()
        is FieldOptions.Inline -> options.options
        is FieldOptions.Named -> optionLists[options.list].orEmpty()
    }

    companion object {
        val EMPTY = FormsDoc(1, emptyMap(), emptyList(), emptyList(), emptyList(), emptyList())

        /**
         * Parses the document, with or without the API's `{ success, data }` envelope (the bundled
         * copy is the bare document). Returns null when it is not a forms document at all.
         */
        fun parse(json: String): FormsDoc? = try {
            val root = BillantaJson.parseToJsonElement(json) as? JsonObject
            val doc = (root?.get("data") as? JsonObject) ?: root
            val forms = doc?.get("forms") as? JsonObject
            if (doc == null || forms == null) {
                null
            } else {
                FormsDoc(
                    schemaVersion = (doc["schemaVersion"] as? JsonPrimitive)?.intOrNull ?: 1,
                    optionLists = (doc["optionLists"] as? JsonObject)
                        ?.mapValues { (_, list) -> FieldParser.parseOptionList(list) }
                        .orEmpty(),
                    company = formSections(forms, "company"),
                    customer = formSections(forms, "customer"),
                    product = formSections(forms, "product"),
                    settings = formSections(forms, "settings"),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

        /** Form sections are never hidable (FIELDS.md §5). */
        private fun formSections(forms: JsonObject, name: String): List<FieldSection> =
            FieldParser.parseSections((forms[name] as? JsonObject)?.get("sections"), hidableByDefault = false)
                .map { it.copy(hidable = false) }
    }
}
