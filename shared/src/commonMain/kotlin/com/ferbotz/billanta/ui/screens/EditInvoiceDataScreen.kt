package com.ferbotz.billanta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.fields.FieldDef
import com.ferbotz.billanta.fields.FieldOwner
import com.ferbotz.billanta.fields.FieldType
import com.ferbotz.billanta.model.ProductKind
import com.ferbotz.billanta.model.formatPaise
import com.ferbotz.billanta.model.initialsOf
import com.ferbotz.billanta.records.Customer
import com.ferbotz.billanta.records.Invoice
import com.ferbotz.billanta.records.InvoiceLine
import com.ferbotz.billanta.records.Product
import com.ferbotz.billanta.render.TemplateDoc
import com.ferbotz.billanta.render.TemplateSection
import com.ferbotz.billanta.state.BillantaState
import com.ferbotz.billanta.state.EditCustomerRoute
import com.ferbotz.billanta.state.EditLineRoute
import com.ferbotz.billanta.state.PickCustomerRoute
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.AppIcon
import com.ferbotz.billanta.ui.BillantaIcon
import com.ferbotz.billanta.ui.components.Avatar
import com.ferbotz.billanta.ui.components.BottomActionBar
import com.ferbotz.billanta.ui.components.IconButtonBox
import com.ferbotz.billanta.ui.components.Overline
import com.ferbotz.billanta.ui.components.PickerField
import com.ferbotz.billanta.ui.components.PrimaryButton
import com.ferbotz.billanta.ui.components.ProductImage
import com.ferbotz.billanta.ui.components.SecondaryButton
import com.ferbotz.billanta.ui.components.StackTopBar
import com.ferbotz.billanta.ui.components.SurfaceCard
import com.ferbotz.billanta.ui.fields.FieldInput
import com.ferbotz.billanta.ui.fields.billantaSwitchColors
import com.ferbotz.billanta.ui.fields.isShown
import com.ferbotz.billanta.ui.fields.summaryOf
import kotlinx.coroutines.delay

/**
 * What an invoice is made of, in the order the template says to fill it in.
 *
 * The list is the template's own `sections`, and each one's fields are the template's own too
 * (BE-021): a template that gains a field gains an input for it without an app release.
 *
 * A section appears here if it has something to edit *or* can be hidden. Hiding lives on the same
 * row because it is the same decision from the user's side — what goes on this invoice — and a
 * hidden section has to stay visible here, or there would be no way to bring it back.
 */
@Composable
fun EditInvoiceDataScreen(state: BillantaState, invoiceId: String) {
    val c = BillantaTheme.colors
    val record by remember(invoiceId) { state.invoiceFlow(invoiceId) }.collectAsState(initial = null)
    val doc = rememberTemplateDoc(state, record)
    // A Draft follows its business profile and customer; opening it is when that shows.
    LaunchedEffect(invoiceId) { state.refreshDraft(invoiceId) }

    Column(Modifier.fillMaxSize().background(c.background)) {
        StackTopBar("Edit invoice", onBack = { state.pop() })

        val invoice = record ?: run {
            CenteredNote("Invoice not found")
            return@Column
        }
        if (doc == null) {
            CenteredNote("Loading template…")
            return@Column
        }
        val sections = doc.sections.filter { it.hasInputs || it.hidable }
        if (sections.isEmpty()) {
            CenteredNote("This template doesn't describe anything to edit yet.")
            return@Column
        }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(18.dp, 4.dp, 18.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(sections, key = { it.id }) { section ->
                SectionRow(
                    section = section,
                    detail = sectionDetail(state, section, invoice),
                    visible = section.id !in invoice.hiddenSections || !section.hidable,
                    onEdit = { state.openSection(invoiceId, section.id) },
                    onVisibilityChange = { show -> state.setSectionVisible(invoice, section.id, show) },
                )
            }
        }

        // Deliberately not reset on the way in: arriving here straight from a section editor, the
        // bar still says "Saved", which is the reassurance a Save button used to give.
        DoneBar(state) { state.pop() }
    }
}

/** Whether a section has any field this build can draw an input for. */
private val TemplateSection.hasInputs: Boolean get() = fields.any { it.effectiveType != null }

/**
 * The lines this section puts on the invoice, read from the invoice's own values — what the PDF
 * will show — rather than from the live customer or profile. Empty means nothing is there yet.
 */
