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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.height
import coil3.compose.AsyncImage
import com.ferbotz.billanta.domain.model.CompanySnapshot
import com.ferbotz.billanta.state.BillantaState
import com.ferbotz.billanta.state.BusinessProfileRoute
import com.ferbotz.billanta.state.discountValueInput
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
import kotlinx.coroutines.delay

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
            Modifier.weight(1f),
            contentPadding = PaddingValues(18.dp, 4.dp, 18.dp, 24.dp),
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

        // Deliberately not reset on the way in: arriving here straight from a section editor, the
        // bar still says "Saved", which is the reassurance the missing Save button used to give.
        DoneBar(state) { state.pop() }
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

    SectionEdits.BankDetails -> invoice.companySnapshot?.let { party ->
        buildList {
            party.bankName?.let { add(it) }
            party.accountNumber?.let { add("A/C $it") }
            party.ifsc?.let { add("IFSC $it") }
            party.upiId?.let { add("UPI $it") }
        }
    }.orEmpty()

    SectionEdits.Signature -> invoice.companySnapshot.let { party ->
        buildList {
            party?.signatoryName?.let { add(it) }
            party?.signatoryDesignation?.let { add(it) }
            if (!party?.signature.isNullOrBlank()) add("Signature image added")
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

/** The server caps both signatory fields at 120 characters, so stop the user at the same place. */
private const val SIGNATORY_MAX = 120

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

    // A fresh editor starts with nothing saved and nothing complained about; the state is app-wide
    // so that the bar can report a write that outlives the screen, which means it needs clearing.
    LaunchedEffect(invoiceId, sectionId) { state.resetSectionSaveState() }

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
            SectionEdits.BankDetails -> BankDetailsSection(state, invoice)
            SectionEdits.Signature -> SignatureSection(state, invoice)
            SectionEdits.None -> CenteredNote("Nothing to edit here.")
        }
    }
}

/**
 * The editable area of a section: everything between the top bar and the Done bar.
 *
 * Takes the weight itself and scrolls, so a long form uses the whole screen. [DoneBar] must not
 * also claim a weight — two equal weights in one Column split it down the middle, which is how the
 * business-details form ended up occupying half the screen with blank space beneath it.
 */
