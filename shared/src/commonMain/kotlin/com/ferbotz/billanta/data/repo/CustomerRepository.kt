package com.ferbotz.billanta.data.repo

import com.ferbotz.billanta.core.AppError
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.core.EpochClock
import com.ferbotz.billanta.core.asFailure
import com.ferbotz.billanta.core.asSuccess
import com.ferbotz.billanta.core.randomUuid
import com.ferbotz.billanta.data.local.CustomerLocalDataSource
import com.ferbotz.billanta.domain.model.CustomerRecord
import kotlinx.coroutines.flow.Flow

/** Offline-first customers. Writes are local; sync replays them as POST/PATCH/DELETE. */
class CustomerRepository(
    private val local: CustomerLocalDataSource,
    private val clock: EpochClock,
    private val onLocalMutation: () -> Unit,
) {

    fun observeCustomers(query: String = ""): Flow<List<CustomerRecord>> = local.observeList(query)

    suspend fun getCustomer(id: String): CustomerRecord? = local.getById(id)

    /** Create (null/blank id) or update. Returns the saved record with its id. */
    suspend fun upsert(customer: CustomerRecord): AppResult<CustomerRecord> {
        if (customer.name.isBlank()) {
            return AppError.Validation("Customer name is required").asFailure()
        }
        val now = clock.nowMillis()
        val record = customer.copy(
            id = customer.id.ifBlank { randomUuid() },
            createdAtMillis = customer.createdAtMillis ?: now,
            updatedAtMillis = now,
            pendingSync = true,
        )
        local.upsertLocal(record)
        onLocalMutation()
        return record.asSuccess()
    }

    suspend fun delete(id: String) {
        local.softDelete(id, updatedAtMillis = clock.nowMillis())
        onLocalMutation()
    }
}
