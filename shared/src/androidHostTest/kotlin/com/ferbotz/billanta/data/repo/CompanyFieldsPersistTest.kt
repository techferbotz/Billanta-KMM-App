package com.ferbotz.billanta.data.repo

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.core.EpochClock
import com.ferbotz.billanta.data.db.BillantaDb
import com.ferbotz.billanta.data.local.ProfileLocalDataSource
import com.ferbotz.billanta.domain.model.CompanyProfile
import com.ferbotz.billanta.domain.model.toProfile
import com.ferbotz.billanta.domain.model.toSnapshot
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Does what the bank/signature editors do, and reads it back as a new session would. */
class CompanyFieldsPersistTest {

    private fun local(): ProfileLocalDataSource {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        BillantaDb.Schema.create(driver)
        return ProfileLocalDataSource(BillantaDb(driver), UnconfinedTestDispatcher())
    }

    @Test
    fun bank_and_signature_fields_survive_a_reload() = runTest {
        val store = local()
        val repo = CompanyRepository(store, EpochClock { 1L }, onLocalMutation = {})

        val saved = repo.save(
            CompanyProfile(
                name = "Studio Nine",
                bankName = "HDFC", accountNumber = "12345678", ifsc = "HDFC0001234", upiId = "studio@okbank",
                signature = "https://cdn/sig.png", signatoryName = "Vishal B", signatoryDesignation = "Director",
            ),
        )
        assertTrue(saved is AppResult.Success, "save failed: $saved")

        val reloaded = assertNotNull(store.getCompany(), "the profile vanished")
        assertEquals("HDFC", reloaded.bankName)
        assertEquals("12345678", reloaded.accountNumber)
        assertEquals("HDFC0001234", reloaded.ifsc)
        assertEquals("studio@okbank", reloaded.upiId)
        assertEquals("https://cdn/sig.png", reloaded.signature)
        assertEquals("Vishal B", reloaded.signatoryName)
        assertEquals("Director", reloaded.signatoryDesignation)
    }

    /**
     * Editing one group of company fields must not erase another.
     *
     * The bank and signature editors each show a handful of fields. While they *built* a whole
     * CompanyProfile, every field they did not show came out null — so saving bank details erased
     * the signatory and saving the signatory erased the bank details. Each editor now describes
     * only its own change and the store supplies the rest, which is what this pins down.
     */
    @Test
    fun editing_one_group_of_fields_leaves_the_others_alone() = runTest {
        val store = local()
        val repo = CompanyRepository(store, EpochClock { 1L }, onLocalMutation = {})

        // A profile with both groups filled in, as a real user would have.
        assertTrue(
            repo.save(
                CompanyProfile(
                    name = "Studio Nine",
                    bankName = "HDFC", accountNumber = "12345678", ifsc = "HDFC0001234", upiId = "studio@okbank",
                    signature = "https://cdn/sig.png", signatoryName = "Vishal B", signatoryDesignation = "Director",
                    logo = "https://cdn/logo.png", qr = "https://cdn/qr.png",
                ),
            ) is AppResult.Success,
        )

        // What the signature editor now does: change only its own fields on the stored profile.
        val afterSignature = assertNotNull(store.getCompany()).copy(
            signatoryName = "Asha M",
            signatoryDesignation = "Partner",
        )
        assertTrue(repo.save(afterSignature) is AppResult.Success)

        assertNotNull(store.getCompany()).let {
            assertEquals("Asha M", it.signatoryName, "the edit itself should apply")
            assertEquals("HDFC", it.bankName, "editing the signatory erased the bank name")
            assertEquals("12345678", it.accountNumber, "editing the signatory erased the account")
            assertEquals("HDFC0001234", it.ifsc, "editing the signatory erased the IFSC")
            assertEquals("studio@okbank", it.upiId, "editing the signatory erased the UPI id")
        }

        // And the other direction: the bank editor must not disturb the signature.
        val afterBank = assertNotNull(store.getCompany()).copy(bankName = "ICICI", ifsc = "ICIC0004321")
        assertTrue(repo.save(afterBank) is AppResult.Success)

        assertNotNull(store.getCompany()).let {
            assertEquals("ICICI", it.bankName)
            assertEquals("https://cdn/sig.png", it.signature, "editing the bank erased the signature image")
            assertEquals("Asha M", it.signatoryName, "editing the bank erased the signatory")
            assertEquals("Partner", it.signatoryDesignation)
            // Fields no editor shows at all must survive every save.
            assertEquals("https://cdn/logo.png", it.logo, "the logo was lost")
            assertEquals("https://cdn/qr.png", it.qr, "the payment QR was lost")
        }
    }

    /** The snapshot round-trip has to be symmetric, or a field is lost on the way back. */
    @Test
    fun a_company_survives_the_trip_through_a_snapshot_and_back() {
        val original = CompanyProfile(
            name = "Studio Nine",
            gstin = "27ABCDE1234F1Z5", phone = "9876543210", email = "hi@studio.in",
            addressLine1 = "12 Linking Road", city = "Mumbai", state = "Maharashtra",
            stateCode = "27", pincode = "400050", country = "India",
            logo = "https://cdn/logo.png", signature = "https://cdn/sig.png",
            upiId = "studio@okbank", qr = "https://cdn/qr.png",
            bankName = "HDFC", accountNumber = "12345678", ifsc = "HDFC0001234",
            signatoryName = "Vishal B", signatoryDesignation = "Director",
        )
        assertEquals(original, original.toSnapshot().toProfile())
    }

    /**
     * The editors build their company from `profile ?: snapshot ?: CompanyProfile(name = "")`. A
     * user who has never filled in the business profile hits that last branch — and a blank name is
     * refused, so nothing is stored at all.
     */
    @Test
    fun saving_bank_details_before_the_business_has_a_name_is_refused() = runTest {
        val store = local()
        val repo = CompanyRepository(store, EpochClock { 1L }, onLocalMutation = {})

        val result = repo.save(CompanyProfile(name = "", bankName = "HDFC", ifsc = "HDFC0001234"))

        assertTrue(result is AppResult.Failure, "a nameless profile should not save")
        assertEquals(null, store.getCompany(), "nothing should have been written")
    }
}
