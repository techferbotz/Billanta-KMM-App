package com.ferbotz.billanta.state

import androidx.compose.runtime.derivedStateOf
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
import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.di.AppContainer
import com.ferbotz.billanta.domain.model.TemplateInfo
import com.ferbotz.billanta.domain.model.UserAccount
import com.ferbotz.billanta.fields.FieldDef
import com.ferbotz.billanta.fields.FieldValues
import com.ferbotz.billanta.fields.FormsDoc
import com.ferbotz.billanta.fields.ImageValue
import com.ferbotz.billanta.model.stateCodeFromGstin
import com.ferbotz.billanta.records.BusinessProfile
import com.ferbotz.billanta.records.Customer
import com.ferbotz.billanta.records.Invoice
import com.ferbotz.billanta.records.InvoiceSettings
import com.ferbotz.billanta.records.Product
import com.ferbotz.billanta.render.TemplateDoc
import com.ferbotz.billanta.render.TemplateParser
import com.ferbotz.billanta.session.AuthState
import com.ferbotz.billanta.ui.components.BottomTab
import com.ferbotz.billanta.ui.fields.FieldContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** A screen pushed on top of the current tab root. */
sealed interface Route

/** Shown once, before the first invoice, so the user picks the look they want as their default. */
data object ChooseTemplateRoute : Route
data class PreviewRoute(val invoiceId: String) : Route

/** The list of sections to fill in, in the order the template declares them. */
data class EditInvoiceDataRoute(val invoiceId: String) : Route

/** One section's editor, built from the fields the template lists in it (BE-021). */
data class EditSectionRoute(val invoiceId: String, val sectionId: String) : Route

/** One line of an invoice, edited column by column. */
data class EditLineRoute(val invoiceId: String, val lineId: String) : Route

/** The saved customers, to bill one of them. */
data class PickCustomerRoute(val invoiceId: String) : Route

data object BusinessProfileRoute : Route
data object SettingsRoute : Route

/** The settings form from `GET /forms`: numbering and the defaults a new invoice starts from. */
data object InvoiceDefaultsRoute : Route

/** Settings → Account → Delete account: the confirmation the public deletion page describes. */
data object DeleteAccountRoute : Route

/**
 * [attachToInvoiceId] is set when the editor was opened from an invoice's customer picker: saving
 * a new customer there means "bill this one", so it is put on the invoice straight away.
 */
data class EditCustomerRoute(val customerId: String?, val attachToInvoiceId: String? = null) : Route
data class EditProductRoute(val productId: String?) : Route

/** A modal bottom sheet layered above everything. */
sealed interface SheetRoute
data class PremiumSheet(val templateId: String) : SheetRoute

