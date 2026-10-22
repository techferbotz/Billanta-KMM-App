package com.ferbotz.billanta.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.ferbotz.billanta.data.db.BillantaDb
import com.ferbotz.billanta.domain.model.UserAccount
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The signed-in account, the one single-row table left outside the values maps. The business
 * profile and the settings live in [SingletonValuesLocal] since BE-021.
 */
class ProfileLocalDataSource(
    private val db: BillantaDb,
    private val dispatcher: CoroutineDispatcher,
) {

    // ---- account -------------------------------------------------------------------------------

    fun observeAccount(): Flow<UserAccount?> =
        db.accountQueries.get().asFlow().mapToList(dispatcher)
            .map { it.firstOrNull()?.toDomain() }

    suspend fun getAccount(): UserAccount? = withContext(dispatcher) {
        db.accountQueries.get().executeAsOneOrNull()?.toDomain()
    }

    suspend fun saveAccount(account: UserAccount) = withContext(dispatcher) {
        db.transaction {
            db.accountQueries.clear()
            db.accountQueries.upsert(
                id = account.id, email = account.email, name = account.name,
                photoUrl = account.photoUrl, isPremium = account.isPremium.toDbLong(),
                createdAtMillis = account.createdAtMillis, updatedAtMillis = account.updatedAtMillis,
            )
        }
    }

    suspend fun clearAccount() = withContext(dispatcher) { db.accountQueries.clear() }
}
