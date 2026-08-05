package com.ferbotz.billanta.data.sync

import com.ferbotz.billanta.core.AppError
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.core.ConnectivityObserver
import com.ferbotz.billanta.core.EpochClock
import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.data.api.BillantaApi
import com.ferbotz.billanta.data.api.SyncRequestDto
import com.ferbotz.billanta.data.api.toDomain
import com.ferbotz.billanta.data.api.toDto
import com.ferbotz.billanta.data.api.toPatchBody
import com.ferbotz.billanta.data.local.CustomerLocalDataSource
import com.ferbotz.billanta.data.local.InvoiceLocalDataSource
import com.ferbotz.billanta.data.local.ProfileLocalDataSource
import com.ferbotz.billanta.data.local.SyncMetaLocal
import com.ferbotz.billanta.data.local.toDomain
import com.ferbotz.billanta.domain.model.CustomerRecord
import com.ferbotz.billanta.domain.model.InvoiceRecord
import com.ferbotz.billanta.session.AuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SyncStatus(
    val running: Boolean = false,
    val lastSuccessAtMillis: Long? = null,
    val lastError: AppError? = null,
) {
    val hasEverSynced: Boolean get() = lastSuccessAtMillis != null
}

/**
 * The offline-sync engine. Push-then-pull, in this order:
 *
 *  1. company + settings (PUT when dirty)
 *  2. customers: replay local ops (POST new / PATCH edited / DELETE tombstoned), then pull the
 *     full list and reconcile server-side deletions
 *  3. invoices: DELETE local tombstones, then `/invoices/sync` — dirty rows pushed in batches
 *     (server applies last-write-wins by `updatedAt`), pulled pages applied LWW locally, cursor
 *     persisted, draining while `hasMore`
 *  4. company + settings pulled back when clean
 *
 * Conflicts the server reports (e.g. duplicate invoice number) land on the row as `syncError`
 * and stop retrying; everything transient stays dirty for the next pass.
 */