private fun sectionDetail(state: BillantaState, section: TemplateSection, invoice: Invoice): List<String> =
    section.fields.flatMap { field ->
        when (field.effectiveType) {
            FieldType.LineItems -> invoice.lines.mapIndexed { i, line -> lineSummary(line, invoice.computed.lines.getOrNull(i)?.lineTotal) }
            FieldType.Computed -> listOfNotNull(computedText(field, invoice)?.let { "${field.label} $it" })
            else -> listOfNotNull(summaryOf(field, invoice.values, state.fieldContext({ null })::optionsFor, state.dateFormat))
        }
    }

private fun lineSummary(line: InvoiceLine, total: Long?): String {
    val values = line.values
    val name = values.string(Product.DESCRIPTION)?.takeIf { it.isNotBlank() } ?: "Untitled item"
    val qty = values.string(QUANTITY) ?: "1"
    val rate = values.string(UNIT_PRICE)?.toLongOrNull()?.formatPaise()
    return listOfNotNull(name, rate?.let { "$qty × $it" }, total?.formatPaise()?.let { "= $it" }).joinToString("  ·  ")
}

/** A computed field's value, formatted as its `format` says (currency unless told otherwise). */
private fun computedText(field: FieldDef, invoice: Invoice, line: Int? = null): String? {
    val id = field.id ?: return null
    val raw = if (line != null) invoice.lines.getOrNull(line)?.computed?.toMap()?.get(id)
    else invoice.computed.totalsMap()[id]
    return when (field.format ?: "currency") {
        "currency" -> raw?.toLongOrNull()?.formatPaise()
        else -> raw
    }
}

/**
 * One section: what it holds, whether it is on the invoice, and a way into its editor. A hidden
 * section is not editable from here — switch it back on first.
 */
