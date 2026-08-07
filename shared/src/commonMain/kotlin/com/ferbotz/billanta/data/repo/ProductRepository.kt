package com.ferbotz.billanta.data.repo

import com.ferbotz.billanta.core.EpochClock
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

    suspend fun delete(id: String) {
        local.softDelete(id, updatedAtMillis = clock.nowMillis())
        onLocalMutation()
    }
}
