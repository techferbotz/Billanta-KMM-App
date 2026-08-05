package com.ferbotz.billanta.data.repo

import com.ferbotz.billanta.core.AppError
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.core.EpochClock
import com.ferbotz.billanta.core.asFailure
import com.ferbotz.billanta.core.asSuccess
import com.ferbotz.billanta.core.flatMap
import com.ferbotz.billanta.data.api.BillantaApi
import com.ferbotz.billanta.data.api.toDomain
import com.ferbotz.billanta.data.local.TemplateLocalDataSource
import com.ferbotz.billanta.domain.model.CompiledTemplate
import com.ferbotz.billanta.domain.model.TemplateInfo
import kotlinx.coroutines.flow.Flow

/**
 * Template catalogue + compiled render-tree cache. A `(templateId, version)` pair is immutable
 * server-side, so once a compiled tree is cached it never needs refetching.
 */
class TemplateRepository(
    private val local: TemplateLocalDataSource,
    private val api: BillantaApi,
    private val clock: EpochClock,
) {

    fun observeTemplates(): Flow<List<TemplateInfo>> = local.observeTemplates()

    /** Pulls GET /templates into the local catalogue. Offline failure is non-fatal. */
    suspend fun refreshCatalogue(): AppResult<List<TemplateInfo>> =
        api.listTemplates().flatMap { list ->
            val templates = list.items.map { it.toDomain() }
            local.replaceCatalogue(templates)
            templates.asSuccess()
        }

    /**
     * The compiled Billanta Template JSON for a template, cache-first:
     * cached (id, version) → done; otherwise fetch (revalidating with the checksum as ETag) and
     * cache forever. Premium templates surface `PREMIUM_REQUIRED` for the paywall.
     */
    suspend fun getCompiled(templateId: String, version: Long? = null): AppResult<CompiledTemplate> {
        // Resolve which version "current" means, locally if possible.
        val info = local.getById(templateId) ?: run {
            when (val fetched = api.getTemplate(templateId)) {
                is AppResult.Success -> fetched.value.toDomain().also {
                    // Not part of the catalogue refresh — cache the single row opportunistically.
                    local.replaceCatalogue(listOf(it))
                }
                is AppResult.Failure -> null
            }
        }
        val resolvedVersion = version ?: info?.currentVersion
            ?: return AppError.Network("Template $templateId unavailable offline").asFailure()

        local.getCompiled(templateId, resolvedVersion)?.let { return it.asSuccess() }

        return when (val result = api.getCompiledTemplate(templateId, resolvedVersion)) {
            is AppResult.Failure -> result
            is AppResult.Success -> when (val fetch = result.value) {
                is BillantaApi.CompiledFetch.NotModified ->
                    // Only possible when we sent an ETag, which requires a cache hit — unreachable
                    // here, but handle it defensively.
                    local.getCompiled(templateId, resolvedVersion)?.asSuccess()
                        ?: AppError.Unexpected("304 without a cached tree").asFailure()
                is BillantaApi.CompiledFetch.Fetched -> {
                    val compiled = CompiledTemplate(
                        templateId = templateId,
                        version = resolvedVersion,
                        checksum = fetch.etag ?: info?.checksum ?: "",
                        json = fetch.json,
                    )
                    local.putCompiled(compiled, fetchedAtMillis = clock.nowMillis())
                    compiled.asSuccess()
                }
            }
        }
    }
}