@Composable
private fun SectionRow(
    section: TemplateSection,
    detail: List<String>,
    visible: Boolean,
    onEdit: () -> Unit,
    onVisibilityChange: (Boolean) -> Unit,
) {
    val c = BillantaTheme.colors
    val canEdit = section.hasInputs && visible
    SurfaceCard(padding = 0) {
        Row(
            Modifier.fillMaxWidth()
                .let { if (canEdit) it.clickable(onClick = onEdit) else it }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledDot(filled = visible && detail.isNotEmpty())
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(section.label, style = BillantaTheme.type.bodyStrong, color = if (visible) c.textPrimary else c.textMuted)
                when {
                    !visible -> Text("Hidden from this invoice", style = BillantaTheme.type.caption, color = c.textSecondary)
                    detail.isEmpty() -> Text(
                        if (section.hasInputs) "Not added yet" else "Shown on the invoice",
                        style = BillantaTheme.type.caption,
                        color = c.textSecondary,
                    )
                    else -> {
                        detail.take(MAX_DETAIL_LINES).forEach { line ->
                            Text(line, style = BillantaTheme.type.body, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (detail.size > MAX_DETAIL_LINES) {
                            Text("+${detail.size - MAX_DETAIL_LINES} more", style = BillantaTheme.type.caption, color = c.textMuted)
                        }
                    }
                }
            }
            if (section.hidable) {
                Switch(checked = visible, onCheckedChange = onVisibilityChange, colors = billantaSwitchColors())
            } else if (canEdit) {
                BillantaIcon(AppIcon.ChevronRight, c.textMuted, size = 18.dp)
            }
        }
    }
}

/** Enough to recognise the section at a glance, not so much that the list stops being a list. */
private const val MAX_DETAIL_LINES = 6

@Composable
private fun FilledDot(filled: Boolean) {
    val c = BillantaTheme.colors
    Box(Modifier.size(10.dp).clip(CircleShape).background(if (filled) c.success else c.border))
}

// ---- one section ---------------------------------------------------------------------------

/**
 * One section's editor: an input for each field the template lists in it, in its order, drawn
 * for the field's type. The customer picker and the lines are editors of their own within it.
 *
 * Typing writes itself a moment after it stops ([AutoSave]); each field then goes where its owner
 * says — a company field to the business profile too, a customer field to the customer too.
 */
@Composable
fun EditSectionScreen(state: BillantaState, invoiceId: String, sectionId: String) {
    val c = BillantaTheme.colors
    val record by remember(invoiceId) { state.invoiceFlow(invoiceId) }.collectAsState(initial = null)
    val doc = rememberTemplateDoc(state, record)
    LaunchedEffect(invoiceId, sectionId) { state.resetSectionSaveState() }

    val section = doc?.sections?.firstOrNull { it.id == sectionId }
    Column(Modifier.fillMaxSize().background(c.background)) {
        StackTopBar(section?.label ?: "Edit", onBack = { state.pop() })
        val invoice = record ?: run {
            CenteredNote("Invoice not found")
            return@Column
        }
        if (section == null) {
            CenteredNote(if (doc == null) "Loading template…" else "This section is no longer on the template.")
            return@Column
        }
        SectionEditorBody(state, invoice, section)
    }
}

@Composable
private fun ColumnScope.SectionEditorBody(state: BillantaState, invoice: Invoice, section: TemplateSection) {
    val c = BillantaTheme.colors
    // The values this editor started from, and what it has made of them since. Only fields that
    // differ from the start are written, so a change pulled from another device meanwhile, in a
    // field the user never touched here, is never overwritten with the stale copy.
    var baseline by remember(invoice.id, section.id) { mutableStateOf(invoice.values) }
    var draft by remember(invoice.id, section.id) { mutableStateOf(invoice.values) }
    val editable = section.fields.filter { it.isStored && it.effectiveType != FieldType.Customer && it.effectiveType != FieldType.LineItems }

    AutoSave(draft) {
        val changes = editable.mapNotNull { field ->
            val id = field.id!!
            (field to draft[id]).takeIf { draft[id] != baseline[id] }
        }
        if (changes.isNotEmpty()) {
            state.saveInvoiceFields(invoice.id, changes)
            baseline = draft
        }
    }

    val visible = section.id !in invoice.hiddenSections || !section.hidable
    val context = state.fieldContext(
        valueOf = { draft[it] },
        computedText = { field -> computedText(field, invoice) },
    )

    Column(
        Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (section.hidable) {
            // The same switch as the list, where the user is already looking at the section.
            SurfaceCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Show on invoice", style = BillantaTheme.type.bodyStrong, color = c.textPrimary)
                        Text(
                            if (visible) "This section is printed" else "This section is left off",
                            style = BillantaTheme.type.caption,
                            color = c.textSecondary,
                        )
                    }
                    Switch(checked = visible, onCheckedChange = { state.setSectionVisible(invoice, section.id, it) }, colors = billantaSwitchColors())
                }
            }
        }
        section.fields.forEach { field ->
            if (!field.isShown(draft)) return@forEach
            when (field.effectiveType) {
                FieldType.Customer -> CustomerPickerRow(state, invoice, field)
                FieldType.LineItems -> LinesEditor(state, invoice, field)
                else -> FieldInput(
                    field = field,
                    value = field.id?.let { draft[it] },
                    onChange = { value ->
                        field.id?.let {
                            // A GSTIN's first two digits are its state, which decides CGST + SGST
                            // against IGST, so an empty state is filled in from it as it is typed.
                            draft = draft.with(it, value)
                                .withStateFromGstin(CUSTOMER_GSTIN, CUSTOMER_STATE)
                                .withStateFromGstin(COMPANY_GSTIN, COMPANY_STATE)
                        }
                    },
                    context = context,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (section.fields.any { it.owner == FieldOwner.Company }) {
            Text(
                "Business details you change here are saved to your business profile too.",
                style = BillantaTheme.type.caption,
                color = c.textMuted,
            )
        }
        Spacer(Modifier.size(8.dp))
    }
    DoneBar(state) { state.pop() }
}

/** Who the invoice is for: the picked customer, and a way to pick another or add one. */
@Composable
private fun CustomerPickerRow(state: BillantaState, invoice: Invoice, field: FieldDef) {
    val name = invoice.customerName?.takeIf { it.isNotBlank() }
    PickerField(
        label = field.label,
        value = name,
        placeholder = "Choose a customer",
        onClick = { state.push(PickCustomerRoute(invoice.id)) },
        leadingSlot = name?.let { { Avatar(initialsOf(it), size = 32) } },
    )
}

/**
 * The lines: each one with its amount, a quantity stepper and a way to remove or move it; then
 * "Add item" for a new one, and the catalogue to copy one from. Every change writes straight
 * through, so backing out can never lose a line.
 */
@Composable
private fun LinesEditor(state: BillantaState, invoice: Invoice, field: FieldDef) {
    val c = BillantaTheme.colors
    val columns = field.columns
    val quantity = columns.firstOrNull { it.id == QUANTITY && it.owner == FieldOwner.Line }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Overline(field.label)
        invoice.lines.forEachIndexed { index, line ->
            SurfaceCard(onClick = { state.push(EditLineRoute(invoice.id, line.id)) }) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                line.values.string(Product.DESCRIPTION)?.takeIf { it.isNotBlank() } ?: "Untitled item",
                                style = BillantaTheme.type.bodyStrong,
                                color = c.textPrimary,
                            )
                            Text(
                                listOfNotNull(
                                    line.values.string(UNIT_PRICE)?.toLongOrNull()?.formatPaise(),
                                    line.values.string(TAX_RATE)?.let { "$it% GST" },
                                ).joinToString(" · ").ifEmpty { "Tap to fill in" },
                                style = BillantaTheme.type.caption,
                                color = c.textSecondary,
                            )
                        }
                        Text(
                            (line.computed?.lineTotal ?: invoice.computed.lines.getOrNull(index)?.lineTotal ?: 0L).formatPaise(),
                            style = BillantaTheme.type.bodyStrong,
                            color = c.textPrimary,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (quantity != null) {
                            QuantityStepper(
                                quantity = line.values.string(QUANTITY) ?: "0",
                                onStep = { delta -> state.stepLineQuantity(invoice, line.id, quantity, delta) },
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (invoice.lines.size > 1) {
                            Box(Modifier.rotate(180f)) {
                                IconButtonBox(AppIcon.ChevronDown, if (index > 0) c.textSecondary else c.border, onClick = {
                                    if (index > 0) state.moveLine(invoice.id, line.id, index - 1)
                                }, size = 20.dp)
                            }
                            IconButtonBox(AppIcon.ChevronDown, if (index < invoice.lines.lastIndex) c.textSecondary else c.border, onClick = {
                                if (index < invoice.lines.lastIndex) state.moveLine(invoice.id, line.id, index + 1)
                            }, size = 20.dp)
                        }
                        IconButtonBox(AppIcon.Trash, c.danger, onClick = { state.removeLine(invoice.id, line.id) }, size = 20.dp)
                    }
                }
            }
        }
        SecondaryButton(
            "Add item",
            onClick = { state.addBlankLine(invoice.id, columns) },
            leadingIcon = AppIcon.Plus,
            modifier = Modifier.fillMaxWidth(),
        )
        if (invoice.lines.isNotEmpty()) {
            SurfaceCard {
                Row {
                    Text("Total", style = BillantaTheme.type.bodyStrong, color = c.textPrimary)
                    Spacer(Modifier.weight(1f))
                    Text(invoice.computed.grandTotal.formatPaise(), style = BillantaTheme.type.bodyStrong, color = c.textPrimary)
                }
            }
        }

        // The catalogue, minus what is already on the invoice: tapping a product again would mean
        // "two of these", which is what the stepper is for.
        val onInvoice = invoice.lines.mapNotNull { it.values.string(Product.DESCRIPTION) }.map(Product::nameKeyOf).toSet()
        val available = if (!state.features.productCatalogue) emptyList()
        else state.products.filterNot { Product.nameKeyOf(it.description) in onInvoice }
        if (available.isNotEmpty()) {
            Overline("From your catalogue")
            available.forEach { product -> CatalogueChoice(product, onClick = { state.addProductLine(invoice.id, columns, product) }) }
        }
    }
}