@Composable
private fun ColumnScope.ColumnScopeBody(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

/**
 * Choosing who the invoice is for.
 *
 * The list is the screen — no picker button. Choosing a customer is a single, complete decision, so
 * the tap both writes it and steps back; nothing here needs confirming afterwards. Done is only the
 * way out for someone who opened the list and decided to keep the customer they already had.
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
    DoneBar(state) { state.pop() }
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

    // A blank number is refused by the write, so there is nothing to send until it has one.
    AutoSave(number, dateMillis, dueDays) {
        if (number.isNotBlank()) {
            state.setInvoiceDetails(
                invoiceId = invoice.id,
                invoiceNumber = number,
                invoiceDateMillis = dateMillis,
                dueDateMillis = if (dueDays <= 0) null
                else dateMillis + dueDays * BillantaState.MILLIS_PER_DAY,
            )
        }
    }

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

    DoneBar(
        state,
        blocker = if (number.isBlank()) "An invoice needs a number — this can't be saved without one." else null,
    ) { state.pop() }
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
 * catalogue, which is what the user has invoiced before. Every change writes straight through, so
 * backing out cannot lose a line and Done means only "this invoice has everything on it".
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
        val catalogue = state.features.productCatalogue
        val available =
            if (!catalogue) emptyList()
            else state.products.filterNot { ProductRecord.nameKeyOf(it.name) in onInvoice }
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
        } else if (invoice.items.isEmpty() && catalogue) {
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
    DoneBar(state) { state.pop() }
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
    // Seeded straight from the record rather than through app-level draft state: the seeding used to
    // land one composition late, which an auto-saving form reads as the user having changed something.
    var kind by remember(invoice.id) { mutableStateOf(invoice.discount?.type) }
    var amount by remember(invoice.id) { mutableStateOf(discountValueInput(invoice.discount)) }

    AutoSave(kind, amount) {
        state.setInvoiceDiscount(invoice.id, kind, amount, invoice.discountBeforeTax)
    }

    ColumnScopeBody {
        FieldLabel("Discount")
        ChipRow(
            items = DISCOUNT_KINDS,
            isSelected = { it.type == kind },
            label = { it.label },
            onSelect = {
                kind = it.type
                if (it.type == null) amount = ""
            },
        )
        if (kind != null) {
            BillantaTextField(
                amount,
                { amount = it },
                label = if (kind == DiscountType.Percentage) "Percent off" else "Amount off (₹)",
                placeholder = "0",
                keyboardType = KeyboardType.Decimal,
            )
        }
    }
    DoneBar(state) { state.pop() }
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
    AutoSave(notes) { state.setInvoiceNotes(invoice.id, notes) }
    ColumnScopeBody {
        BillantaTextField(
            notes,
            { notes = it },
            label = "Notes",
            placeholder = "Payment terms, thank-you note…",
            singleLine = false,
        )
    }
    DoneBar(state) { state.pop() }
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

    // Copies onto the stored profile rather than rebuilding it, so fields this form does not show
    // — logo, QR, the authorised signatory — are carried through untouched.
    fun commit() = state.setInvoiceCompany(invoice.id, alsoUpdateProfile) { current ->
        current.copy(
            name = name.trim(),
            gstin = gstin.trim().ifBlank { null },
            addressLine1 = line1.trim().ifBlank { null },
            addressLine2 = line2.trim().ifBlank { null },
            city = city.trim().ifBlank { null },
            state = stateName.trim().ifBlank { null },
            stateCode = stateCode.trim().ifBlank { null },
            pincode = pincode.trim().ifBlank { null },
            phone = phone.trim().ifBlank { null },
            email = email.trim().ifBlank { null },
            upiId = upi.trim().ifBlank { null },
            bankName = bank.trim().ifBlank { null },
            accountNumber = account.trim().ifBlank { null },
            ifsc = ifsc.trim().ifBlank { null },
        )
    }

    // The switch is a key as well as a field: flipping it is the user saying where these details
    // belong, so the draft follows it to the other destination straight away.
    AutoSave(
        name, gstin, phone, email, line1, line2, city, stateName, stateCode, pincode,
        upi, bank, account, ifsc, alsoUpdateProfile,
    ) { if (name.isNotBlank()) commit() }

    ColumnScopeBody {
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

    DoneBar(
        state,
        blocker = if (name.isBlank()) "A business name is required — nothing is saved without it." else null,
    ) { state.pop() }
}

/**
 * The four company fields a payment block prints (BE-011).
 *
 * A focused editor rather than the whole company form: changing an IFSC should not mean scrolling
 * past an address. It writes to the same company profile the full form does.
 */
@Composable
private fun ColumnScope.BankDetailsSection(state: BillantaState, invoice: InvoiceRecord) {
    val snapshot = invoice.companySnapshot
    val profile = state.company

    var bank by remember(invoice.id) { mutableStateOf(snapshot?.bankName ?: profile?.bankName ?: "") }
    var account by remember(invoice.id) { mutableStateOf(snapshot?.accountNumber ?: profile?.accountNumber ?: "") }
    var ifsc by remember(invoice.id) { mutableStateOf(snapshot?.ifsc ?: profile?.ifsc ?: "") }
    var upi by remember(invoice.id) { mutableStateOf(snapshot?.upiId ?: profile?.upiId ?: "") }
    var alsoUpdateProfile by remember(invoice.id) { mutableStateOf(true) }
    // The business profile requires a name, so without one the save was simply refused and the
    // typing was lost on back. Collect it here rather than sending the user off to another screen.
    val needsName = profile?.name.isNullOrBlank() && snapshot?.name.isNullOrBlank()
    var businessName by remember(invoice.id) {
        mutableStateOf(profile?.name ?: snapshot?.name ?: "")
    }

    fun commit() = state.setInvoiceCompany(invoice.id, alsoUpdateProfile) { current ->
        current.copy(
            name = current.name.ifBlank { businessName.trim() },
            bankName = bank.trim().ifBlank { null },
            accountNumber = account.trim().ifBlank { null },
            ifsc = ifsc.trim().ifBlank { null },
            upiId = upi.trim().ifBlank { null },
        )
    }

    AutoSave(bank, account, ifsc, upi, businessName, alsoUpdateProfile) {
        if (!needsName || businessName.isNotBlank()) commit()
    }

    ColumnScopeBody {
        Text(
            "Where this invoice asks to be paid.",
            style = BillantaTheme.type.caption,
            color = BillantaTheme.colors.textSecondary,
        )
        if (needsName) {
            BillantaTextField(
                businessName, { businessName = it },
                label = "Business name", placeholder = "Your business",
                modifier = Modifier.fillMaxWidth(),
            )
        }
        BillantaTextField(bank, { bank = it }, label = "Bank name", modifier = Modifier.fillMaxWidth())
        BillantaTextField(
            account, { account = it },
            label = "Account number", keyboardType = KeyboardType.Number,
            modifier = Modifier.fillMaxWidth(),
        )
        BillantaTextField(ifsc, { ifsc = it }, label = "IFSC", placeholder = "HDFC0001234", modifier = Modifier.fillMaxWidth())
        BillantaTextField(upi, { upi = it }, label = "UPI ID", placeholder = "you@okbank", modifier = Modifier.fillMaxWidth())
        SaveToProfileSwitch(alsoUpdateProfile) { alsoUpdateProfile = it }
    }

    DoneBar(
        state,
        blocker = if (needsName && businessName.isBlank()) {
            "A business name is required — nothing is saved without it."
        } else null,
    ) { state.pop() }
}

/** The signature image (BE-011): choose one from the device, or take the current one off. */
@Composable
private fun ColumnScope.SignatureSection(state: BillantaState, invoice: InvoiceRecord) {
    val c = BillantaTheme.colors
    val snapshot = invoice.companySnapshot
    val profile = state.company
    val signature = snapshot?.signature ?: profile?.signature
    var alsoUpdateProfile by remember(invoice.id) { mutableStateOf(true) }
    // The signatory is text on the same section, so it saves with the same switch.
    var signatoryName by remember(invoice.id) {
        mutableStateOf(snapshot?.signatoryName ?: profile?.signatoryName ?: "")
    }
    var signatoryDesignation by remember(invoice.id) {
        mutableStateOf(snapshot?.signatoryDesignation ?: profile?.signatoryDesignation ?: "")
    }
    val needsName = profile?.name.isNullOrBlank() && snapshot?.name.isNullOrBlank()
    var businessName by remember(invoice.id) { mutableStateOf(profile?.name ?: snapshot?.name ?: "") }

    // Picking an image no longer steps back: the point of the screen is to show what the invoice
    // will print, so the user should see the signature they just chose land on it.
    fun choose() = state.pickAndUploadCompanyImage(
        invoiceId = invoice.id,
        alsoUpdateProfile = alsoUpdateProfile,
        apply = { company, url -> company.copy(signature = url) },
    )

    AutoSave(signatoryName, signatoryDesignation, businessName, alsoUpdateProfile) {
        if (!needsName || businessName.isNotBlank()) {
            state.setInvoiceCompany(invoice.id, alsoUpdateProfile) { current ->
                current.copy(
                    name = current.name.ifBlank { businessName.trim() },
                    signatoryName = signatoryName.trim().ifBlank { null },
                    signatoryDesignation = signatoryDesignation.trim().ifBlank { null },
                )
            }
        }
    }

    ColumnScopeBody {
        if (signature.isNullOrBlank()) {
            Text(
                "Add the signature that appears above your name on the invoice.",
                style = BillantaTheme.type.body,
                color = c.textSecondary,
            )
        } else {
            Overline("On this invoice")
            SurfaceCard(Modifier.fillMaxWidth()) {
                AsyncImage(
                    model = signature,
                    contentDescription = "Signature",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                )
            }
        }

        if (needsName) {
            BillantaTextField(
                businessName, { businessName = it },
                label = "Business name", placeholder = "Your business",
                modifier = Modifier.fillMaxWidth(),
            )
        }
        BillantaTextField(
            signatoryName, { signatoryName = it.take(SIGNATORY_MAX) },
            label = "Authorised signatory", placeholder = "Vishal B",
            modifier = Modifier.fillMaxWidth(),
        )
        BillantaTextField(
            signatoryDesignation, { signatoryDesignation = it.take(SIGNATORY_MAX) },
            label = "Designation (optional)", placeholder = "Director",
            modifier = Modifier.fillMaxWidth(),
        )

        SaveToProfileSwitch(alsoUpdateProfile) { alsoUpdateProfile = it }

        when {
            // `features.mediaUpload` (BE-014). Removing a signature is not an upload, so that stays.
            !state.features.mediaUpload -> Text(
                "Adding a signature image isn't available right now.",
                style = BillantaTheme.type.caption,
                color = c.textMuted,
            )
            state.canPickImages -> SecondaryButton(
                when {
                    state.savingSection -> "Uploading…"
                    signature.isNullOrBlank() -> "Choose signature"
                    else -> "Replace signature"
                },
                onClick = { choose() },
                enabled = !state.savingSection,
                modifier = Modifier.fillMaxWidth(),
            )
            else -> Text(
                "Choosing an image isn't available in this build.",
                style = BillantaTheme.type.caption,
                color = c.textMuted,
            )
        }

        if (!signature.isNullOrBlank()) {
            SecondaryButton(
                "Remove signature",
                onClick = {
                    state.setInvoiceCompany(invoice.id, alsoUpdateProfile) { it.copy(signature = null) }
                },
                enabled = !state.savingSection,
                tint = c.danger,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // Uploads need the network; there is no offline queue for media, so a failure is reported in
    // the bar along with everything else this section writes.
    DoneBar(
        state,
        blocker = if (needsName && businessName.isBlank()) {
            "A business name is required — nothing is saved without it."
        } else null,
    ) { state.pop() }
}

/** Shared by every editor that writes company fields: does this change stop at this invoice? */
@Composable
private fun SaveToProfileSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = BillantaTheme.colors
    SurfaceCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Save to my business profile", style = BillantaTheme.type.bodyStrong, color = c.textPrimary)
                Text(
                    if (checked) "Used on your other invoices too, and on new ones"
                    else "Changes only this invoice",
                    style = BillantaTheme.type.caption,
                    color = c.textSecondary,
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onChange,
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

// ---- saving --------------------------------------------------------------------------------

/** How long after the user's last change a section editor writes itself. */
private const val AUTO_SAVE_DELAY_MILLIS = 700L

/**
 * Writes a section's draft a moment after the user stops changing it, and once more on the way out.
 *
 * [keys] are everything the write reads: a change to any of them restarts the timer, so a burst of
 * typing costs one write rather than one per keystroke. The first composition never writes — the
 * draft is seeded from the record it would be written to, so saving on open would mark an invoice
 * the user merely looked at as changed.
 *
 * The write on dispose is what makes Done, the back arrow and the system back mean the same thing:
 * whichever one the user reaches for, the last thing they typed is already on its way to the
 * database. It runs on the app's scope rather than this screen's, so the screen going away cannot
 * cancel it half-written.
 */
@Composable
private fun AutoSave(vararg keys: Any?, save: () -> Unit) {
    // Kept up to date so that both the timer and the write on the way out see the latest draft
    // rather than the one that happened to be in the composition they were started from.
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
 * The bottom of every section editor: what the saving is doing, and the way back out.
 *
 * There is no Save button — the section writes itself — so this one only ever means "I am finished
 * with this section". [blocker] is the exception: it says the draft cannot be written at all, which
 * is the one case where walking away would lose an edit, so Done waits until it is cleared. The
 * back arrow still works, and what is on the invoice is the last draft that *was* writable.
 */
@Composable
private fun ColumnScope.DoneBar(state: BillantaState, blocker: String? = null, onDone: () -> Unit) {
    val c = BillantaTheme.colors
    val problem = state.draftError ?: blocker
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
            PrimaryButton(
                "Done",
                onClick = onDone,
                enabled = blocker == null,
                leadingIcon = AppIcon.Check,
                modifier = Modifier.fillMaxWidth(),
            )
        }
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
