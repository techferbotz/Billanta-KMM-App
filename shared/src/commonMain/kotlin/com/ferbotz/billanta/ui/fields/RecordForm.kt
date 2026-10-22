package com.ferbotz.billanta.ui.fields

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ferbotz.billanta.core.InvoiceDateFormat
import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.fields.DiscountValue
import com.ferbotz.billanta.fields.FieldDef
import com.ferbotz.billanta.fields.FieldOption
import com.ferbotz.billanta.fields.FieldOptions
import com.ferbotz.billanta.fields.FieldSection
import com.ferbotz.billanta.fields.FieldType
import com.ferbotz.billanta.fields.FieldValues
import com.ferbotz.billanta.fields.passes
import com.ferbotz.billanta.model.formatPaise
import com.ferbotz.billanta.ui.components.Overline
import com.ferbotz.billanta.ui.components.SurfaceCard

/**
 * A whole record's form, built from a `/forms` form or a template section: each section under its
 * label, each field drawn for its type, in the order given. Nothing here knows which record it is —
 * a field added on the backend simply appears (FIELDS.md §5).
 *
 * A field whose `visibleIf` fails is not drawn, but its value is never touched (§4.2).
 */
@Composable
fun RecordForm(
    sections: List<FieldSection>,
    values: FieldValues,
    onValuesChange: (FieldValues) -> Unit,
    context: FieldContext,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        sections.forEach { section ->
            val shown = section.fields.filter { it.isShown(values) }
            if (shown.isEmpty()) return@forEach
            if (sections.size > 1) Overline(section.label)
            SurfaceCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    shown.forEach { field ->
                        FieldInput(
                            field = field,
                            value = field.id?.let { values[it] },
                            onChange = { value -> field.id?.let { onValuesChange(values.with(it, value)) } },
                            context = context,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/** Whether to draw [this]: a known type (or fallback), and a passing `visibleIf`. */
fun FieldDef.isShown(values: FieldValues): Boolean =
    effectiveType != null && (visibleIf?.passes { values[it] } ?: true)

/**
 * A field's value as one short line for a summary — a section row, a card. Null when there is
 * nothing worth showing. [optionsFor] resolves a select's label; [dateFormat] writes dates.
 */
fun summaryOf(
    field: FieldDef,
    values: FieldValues,
    optionsFor: (FieldOptions?) -> List<FieldOption>,
    dateFormat: InvoiceDateFormat,
): String? {
    val id = field.id ?: return null
    val text = values.string(id)
    return when (field.effectiveType) {
        null, FieldType.Customer, FieldType.LineItems, FieldType.Info, FieldType.Computed -> null
        FieldType.Bool -> field.label.takeIf { values.bool(id) == true }
        FieldType.Date, FieldType.DueDate -> text?.let(Iso8601::parseOrNull)?.let {
            if (field.effectiveType == FieldType.DueDate) "Due ${dateFormat.format(it)}" else dateFormat.format(it)
        }
        FieldType.Money -> text?.toLongOrNull()?.formatPaise()
        FieldType.Select -> text?.let { value -> optionsFor(field.options).firstOrNull { it.value == value }?.label ?: value }
        FieldType.Image, FieldType.Signature -> "${field.label} added".takeIf { values.image(id) != null }
        FieldType.Discount -> values.discount(id)?.let {
            when (it.kind) {
                DiscountValue.Kind.Percent -> "${it.value}% discount"
                DiscountValue.Kind.Flat -> "${it.value.toLongOrNull()?.formatPaise() ?: it.value} discount"
            }
        }
        FieldType.Gstin -> text?.let { "GSTIN $it" }
        FieldType.Pan -> text?.let { "PAN $it" }
        FieldType.Ifsc -> text?.let { "IFSC $it" }
        FieldType.UpiId -> text?.let { "UPI $it" }
        else -> text?.takeIf { it.isNotBlank() }
    }
}
