package com.ferbotz.billanta.thumbs

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** How one look at an invoice's thumbnail ended. */
enum class ThumbnailOutcome {
    /** The picture on disk is right: it already was, or it has just been drawn. */
    Done,

    /** The invoice is gone, so there is nothing to draw. */
    Gone,

    /** Its template isn't downloaded, or an image is out of reach. Tried again on [ThumbnailScheduler.retry]. */
    Waiting,
}

/**
 * Decides which invoice's thumbnail to see to next, and when (FIELDS_APP_GUIDE.md §12.3).
 *
 * - **Rows on screen first**, in the order they appear. One that scrolls away before its turn is
 *   dropped.
 * - **A picture that goes stale redraws by itself**, [settleMillis] after its invoice last changed:
 *   an edit, a sync pull, a Draft following the profile. An invoice without a picture waits until it
 *   is on screen, so a new phone with fifty synced invoices draws only the first screen.
 * - **Nothing runs while an invoice editor is open**, or before there is a renderer. Stale pictures
 *   wait a further [settleMillis] after the editor closes.
 * - **One job at a time.**
 *
 * Each row comes with a stamp that changes whenever something drawn may have. A row is seen to
 * again only when its stamp differs from the one it was last seen to at, and seeing to it is cheap
 * when the picture turns out to be right.
 *
 * Every input is posted to [dispatcher], which must run one thing at a time; all state lives there.
 */
class ThumbnailScheduler(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val now: () -> Long,
    private val hasPicture: (invoiceId: String) -> Boolean,
    private val seeTo: suspend (invoiceId: String) -> ThumbnailOutcome,
    private val settleMillis: Long = 1_000,
) {
    private var stamps: Map<String, Any> = emptyMap()
    private val seenAt = HashMap<String, Any>()
    private val staleSince = LinkedHashMap<String, Long>()
    private val waiting = HashSet<String>()
    private var onScreen: List<String> = emptyList()
    private var editing = false
    private var hasRenderer = false
    private var holdUntil = 0L
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(dispatcher) { loop() }
    }

    /** Every invoice on the phone, with its current stamp. */
    fun rows(stamps: Map<String, Any>) = post {
        val time = now()
        stamps.forEach { (id, stamp) ->
            val before = this.stamps[id]
            if (before != null && before != stamp) {
                // Something drawn may have changed, so whatever kept it waiting may have too.
                waiting -= id
                if (hasPicture(id)) {
                    staleSince.remove(id)
                    staleSince[id] = time
                }
            }
        }
        this.stamps = stamps
        seenAt.keys.retainAll(stamps.keys)
        staleSince.keys.retainAll(stamps.keys)
        waiting.retainAll(stamps.keys)
    }

    /** The invoices on screen, top to bottom. */
    fun visible(ids: List<String>) = post {
        // A row coming back on screen is worth another try.
        ids.filterNot { it in onScreen }.forEach { waiting -= it }
        onScreen = ids
    }

    fun editorOpen(open: Boolean) = post {
        if (editing && !open) holdUntil = now() + settleMillis
        editing = open
    }

    /** Whether there is a renderer to draw with. */
    fun ready(ready: Boolean) = post { hasRenderer = ready }

    /** The template catalogue changed, or the connection came back: try what was waiting. */
    fun retry() = post { waiting.clear() }

    /** After the account-change wipe: nothing seen to is still on disk. */
    fun reset() = post {
        seenAt.clear()
        staleSince.clear()
        waiting.clear()
    }

    private fun post(change: () -> Unit) {
        scope.launch(dispatcher) {
            change()
            wake.trySend(Unit)
        }
    }

    private suspend fun loop() {
        while (true) {
            val job = next()
            if (job == null) {
                val wait = millisUntilStaleDue()
                if (wait == null) wake.receive() else withTimeoutOrNull(wait) { wake.receive() }
                continue
            }
            val (id, stamp) = job
            // A failure is a wait, never the end of the loop: the next retry tries again.
            val outcome = try {
                seeTo(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                ThumbnailOutcome.Waiting
            }
            when (outcome) {
                ThumbnailOutcome.Done, ThumbnailOutcome.Gone -> {
                    seenAt[id] = stamp
                    staleSince.remove(id)
                }
                ThumbnailOutcome.Waiting -> waiting += id
            }
        }
    }

    /** The stamp [id] still has to be seen to at, or null when it is up to date or held. */
    private fun due(id: String): Any? {
        val stamp = stamps[id] ?: return null
        return stamp.takeIf { seenAt[id] != it && id !in waiting }
    }

    private fun next(): Pair<String, Any>? {
        if (editing || !hasRenderer) return null
        onScreen.forEach { id -> due(id)?.let { return id to it } }
        val time = now()
        if (time < holdUntil) return null
        staleSince.forEach { (id, since) ->
            if (time - since >= settleMillis) due(id)?.let { return id to it }
        }
        return null
    }

    /** How long until a stale picture is due, or null when none is waiting on the clock. */
    private fun millisUntilStaleDue(): Long? {
        if (editing || !hasRenderer) return null
        val time = now()
        return staleSince.filterKeys { due(it) != null }.values
            .minOfOrNull { maxOf(it + settleMillis, holdUntil) }
            ?.let { (it - time).coerceAtLeast(1) }
    }
}
