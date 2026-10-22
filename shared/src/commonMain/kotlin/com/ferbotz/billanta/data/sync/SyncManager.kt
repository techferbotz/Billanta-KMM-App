package com.ferbotz.billanta.data.sync

import com.ferbotz.billanta.config.RemoteConfig
import com.ferbotz.billanta.core.AppError
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.core.BillantaJson
import com.ferbotz.billanta.core.ConnectivityObserver
import com.ferbotz.billanta.core.EpochClock
import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.core.logWarn
import com.ferbotz.billanta.data.api.BillantaApi
import com.ferbotz.billanta.data.api.InvoiceValuesDto
import com.ferbotz.billanta.data.api.InvoiceValuesSyncRequestDto
import com.ferbotz.billanta.data.api.patchBody
import com.ferbotz.billanta.data.api.toCustomer
import com.ferbotz.billanta.data.api.toDto
import com.ferbotz.billanta.data.api.toInvoice
import com.ferbotz.billanta.data.api.toProduct
import com.ferbotz.billanta.data.local.CustomerValuesLocal
import com.ferbotz.billanta.data.local.InvoiceValuesLocal
import com.ferbotz.billanta.data.local.ProductValuesLocal
import com.ferbotz.billanta.data.local.SingletonValuesLocal
import com.ferbotz.billanta.data.local.SyncMetaLocal
import com.ferbotz.billanta.data.local.toCustomer
import com.ferbotz.billanta.data.local.toProduct
import com.ferbotz.billanta.fields.FieldValues
import com.ferbotz.billanta.records.Invoice
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
 * The offline-sync engine for BE-021's values maps. Push-then-pull, in the same order as before:
 *
 *  1. the business profile and the settings: a `PATCH` of just the keys changed since the last
 *     push, `null` for a cleared one, so two devices editing different fields both win
 *  2. customers, then products: replay local ops (`POST` the whole map for a record the server has
 *     never seen, `PATCH` the changed keys otherwise, `DELETE` a removal), then pull every page and
 *     reconcile deletions made on another device
 *  3. invoices: `DELETE` local tombstones, then `/invoices/sync`. Pushes go in batches under both
 *     `limits.maxSyncBatchInvoices` and the 2 MB request cap; pulled pages apply last-write-wins
 *  4. the profile and settings pulled back when clean
 *
 * A record over the size limits in `GET /config` is never sent: the server would only refuse it.
 * An invoice says why on its own row; the others are logged. Remembered values are not synced and
 * never reach this class.
 */
