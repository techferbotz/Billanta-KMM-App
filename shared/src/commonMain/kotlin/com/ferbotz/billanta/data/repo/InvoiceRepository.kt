package com.ferbotz.billanta.data.repo

import com.ferbotz.billanta.core.AppError
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.core.BigMath
import com.ferbotz.billanta.core.EpochClock
import com.ferbotz.billanta.core.asFailure
import com.ferbotz.billanta.core.asSuccess
import com.ferbotz.billanta.core.randomUuid
import com.ferbotz.billanta.data.local.CustomerLocalDataSource
import com.ferbotz.billanta.data.local.InvoiceLocalDataSource
import com.ferbotz.billanta.data.local.ProfileLocalDataSource
import com.ferbotz.billanta.domain.model.CompanySnapshot
import com.ferbotz.billanta.domain.model.InvoiceDraft
import com.ferbotz.billanta.domain.model.InvoiceItemRecord
import com.ferbotz.billanta.domain.model.InvoiceRecord
import com.ferbotz.billanta.domain.model.toSnapshot
import com.ferbotz.billanta.domain.money.CalcLine
import com.ferbotz.billanta.domain.money.DiscountSpec
import com.ferbotz.billanta.domain.money.GstSplit
import com.ferbotz.billanta.domain.money.InvoiceCalculator
import kotlinx.coroutines.flow.Flow

/**
 * Offline-first invoices. Every write lands locally (with totals computed by the exact server
 * algorithm) and is flagged dirty; [onLocalMutation] nudges the SyncManager to push.
 */
