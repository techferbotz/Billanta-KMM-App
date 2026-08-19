package com.ferbotz.billanta.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.core.DecimalString
import com.ferbotz.billanta.core.InvoiceDateFormat
import com.ferbotz.billanta.core.logWarn
import com.ferbotz.billanta.core.randomUuid
import com.ferbotz.billanta.core.systemEpochMillis
import com.ferbotz.billanta.data.repo.InvoiceRepository
import com.ferbotz.billanta.di.AppContainer
import com.ferbotz.billanta.domain.model.CompanyProfile
import com.ferbotz.billanta.domain.model.toSnapshot
import com.ferbotz.billanta.domain.model.CustomerRecord
import com.ferbotz.billanta.domain.model.InvoiceDocStatus
import com.ferbotz.billanta.domain.model.InvoiceDraft
import com.ferbotz.billanta.domain.model.InvoiceItemRecord
import com.ferbotz.billanta.domain.model.InvoiceRecord
import com.ferbotz.billanta.domain.model.ProductRecord
import com.ferbotz.billanta.domain.model.TemplateInfo
import com.ferbotz.billanta.domain.model.UserAccount
import com.ferbotz.billanta.domain.model.UserSettings
import com.ferbotz.billanta.domain.money.CalcLine
import com.ferbotz.billanta.domain.money.DiscountSpec
import com.ferbotz.billanta.domain.money.DiscountType
import com.ferbotz.billanta.domain.money.GstSplit
import com.ferbotz.billanta.domain.money.InvoiceCalculator
import com.ferbotz.billanta.domain.money.InvoiceTotals
import com.ferbotz.billanta.model.parseRupeesToPaise
import com.ferbotz.billanta.render.SectionEdits
import com.ferbotz.billanta.render.TemplateSection
import com.ferbotz.billanta.session.AuthState
import com.ferbotz.billanta.ui.components.BottomTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/** A screen pushed on top of the current tab root. */
sealed interface Route

/** Shown once, before the first invoice, so the user picks the look they want as their default. */
data object ChooseTemplateRoute : Route
data class PreviewRoute(val invoiceId: String) : Route

/** The list of sections to fill in, in the order the template declares them. */
data class EditInvoiceDataRoute(val invoiceId: String) : Route

/** One section's editor. [edits] is what the template says this section is made of. */
data class EditSectionRoute(
    val invoiceId: String,
    val sectionId: String,
    val edits: SectionEdits,
    val label: String,
    /** Whether the template lets this block be left off the invoice at all. */
    val hidable: Boolean,
) : Route
data object BusinessProfileRoute : Route
data object SettingsRoute : Route
/**
 * [attachToInvoiceId] is set when the editor was opened from an invoice's "Bill to" screen: saving
 * a new customer there means "use this one", so it is put on the invoice straight away.
 */
data class EditCustomerRoute(val customerId: String?, val attachToInvoiceId: String? = null) : Route
data class EditProductRoute(val productId: String?) : Route

