package com.ferbotz.billanta.data.local

import com.ferbotz.billanta.data.db.BillantaDb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** The last `GET /forms` document and its ETag. Not user data, so it outlives sign-out. */
class FormsCacheLocal(
    private val db: BillantaDb,
    private val dispatcher: CoroutineDispatcher,
) {
    data class Cached(val json: String, val etag: String?)

    suspend fun get(): Cached? = withContext(dispatcher) {
        db.formsCacheQueries.get().executeAsOneOrNull()?.let { Cached(it.json, it.etag) }
    }

    suspend fun put(json: String, etag: String?, atMillis: Long) =
        withContext(dispatcher) { db.formsCacheQueries.put(json, etag, atMillis) }
}
