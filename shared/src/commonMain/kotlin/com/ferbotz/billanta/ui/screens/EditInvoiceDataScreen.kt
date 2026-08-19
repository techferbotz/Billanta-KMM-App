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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.Color
import com.ferbotz.billanta.model.initialsOf
import com.ferbotz.billanta.state.EditCustomerRoute
import com.ferbotz.billanta.ui.components.Avatar
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextAlign
import com.ferbotz.billanta.domain.model.ProductRecord
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDatePickerState
import com.ferbotz.billanta.core.InvoiceDateFormat
import com.ferbotz.billanta.ui.components.TextButtonLink
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.ferbotz.billanta.domain.model.CompanyProfile
import com.ferbotz.billanta.state.BillantaState
import com.ferbotz.billanta.state.BusinessProfileRoute
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
                    dateFormat = state.dateFormat,
                    onEdit = { state.openSection(invoiceId, section) },
                    onVisibilityChange = { show -> state.setSectionVisible(invoice, section.id, show) },
                )
            }
        }
    }
}

/** A one-line answer to "is there anything in this section yet?", or null when there isn't. */
/**
 * The lines this section will actually put on the invoice.
 *
 * Deliberately read from the *snapshots* rather than the live customer or company: those are what
 * the invoice froze and what the PDF will show, so a customer edited afterwards must not make this
 * screen disagree with the document.
 *
 * Empty means the section has nothing yet, which is what the dot and the "Not added yet" line read.
 */
internal fun SectionEdits.detail(
    invoice: InvoiceRecord,
    dateFormat: InvoiceDateFormat = InvoiceDateFormat.Default,
): List<String> = when (this) {
    SectionEdits.Customer -> invoice.customerSnapshot?.let { party ->
        buildList {
            add(party.name)
            party.gstin?.let { add("GSTIN $it") }
            listOfNotNull(party.phone, party.email).forEach { add(it) }
            addressLines(
                party.addressLine1, party.addressLine2, party.city,
                party.state, party.pincode,
            ).forEach { add(it) }
        }
    } ?: listOfNotNull(invoice.customerName)

    SectionEdits.Company -> invoice.companySnapshot?.let { party ->
        buildList {
            add(party.name)
            party.gstin?.let { add("GSTIN $it") }
            listOfNotNull(party.phone, party.email).forEach { add(it) }
            addressLines(
                party.addressLine1, party.addressLine2, party.city,
                party.state, party.pincode,
            ).forEach { add(it) }
            party.upiId?.let { add("UPI $it") }
            party.bankName?.let { bank ->
                add(listOfNotNull(bank, party.accountNumber, party.ifsc).joinToString(" · "))
            }
        }
    }.orEmpty()

    SectionEdits.InvoiceDetails -> buildList {
        invoice.invoiceNumber.takeIf { it.isNotBlank() }?.let { add(it) }
        add("Dated ${dateFormat.format(invoice.invoiceDateMillis)}")
        invoice.dueDateMillis?.let { add("Due ${dateFormat.format(it)}") }
    }

    SectionEdits.Items -> invoice.items.map { line ->
        "${line.description}  ·  ${line.quantity} × ${line.unitPricePaise.formatPaise()}" +
            "  =  ${line.lineTotalPaise.formatPaise()}"
    }

    SectionEdits.Discount -> buildList {
        val discount = invoice.discount
        if (discount != null) {
            add(
                if (discount.type == DiscountType.Percentage) "${discount.value}% discount"
                else "${invoice.discountTotalPaise.formatPaise()} discount",
            )
        }
        if (invoice.items.isNotEmpty()) {
            add("Subtotal ${invoice.subtotalPaise.formatPaise()}")
            if (invoice.discountTotalPaise > 0) add("Less ${invoice.discountTotalPaise.formatPaise()}")
            add("Tax ${invoice.taxTotalPaise.formatPaise()}")
            add("Total ${invoice.grandTotalPaise.formatPaise()}")
        }
    }

    SectionEdits.Notes -> listOfNotNull(invoice.notes?.takeIf { it.isNotBlank() })
    SectionEdits.None -> emptyList()
}

