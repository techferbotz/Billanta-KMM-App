package com.ferbotz.billanta.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.ferbotz.billanta.data.db.BillantaDb
import com.ferbotz.billanta.domain.model.CompanyProfile
import com.ferbotz.billanta.domain.model.UserAccount
import com.ferbotz.billanta.domain.model.UserSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Account + company + settings — the single-row tables — plus their dirty bookkeeping. */
class ProfileLocalDataSource(
    private val db: BillantaDb,
    private val dispatcher: CoroutineDispatcher,
) {

    // ---- account -------------------------------------------------------------------------------

    fun observeAccount(): Flow<UserAccount?> =
        db.accountQueries.get().asFlow().mapToList(dispatcher)
            .map { it.firstOrNull()?.toDomain() }

    suspend fun getAccount(): UserAccount? = withContext(dispatcher) {
        db.accountQueries.get().executeAsOneOrNull()?.toDomain()
    }

    suspend fun saveAccount(account: UserAccount) = withContext(dispatcher) {
        db.transaction {
            db.accountQueries.clear()
            db.accountQueries.upsert(
                id = account.id, email = account.email, name = account.name,
                photoUrl = account.photoUrl, isPremium = account.isPremium.toDbLong(),
                createdAtMillis = account.createdAtMillis, updatedAtMillis = account.updatedAtMillis,
            )
        }
    }

    suspend fun clearAccount() = withContext(dispatcher) { db.accountQueries.clear() }

    // ---- company -------------------------------------------------------------------------------

    fun observeCompany(): Flow<CompanyProfile?> =
        db.companyQueries.get().asFlow().mapToList(dispatcher)
            .map { it.firstOrNull()?.toDomain() }

    suspend fun getCompany(): CompanyProfile? = withContext(dispatcher) {
        db.companyQueries.get().executeAsOneOrNull()?.toDomain()
    }

    suspend fun isCompanyDirty(): Boolean = withContext(dispatcher) {
        db.companyQueries.get().executeAsOneOrNull()?.dirty?.toBool() ?: false
    }

    suspend fun saveCompany(company: CompanyProfile, dirty: Boolean, updatedAtMillis: Long) =
        withContext(dispatcher) {
            db.companyQueries.upsert(
                name = company.name, gstin = company.gstin,
                addressLine1 = company.addressLine1, addressLine2 = company.addressLine2,
                city = company.city, state = company.state, stateCode = company.stateCode,
                pincode = company.pincode, country = company.country, phone = company.phone,
                email = company.email, logo = company.logo, signature = company.signature,
                upiId = company.upiId, qr = company.qr, bankName = company.bankName,
                accountNumber = company.accountNumber, ifsc = company.ifsc,
                dirty = dirty.toDbLong(), updatedAtMillis = updatedAtMillis,
            )
        }

    suspend fun markCompanyClean() = withContext(dispatcher) { db.companyQueries.markClean() }

    // ---- settings ------------------------------------------------------------------------------

    fun observeSettings(): Flow<UserSettings?> =
        db.settingsQueries.get().asFlow().mapToList(dispatcher)
            .map { it.firstOrNull()?.toDomain() }

    suspend fun getSettings(): UserSettings? = withContext(dispatcher) {
        db.settingsQueries.get().executeAsOneOrNull()?.toDomain()
    }

    suspend fun isSettingsDirty(): Boolean = withContext(dispatcher) {
        db.settingsQueries.get().executeAsOneOrNull()?.dirty?.toBool() ?: false
    }

    suspend fun saveSettings(settings: UserSettings, dirty: Boolean, updatedAtMillis: Long) =
        withContext(dispatcher) {
            db.settingsQueries.upsert(
                defaultCurrency = settings.defaultCurrency,
                defaultTaxPercent = settings.defaultTaxPercent,
                invoiceNumberPrefix = settings.invoiceNumberPrefix,
                nextInvoiceNumber = settings.nextInvoiceNumber,
                defaultTemplateId = settings.defaultTemplateId,
                defaultNotes = settings.defaultNotes,
                dirty = dirty.toDbLong(), updatedAtMillis = updatedAtMillis,
            )
        }

    suspend fun markSettingsClean() = withContext(dispatcher) { db.settingsQueries.markClean() }

    // ---- wipe (account switch / delete) --------------------------------------------------------

    suspend fun clearProfile() = withContext(dispatcher) {
        db.transaction {
            db.accountQueries.clear()
            db.companyQueries.clear()
            db.settingsQueries.clear()
        }
    }
}
