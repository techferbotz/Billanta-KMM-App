package com.ferbotz.billanta.data.repo

import com.ferbotz.billanta.core.AppError
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.core.asFailure
import com.ferbotz.billanta.records.Invoice
import com.ferbotz.billanta.render.TemplateDoc
import com.ferbotz.billanta.render.TemplateParser
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A compiled tree, with the template and version it really is — which a Draft's own fields may not say. */
data class ResolvedTemplate(val doc: TemplateDoc, val templateId: String, val version: Long)

/**
 * Which compiled tree an invoice is drawn with. The preview, the editor and the list thumbnails all
 * ask here, so a thumbnail can never show a different version from the invoice it stands for.
 *
 * A Draft follows the template's current version; an issued invoice keeps the version it was made
 * on, and falls back to the current one when that can't be fetched offline (FIELDS_APP_GUIDE.md
 * §10). A published version never changes, so parsed trees are kept per (id, version): the list
 * draws many invoices on the same one or two templates, and each tree is tens of kilobytes of JSON.
 */
class TemplateDocs(private val templates: TemplateRepository) {

    private val mutex = Mutex()
    private val parsed = LinkedHashMap<Pair<String, Long>, TemplateDoc>()

    /** [fallbackTemplateId] stands in for an invoice made before any template was downloaded. */
    suspend fun resolve(invoice: Invoice?, fallbackTemplateId: String? = null): AppResult<ResolvedTemplate> {
        val templateId = invoice?.templateId?.takeIf { it.isNotEmpty() } ?: fallbackTemplateId
            ?: return AppError.Validation("No templates yet — connect once to download them.").asFailure()
        val current = templates.currentVersion(templateId)
        val version = if (invoice == null || invoice.isDraft) current ?: invoice?.templateVersion else invoice.templateVersion ?: current
        val result = load(templateId, version)
        if (result is AppResult.Failure && version != null && version != current) {
            (load(templateId, null) as? AppResult.Success)?.let { return it }
        }
        return result
    }

    private suspend fun load(templateId: String, version: Long?): AppResult<ResolvedTemplate> {
        if (version != null) {
            mutex.withLock { parsed[templateId to version] }?.let { return AppResult.Success(ResolvedTemplate(it, templateId, version)) }
        }
        val compiled = when (val result = templates.getCompiled(templateId, version)) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> return result
        }
        val doc = TemplateParser.parse(compiled.json)
            ?: return AppError.Validation("This template can't be displayed — try updating the app.").asFailure()
        mutex.withLock {
            parsed[templateId to compiled.version] = doc
            while (parsed.size > MAX_PARSED) parsed.remove(parsed.keys.first())
        }
        return AppResult.Success(ResolvedTemplate(doc, templateId, compiled.version))
    }

    private companion object {
        /** A catalogue of a few templates, plus the odd older version an issued invoice keeps. */
        const val MAX_PARSED = 8
    }
}
