package com.ferbotz.billanta.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.ferbotz.billanta.data.db.BillantaDb
import com.ferbotz.billanta.domain.model.CompiledTemplate
import com.ferbotz.billanta.domain.model.TemplateInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class TemplateLocalDataSource(
    private val db: BillantaDb,
    private val dispatcher: CoroutineDispatcher,
) {
    private val q get() = db.templatesQueries

    fun observeTemplates(): Flow<List<TemplateInfo>> =
        q.listTemplates().asFlow().mapToList(dispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    suspend fun getById(id: String): TemplateInfo? = withContext(dispatcher) {
        q.templateById(id).executeAsOneOrNull()?.toDomain()
    }

    /** Mirror of GET /templates: upserts the catalogue and drops templates the server removed. */
    suspend fun replaceCatalogue(templates: List<TemplateInfo>) = withContext(dispatcher) {
        db.transaction {
            if (templates.isEmpty()) {
                q.clearTemplates()
            } else {
                templates.forEachIndexed { idx, t ->
                    q.upsertTemplate(
                        id = t.id, name = t.name, category = t.category, thumbnailUrl = t.thumbnailUrl,
                        isPremium = t.isPremium.toDbLong(), currentVersion = t.currentVersion,
                        checksum = t.checksum, orderIdx = idx.toLong(),
                    )
                }
                q.deleteTemplatesNotIn(templates.map { it.id })
            }
        }
    }

    suspend fun getCompiled(templateId: String, version: Long): CompiledTemplate? =
        withContext(dispatcher) { q.compiledFor(templateId, version).executeAsOneOrNull()?.toDomain() }

    suspend fun putCompiled(compiled: CompiledTemplate, fetchedAtMillis: Long) = withContext(dispatcher) {
        q.upsertCompiled(
            templateId = compiled.templateId, version = compiled.version,
            checksum = compiled.checksum, json = compiled.json, fetchedAtMillis = fetchedAtMillis,
        )
    }

    suspend fun clearAll() = withContext(dispatcher) {
        db.transaction {
            q.clearTemplates()
            q.clearCompiled()
        }
    }
}
