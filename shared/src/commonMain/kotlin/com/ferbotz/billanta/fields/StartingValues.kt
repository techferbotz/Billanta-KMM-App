package com.ferbotz.billanta.fields

import com.ferbotz.billanta.core.DecimalString
import com.ferbotz.billanta.core.Iso8601
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Where every field of a new record starts, and what a change leaves behind for next time
 * (FIELDS_APP_GUIDE.md §5, §6, §8). Pure: everything a rule reads is passed in, so each rule is
 * testable on its own and the repository only has to fetch and store.
 */
object StartingValues {

    const val INVOICE_NUMBER = "invoice_number"
    const val CURRENCY = "currency"
    const val QUANTITY = "quantity"
    const val SETTINGS_PREFIX = "settings_invoice_prefix"
    const val SETTINGS_NEXT_NUMBER = "settings_next_invoice_number"
    const val SETTINGS_DEFAULT_CURRENCY = "settings_default_currency"

    /** A new invoice's values, and the counter settings must hold now that a number is taken. */
    data class NewInvoice(val values: FieldValues, val nextInvoiceNumber: Long)

    /**
     * Starting values of a new invoice (§5): the profile's company values, the currency, a fresh
     * number, then every other field [sections] list. Customer fields stay empty until a customer
     * is picked. [settings] should already carry the settings form's defaults (see
     * [withFormDefaults]); [usedNumbers] are the invoice numbers already on this device.
     */
    fun forNewInvoice(
        sections: List<FieldSection>,
        profile: FieldValues,
        settings: FieldValues,
        remembered: Map<String, JsonElement>,
        today: String,
        usedNumbers: Collection<String>,
    ): NewInvoice {
        var values = InvoiceCopies.withCompany(FieldValues.EMPTY, profile)
        // Money is printed in the invoice's currency, so it is set even where no template lists it.
        values = values.withString(CURRENCY, settings.string(SETTINGS_DEFAULT_CURRENCY)?.takeIf { it.isNotBlank() } ?: "INR")
        val prefix = settings.string(SETTINGS_PREFIX).orEmpty()
        val number = nextNumber(prefix, settings.decimal(SETTINGS_NEXT_NUMBER), usedNumbers)
        values = values.withString(INVOICE_NUMBER, "$prefix$number")
        return NewInvoice(fillMissing(sections, values, settings, remembered, today), number + 1)
    }

    /**
     * Gives each invoice field [sections] list that is still absent its starting value. Used for a
     * new invoice, and after a template switch for the fields the new template adds (§7.3).
     *
     * Company, customer and computed fields are left alone (they come from their records, or are
     * calculated), as are the number, the customer picker and the lines. A `due_date` is worked out
     * last, because it counts from another date the same pass may have just set.
     */
    fun fillMissing(
        sections: List<FieldSection>,
        values: FieldValues,
        settings: FieldValues,
        remembered: Map<String, JsonElement>,
        today: String,
    ): FieldValues {
        val fields = sections.flatMap { it.fields }.filter { field ->
            field.isStored && field.owner == FieldOwner.Invoice && field.effectiveType !in NOT_PREFILLED
        }
        val (dueDates, others) = fields.partition { it.effectiveType == FieldType.DueDate }
        var result = values
        others.forEach { field ->
            if (field.id!! !in result) startingValue(field, settings, remembered, today)?.let { result = result.with(field.id, it) }
        }
        dueDates.forEach { field ->
            if (field.id!! !in result) dueDateStart(field, result, settings, remembered)?.let { result = result.withString(field.id, it) }
        }
        return result
    }

    /**
     * A new line (§4.3, §5 step 7): a picked product's values, all of them and not just the
     * columns this template shows, then each column still empty gets its starting value.
     */
    fun forNewLine(
        columns: List<FieldDef>,
        product: FieldValues?,
        settings: FieldValues,
        remembered: Map<String, JsonElement>,
        today: String,
    ): FieldValues {
        var values = product?.without(QUANTITY) ?: FieldValues.EMPTY
        columns.filter { it.isStored && it.owner == FieldOwner.Line }.forEach { column ->
            if (column.id!! !in values) startingValue(column, settings, remembered, today)?.let { values = values.with(column.id, it) }
        }
        return values
    }

    /**
     * A new business profile, customer or product (§8): each field's default. Forms never send
     * `remember`, and a `setting` default reads [settings] (a product's GST % does).
     */
    fun forNewRecord(sections: List<FieldSection>, settings: FieldValues, today: String): FieldValues =
        sections.flatMap { it.fields }.filter { it.isStored }.fold(FieldValues.EMPTY) { values, field ->
            startingValue(field, settings, emptyMap(), today)?.let { values.with(field.id!!, it) } ?: values
        }

    /**
     * [stored] settings with the settings form's literal defaults under them, for when the settings
     * have never been fetched, an offline first launch say (§10).
     */
    fun withFormDefaults(settingsForm: List<FieldSection>, stored: FieldValues): FieldValues =
        settingsForm.flatMap { it.fields }.fold(stored) { values, field ->
            val id = field.id
            val default = field.default as? FieldDefault.Value
            if (id == null || default == null || id in values) values else values.with(id, default.value)
        }

