package com.ferbotz.billanta.ui.fields

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ferbotz.billanta.core.InvoiceDateFormat
import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.core.parsePaise
import com.ferbotz.billanta.fields.DiscountValue
import com.ferbotz.billanta.fields.FieldDef
import com.ferbotz.billanta.fields.FieldOption
import com.ferbotz.billanta.fields.FieldOptions
import com.ferbotz.billanta.fields.FieldType
import com.ferbotz.billanta.fields.FormatChecks
import com.ferbotz.billanta.fields.ImageValue
import com.ferbotz.billanta.model.formatPaise
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.AppIcon
import com.ferbotz.billanta.ui.BillantaIcon
import com.ferbotz.billanta.ui.components.BillantaTextField
import com.ferbotz.billanta.ui.components.ChipRow
import com.ferbotz.billanta.ui.components.FieldLabel
import com.ferbotz.billanta.ui.components.PickerField
import com.ferbotz.billanta.ui.components.SecondaryButton
import com.ferbotz.billanta.ui.components.TextButtonLink
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * What an input needs beyond its own value: the option lists a select may name, how dates read,
 * other fields' values (a due date counts from one), and whether images can be added here.
 */
class FieldContext(
    private val optionLists: Map<String, List<FieldOption>>,
    val dateFormat: InvoiceDateFormat,
    /** Another field's current value, by id — for `visibleIf` and a due date's base. */
    val valueOf: (String) -> JsonElement?,
    /** Why an image cannot be added right now, or null when it can. */
    val imageBlocker: String?,
    /** Picks an image and uploads it, handing back what `POST /media` returned. */
    val pickImage: (onPicked: (ImageValue) -> Unit) -> Unit,
    val uploadingImage: Boolean,
    /** A computed value to show read-only (a total), formatted by the caller's render context. */
    val computedText: (FieldDef) -> String? = { null },
) {
    fun optionsFor(options: FieldOptions?): List<FieldOption> = when (options) {
        null -> emptyList()
        is FieldOptions.Inline -> options.options
        is FieldOptions.Named -> optionLists[options.list].orEmpty()
    }
}

/**
 * One input, drawn for the field's type (FIELDS_APP_GUIDE.md §4.2). A type this build does not know
 * draws its fallback, or nothing at all; either way its value is left exactly as it was, so it
 * still prints and still syncs. `customer` and `line_items` are whole editors of their own and are
 * drawn by the section editor, not here.
 */
@Composable
fun FieldInput(
    field: FieldDef,
    value: JsonElement?,
    onChange: (JsonElement?) -> Unit,
    context: FieldContext,
    modifier: Modifier = Modifier,
) {
    val type = field.effectiveType ?: return
    // Remembered per field id so an input never loses what is being typed when the stored value
    // comes back reformatted (₹1,500 stored as "150000", say).
    when (type) {
        FieldType.Text, FieldType.Multiline, FieldType.Phone, FieldType.Email, FieldType.Gstin, FieldType.Pan,
        FieldType.Ifsc, FieldType.UpiId, FieldType.Pincode, FieldType.HsnSac, FieldType.InvoiceNumber,
        -> TextInput(field, type, value, onChange, modifier)
        FieldType.Number -> NumberInput(field, value, onChange, modifier)
        FieldType.Money -> MoneyInput(field, value, onChange, modifier)
        FieldType.Bool -> SwitchInput(field, value, onChange, modifier)
        FieldType.Date -> DateInput(field, value, onChange, context, modifier)
        FieldType.DueDate -> DueDateInput(field, value, onChange, context, modifier)
        FieldType.Select -> SelectInput(field, value, onChange, context, modifier)
        FieldType.Image, FieldType.Signature -> ImageInput(field, value, onChange, context, modifier)
        FieldType.CurrencyCode -> CurrencyInput(field, value, onChange, modifier)
        FieldType.Discount -> DiscountInput(field, value, onChange, modifier)
        FieldType.Computed -> ComputedDisplay(field, context, modifier)
        FieldType.Info -> InfoDisplay(field, modifier)
        FieldType.Customer, FieldType.LineItems -> Unit
    }
}

