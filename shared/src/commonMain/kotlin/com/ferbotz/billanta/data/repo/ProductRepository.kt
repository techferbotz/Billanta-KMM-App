package com.ferbotz.billanta.data.repo

import com.ferbotz.billanta.core.AppError
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.core.EpochClock
import com.ferbotz.billanta.core.asFailure
import com.ferbotz.billanta.core.asSuccess
import com.ferbotz.billanta.core.randomUuid
import com.ferbotz.billanta.data.local.ProductLocalDataSource
import com.ferbotz.billanta.domain.model.ProductRecord
import kotlinx.coroutines.flow.Flow

/**
 * The reusable product catalogue. It builds itself: every line item added to an invoice is
 * remembered, so the next invoice can pick it instead of retyping the description, rate and tax
 * rate.
 *
 * Local-only for now — the backend endpoints are requested as APP-002 in the contract folder.
 * Rows already carry the same dirty/synced columns as customers, so wiring the push is a small
 * addition rather than a migration.
 */
class ProductRepository(
    private val local: ProductLocalDataSource,
    private val clock: EpochClock,
    private val onLocalMutation: () -> Unit,
) {

    fun observeProducts(query: String = ""): Flow<List<ProductRecord>> = local.observeList(query)

    /**
     * Records that a line item was used. A product already known under the same normalised name is
     * updated in place — its rate refreshed and its usage count bumped — so the catalogue tracks
     * what the user currently charges rather than filling up with near-duplicates.
     */
    suspend fun remember(
        name: String,
        hsnSac: String?,
        unitPricePaise: Long,
        taxRatePercent: String,
        unit: String? = null,
    ): ProductRecord? {
        if (name.isBlank()) return null
        val now = clock.nowMillis()
        val existing = local.byNameKey(ProductRecord.nameKeyOf(name))

        val record = existing?.copy(
            name = name.trim(),
            hsnSac = hsnSac ?: existing.hsnSac,
            unitPricePaise = unitPricePaise,
            taxRatePercent = taxRatePercent,
            unit = unit ?: existing.unit,
            usageCount = existing.usageCount + 1,
            lastUsedAtMillis = now,
            updatedAtMillis = now,
        ) ?: ProductRecord(
            id = randomUuid(),
            name = name.trim(),
            hsnSac = hsnSac,
            unitPricePaise = unitPricePaise,
            taxRatePercent = taxRatePercent,
            unit = unit,
            usageCount = 1,
            lastUsedAtMillis = now,
            createdAtMillis = now,
            updatedAtMillis = now,
        )

        local.upsert(record, dirty = true, isSynced = existing?.pendingSync?.not() ?: false)
        onLocalMutation()
        return record
    }

    suspend fun getById(id: String): ProductRecord? = local.getById(id)

    /**
     * Creates or edits a product from the catalogue screen.
     *
     * Deliberately not [remember]: that matches on the name so repeated invoicing updates one row,
     * and bumps the usage count. Editing by hand has to key on the id instead, or renaming a
     * product would silently create a second one and leave the original behind.
     */
    suspend fun save(
        id: String?,
        name: String,
        hsnSac: String?,
        unitPricePaise: Long,
        taxRatePercent: String,
        unit: String?,
    ): AppResult<ProductRecord> {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return AppError.Validation("Name is required").asFailure()

        // Two rows sharing a name would make the auto-capture lookup ambiguous, so refuse rather
        // than let the catalogue drift into near-duplicates.
        local.byNameKey(ProductRecord.nameKeyOf(trimmed))?.let { clash ->
            if (clash.id != id) {
                return AppError.Validation("\"$trimmed\" is already in the catalogue").asFailure()
            }
        }

        val now = clock.nowMillis()
        val existing = id?.let { local.getById(it) }
        val record = existing?.copy(
            name = trimmed,
            hsnSac = hsnSac?.takeIf { it.isNotBlank() },
            unitPricePaise = unitPricePaise,
            taxRatePercent = taxRatePercent,
            unit = unit?.takeIf { it.isNotBlank() },
            updatedAtMillis = now,
        ) ?: ProductRecord(
            id = id ?: randomUuid(),
            name = trimmed,
            hsnSac = hsnSac?.takeIf { it.isNotBlank() },
            unitPricePaise = unitPricePaise,
            taxRatePercent = taxRatePercent,
            unit = unit?.takeIf { it.isNotBlank() },
            usageCount = 0,
            lastUsedAtMillis = 0,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        local.upsert(record, dirty = true, isSynced = existing?.pendingSync?.not() ?: false)
        onLocalMutation()
        return record.asSuccess()
    }

    suspend fun delete(id: String) {
        local.softDelete(id, updatedAtMillis = clock.nowMillis())
        onLocalMutation()
    }
}
