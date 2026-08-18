package com.ferbotz.billanta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.domain.model.InvoiceRecord
import com.ferbotz.billanta.domain.money.DiscountType
import com.ferbotz.billanta.model.formatPaise
import com.ferbotz.billanta.render.SectionEdits
import com.ferbotz.billanta.render.TemplateDoc
import com.ferbotz.billanta.render.TemplateParser
import com.ferbotz.billanta.render.TemplateSection
import com.ferbotz.billanta.state.AddItemSheet
import com.ferbotz.billanta.state.BillantaState
import com.ferbotz.billanta.state.BusinessProfileRoute
import com.ferbotz.billanta.state.CustomerPickerSheet
import com.ferbotz.billanta.ui.AppIcon
import com.ferbotz.billanta.ui.BillantaIcon
import com.ferbotz.billanta.ui.components.BillantaTextField
import com.ferbotz.billanta.ui.components.BottomActionBar
import com.ferbotz.billanta.ui.components.ChipRow
import com.ferbotz.billanta.ui.components.FieldLabel
import com.ferbotz.billanta.ui.components.ListRow
import com.ferbotz.billanta.ui.components.Overline
import com.ferbotz.billanta.ui.components.PickerField
import com.ferbotz.billanta.ui.components.PrimaryButton
import com.ferbotz.billanta.ui.components.SecondaryButton
import com.ferbotz.billanta.ui.components.StackTopBar
import com.ferbotz.billanta.ui.components.SurfaceCard
import com.ferbotz.billanta.theme.BillantaTheme

/**
 * What an invoice is made of, in the order the template says to fill it in.
 *
 * The list is the template's own `sections` — the backend decides which blocks exist, what to call
 * them and what each one edits, so a template that gains a section gains an editor for it without
 * an app release.
 *
 * A section appears here if it can be edited *or* hidden. Hiding lives on the same row as editing
 * because they are the same decision from the user's side — what goes on this invoice — and a
 * hidden section has to stay visible here, or there would be no way to bring it back.
 */
@Composable
fun EditInvoiceDataScreen(state: BillantaState, invoiceId: String) {
    val c = BillantaTheme.colors
    val record by remember(invoiceId) { state.invoiceFlow(invoiceId) }.collectAsState(initial = null)
    val doc = rememberTemplateDoc(state, record)

    Column(Modifier.fillMaxSize().background(c.background)) {
        StackTopBar("Edit invoice", onBack = { state.pop() })

        val invoice = record
        if (invoice == null) {
            CenteredNote("Invoice not found")
            return@Column
        }

        val sections = doc?.sections.orEmpty().filter { it.isEditable || it.hidable }

        if (doc == null) {
            CenteredNote("Loading template…")
            return@Column
        }
        if (sections.isEmpty()) {
            CenteredNote("This template doesn't describe anything to edit yet.")
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(18.dp, 4.dp, 18.dp, 120.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(sections, key = { it.id }) { section ->
                SectionRow(
                    section = section,
                    invoice = invoice,
                    onEdit = { state.openSection(invoiceId, section) },
                    onVisibilityChange = { show ->
                        val hidden = invoice.hiddenSections.toMutableSet()
                        if (show) hidden.remove(section.id) else hidden.add(section.id)
                        state.setInvoiceCustomisation(invoice.id, invoice.themeOverrides, hidden)
                    },
                )
            }
        }
    }
}

/** A one-line answer to "is there anything in this section yet?", or null when there isn't. */
private fun SectionEdits.summarise(invoice: InvoiceRecord): String? = when (this) {
    SectionEdits.Customer -> invoice.customerSnapshot?.name ?: invoice.customerName
    SectionEdits.InvoiceDetails -> invoice.invoiceNumber.takeIf { it.isNotBlank() }
    SectionEdits.Items -> invoice.items.size.takeIf { it > 0 }?.let { count ->
        "$count ${if (count == 1) "item" else "items"} · ${invoice.grandTotalPaise.formatPaise()}"
    }
    SectionEdits.Discount -> invoice.discount?.let {
        if (it.type == DiscountType.Percentage) "${it.value}% off" else invoice.discountTotalPaise.formatPaise() + " off"
    }
    SectionEdits.Notes -> invoice.notes?.takeIf { it.isNotBlank() }
    SectionEdits.Company -> invoice.companySnapshot?.name
    SectionEdits.None -> null
}

/**
 * One section: what it holds, whether it is on the invoice, and a way into its editor.
 *
 * A hidden section is not editable from here — it is not on the invoice, so filling it in would be
 * a confusing thing to offer. Switch it back on first.
 */
@Composable
private fun SectionRow(
    section: TemplateSection,
    invoice: InvoiceRecord,
    onEdit: () -> Unit,
    onVisibilityChange: (Boolean) -> Unit,
) {
    val c = BillantaTheme.colors
    val visible = section.id !in invoice.hiddenSections
    val summary = section.edits.summarise(invoice)
    val canEdit = section.isEditable && visible

    SurfaceCard(padding = 0) {
        Row(
            Modifier.fillMaxWidth()
                .let { if (canEdit) it.clickable(onClick = onEdit) else it }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledDot(filled = visible && summary != null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    section.label,
                    style = BillantaTheme.type.bodyStrong,
                    color = if (visible) c.textPrimary else c.textMuted,
                )
                Text(
                    when {
                        !visible -> "Hidden from this invoice"
                        summary != null -> summary
                        section.isEditable -> "Not added yet"
                        else -> "Shown on the invoice"
                    },
                    style = BillantaTheme.type.caption,
                    color = c.textSecondary,
                )
            }
            if (section.hidable) {
                Switch(
                    checked = visible,
                    onCheckedChange = onVisibilityChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = c.onPrimary,
                        checkedTrackColor = c.primary,
                        uncheckedTrackColor = c.surfaceAlt,
                        uncheckedBorderColor = c.border,
                        uncheckedThumbColor = c.textMuted,
                    ),
                )
            } else if (canEdit) {
                BillantaIcon(AppIcon.ChevronRight, c.textMuted, size = 18.dp)
            }
        }
    }
}

