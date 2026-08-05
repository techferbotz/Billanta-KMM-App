package com.ferbotz.billanta.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.ferbotz.billanta.data.db.BillantaDb
import com.ferbotz.billanta.data.db.CustomerRow
import com.ferbotz.billanta.domain.model.CustomerRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class CustomerLocalDataSource(
    private val db: BillantaDb,
    private val dispatcher: CoroutineDispatcher,
) {
    private val q get() = db.customersQueries

    fun observeList(query: String): Flow<List<CustomerRecord>> =
        q.list(query.trim()).asFlow().mapToList(dispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    suspend fun getById(id: String): CustomerRecord? = withContext(dispatcher) {
        q.byId(id).executeAsOneOrNull()?.toDomain()
    }

    /** A local edit: marks the row dirty for the push queue. */
    suspend fun upsertLocal(record: CustomerRecord) = withContext(dispatcher) {
        val existing = q.byId(record.id).executeAsOneOrNull()
        write(record, dirty = true, isDeleted = false, isSynced = existing?.isSynced?.toBool() ?: false)
    }

    /** Server state: overwrites cleanly unless the local row has unpushed edits. */
    suspend fun applyServer(record: CustomerRecord) = withContext(dispatcher) {
        val local = q.byIdIncludingDeleted(record.id).executeAsOneOrNull()
        if (local != null && local.dirty.toBool()) return@withContext
        write(record, dirty = false, isDeleted = false, isSynced = true)
    }

    suspend fun softDelete(id: String, updatedAtMillis: Long) = withContext(dispatcher) {
        val local = q.byIdIncludingDeleted(id).executeAsOneOrNull() ?: return@withContext
        if (local.isSynced.toBool()) {
            q.softDelete(updatedAtMillis = updatedAtMillis, id = id)
        } else {
            q.hardDelete(id) // the server never saw it — nothing to replay
        }
    }

    suspend fun hardDelete(id: String) = withContext(dispatcher) { q.hardDelete(id) }

    suspend fun dirtyRows(): List<CustomerRow> = withContext(dispatcher) { q.dirtyRows().executeAsList() }

    /** Ids the server knows about and we have no local edits for — used to reconcile deletions. */
    suspend fun syncedCleanIds(): List<String> = withContext(dispatcher) { q.syncedCleanIds().executeAsList() }

    suspend fun markSynced(id: String, updatedAtMillis: Long) =
        withContext(dispatcher) { q.markSynced(id = id, updatedAtMillis = updatedAtMillis) }

    suspend fun clearAll() = withContext(dispatcher) { q.clear() }

    private fun write(record: CustomerRecord, dirty: Boolean, isDeleted: Boolean, isSynced: Boolean) {
        q.upsert(
            id = record.id, name = record.name, phone = record.phone, email = record.email,
            gstin = record.gstin, addressLine1 = record.addressLine1, addressLine2 = record.addressLine2,
            city = record.city, state = record.state, stateCode = record.stateCode,
            pincode = record.pincode, country = record.country,
            createdAtMillis = record.createdAtMillis, updatedAtMillis = record.updatedAtMillis,
            dirty = dirty.toDbLong(), isDeleted = isDeleted.toDbLong(), isSynced = isSynced.toDbLong(),
        )
    }
}
