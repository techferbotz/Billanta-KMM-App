package com.ferbotz.billanta.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.ferbotz.billanta.config.LaunchGate
import com.ferbotz.billanta.config.RemoteConfig
import com.ferbotz.billanta.config.launchGate
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.core.DecimalString
import com.ferbotz.billanta.core.InvoiceDateFormat
import com.ferbotz.billanta.core.logWarn
import com.ferbotz.billanta.core.randomUuid
import com.ferbotz.billanta.core.systemEpochMillis
import com.ferbotz.billanta.data.repo.InvoiceRepository
import com.ferbotz.billanta.di.AppContainer
import com.ferbotz.billanta.domain.model.CompanyProfile
import com.ferbotz.billanta.domain.model.toProfile
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
/** Settings → Account → Delete account: the confirmation the public deletion page describes. */
data object DeleteAccountRoute : Route
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
        if (settings.defaultTemplateId == null && features.templateStore && visibleTemplates.isNotEmpty()) {
            push(ChooseTemplateRoute)
        }
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
    /** Shown when back is pressed with nowhere left to go; see [back]. */
    var confirmingExit by mutableStateOf(false)

    /**
     * Where back goes, in order: close a sheet, pop a pushed screen, return to the Invoices tab,
     * and only then offer to leave.
     *
     * The tab step matters because the tabs are siblings, not a history — without it, back from
     * Catalogue closed the app outright, which is a surprising way to lose your place.
     */
    fun back(): Boolean = when {
        sheet != null -> { sheet = null; true }
        stack.isNotEmpty() -> { pop(); true }
        tab != BottomTab.INVOICES -> { selectTab(BottomTab.INVOICES); true }
        else -> { confirmingExit = true; true }
    }

    fun dismissExitConfirmation() { confirmingExit = false }

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

    // ---- remote config (BE-014) ----------------------------------------------------------------

    /** The `GET /config` document in force, as Compose state. Never absent — see RemoteConfigManager. */
    var remoteConfig by mutableStateOf(container.remoteConfig.current)
        private set

    /**
     * Which UI entry points to offer. Maintenance switches the cloud-backed ones off (see
     * [RemoteConfig.effectiveFeatures]); none of these touch the data layer.
     */
    val features: RemoteConfig.Features get() = remoteConfig.effectiveFeatures

    /** The legal links — always from `/config`, never a hard-coded host (BE-015). */
    val links: RemoteConfig.Links get() = remoteConfig.links

    /** Maintenance, a forced update or a recommended one — whichever applies first. */
    val launchGate: LaunchGate get() = launchGate(remoteConfig, container.identity)

    /** A recommended update is a nudge: once waved away, not shown again until the next launch. */
    var recommendedUpdateDismissed by mutableStateOf(false)

    /**
     * The catalogue as the pickers show it. With premium templates switched off there is nothing
     * to upsell, so they are not offered at all — an invoice already on one still renders it.
     */
    val visibleTemplates: List<TemplateInfo>
        get() = if (features.premiumTemplates) templates else templates.filterNot { it.isPremium }

    fun openTerms() = container.openInAppBrowser(links.terms)
    fun openPrivacyPolicy() = container.openInAppBrowser(links.privacyPolicy)
    fun openAccountDeletionInfo() = container.openInAppBrowser(links.deleteAccount)
    fun contactSupport() = container.openUrl("mailto:${links.supportEmail}")

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
        scope.launch { container.remoteConfig.document.collect { remoteConfig = it.config } }
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

    var deletingAccount by mutableStateOf(false)
        private set

    /** Why the last deletion attempt failed, shown on the confirmation screen itself. */
    var deleteAccountError by mutableStateOf<String?>(null)

    /**
     * `DELETE /users/me`, then signed out locally (BE-015 / BE-017).
     *
     * The server cascades everything the account owns; the local copy goes with it, so a later
     * sign-in with the same Google account cannot re-upload what was just deleted. Nothing changes
     * locally if the server refuses — the account is still there.
     */
    fun deleteAccount(onDone: () -> Unit = {}) {
        if (deletingAccount) return
        scope.launch {
            deletingAccount = true
            deleteAccountError = null
            when (val result = container.userManager.deleteAccount()) {
                is AppResult.Success -> {
                    popToRoot()
                    selectTab(BottomTab.PROFILE)
                    uiMessage = "Your account has been deleted."
                    onDone()
                }
                is AppResult.Failure -> deleteAccountError = result.error.userMessage()
            }
            deletingAccount = false
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

    /** The last complaint from a section write. Cleared as soon as the next one is attempted. */
    var draftError by mutableStateOf<String?>(null)

    // ---- section-by-section editing ------------------------------------------------------------

    /** How far a section editor's writing has got, for the line above its Done button. */
    enum class SaveState { Clean, Saving, Saved }

    /**
     * Section editors have no Save button: they write themselves a moment after the user stops
     * changing anything. That makes "did that stick?" a question the screen has to answer without
     * being asked, which is what this is for.
     */
    var sectionSaveState by mutableStateOf(SaveState.Clean)
        private set

    /** True while a section write is in flight — for buttons that must not fire twice. */
    val savingSection: Boolean get() = sectionSaveState == SaveState.Saving

    /**
     * Section writes run one at a time.
     *
     * Auto-save can ask for a write while the previous one is still going. Dropping the newer one
     * — which is what the Save button's `if (savingSection) return` did — would silently lose the
     * most recent keystrokes, the one thing an auto-saving form must never do. So they queue.
     */
    private val sectionWriteLock = Mutex()

    /** A section editor has just opened: its draft matches what is stored, and nothing has failed. */
    fun resetSectionSaveState() {
        sectionSaveState = SaveState.Clean
        draftError = null
    }

    /**
     * A failed section write, reported wherever the user can still see it.
     *
     * Auto-save means a write can land after its editor is gone — the last one fires as the screen
     * is disposed — so a complaint that only ever reached that screen's own bar would be a silent
     * loss. On screen, the bar says it; off screen, the app-wide message does.
     */
    private fun reportSectionFailure(message: String) {
        sectionSaveState = SaveState.Clean
        draftError = message
        if (currentRoute !is EditSectionRoute) uiMessage = message
    }

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

    fun setInvoiceDiscount(
        invoiceId: String,
        type: DiscountType?,
        value: String,
        beforeTax: Boolean,
        onSaved: () -> Unit = {},
    ) = saveSection(onSaved) { setDiscount(invoiceId, discountSpec(type, value), beforeTax) }

    /** Percent goes through as typed; Flat is entered in rupees and sent as paise (the wire unit). */
    private fun discountSpec(type: DiscountType?, value: String): DiscountSpec? {
        if (type == null) return null
        val raw = value.trim().takeIf { it.isNotEmpty() } ?: return null
        return when (type) {
            DiscountType.Percentage -> DiscountSpec(type, raw)
            DiscountType.Flat -> parseRupeesToPaise(raw)?.let { DiscountSpec(type, it.toString()) }
        }
    }

    /**
     * Saves the business details shown on an invoice.
     *
     * [alsoUpdateProfile] is the difference between "fix my details" and "this one invoice is
     * different": saving the profile restamps every invoice, so a one-off override has to stop at
     * this record or the correction would leak backwards into invoices already issued.
     */
    fun setInvoiceCompany(
        invoiceId: String,
        alsoUpdateProfile: Boolean,
        onSaved: () -> Unit = {},
        mutate: (CompanyProfile) -> CompanyProfile,
    ) {
        scope.launch {
            sectionWriteLock.withLock {
                sectionSaveState = SaveState.Saving
                draftError = null
                // Read the stored profile and change only what the editor asked for. An editor that
                // built a whole CompanyProfile from its own fields silently nulled every field it
                // did not show — which is how editing bank details erased the signatory, and vice
                // versa.
                val stored = container.companyRepository.getCompany()
                val invoice = container.invoiceRepository.getInvoice(invoiceId)
                val current = stored
                    ?: invoice?.companySnapshot?.toProfile()
                    ?: CompanyProfile(name = "")
                val company = mutate(current)
                // Auto-save asks for a write whenever a field settles, and a field can settle back
                // on the value it already held. Saving the profile restamps every invoice, so a
                // write that would change nothing is worth not doing at all — it also stops a
                // switch left on by default from wiping this invoice's override when the user
                // opened the section and typed nothing.
                val alreadyStored =
                    if (alsoUpdateProfile) company == stored
                    else company.toSnapshot() == invoice?.companySnapshot
                if (alreadyStored) {
                    sectionSaveState = SaveState.Saved
                    onSaved()
                    return@withLock
                }
                // Saving the profile restamps every invoice, this one included; the override does not.
                val result: AppResult<*> = if (alsoUpdateProfile) {
                    container.companyRepository.save(company)
                } else {
                    container.invoiceRepository.setCompanySnapshot(invoiceId, company.toSnapshot())
                }
                when (result) {
                    is AppResult.Success -> {
                        sectionSaveState = SaveState.Saved
                        onSaved()
                    }
                    is AppResult.Failure -> reportSectionFailure(result.error.userMessage())
                }
            }
        }
    }

    val canPickImages: Boolean get() = container.imagePickerCoordinator.isAvailable

    /**
     * Picks an image, uploads it, and puts the returned URL on the company profile.
     *
     * Upload needs the network — media has no offline queue — so this reports plainly rather than
     * queueing something that would never send. [apply] places the URL on whichever company field
     * the caller is editing (signature, logo, QR).
     */
    fun pickAndUploadCompanyImage(
        invoiceId: String,
        alsoUpdateProfile: Boolean,
        apply: (CompanyProfile, String) -> CompanyProfile,
        onDone: () -> Unit = {},
    ) {
        if (savingSection) return
        scope.launch {
            sectionSaveState = SaveState.Saving
            draftError = null
            val image = when (val picked = container.imagePickerCoordinator.pick()) {
                is AppResult.Failure -> {
                    reportSectionFailure(picked.error.userMessage())
                    return@launch
                }
                // Null means they backed out: say nothing and leave everything as it was.
                is AppResult.Success -> picked.value ?: run {
                    sectionSaveState = SaveState.Clean
                    return@launch
                }
            }
            when (val upload = container.mediaRepository.uploadImage(image.bytes, image.fileName, image.contentType)) {
                is AppResult.Failure -> reportSectionFailure(upload.error.userMessage())
                is AppResult.Success -> setInvoiceCompany(invoiceId, alsoUpdateProfile, onDone) {
                    apply(it, upload.value.url)
                }
            }
        }
    }

    fun setInvoiceNotes(invoiceId: String, notes: String, onSaved: () -> Unit = {}) =
        saveSection(onSaved) { setNotes(invoiceId, notes.trim()) }

    /**
     * Every section editor saves the same way: write, surface any complaint in place.
     *
     * [onSaved] is for the writes that are also a navigation decision — choosing a customer is one
     * whole answer, so it steps back. An auto-save passes nothing and leaves the user where they are.
     */
    private fun saveSection(
        onSaved: () -> Unit,
        write: suspend InvoiceRepository.() -> AppResult<InvoiceRecord>,
    ) {
        scope.launch {
            sectionWriteLock.withLock {
                sectionSaveState = SaveState.Saving
                draftError = null
                when (val result = container.invoiceRepository.write()) {
                    is AppResult.Success -> {
                        sectionSaveState = SaveState.Saved
                        onSaved()
                    }
                    is AppResult.Failure -> reportSectionFailure(result.error.userMessage())
                }
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

/**
 * A stored discount back into the string its field shows — the inverse of what the wire wants.
 *
 * A percentage travels as typed; a flat discount is stored in paise but entered in rupees, so that
 * conversion has to be undone or "₹500 off" would come back as "50000".
 */
internal fun discountValueInput(discount: DiscountSpec?): String {
    val spec = discount ?: return ""
    if (spec.type != DiscountType.Flat) return spec.value
    val paise = spec.value.toLongOrNull() ?: 0L
    return if (paise % 100 == 0L) (paise / 100).toString()
    else "${paise / 100}.${(paise % 100).toString().padStart(2, '0')}"
}
