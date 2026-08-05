package com.ferbotz.billanta.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.core.randomUuid
import com.ferbotz.billanta.core.systemEpochMillis
import com.ferbotz.billanta.data.repo.DashboardStats
import com.ferbotz.billanta.data.sync.SyncStatus
import com.ferbotz.billanta.di.AppContainer
import com.ferbotz.billanta.domain.model.CompanyProfile
import com.ferbotz.billanta.domain.model.CustomerRecord
import com.ferbotz.billanta.domain.model.InvoiceDocStatus
import com.ferbotz.billanta.domain.model.InvoiceDraft
import com.ferbotz.billanta.domain.model.InvoiceRecord
import com.ferbotz.billanta.domain.model.TemplateInfo
import com.ferbotz.billanta.domain.model.UserAccount
import com.ferbotz.billanta.domain.model.UserSettings
import com.ferbotz.billanta.domain.money.CalcLine
import com.ferbotz.billanta.domain.money.DiscountSpec
import com.ferbotz.billanta.domain.money.DiscountType
import com.ferbotz.billanta.domain.money.GstSplit
import com.ferbotz.billanta.domain.money.InvoiceCalculator
import com.ferbotz.billanta.domain.money.InvoiceTotals
import com.ferbotz.billanta.model.InvoiceFilter
import com.ferbotz.billanta.model.parseRupeesToPaise
import com.ferbotz.billanta.session.AuthState
import com.ferbotz.billanta.ui.components.BottomTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

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
 * App-scope state holder: navigation + ephemeral UI state, with all data collected live from the
 * repositories. Mutations go through the repos (which mark rows dirty and nudge the SyncManager);
 * this class never owns data, it mirrors the DB.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BillantaState(
    val container: AppContainer,
    private val scope: CoroutineScope,
) {

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

    fun openCreate() { resetDraft(); push(CreateInvoiceRoute) }
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

    // ---- session -------------------------------------------------------------------------------
    var auth by mutableStateOf<AuthState>(AuthState.Restoring)
        private set
    val signedIn: Boolean get() = auth is AuthState.SignedIn
    val currentUser: UserAccount? get() = (auth as? AuthState.SignedIn)?.user
    val isPremium: Boolean get() = currentUser?.isPremium == true

    var signingIn by mutableStateOf(false)
        private set
    var signInError by mutableStateOf<String?>(null)

    // ---- connectivity & sync -------------------------------------------------------------------
    var isOnline by mutableStateOf(true)
        private set
    var syncStatus by mutableStateOf(SyncStatus())
        private set

    // ---- theme ---------------------------------------------------------------------------------
    var isDark by mutableStateOf(container.prefs.getBoolean(PREF_DARK) ?: false)
        private set

    fun setDarkTheme(dark: Boolean) {
        isDark = dark
        container.prefs.putBoolean(PREF_DARK, dark)
    }

    // ---- data mirrors --------------------------------------------------------------------------
    var filter by mutableStateOf(InvoiceFilter.ALL)
    var query by mutableStateOf("")
    var loading by mutableStateOf(true)
        private set
    var invoices by mutableStateOf<List<InvoiceRecord>>(emptyList())
        private set
    private var allInvoices by mutableStateOf<List<InvoiceRecord>>(emptyList())
    var dashboard by mutableStateOf<DashboardStats?>(null)
        private set
    var customers by mutableStateOf<List<CustomerRecord>>(emptyList())
        private set
    var templates by mutableStateOf<List<TemplateInfo>>(emptyList())
        private set
    var settings by mutableStateOf(UserSettings())
        private set
    var company by mutableStateOf<CompanyProfile?>(null)
        private set

    /** Transient error/info surfaced by actions; a screen shows and clears it. */
    var uiMessage by mutableStateOf<String?>(null)

    val selectedTemplateId: String?
        get() = settings.defaultTemplateId
            ?: templates.firstOrNull { !it.isPremium }?.id
            ?: templates.firstOrNull()?.id

    fun templateById(id: String): TemplateInfo? = templates.firstOrNull { it.id == id }
    fun customerById(id: String?): CustomerRecord? = id?.let { cid -> customers.firstOrNull { it.id == cid } }
    fun invoiceFlow(id: String): Flow<InvoiceRecord?> = container.invoiceRepository.observeInvoice(id)

    fun invoiceCountFor(customerId: String): Int = allInvoices.count { it.customerId == customerId }
    fun billedTotalFor(customerId: String): Long =
        allInvoices.filter { it.customerId == customerId }.sumOf { it.grandTotalPaise }

    init {
        scope.launch { container.userManager.authState.collect { auth = it } }
        scope.launch { container.connectivity.isOnline.collect { isOnline = it } }
        scope.launch { container.syncManager.status.collect { syncStatus = it } }
        scope.launch {
            container.userManager.sessionExpired.collect { uiMessage = "Session expired — please sign in again." }
        }
        scope.launch {
            snapshotFlow { filter to query }
                .flatMapLatest { (f, q) -> container.invoiceRepository.observeInvoices(f.status, q) }
                .collect { invoices = it; loading = false }
        }
        scope.launch { container.invoiceRepository.observeInvoices(null, "").collect { allInvoices = it } }
        scope.launch { container.invoiceRepository.observeDashboard().collect { dashboard = it } }
        scope.launch { container.customerRepository.observeCustomers().collect { customers = it } }
        scope.launch { container.templateRepository.observeTemplates().collect { templates = it } }
        scope.launch { container.settingsRepository.observeSettings().collect { settings = it } }
        scope.launch { container.companyRepository.observeCompany().collect { company = it } }
    }

    // ---- invoice actions -----------------------------------------------------------------------

    fun setInvoiceStatus(id: String, status: InvoiceDocStatus) = launchReporting {
        container.invoiceRepository.setStatus(id, status)
    }

    fun deleteInvoice(id: String) {
        scope.launch { container.invoiceRepository.delete(id) }
    }

    fun setInvoiceTemplate(id: String, template: TemplateInfo) = launchReporting {
        container.invoiceRepository.setTemplate(id, template.id, template.currentVersion)
    }

    fun gstSplitFor(invoice: InvoiceRecord): GstSplit = container.invoiceRepository.gstSplitFor(invoice)

    // ---- customer actions ----------------------------------------------------------------------

    fun upsertCustomer(record: CustomerRecord, onSaved: (CustomerRecord) -> Unit = {}) {
        scope.launch {
            when (val result = container.customerRepository.upsert(record)) {
                is AppResult.Success -> onSaved(result.value)
                is AppResult.Failure -> uiMessage = result.error.userMessage()
            }
        }
    }

    fun deleteCustomer(id: String) {
        scope.launch { container.customerRepository.delete(id) }
    }

    // ---- template / settings / company actions -------------------------------------------------

    /** Free templates select immediately (persisted as the default); premium ones gate. */
    fun selectTemplate(template: TemplateInfo) {
        if (template.isPremium && !isPremium) {
            openSheet(PremiumSheet(template.id))
            return
        }
        launchReporting { container.settingsRepository.save(settings.copy(defaultTemplateId = template.id)) }
    }

    fun refreshTemplates() {
        scope.launch { container.templateRepository.refreshCatalogue() }
    }

    fun saveSettings(updated: UserSettings, onSaved: () -> Unit = {}) {
        scope.launch {
            when (val result = container.settingsRepository.save(updated)) {
                is AppResult.Success -> onSaved()
                is AppResult.Failure -> uiMessage = result.error.userMessage()
            }
        }
    }

    fun saveCompany(profile: CompanyProfile, onSaved: () -> Unit = {}) {
        scope.launch {
            when (val result = container.companyRepository.save(profile)) {
                is AppResult.Success -> onSaved()
                is AppResult.Failure -> uiMessage = result.error.userMessage()
            }
        }
    }

    // ---- session actions -----------------------------------------------------------------------

    fun signInWithGoogle(onSuccess: () -> Unit) {
        if (signingIn) return
        scope.launch {
            signingIn = true
            signInError = null
            val outcome = when (val token = container.signInCoordinator.requestIdToken()) {
                is AppResult.Failure -> token
                is AppResult.Success -> container.userManager.signInWithGoogle(token.value)
            }
            signingIn = false
            when (outcome) {
                is AppResult.Success -> {
                    container.syncManager.requestSync(immediate = true)
                    onSuccess()
                }
                is AppResult.Failure -> signInError = outcome.error.userMessage()
            }
        }
    }

    fun signOut() {
        scope.launch { container.userManager.signOut() }
    }

    fun deleteAccount(onDone: () -> Unit = {}) {
        scope.launch {
            when (val result = container.userManager.deleteAccount()) {
                is AppResult.Success -> onDone()
                is AppResult.Failure -> uiMessage = result.error.userMessage()
            }
        }
    }

    fun requestSyncNow() {
        scope.launch { container.syncManager.syncNow() }
    }

    // ---- create-invoice draft ------------------------------------------------------------------

    data class DraftLine(
        val uiId: String,
        val description: String,
        val hsnSac: String?,
        val quantity: String,
        val unitPricePaise: Long,
        val taxRatePercent: String,
    )

    val draftItems: SnapshotStateList<DraftLine> = mutableStateListOf()
    var draftNumber by mutableStateOf("")
    var draftCustomerId by mutableStateOf<String?>(null)
    var draftNotes by mutableStateOf("")
    var draftDiscountType by mutableStateOf<DiscountType?>(null)
    var draftDiscountValue by mutableStateOf("")
    var draftDiscountBeforeTax by mutableStateOf(true)
    var draftDueDays by mutableStateOf(14)
    var draftError by mutableStateOf<String?>(null)
    var savingDraft by mutableStateOf(false)
        private set

    val draftCustomer: CustomerRecord? get() = customerById(draftCustomerId)

    /** Percent goes through as typed; Flat is entered in rupees and sent as paise (the wire unit). */
    private val draftDiscount: DiscountSpec?
        get() {
            val type = draftDiscountType ?: return null
            val raw = draftDiscountValue.trim().takeIf { it.isNotEmpty() } ?: return null
            return when (type) {
                DiscountType.Percentage -> DiscountSpec(type, raw)
                DiscountType.Flat -> parseRupeesToPaise(raw)?.let { DiscountSpec(type, it.toString()) }
            }
        }

    /** Live totals via the exact server algorithm; null while any entry is unparsable. */
    val draftTotals: InvoiceTotals?
        get() = try {
            InvoiceCalculator.compute(
                items = draftItems.map { CalcLine(it.quantity, it.unitPricePaise, it.taxRatePercent) },
                discount = draftDiscount,
                discountBeforeTax = draftDiscountBeforeTax,
            )
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: ArithmeticException) {
            null
        }

    val draftGstSplit: GstSplit?
        get() = draftTotals?.let {
            InvoiceCalculator.gstSplit(it.taxTotal, company?.stateCode, draftCustomer?.stateCode)
        }

    /** Both state codes known → the CGST/SGST vs IGST rows can be labelled honestly. */
    val draftGstKnown: Boolean
        get() = !company?.stateCode.isNullOrBlank() && !draftCustomer?.stateCode.isNullOrBlank()

    private fun resetDraft() {
        draftItems.clear()
        draftCustomerId = null
        draftNotes = settings.defaultNotes ?: ""
        draftDiscountType = null
        draftDiscountValue = ""
        draftDiscountBeforeTax = true
        draftDueDays = 14
        draftError = null
        draftNumber = settings.formatNextInvoiceNumber()
    }

    fun addDraftItem(description: String, hsnSac: String?, quantity: String, unitPricePaise: Long, taxRatePercent: String) {
        draftItems.add(DraftLine(randomUuid(), description, hsnSac, quantity, unitPricePaise, taxRatePercent))
    }

    fun removeDraftItem(uiId: String) {
        draftItems.removeAll { it.uiId == uiId }
    }

    fun setDraftCustomer(id: String) { draftCustomerId = id }

    fun saveDraft(status: InvoiceDocStatus, onSaved: (InvoiceRecord) -> Unit) {
        if (savingDraft) return
        scope.launch {
            savingDraft = true
            draftError = null
            val today = todayUtcMidnightMillis()
            val template = selectedTemplateId?.let { templateById(it) }
            val draft = InvoiceDraft(
                invoiceNumber = draftNumber.trim(),
                invoiceDateMillis = today,
                dueDateMillis = today + draftDueDays * MILLIS_PER_DAY,
                currency = settings.defaultCurrency,
                status = status,
                templateId = template?.id,
                templateVersion = template?.currentVersion,
                customerId = draftCustomerId,
                notes = draftNotes.trim().ifBlank { null },
                discountType = draftDiscount?.type,
                discountValue = draftDiscount?.value,
                discountBeforeTax = draftDiscountBeforeTax,
                items = draftItems.map {
                    InvoiceDraft.DraftItem(
                        description = it.description,
                        hsnSac = it.hsnSac,
                        quantity = it.quantity,
                        unitPricePaise = it.unitPricePaise,
                        taxRatePercent = it.taxRatePercent,
                    )
                },
            )
            when (val result = container.invoiceRepository.saveDraft(draft)) {
                is AppResult.Success -> {
                    container.settingsRepository.consumeInvoiceNumberIfMatches(draft.invoiceNumber)
                    savingDraft = false
                    onSaved(result.value)
                }
                is AppResult.Failure -> {
                    savingDraft = false
                    draftError = result.error.userMessage()
                }
            }
        }
    }

    // ---- misc ----------------------------------------------------------------------------------

    private fun launchReporting(block: suspend () -> AppResult<*>) {
        scope.launch {
            (block() as? AppResult.Failure)?.let { uiMessage = it.error.userMessage() }
        }
    }

    private companion object {
        const val PREF_DARK = "ui.darkMode"
        const val MILLIS_PER_DAY = 86_400_000L
    }
}

fun todayUtcMidnightMillis(): Long = (systemEpochMillis() / 86_400_000L) * 86_400_000L
