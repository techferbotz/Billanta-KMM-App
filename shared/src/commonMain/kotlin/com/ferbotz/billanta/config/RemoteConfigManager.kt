package com.ferbotz.billanta.config

import com.ferbotz.billanta.core.AlwaysOnlineConnectivity
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.core.ConnectivityObserver
import com.ferbotz.billanta.core.KeyValueStore
import com.ferbotz.billanta.core.logWarn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Holds the remote config the app is currently obeying, and keeps it fresh.
 *
 * There is always a document, and it never gets worse: compiled-in defaults, then the last
 * document this install fetched (restored synchronously, before anything reads it), then this
 * process's own fetch. Offline, a 5xx or a malformed answer all leave what is already there in
 * place, silently.
 *
 * The cache belongs to the install, not to a session: it sits in the same store as the tokens but
 * under its own key, and signing out removes only the token keys — so logging out can never throw
 * away a kill switch.
 */
class RemoteConfigManager(
    /** `GET /config`, as the raw response body. */
    private val fetch: suspend () -> AppResult<String>,
    private val store: KeyValueStore,
    private val scope: CoroutineScope,
    private val connectivity: ConnectivityObserver = AlwaysOnlineConnectivity,
) {
    private val _document = MutableStateFlow(restore())
    val document: StateFlow<RemoteConfigDocument> = _document.asStateFlow()

    /** The config in force right now. */
    val current: RemoteConfig get() = _document.value.config

    private val refreshLock = Mutex()
    private var lastFetchFailed = false

    /**
     * Fetches on this cold start, then again whenever the document's TTL runs out.
     *
     * One addition to "cold start and TTL, nowhere else": a failed fetch is retried once the
     * network returns. Launching offline is ordinary for an offline-first app, and without this a
     * kill switch flipped during that time would wait out a whole TTL after the phone came back
     * online. It only ever completes a fetch that was due — it never adds one.
     */
    fun start() {
        scope.launch {
            while (true) {
                refresh()
                delay(_document.value.ttlSeconds.coerceIn(MIN_TTL_SECONDS, MAX_TTL_SECONDS) * 1_000)
            }
        }
        scope.launch {
            connectivity.isOnline.collect { online -> if (online && lastFetchFailed) refresh() }
        }
    }

    /** One fetch. True when a new document was applied. */
    suspend fun refresh(): Boolean = refreshLock.withLock {
        val body = when (val result = fetch()) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> {
                lastFetchFailed = true
                return@withLock false
            }
        }
        val parsed = parseRemoteConfig(body)
        if (parsed == null) {
            lastFetchFailed = true
            logWarn(TAG, "ignored a /config answer that is not a successful envelope; keeping ${_document.value.revision}")
            return@withLock false
        }
        lastFetchFailed = false
        // The raw body, not a re-encoding of what this build understood: a later build re-reads it
        // with its own defaults for any leaf this one did not know about.
        store.putString(KEY_DOCUMENT, body)
        apply(parsed)
        true
    }

    private fun restore(): RemoteConfigDocument {
        val cached = store.getString(KEY_DOCUMENT)?.let(::parseRemoteConfig)
        val document = cached ?: RemoteConfigDocument()
        logApplied(document, source = if (cached != null) "cache" else "defaults")
        return document
    }

    private fun apply(document: RemoteConfigDocument) {
        _document.value = document
        logApplied(document, source = "server")
    }

    /** The protocol asks for variant + revision to be logged with every applied document. */
    private fun logApplied(document: RemoteConfigDocument, source: String) {
        logWarn(
            TAG,
            "applied variant=${document.variant} revision=${document.revision} " +
                "schema=${document.schemaVersion} ttl=${document.ttlSeconds}s from $source",
        )
    }

    private companion object {
        const val TAG = "Config"
        const val KEY_DOCUMENT = "remoteConfig.document"

        /** A TTL of 0 from a bad patch would otherwise turn every install into a request loop. */
        const val MIN_TTL_SECONDS = 60L
        const val MAX_TTL_SECONDS = 86_400L
    }
}
