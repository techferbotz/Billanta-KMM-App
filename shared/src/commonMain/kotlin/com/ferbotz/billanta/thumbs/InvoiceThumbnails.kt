package com.ferbotz.billanta.thumbs

import com.ferbotz.billanta.core.ConnectivityObserver
import com.ferbotz.billanta.core.EpochClock
import com.ferbotz.billanta.core.InvoiceDateFormat
import com.ferbotz.billanta.core.logWarn
import com.ferbotz.billanta.data.repo.InvoiceRepository
import com.ferbotz.billanta.data.repo.TemplateDocs
import com.ferbotz.billanta.data.repo.TemplateRepository
import com.ferbotz.billanta.fields.FormsDoc
import com.ferbotz.billanta.records.Invoice
import com.ferbotz.billanta.render.paint.InvoiceImageLoader
import com.ferbotz.billanta.render.text.TextShaper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path

/**
 * The invoice list's pictures (BE-023, FIELDS_APP_GUIDE.md §12): page 1 of each invoice, drawn on
 * this phone with the app's own renderer, kept in its cache directory and never sent anywhere.
 *
 * The cards read [files]. The list says what is on screen, and the app says when an invoice editor
 * is open and how dates are written. [ThumbnailScheduler] decides what to draw when, and
 * [ThumbnailDrawer] draws it.
 */