class InvoiceRepository(
    private val local: InvoiceLocalDataSource,
    private val customerLocal: CustomerLocalDataSource,
    private val profileLocal: ProfileLocalDataSource,
    private val clock: EpochClock,
    private val onLocalMutation: () -> Unit,
) {

    fun observeInvoices(query: String = ""): Flow<List<InvoiceRecord>> =
        local.observeList(status = null, query = query)

    fun observeInvoice(id: String): Flow<InvoiceRecord?> = local.observeById(id)

    suspend fun getInvoice(id: String): InvoiceRecord? = local.getById(id)

    /**
     * Creates or replaces an invoice from the edit flow. Totals are computed here — never taken
     * from the UI — with the MONEY.md algorithm, so the server will store identical figures.
     */
    suspend fun saveDraft(draft: InvoiceDraft): AppResult<InvoiceRecord> {
        if (draft.invoiceNumber.isBlank()) {
            return AppError.Validation("Invoice number is required").asFailure()
        }
        if (draft.items.isEmpty()) {
            return AppError.Validation("Add at least one item").asFailure()
        }

        val id = draft.id ?: randomUuid()
        if (local.isNumberInUse(draft.invoiceNumber, excludeId = id)) {
            return AppError.Validation("Invoice number ${draft.invoiceNumber} is already used").asFailure()
        }

        val totals = try {
            InvoiceCalculator.compute(
                items = draft.items.map { CalcLine(it.quantity, it.unitPricePaise, it.taxRatePercent) },
                discount = draft.discount,
                discountBeforeTax = draft.discountBeforeTax,
            )
        } catch (e: BigMath.MoneyOverflowException) {
            return AppError.Validation(e.message ?: "Amount too large").asFailure()
        } catch (e: IllegalArgumentException) {
            return AppError.Validation(e.message ?: "Invalid amounts").asFailure()
        }

        val customer = draft.customerId?.let { customerLocal.getById(it) }
        val company = profileLocal.getCompany()
        val now = clock.nowMillis()

        val record = InvoiceRecord(
            id = id,
            invoiceNumber = draft.invoiceNumber,
            invoiceDateMillis = draft.invoiceDateMillis,
            dueDateMillis = draft.dueDateMillis,
            currency = draft.currency,
            status = draft.status,
            templateId = draft.templateId,
            templateVersion = draft.templateVersion,
            customerId = customer?.id,
            customerName = customer?.name,
            // Snapshots freeze the parties at issue time — the invoice re-renders identically forever.
            customerSnapshot = customer?.toSnapshot(),
            companySnapshot = company?.toSnapshot(),
            notes = draft.notes,
            discount = draft.discount,
            discountBeforeTax = draft.discountBeforeTax,
            items = draft.items.mapIndexed { i, item ->
                InvoiceItemRecord(
                    description = item.description,
                    hsnSac = item.hsnSac,
                    quantity = item.quantity,
                    unitPricePaise = item.unitPricePaise,
                    taxRatePercent = item.taxRatePercent,
                    lineTotalPaise = totals.lines[i].lineTotal,
                    taxAmountPaise = totals.lines[i].taxAmount,
                )
            },
            subtotalPaise = totals.subtotal,
            discountTotalPaise = totals.discountTotal,
            taxTotalPaise = totals.taxTotal,
            grandTotalPaise = totals.grandTotal,
            createdAtMillis = local.getById(id)?.createdAtMillis ?: now,
            updatedAtMillis = now,
            pendingSync = true,
        )

        local.upsert(record, dirty = true)
        onLocalMutation()
        return record.asSuccess()
    }

    /** Colour and section choices are presentation-only, but belong to this invoice for good. */
    suspend fun setCustomisation(
        id: String,
        themeOverrides: Map<String, Long>,
        hiddenSections: Set<String>,
    ): AppResult<InvoiceRecord> = patchScalars(id) {
        it.copy(themeOverrides = themeOverrides, hiddenSections = hiddenSections)
    }

    // ---- section-by-section editing ------------------------------------------------------------

    /**
     * Creates the invoice up front, empty, so the user edits something that already exists rather
     * than filling a form that might be lost. Snapshots the business now; the customer snapshot
     * follows when one is chosen.
     */
    suspend fun createEmpty(
        invoiceNumber: String,
        currency: String,
        templateId: String?,
        templateVersion: Long?,
        invoiceDateMillis: Long,
        dueDateMillis: Long?,
        notes: String?,
    ): AppResult<InvoiceRecord> {
        val now = clock.nowMillis()
        val record = InvoiceRecord(
            id = randomUuid(),
            invoiceNumber = invoiceNumber,
            invoiceDateMillis = invoiceDateMillis,
            dueDateMillis = dueDateMillis,
            currency = currency,
            templateId = templateId,
            templateVersion = templateVersion,
            companySnapshot = profileLocal.getCompany()?.toSnapshot(),
            notes = notes,
            createdAtMillis = now,
            updatedAtMillis = now,
            pendingSync = true,
        )
        local.upsert(record, dirty = true)
        onLocalMutation()
        return record.asSuccess()
    }

    /** Attaches a customer, re-snapshotting them so the invoice keeps what they looked like now. */
    suspend fun setCustomer(id: String, customerId: String): AppResult<InvoiceRecord> {
        val customer = customerLocal.getById(customerId)
            ?: return AppError.Validation("Customer not found").asFailure()
        return patchScalars(id) {
            it.copy(
                customerId = customer.id,
                customerName = customer.name,
                customerSnapshot = customer.toSnapshot(),
            )
        }
    }

    suspend fun setDetails(
        id: String,
        invoiceNumber: String,
        invoiceDateMillis: Long,
        dueDateMillis: Long?,
        currency: String,
    ): AppResult<InvoiceRecord> {
        if (invoiceNumber.isBlank()) {
            return AppError.Validation("Invoice number is required").asFailure()
        }
        if (local.isNumberInUse(invoiceNumber, excludeId = id)) {
            return AppError.Validation("Invoice number $invoiceNumber is already used").asFailure()
        }
        return patchScalars(id) {
            it.copy(
                invoiceNumber = invoiceNumber,
                invoiceDateMillis = invoiceDateMillis,
                dueDateMillis = dueDateMillis,
                currency = currency,
            )
        }
    }

    suspend fun setItems(id: String, items: List<InvoiceDraft.DraftItem>): AppResult<InvoiceRecord> =
        recomputeAndSave(id) { record ->
            record.copy(
                items = items.map {
                    InvoiceItemRecord(
                        description = it.description,
                        hsnSac = it.hsnSac,
                        quantity = it.quantity,
                        unitPricePaise = it.unitPricePaise,
                        taxRatePercent = it.taxRatePercent,
                    )
                },
            )
        }

    suspend fun setDiscount(id: String, discount: DiscountSpec?, beforeTax: Boolean): AppResult<InvoiceRecord> =
        recomputeAndSave(id) { it.copy(discount = discount, discountBeforeTax = beforeTax) }

    suspend fun setNotes(id: String, notes: String?): AppResult<InvoiceRecord> =
        patchScalars(id) { it.copy(notes = notes?.takeIf { text -> text.isNotBlank() }) }

    /**
     * Applies an edit that changes the money, then recomputes every total with the exact server
     * algorithm — so a partly-filled invoice always shows figures the server will agree with.
     */
    private suspend fun recomputeAndSave(
        id: String,
        transform: (InvoiceRecord) -> InvoiceRecord,
    ): AppResult<InvoiceRecord> {
        val existing = local.getById(id) ?: return AppError.Validation("Invoice not found").asFailure()
        val edited = transform(existing)

        val totals = try {
            InvoiceCalculator.compute(
                items = edited.items.map { CalcLine(it.quantity, it.unitPricePaise, it.taxRatePercent) },
                discount = edited.discount,
                discountBeforeTax = edited.discountBeforeTax,
            )
        } catch (e: BigMath.MoneyOverflowException) {
            return AppError.Validation(e.message ?: "Amount too large").asFailure()
        } catch (e: IllegalArgumentException) {
            return AppError.Validation(e.message ?: "Invalid amounts").asFailure()
        }

        val updated = edited.copy(
            items = edited.items.mapIndexed { i, item ->
                item.copy(
                    lineTotalPaise = totals.lines[i].lineTotal,
                    taxAmountPaise = totals.lines[i].taxAmount,
                )
            },
            subtotalPaise = totals.subtotal,
            discountTotalPaise = totals.discountTotal,
            taxTotalPaise = totals.taxTotal,
            grandTotalPaise = totals.grandTotal,
            updatedAtMillis = clock.nowMillis(),
            pendingSync = true,
        )
        local.upsert(updated, dirty = true)
        onLocalMutation()
        return updated.asSuccess()
    }

    /** Template only affects rendering; the change re-syncs as an idempotent re-POST. */
    suspend fun setTemplate(id: String, templateId: String, templateVersion: Long?): AppResult<InvoiceRecord> =
        patchScalars(id) { it.copy(templateId = templateId, templateVersion = templateVersion) }

    private suspend fun patchScalars(id: String, transform: (InvoiceRecord) -> InvoiceRecord): AppResult<InvoiceRecord> {
        val existing = local.getById(id)
            ?: return AppError.Validation("Invoice not found").asFailure()
        val updated = transform(existing).copy(updatedAtMillis = clock.nowMillis(), pendingSync = true)
        local.upsert(updated, dirty = true)
        onLocalMutation()
        return updated.asSuccess()
    }

    /**
     * Puts the current business details on every invoice that is not deleted.
     *
     * The company snapshot is the issuer's own letterhead rather than a third party captured at
     * issue time, so correcting a GSTIN or an address should reach the invoices already made — not
     * just the next one. The customer snapshot is deliberately *not* treated this way: that one
     * records who was billed, and it has to keep saying what it said when the invoice went out.
     */
    suspend fun restampCompanySnapshot(snapshot: CompanySnapshot?) {
        local.restampCompanySnapshot(snapshot, clock.nowMillis())
        onLocalMutation()
    }

    /** Soft delete — a tombstone, exactly like the server's. Sync replays it as DELETE. */
    suspend fun delete(id: String) {
        val now = clock.nowMillis()
        local.softDelete(id, deletedAtMillis = now, updatedAtMillis = now)
        onLocalMutation()
    }

    /** Presentation-only CGST/SGST vs IGST split, from the snapshots' state codes. */
    fun gstSplitFor(invoice: InvoiceRecord): GstSplit = InvoiceCalculator.gstSplit(
        taxTotal = invoice.taxTotalPaise,
        sellerStateCode = invoice.companySnapshot?.stateCode,
        buyerStateCode = invoice.customerSnapshot?.stateCode,
    )

    suspend fun invoiceStatsForCustomer(customerId: String): Pair<Long, Long> =
        local.statsForCustomer(customerId)
}
