package com.ferbotz.billanta.thumbs

import com.ferbotz.billanta.core.InvoiceDateFormat
import com.ferbotz.billanta.fields.FieldOption
import com.ferbotz.billanta.records.Invoice
import okio.ByteString.Companion.encodeUtf8

/**
 * What a list thumbnail is filed under: a hash of everything that changes the picture
 * (FIELDS_APP_GUIDE.md §12.2). Equal fingerprints mean the cached picture is still right, which is
 * what lets signing back in, or a restart, draw nothing.
 */
object ThumbnailFingerprint {

    /**
     * Bump whenever layout or paint output changes, so every cached picture is drawn again by the
     * new renderer. Text alignment, placeholder rules, fonts, a new style key: all count.
     */
    const val RENDERER_VERSION = 1

    /**
     * [invoice] must carry its lines: the list's rows don't. [templateVersion] is the version
     * actually drawn — for a Draft that is the template's current one, not the invoice's own field.
     * [optionListsKey] is [optionListsKey] of the `/forms` lists, since a select prints its label.
     */
    fun of(
        invoice: Invoice,
        templateId: String,
        templateVersion: Long,
        dateFormat: InvoiceDateFormat,
        optionListsKey: String,
    ): String = sha(
        buildString {
            append("renderer ").append(RENDERER_VERSION).append('\n')
            append("template ").append(templateId).append('@').append(templateVersion).append('\n')
            append("dates ").append(dateFormat.id).append('\n')
            append("options ").append(optionListsKey).append('\n')
            append("colours ")
            invoice.themeOverrides.entries.sortedBy { it.key }.forEach { (token, hex) -> append(token).append('=').append(hex).append(';') }
            append('\n')
            append("hidden ").append(invoice.hiddenSections.sorted().joinToString(",")).append('\n')
            // Values, every line with what was calculated for it, and the totals: what is drawn.
            // Status is not drawn, so it is not here.
            append(invoice.renderContext().toString())
        },
    )

    /** A short stand-in for the `/forms` option lists, worked out once rather than per invoice. */
    fun optionListsKey(optionLists: Map<String, List<FieldOption>>): String = sha(optionLists.toString())

    private fun sha(text: String): String = text.encodeUtf8().sha256().hex().take(16)
}
