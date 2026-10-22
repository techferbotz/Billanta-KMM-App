package com.ferbotz.billanta.data.local

import com.ferbotz.billanta.data.db.BillantaDb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

/**
 * Remembered values (FIELDS.md §8.3): device-only, keyed by field id. Nothing in this class talks to
 * the network, and nothing that does reads this table.
 */
class RememberedValuesLocal(
    private val db: BillantaDb,
    private val dispatcher: CoroutineDispatcher,
) {
    private val q get() = db.rememberedValueQueries

    suspend fun all(): Map<String, JsonElement> = withContext(dispatcher) {
        q.all().executeAsList().mapNotNull { row -> parseOrNull(row.valueJson)?.let { row.fieldId to it } }.toMap()
    }

    suspend fun put(fieldId: String, value: JsonElement) = withContext(dispatcher) { q.put(fieldId, value.toString()) }

    suspend fun delete(fieldId: String) = withContext(dispatcher) { q.delete(fieldId) }

    suspend fun clearAll() = withContext(dispatcher) { q.clear() }
}