class InvoiceThumbnails(
    /** Null turns thumbnails off: tests and previews have no cache directory. */
    private val store: ThumbnailStore?,
    private val invoices: InvoiceRepository,
    private val templates: TemplateRepository,
    private val templateDocs: TemplateDocs,
    private val forms: StateFlow<FormsDoc>,
    private val images: InvoiceImageLoader,
    connectivity: ConnectivityObserver,
    private val scope: CoroutineScope,
    clock: EpochClock,
    /** One at a time: the store and the scheduler live here, and drawing happens here. */
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    /** Invoice id → its picture. */
    val files: StateFlow<Map<String, Path>> = store?.files ?: MutableStateFlow(emptyMap())

    private val dateFormat = MutableStateFlow(InvoiceDateFormat.Default)
    private var drawer: ThumbnailDrawer? = null
    private val firstTimings = ArrayList<Long>()

    private val scheduler: ThumbnailScheduler? = store?.let { store ->
        ThumbnailScheduler(
            scope = scope,
            dispatcher = dispatcher,
            now = clock::nowMillis,
            hasPicture = { id -> id in store.files.value },
            seeTo = ::seeTo,
        )
    }

    init {
        if (store != null && scheduler != null) {
            scope.launch(dispatcher) { quietly("reading the folder") { store.load() } }
            scope.launch {
                combine(invoices.observeInvoices(), templates.observeTemplates(), forms, dateFormat) { rows, catalogue, forms, format ->
                    val current = catalogue.associate { it.id to it.currentVersion }
                    val optionsKey = ThumbnailFingerprint.optionListsKey(forms.optionLists)
                    rows.associate { row -> row.id to stampOf(row, current, format, optionsKey) }
                }.collect { stamps ->
                    scheduler.rows(stamps)
                    // A deleted invoice's picture goes with it, whether deleted here or by a pull.
                    withContext(dispatcher) { quietly("sweeping deleted invoices") { store.retainOnly(stamps.keys) } }
                }
            }
            // A template that arrives, or a connection that comes back, may be what something waits for.
            scope.launch { templates.observeTemplates().drop(1).collect { scheduler.retry() } }
            scope.launch { connectivity.isOnline.drop(1).filter { it }.collect { scheduler.retry() } }
        }
    }

    /** The renderer to draw with, from the UI once the fonts are loaded. Nothing is drawn before. */
    fun attach(shaper: TextShaper) {
        val scheduler = scheduler ?: return
        scope.launch(dispatcher) {
            drawer = ThumbnailDrawer(shaper, images::loadComplete)
            scheduler.ready(true)
        }
    }

    /** The invoices on screen, top to bottom; drawn first, in that order. */
    fun setVisible(invoiceIds: List<String>) {
        scheduler?.visible(invoiceIds)
    }

    /** Nothing is drawn while an invoice editor is open: every keystroke saves. */
    fun setEditorOpen(open: Boolean) {
        scheduler?.editorOpen(open)
    }

    /** The user's date format, which every picture prints. */
    fun setDateFormat(format: InvoiceDateFormat) {
        dateFormat.value = format
    }

    /** For the account-change wipe: the pictures go with the invoices they showed. */
    suspend fun clear() {
        val store = store ?: return
        withContext(dispatcher) { quietly("clearing the folder") { store.clear() } }
        scheduler?.reset()
    }

    private suspend fun seeTo(invoiceId: String): ThumbnailOutcome = try {
        drawIfStale(invoiceId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // A full disk, say. The list keeps its placeholder, and a later retry tries again.
        logWarn(TAG, "thumbnail not saved: ${e::class.simpleName} ${e.message}")
        ThumbnailOutcome.Waiting
    }

    private suspend fun drawIfStale(invoiceId: String): ThumbnailOutcome {
        val store = store ?: return ThumbnailOutcome.Done
        val drawer = drawer ?: return ThumbnailOutcome.Waiting
        val invoice = invoices.getInvoice(invoiceId) ?: return ThumbnailOutcome.Gone
        val template = templateDocs.resolve(invoice).getOrNull() ?: return ThumbnailOutcome.Waiting
        val forms = forms.value
        val format = dateFormat.value
        val fingerprint = ThumbnailFingerprint.of(
            invoice = invoice,
            templateId = template.templateId,
            templateVersion = template.version,
            dateFormat = format,
            optionListsKey = ThumbnailFingerprint.optionListsKey(forms.optionLists),
        )
        if (store.fingerprintOf(invoiceId) == fingerprint) return ThumbnailOutcome.Done
        val drawing = drawer.draw(invoice, template.doc, forms.optionLists, format) ?: return ThumbnailOutcome.Waiting
        store.write(invoiceId, fingerprint, drawing.jpeg)
        report(drawing, template.templateId)
        return ThumbnailOutcome.Done
    }

    /**
     * Changes whenever something drawn may have. The row has no lines, so its update time and line
     * summary stand in for them; the full invoice is read only when the row is seen to.
     */
    private fun stampOf(row: Invoice, currentVersions: Map<String, Long>, format: InvoiceDateFormat, optionsKey: String): Any =
        listOf(
            row.updatedAtMillis, row.values, row.computed, row.lineSummary,
            row.templateId, row.templateVersion, row.themeOverrides, row.hiddenSections,
            // A Draft is drawn on its template's current version, which moves without the row changing.
            if (row.isDraft) currentVersions[row.templateId] else null,
            format, optionsKey,
        )

    /**
     * The guide asks for a timing on a low-end phone (§12.3): a slow draw says so, and so does a
     * summary of the first few each run, so any phone's log has the numbers.
     */
    private fun report(drawing: ThumbnailDrawing, templateId: String) {
        if (drawing.totalMillis > SLOW_MILLIS) {
            logWarn(
                TAG,
                "slow thumbnail on $templateId: ${drawing.totalMillis} ms (layout ${drawing.layoutMillis}, " +
                    "paint ${drawing.paintMillis}, encode ${drawing.encodeMillis}); over $SLOW_MILLIS ms is an APP request",
            )
        }
        if (firstTimings.size < SUMMARY_AFTER) {
            firstTimings += drawing.totalMillis
            if (firstTimings.size == SUMMARY_AFTER) {
                val sorted = firstTimings.sorted()
                logWarn(TAG, "first $SUMMARY_AFTER thumbnails: median ${sorted[sorted.size / 2]} ms, slowest ${sorted.last()} ms")
            }
        }
    }

    /** The cache is a convenience: a file error is logged, and never what stops the app. */
    private inline fun quietly(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logWarn(TAG, "$what failed: ${e::class.simpleName} ${e.message}")
        }
    }

    private companion object {
        const val TAG = "Thumbnails"
        const val SLOW_MILLIS = 300L
        const val SUMMARY_AFTER = 8
    }
}
