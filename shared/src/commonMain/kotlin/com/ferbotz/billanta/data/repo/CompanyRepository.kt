package com.ferbotz.billanta.data.repo

import com.ferbotz.billanta.core.AppError
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.core.EpochClock
import com.ferbotz.billanta.core.asFailure
import com.ferbotz.billanta.core.asSuccess
import com.ferbotz.billanta.data.local.ProfileLocalDataSource
import com.ferbotz.billanta.domain.model.CompanyProfile
import kotlinx.coroutines.flow.Flow

class CompanyRepository(
    private val local: ProfileLocalDataSource,
    private val clock: EpochClock,
    private val onLocalMutation: () -> Unit,
) {

    fun observeCompany(): Flow<CompanyProfile?> = local.observeCompany()

    suspend fun getCompany(): CompanyProfile? = local.getCompany()

    /** Full replace, like PUT /company. Saved locally, pushed by the sync pass. */
    suspend fun save(company: CompanyProfile): AppResult<CompanyProfile> {
        if (company.name.isBlank()) {
            return AppError.Validation("Business name is required").asFailure()
        }
        local.saveCompany(company, dirty = true, updatedAtMillis = clock.nowMillis())
        onLocalMutation()
        return company.asSuccess()
    }
}