@Composable
private fun FilledDot(filled: Boolean) {
    val c = BillantaTheme.colors
    Box(
        Modifier.size(10.dp).clip(CircleShape)
            .background(if (filled) c.success else c.border),
    )
}

// ---- one section ---------------------------------------------------------------------------

/**
 * The editor for a single section. Which one to show comes from the template's `edits` value, not
 * from the section's id — so a template may name its blocks whatever it likes.
 */
@Composable
fun EditSectionScreen(state: BillantaState, invoiceId: String, edits: SectionEdits, label: String) {
    val c = BillantaTheme.colors
    val record by remember(invoiceId) { state.invoiceFlow(invoiceId) }.collectAsState(initial = null)

    Column(Modifier.fillMaxSize().background(c.background)) {
        StackTopBar(label, onBack = { state.pop() })
        val invoice = record ?: run {
            CenteredNote("Invoice not found")
            return@Column
        }
        when (edits) {
            SectionEdits.Customer -> CustomerSection(state, invoice)
            SectionEdits.InvoiceDetails -> DetailsSection(state, invoice)
            SectionEdits.Items -> ItemsSection(state, invoice)
            SectionEdits.Discount -> DiscountSection(state, invoice)
            SectionEdits.Notes -> NotesSection(state, invoice)
            SectionEdits.Company -> CompanySection(state, invoice)
            SectionEdits.None -> CenteredNote("Nothing to edit here.")
        }
    }
}

@Composable
private fun ColumnScopeBody(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun ColumnScope.CustomerSection(state: BillantaState, invoice: InvoiceRecord) {
    // The picker sheet writes to the shared draft slot; seed it so reopening shows the current one.
    LaunchedEffect(invoice.id) { state.setDraftCustomer(invoice.customerId ?: "") }
    val chosen = state.customerById(state.draftCustomerId?.takeIf { it.isNotEmpty() })
        ?: state.customerById(invoice.customerId)

    ColumnScopeBody {
        PickerField(
            label = "Customer",
            value = chosen?.name,
            placeholder = "Choose a customer",
            onClick = { state.openSheet(CustomerPickerSheet) },
        )
        chosen?.let { customer ->
            SurfaceCard {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Overline("Billed to")
                    Text(customer.name, style = BillantaTheme.type.bodyStrong, color = BillantaTheme.colors.textPrimary)
                    listOfNotNull(customer.email, customer.phone, customer.gstin).forEach {
                        Text(it, style = BillantaTheme.type.caption, color = BillantaTheme.colors.textSecondary)
                    }
                }
            }
        }
    }
    SaveBar(state, enabled = chosen != null) {
        state.setInvoiceCustomer(invoice.id, chosen!!.id) { state.pop() }
    }
}