class SyncManager(
    private val scope: CoroutineScope,
    private val api: BillantaApi,
    private val authState: StateFlow<AuthState>,
    private val invoiceLocal: InvoiceLocalDataSource,
    private val customerLocal: CustomerLocalDataSource,
    private val profileLocal: ProfileLocalDataSource,
    private val syncMeta: SyncMetaLocal,
    private val connectivity: ConnectivityObserver,
    private val clock: EpochClock,
) {
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val requests = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    private val syncMutex = Mutex()

    init {
        // Debounced trigger: rapid edits collapse into one sync, which runs outside collectLatest
        // so an in-flight sync is never cancelled by the next request.
        scope.launch {
            requests.collectLatest { debounceMillis ->
                delay(debounceMillis)
                scope.launch { syncNow() }
            }
        }
        // Sync when a session appears (sign-in or app start with a restored session).
        scope.launch {
            authState.collect { state -> if (state is AuthState.SignedIn) requestSync(immediate = true) }
        }
        // Retry when connectivity returns and something is waiting.
        scope.launch {
            connectivity.isOnline.collect { online ->
                if (online && hasPendingWork()) requestSync(immediate = true)
            }
        }
    }

    /** Nudge from repositories after a local mutation. */
    fun requestSync(immediate: Boolean = false) {
        requests.tryEmit(if (immediate) 0L else DEBOUNCE_MILLIS)
    }

    /** Runs a full sync immediately (serialized — concurrent calls queue up). */
    suspend fun syncNow(): AppResult<Unit> = syncMutex.withLock {
        if (authState.value !is AuthState.SignedIn) return@withLock AppResult.Success(Unit)
        _status.value = _status.value.copy(running = true)
        val error = try {
            doFullSync()
        } catch (e: CancellationException) {
            _status.value = _status.value.copy(running = false)
            throw e
        } catch (e: Throwable) {
            AppError.Unexpected(e.message)
        }
        _status.value = if (error == null) {
            SyncStatus(running = false, lastSuccessAtMillis = clock.nowMillis(), lastError = null)
        } else {
            _status.value.copy(running = false, lastError = error)
        }
        if (error == null) syncMeta.put(SyncMetaLocal.KEY_LAST_SYNC_AT, clock.nowMillis().toString())
        return@withLock error?.let { AppResult.Failure(it) } ?: AppResult.Success(Unit)
    }

    /** Best-effort through all steps; returns the first error (null = clean pass). */
    private suspend fun doFullSync(): AppError? {
        var firstError: AppError? = null
        fun note(error: AppError?) {
            if (firstError == null && error != null) firstError = error
        }

        note(pushCompany())
        note(pushSettings())
        note(pushCustomers())
        note(pullCustomers())
        note(pushInvoiceTombstones())
        note(syncInvoicesWithServer())
        note(pullCompanyIfClean())
        note(pullSettingsIfClean())
        return firstError
    }

    // ---- company / settings --------------------------------------------------------------------

    private suspend fun pushCompany(): AppError? {
        if (!profileLocal.isCompanyDirty()) return null
        val company = profileLocal.getCompany() ?: return null
        return when (val result = api.putCompany(company.toDto())) {
            is AppResult.Success -> {
                profileLocal.markCompanyClean()
                null
            }
            is AppResult.Failure -> result.error
        }
    }

    private suspend fun pushSettings(): AppError? {
        if (!profileLocal.isSettingsDirty()) return null
        val settings = profileLocal.getSettings() ?: return null
        return when (val result = api.putSettings(settings.toDto())) {
            is AppResult.Success -> {
                profileLocal.markSettingsClean()
                null
            }
            is AppResult.Failure -> result.error
        }
    }

    private suspend fun pullCompanyIfClean(): AppError? {
        if (profileLocal.isCompanyDirty()) return null
        return when (val result = api.getCompany()) {
            is AppResult.Success -> {
                val dto = result.value
                if (dto != null && !profileLocal.isCompanyDirty()) {
                    profileLocal.saveCompany(dto.toDomain(), dirty = false, updatedAtMillis = clock.nowMillis())
                }
                null
            }
            is AppResult.Failure -> result.error
        }
    }

    private suspend fun pullSettingsIfClean(): AppError? {
        if (profileLocal.isSettingsDirty()) return null
        return when (val result = api.getSettings()) {
            is AppResult.Success -> {
                if (!profileLocal.isSettingsDirty()) {
                    profileLocal.saveSettings(result.value.toDomain(), dirty = false, updatedAtMillis = clock.nowMillis())
                }
                null
            }
            is AppResult.Failure -> result.error
        }
    }

    // ---- customers -----------------------------------------------------------------------------

    private suspend fun pushCustomers(): AppError? {
        var firstError: AppError? = null
        for (row in customerLocal.dirtyRows()) {
            val record = row.toDomain()
            val error: AppError? = if (row.isDeleted != 0L) {
                when (val result = api.deleteCustomer(row.id)) {
                    is AppResult.Success -> {
                        customerLocal.hardDelete(row.id)
                        null
                    }
                    is AppResult.Failure -> {
                        val err = result.error
                        if (err is AppError.Http && err.status == 404) {
                            customerLocal.hardDelete(row.id) // already gone server-side
                            null
                        } else err
                    }
                }
            } else if (row.isSynced != 0L) {
                when (val result = api.patchCustomer(row.id, record.toPatchBody())) {
                    is AppResult.Success -> {
                        customerLocal.markSynced(row.id, row.updatedAtMillis)
                        null
                    }
                    is AppResult.Failure -> {
                        val err = result.error
                        if (err is AppError.Http && err.status == 404) {
                            createCustomerOnServer(record, row.updatedAtMillis) // server lost it — recreate
                        } else err
                    }
                }
            } else {
                createCustomerOnServer(record, row.updatedAtMillis)
            }
            if (firstError == null && error != null) firstError = error
        }
        return firstError
    }

    private suspend fun createCustomerOnServer(
        record: CustomerRecord,
        rowUpdatedAtMillis: Long,
    ): AppError? = when (val result = api.createCustomer(record.toDto())) {
        is AppResult.Success -> {
            customerLocal.markSynced(record.id, rowUpdatedAtMillis)
            null
        }
        is AppResult.Failure -> result.error
    }

    private suspend fun pullCustomers(): AppError? {
        val serverIds = mutableSetOf<String>()
        var cursor: String? = null
        while (true) {
            val page = when (val result = api.listCustomers(limit = 100, cursor = cursor)) {
                is AppResult.Success -> result.value
                is AppResult.Failure -> return result.error
            }
            for (dto in page.items) {
                val record = try {
                    dto.toDomain(fallbackUpdatedAtMillis = clock.nowMillis())
                } catch (_: IllegalArgumentException) {
                    continue
                }
                serverIds += record.id
                customerLocal.applyServer(record)
            }
            cursor = page.nextCursor
            if (!page.hasMore || cursor == null) break
        }
        // Full page-through succeeded → anything synced+clean that the server no longer has was
        // deleted from another device.
        (customerLocal.syncedCleanIds().toSet() - serverIds).forEach { customerLocal.hardDelete(it) }
        return null
    }

    // ---- invoices ------------------------------------------------------------------------------

    /** Local soft-deletes replay as DELETE /invoices/:id (idempotent; 404 counts as done). */
    private suspend fun pushInvoiceTombstones(): AppError? {
        var firstError: AppError? = null
        val tombstones = invoiceLocal.dirtyRecords().filter { it.deletedAtMillis != null }
        for (record in tombstones) {
            when (val result = api.deleteInvoice(record.id)) {
                is AppResult.Success -> invoiceLocal.hardDelete(record.id)
                is AppResult.Failure -> {
                    val err = result.error
                    if (err is AppError.Http && err.status == 404) {
                        invoiceLocal.hardDelete(record.id)
                    } else if (firstError == null) {
                        firstError = err
                    }
                }
            }
        }
        return firstError
    }

    /** The `/invoices/sync` conversation: push dirty batches, then drain the pull cursor. */
    private suspend fun syncInvoicesWithServer(): AppError? {
        val dirty = invoiceLocal.dirtyRecords().filter { it.deletedAtMillis == null }
        val batches = dirty.chunked(PUSH_BATCH_SIZE)
        var since: String? = syncMeta.get(SyncMetaLocal.KEY_INVOICE_CURSOR)
        var batchIndex = 0

        while (true) {
            val push = batches.getOrNull(batchIndex)?.map { it.toDto() } ?: emptyList()
            val response = when (val result = api.syncInvoices(SyncRequestDto(invoices = push, since = since))) {
                is AppResult.Success -> result.value
                is AppResult.Failure -> return result.error
            }

            // Terminal conflicts (duplicate number, malformed, cross-user id): record and stop
            // retrying — the row keeps its data plus a visible syncError.
            val conflictIds = response.conflicts.map { it.id }.toSet()
            response.conflicts.forEach { conflict ->
                invoiceLocal.setSyncError(conflict.id, conflict.reason ?: "Sync conflict")
            }

            // Everything else in the batch was applied (or LWW-skipped, in which case the server's
            // newer copy arrives via `changed` below). Guarded by updatedAt so a mid-flight edit
            // stays dirty.
            for (dto in push) {
                if (dto.id in conflictIds) continue
                val pushedAt = dto.updatedAt?.let { Iso8601.parseOrNull(it) } ?: continue
                invoiceLocal.markClean(dto.id, pushedAt)
            }

            response.changed.forEach { applyServerInvoice(it.toDomain()) }

            response.nextCursor?.let {
                since = it
                syncMeta.put(SyncMetaLocal.KEY_INVOICE_CURSOR, it)
            }

            batchIndex++
            val morePushes = batchIndex < batches.size
            if (!response.hasMore && !morePushes) break
        }
        return null
    }

    /** Last-write-wins apply: local unpushed edits that are newer survive; everything else takes the server copy. */
    private suspend fun applyServerInvoice(server: InvoiceRecord) {
        val local = invoiceLocal.getById(server.id)
        if (local != null && local.pendingSync && local.updatedAtMillis > server.updatedAtMillis) return
        invoiceLocal.upsert(server, dirty = false)
    }

    // ---- misc ----------------------------------------------------------------------------------

    private suspend fun hasPendingWork(): Boolean =
        invoiceLocal.dirtyRecords().isNotEmpty() ||
            customerLocal.dirtyRows().isNotEmpty() ||
            profileLocal.isCompanyDirty() ||
            profileLocal.isSettingsDirty()

    private companion object {
        const val DEBOUNCE_MILLIS = 1_500L
        const val PUSH_BATCH_SIZE = 100
    }
}
