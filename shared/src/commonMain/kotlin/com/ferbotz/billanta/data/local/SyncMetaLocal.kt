package com.ferbotz.billanta.data.local

import com.ferbotz.billanta.data.db.BillantaDb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Sync bookkeeping (cursor, timestamps) — DB-backed so it's wiped together with the data. */
class SyncMetaLocal(
    private val db: BillantaDb,
    private val dispatcher: CoroutineDispatcher,
) {
    suspend fun get(key: String): String? = withContext(dispatcher) {
        db.syncMetaQueries.get(key).executeAsOneOrNull()
    }

    suspend fun put(key: String, value: String) = withContext(dispatcher) {
        db.syncMetaQueries.put(key, value)
    }

    suspend fun delete(key: String) = withContext(dispatcher) { db.syncMetaQueries.delete(key) }

    suspend fun clearAll() = withContext(dispatcher) { db.syncMetaQueries.clear() }

    companion object {
        const val KEY_INVOICE_CURSOR = "invoiceCursor"
        const val KEY_LAST_SYNC_AT = "lastSyncAtMillis"
        const val KEY_OWNER_USER_ID = "ownerUserId"
    }
}
