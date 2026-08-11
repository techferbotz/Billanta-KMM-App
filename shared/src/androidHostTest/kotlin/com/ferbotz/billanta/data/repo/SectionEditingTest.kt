package com.ferbotz.billanta.data.repo

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.core.EpochClock
import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.data.db.BillantaDb
import com.ferbotz.billanta.data.local.CustomerLocalDataSource
import com.ferbotz.billanta.data.local.InvoiceLocalDataSource
import com.ferbotz.billanta.data.local.ProfileLocalDataSource
import com.ferbotz.billanta.domain.model.CustomerRecord
import com.ferbotz.billanta.domain.model.InvoiceDraft
import com.ferbotz.billanta.domain.model.InvoiceRecord
import com.ferbotz.billanta.domain.money.DiscountSpec
import com.ferbotz.billanta.domain.money.DiscountType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The invoice is now created empty and filled in one section at a time, so each write has to leave
 * a document the server would agree with — totals recomputed, snapshots frozen, nothing lost from
 * the sections that were not being edited.
 */
class SectionEditingTest {

    private val fixedNow = Iso8601.epochMillisFor(2026, 8, 6, 12, 0)
    private val today = Iso8601.epochMillisFor(2026, 8, 6)

    private class Harness(dispatcher: CoroutineDispatcher) {
        private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val db = BillantaDb(driver).also { BillantaDb.Schema.create(driver) }
        val customers = CustomerLocalDataSource(db, dispatcher)
        var mutations = 0
        val repo = InvoiceRepository(
            local = InvoiceLocalDataSource(db, dispatcher),
            customerLocal = customers,
            profileLocal = ProfileLocalDataSource(db, dispatcher),
            clock = EpochClock { Iso8601.epochMillisFor(2026, 8, 6, 12, 0) },
            onLocalMutation = { mutations++ },
        )
    }

    private fun harness() = Harness(UnconfinedTestDispatcher())

    private suspend fun InvoiceRepository.createBlank(): InvoiceRecord = expect(
        createEmpty(
            invoiceNumber = "INV-1",
            currency = "INR",
            templateId = "classic",
            templateVersion = 3,
            invoiceDateMillis = today,
            dueDateMillis = today + 14 * 86_400_000L,
            notes = null,
        ),
    )

    private fun <T> expect(result: AppResult<T>): T = when (result) {
        is AppResult.Success -> result.value
        is AppResult.Failure -> throw AssertionError("expected success, got ${result.error}")
    }

    private fun item(description: String, price: Long, qty: String = "1", tax: String = "18") =
        InvoiceDraft.DraftItem(
            description = description, hsnSac = null, quantity = qty,
            unitPricePaise = price, taxRatePercent = tax,
        )

    @Test
    fun a_new_invoice_starts_empty_but_real() = runTest {
        val h = harness()
        val created = h.repo.createBlank()

        assertEquals("INV-1", created.invoiceNumber)
        assertTrue(created.items.isEmpty(), "a new invoice has no items yet")
        assertNull(created.customerId, "no customer until one is chosen")
        assertEquals(0L, created.grandTotalPaise)
        assertTrue(created.pendingSync, "it must sync like any other invoice")

        // It exists in the database from the moment it is created — backing out loses nothing.
        assertNotNull(h.repo.getInvoice(created.id), "the empty invoice was not persisted")
        assertEquals(1, h.mutations, "creating should nudge sync exactly once")
    }

    @Test
    fun each_section_saves_without_disturbing_the_others() = runTest {
        val h = harness()
        val id = h.repo.createBlank().id
        h.customers.upsertLocal(
            CustomerRecord(id = "cust-1", name = "Kavya Iyer", stateCode = "27", gstin = "27ABCDE1234F1Z5"),
        )

        expect(h.repo.setCustomer(id, "cust-1"))
        expect(h.repo.setItems(id, listOf(item("Design", 100_000))))
        expect(h.repo.setNotes(id, "Thanks!"))
        val final = expect(h.repo.setDetails(id, "INV-7", today, null, "INR"))

        assertEquals("Kavya Iyer", final.customerSnapshot?.name, "the customer was lost by a later edit")
        assertEquals("27ABCDE1234F1Z5", final.customerSnapshot?.gstin, "the snapshot must freeze the whole party")
        assertEquals(1, final.items.size, "the items were lost by a later edit")
        assertEquals("Thanks!", final.notes)
        assertEquals("INV-7", final.invoiceNumber)
        assertNull(final.dueDateMillis, "clearing the due date should stick")
    }