class SyncManager(
    private val scope: CoroutineScope,
    private val api: BillantaApi,
    private val authState: StateFlow<AuthState>,
    private val singletons: SingletonValuesLocal,
    private val customers: CustomerValuesLocal,
    private val products: ProductValuesLocal,
    private val invoices: InvoiceValuesLocal,
    private val syncMeta: SyncMetaLocal,
    private val connectivity: ConnectivityObserver,
    private val clock: EpochClock,
    /** Ends the session when the server says the account behind it is gone (BE-010). */
    private val onAccountVanished: suspend () -> Unit = {},
    /** `features.cloudSync`: off leaves everything local and dirty for when it comes back. */
    private val syncEnabled: () -> Boolean = { true },
    private val limits: () -> RemoteConfig.Limits = { RemoteConfig.Limits() },
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
        scope.launch {
            authState.collect { state -> if (state is AuthState.SignedIn) requestSync(immediate = true) }
        }
        scope.launch {
            connectivity.isOnline.collect { online -> if (online && hasPendingWork()) requestSync(immediate = true) }
        }
    }

    /** Nudge from repositories after a local mutation. */
    fun requestSync(immediate: Boolean = false) {
        requests.tryEmit(if (immediate) 0L else DEBOUNCE_MILLIS)
    }

    /** Runs a full sync now (serialized: concurrent calls queue up). */
    suspend fun syncNow(): AppResult<Unit> = syncMutex.withLock {
        if (authState.value !is AuthState.SignedIn) return@withLock AppResult.Success(Unit)
        if (!syncEnabled()) return@withLock AppResult.Success(Unit)
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

    /** Best-effort through every step, each logged by name when it fails; returns the first error. */
    private suspend fun doFullSync(): AppError? {
        var firstError: AppError? = null
        suspend fun step(name: String, block: suspend () -> AppError?) {
            if ((firstError as? AppError.Http)?.isAccountGone == true) return
            val error = block()
            if (error != null) {
                logWarn(LOG_TAG, "$name failed — ${error.diagnostic()}")
                if (firstError == null) firstError = error
            }
        }

        step("pushProfile") { pushProfile() }
        step("pushSettings") { pushSettings() }
        step("pushCustomers") { pushCustomers() }
        step("pullCustomers") { pullCustomers() }
        val justPushedProducts = mutableSetOf<String>()
        step("pushProducts") { pushProducts(justPushedProducts) }
        step("pullProducts") { pullProducts(justPushedProducts) }
        step("pushInvoiceTombstones") { pushInvoiceTombstones() }
        step("syncInvoices") { syncInvoices() }
        step("pullProfile") { pullProfileIfClean() }
        step("pullSettings") { pullSettingsIfClean() }

        (firstError as? AppError.Http)?.takeIf { it.isAccountGone }?.let {
            logWarn(LOG_TAG, "the signed-in account no longer exists on the server; signing out")
            onAccountVanished()
        }
        return firstError
    }

    // ---- profile / settings --------------------------------------------------------------------

    private suspend fun pushProfile(): AppError? {
        val profile = singletons.getProfile()?.takeIf { it.dirtyKeys.isNotEmpty() } ?: return null
        if (!fits(profile.values, "profile")) return null
        return when (val result = api.patchCompanyValues(profile.values.patchBody(profile.dirtyKeys))) {
            is AppResult.Success -> {
                singletons.markProfileClean(profile.updatedAtMillis)
                null
            }
            is AppResult.Failure -> result.error
        }
    }

    private suspend fun pushSettings(): AppError? {
        val settings = singletons.getSettings()?.takeIf { it.dirtyKeys.isNotEmpty() } ?: return null
        if (!fits(settings.values, "settings")) return null
        return when (val result = api.patchSettingsValues(settings.values.patchBody(settings.dirtyKeys))) {
            is AppResult.Success -> {
                singletons.markSettingsClean(settings.updatedAtMillis)
                null
            }
            is AppResult.Failure -> result.error
        }
    }

    private suspend fun pullProfileIfClean(): AppError? {
        if (singletons.getProfile()?.dirtyKeys?.isNotEmpty() == true) return null
        return when (val result = api.getCompanyValues()) {
            is AppResult.Success -> {
                result.value?.let { dto ->
                    singletons.applyServerProfile(
                        FieldValues.of(dto.values),
                        dto.createdAt?.let(Iso8601::parseOrNull),
                        dto.updatedAt?.let(Iso8601::parseOrNull) ?: clock.nowMillis(),
                    )
                }
                null
            }
            is AppResult.Failure -> result.error
        }
    }

    private suspend fun pullSettingsIfClean(): AppError? {
        if (singletons.getSettings()?.dirtyKeys?.isNotEmpty() == true) return null
        return when (val result = api.getSettingsValues()) {
            is AppResult.Success -> {
                val dto = result.value
                singletons.applyServerSettings(
                    FieldValues.of(dto.values),
                    dto.createdAt?.let(Iso8601::parseOrNull),
                    dto.updatedAt?.let(Iso8601::parseOrNull) ?: clock.nowMillis(),
                )
                null
            }
            is AppResult.Failure -> result.error
        }
    }

    // ---- customers -----------------------------------------------------------------------------

    private suspend fun pushCustomers(): AppError? {
        var firstError: AppError? = null
        for (row in customers.dirtyRows()) {
            val customer = row.toCustomer()
            val error: AppError? = when {
                row.isDeleted != 0L -> deleteRemotely({ api.deleteCustomer(row.id) }) { customers.hardDelete(row.id) }
                !fits(customer.values, "customer ${row.id}") -> null
                row.isSynced != 0L && customer.dirtyKeys.isNotEmpty() ->
                    when (val result = api.patchCustomerValues(row.id, customer.values.patchBody(customer.dirtyKeys))) {
                        is AppResult.Success -> {
                            customers.markSynced(row.id, row.updatedAtMillis)
                            null
                        }
                        // The server lost it: put the whole record back.
                        is AppResult.Failure -> if (result.error.isNotFound) createCustomer(row.id, row.updatedAtMillis) else result.error
                    }
                else -> createCustomer(row.id, row.updatedAtMillis)
            }
            if (firstError == null && error != null) firstError = error
        }
        return firstError
    }

    private suspend fun createCustomer(id: String, rowUpdatedAtMillis: Long): AppError? {
        val customer = customers.getById(id) ?: return null
        return when (val result = api.createCustomerValues(customer.toDto())) {
            is AppResult.Success -> {
                customers.markSynced(id, rowUpdatedAtMillis)
                null
            }
            is AppResult.Failure -> result.error
        }
    }

    private suspend fun pullCustomers(): AppError? {
        val serverIds = mutableSetOf<String>()
        var cursor: String? = null
        while (true) {
            val page = when (val result = api.listCustomerValues(limit = 100, cursor = cursor)) {
                is AppResult.Success -> result.value
                is AppResult.Failure -> return result.error
            }
            page.items.forEach { dto ->
                serverIds += dto.id
                customers.applyServer(dto.toCustomer(fallbackUpdatedAtMillis = clock.nowMillis()))
            }
            cursor = page.nextCursor
            if (!page.hasMore || cursor == null) break
        }
        // A full page-through succeeded, so anything synced and clean that the server no longer
        // has was deleted from another device.
        (customers.syncedCleanIds().toSet() - serverIds).forEach { customers.hardDelete(it) }
        return null
    }

    // ---- products ------------------------------------------------------------------------------

    private suspend fun pushProducts(pushedIds: MutableSet<String>): AppError? {
        var firstError: AppError? = null
        for (row in products.dirtyRows()) {
            val product = row.toProduct()
            val error: AppError? = when {
                row.isDeleted != 0L -> deleteRemotely({ api.deleteProduct(row.id) }) { products.hardDelete(row.id) }
                !fits(product.values, "product ${row.id}") -> null
                row.isSynced != 0L && product.dirtyKeys.isNotEmpty() ->
                    when (val result = api.patchProductValues(row.id, product.values.patchBody(product.dirtyKeys))) {
                        is AppResult.Success -> {
                            products.markSynced(row.id, row.updatedAtMillis)
                            pushedIds += row.id
                            null
                        }
                        is AppResult.Failure ->
                            if (result.error.isNotFound) createProduct(row.id, row.updatedAtMillis, pushedIds) else result.error
                    }
                else -> createProduct(row.id, row.updatedAtMillis, pushedIds)
            }
            if (firstError == null && error != null) firstError = error
        }
        return firstError
    }

    private suspend fun createProduct(id: String, rowUpdatedAtMillis: Long, pushedIds: MutableSet<String>): AppError? {
        val product = products.getById(id) ?: return null
        return when (val result = api.createProductValues(product.toDto())) {
            is AppResult.Success -> {
                products.markSynced(id, rowUpdatedAtMillis)
                pushedIds += id
                null
            }
            is AppResult.Failure -> result.error
        }
    }

    private suspend fun pullProducts(justPushed: Set<String>): AppError? {
        val serverIds = mutableSetOf<String>()
        var cursor: String? = null
        while (true) {
            val page = when (val result = api.listProductValues(limit = 100, cursor = cursor)) {
                is AppResult.Success -> result.value
                is AppResult.Failure -> return result.error
            }
            page.items.forEach { dto ->
                serverIds += dto.id
                products.applyServer(dto.toProduct(fallbackUpdatedAtMillis = clock.nowMillis()))
            }
            cursor = page.nextCursor
            if (!page.hasMore || cursor == null) break
        }
        // Ids pushed in this pass are shielded: a server that has not yet made the write visible
        // would otherwise look like a deletion.
        (products.syncedCleanIds().toSet() - serverIds - justPushed).forEach { products.hardDelete(it) }
        return null
    }

    // ---- invoices ------------------------------------------------------------------------------

    /** Local soft-deletes replay as `DELETE /invoices/:id` with the time the user deleted it. */
    private suspend fun pushInvoiceTombstones(): AppError? {
        var firstError: AppError? = null
        for (invoice in invoices.dirtyInvoices().filter { it.deletedAtMillis != null }) {
            val error = deleteRemotely({ api.deleteInvoice(invoice.id, invoice.deletedAtMillis) }) {
                invoices.hardDelete(invoice.id)
            }
            if (firstError == null && error != null) firstError = error
        }
        return firstError
    }

    /** The `/invoices/sync` conversation: push dirty batches, then drain the pull cursor. */
    private suspend fun syncInvoices(): AppError? {
        val batches = pushBatches(invoices.dirtyInvoices().filter { it.deletedAtMillis == null && it.syncError == null })
        var since: String? = syncMeta.get(SyncMetaLocal.KEY_INVOICE_CURSOR)
        var batchIndex = 0

        while (true) {
            val push = batches.getOrNull(batchIndex).orEmpty()
            val response = when (val result = api.syncInvoiceValues(InvoiceValuesSyncRequestDto(push, since))) {
                is AppResult.Success -> result.value
                is AppResult.Failure -> return result.error
            }

            // Terminal conflicts (a duplicate number, a malformed money input, a foreign id): the
            // row keeps its data with a visible syncError and stops retrying until edited.
            val conflictIds = response.conflicts.mapTo(HashSet()) { it.id }
            response.conflicts.forEach { conflict ->
                logWarn(LOG_TAG, "invoice ${conflict.id} rejected: ${conflict.reason ?: "no reason given"}")
                invoices.setSyncError(conflict.id, conflict.reason ?: "Sync conflict")
            }
            // Everything else was applied, or skipped as older, in which case the server's newer
            // copy arrives in `changed`. Guarded by updatedAt so an edit made meanwhile stays dirty.
            push.filter { it.id !in conflictIds }.forEach { dto ->
                dto.updatedAt?.let(Iso8601::parseOrNull)?.let { invoices.markClean(dto.id, it) }
            }

            response.changed.forEach { applyServerInvoice(it.toInvoice(fallbackUpdatedAtMillis = clock.nowMillis())) }

            response.nextCursor?.let {
                since = it
                syncMeta.put(SyncMetaLocal.KEY_INVOICE_CURSOR, it)
            }

            batchIndex++
            if (!response.hasMore && batchIndex >= batches.size) break
        }
        return null
    }

    /**
     * Splits the push under both caps: the server's batch size, and the request body limit, which
     * a few hundred invoices can now reach on their own. An invoice over the size limits is held
     * back with a syncError naming the limit, since the server would only refuse it.
     */
    private suspend fun pushBatches(dirty: List<Invoice>): List<List<InvoiceValuesDto>> {
        val limits = limits()
        val maxCount = PUSH_BATCH_SIZE.coerceAtMost(limits.maxSyncBatchInvoices).coerceAtLeast(1)
        val batches = mutableListOf<List<InvoiceValuesDto>>()
        var current = mutableListOf<InvoiceValuesDto>()
        var currentBytes = 0
        for (invoice in dirty) {
            val oversize = tooLarge(invoice, limits)
            if (oversize != null) {
                logWarn(LOG_TAG, "invoice ${invoice.id} not sent: $oversize")
                invoices.setSyncError(invoice.id, oversize)
                continue
            }
            val dto = invoice.toDto()
            val bytes = BillantaJson.encodeToString(InvoiceValuesDto.serializer(), dto).utf8Size()
            if (current.isNotEmpty() && (current.size >= maxCount || currentBytes + bytes > MAX_PUSH_BYTES)) {
                batches += current
                current = mutableListOf()
                currentBytes = 0
            }
            current += dto
            currentBytes += bytes
        }
        if (current.isNotEmpty()) batches += current
        return batches
    }

    private fun tooLarge(invoice: Invoice, limits: RemoteConfig.Limits): String? = when {
        invoice.lines.size > limits.maxLineItems -> "More than ${limits.maxLineItems} lines"
        invoice.values.toJsonString().utf8Size() > limits.maxValuesBytes -> "Too much text on this invoice"
        invoice.lines.any { it.values.toJsonString().utf8Size() > limits.maxLineValuesBytes } -> "A line has too much text"
        else -> null
    }

    /** The limits are in bytes, and ₹ or any Indic script takes several per character. */
    private fun String.utf8Size(): Int = encodeToByteArray().size

    /** Last-write-wins: a newer unpushed local edit survives; anything else takes the server copy. */
    private suspend fun applyServerInvoice(server: Invoice) {
        val local = invoices.getByIdIncludingDeleted(server.id)
        if (local != null && local.pendingSync && local.updatedAtMillis > server.updatedAtMillis) return
        if (server.deletedAtMillis != null) {
            invoices.hardDelete(server.id)
            return
        }
        invoices.upsert(server, dirty = false)
    }

    // ---- misc ----------------------------------------------------------------------------------

    /** A DELETE that counts `404` as done: the record is already gone, which is what we wanted. */
    private suspend fun deleteRemotely(call: suspend () -> AppResult<Unit>, onGone: suspend () -> Unit): AppError? =
        when (val result = call()) {
            is AppResult.Success -> {
                onGone()
                null
            }
            is AppResult.Failure -> if (result.error.isNotFound) {
                onGone()
                null
            } else {
                result.error
            }
        }

    /** Whether a values map fits `limits.maxValuesBytes`; one that does not is logged, never sent. */
    private fun fits(values: FieldValues, what: String): Boolean {
        val max = limits().maxValuesBytes
        val ok = values.toJsonString().utf8Size() <= max
        if (!ok) logWarn(LOG_TAG, "$what not sent: its values are over $max bytes")
        return ok
    }

    private suspend fun hasPendingWork(): Boolean =
        invoices.dirtyInvoices().isNotEmpty() ||
            customers.dirtyRows().isNotEmpty() ||
            products.dirtyRows().isNotEmpty() ||
            singletons.getProfile()?.dirtyKeys?.isNotEmpty() == true ||
            singletons.getSettings()?.dirtyKeys?.isNotEmpty() == true

    private val AppError.isNotFound: Boolean get() = this is AppError.Http && status == 404

    private companion object {
        const val DEBOUNCE_MILLIS = 1_500L
        const val PUSH_BATCH_SIZE = 100

        /** Under the server's 2 MB body cap, with room for the envelope and multi-byte text. */
        const val MAX_PUSH_BYTES = 1_500_000
        const val LOG_TAG = "Sync"
    }
}