private fun JsonElement?.text(): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

private fun textOrNull(text: String): JsonElement? = text.takeIf { it.isNotBlank() }?.let(::JsonPrimitive)

@Composable
private fun Help(field: FieldDef, warning: String? = null) {
    val c = BillantaTheme.colors
    val line = warning ?: field.help ?: return
    Text(
        line,
        style = BillantaTheme.type.caption,
        color = if (warning != null) c.warning else c.textSecondary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun TextInput(field: FieldDef, type: FieldType, value: JsonElement?, onChange: (JsonElement?) -> Unit, modifier: Modifier) {
    var text by remember(field.id) { mutableStateOf(value.text()) }
    Column(modifier) {
        BillantaTextField(
            value = text,
            onValueChange = { typed ->
                val limited = field.maxLength?.let { typed.take(it) } ?: typed
                text = limited
                onChange(textOrNull(limited))
            },
            label = field.label,
            placeholder = field.placeholder.orEmpty(),
            keyboardType = when (type) {
                FieldType.Phone -> KeyboardType.Phone
                FieldType.Email -> KeyboardType.Email
                FieldType.Pincode, FieldType.HsnSac -> KeyboardType.Number
                else -> KeyboardType.Text
            },
            singleLine = type != FieldType.Multiline,
            modifier = Modifier.fillMaxWidth(),
        )
        Help(field, FormatChecks.warningFor(type, text))
    }
}

/**
 * A decimal number. Typing is constrained rather than checked: no sign, one dot, at most
 * [FieldDef.decimals] places, and nothing over [FieldDef.max] — so what is stored is always
 * something the server accepts (a tax rate over 100 would be refused).
 */
@Composable
private fun NumberInput(field: FieldDef, value: JsonElement?, onChange: (JsonElement?) -> Unit, modifier: Modifier) {
    var text by remember(field.id) { mutableStateOf(value.text()) }
    Column(modifier) {
        BillantaTextField(
            value = text,
            onValueChange = { typed ->
                if (acceptsDecimal(typed, field.decimals ?: 8, field.max)) {
                    text = typed
                    onChange(textOrNull(typed.trimEnd('.')))
                }
            },
            label = field.label,
            placeholder = field.placeholder ?: "0",
            keyboardType = if (field.decimals == 0) KeyboardType.Number else KeyboardType.Decimal,
            modifier = Modifier.fillMaxWidth(),
        )
        Help(field)
    }
}

internal fun acceptsDecimal(text: String, decimals: Int, max: Double?): Boolean {
    if (text.isEmpty()) return true
    if (text.any { it != '.' && it !in '0'..'9' } || text.count { it == '.' } > 1) return false
    if (decimals == 0 && '.' in text) return false
    val fraction = text.substringAfter('.', "")
    if (fraction.length > decimals) return false
    val numeric = text.trimEnd('.').ifEmpty { "0" }
    return max == null || (numeric.toDoubleOrNull() ?: 0.0) <= max
}

/** Rupees in, whole paise stored: `"1500.5"` typed is `"150050"` on the wire. */
@Composable
private fun MoneyInput(field: FieldDef, value: JsonElement?, onChange: (JsonElement?) -> Unit, modifier: Modifier) {
    var text by remember(field.id) { mutableStateOf(rupeesText(value.text())) }
    Column(modifier) {
        BillantaTextField(
            value = text,
            onValueChange = { typed ->
                if (acceptsDecimal(typed, decimals = 2, max = null) && typed.length <= 15) {
                    text = typed
                    onChange(typed.trimEnd('.').takeIf { it.isNotEmpty() }?.let { JsonPrimitive(rupeesToPaise(it).toString()) })
                }
            },
            label = field.label,
            placeholder = field.placeholder ?: "0.00",
            leadingIcon = null,
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.fillMaxWidth(),
        )
        Help(field)
    }
}

/** A stored paise string back to what the rupee field shows: `"150050"` → `"1500.50"`, `"150000"` → `"1500"`. */
internal fun rupeesText(paise: String): String {
    val value = paise.toLongOrNull() ?: return ""
    val rupees = value / 100
    val rest = value % 100
    return if (rest == 0L) rupees.toString() else "$rupees.${rest.toString().padStart(2, '0')}"
}

/** `"1500.5"` rupees → 150050 paise; anything unparseable is zero. */
internal fun rupeesToPaise(rupees: String): Long = try {
    val whole = rupees.substringBefore('.').ifEmpty { "0" }
    val fraction = rupees.substringAfter('.', "").padEnd(2, '0').take(2)
    parsePaise(whole + fraction)
} catch (_: IllegalArgumentException) {
    0L
}

@Composable
private fun SwitchInput(field: FieldDef, value: JsonElement?, onChange: (JsonElement?) -> Unit, modifier: Modifier) {
    val c = BillantaTheme.colors
    val checked = (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: false
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(field.label, style = BillantaTheme.type.body, color = c.textPrimary, modifier = Modifier.weight(1f))
            Switch(
                checked = checked,
                // Off is stored as `false`, not removed: "off" is a choice the user made.
                onCheckedChange = { onChange(JsonPrimitive(it)) },
                colors = billantaSwitchColors(),
            )
        }
        Help(field)
    }
}

@Composable
internal fun billantaSwitchColors() = BillantaTheme.colors.let { c ->
    SwitchDefaults.colors(
        checkedThumbColor = c.onPrimary,
        checkedTrackColor = c.primary,
        uncheckedTrackColor = c.surfaceAlt,
        uncheckedBorderColor = c.border,
        uncheckedThumbColor = c.textMuted,
    )
}

/** `YYYY-MM-DD` ↔ the picker's UTC midnight. */
private fun millisOf(date: String): Long? = date.takeIf { it.length == 10 }?.let(Iso8601::parseOrNull)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerDialogFor(initial: String, onPicked: (String) -> Unit, onDismiss: () -> Unit) {
    val picker = rememberDatePickerState(initialSelectedDateMillis = millisOf(initial))
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButtonLink("Set", onClick = {
                picker.selectedDateMillis?.let { onPicked(Iso8601.formatDate(it)) }
                onDismiss()
            })
        },
        dismissButton = { TextButtonLink("Cancel", onClick = onDismiss) },
    ) { DatePicker(state = picker) }
}

