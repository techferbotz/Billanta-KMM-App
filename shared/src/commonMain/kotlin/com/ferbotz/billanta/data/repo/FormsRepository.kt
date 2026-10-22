package com.ferbotz.billanta.data.repo

import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.core.EpochClock
import com.ferbotz.billanta.core.logWarn
import com.ferbotz.billanta.data.api.BillantaApi
import com.ferbotz.billanta.data.local.FormsCacheLocal
import com.ferbotz.billanta.fields.FormsDoc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The business profile, customer, product and settings forms (`GET /forms`, FIELDS.md §5), from
 * which those four screens are built.
 *
 * There is always a document to show. A first launch can be offline and signed out, so the app
 * ships a copy, which is used until the first successful fetch replaces it (FIELDS_APP_GUIDE.md
 * §1). After that the cached copy is shown at once and revalidated with its ETag on every cold
 * start, so a field added on the backend appears without an app release.
 */
class FormsRepository(
    private val api: BillantaApi,
    private val cache: FormsCacheLocal,
    private val clock: EpochClock,
    /** The bundled `files/forms.json`, or null if it cannot be read. */
    private val bundled: suspend () -> String?,
) {
    private val _forms = MutableStateFlow(FormsDoc.EMPTY)
    val forms: StateFlow<FormsDoc> = _forms.asStateFlow()

    private var etag: String? = null

    /** Publishes the best local copy: the cached document, else the bundled one. */
    suspend fun load() {
        val cached = cache.get()
        val doc = cached?.json?.let(FormsDoc::parse)
        if (doc != null) {
            etag = cached.etag
            _forms.value = doc
            return
        }
        val shipped = try {
            bundled()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        shipped?.let(FormsDoc::parse)?.let { _forms.value = it }
            ?: logWarn(LOG_TAG, "no bundled forms document; screens wait for GET /forms")
    }

    /** Revalidates against the server. A `304` keeps what is shown; a document that fails to parse is ignored. */
    suspend fun refresh(): AppResult<Unit> = when (val result = api.getForms(etag)) {
        is AppResult.Failure -> result
        is AppResult.Success -> {
            when (val fetch = result.value) {
                BillantaApi.FormsFetch.NotModified -> Unit
                is BillantaApi.FormsFetch.Fetched -> {
                    val doc = FormsDoc.parse(fetch.json)
                    if (doc == null) {
                        logWarn(LOG_TAG, "GET /forms returned a document that does not parse; keeping the current one")
                    } else {
                        cache.put(fetch.json, fetch.etag, clock.nowMillis())
                        etag = fetch.etag
                        _forms.value = doc
                    }
                }
            }
            AppResult.Success(Unit)
        }
    }

    private companion object {
        const val LOG_TAG = "Forms"
    }
}