    /**
     * The number of the next invoice: the larger of the settings counter and one more than the
     * highest number already used with [prefix] on this device, so a device that has synced
     * invoices never reissues one (§5 step 4).
     */
    fun nextNumber(prefix: String, storedNext: DecimalString?, usedNumbers: Collection<String>): Long {
        val stored = storedNext?.takeIf { it.scale == 0 }?.unscaled?.coerceAtLeast(1) ?: 1L
        val highest = usedNumbers.mapNotNull { number ->
            number.takeIf { it.startsWith(prefix) }?.substring(prefix.length)
                ?.takeIf { it.isNotEmpty() && it.length <= 15 && it.all { c -> c in '0'..'9' } }
                ?.toLong()
        }.maxOrNull() ?: 0L
        return maxOf(stored, highest + 1)
    }

    /**
     * What a change to [field] leaves in the remembered-values store (§6): the value to keep, or
     * null to delete the entry, as clearing a field does. A `due_date` keeps the gap in days from
     * its `relativeTo` date, and nothing when that date is empty.
     */
    fun rememberedAfterChange(field: FieldDef, values: FieldValues): JsonElement? {
        val id = field.id ?: return null
        val value = values[id] ?: return null
        if (field.effectiveType != FieldType.DueDate) return value
        val due = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::epochDayOf) ?: return null
        val base = values.string(field.dueDateBase)?.let(::epochDayOf) ?: return null
        return (due - base).takeIf { it >= 0 }?.let { JsonPrimitive(it.toString()) }
    }

    /** One field's starting value: remembered (if it still fits) → default → none. */
    private fun startingValue(
        field: FieldDef,
        settings: FieldValues,
        remembered: Map<String, JsonElement>,
        today: String,
    ): JsonElement? {
        val encoding = field.effectiveType?.encoding ?: return null
        if (field.remembers) remembered[field.id!!]?.takeIf { encoding.fits(it) }?.let { return it }
        val candidate = when (val default = field.default) {
            null -> null
            is FieldDefault.Value -> default.value
            FieldDefault.Today -> JsonPrimitive(today)
            is FieldDefault.Setting -> settings[default.id]
        }
        return candidate?.takeIf { encoding.fits(it) }
    }

    /** A `due_date`'s starting date: its base date plus the remembered gap, else the default days. */
    private fun dueDateStart(
        field: FieldDef,
        values: FieldValues,
        settings: FieldValues,
        remembered: Map<String, JsonElement>,
    ): String? {
        val base = values.string(field.dueDateBase)?.let(::epochDayOf) ?: return null
        val days = (if (field.remembers) remembered[field.id!!]?.let(::daysOf) else null)
            ?: when (val default = field.default) {
                is FieldDefault.Value -> daysOf(default.value)
                is FieldDefault.Setting -> settings[default.id]?.let(::daysOf)
                FieldDefault.Today -> 0L
                null -> null
            }
            ?: return null
        return isoDateOf(base + days)
    }

    /** A whole, non-negative number of days, as a gap or a default is stored. */
    private fun daysOf(value: JsonElement): Long? =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.let(DecimalString::parseOrNull)?.takeIf { it.scale == 0 }?.unscaled

    private val NOT_PREFILLED = setOf(
        FieldType.InvoiceNumber, FieldType.Customer, FieldType.LineItems, FieldType.Computed, FieldType.Info,
    )
}

/**
 * An invoice's copy of the company and customer values (FIELDS.md §8.4). The copy is complete —
 * every key the record has, not just the printed ones — so switching template never finds one
 * missing. Keys are recognised by the prefix that names their owner (§3.1), which the backend
 * guarantees never changes for an id.
 */
object InvoiceCopies {

    const val COMPANY_PREFIX = "company_"
    const val CUSTOMER_PREFIX = "customer_"

    /** The invoice field holding the picked customer's id. */
    const val CUSTOMER_ID = "customer"

    /** [values] with every company key replaced by [profile]'s, dropping those it no longer has. */
    fun withCompany(values: FieldValues, profile: FieldValues): FieldValues =
        values.filterKeys { !it.startsWith(COMPANY_PREFIX) }
            .withAll(profile.filterKeys { it.startsWith(COMPANY_PREFIX) })

    /** [values] billed to [customerId], with every customer key replaced by that customer's. */
    fun withCustomer(values: FieldValues, customerId: String?, customer: FieldValues): FieldValues =
        values.filterKeys { !it.startsWith(CUSTOMER_PREFIX) }
            .withAll(customer.filterKeys { it.startsWith(CUSTOMER_PREFIX) })
            .withString(CUSTOMER_ID, customerId)

    /** The customer values an invoice holds: what a customer typed into it is saved as. */
    fun customerPart(values: FieldValues): FieldValues = values.filterKeys { it.startsWith(CUSTOMER_PREFIX) }
}

/** Days since 1970-01-01 for a `YYYY-MM-DD` string, or null when it is not one. */
internal fun epochDayOf(date: String): Long? {
    if (!isIsoDate(date)) return null
    return Iso8601.daysFromCivil(date.substring(0, 4).toInt(), date.substring(5, 7).toInt(), date.substring(8, 10).toInt())
}

/** `YYYY-MM-DD` for a day count since 1970-01-01. */
internal fun isoDateOf(epochDay: Long): String {
    val (y, m, d) = Iso8601.civilFromDays(epochDay)
    return "${y.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
}