@Composable
private fun DateInput(field: FieldDef, value: JsonElement?, onChange: (JsonElement?) -> Unit, context: FieldContext, modifier: Modifier) {
    var picking by remember { mutableStateOf(false) }
    val current = value.text()
    Column(modifier) {
        PickerField(
            label = field.label,
            value = millisOf(current)?.let(context.dateFormat::format),
            placeholder = field.placeholder ?: "Pick a date",
            onClick = { picking = true },
            trailingIcon = AppIcon.Calendar,
        )
        Help(field)
    }
    if (picking) DatePickerDialogFor(current, onPicked = { onChange(JsonPrimitive(it)) }, onDismiss = { picking = false })
}

/**
 * A due date: chips for the template's presets, counted from its `relativeTo` date, plus any date
 * at all. The date is what is stored; the remembered value is the gap, worked out on save. With no
 * base date the presets cannot mean anything, so only the picker is offered (§10).
 */
@Composable
private fun DueDateInput(field: FieldDef, value: JsonElement?, onChange: (JsonElement?) -> Unit, context: FieldContext, modifier: Modifier) {
    var picking by remember { mutableStateOf(false) }
    val current = value.text()
    val base = context.valueOf(field.dueDateBase).text().let(::millisOf)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(field.label)
        if (base != null && field.presets.isNotEmpty()) {
            ChipRow(
                items = field.presets,
                isSelected = { days -> millisOf(current) == base + days * DAY_MILLIS },
                label = { days -> if (days == 0) "On receipt" else "$days days" },
                onSelect = { days -> onChange(JsonPrimitive(Iso8601.formatDate(base + days * DAY_MILLIS))) },
            )
        }
        PickerField(
            label = "",
            value = millisOf(current)?.let(context.dateFormat::format),
            placeholder = "Pick a date",
            onClick = { picking = true },
            trailingIcon = AppIcon.Calendar,
        )
        if (current.isNotEmpty()) TextButtonLink("No due date", onClick = { onChange(null) })
        Help(field)
    }
    if (picking) DatePickerDialogFor(current, onPicked = { onChange(JsonPrimitive(it)) }, onDismiss = { picking = false })
}

