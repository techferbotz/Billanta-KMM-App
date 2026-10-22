package com.ferbotz.billanta.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.ferbotz.billanta.data.db.BillantaDb
import com.ferbotz.billanta.data.db.ProductRow
import com.ferbotz.billanta.domain.model.ProductRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class ProductLocalDataSource(
    private val db: BillantaDb,
    private val dispatcher: CoroutineDispatcher,
) {
    private val q get() = db.productsQueries

    fun observeList(query: String = "", limit: Long = DEFAULT_LIMIT): Flow<List<ProductRecord>> =
        q.list(q = query.trim(), limit = limit).asFlow().mapToList(dispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    suspend fun getById(id: String): ProductRecord? = withContext(dispatcher) {
        q.byId(id).executeAsOneOrNull()?.toDomain()
    }

    suspend fun byNameKey(nameKey: String): ProductRecord? = withContext(dispatcher) {
        q.byNameKey(nameKey).executeAsOneOrNull()?.toDomain()
    }

    suspend fun upsert(record: ProductRecord, dirty: Boolean, isSynced: Boolean) =
        withContext(dispatcher) {
            q.upsert(
                id = record.id,
                name = record.name,
                nameKey = ProductRecord.nameKeyOf(record.name),
                hsnSac = record.hsnSac,
                unitPrice = record.unitPricePaise,
                taxRatePercent = record.taxRatePercent,
                unit = record.unit,
                usageCount = record.usageCount,
                lastUsedAtMillis = record.lastUsedAtMillis,
                createdAtMillis = record.createdAtMillis,
                updatedAtMillis = record.updatedAtMillis,
                dirty = dirty.toDbLong(),
                isDeleted = 0L,
                isSynced = isSynced.toDbLong(),
                imageUrl = record.imageUrl,
                imageThumbnailUrl = record.imageThumbnailUrl,
            )
        }

    suspend fun softDelete(id: String, updatedAtMillis: Long) = withContext(dispatcher) {
        q.softDelete(updatedAtMillis = updatedAtMillis, id = id)
    }

    /** Rows with local edits the server has not confirmed. */
    suspend fun dirtyRows(): List<ProductRow> = withContext(dispatcher) { q.dirtyRows().executeAsList() }

    /** Ids the server knows about and we have no local edits for — used to spot remote deletions. */
    suspend fun syncedCleanIds(): List<String> = withContext(dispatcher) { q.syncedCleanIds().executeAsList() }

    suspend fun markSynced(id: String, updatedAtMillis: Long) = withContext(dispatcher) {
        q.markSynced(id = id, updatedAtMillis = updatedAtMillis)
    }

    /**
     * Server state. A row with unpushed local edits is left alone — the push half of the pass owns
     * it, and overwriting here would lose whatever the user just typed.
     *
     * [serverKnowsImages] is false when the server's answer carried no photo keys at all — a
     * backend from before APP-015. That silence is not "the photo was removed", so the phone's
     * photo is kept; once the server does send the keys, it is authoritative, `null` included.
     *
     * [rescueUnsentPhoto] covers the handover (BE-020): until the backend stored photos it dropped
     * the keys it was sent, so a photo pushed then comes back now as `null` — never stored, rather
     * than removed. While it is set, such a row keeps its photo and is marked for a PATCH instead of
     * being cleared. Returns true when that happened.
     */
    suspend fun applyServer(
        record: ProductRecord,
        serverKnowsImages: Boolean = true,
        rescueUnsentPhoto: Boolean = false,
    ): Boolean = withContext(dispatcher) {
        val local = q.byIdIncludingDeleted(record.id).executeAsOneOrNull()
        if (local != null && local.dirty.toBool()) return@withContext false
        val rescue = rescueUnsentPhoto && serverKnowsImages && local != null &&
            (local.imageUrl != null || local.imageThumbnailUrl != null) &&
            record.imageUrl == null && record.imageThumbnailUrl == null
        q.upsert(
            id = record.id,
            name = record.name,
            nameKey = ProductRecord.nameKeyOf(record.name),
            hsnSac = record.hsnSac,
            unitPrice = record.unitPricePaise,
            taxRatePercent = record.taxRatePercent,
            unit = record.unit,
            // Usage stats are this device's own ranking signal; the server does not carry them.
            usageCount = local?.usageCount ?: record.usageCount,
            lastUsedAtMillis = local?.lastUsedAtMillis ?: record.lastUsedAtMillis,
            createdAtMillis = record.createdAtMillis,
            updatedAtMillis = record.updatedAtMillis,
            // Rescued: pending again, and still marked as known to the server, so the push sends
            // it as a PATCH carrying both photo keys rather than a POST that replaces the row.
            dirty = if (rescue) 1L else 0L,
            isDeleted = 0L,
            isSynced = 1L,
            imageUrl = if (serverKnowsImages && !rescue) record.imageUrl else local?.imageUrl,
            imageThumbnailUrl = if (serverKnowsImages && !rescue) record.imageThumbnailUrl else local?.imageThumbnailUrl,
        )
        rescue
    }

    suspend fun hardDelete(id: String) = withContext(dispatcher) { q.hardDelete(id) }

    /** Every row becomes pending push again — see UserManager's account-changed path. */
    suspend fun markAllDirty() = withContext(dispatcher) { q.markAllDirty() }

    suspend fun clearAll() = withContext(dispatcher) { q.clear() }

    private companion object {
        const val DEFAULT_LIMIT = 100L
    }
}