@Composable
private fun CatalogueChoice(product: Product, onClick: () -> Unit) {
    val c = BillantaTheme.colors
    SurfaceCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProductImage(
                url = product.image?.previewUrl,
                kind = ProductKind.of(product.values.string(HSN_SAC)),
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)),
                iconSize = 20.dp,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(product.description.ifBlank { "Untitled product" }, style = BillantaTheme.type.bodyStrong, color = c.textPrimary)
                Text(
                    listOfNotNull(
                        product.values.string(UNIT_PRICE)?.toLongOrNull()?.formatPaise(),
                        product.values.string(TAX_RATE)?.let { "$it% GST" },
                    ).joinToString(" · "),
                    style = BillantaTheme.type.caption,
                    color = c.textSecondary,
                )
            }
            BillantaIcon(AppIcon.Plus, c.primary, size = 18.dp)
        }
    }
}

/** Steps a line's quantity. Whole units only — a fractional quantity is typed, not tapped. */
@Composable
private fun QuantityStepper(quantity: String, onStep: (Int) -> Unit) {
    val c = BillantaTheme.colors
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(c.surfaceAlt), verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", onClick = { onStep(-1) })
        Text(
            quantity,
            style = BillantaTheme.type.bodyStrong,
            color = c.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 40.dp).padding(horizontal = 4.dp),
        )
        StepButton("+", onClick = { onStep(1) })
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, style = BillantaTheme.type.sectionTitle, color = BillantaTheme.colors.primary)
    }
}