/** A modal bottom sheet layered above everything. */
sealed interface SheetRoute
data class AddItemSheet(val invoiceId: String) : SheetRoute
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

    /**
     * First time through, the user picks a template and it becomes their default; every invoice
     * after that goes straight into the create flow.
     */
    fun openCreate() {
        if (settings.defaultTemplateId == null && templates.isNotEmpty()) push(ChooseTemplateRoute)
        else createAndOpenInvoice { push(it) }
    }

    /** Chosen from the first-run picker: remember it, then carry on into the invoice. */
    fun chooseDefaultTemplate(template: TemplateInfo) {
        if (template.isPremium && !isPremium) {
            openSheet(PremiumSheet(template.id))
            return
        }
        saveSettings(settings.copy(defaultTemplateId = template.id)) {
            createAndOpenInvoice { replaceTop(it) }
        }
    }

    var creatingInvoice by mutableStateOf(false)
        private set

    /**
     * The invoice is created empty and opened straight away, so what the user edits already exists.
     * Nothing can be lost by backing out mid-way, and the preview *is* the form: every unfilled
     * section is a dashed box on the real template rather than a field in a sheet.
     */
    private fun createAndOpenInvoice(open: (Route) -> Unit) {
        if (creatingInvoice) return
        scope.launch {
            creatingInvoice = true
            val today = todayUtcMidnightMillis()
            val template = selectedTemplateId?.let { templateById(it) }
            val number = settings.formatNextInvoiceNumber()
            val result = container.invoiceRepository.createEmpty(
                invoiceNumber = number,
                currency = settings.defaultCurrency,
                templateId = template?.id,
                templateVersion = template?.currentVersion,
                invoiceDateMillis = today,
                dueDateMillis = today + DEFAULT_DUE_DAYS * MILLIS_PER_DAY,
                notes = settings.defaultNotes,
            )
            creatingInvoice = false
            when (result) {
                is AppResult.Success -> {
                    container.settingsRepository.consumeInvoiceNumberIfMatches(number)
                    open(PreviewRoute(result.value.id))
                }
                is AppResult.Failure -> uiMessage = result.error.userMessage()
            }
        }
    }
    fun openPreview(invoiceId: String) = push(PreviewRoute(invoiceId))

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

    /**
     * How dates are written on every invoice. A device preference rather than invoice data: the
     * server has no field for it, and it is a matter of taste, not of what the invoice says.
     */
    var dateFormat by mutableStateOf(InvoiceDateFormat.fromId(container.prefs.getString(PREF_DATE_FORMAT)))
        private set

    fun chooseDateFormat(format: InvoiceDateFormat) {
        dateFormat = format
        container.prefs.putString(PREF_DATE_FORMAT, format.id)
    }

    // ---- theme ---------------------------------------------------------------------------------
    var isDark by mutableStateOf(container.prefs.getBoolean(PREF_DARK) ?: false)
        private set

    fun setDarkTheme(dark: Boolean) {
        isDark = dark
        container.prefs.putBoolean(PREF_DARK, dark)
    }

    // ---- data mirrors --------------------------------------------------------------------------
    var query by mutableStateOf("")
    var loading by mutableStateOf(true)
        private set
    var invoices by mutableStateOf<List<InvoiceRecord>>(emptyList())
        private set
    private var allInvoices by mutableStateOf<List<InvoiceRecord>>(emptyList())
    var customers by mutableStateOf<List<CustomerRecord>>(emptyList())
        private set
    var templates by mutableStateOf<List<TemplateInfo>>(emptyList())
        private set
    var products by mutableStateOf<List<ProductRecord>>(emptyList())
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
        scope.launch {
            container.userManager.sessionExpired.collect { uiMessage = "Session expired — please sign in again." }
        }
        scope.launch {
            snapshotFlow { query }
                .flatMapLatest { q -> container.invoiceRepository.observeInvoices(q) }
                .collect { invoices = it; loading = false }
        }
        scope.launch { container.invoiceRepository.observeInvoices("").collect { allInvoices = it } }
        scope.launch { container.customerRepository.observeCustomers().collect { customers = it } }
        scope.launch { container.templateRepository.observeTemplates().collect { templates = it } }
        scope.launch { container.productRepository.observeProducts().collect { products = it } }
        scope.launch { container.settingsRepository.observeSettings().collect { settings = it } }
        scope.launch { container.companyRepository.observeCompany().collect { company = it } }
    }

    // ---- invoice actions -----------------------------------------------------------------------

    fun deleteInvoice(id: String) {
        scope.launch { container.invoiceRepository.delete(id) }
    }

    fun setInvoiceTemplate(id: String, template: TemplateInfo) = launchReporting {
        container.invoiceRepository.setTemplate(id, template.id, template.currentVersion)
    }

    fun setInvoiceCustomisation(
        id: String,
        themeOverrides: Map<String, Long>,
        hiddenSections: Set<String>,
    ) = launchReporting {
        container.invoiceRepository.setCustomisation(id, themeOverrides, hiddenSections)
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

    // ---- product catalogue ---------------------------------------------------------------------

    var savingProduct by mutableStateOf(false)
        private set
    var productError by mutableStateOf<String?>(null)

    fun productById(id: String?): ProductRecord? = id?.let { pid -> products.firstOrNull { it.id == pid } }

    /** [price] is what the user typed, in rupees; the catalogue stores paise. */
    fun saveProduct(
        id: String?,
        name: String,
        hsnSac: String,
        price: String,
        taxRatePercent: String,
        unit: String,
        onSaved: () -> Unit = {},
    ) {
        if (savingProduct) return
        val paise = price.trim().ifBlank { "0" }.let(::parseRupeesToPaise)
        if (paise == null) {
            productError = "Enter a valid rate"
            return
        }
        scope.launch {
            savingProduct = true
            productError = null
            val result = container.productRepository.save(
                id = id,
                name = name,
                hsnSac = hsnSac,
                unitPricePaise = paise,
                taxRatePercent = taxRatePercent.trim().ifBlank { "0" },
                unit = unit,
            )
            savingProduct = false
            when (result) {
                is AppResult.Success -> onSaved()
                is AppResult.Failure -> productError = result.error.userMessage()
            }
        }
    }

    fun deleteProduct(id: String) {
        scope.launch { container.productRepository.delete(id) }
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

    // ---- create-invoice draft ------------------------------------------------------------------

    data class DraftLine(
        val uiId: String,
        val description: String,
        val hsnSac: String?,
        val quantity: String,
        val unitPricePaise: Long,
        val taxRatePercent: String,
    )

    var draftDiscountType by mutableStateOf<DiscountType?>(null)
    var draftDiscountValue by mutableStateOf("")
    var draftDiscountBeforeTax by mutableStateOf(true)
    var draftError by mutableStateOf<String?>(null)


    /** Percent goes through as typed; Flat is entered in rupees and sent as paise (the wire unit). */
    internal val draftDiscount: DiscountSpec?
        get() {
            val type = draftDiscountType ?: return null
            val raw = draftDiscountValue.trim().takeIf { it.isNotEmpty() } ?: return null
            return when (type) {
                DiscountType.Percentage -> DiscountSpec(type, raw)
                DiscountType.Flat -> parseRupeesToPaise(raw)?.let { DiscountSpec(type, it.toString()) }
            }
        }

    /** Live totals via the exact server algorithm; null while any entry is unparsable. */
    // ---- section-by-section editing ------------------------------------------------------------

    var savingSection by mutableStateOf(false)
        private set

    fun openInvoiceData(invoiceId: String) = push(EditInvoiceDataRoute(invoiceId))

    fun openSection(invoiceId: String, section: TemplateSection) =
        push(EditSectionRoute(invoiceId, section.id, section.edits, section.label, section.hidable))

    /**
     * Tapping a section on the invoice itself goes straight into its editor, but pushes the section
     * list underneath first — so backing out lands on the list of everything still to fill in,
     * rather than returning to the invoice after every single edit.
     */
    fun openSectionViaList(invoiceId: String, section: TemplateSection) {
        push(EditInvoiceDataRoute(invoiceId))
        push(EditSectionRoute(invoiceId, section.id, section.edits, section.label, section.hidable))
    }

    /** Puts a section on the invoice, or takes it off. Only meaningful for a hidable section. */
    fun setSectionVisible(invoice: InvoiceRecord, sectionId: String, visible: Boolean) {
        val hidden = invoice.hiddenSections.toMutableSet()
        if (visible) hidden.remove(sectionId) else hidden.add(sectionId)
        // Every layer of this tests green, yet it has been reported as not working twice. Log what
        // was actually asked for, so the next report says whether the write happened at all.
        logWarn("Sections", "$sectionId visible=$visible -> hidden=$hidden on ${invoice.id}")
        setInvoiceCustomisation(invoice.id, invoice.themeOverrides, hidden)
    }

    fun setInvoiceCustomer(invoiceId: String, customerId: String, onSaved: () -> Unit = {}) =
        saveSection(onSaved) { setCustomer(invoiceId, customerId) }

    fun setInvoiceDetails(
        invoiceId: String,
        invoiceNumber: String,
        invoiceDateMillis: Long,
        dueDateMillis: Long?,
        onSaved: () -> Unit = {},
    ) = saveSection(onSaved) {
        setDetails(invoiceId, invoiceNumber.trim(), invoiceDateMillis, dueDateMillis, settings.defaultCurrency)
    }

    fun setInvoiceDiscount(invoiceId: String, onSaved: () -> Unit = {}) =
        saveSection(onSaved) { setDiscount(invoiceId, draftDiscount, draftDiscountBeforeTax) }

    /**
     * Saves the business details shown on an invoice.
     *
     * [alsoUpdateProfile] is the difference between "fix my details" and "this one invoice is
     * different": saving the profile restamps every invoice, so a one-off override has to stop at
     * this record or the correction would leak backwards into invoices already issued.
     */
    fun setInvoiceCompany(
        invoiceId: String,
        company: CompanyProfile,
        alsoUpdateProfile: Boolean,
        onSaved: () -> Unit = {},
    ) {
        if (savingSection) return
        scope.launch {
            savingSection = true
            draftError = null
            // Saving the profile restamps every invoice, this one included; the override does not.
            val result: AppResult<*> = if (alsoUpdateProfile) {
                container.companyRepository.save(company)
            } else {
                container.invoiceRepository.setCompanySnapshot(invoiceId, company.toSnapshot())
            }
            savingSection = false
            when (result) {
                is AppResult.Success -> onSaved()
                is AppResult.Failure -> draftError = result.error.userMessage()
            }
        }
    }

    fun setInvoiceNotes(invoiceId: String, notes: String, onSaved: () -> Unit = {}) =
        saveSection(onSaved) { setNotes(invoiceId, notes.trim()) }

    /** Every section editor saves the same way: write, surface any complaint, then step back. */
    private fun saveSection(
        onSaved: () -> Unit,
        write: suspend InvoiceRepository.() -> AppResult<InvoiceRecord>,
    ) {
        if (savingSection) return
        scope.launch {
            savingSection = true
            draftError = null
            val result = container.invoiceRepository.write()
            savingSection = false
            when (result) {
                is AppResult.Success -> onSaved()
                is AppResult.Failure -> draftError = result.error.userMessage()
            }
        }
    }

    /**
     * Appends one line and saves immediately.
     *
     * Edits the stored invoice rather than a scratchpad: the items screen has no Save button, so
     * there is no moment at which a pending list would be flushed — every change is the save.
     */
    fun addInvoiceItem(
        invoiceId: String,
        description: String,
        hsnSac: String?,
        quantity: String,
        unitPricePaise: Long,
        taxRatePercent: String,
    ) {
        scope.launch {
            val record = container.invoiceRepository.getInvoice(invoiceId) ?: return@launch
            val items = record.items.map { it.toDraftItem() } + InvoiceDraft.DraftItem(
                description = description,
                hsnSac = hsnSac,
                quantity = quantity,
                unitPricePaise = unitPricePaise,
                taxRatePercent = taxRatePercent,
            )
            when (val result = container.invoiceRepository.setItems(invoiceId, items)) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> uiMessage = result.error.userMessage()
            }
            // The catalogue builds itself from what actually gets invoiced.
            container.productRepository.remember(description, hsnSac, unitPricePaise, taxRatePercent)
        }
    }

    /**
     * Steps one line's quantity. Stepping the last one off removes the line, so "−" on a quantity
     * of 1 does the obvious thing rather than leaving a zero-quantity row on the invoice.
     */
    fun changeInvoiceItemQuantity(invoiceId: String, index: Int, delta: Int) {
        scope.launch {
            val record = container.invoiceRepository.getInvoice(invoiceId) ?: return@launch
            val line = record.items.getOrNull(index) ?: return@launch
            val stepped = DecimalString.parseOrNull(line.quantity)?.plusWhole(delta)
            val items = if (stepped == null) {
                record.items.filterIndexed { i, _ -> i != index }.map { it.toDraftItem() }
            } else {
                record.items.mapIndexed { i, item ->
                    if (i == index) item.toDraftItem().copy(quantity = stepped.toString())
                    else item.toDraftItem()
                }
            }
            when (val result = container.invoiceRepository.setItems(invoiceId, items)) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> uiMessage = result.error.userMessage()
            }
        }
    }

    fun removeInvoiceItem(invoiceId: String, index: Int) {
        scope.launch {
            val record = container.invoiceRepository.getInvoice(invoiceId) ?: return@launch
            if (index !in record.items.indices) return@launch
            val items = record.items.filterIndexed { i, _ -> i != index }.map { it.toDraftItem() }
            when (val result = container.invoiceRepository.setItems(invoiceId, items)) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> uiMessage = result.error.userMessage()
            }
        }
    }

    private fun InvoiceItemRecord.toDraftItem() = InvoiceDraft.DraftItem(
        description = description,
        hsnSac = hsnSac,
        quantity = quantity,
        unitPricePaise = unitPricePaise,
        taxRatePercent = taxRatePercent,
    )

    fun seedDiscountFrom(record: InvoiceRecord) {
        draftDiscountType = record.discount?.type
        // Flat discounts are stored in paise but typed in rupees, so undo the conversion.
        draftDiscountValue = record.discount?.let {
            if (it.type != DiscountType.Flat) it.value else {
                val paise = it.value.toLongOrNull() ?: 0L
                if (paise % 100 == 0L) (paise / 100).toString()
                else "${paise / 100}.${(paise % 100).toString().padStart(2, '0')}"
            }
        } ?: ""
        draftDiscountBeforeTax = record.discountBeforeTax
        draftError = null
    }

    // ---- misc ----------------------------------------------------------------------------------

    private fun launchReporting(block: suspend () -> AppResult<*>) {
        scope.launch {
            (block() as? AppResult.Failure)?.let { uiMessage = it.error.userMessage() }
        }
    }

    internal companion object {
        const val PREF_DARK = "ui.darkMode"
        const val PREF_DATE_FORMAT = "invoice.dateFormat"
        const val MILLIS_PER_DAY = 86_400_000L

        /** Net-14 unless the user picks otherwise, as the retired create form defaulted to. */
        const val DEFAULT_DUE_DAYS = 14L
    }
}

fun todayUtcMidnightMillis(): Long = (systemEpochMillis() / 86_400_000L) * 86_400_000L
