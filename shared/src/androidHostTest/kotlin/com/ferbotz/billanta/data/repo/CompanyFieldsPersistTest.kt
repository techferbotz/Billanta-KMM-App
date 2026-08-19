package com.ferbotz.billanta.data.repo

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.core.EpochClock
import com.ferbotz.billanta.data.db.BillantaDb
import com.ferbotz.billanta.data.local.ProfileLocalDataSource
import com.ferbotz.billanta.domain.model.CompanyProfile
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
