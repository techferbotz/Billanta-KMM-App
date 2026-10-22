package com.ferbotz.billanta.fields

import com.ferbotz.billanta.core.BillantaJson
import com.ferbotz.billanta.core.DecimalString
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A record's values: field id → value (FIELDS.md §7), kept as the JSON it travels as.
 *
 * Keeping the JSON is the point. A value this build cannot interpret, such as one of a type it does
 * not know, survives a save and a sync untouched, because nothing ever converts it to a Kotlin type
 * and back.
 *
 * **Empty means absent** (§4.5): setting `null`, a blank string, `{}` or `[]` removes the key, so no
 * placeholder is ever stored or sent.
 */
class FieldValues private constructor(val json: JsonObject) {

    val keys: Set<String> get() = json.keys
    val size: Int get() = json.size
    fun isEmpty(): Boolean = json.isEmpty()

    operator fun get(id: String): JsonElement? = json[id]
    operator fun contains(id: String): Boolean = json.containsKey(id)

    /** A string value, or null when absent or not a string. */
    fun string(id: String): String? = (json[id] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun bool(id: String): Boolean? = (json[id] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

    fun decimal(id: String): DecimalString? = string(id)?.let(DecimalString::parseOrNull)

    fun image(id: String): ImageValue? = ImageValue.from(json[id])

    fun discount(id: String = DiscountValue.FIELD_ID): DiscountValue? = DiscountValue.from(json[id])

    /** This map with [id] set to [value], or without [id] when [value] is empty. */
    fun with(id: String, value: JsonElement?): FieldValues {
        if (isEmptyValue(value)) return without(id)
        if (json[id] == value) return this
        return FieldValues(JsonObject(json + (id to value!!)))
    }

    fun withString(id: String, value: String?): FieldValues = with(id, value?.let(::JsonPrimitive))

    fun withBool(id: String, value: Boolean?): FieldValues = with(id, value?.let(::JsonPrimitive))

    fun without(id: String): FieldValues =
        if (id in json) FieldValues(JsonObject(json - id)) else this

    /** This map with every key of [other] laid over it. */
    fun withAll(other: FieldValues): FieldValues =
        if (other.isEmpty()) this else FieldValues(JsonObject(json + other.json))

    fun filterKeys(predicate: (String) -> Boolean): FieldValues =
        FieldValues(JsonObject(json.filterKeys(predicate)))

    fun toJsonString(): String = json.toString()

    override fun equals(other: Any?): Boolean = other is FieldValues && other.json == json
    override fun hashCode(): Int = json.hashCode()
    override fun toString(): String = "FieldValues($json)"

    companion object {
        val EMPTY = FieldValues(JsonObject(emptyMap()))

        /** Wraps [json], dropping any empty value so the invariant holds whatever the source. */
        fun of(json: JsonObject): FieldValues =
            if (json.values.none(::isEmptyValue)) FieldValues(json)
            else FieldValues(JsonObject(json.filterValues { !isEmptyValue(it) }))

        fun of(vararg pairs: Pair<String, JsonElement?>): FieldValues =
            pairs.fold(EMPTY) { acc, (id, value) -> acc.with(id, value) }

        /** Parses a stored map. Anything that is not a JSON object reads as empty, never a crash. */
        fun parse(text: String?): FieldValues {
            if (text.isNullOrBlank()) return EMPTY
            return try {
                (BillantaJson.parseToJsonElement(text) as? JsonObject)?.let(::of) ?: EMPTY
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                EMPTY
            }
        }

        /** FIELDS.md §4.5: null, a blank string, `{}` and `[]` all mean "no value". */
        fun isEmptyValue(value: JsonElement?): Boolean = when (value) {
            null, JsonNull -> true
            is JsonPrimitive -> value.isString && value.content.isBlank()
            is JsonObject -> value.isEmpty()
            is JsonArray -> value.isEmpty()
        }
    }
}

/** An `image` or `signature` value: the two URLs `POST /media` returned. */
data class ImageValue(val url: String, val thumbnailUrl: String? = null) {

    /** The smallest image that exists, for places that only need a glance at it. */
    val previewUrl: String get() = thumbnailUrl ?: url

    fun toJson(): JsonObject = buildJsonObject {
        put("url", url)
        thumbnailUrl?.let { put("thumbnailUrl", it) }
    }

    companion object {
        fun from(value: JsonElement?): ImageValue? {
            val obj = value as? JsonObject ?: return null
            val url = obj.stringOrNull("url")?.takeIf { it.isNotBlank() } ?: return null
            return ImageValue(url, obj.stringOrNull("thumbnailUrl")?.takeIf { it.isNotBlank() })
        }
    }
}

/**
 * The invoice's `discount` value (FIELDS.md §4.3). A flat [value] is paise; a percent is 0–100.
 * [beforeTax] defaults to true when absent, as MONEY.md's inputs table says.
 */
data class DiscountValue(val kind: Kind, val value: String, val beforeTax: Boolean = true) {

    enum class Kind(val wireName: String) { Flat("flat"), Percent("percent") }

    fun toJson(): JsonObject = buildJsonObject {
        put("kind", kind.wireName)
        put("value", value)
        put("beforeTax", beforeTax)
    }

    companion object {
        const val FIELD_ID = "discount"

        fun from(value: JsonElement?): DiscountValue? {
            val obj = value as? JsonObject ?: return null
            val kind = Kind.entries.firstOrNull { it.wireName == obj.stringOrNull("kind") } ?: return null
            val amount = (obj["value"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
                ?.takeIf { it.isNotBlank() } ?: return null
            val beforeTax = (obj["beforeTax"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: true
            return DiscountValue(kind, amount, beforeTax)
        }
    }
}

/**
 * Whether [value] has the JSON shape this encoding stores (FIELDS_APP_GUIDE.md §5). Used to
 * ignore a remembered value that no longer fits: two templates may give one id different types.
 */
fun ValueEncoding.fits(value: JsonElement?): Boolean {
    if (FieldValues.isEmptyValue(value)) return false
    val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
    return when (this) {
        ValueEncoding.Text -> text != null
        ValueEncoding.Decimal -> text != null && DecimalString.parseOrNull(text) != null
        ValueEncoding.Paise -> text != null && text.isNotEmpty() && text.all { it in '0'..'9' }
        ValueEncoding.Bool -> (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull != null
        ValueEncoding.Date -> text != null && isIsoDate(text)
        ValueEncoding.Image -> ImageValue.from(value) != null
        ValueEncoding.Discount -> DiscountValue.from(value) != null
        ValueEncoding.NotStored -> false
    }
}

/** `YYYY-MM-DD` with a plausible month and day. */
fun isIsoDate(text: String): Boolean {
    if (text.length != 10 || text[4] != '-' || text[7] != '-') return false
    val digits = text.removeRange(7, 8).removeRange(4, 5)
    if (digits.any { it !in '0'..'9' }) return false
    val month = text.substring(5, 7).toInt()
    val day = text.substring(8, 10).toInt()
    return month in 1..12 && day in 1..31
}

internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
