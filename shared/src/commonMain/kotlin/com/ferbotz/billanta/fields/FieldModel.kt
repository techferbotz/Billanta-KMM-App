package com.ferbotz.billanta.fields

import kotlinx.serialization.json.JsonElement

/**
 * Where a field's value is saved (FIELDS.md §3.2). The backend fixes it per id and sends it on
 * every definition, so the app never decides it.
 */
enum class FieldOwner(val wireName: String) {
    Company("company"),
    Customer("customer"),
    Invoice("invoice"),
    Line("line"),
    Settings("settings"),
    Computed("computed"),
    LineComputed("line_computed"),
    ;

    /** Calculated by the app, never stored on any record. */
    val isComputed: Boolean get() = this == Computed || this == LineComputed

    companion object {
        /** An owner this build does not know is saved on the invoice (FIELDS.md §4.6). */
        fun fromWire(value: String?): FieldOwner = entries.firstOrNull { it.wireName == value } ?: Invoice
    }
}

/**
 * How a field is entered and how its value travels (FIELDS.md §4.3).
 *
 * A type this build does not know parses to null on [FieldDef.type] rather than failing, which is
 * what lets the backend ship a new type before every build understands it.
 */
enum class FieldType(val wireName: String) {
    Text("text"),
    Multiline("multiline"),
    Number("number"),
    Money("money"),
    Bool("boolean"),
    Date("date"),
    Select("select"),
    Image("image"),
    Signature("signature"),
    Phone("phone"),
    Email("email"),
    Gstin("gstin"),
    Pan("pan"),
    Ifsc("ifsc"),
    UpiId("upi_id"),
    Pincode("pincode"),
    HsnSac("hsn_sac"),
    InvoiceNumber("invoice_number"),
    DueDate("due_date"),
    CurrencyCode("currency_code"),
    Customer("customer"),
    LineItems("line_items"),
    Discount("discount"),
    Computed("computed"),
    Info("info"),
    ;

    /** How a value of this type sits in a values map (FIELDS_APP_GUIDE.md §2). */
    val encoding: ValueEncoding
        get() = when (this) {
            Number -> ValueEncoding.Decimal
            Money -> ValueEncoding.Paise
            Bool -> ValueEncoding.Bool
            Date, DueDate -> ValueEncoding.Date
            Image, Signature -> ValueEncoding.Image
            Discount -> ValueEncoding.Discount
            // Lines travel beside the values map, never inside it; computed and info are drawn,
            // not stored.
            LineItems, Computed, Info -> ValueEncoding.NotStored
            Text, Multiline, Select, Phone, Email, Gstin, Pan, Ifsc, UpiId, Pincode, HsnSac,
            InvoiceNumber, CurrencyCode, Customer -> ValueEncoding.Text
        }

    companion object {
        fun fromWire(value: String?): FieldType? = entries.firstOrNull { it.wireName == value }
    }
}

/** The JSON shape a stored value has. */
enum class ValueEncoding {
    /** A string: text-like types, a select's option value, a currency code, a customer id. */
    Text,

    /** A decimal string, `"2.5"` — never a JSON number. */
    Decimal,

    /** An integer string of paise, `"150000"`. */
    Paise,

    /** `true` / `false`. */
    Bool,

    /** `"YYYY-MM-DD"`. */
    Date,

    /** `{ "url", "thumbnailUrl" }`, as `POST /media` returned it. */
    Image,

    /** `{ "kind": "flat" | "percent", "value", "beforeTax" }`. */
    Discount,

    /** Not a key in any values map. */
    NotStored,
}

/** A field's starting value when nothing is remembered (FIELDS.md §4.4). */
sealed interface FieldDefault {
    /** A literal in the type's wire format. For a `due_date` it is a number of days. */
    data class Value(val value: JsonElement) : FieldDefault

    /** The device's current date. */
    data object Today : FieldDefault

    /** A `settings_*` value. For a `due_date` it is a number of days. */
    data class Setting(val id: String) : FieldDefault
}

/** Passes while the value at [path] is truthy, or falsy with [negate] (FIELDS.md §6.2). */
data class Gate(val path: String, val negate: Boolean = false)

data class FieldOption(val value: String, val label: String)

/** A select's options: listed inline, or the name of a `/forms` option list. */
sealed interface FieldOptions {
    data class Inline(val options: List<FieldOption>) : FieldOptions
    data class Named(val list: String) : FieldOptions
}

/**
 * How one field is edited (FIELDS.md §4), exactly as the backend resolved it: inherited type,
 * label and options are already filled in, so the app never consults its own list of ids.
 */
data class FieldDef(
    /** A registered field id. Null only on an `info` field, which stores nothing. */
    val id: String?,
    val owner: FieldOwner,
    /** The type as sent, so an unknown one can still be named in a log line. */
    val rawType: String,
    /** Null when this build does not know [rawType]; see [effectiveType]. */
    val type: FieldType?,
    val fallbackType: FieldType? = null,
    val label: String,
    val placeholder: String? = null,
    val help: String? = null,
    val default: FieldDefault? = null,
    val remember: Boolean = false,
    /** An editor rule only: hides the input, never the printed value, and never clears it. */
    val visibleIf: Gate? = null,
    val options: FieldOptions? = null,
    val maxLength: Int? = null,
    val min: Double? = null,
    val max: Double? = null,
    val decimals: Int? = null,
    /** `due_date`: the date field its presets and remembered gap count from. */
    val relativeTo: String? = null,
    /** `due_date`: day offsets offered as chips. */
    val presets: List<Int> = emptyList(),
    /** `line_items`: the definition of each line, column by column. */
    val columns: List<FieldDef> = emptyList(),
    /** `computed`: `currency` (the default), `number` or `text`. */
    val format: String? = null,
    /** `info`: the static text to show. */
    val text: String? = null,
) {
    /**
     * What to draw: the type when this build knows it, else a known [fallbackType], else null,
     * meaning the input is hidden. A hidden input's value is still kept, printed and synced.
     */
    val effectiveType: FieldType? get() = type ?: fallbackType

    /** The date a `due_date` counts from. */
    val dueDateBase: String get() = relativeTo ?: INVOICE_DATE

    /**
     * Whether the last entered value should prefill the next invoice (FIELDS.md §8.3). The backend
     * never sets `remember` on the ids below, but the app is the one that would leak a value into
     * the wrong place if it ever did, so it checks again.
     */
    val remembers: Boolean
        get() = remember && id != null &&
            (owner == FieldOwner.Invoice || owner == FieldOwner.Line) &&
            type !in NEVER_REMEMBERED

    /** A key in some values map, as opposed to a computed or static field. */
    val isStored: Boolean
        get() = id != null && !owner.isComputed && type?.encoding != ValueEncoding.NotStored

    companion object {
        const val INVOICE_DATE = "invoice_date"

        private val NEVER_REMEMBERED = setOf(
            FieldType.InvoiceNumber, FieldType.Customer, FieldType.LineItems,
            FieldType.Computed, FieldType.Info,
        )
    }
}

/**
 * A titled group of fields: one block of a template's page, or one part of a global form. The
 * fields are in editor order.
 */
data class FieldSection(
    val id: String,
    val label: String,
    /** Whether the user may switch the section off. Template sections only; forms never can. */
    val hidable: Boolean,
    val fields: List<FieldDef>,
) {
    /** Every field of the section, with the `line_items` columns after their list. */
    val allFields: List<FieldDef> get() = fields.flatMap { listOf(it) + it.columns }
}