    @Test
    fun editing_items_recomputes_every_total() = runTest {
        val h = harness()
        val id = h.repo.createBlank().id

        val withItems = expect(h.repo.setItems(id, listOf(item("Design", 100_000), item("Print", 50_000))))
        assertEquals(150_000, withItems.subtotalPaise)
        assertEquals(27_000, withItems.taxTotalPaise, "18% of ₹1500")
        assertEquals(177_000, withItems.grandTotalPaise)
        // Per-line figures come from the calculator, never from the caller.
        assertEquals(listOf(100_000L, 50_000L), withItems.items.map { it.lineTotalPaise })
        assertEquals(listOf(18_000L, 9_000L), withItems.items.map { it.taxAmountPaise })

        // Removing a line has to walk the totals back down, not just drop the row.
        val trimmed = expect(h.repo.setItems(id, listOf(item("Design", 100_000))))
        assertEquals(100_000, trimmed.subtotalPaise)
        assertEquals(118_000, trimmed.grandTotalPaise)
    }

    @Test
    fun a_discount_applies_to_an_invoice_that_already_has_items() = runTest {
        val h = harness()
        val id = h.repo.createBlank().id
        expect(h.repo.setItems(id, listOf(item("Design", 100_000))))

        val discounted = expect(
            h.repo.setDiscount(id, DiscountSpec(DiscountType.Percentage, "10"), beforeTax = true),
        )
        assertEquals(10_000, discounted.discountTotalPaise)
        assertEquals(100_000, discounted.subtotalPaise, "subtotal is before discount")
        assertEquals(16_200, discounted.taxTotalPaise, "tax follows the discounted base")
        assertEquals(106_200, discounted.grandTotalPaise)

        // And taking it away has to undo all of that.
        val cleared = expect(h.repo.setDiscount(id, null, beforeTax = true))
        assertEquals(0, cleared.discountTotalPaise)
        assertEquals(118_000, cleared.grandTotalPaise)
    }

    @Test
    fun an_edit_that_cannot_be_honoured_is_refused_rather_than_half_applied() = runTest {
        val h = harness()
        val first = h.repo.createBlank()
        val second = expect(
            h.repo.createEmpty("INV-2", "INR", "classic", 3, today, null, null),
        )

        // Two invoices may not share a number.
        val clash = h.repo.setDetails(second.id, "INV-1", today, null, "INR")
        assertTrue(clash is AppResult.Failure, "a duplicate invoice number should be refused")
        assertEquals("INV-2", h.repo.getInvoice(second.id)?.invoiceNumber, "the refused edit still landed")

        assertTrue(
            h.repo.setDetails(second.id, "  ", today, null, "INR") is AppResult.Failure,
            "a blank invoice number should be refused",
        )
        assertTrue(
            h.repo.setCustomer(second.id, "nobody") is AppResult.Failure,
            "attaching a customer that does not exist should be refused",
        )
        assertEquals("INV-1", h.repo.getInvoice(first.id)?.invoiceNumber, "the other invoice was touched")
    }

    @Test
    fun every_section_edit_marks_the_invoice_for_sync() = runTest {
        val h = harness()
        val id = h.repo.createBlank().id
        val before = h.mutations

        expect(h.repo.setItems(id, listOf(item("Design", 100_000))))
        expect(h.repo.setNotes(id, "Thanks!"))

        assertEquals(before + 2, h.mutations, "each section edit must nudge the sync manager")
        assertTrue(h.repo.getInvoice(id)!!.pendingSync, "an edited invoice must be dirty")
        assertEquals(fixedNow, h.repo.getInvoice(id)!!.updatedAtMillis, "updatedAt drives last-write-wins")
    }
}