// ---- one line ------------------------------------------------------------------------------

/**
 * One line, edited through the template's line columns. Computed columns (the amount) are shown,
 * not edited. A line never writes back to a product (§4.3); when the editor closes, a line with a
 * new description joins the catalogue instead.
 */
@Composable
fun EditLineScreen(state: BillantaState, invoiceId: String, lineId: String) {
    val c = BillantaTheme.colors
    val record by remember(invoiceId) { state.invoiceFlow(invoiceId) }.collectAsState(initial = null)
    val doc = rememberTemplateDoc(state, record)
    LaunchedEffect(invoiceId, lineId) { state.resetSectionSaveState() }
    // Declared before the editor's AutoSave so it is disposed after it: the last edit is queued
    // first, and the catalogue reads the line once that write is in.
    DisposableEffect(invoiceId, lineId) { onDispose { state.lineEditorClosed(invoiceId, lineId) } }

    Column(Modifier.fillMaxSize().background(c.background)) {
        val invoice = record
        val index = invoice?.lines?.indexOfFirst { it.id == lineId } ?: -1
        StackTopBar("Item", onBack = { state.pop() }, actions = {
            if (index >= 0) IconButtonBox(AppIcon.Trash, c.danger, onClick = {
                state.removeLine(invoiceId, lineId)
                state.pop()
            })
        })
        if (invoice == null || index < 0) {
            CenteredNote("This item is no longer on the invoice.")
            return@Column
        }
        val columns = doc?.sections.orEmpty().flatMap { it.fields }
            .firstOrNull { it.effectiveType == FieldType.LineItems }?.columns
        if (columns == null) {
            CenteredNote(if (doc == null) "Loading template…" else "This template has no line items.")
            return@Column
        }
        LineEditorBody(state, invoice, index, columns)
    }
}

