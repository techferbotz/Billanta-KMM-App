package com.ferbotz.billanta.data.repo

import com.ferbotz.billanta.core.AppError
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.core.EpochClock
import com.ferbotz.billanta.core.randomUuid
import com.ferbotz.billanta.core.todayIso
import com.ferbotz.billanta.data.local.CustomerValuesLocal
import com.ferbotz.billanta.data.local.InvoiceValuesLocal
import com.ferbotz.billanta.data.local.ProductValuesLocal
import com.ferbotz.billanta.data.local.RememberedValuesLocal
import com.ferbotz.billanta.data.local.SingletonValuesLocal
import com.ferbotz.billanta.domain.model.InvoiceDocStatus
import com.ferbotz.billanta.fields.FieldDef
import com.ferbotz.billanta.fields.FieldOwner
import com.ferbotz.billanta.fields.FieldSection
import com.ferbotz.billanta.fields.FieldValues
import com.ferbotz.billanta.fields.InvoiceCopies
import com.ferbotz.billanta.fields.StartingValues
import com.ferbotz.billanta.records.Customer
import com.ferbotz.billanta.records.Invoice
import com.ferbotz.billanta.records.InvoiceLine
import com.ferbotz.billanta.records.Product
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement

/**
 * Invoices as BE-021 defines them: values maps edited field by field, where each field's owner
 * decides where a typed value goes (FIELDS_APP_GUIDE.md §4.5, §7).
 *
 * - **Invoice** fields live on the invoice.
 * - **Company** fields live on the invoice *and* write back to the business profile.
 * - **Customer** fields live on the invoice *and* write back to the picked customer. When none is
 *   picked, the first edit creates one.
 * - **Line** fields live on their line, and never change a product.
 *
 * **Drafts follow, issued invoices freeze.** A Draft's company and customer copy is refreshed from
 * the records whenever they change and whenever the invoice is opened. Once issued, which here
 * means first shared, it changes only through edits made inside it.
 *
 * Every change is a read-modify-write of the whole invoice, serialised on one lock so two quick
 * edits can never lose one another.
 */