private const val DAY_MILLIS = 86_400_000L

/** A few options are chips; a long list (the states) opens a list to pick from. */
@Composable
private fun SelectInput(field: FieldDef, value: JsonElement?, onChange: (JsonElement?) -> Unit, context: FieldContext, modifier: Modifier) {
    val options = context.optionsFor(field.options)
    val current = value.text()
    var picking by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (options.size in 1..4) {
            FieldLabel(field.label)
            ChipRow(
                items = options,
                isSelected = { it.value == current },
                label = { it.label },
                onSelect = { onChange(JsonPrimitive(it.value)) },
            )
        } else {
            PickerField(
                label = field.label,
                // A value missing from the options is shown as stored, never hidden (§10).
                value = options.firstOrNull { it.value == current }?.label ?: current.takeIf { it.isNotEmpty() },
                placeholder = field.placeholder ?: "Choose",
                onClick = { picking = true },
                trailingIcon = AppIcon.ChevronDown,
            )
        }
        Help(field)
    }
    if (picking) {
        val c = BillantaTheme.colors
        AlertDialog(
            onDismissRequest = { picking = false },
            containerColor = c.surface,
            title = { Text(field.label, style = BillantaTheme.type.sectionTitle, color = c.textPrimary) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    options.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .clickable { onChange(JsonPrimitive(option.value)); picking = false }
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(option.label, style = BillantaTheme.type.body, color = c.textPrimary, modifier = Modifier.weight(1f))
                            if (option.value == current) BillantaIcon(AppIcon.Check, c.primary, size = 18.dp)
                        }
                    }
                }
            },
            confirmButton = {
                if (current.isNotEmpty()) TextButtonLink("Clear", onClick = { onChange(null); picking = false })
            },
            dismissButton = { TextButtonLink("Cancel", onClick = { picking = false }) },
        )
    }
}

/** An image or a signature: chosen from the gallery and uploaded at once, since media has no offline queue. */
@Composable
private fun ImageInput(field: FieldDef, value: JsonElement?, onChange: (JsonElement?) -> Unit, context: FieldContext, modifier: Modifier) {
    val c = BillantaTheme.colors
    val image = ImageValue.from(value)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(field.label)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(c.surfaceAlt)
                    .border(1.dp, c.border, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (image != null) {
                    AsyncImage(
                        model = image.previewUrl,
                        contentDescription = field.label,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(72.dp).padding(4.dp),
                    )
                } else {
                    BillantaIcon(AppIcon.Camera, c.textMuted, size = 26.dp)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val blocker = context.imageBlocker
                SecondaryButton(
                    when {
                        context.uploadingImage -> "Uploading…"
                        image == null -> "Add ${field.label.lowercase()}"
                        else -> "Change"
                    },
                    onClick = { context.pickImage { onChange(it.toJson()) } },
                    enabled = blocker == null && !context.uploadingImage,
                    leadingIcon = AppIcon.Camera,
                )
                if (image != null) TextButtonLink("Remove", color = c.danger, onClick = { onChange(null) })
                blocker?.let { Text(it, style = BillantaTheme.type.caption, color = c.textSecondary) }
            }
        }
        Help(field)
    }
}

private val COMMON_CURRENCIES = listOf("INR", "USD", "EUR", "GBP", "AED", "SGD")