/**
 * App-scope state holder: navigation and ephemeral UI state, with all data collected live from the
 * repositories. Mutations go through the repositories, which mark rows dirty and nudge the sync;
 * this class never owns data, it mirrors the database.
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

    fun openSheet(s: SheetRoute) { sheet = s }
    fun closeSheet() { sheet = null }

    /** Shown when back is pressed with nowhere left to go; see [back]. */
    var confirmingExit by mutableStateOf(false)

    /**
     * Where back goes, in order: close a sheet, pop a pushed screen, return to the Invoices tab,
     * and only then offer to leave.
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

    /** How dates are written on every invoice: a device preference, not invoice data. */
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
    var invoices by mutableStateOf<List<Invoice>>(emptyList())
        private set
    private var allInvoices by mutableStateOf<List<Invoice>>(emptyList())
    var customers by mutableStateOf<List<Customer>>(emptyList())
        private set
    var templates by mutableStateOf<List<TemplateInfo>>(emptyList())
        private set
    var products by mutableStateOf<List<Product>>(emptyList())
        private set
    var profile by mutableStateOf<BusinessProfile?>(null)
        private set
    var settings by mutableStateOf<InvoiceSettings?>(null)
        private set

    /** The business profile, customer, product and settings forms (`GET /forms`). Never absent. */
    var forms by mutableStateOf(container.formsRepository.forms.value)
        private set

    /** Transient error/info surfaced by actions; a screen shows and clears it. */
    var uiMessage by mutableStateOf<String?>(null)

    // ---- remote config (BE-014) ----------------------------------------------------------------

    var remoteConfig by mutableStateOf(container.remoteConfig.current)
        private set

    val features: RemoteConfig.Features get() = remoteConfig.effectiveFeatures
    val links: RemoteConfig.Links get() = remoteConfig.links
    val launchGate: LaunchGate get() = launchGate(remoteConfig, container.identity)
    var recommendedUpdateDismissed by mutableStateOf(false)

    val visibleTemplates: List<TemplateInfo>
        get() = if (features.premiumTemplates) templates else templates.filterNot { it.isPremium }

    fun openTerms() = container.openInAppBrowser(links.terms)
    fun openPrivacyPolicy() = container.openInAppBrowser(links.privacyPolicy)
    fun openAccountDeletionInfo() = container.openInAppBrowser(links.deleteAccount)
    fun contactSupport() = container.openUrl("mailto:${links.supportEmail}")

    // ---- templates -----------------------------------------------------------------------------

    /**
     * The template new invoices start on. The device remembers the last one chosen — BE-021 took
     * the default template off the server's settings, as a device preference rather than data.
     */
    var defaultTemplateId by mutableStateOf(container.prefs.getString(PREF_DEFAULT_TEMPLATE))
        private set

    val selectedTemplateId: String?
        get() = defaultTemplateId?.takeIf { id -> templates.isEmpty() || templates.any { it.id == id } }
            ?: templates.firstOrNull { !it.isPremium }?.id
            ?: templates.firstOrNull()?.id

    private fun rememberTemplate(id: String) {
        defaultTemplateId = id
        container.prefs.putString(PREF_DEFAULT_TEMPLATE, id)
    }

    fun templateById(id: String): TemplateInfo? = templates.firstOrNull { it.id == id }

    /**
     * The compiled tree an invoice draws with. A Draft follows the template's current version; an
     * issued invoice keeps the version it was made on, and falls back to the newest one cached
     * when that one cannot be fetched offline (FIELDS_APP_GUIDE.md §10).
     */
    suspend fun templateDocFor(invoice: Invoice?): AppResult<TemplateDoc> {
        val templateId = invoice?.templateId?.takeIf { it.isNotEmpty() } ?: selectedTemplateId
            ?: return AppResult.Failure(com.ferbotz.billanta.core.AppError.Validation("No templates yet — connect once to download them."))
        val current = templateById(templateId)?.currentVersion
        val version = if (invoice == null || invoice.isDraft) current ?: invoice?.templateVersion else invoice.templateVersion ?: current
        return when (val result = container.templateRepository.getCompiled(templateId, version)) {
            is AppResult.Success -> TemplateParser.parse(result.value.json)?.let { AppResult.Success(it) }
                ?: AppResult.Failure(com.ferbotz.billanta.core.AppError.Validation("This template can't be displayed — try updating the app."))
            is AppResult.Failure -> if (version != null && version != current) {
                container.templateRepository.getCompiled(templateId, null).let { fallback ->
                    (fallback as? AppResult.Success)?.value?.json?.let(TemplateParser::parse)?.let { AppResult.Success(it) } ?: result
                }
            } else result
        }
    }

    fun invoiceFlow(id: String): Flow<Invoice?> = container.invoiceRepository.observeInvoice(id)

    fun customerById(id: String?): Customer? = id?.let { cid -> customers.firstOrNull { it.id == cid } }
    fun productById(id: String?): Product? = id?.let { pid -> products.firstOrNull { it.id == pid } }

    /**
     * Invoices, amount billed and latest invoice date for every customer, computed in one pass and
     * only when the invoices change.
     */
    val customerStats: Map<String, CustomerStats> by derivedStateOf {
        allInvoices.filter { it.customerId != null }.groupBy { it.customerId!! }.mapValues { (_, list) ->
            CustomerStats(
                invoiceCount = list.size,
                billedPaise = list.sumOf { it.computed.grandTotal },
                lastInvoiceMillis = list.maxOf { it.invoiceDateMillis ?: 0L },
            )
        }
    }

    /** Where this business is registered, for telling a same-state sale from an inter-state one. */
    val sellerStateCode: String?
        get() = profile?.values?.let { it.string(COMPANY_STATE) ?: it.string(COMPANY_GSTIN)?.let(::stateCodeFromGstin) }

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
        scope.launch { container.profileRepository.observeSettings().collect { settings = it } }
        scope.launch { container.profileRepository.observeProfile().collect { profile = it } }
        scope.launch { container.formsRepository.forms.collect { forms = it } }
        scope.launch { container.remoteConfig.document.collect { remoteConfig = it.config } }
    }

    // ---- creating an invoice -------------------------------------------------------------------

    /**
     * First time through, the user picks a template and it becomes their default; every invoice
     * after that goes straight into the create flow.
     */
    fun openCreate() {
        if (defaultTemplateId == null && features.templateStore && visibleTemplates.isNotEmpty()) push(ChooseTemplateRoute)
        else createAndOpenInvoice { push(it) }
    }

    /** Chosen from the first-run picker: remember it, then carry on into the invoice. */
    fun chooseDefaultTemplate(template: TemplateInfo) {
        if (template.isPremium && !isPremium) {
            openSheet(PremiumSheet(template.id))
            return
        }
        rememberTemplate(template.id)
        createAndOpenInvoice { replaceTop(it) }
    }

    var creatingInvoice by mutableStateOf(false)
        private set

    /**
     * The invoice is created with its starting values and opened straight away, so what the user
     * edits already exists. Without a template to read (offline, never downloaded) it still
     * starts with the profile copy and a number; the template's own fields fill in when it loads.
     */
    private fun createAndOpenInvoice(open: (Route) -> Unit) {
        if (creatingInvoice) return
        scope.launch {
            creatingInvoice = true
            val templateId = selectedTemplateId
            val doc = (templateDocFor(null) as? AppResult.Success)?.value
            val invoice = container.invoiceRepository.create(
                templateId = templateId,
                templateVersion = templateId?.let { templateById(it)?.currentVersion },
                sections = doc?.sections?.map { it.asFieldSection() }.orEmpty(),
                settingsForm = forms.settings,
            )
            creatingInvoice = false
            open(PreviewRoute(invoice.id))
        }
    }

    fun openPreview(invoiceId: String) = push(PreviewRoute(invoiceId))

    // ---- invoice actions -----------------------------------------------------------------------

    fun deleteInvoice(id: String) {
        scope.launch { container.invoiceRepository.delete(id) }
    }

    /** Opening a Draft brings its company and customer copy up to date (FIELDS.md §8.4). */
    fun refreshDraft(invoiceId: String) {
        scope.launch { container.invoiceRepository.refreshDraft(invoiceId) }
    }

    /** Moves an invoice to another template, keeping every value; the new template's fields start. */
    fun setInvoiceTemplate(invoice: Invoice, template: TemplateInfo) = launchReporting {
        val doc = when (val result = container.templateRepository.getCompiled(template.id, template.currentVersion)) {
            is AppResult.Success -> TemplateParser.parse(result.value.json)
            is AppResult.Failure -> return@launchReporting result
        }
        rememberTemplate(template.id)
        container.invoiceRepository.setTemplate(
            invoiceId = invoice.id,
            templateId = template.id,
            templateVersion = template.currentVersion,
            sections = doc?.sections?.map { it.asFieldSection() }.orEmpty(),
            settingsForm = forms.settings,
        )
    }

    fun setInvoiceCustomisation(invoice: Invoice, themeOverrides: Map<String, String>, hiddenSections: Set<String>) =
        launchReporting { container.invoiceRepository.setCustomisation(invoice.id, hiddenSections, themeOverrides) }

    /** Puts a section on the invoice, or takes it off. Only offered for a hidable section. */
    fun setSectionVisible(invoice: Invoice, sectionId: String, visible: Boolean) {
        val hidden = if (visible) invoice.hiddenSections - sectionId else invoice.hiddenSections + sectionId
        setInvoiceCustomisation(invoice, invoice.themeOverrides, hidden)
    }

    /**
     * The first share issues the invoice: its company and customer copy stops following the
     * records (FIELDS.md §8.4), and its new lines join the catalogue.
     */
    fun markIssued(invoiceId: String) {
        scope.launch { container.invoiceRepository.markIssued(invoiceId) }
    }

    /** Gives an invoice the next free number, after the server said its number is already used. */
    fun renumber(invoiceId: String) = launchReporting { container.invoiceRepository.renumber(invoiceId, forms.settings) }

    // ---- section-by-section editing ------------------------------------------------------------

    /** How far a section editor's writing has got, for the line above its Done button. */
    enum class SaveState { Clean, Saving, Saved }

    var sectionSaveState by mutableStateOf(SaveState.Clean)
        private set

    /** The last complaint from a section write. Cleared as soon as the next one is attempted. */
    var draftError by mutableStateOf<String?>(null)

    /** Section writes run one at a time, so an auto-save can never drop the newest keystrokes. */
    private val sectionWriteLock = Mutex()

    fun resetSectionSaveState() {
        sectionSaveState = SaveState.Clean
        draftError = null
    }

    fun openInvoiceData(invoiceId: String) = push(EditInvoiceDataRoute(invoiceId))

    fun openSection(invoiceId: String, sectionId: String) = push(EditSectionRoute(invoiceId, sectionId))

    /**
     * Tapping a section on the invoice itself goes straight into its editor, with the section list
     * underneath, so backing out lands on everything still to fill in.
     */
    fun openSectionViaList(invoiceId: String, sectionId: String) {
        push(EditInvoiceDataRoute(invoiceId))
        push(EditSectionRoute(invoiceId, sectionId))
    }

    /** Saves what a section editor changed; each field goes where its owner says (FIELDS_APP_GUIDE.md §4.5). */
    fun saveInvoiceFields(invoiceId: String, changes: List<Pair<FieldDef, JsonElement?>>) {
        if (changes.isEmpty()) return
        writeSection { container.invoiceRepository.setFields(invoiceId, changes) }
    }

    fun saveLineFields(invoiceId: String, lineId: String, changes: List<Pair<FieldDef, JsonElement?>>) {
        if (changes.isEmpty()) return
        writeSection { container.invoiceRepository.setLineFields(invoiceId, lineId, changes) }
    }

    private fun writeSection(write: suspend () -> AppResult<*>) {
        scope.launch {
            sectionWriteLock.withLock {
                sectionSaveState = SaveState.Saving
                draftError = null
                when (val result = write()) {
                    is AppResult.Success -> sectionSaveState = SaveState.Saved
                    is AppResult.Failure -> {
                        sectionSaveState = SaveState.Clean
                        draftError = result.error.userMessage()
                        // A write can land after its editor has gone; say so where the user still is.
                        if (currentRoute !is EditSectionRoute && currentRoute !is EditLineRoute) uiMessage = draftError
                    }
                }
            }
        }
    }

    fun pickCustomer(invoiceId: String, customerId: String, onDone: () -> Unit = {}) {
        scope.launch {
            when (val result = container.invoiceRepository.pickCustomer(invoiceId, customerId)) {
                is AppResult.Success -> onDone()
                is AppResult.Failure -> uiMessage = result.error.userMessage()
            }
        }
    }

    /** A blank line, opened straight into its editor. */
    fun addBlankLine(invoiceId: String, columns: List<FieldDef>) {
        scope.launch {
            when (val result = container.invoiceRepository.addLine(invoiceId, columns, null, forms.settings)) {
                is AppResult.Success -> push(EditLineRoute(invoiceId, result.value))
                is AppResult.Failure -> uiMessage = result.error.userMessage()
            }
        }
    }

    /** A line copied from a catalogue product. It is complete as it is, so no editor opens. */
    fun addProductLine(invoiceId: String, columns: List<FieldDef>, product: Product) = launchReporting {
        container.invoiceRepository.addLine(invoiceId, columns, product, forms.settings)
    }

    fun removeLine(invoiceId: String, lineId: String) = launchReporting {
        container.invoiceRepository.removeLine(invoiceId, lineId)
    }

    fun moveLine(invoiceId: String, lineId: String, toIndex: Int) = launchReporting {
        container.invoiceRepository.moveLine(invoiceId, lineId, toIndex)
    }

    /**
     * Steps a line's quantity by whole units, keeping any fraction. Stepping the last one off
     * removes the line, so "−" on a quantity of 1 does the obvious thing.
     */
    fun stepLineQuantity(invoice: Invoice, lineId: String, quantityColumn: FieldDef, delta: Int) {
        val line = invoice.lines.firstOrNull { it.id == lineId } ?: return
        val current = line.values.decimal(QUANTITY) ?: DecimalString(0, 0)
        val stepped = current.plusWhole(delta)
        if (stepped == null) removeLine(invoice.id, lineId)
        else saveLineFields(invoice.id, lineId, listOf(quantityColumn to JsonPrimitive(stepped.toString())))
    }

    /** The line editor closed: a line with a new description joins the catalogue. */
    fun lineEditorClosed(invoiceId: String, lineId: String) {
        if (!features.productCatalogue) return
        scope.launch { container.invoiceRepository.addLineToCatalogue(invoiceId, lineId) }
    }

    // ---- customers, products, profile, settings ------------------------------------------------

    fun saveCustomer(customer: Customer, onSaved: (Customer) -> Unit = {}) {
        scope.launch { onSaved(container.customerRepository.save(customer)) }
    }

    fun deleteCustomer(id: String) {
        scope.launch { container.customerRepository.delete(id) }
    }

    fun saveProduct(product: Product, onSaved: () -> Unit = {}) {
        scope.launch {
            container.productRepository.save(product)
            onSaved()
        }
    }

    fun deleteProduct(id: String) {
        scope.launch { container.productRepository.delete(id) }
    }

    fun saveProfile(values: FieldValues, onSaved: () -> Unit = {}) {
        scope.launch {
            container.profileRepository.saveProfile(values)
            onSaved()
        }
    }

    fun saveSettings(values: FieldValues, onSaved: () -> Unit = {}) {
        scope.launch {
            container.profileRepository.saveSettings(values)
            onSaved()
        }
    }

    // ---- images --------------------------------------------------------------------------------

    val canPickImages: Boolean get() = container.imagePickerCoordinator.isAvailable

    /** True from the moment a picked image starts uploading until its URLs are back. */
    var uploadingImage by mutableStateOf(false)
        private set

    /**
     * Why an image cannot be added right now, or null when it can. Uploads go straight to
     * `POST /media`, which needs an account and a connection — media has no offline queue.
     */
    val imageBlocker: String?
        get() = when {
            !features.mediaUpload -> "Adding images isn't available right now."
            !canPickImages -> "Choosing an image isn't available in this build."
            !signedIn -> "Sign in from Profile to add images."
            else -> null
        }

    /** Picks an image and uploads it, handing back what `POST /media` returned. */
    fun pickAndUploadImage(onPicked: (ImageValue) -> Unit) {
        if (uploadingImage || imageBlocker != null) return
        scope.launch {
            val image = when (val picked = container.imagePickerCoordinator.pick()) {
                is AppResult.Failure -> {
                    uiMessage = picked.error.userMessage()
                    return@launch
                }
                // Null means they backed out of the picker: a decision, so say nothing.
                is AppResult.Success -> picked.value ?: return@launch
            }
            uploadingImage = true
            when (val upload = container.mediaRepository.uploadImage(image.bytes, image.fileName, image.contentType)) {
                is AppResult.Success -> onPicked(ImageValue(upload.value.url, upload.value.thumbnailUrl))
                is AppResult.Failure -> uiMessage = upload.error.userMessage()
            }
            uploadingImage = false
        }
    }

    /** What every field input needs from the app, over [valueOf] for the record being edited. */
    fun fieldContext(valueOf: (String) -> JsonElement?, computedText: (FieldDef) -> String? = { null }) = FieldContext(
        optionLists = forms.optionLists,
        dateFormat = dateFormat,
        valueOf = valueOf,
        imageBlocker = imageBlocker,
        pickImage = ::pickAndUploadImage,
        uploadingImage = uploadingImage,
        computedText = computedText,
    )

    // ---- template choice -----------------------------------------------------------------------

    /** Free templates select immediately; premium ones gate. */
    fun selectTemplate(template: TemplateInfo) {
        if (template.isPremium && !isPremium) {
            openSheet(PremiumSheet(template.id))
            return
        }
        rememberTemplate(template.id)
    }

    fun refreshTemplates() {
        scope.launch { container.templateRepository.refreshCatalogue() }
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

    /** `DELETE /users/me`, then signed out locally (BE-015 / BE-017). */
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

    // ---- misc ----------------------------------------------------------------------------------

    private fun launchReporting(block: suspend () -> AppResult<*>) {
        scope.launch {
            (block() as? AppResult.Failure)?.let { uiMessage = it.error.userMessage() }
        }
    }

    internal companion object {
        const val PREF_DARK = "ui.darkMode"
        const val PREF_DATE_FORMAT = "invoice.dateFormat"
        const val PREF_DEFAULT_TEMPLATE = "invoice.defaultTemplateId"
        const val QUANTITY = "quantity"
        const val COMPANY_STATE = "company_state"
        const val COMPANY_GSTIN = "company_gstin"
    }
}

/** An invoice's date as UTC-midnight millis, for sorting, grouping and the short date on cards. */
val Invoice.invoiceDateMillis: Long?
    get() = values.string(Invoice.INVOICE_DATE)?.let(Iso8601::parseOrNull)

/** What a customer card summarises about the invoices made out to them. */
data class CustomerStats(
    val invoiceCount: Int,
    val billedPaise: Long,
    val lastInvoiceMillis: Long,
)