class InvoiceRepository(
    private val invoices: InvoiceValuesLocal,
    private val customers: CustomerValuesLocal,
    private val products: ProductValuesLocal,
    private val singletons: SingletonValuesLocal,
    private val remembered: RememberedValuesLocal,
    private val clock: EpochClock,
    private val onLocalMutation: () -> Unit,
    private val newId: () -> String = ::randomUuid,
) {
    private val lock = Mutex()

    fun observeInvoices(query: String = ""): Flow<List<Invoice>> = invoices.observeList(query)

    fun observeInvoice(id: String): Flow<Invoice?> = invoices.observeById(id)

    suspend fun getInvoice(id: String): Invoice? = invoices.getById(id)

    /**
     * A new Draft on [templateId], with every starting value (§5): the profile copy, the next
     * number (taken from the settings counter, which moves on), and each listed field's remembered
     * value or default. [settingsForm] supplies the defaults when settings were never fetched.
     */
    suspend fun create(
        templateId: String?,
        templateVersion: Long?,
        sections: List<FieldSection>,
        settingsForm: List<FieldSection>,
    ): Invoice = lock.withLock {
        val storedSettings = singletons.getSettings()?.values ?: FieldValues.EMPTY
        val settings = StartingValues.withFormDefaults(settingsForm, storedSettings)
        val start = StartingValues.forNewInvoice(
            sections = sections,
            profile = singletons.getProfile()?.values ?: FieldValues.EMPTY,
            settings = settings,
            remembered = remembered.all(),
            today = clock.todayIso(),
            usedNumbers = invoices.numbersStartingWith(settings.string(StartingValues.SETTINGS_PREFIX).orEmpty()),
        )
        val now = clock.nowMillis()
        val invoice = Invoice(
            id = newId(),
            templateId = templateId,
            templateVersion = templateVersion,
            values = start.values,
            createdAtMillis = now,
            updatedAtMillis = now,
        ).recomputed()
        invoices.upsert(invoice, dirty = true)
        singletons.saveSettingsLocal(
            storedSettings.withString(StartingValues.SETTINGS_NEXT_NUMBER, start.nextInvoiceNumber.toString()),
            now,
        )
        onLocalMutation()
        invoice
    }

    /** Sets one field a section lists, routed by its owner. A computed field is never stored. */
    suspend fun setField(invoiceId: String, field: FieldDef, value: JsonElement?): AppResult<Invoice> =
        edit(invoiceId) { invoice ->
            val id = field.id ?: return@edit invoice
            val values = when (field.owner) {
                FieldOwner.Invoice -> invoice.values.with(id, value)
                FieldOwner.Company -> invoice.values.with(id, value).also { writeBackCompany(id, value) }
                FieldOwner.Customer -> {
                    var values = invoice.values.with(id, value)
                    val customerId = values.string(InvoiceCopies.CUSTOMER_ID)
                        ?: newId().also { values = values.withString(InvoiceCopies.CUSTOMER_ID, it) }
                    writeBackCustomer(customerId, id, value, values)
                    values
                }
                // Lines go through setLineField; settings and computed values are never an
                // invoice's to hold.
                FieldOwner.Line, FieldOwner.LineComputed, FieldOwner.Settings, FieldOwner.Computed -> invoice.values
            }
            rememberChange(field, values)
            invoice.copy(values = values)
        }.also { result ->
            // The write-back changed a record, so every other Draft that copies it follows.
            if (result is AppResult.Success && (field.owner == FieldOwner.Company || field.owner == FieldOwner.Customer)) {
                refreshDrafts()
            }
        }

    /**
     * Several fields at once, as a section editor saves them. Each goes where its owner says, in
     * order, exactly as [setField] would put it.
     */
    suspend fun setFields(invoiceId: String, changes: List<Pair<FieldDef, JsonElement?>>): AppResult<Invoice> {
        var last: AppResult<Invoice> = invoices.getById(invoiceId)?.let { AppResult.Success(it) }
            ?: AppResult.Failure(AppError.Validation("This invoice no longer exists."))
        for ((field, value) in changes) {
            last = setField(invoiceId, field, value)
            if (last is AppResult.Failure) return last
        }
        return last
    }

    /** Sets one column of one line. Products are never touched by a line edit (§4.3). */
    suspend fun setLineField(invoiceId: String, lineId: String, column: FieldDef, value: JsonElement?): AppResult<Invoice> =
        edit(invoiceId) { invoice ->
            val id = column.id ?: return@edit invoice
            if (column.owner != FieldOwner.Line) return@edit invoice
            var changed: FieldValues? = null
            val lines = invoice.lines.map { line ->
                if (line.id != lineId) line else line.copy(values = line.values.with(id, value)).also { changed = it.values }
            }
            changed?.let { rememberChange(column, it) }
            invoice.copy(lines = lines)
        }

    /** Several columns of one line at once, as the line editor saves them. */
    suspend fun setLineFields(invoiceId: String, lineId: String, changes: List<Pair<FieldDef, JsonElement?>>): AppResult<Invoice> =
        edit(invoiceId) { invoice ->
            val line = invoice.lines.firstOrNull { it.id == lineId } ?: return@edit invoice
            var values = line.values
            changes.forEach { (column, value) ->
                val id = column.id
                if (id != null && column.owner == FieldOwner.Line) values = values.with(id, value)
            }
            changes.forEach { (column, _) -> rememberChange(column, values) }
            invoice.copy(lines = invoice.lines.map { if (it.id == lineId) it.copy(values = values) else it })
        }

    /**
     * Adds a line at the end: a picked [product]'s values, all of them, then each column's
     * starting value (§4.3). Returns the new line's id.
     */
    suspend fun addLine(
        invoiceId: String,
        columns: List<FieldDef>,
        product: Product?,
        settingsForm: List<FieldSection>,
    ): AppResult<String> {
        val lineId = newId()
        val settings = StartingValues.withFormDefaults(settingsForm, singletons.getSettings()?.values ?: FieldValues.EMPTY)
        val values = StartingValues.forNewLine(columns, product?.values, settings, remembered.all(), clock.todayIso())
        return edit(invoiceId) { invoice -> invoice.copy(lines = invoice.lines + InvoiceLine(lineId, values)) }
            .also { result -> if (result is AppResult.Success && product != null) products.markUsed(product.id, clock.nowMillis()) }
            .let { result -> if (result is AppResult.Failure) result else AppResult.Success(lineId) }
    }

    suspend fun removeLine(invoiceId: String, lineId: String): AppResult<Invoice> =
        edit(invoiceId) { invoice -> invoice.copy(lines = invoice.lines.filterNot { it.id == lineId }) }

    /** Moves a line to [toIndex]; lines are sent in display order. */
    suspend fun moveLine(invoiceId: String, lineId: String, toIndex: Int): AppResult<Invoice> =
        edit(invoiceId) { invoice ->
            val line = invoice.lines.firstOrNull { it.id == lineId } ?: return@edit invoice
            val rest = invoice.lines - line
            invoice.copy(lines = rest.toMutableList().apply { add(toIndex.coerceIn(0, rest.size), line) })
        }

    /** Bills [customerId]: the invoice's customer copy becomes that customer's values (§7.1). */
    suspend fun pickCustomer(invoiceId: String, customerId: String): AppResult<Invoice> =
        edit(invoiceId) { invoice ->
            val customer = customers.getById(customerId) ?: return@edit invoice
            invoice.copy(values = InvoiceCopies.withCustomer(invoice.values, customerId, customer.values))
        }

    /**
     * Moves the invoice to another template, keeping every value (§7.3): a field the new template
     * does not list stays, unprinted, for a switch back. Fields it adds get their starting values.
     */
    suspend fun setTemplate(
        invoiceId: String,
        templateId: String,
        templateVersion: Long?,
        sections: List<FieldSection>,
        settingsForm: List<FieldSection>,
    ): AppResult<Invoice> {
        val settings = StartingValues.withFormDefaults(settingsForm, singletons.getSettings()?.values ?: FieldValues.EMPTY)
        val remembered = remembered.all()
        return edit(invoiceId) { invoice ->
            invoice.copy(
                templateId = templateId,
                templateVersion = templateVersion,
                values = StartingValues.fillMissing(sections, invoice.values, settings, remembered, clock.todayIso()),
            )
        }
    }

    suspend fun setCustomisation(
        invoiceId: String,
        hiddenSections: Set<String>,
        themeOverrides: Map<String, String>,
    ): AppResult<Invoice> = edit(invoiceId) { it.copy(hiddenSections = hiddenSections, themeOverrides = themeOverrides) }

    /**
     * Issues a Draft: from now on its company and customer copy stays as it is (§7.2). Called on
     * the first share. Its lines with new descriptions join the catalogue at the same moment, so
     * the catalogue fills with what was actually invoiced.
     */
    suspend fun markIssued(invoiceId: String): AppResult<Invoice> {
        val result = edit(invoiceId) { invoice ->
            if (invoice.isDraft) refreshedCopy(invoice).copy(status = InvoiceDocStatus.Pending) else invoice
        }
        (result as? AppResult.Success)?.value?.lines?.forEach { addToCatalogue(it) }
        return result
    }

    /**
     * A line the user finished editing joins the catalogue when its description is new there. An
     * existing product is never changed by a line: not its rate, not its photo.
     */
    suspend fun addLineToCatalogue(invoiceId: String, lineId: String) {
        invoices.getById(invoiceId)?.lines?.firstOrNull { it.id == lineId }?.let { addToCatalogue(it) }
    }

    /** Opening a Draft brings its copy up to date first (§7.2). */
    suspend fun refreshDraft(invoiceId: String): AppResult<Invoice> = edit(invoiceId) { refreshedCopy(it) }

    /** After the profile or a customer changed: every Draft's copy follows it. */
    suspend fun refreshDrafts() = lock.withLock {
        val now = clock.nowMillis()
        var changed = false
        invoices.drafts().forEach { draft ->
            val refreshed = refreshedCopy(draft)
            if (refreshed.values != draft.values) {
                invoices.upsert(refreshed.copy(updatedAtMillis = now, syncError = null).recomputed(), dirty = true)
                changed = true
            }
        }
        if (changed) onLocalMutation()
    }

    /** Gives an invoice the next number, after the server said its number is taken (a `409`). */
    suspend fun renumber(invoiceId: String, settingsForm: List<FieldSection>): AppResult<Invoice> {
        val storedSettings = singletons.getSettings()?.values ?: FieldValues.EMPTY
        val settings = StartingValues.withFormDefaults(settingsForm, storedSettings)
        val prefix = settings.string(StartingValues.SETTINGS_PREFIX).orEmpty()
        val number = StartingValues.nextNumber(
            prefix,
            settings.decimal(StartingValues.SETTINGS_NEXT_NUMBER),
            invoices.numbersStartingWith(prefix),
        )
        val result = edit(invoiceId) { it.copy(values = it.values.withString(StartingValues.INVOICE_NUMBER, "$prefix$number")) }
        if (result is AppResult.Success) {
            singletons.saveSettingsLocal(storedSettings.withString(StartingValues.SETTINGS_NEXT_NUMBER, (number + 1).toString()), clock.nowMillis())
        }
        return result
    }

    /** Soft-deletes, so the tombstone reaches the server and every other device. */
    suspend fun delete(invoiceId: String) {
        lock.withLock { invoices.softDelete(invoiceId, clock.nowMillis()) }
        onLocalMutation()
    }

    // ---- internals -----------------------------------------------------------------------------

    /**
     * The one read-modify-write path. A change that alters nothing writes nothing, so opening an
     * invoice never marks it dirty for no reason. Any real change recomputes the totals and clears
     * a sync error, giving a rejected invoice another try.
     */
    private suspend fun edit(invoiceId: String, transform: suspend (Invoice) -> Invoice): AppResult<Invoice> =
        lock.withLock {
            val invoice = invoices.getById(invoiceId)
                ?: return@withLock AppResult.Failure(AppError.Validation("This invoice no longer exists."))
            val changed = transform(invoice)
            if (changed == invoice) return@withLock AppResult.Success(invoice)
            val saved = changed.copy(updatedAtMillis = clock.nowMillis(), syncError = null, pendingSync = true).recomputed()
            invoices.upsert(saved, dirty = true)
            onLocalMutation()
            AppResult.Success(saved)
        }

    /** A Draft with its company and customer copy taken from the records again; issued ones as they are. */
    private suspend fun refreshedCopy(invoice: Invoice): Invoice {
        if (!invoice.isDraft) return invoice
        var values = invoice.values
        // Only from records this device actually has: a profile or customer not yet pulled must not
        // read as "everything was cleared".
        singletons.getProfile()?.let { values = InvoiceCopies.withCompany(values, it.values) }
        invoice.customerId?.let { id -> customers.getById(id)?.let { values = InvoiceCopies.withCustomer(values, id, it.values) } }
        return if (values == invoice.values) invoice else invoice.copy(values = values)
    }

    private suspend fun writeBackCompany(id: String, value: JsonElement?) {
        val profile = singletons.getProfile()?.values ?: FieldValues.EMPTY
        val updated = profile.with(id, value)
        if (updated != profile) singletons.saveProfileLocal(updated, clock.nowMillis())
    }

    /**
     * Writes one customer field back. A customer that does not exist yet, because none was picked,
     * is created from everything the invoice holds for its customer.
     */
    private suspend fun writeBackCustomer(customerId: String, id: String, value: JsonElement?, invoiceValues: FieldValues) {
        val now = clock.nowMillis()
        val existing = customers.getById(customerId)
        if (existing == null) {
            customers.saveLocal(Customer(customerId, InvoiceCopies.customerPart(invoiceValues), createdAtMillis = now, updatedAtMillis = now))
        } else {
            val updated = existing.values.with(id, value)
            if (updated != existing.values) customers.saveLocal(existing.copy(values = updated, updatedAtMillis = now))
        }
    }

    private suspend fun rememberChange(field: FieldDef, values: FieldValues) {
        if (!field.remembers) return
        val id = field.id ?: return
        StartingValues.rememberedAfterChange(field, values)?.let { remembered.put(id, it) } ?: remembered.delete(id)
    }

    private suspend fun addToCatalogue(line: InvoiceLine) {
        val description = line.values.string(Product.DESCRIPTION)?.takeIf { it.isNotBlank() } ?: return
        if (products.byNameKey(Product.nameKeyOf(description)) != null) return
        val now = clock.nowMillis()
        products.saveLocal(
            Product(
                id = newId(),
                values = line.values.without(StartingValues.QUANTITY),
                createdAtMillis = now,
                updatedAtMillis = now,
                usageCount = 1,
                lastUsedAtMillis = now,
            ),
        )
        onLocalMutation()
    }
}
