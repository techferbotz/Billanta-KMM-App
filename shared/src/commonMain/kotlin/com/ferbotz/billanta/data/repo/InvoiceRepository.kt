package com.ferbotz.billanta.data.repo

import com.ferbotz.billanta.core.AppError
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.core.BigMath
import com.ferbotz.billanta.core.EpochClock
import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.core.asFailure
import com.ferbotz.billanta.core.asSuccess
import com.ferbotz.billanta.core.randomUuid
import com.ferbotz.billanta.data.local.CustomerLocalDataSource
import com.ferbotz.billanta.data.local.InvoiceLocalDataSource
import com.ferbotz.billanta.data.local.ProfileLocalDataSource
import com.ferbotz.billanta.domain.model.InvoiceDocStatus
import com.ferbotz.billanta.domain.model.InvoiceDraft
import com.ferbotz.billanta.domain.model.InvoiceItemRecord
import com.ferbotz.billanta.domain.model.InvoiceRecord
import com.ferbotz.billanta.domain.model.toSnapshot
import com.ferbotz.billanta.domain.money.CalcLine
import com.ferbotz.billanta.domain.money.GstSplit
import com.ferbotz.billanta.domain.money.InvoiceCalculator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class DashboardStats(
    val monthTotalPaise: Long,
    val unpaidTotalPaise: Long,
    val pendingCount: Int,
)

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

    fun observeInvoices(status: InvoiceDocStatus? = null, query: String = ""): Flow<List<InvoiceRecord>> =
        local.observeList(status, query)

    fun observeInvoice(id: String): Flow<InvoiceRecord?> = local.observeById(id)

    suspend fun getInvoice(id: String): InvoiceRecord? = local.getById(id)

    fun observeDashboard(): Flow<DashboardStats> {
        val monthRange = Iso8601.monthRange(clock.nowMillis())
        return combine(
            local.observeMonthTotal(monthRange),
            local.observeUnpaidTotal(),
            local.observePendingCount(),
        ) { month, unpaid, pending ->
            DashboardStats(month, unpaid, pending.toInt())
        }
    }

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

    suspend fun setStatus(id: String, status: InvoiceDocStatus): AppResult<InvoiceRecord> =
        patchScalars(id) { it.copy(status = status) }

    suspend fun updateNotes(id: String, notes: String?): AppResult<InvoiceRecord> =
        patchScalars(id) { it.copy(notes = notes) }

    suspend fun updateDueDate(id: String, dueDateMillis: Long?): AppResult<InvoiceRecord> =
        patchScalars(id) { it.copy(dueDateMillis = dueDateMillis) }

    suspend fun setPdfPath(id: String, pdfPath: String?): AppResult<InvoiceRecord> =
        patchScalars(id) { it.copy(pdfPath = pdfPath) }

    private suspend fun patchScalars(id: String, transform: (InvoiceRecord) -> InvoiceRecord): AppResult<InvoiceRecord> {
        val existing = local.getById(id)
            ?: return AppError.Validation("Invoice not found").asFailure()
        val updated = transform(existing).copy(updatedAtMillis = clock.nowMillis(), pendingSync = true)
        local.upsert(updated, dirty = true)
        onLocalMutation()
        return updated.asSuccess()
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