@Composable
private fun CurrencyInput(field: FieldDef, value: JsonElement?, onChange: (JsonElement?) -> Unit, modifier: Modifier) {
    var text by remember(field.id) { mutableStateOf(value.text()) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(field.label)
        ChipRow(
            items = COMMON_CURRENCIES,
            isSelected = { it == text },
            label = { it },
            onSelect = { text = it; onChange(JsonPrimitive(it)) },
        )
        BillantaTextField(
            value = text,
            onValueChange = { typed ->
                // An ISO 4217 code: three letters, which the server checks.
                val code = typed.uppercase().filter { it in 'A'..'Z' }.take(3)
                text = code
                onChange(code.takeIf { it.length == 3 }?.let(::JsonPrimitive))
            },
            placeholder = "Other code, e.g. JPY",
            modifier = Modifier.fillMaxWidth(),
        )
        Help(field)
    }
}

/**
 * The invoice discount: none, a percent (0–100) or a flat amount (rupees in, paise stored), and
 * whether it comes off before tax — the GST-correct default — or after.
 */
@Composable
private fun DiscountInput(field: FieldDef, value: JsonElement?, onChange: (JsonElement?) -> Unit, modifier: Modifier) {
    val c = BillantaTheme.colors
    val stored = DiscountValue.from(value)
    var kind by remember(field.id) { mutableStateOf(stored?.kind) }
    var amount by remember(field.id) {
        mutableStateOf(
            when (stored?.kind) {
                DiscountValue.Kind.Flat -> rupeesText(stored.value)
                DiscountValue.Kind.Percent -> stored.value
                null -> ""
            },
        )
    }
    var beforeTax by remember(field.id) { mutableStateOf(stored?.beforeTax ?: true) }

    fun emit() {
        val k = kind
        val typed = amount.trimEnd('.')
        onChange(
            if (k == null || typed.isEmpty()) null
            else DiscountValue(
                kind = k,
                value = if (k == DiscountValue.Kind.Flat) rupeesToPaise(typed).toString() else typed,
                beforeTax = beforeTax,
            ).toJson(),
        )
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(field.label)
        ChipRow(
            items = listOf<DiscountValue.Kind?>(null, DiscountValue.Kind.Percent, DiscountValue.Kind.Flat),
            isSelected = { it == kind },
            label = { when (it) { null -> "None"; DiscountValue.Kind.Percent -> "Percent"; DiscountValue.Kind.Flat -> "Amount" } },
            onSelect = {
                if (it != kind) amount = ""
                kind = it
                emit()
            },
        )
        if (kind != null) {
            BillantaTextField(
                value = amount,
                onValueChange = { typed ->
                    val ok = if (kind == DiscountValue.Kind.Percent) acceptsDecimal(typed, 2, 100.0) else acceptsDecimal(typed, 2, null)
                    if (ok) {
                        amount = typed
                        emit()
                    }
                },
                label = if (kind == DiscountValue.Kind.Percent) "Percent off" else "Amount off (₹)",
                placeholder = "0",
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Before tax", style = BillantaTheme.type.body, color = c.textPrimary)
                    Text(
                        if (beforeTax) "GST is charged on the discounted amount" else "GST is charged on the full amount",
                        style = BillantaTheme.type.caption,
                        color = c.textSecondary,
                    )
                }
                Switch(checked = beforeTax, onCheckedChange = { beforeTax = it; emit() }, colors = billantaSwitchColors())
            }
        }
        Help(field)
    }
}

@Composable
private fun ComputedDisplay(field: FieldDef, context: FieldContext, modifier: Modifier) {
    val c = BillantaTheme.colors
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(field.label, style = BillantaTheme.type.bodyStrong, color = c.textPrimary, modifier = Modifier.weight(1f))
        Text(context.computedText(field) ?: "—", style = BillantaTheme.type.bodyStrong, color = c.textPrimary)
    }
}

@Composable
private fun InfoDisplay(field: FieldDef, modifier: Modifier) {
    val c = BillantaTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (field.label.isNotBlank()) Text(field.label, style = BillantaTheme.type.bodyStrong, color = c.textPrimary)
        field.text?.let { Text(it, style = BillantaTheme.type.body, color = c.textSecondary) }
    }
}

/** A paise string as the app shows money, for summaries. */
internal fun paiseText(paise: String?): String? = paise?.toLongOrNull()?.formatPaise()
