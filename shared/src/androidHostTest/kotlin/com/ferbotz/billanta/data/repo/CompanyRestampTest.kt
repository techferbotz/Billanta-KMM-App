package com.ferbotz.billanta.data.repo

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.core.EpochClock
import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.data.db.BillantaDb
import com.ferbotz.billanta.data.local.CustomerLocalDataSource
import com.ferbotz.billanta.data.local.InvoiceLocalDataSource
import com.ferbotz.billanta.data.local.ProfileLocalDataSource
import com.ferbotz.billanta.domain.model.CompanyProfile
import com.ferbotz.billanta.domain.model.CustomerRecord
import com.ferbotz.billanta.domain.model.toSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Correcting your business details has to reach the invoices you have already made.
 *
 * The company snapshot is the issuer's own letterhead, so a fixed GSTIN or address belongs on every
 * invoice — not only the next one created. Previously the snapshot was written once at creation and
 * never again, so an existing invoice kept printing the old details forever.
 */
class CompanyRestampTest {

    private val now = Iso8601.epochMillisFor(2026, 8, 19, 10, 0)
    private val today = Iso8601.epochMillisFor(2026, 8, 19)

    private class Harness(dispatcher: CoroutineDispatcher, now: Long) {
        private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val db = BillantaDb(driver).also { BillantaDb.Schema.create(driver) }
        val invoiceLocal = InvoiceLocalDataSource(db, dispatcher)
        val customerLocal = CustomerLocalDataSource(db, dispatcher)
        val profileLocal = ProfileLocalDataSource(db, dispatcher)
        val clock = EpochClock { now }
        val invoices = InvoiceRepository(invoiceLocal, customerLocal, profileLocal, clock) {}
        val company = CompanyRepository(
            local = profileLocal,
            clock = clock,
            onLocalMutation = {},
            onCompanyChanged = { invoices.restampCompanySnapshot(it.toSnapshot()) },
        )
    }

    private fun harness() = Harness(UnconfinedTestDispatcher(), now)

    private fun <T> expect(result: AppResult<T>): T = when (result) {
        is AppResult.Success -> result.value
        is AppResult.Failure -> throw AssertionError("expected success, got ${result.error}")
    }

    private suspend fun Harness.newInvoice(number: String) = expect(
        invoices.createEmpty(number, "INR", "classic", 1, today, null, null),
    )

    @Test
    fun editing_the_business_updates_the_invoices_already_made() = runTest {
        val h = harness()
        expect(h.company.save(CompanyProfile(name = "Studio Nine", gstin = "27AAAAA0000A1Z5")))
        val invoice = h.newInvoice("INV-1")
        assertEquals("27AAAAA0000A1Z5", invoice.companySnapshot?.gstin, "the invoice did not snapshot at creation")

        expect(h.company.save(CompanyProfile(name = "Studio Nine", gstin = "27BBBBB1111B1Z5", city = "Pune")))

        val after = assertNotNull(h.invoices.getInvoice(invoice.id))
        assertEquals("27BBBBB1111B1Z5", after.companySnapshot?.gstin, "the corrected GSTIN never reached the invoice")
        assertEquals("Pune", after.companySnapshot?.city, "a newly filled field did not reach the invoice either")
    }

    @Test
    fun every_live_invoice_is_updated_not_just_the_newest() = runTest {
        val h = harness()
        expect(h.company.save(CompanyProfile(name = "Studio Nine")))
        val first = h.newInvoice("INV-1")
        val second = h.newInvoice("INV-2")

        expect(h.company.save(CompanyProfile(name = "Studio Ten")))

        assertEquals("Studio Ten", h.invoices.getInvoice(first.id)?.companySnapshot?.name)
        assertEquals("Studio Ten", h.invoices.getInvoice(second.id)?.companySnapshot?.name)
    }

    /**
     * The asymmetry that matters: the customer snapshot records *who was billed* and must keep
     * saying what it said when the invoice went out, whatever the customer record says now.
     */
    @Test
    fun the_customer_snapshot_is_deliberately_left_alone() = runTest {
        val h = harness()
        expect(h.company.save(CompanyProfile(name = "Studio Nine")))
        h.customerLocal.upsertLocal(CustomerRecord(id = "cust-1", name = "Kavya Iyer", city = "Mumbai"))
        val invoice = h.newInvoice("INV-1")
        expect(h.invoices.setCustomer(invoice.id, "cust-1"))

        expect(h.company.save(CompanyProfile(name = "Studio Ten")))

        val after = assertNotNull(h.invoices.getInvoice(invoice.id))
        assertEquals("Studio Ten", after.companySnapshot?.name, "the company snapshot should follow the profile")
        assertEquals("Kavya Iyer", after.customerSnapshot?.name, "the customer snapshot must not be touched")
        assertEquals("Mumbai", after.customerSnapshot?.city)
    }

    @Test
    fun a_restamped_invoice_is_queued_to_sync() = runTest {
        val h = harness()
        expect(h.company.save(CompanyProfile(name = "Studio Nine")))
        val invoice = h.newInvoice("INV-1")
        h.invoiceLocal.markClean(invoice.id, invoice.updatedAtMillis)
        assertTrue(h.invoiceLocal.dirtyRecords().isEmpty(), "test setup: the invoice should start clean")

        expect(h.company.save(CompanyProfile(name = "Studio Ten")))

        assertEquals(
            listOf(invoice.id),
            h.invoiceLocal.dirtyRecords().map { it.id },
            "the server would keep the old letterhead if this never pushed",
        )
    }

    /**
     * A deleted invoice is a tombstone waiting to be replayed as a DELETE. Rewriting its contents
     * would bump `updatedAt` on a row whose only job is to say "this is gone", so the restamp has
     * to skip it.
     */
    @Test
    fun a_deleted_invoice_is_left_as_a_tombstone() = runTest {
        val h = harness()
        expect(h.company.save(CompanyProfile(name = "Studio Nine")))
        val invoice = h.newInvoice("INV-1")
        h.invoices.delete(invoice.id)

        expect(h.company.save(CompanyProfile(name = "Studio Ten")))

        val tombstone = assertNotNull(h.invoices.getInvoice(invoice.id))
        assertNotNull(tombstone.deletedAtMillis, "it should still be deleted")
        assertEquals(
            "Studio Nine",
            tombstone.companySnapshot?.name,
            "the restamp touched a tombstone",
        )
    }
}
