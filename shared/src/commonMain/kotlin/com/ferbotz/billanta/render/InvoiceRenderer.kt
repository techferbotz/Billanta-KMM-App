package com.ferbotz.billanta.render

import com.ferbotz.billanta.core.InvoiceDateFormat
import com.ferbotz.billanta.fields.FieldOption
import com.ferbotz.billanta.fields.FieldType
import com.ferbotz.billanta.fields.isTruthy
import com.ferbotz.billanta.render.layout.LayoutEngine
import com.ferbotz.billanta.render.layout.Paginator
import com.ferbotz.billanta.render.layout.PlaceholderMode
import com.ferbotz.billanta.render.layout.RenderedDocument
import com.ferbotz.billanta.render.layout.SizePt
import com.ferbotz.billanta.render.layout.TemplateFlattener
import com.ferbotz.billanta.render.layout.imageUrls
import com.ferbotz.billanta.render.text.TextShaper
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

    /**
     * Draws a v2 tree from the invoice's render context (FIELDS.md §8.1, built by
     * [renderContextOf]). [optionLists] are `/forms` `optionLists`, so a select prints its label.
     */
    fun render(
        doc: TemplateDoc,
        context: JsonObject,
        optionLists: Map<String, List<FieldOption>>,
        theme: InvoiceTheme = InvoiceTheme.NONE,
        /**
         * Reserve space for sections with nothing in them when the invoice is being edited, so the
         * screen can offer a "tap to add" box. Exports always use [PlaceholderMode.None], so a
         * shared file never shows a gap where an optional section was left blank.
         */
        placeholders: PlaceholderMode = PlaceholderMode.None,
        /** How dates are written. A display choice, so it is the caller's to make. */
        dateFormat: InvoiceDateFormat = InvoiceDateFormat.Default,
    ): RenderedDocument = layOut(doc, bindingsFor(doc, context, optionLists, dateFormat), theme, placeholders)

    /**
     * Every image a v2 invoice will draw. Exports must fetch these and hand their sizes back
     * through [imageSizes] before rendering, otherwise a logo that is still downloading is simply
     * missing from the shared file.
     */
    fun imageUrlsFor(doc: TemplateDoc, context: JsonObject): List<String> =
        TemplateFlattener.flatten(doc, bindingsFor(doc, context, emptyMap(), InvoiceDateFormat.Default)).imageUrls()

    private fun layOut(
        doc: TemplateDoc,
        bindings: BindingContext,
        theme: InvoiceTheme,
        placeholders: PlaceholderMode,
    ): RenderedDocument {
        val tree = TemplateFlattener.flatten(doc, bindings, theme, placeholders)
        val engine = LayoutEngine(shaper, imageSizes)
        val root = engine.layout(tree, LayoutEngine.pageContentWidth(doc.page))
        return Paginator(engine).paginate(root, doc.page)
    }

    private fun bindingsFor(
        doc: TemplateDoc,
        context: JsonObject,
        optionLists: Map<String, List<FieldOption>>,
        dateFormat: InvoiceDateFormat,
    ) = BindingContext(
        root = context,
        // Money is printed in the invoice's own currency, which every new invoice carries (§5).
        currency = (context["currency"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "INR",
        dateFormat = dateFormat,
        selectLabels = SelectLabels(doc.sections, optionLists),
    )
}

/**
 * Which of a v2 template's sections have nothing in them yet, so the editor can offer a
 * "tap to add" box instead of an empty heading.
 *
 * A section is empty when none of its data fields has a truthy value. Switches, the discount,
 * totals and static text are not data a section can be missing, so a section made only of those —
 * a totals block, say — is never reported empty.
 */
fun emptySectionsFor(doc: TemplateDoc, context: JsonObject): Set<String> =
    doc.sections.filter { section ->
        val data = section.fields.filter { it.id != null && it.effectiveType !in NOT_DATA }
        data.isNotEmpty() && data.none { isTruthy(context[it.id!!]) }
    }.mapTo(LinkedHashSet()) { it.id }

private val NOT_DATA = setOf(FieldType.Bool, FieldType.Discount, FieldType.Computed, FieldType.Info)
