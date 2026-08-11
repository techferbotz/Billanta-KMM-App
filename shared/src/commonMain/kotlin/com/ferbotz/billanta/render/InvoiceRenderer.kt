package com.ferbotz.billanta.render

import com.ferbotz.billanta.domain.model.InvoiceRecord
import com.ferbotz.billanta.render.layout.LayoutEngine
import com.ferbotz.billanta.render.layout.Paginator
import com.ferbotz.billanta.render.layout.PlaceholderMode
import com.ferbotz.billanta.render.layout.RenderedDocument
import com.ferbotz.billanta.render.layout.SizePt
import com.ferbotz.billanta.render.layout.TemplateFlattener
import com.ferbotz.billanta.render.layout.imageUrls
import com.ferbotz.billanta.render.text.TextShaper

/**
 * The renderer's public face: a compiled template plus an invoice in, paginated draw commands out.
 *
 * Everything downstream — the on-screen preview, the PNG/JPEG rasteriser and the PDF writer —
 * consumes the returned [RenderedDocument], so the file a user shares is by construction the
 * document they previewed.
 */
class InvoiceRenderer(
    private val shaper: TextShaper,
    /** Intrinsic sizes of preloaded images by URL; see [imageUrlsFor]. */
    private val imageSizes: Map<String, SizePt> = emptyMap(),
) {

    fun render(
        doc: TemplateDoc,
        record: InvoiceRecord,
        theme: InvoiceTheme = InvoiceTheme.NONE,
        /**
         * Reserve space for sections with nothing in them when the invoice is being edited, so the
         * screen can offer a "tap to add" box. Exports always use [PlaceholderMode.None], so a
         * shared file never shows a gap where an optional section was left blank.
         */
        placeholders: PlaceholderMode = PlaceholderMode.None,
    ): RenderedDocument {
        val tree = TemplateFlattener.flatten(doc, contextFor(record), theme, placeholders)
        val engine = LayoutEngine(shaper, imageSizes)
        val root = engine.layout(tree, LayoutEngine.pageContentWidth(doc.page))
        return Paginator(engine).paginate(root, doc.page)
    }

    /**
     * Every image this invoice will draw. Exports must fetch these and hand their sizes back
     * through [imageSizes] before rendering, otherwise a logo that is still downloading is simply
     * missing from the shared file.
     */
    fun imageUrlsFor(doc: TemplateDoc, record: InvoiceRecord): List<String> =
        TemplateFlattener.flatten(doc, contextFor(record)).imageUrls()

    private fun contextFor(record: InvoiceRecord) =
        BindingContext(bindingDataFor(record), record.currency)
}

/**
 * Which of a template's sections have no data behind them yet, so the editor can offer a
 * "tap to add" box instead of an empty heading.
 *
 * Driven by what each section says it edits (APP-007), so a template that names its sections
 * differently still works. Sections that edit nothing, or whose data lives on the business profile
 * rather than the invoice, are never reported empty.
 */
fun emptySectionsFor(doc: TemplateDoc, record: InvoiceRecord): Set<String> =
    doc.sections.filter { section ->
        when (section.edits) {
            SectionEdits.Customer -> record.customerSnapshot == null
            SectionEdits.Items -> record.items.isEmpty()
            SectionEdits.Notes -> record.notes.isNullOrBlank()
            SectionEdits.InvoiceDetails -> record.invoiceNumber.isBlank()
            SectionEdits.Company -> record.companySnapshot == null
            // A discount is a choice, not a gap — an invoice without one is complete.
            SectionEdits.Discount, SectionEdits.None -> false
        }
    }.map { it.id }.toSet()