@Composable
private fun ColumnScope.DetailsSection(state: BillantaState, invoice: InvoiceRecord) {
    var number by remember(invoice.id) { mutableStateOf(invoice.invoiceNumber) }
    var dueDays by remember(invoice.id) {
        mutableStateOf(
            invoice.dueDateMillis
                ?.let { ((it - invoice.invoiceDateMillis) / BillantaState.MILLIS_PER_DAY).toInt() }
                ?: 0,
        )
    }

    ColumnScopeBody {
        BillantaTextField(number, { number = it }, label = "Invoice number", placeholder = "INV-0001")
        FieldLabel("Payment due")
        ChipRow(
            items = DUE_OPTIONS,
            isSelected = { it.days == dueDays },
            label = { it.label },
            onSelect = { dueDays = it.days },
        )
    }
    SaveBar(state, enabled = number.isNotBlank()) {
        state.setInvoiceDetails(
            invoiceId = invoice.id,
            invoiceNumber = number,
            invoiceDateMillis = invoice.invoiceDateMillis,
            dueDateMillis = if (dueDays <= 0) null
            else invoice.invoiceDateMillis + dueDays * BillantaState.MILLIS_PER_DAY,
        ) { state.pop() }
    }
}

private data class DueOption(val label: String, val days: Int)

private val DUE_OPTIONS = listOf(
    DueOption("On receipt", 0),
    DueOption("7 days", 7),
    DueOption("14 days", 14),
    DueOption("30 days", 30),
)

