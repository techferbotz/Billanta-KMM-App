package com.ferbotz.billanta.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.ferbotz.billanta.model.BusinessProfile
import com.ferbotz.billanta.model.Customer
import com.ferbotz.billanta.model.Invoice
import com.ferbotz.billanta.model.InvoiceFilter
import com.ferbotz.billanta.model.InvoiceStatus
import com.ferbotz.billanta.model.InvoiceTemplate
import com.ferbotz.billanta.model.LineItem
import com.ferbotz.billanta.model.Paise
import com.ferbotz.billanta.model.SampleData
import com.ferbotz.billanta.ui.components.BottomTab

/** A screen pushed on top of the current tab root. */
sealed interface Route
data object CreateInvoiceRoute : Route
data class PreviewRoute(val invoiceId: String) : Route
data object BusinessProfileRoute : Route
data object SettingsRoute : Route
data object SignInRoute : Route
data class EditCustomerRoute(val customerId: String?) : Route

/** A modal bottom sheet layered above everything. */
sealed interface SheetRoute
data object AddItemSheet : SheetRoute
data object CustomerPickerSheet : SheetRoute
data class PremiumSheet(val templateId: String) : SheetRoute

/**
 * Single source of truth for the prototype. Plain Compose state (no platform ViewModel) so it drops
 * straight into `remember { BillantaState() }` on Android and iOS alike.
 */
class BillantaState {

    // ---- navigation ----------------------------------------------------------------------------
    var tab by mutableStateOf(BottomTab.INVOICES)
        private set
    val stack: SnapshotStateList<Route> = mutableStateListOf()
    var sheet by mutableStateOf<SheetRoute?>(null)
        private set

    val currentRoute: Route? get() = stack.lastOrNull()

    fun selectTab(t: BottomTab) {
        if (t == tab) stack.clear() else { tab = t; stack.clear() }
    }

    fun push(route: Route) { stack.add(route) }
    fun replaceTop(route: Route) { if (stack.isNotEmpty()) stack[stack.lastIndex] = route else stack.add(route) }
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    fun popToRoot() { stack.clear() }

    fun openCreate() = push(CreateInvoiceRoute)
    fun openPreview(invoiceId: String) = push(PreviewRoute(invoiceId))
    fun openSignIn() = push(SignInRoute)

    fun openSheet(s: SheetRoute) { sheet = s }
    fun closeSheet() { sheet = null }

    /** Global back handling. Returns true if it consumed the event. */
    fun back(): Boolean = when {
        sheet != null -> { sheet = null; true }
        stack.isNotEmpty() -> { pop(); true }
        else -> false
    }

    // ---- preferences ---------------------------------------------------------------------------
    var isDark by mutableStateOf(false)
    var isOffline by mutableStateOf(true)
    var signedIn by mutableStateOf(false)

    // ---- invoices list state -------------------------------------------------------------------
    var filter by mutableStateOf(InvoiceFilter.ALL)
    var query by mutableStateOf("")
    var loading by mutableStateOf(true) // resolves after first frame to show the loading state

    val invoices: SnapshotStateList<Invoice> = mutableStateListOf<Invoice>().apply { addAll(SampleData.invoices) }
    val customers: SnapshotStateList<Customer> = mutableStateListOf<Customer>().apply { addAll(SampleData.customers) }
    val templates: List<InvoiceTemplate> = SampleData.templates
    var business by mutableStateOf(SampleData.business)

    var selectedTemplateId by mutableStateOf("modern")

    fun invoiceById(id: String): Invoice? = invoices.firstOrNull { it.id == id } ?: if (id == draft.id) draft else null
    fun customerById(id: String?): Customer? = id?.let { cid -> customers.firstOrNull { it.id == cid } }
    fun templateById(id: String): InvoiceTemplate = templates.first { it.id == id }

    val filteredInvoices: List<Invoice>
        get() = invoices.filter { inv ->
            (filter == InvoiceFilter.ALL || inv.status.name == filter.name) &&
                (query.isBlank() ||
                    inv.customer.name.contains(query, ignoreCase = true) ||
                    inv.number.contains(query, ignoreCase = true))
        }

    val monthTotal: Paise get() = invoices.fold(Paise(0)) { acc, i -> Paise(acc.value + i.total.value) }
    val unpaidTotal: Paise
        get() = invoices.filter { it.status == InvoiceStatus.PENDING }
            .fold(Paise(0)) { acc, i -> Paise(acc.value + i.total.value) }
    val pendingCount: Int get() = invoices.count { it.status == InvoiceStatus.PENDING }

    // ---- create-invoice draft ------------------------------------------------------------------
    val draftItems: SnapshotStateList<LineItem> =
        mutableStateListOf<LineItem>().apply { addAll(SampleData.draftInvoice.items) }
    var draftCustomerId by mutableStateOf<String?>(SampleData.draftInvoice.customer.id)
    var draftNotes by mutableStateOf(SampleData.draftInvoice.notes ?: "")

    val draft: Invoice
        get() = SampleData.draftInvoice.copy(
            customer = customerById(draftCustomerId) ?: SampleData.draftInvoice.customer,
            items = draftItems.toList(),
            notes = draftNotes,
        )

    fun addDraftItem(item: LineItem) { draftItems.add(item) }
    fun removeDraftItem(id: String) { draftItems.removeAll { it.id == id } }
    fun setDraftCustomer(id: String) { draftCustomerId = id }

    private var itemSeq = 100
    fun nextItemId(): String = "u${itemSeq++}"

    private var custSeq = 100
    fun nextCustomerId(): String = "cu${custSeq++}"
    fun upsertCustomer(cust: Customer) {
        val idx = customers.indexOfFirst { it.id == cust.id }
        if (idx >= 0) customers[idx] = cust else customers.add(cust)
    }
    fun deleteCustomer(id: String) { customers.removeAll { it.id == id } }
    fun invoiceCountFor(customerId: String): Int = invoices.count { it.customer.id == customerId }
    fun billedTotalFor(customerId: String): Paise =
        invoices.filter { it.customer.id == customerId }.fold(Paise(0)) { a, i -> Paise(a.value + i.total.value) }

    /** "Save" the draft as a new pending invoice at the top of the list. */
    fun commitDraftAsInvoice(): Invoice {
        val committed = draft.copy(id = "committed", status = InvoiceStatus.PENDING)
        if (invoices.none { it.id == committed.id }) invoices.add(0, committed)
        return committed
    }
}
