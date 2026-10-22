package com.ferbotz.billanta.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.ferbotz.billanta.data.db.BillantaDb
import com.ferbotz.billanta.data.db.SingletonValues
import com.ferbotz.billanta.fields.FieldValues
import com.ferbotz.billanta.records.BusinessProfile
import com.ferbotz.billanta.records.InvoiceSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** The business profile and the settings: the two one-per-user values maps (BE-021). */
class SingletonValuesLocal(
    private val db: BillantaDb,
    private val dispatcher: CoroutineDispatcher,
) {
    private val q get() = db.singletonValuesQueries

    fun observeProfile(): Flow<BusinessProfile?> = observe(KIND_COMPANY).map { it?.toProfile() }
    fun observeSettings(): Flow<InvoiceSettings?> = observe(KIND_SETTINGS).map { it?.toSettings() }

    suspend fun getProfile(): BusinessProfile? = get(KIND_COMPANY)?.toProfile()
    suspend fun getSettings(): InvoiceSettings? = get(KIND_SETTINGS)?.toSettings()

    /** A local edit: the ids that changed join the ones already waiting to be pushed. */
    suspend fun saveProfileLocal(values: FieldValues, updatedAtMillis: Long): BusinessProfile =
        saveLocal(KIND_COMPANY, values, updatedAtMillis).toProfile()

    suspend fun saveSettingsLocal(values: FieldValues, updatedAtMillis: Long): InvoiceSettings =
        saveLocal(KIND_SETTINGS, values, updatedAtMillis).toSettings()

    /** Server state replaces ours, unless we have edits it hasn't seen yet. */
    suspend fun applyServerProfile(values: FieldValues, createdAtMillis: Long?, updatedAtMillis: Long) =
        applyServer(KIND_COMPANY, values, createdAtMillis, updatedAtMillis)

    suspend fun applyServerSettings(values: FieldValues, createdAtMillis: Long?, updatedAtMillis: Long) =
        applyServer(KIND_SETTINGS, values, createdAtMillis, updatedAtMillis)

    suspend fun markProfileClean(updatedAtMillis: Long) =
        withContext(dispatcher) { q.markClean(KIND_COMPANY, updatedAtMillis) }

    suspend fun markSettingsClean(updatedAtMillis: Long) =
        withContext(dispatcher) { q.markClean(KIND_SETTINGS, updatedAtMillis) }

    /** Every stored key becomes pending push again — see UserManager's account-changed path. */
    suspend fun markAllDirty() = withContext(dispatcher) {
        db.transaction {
            listOf(KIND_COMPANY, KIND_SETTINGS).forEach { kind ->
                val row = q.get(kind).executeAsOneOrNull() ?: return@forEach
                q.upsert(kind, row.valuesJson, encodeKeys(FieldValues.parse(row.valuesJson).keys), row.createdAtMillis, row.updatedAtMillis)
            }
        }
    }

    suspend fun clearAll() = withContext(dispatcher) { q.clear() }

    private fun observe(kind: String): Flow<SingletonValues?> = q.get(kind).asFlow().mapToOneOrNull(dispatcher)

    private suspend fun get(kind: String): SingletonValues? = withContext(dispatcher) { q.get(kind).executeAsOneOrNull() }

    private suspend fun saveLocal(kind: String, values: FieldValues, updatedAtMillis: Long): SingletonValues =
        withContext(dispatcher) {
            db.transactionWithResult {
                val existing = q.get(kind).executeAsOneOrNull()
                val before = FieldValues.parse(existing?.valuesJson)
                val dirty = decodeKeys(existing?.dirtyKeysJson) + changedKeys(before, values)
                val row = SingletonValues(kind, values.toJsonString(), encodeKeys(dirty), existing?.createdAtMillis, updatedAtMillis)
                q.upsert(row.kind, row.valuesJson, row.dirtyKeysJson, row.createdAtMillis, row.updatedAtMillis)
                row
            }
        }

    private suspend fun applyServer(kind: String, values: FieldValues, createdAtMillis: Long?, updatedAtMillis: Long) =
        withContext(dispatcher) {
            db.transaction {
                val existing = q.get(kind).executeAsOneOrNull()
                if (existing != null && decodeKeys(existing.dirtyKeysJson).isNotEmpty()) return@transaction
                q.upsert(kind, values.toJsonString(), encodeKeys(emptySet()), createdAtMillis, updatedAtMillis)
            }
        }

    private fun SingletonValues.toProfile() = BusinessProfile(
        values = FieldValues.parse(valuesJson),
        dirtyKeys = decodeKeys(dirtyKeysJson),
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
    )

    private fun SingletonValues.toSettings() = InvoiceSettings(
        values = FieldValues.parse(valuesJson),
        dirtyKeys = decodeKeys(dirtyKeysJson),
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
    )

    private companion object {
        const val KIND_COMPANY = "company"
        const val KIND_SETTINGS = "settings"
    }
}