@Composable
private fun ColumnScope.LineEditorBody(state: BillantaState, invoice: Invoice, index: Int, columns: List<FieldDef>) {
    val line = invoice.lines[index]
    var baseline by remember(line.id) { mutableStateOf(line.values) }
    var draft by remember(line.id) { mutableStateOf(line.values) }
    AutoSave(draft) {
        val changes = columns.filter { it.isStored && it.owner == FieldOwner.Line }.mapNotNull { column ->
            val id = column.id!!
            (column to draft[id]).takeIf { draft[id] != baseline[id] }
        }
        if (changes.isNotEmpty()) {
            state.saveLineFields(invoice.id, line.id, changes)
            baseline = draft
        }
    }
    val context = state.fieldContext(
        valueOf = { draft[it] ?: invoice.values[it] },
        computedText = { field -> computedText(field, invoice, line = index) },
    )
    Column(
        Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        columns.forEach { column ->
            if (!column.isShown(draft)) return@forEach
            FieldInput(
                field = column,
                value = column.id?.let { draft[it] },
                onChange = { value -> column.id?.let { draft = draft.with(it, value) } },
                context = context,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    DoneBar(state) { state.pop() }
}

// ---- picking the customer ------------------------------------------------------------------

/**
 * Choosing who the invoice is for. The list is the screen: choosing is a single, complete decision,
 * so a tap both writes it and steps back. "New customer" opens the customer form, and the customer
 * saved there is billed straight away.
 */
@Composable
fun PickCustomerScreen(state: BillantaState, invoiceId: String) {
    val c = BillantaTheme.colors
    val record by remember(invoiceId) { state.invoiceFlow(invoiceId) }.collectAsState(initial = null)
    Column(Modifier.fillMaxSize().background(c.background)) {
        StackTopBar("Bill to", onBack = { state.pop() })
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(18.dp, 4.dp, 18.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                SecondaryButton(
                    "New customer",
                    onClick = { state.push(EditCustomerRoute(null, attachToInvoiceId = invoiceId)) },
                    leadingIcon = AppIcon.Plus,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.customers.isEmpty()) {
                item {
                    Text(
                        "No customers yet. Add one and it will be reusable on every invoice.",
                        style = BillantaTheme.type.body,
                        color = c.textSecondary,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }
            items(state.customers, key = { it.id }) { customer ->
                CustomerChoice(customer, selected = customer.id == record?.customerId) {
                    if (customer.id == record?.customerId) state.pop()
                    else state.pickCustomer(invoiceId, customer.id) { state.pop() }
                }
            }
        }
    }
}

@Composable
private fun CustomerChoice(customer: Customer, selected: Boolean, onClick: () -> Unit) {
    val c = BillantaTheme.colors
    SurfaceCard(padding = 0) {
        Row(
            Modifier.fillMaxWidth()
                .border(if (selected) 2.dp else 0.dp, if (selected) c.primary else Color.Transparent, RoundedCornerShape(16.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(initialsOf(customer.name.ifBlank { "?" }), size = 44)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(customer.name.ifBlank { "Unnamed customer" }, style = BillantaTheme.type.bodyStrong, color = c.textPrimary)
                listOfNotNull(
                    customer.values.string(Customer.PHONE),
                    customer.values.string("customer_email"),
                    customer.values.string("customer_city"),
                ).firstOrNull()?.let { Text(it, style = BillantaTheme.type.caption, color = c.textSecondary) }
            }
            if (selected) BillantaIcon(AppIcon.Check, c.primary, size = 20.dp)
        }
    }
}

// ---- saving --------------------------------------------------------------------------------

/** How long after the user's last change an editor writes itself. */
private const val AUTO_SAVE_DELAY_MILLIS = 700L

/**
 * Writes an editor's draft a moment after the user stops changing it, and once more on the way
 * out. [keys] are everything the write reads: a change to any of them restarts the timer, so a
 * burst of typing costs one write. The first composition never writes — the draft starts as the
 * record — so merely looking at an invoice never marks it changed. The write on dispose is what
 * makes Done, the back arrow and system back mean the same thing.
 */
@Composable
internal fun AutoSave(vararg keys: Any?, save: () -> Unit) {
    val latest by rememberUpdatedState(save)
    val pending = remember { PendingSave() }
    LaunchedEffect(*keys) {
        if (!pending.seeded) {
            pending.seeded = true
            return@LaunchedEffect
        }
        pending.dirty = true
        delay(AUTO_SAVE_DELAY_MILLIS)
        pending.flush(latest)
    }
    DisposableEffect(Unit) { onDispose { pending.flush(latest) } }
}

/** Whether an [AutoSave] still owes a write — the bit that has to outlive each recomposition. */
private class PendingSave {
    var seeded = false
    var dirty = false

    fun flush(save: () -> Unit) {
        if (!dirty) return
        dirty = false
        save()
    }
}

/**
 * The bottom of every editor: what the saving is doing, and the way back out. There is no Save
 * button — the editor writes itself — so this only ever means "I am finished here".
 */
@Composable
private fun ColumnScope.DoneBar(state: BillantaState, onDone: () -> Unit) {
    val c = BillantaTheme.colors
    val problem = state.draftError
    BottomActionBar {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                problem ?: when (state.sectionSaveState) {
                    BillantaState.SaveState.Saving -> "Saving…"
                    BillantaState.SaveState.Saved -> "Saved"
                    BillantaState.SaveState.Clean -> "Changes are saved as you make them"
                },
                style = BillantaTheme.type.caption,
                color = if (problem != null) c.danger else c.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryButton("Done", onClick = onDone, leadingIcon = AppIcon.Check, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun CenteredNote(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = BillantaTheme.type.body, color = BillantaTheme.colors.textMuted, textAlign = TextAlign.Center)
    }
}

/**
 * The invoice's compiled template: a Draft follows the template's current version, an issued
 * invoice keeps the one it was made on (see [BillantaState.templateDocFor]).
 */
@Composable
fun rememberTemplateDoc(state: BillantaState, invoice: Invoice?): TemplateDoc? {
    val templateId = invoice?.templateId?.takeIf { it.isNotEmpty() } ?: state.selectedTemplateId
    val current = templateId?.let { state.templateById(it)?.currentVersion }
    val doc by produceState<TemplateDoc?>(null, invoice?.id, templateId, current, invoice?.templateVersion, invoice?.isDraft) {
        value = (state.templateDocFor(invoice) as? AppResult.Success)?.value
    }
    return doc
}

private const val CUSTOMER_GSTIN = "customer_gstin"
private const val CUSTOMER_STATE = "customer_state"
private const val COMPANY_GSTIN = "company_gstin"
private const val COMPANY_STATE = "company_state"
private const val QUANTITY = "quantity"
private const val UNIT_PRICE = "unit_price"
private const val TAX_RATE = "tax_rate"
private const val HSN_SAC = "hsn_sac"