@Composable
private fun ColumnScope.ItemsSection(state: BillantaState, invoice: InvoiceRecord) {
    // The add-item sheet appends to the shared scratchpad, so load this invoice's items into it.
    LaunchedEffect(invoice.id) { state.seedItemsFrom(invoice) }
    val c = BillantaTheme.colors

    Column(Modifier.fillMaxWidth().weight(1f)) {
        LazyColumn(
            contentPadding = PaddingValues(18.dp, 4.dp, 18.dp, 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.draftItems, key = { it.uiId }) { item ->
                SurfaceCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(item.description, style = BillantaTheme.type.bodyStrong, color = c.textPrimary)
                            Text(
                                "${item.quantity} × ${item.unitPricePaise.formatPaise()} · ${item.taxRatePercent}% GST",
                                style = BillantaTheme.type.caption,
                                color = c.textSecondary,
                            )
                        }
                        Box(
                            Modifier.clip(CircleShape).clickable { state.removeDraftItem(item.uiId) }.padding(8.dp),
                        ) { BillantaIcon(AppIcon.Trash, c.danger, size = 18.dp) }
                    }
                }
            }
            state.draftTotals?.let { totals ->
                item {
                    SurfaceCard {
                        Row {
                            Text("Total", style = BillantaTheme.type.bodyStrong, color = c.textPrimary)
                            Spacer(Modifier.weight(1f))
                            Text(
                                totals.grandTotal.formatPaise(),
                                style = BillantaTheme.type.bodyStrong,
                                color = c.textPrimary,
                            )
                        }
                    }
                }
            }
            item {
                SecondaryButton(
                    "Add item",
                    onClick = { state.openSheet(AddItemSheet) },
                    leadingIcon = AppIcon.Plus,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
    SaveBar(state, enabled = true) { state.setInvoiceItems(invoice.id) { state.pop() } }
}

@Composable
private fun ColumnScope.DiscountSection(state: BillantaState, invoice: InvoiceRecord) {
    LaunchedEffect(invoice.id) { state.seedDiscountFrom(invoice) }

    ColumnScopeBody {
        FieldLabel("Discount")
        ChipRow(
            items = DISCOUNT_KINDS,
            isSelected = { it.type == state.draftDiscountType },
            label = { it.label },
            onSelect = {
                state.draftDiscountType = it.type
                if (it.type == null) state.draftDiscountValue = ""
            },
        )
        if (state.draftDiscountType != null) {
            BillantaTextField(
                state.draftDiscountValue,
                { state.draftDiscountValue = it },
                label = if (state.draftDiscountType == DiscountType.Percentage) "Percent off" else "Amount off (₹)",
                placeholder = "0",
                keyboardType = KeyboardType.Decimal,
            )
        }
    }
    SaveBar(state, enabled = true) { state.setInvoiceDiscount(invoice.id) { state.pop() } }
}

private data class DiscountKind(val label: String, val type: DiscountType?)

private val DISCOUNT_KINDS = listOf(
    DiscountKind("None", null),
    DiscountKind("Percent", DiscountType.Percentage),
    DiscountKind("Amount", DiscountType.Flat),
)

@Composable
private fun ColumnScope.NotesSection(state: BillantaState, invoice: InvoiceRecord) {
    var notes by remember(invoice.id) { mutableStateOf(invoice.notes.orEmpty()) }
    ColumnScopeBody {
        BillantaTextField(
            notes,
            { notes = it },
            label = "Notes",
            placeholder = "Payment terms, thank-you note…",
            singleLine = false,
        )
    }
    SaveBar(state, enabled = true) { state.setInvoiceNotes(invoice.id, notes) { state.pop() } }
}

/**
 * The business details are the user's own, shared by every invoice, so this section points at the
 * profile rather than editing a copy of it — editing it here would silently diverge from the next
 * invoice's snapshot.
 */
@Composable
private fun ColumnScope.CompanySection(state: BillantaState, invoice: InvoiceRecord) {
    val c = BillantaTheme.colors
    ColumnScopeBody {
        SurfaceCard {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Overline("On this invoice")
                Text(
                    invoice.companySnapshot?.name ?: "No business details yet",
                    style = BillantaTheme.type.bodyStrong,
                    color = c.textPrimary,
                )
                invoice.companySnapshot?.let { snapshot ->
                    listOfNotNull(snapshot.gstin, snapshot.email, snapshot.phone).forEach {
                        Text(it, style = BillantaTheme.type.caption, color = c.textSecondary)
                    }
                }
            }
        }
        Text(
            "Your business details are shared by every invoice. Changing them here would only " +
                "affect this one, so edit them in your profile instead.",
            style = BillantaTheme.type.caption,
            color = c.textSecondary,
        )
        SecondaryButton(
            "Edit business profile",
            onClick = { state.push(BusinessProfileRoute) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ColumnScope.SaveBar(state: BillantaState, enabled: Boolean, onSave: () -> Unit) {
    val c = BillantaTheme.colors
    Spacer(Modifier.weight(1f))
    state.draftError?.let {
        Text(
            it,
            style = BillantaTheme.type.caption,
            color = c.danger,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
        )
    }
    BottomActionBar {
        PrimaryButton(
            if (state.savingSection) "Saving…" else "Save",
            onClick = onSave,
            enabled = enabled && !state.savingSection,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CenteredNote(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = BillantaTheme.type.body, color = BillantaTheme.colors.textMuted)
    }
}

/**
 * The invoice's template, compiled. Mirrors the preview's choice of the template's *current*
 * version rather than the one pinned at creation, so the two screens always agree on which
 * sections exist.
 */
@Composable
fun rememberTemplateDoc(state: BillantaState, record: InvoiceRecord?): TemplateDoc? {
    val templateId = record?.templateId ?: state.selectedTemplateId
    val version = templateId?.let { state.templateById(it)?.currentVersion }
    val doc by produceState<TemplateDoc?>(null, templateId, version) {
        value = null
        if (templateId == null) return@produceState
        value = when (val result = state.container.templateRepository.getCompiled(templateId, version)) {
            is AppResult.Success -> TemplateParser.parse(result.value.json)
            is AppResult.Failure -> null
        }
    }
    return doc
}