/** One postal address, folded onto as few lines as the filled-in fields allow. */
internal fun addressLines(
    line1: String?,
    line2: String?,
    city: String?,
    state: String?,
    pincode: String?,
): List<String> = buildList {
    line1?.takeIf { it.isNotBlank() }?.let { add(it) }
    line2?.takeIf { it.isNotBlank() }?.let { add(it) }
    listOfNotNull(city, state, pincode).filter { it.isNotBlank() }
        .takeIf { it.isNotEmpty() }?.let { add(it.joinToString(", ")) }
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
    dateFormat: InvoiceDateFormat,
    onEdit: () -> Unit,
    onVisibilityChange: (Boolean) -> Unit,
) {
    val c = BillantaTheme.colors
    val visible = section.id !in invoice.hiddenSections
    val detail = section.edits.detail(invoice, dateFormat)
    val canEdit = section.isEditable && visible

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
                Text(
                    section.label,
                    style = BillantaTheme.type.bodyStrong,
                    color = if (visible) c.textPrimary else c.textMuted,
                )
                if (!visible) {
                    Text("Hidden from this invoice", style = BillantaTheme.type.caption, color = c.textSecondary)
                } else if (detail.isEmpty()) {
                    Text(
                        if (section.isEditable) "Not added yet" else "Shown on the invoice",
                        style = BillantaTheme.type.caption,
                        color = c.textSecondary,
                    )
                } else {
                    // Long sections would otherwise push the rest of the list off screen; the
                    // section's own editor is where the whole thing is meant to be read.
                    detail.take(MAX_DETAIL_LINES).forEach { line ->
                        Text(
                            line,
                            style = BillantaTheme.type.body,
                            color = c.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (detail.size > MAX_DETAIL_LINES) {
                        Text(
                            "+${detail.size - MAX_DETAIL_LINES} more",
                            style = BillantaTheme.type.caption,
                            color = c.textMuted,
                        )
                    }
                }
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

/** Enough to recognise the section at a glance, not so much that the list stops being a list. */
private const val MAX_DETAIL_LINES = 6

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
fun EditSectionScreen(
    state: BillantaState,
    invoiceId: String,
    sectionId: String,
    edits: SectionEdits,
    label: String,
    hidable: Boolean,
) {
    val c = BillantaTheme.colors
    val record by remember(invoiceId) { state.invoiceFlow(invoiceId) }.collectAsState(initial = null)

    Column(Modifier.fillMaxSize().background(c.background)) {
        StackTopBar(label, onBack = { state.pop() })
        val invoice = record ?: run {
            CenteredNote("Invoice not found")
            return@Column
        }

        val visible = sectionId !in invoice.hiddenSections
        // The same switch as the list, where you are already looking at the section — deciding to
        // leave a block off is a thought you have while editing it, not only from a row above it.
        if (hidable) {
            SurfaceCard(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Show on invoice", style = BillantaTheme.type.bodyStrong, color = c.textPrimary)
                        Text(
                            if (visible) "This section is printed" else "This section is left off",
                            style = BillantaTheme.type.caption,
                            color = c.textSecondary,
                        )
                    }
                    Switch(
                        checked = visible,
                        onCheckedChange = { state.setSectionVisible(invoice, sectionId, it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = c.onPrimary,
                            checkedTrackColor = c.primary,
                            uncheckedTrackColor = c.surfaceAlt,
                            uncheckedBorderColor = c.border,
                            uncheckedThumbColor = c.textMuted,
                        ),
                    )
                }
            }
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

/**
 * Choosing who the invoice is for.
 *
 * The list is the screen — no picker button, no Save. Choosing a customer is a single, complete
 * decision, so it commits and steps back on the tap; a Save button would only ask the user to
 * confirm something they had already said.
 */
@Composable
private fun ColumnScope.CustomerSection(state: BillantaState, invoice: InvoiceRecord) {
    val c = BillantaTheme.colors
    LazyColumn(
        Modifier.fillMaxWidth().weight(1f),
        contentPadding = PaddingValues(18.dp, 4.dp, 18.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SecondaryButton(
                "Add new customer",
                onClick = { state.push(EditCustomerRoute(null, attachToInvoiceId = invoice.id)) },
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
            val selected = customer.id == invoice.customerId
            SurfaceCard(padding = 0) {
                Row(
                    Modifier.fillMaxWidth()
                        .border(
                            width = if (selected) 2.dp else 0.dp,
                            color = if (selected) c.primary else Color.Transparent,
                            shape = RoundedCornerShape(16.dp),
                        )
                        .clickable {
                            // Re-selecting the current customer is a no-op, but still a clear
                            // "yes, this one" — so it steps back rather than sitting there.
                            if (selected) state.pop()
                            else state.setInvoiceCustomer(invoice.id, customer.id) { state.pop() }
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Avatar(initialsOf(customer.name), size = 44)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(customer.name, style = BillantaTheme.type.bodyStrong, color = c.textPrimary)
                        listOfNotNull(customer.phone, customer.email, customer.city).firstOrNull()?.let {
                            Text(it, style = BillantaTheme.type.caption, color = c.textSecondary)
                        }
                    }
                    if (selected) BillantaIcon(AppIcon.Check, c.primary, size = 20.dp)
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ColumnScope.DetailsSection(state: BillantaState, invoice: InvoiceRecord) {
    var number by remember(invoice.id) { mutableStateOf(invoice.invoiceNumber) }
    var dateMillis by remember(invoice.id) { mutableStateOf(invoice.invoiceDateMillis) }
    // Held as a count of days rather than an absolute date, so moving the invoice date carries the
    // due date with it — "net 14" is a term, not a fixed day.
    var dueDays by remember(invoice.id) {
        mutableStateOf(
            invoice.dueDateMillis
                ?.let { ((it - invoice.invoiceDateMillis) / BillantaState.MILLIS_PER_DAY).toInt() }
                ?: 0,
        )
    }
    var pickingDate by remember { mutableStateOf(false) }

    ColumnScopeBody {
        BillantaTextField(number, { number = it }, label = "Invoice number", placeholder = "INV-0001")

        PickerField(
            label = "Invoice date",
            value = state.dateFormat.format(dateMillis),
            placeholder = "Pick a date",
            onClick = { pickingDate = true },
        )

        FieldLabel("Payment due")
        ChipRow(
            items = DUE_OPTIONS,
            isSelected = { it.days == dueDays },
            label = { it.label },
            onSelect = { dueDays = it.days },
        )

        FieldLabel("Date format")
        Text(
            "How dates are printed on every invoice.",
            style = BillantaTheme.type.caption,
            color = BillantaTheme.colors.textSecondary,
        )
        ChipRow(
            items = InvoiceDateFormat.entries,
            isSelected = { it == state.dateFormat },
            // The sample *is* the label — nobody recognises a format from its name.
            label = { it.format(dateMillis) },
            onSelect = { state.chooseDateFormat(it) },
        )
    }

    if (pickingDate) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = dateMillis)
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButtonLink("Set", onClick = {
                    picker.selectedDateMillis?.let { dateMillis = it }
                    pickingDate = false
                })
            },
            dismissButton = { TextButtonLink("Cancel", onClick = { pickingDate = false }) },
        ) {
            DatePicker(state = picker)
        }
    }

    SaveBar(state, enabled = number.isNotBlank()) {
        state.setInvoiceDetails(
            invoiceId = invoice.id,
            invoiceNumber = number,
            invoiceDateMillis = dateMillis,
            dueDateMillis = if (dueDays <= 0) null
            else dateMillis + dueDays * BillantaState.MILLIS_PER_DAY,
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

/**
 * The invoice's line items.
 *
 * Same shape as the customer screen: "add new" on top, then a list to pick from — here the product
 * catalogue, which is what the user has invoiced before. There is no Save: each change writes
 * straight through, so backing out cannot lose a line.
 *
 * A product that is already on the invoice drops out of the catalogue list and appears above it
 * with a quantity stepper. Tapping it again meant "two of these", not "a second identical line".
 */
@Composable
private fun ColumnScope.ItemsSection(state: BillantaState, invoice: InvoiceRecord) {
    val c = BillantaTheme.colors
    LazyColumn(
        Modifier.fillMaxWidth().weight(1f),
        contentPadding = PaddingValues(18.dp, 4.dp, 18.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SecondaryButton(
                "Add new item",
                onClick = { state.openSheet(AddItemSheet(invoice.id)) },
                leadingIcon = AppIcon.Plus,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (invoice.items.isNotEmpty()) {
            item { Overline("On this invoice") }
            itemsIndexed(invoice.items) { index, line ->
                SurfaceCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(line.description, style = BillantaTheme.type.bodyStrong, color = c.textPrimary)
                                Text(
                                    "${line.unitPricePaise.formatPaise()} · ${line.taxRatePercent}% GST",
                                    style = BillantaTheme.type.caption,
                                    color = c.textSecondary,
                                )
                            }
                            Text(
                                line.lineTotalPaise.formatPaise(),
                                style = BillantaTheme.type.bodyStrong,
                                color = c.textPrimary,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            QuantityStepper(
                                quantity = line.quantity,
                                onStep = { delta -> state.changeInvoiceItemQuantity(invoice.id, index, delta) },
                            )
                            Spacer(Modifier.weight(1f))
                            Box(
                                Modifier.clip(CircleShape)
                                    .clickable { state.removeInvoiceItem(invoice.id, index) }
                                    .padding(8.dp),
                            ) { BillantaIcon(AppIcon.Trash, c.danger, size = 18.dp) }
                        }
                    }
                }
            }
            item {
                SurfaceCard {
                    Row {
                        Text("Total", style = BillantaTheme.type.bodyStrong, color = c.textPrimary)
                        Spacer(Modifier.weight(1f))
                        Text(
                            invoice.grandTotalPaise.formatPaise(),
                            style = BillantaTheme.type.bodyStrong,
                            color = c.textPrimary,
                        )
                    }
                }
            }
        }

        // Matching on the name is what the catalogue itself keys on, so a line added from a
        // product and the product it came from stay recognisably the same thing.
        val onInvoice = invoice.items.map { ProductRecord.nameKeyOf(it.description) }.toSet()
        val available = state.products.filterNot { ProductRecord.nameKeyOf(it.name) in onInvoice }
        if (available.isNotEmpty()) {
            item { Overline("From your catalogue") }
            items(available, key = { it.id }) { product ->
                SurfaceCard(
                    onClick = {
                        state.addInvoiceItem(
                            invoiceId = invoice.id,
                            description = product.name,
                            hsnSac = product.hsnSac,
                            quantity = "1",
                            unitPricePaise = product.unitPricePaise,
                            taxRatePercent = product.taxRatePercent,
                        )
                    },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(product.name, style = BillantaTheme.type.bodyStrong, color = c.textPrimary)
                            Text(
                                "${product.unitPricePaise.formatPaise()} · ${product.taxRatePercent}% GST",
                                style = BillantaTheme.type.caption,
                                color = c.textSecondary,
                            )
                        }
                        BillantaIcon(AppIcon.Plus, c.primary, size = 18.dp)
                    }
                }
            }
        } else if (invoice.items.isEmpty()) {
            item {
                Text(
                    "Nothing in your catalogue yet. Add an item and it will be saved here for next time.",
                    style = BillantaTheme.type.body,
                    color = c.textSecondary,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        }
    }
}

/** Steps a line's quantity. Whole units only — a fractional quantity is typed, not tapped. */
@Composable
private fun QuantityStepper(quantity: String, onStep: (Int) -> Unit) {
    val c = BillantaTheme.colors
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(c.surfaceAlt),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
    Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = BillantaTheme.type.sectionTitle, color = BillantaTheme.colors.primary)
    }
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
    // Seeded from the invoice's own letterhead, falling back to the saved profile for an invoice
    // made before there was one. Editing here is editing what *this* invoice prints.
    val snapshot = invoice.companySnapshot
    val profile = state.company

    var name by remember(invoice.id) { mutableStateOf(snapshot?.name ?: profile?.name ?: "") }
    var gstin by remember(invoice.id) { mutableStateOf(snapshot?.gstin ?: profile?.gstin ?: "") }
    var phone by remember(invoice.id) { mutableStateOf(snapshot?.phone ?: profile?.phone ?: "") }
    var email by remember(invoice.id) { mutableStateOf(snapshot?.email ?: profile?.email ?: "") }
    var line1 by remember(invoice.id) { mutableStateOf(snapshot?.addressLine1 ?: profile?.addressLine1 ?: "") }
    var line2 by remember(invoice.id) { mutableStateOf(snapshot?.addressLine2 ?: profile?.addressLine2 ?: "") }
    var city by remember(invoice.id) { mutableStateOf(snapshot?.city ?: profile?.city ?: "") }
    var stateName by remember(invoice.id) { mutableStateOf(snapshot?.state ?: profile?.state ?: "") }
    var stateCode by remember(invoice.id) { mutableStateOf(snapshot?.stateCode ?: profile?.stateCode ?: "") }
    var pincode by remember(invoice.id) { mutableStateOf(snapshot?.pincode ?: profile?.pincode ?: "") }
    var upi by remember(invoice.id) { mutableStateOf(snapshot?.upiId ?: profile?.upiId ?: "") }
    var bank by remember(invoice.id) { mutableStateOf(snapshot?.bankName ?: profile?.bankName ?: "") }
    var account by remember(invoice.id) { mutableStateOf(snapshot?.accountNumber ?: profile?.accountNumber ?: "") }
    var ifsc by remember(invoice.id) { mutableStateOf(snapshot?.ifsc ?: profile?.ifsc ?: "") }
    var alsoUpdateProfile by remember(invoice.id) { mutableStateOf(true) }

    Column(
        Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BillantaTextField(name, { name = it }, label = "Business name", modifier = Modifier.fillMaxWidth())
        BillantaTextField(
            gstin,
            {
                gstin = it
                // The first two GSTIN digits are the state code — prefill while it is untouched.
                if (stateCode.isBlank() && it.length >= 2 && it.take(2).all(Char::isDigit)) {
                    stateCode = it.take(2)
                }
            },
            label = "GSTIN (optional)", placeholder = "27ABCDE1234F1Z5",
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BillantaTextField(phone, { phone = it }, label = "Phone", keyboardType = KeyboardType.Phone, modifier = Modifier.weight(1f))
            BillantaTextField(email, { email = it }, label = "Email", keyboardType = KeyboardType.Email, modifier = Modifier.weight(1f))
        }
        BillantaTextField(line1, { line1 = it }, label = "Address line 1", modifier = Modifier.fillMaxWidth())
        BillantaTextField(line2, { line2 = it }, label = "Address line 2 (optional)", modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BillantaTextField(city, { city = it }, label = "City", modifier = Modifier.weight(1f))
            BillantaTextField(pincode, { pincode = it }, label = "PIN code", keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BillantaTextField(stateName, { stateName = it }, label = "State", modifier = Modifier.weight(2f))
            BillantaTextField(
                stateCode, { stateCode = it.filter(Char::isDigit).take(2) },
                label = "Code", keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f),
            )
        }

        FieldLabel("Payment")
        BillantaTextField(upi, { upi = it }, label = "UPI ID", placeholder = "you@okbank", modifier = Modifier.fillMaxWidth())
        BillantaTextField(bank, { bank = it }, label = "Bank name", modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BillantaTextField(account, { account = it }, label = "Account number", modifier = Modifier.weight(1.4f))
            BillantaTextField(ifsc, { ifsc = it }, label = "IFSC", modifier = Modifier.weight(1f))
        }

        SurfaceCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Save to my business profile", style = BillantaTheme.type.bodyStrong, color = c.textPrimary)
                    Text(
                        if (alsoUpdateProfile) "Used on your other invoices too, and on new ones"
                        else "Changes only this invoice",
                        style = BillantaTheme.type.caption,
                        color = c.textSecondary,
                    )
                }
                Switch(
                    checked = alsoUpdateProfile,
                    onCheckedChange = { alsoUpdateProfile = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = c.onPrimary,
                        checkedTrackColor = c.primary,
                        uncheckedTrackColor = c.surfaceAlt,
                        uncheckedBorderColor = c.border,
                        uncheckedThumbColor = c.textMuted,
                    ),
                )
            }
        }
    }

    SaveBar(state, enabled = name.isNotBlank()) {
        state.setInvoiceCompany(
            invoiceId = invoice.id,
            company = CompanyProfile(
                name = name.trim(),
                gstin = gstin.trim().ifBlank { null },
                addressLine1 = line1.trim().ifBlank { null },
                addressLine2 = line2.trim().ifBlank { null },
                city = city.trim().ifBlank { null },
                state = stateName.trim().ifBlank { null },
                stateCode = stateCode.trim().ifBlank { null },
                pincode = pincode.trim().ifBlank { null },
                country = state.company?.country,
                phone = phone.trim().ifBlank { null },
                email = email.trim().ifBlank { null },
                logo = state.company?.logo,
                signature = state.company?.signature,
                upiId = upi.trim().ifBlank { null },
                qr = state.company?.qr,
                bankName = bank.trim().ifBlank { null },
                accountNumber = account.trim().ifBlank { null },
                ifsc = ifsc.trim().ifBlank { null },
            ),
            alsoUpdateProfile = alsoUpdateProfile,
        ) { state.pop() }
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
