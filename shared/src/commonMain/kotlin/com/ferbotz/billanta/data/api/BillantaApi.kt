package com.ferbotz.billanta.data.api

import com.ferbotz.billanta.core.AppError
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.core.asFailure
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** All protected/optional-auth endpoints, on the bearer-authed client. One method per API.md route. */
class BillantaApi(
    private val client: HttpClient,
    /**
     * The largest `?limit` the server accepts — `limits.maxPageSize` from `GET /config`. Every
     * list call is clamped to it so a page request that would be refused is never sent.
     */
    private val maxPageSize: () -> Int = { 100 },
) {

    private fun pageSize(requested: Int): Int = requested.coerceAtMost(maxPageSize()).coerceAtLeast(1)

    // ---- remote config -------------------------------------------------------------------------

    /**
     * `GET /config` (BE-014), as the raw body: it is read leaf by leaf, and cached verbatim so a
     * later build can re-read it with its own defaults. Public — a token, when there is one, only
     * lets the server apply per-user overrides.
     */
    suspend fun getConfigBody(): AppResult<String> = try {
        val response = client.get("config")
        if (response.status.isSuccess()) AppResult.Success(response.bodyAsText())
        else httpErrorOf(response).asFailure()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        AppError.Network(e.message).asFailure()
    }

    // ---- users ---------------------------------------------------------------------------------

    suspend fun getMe(): AppResult<UserDto> = apiCall { client.get("users/me") }

    suspend fun patchMe(patch: JsonObject): AppResult<UserDto> = apiCall {
        client.patch("users/me") {
            contentType(ContentType.Application.Json)
            setBody(patch)
        }
    }

    /** Permanently deletes the account and all server data. */
    suspend fun deleteMe(): AppResult<Unit> = apiCallUnit { client.delete("users/me") }

    // ---- customers -----------------------------------------------------------------------------

    suspend fun deleteCustomer(id: String): AppResult<Unit> = apiCallUnit { client.delete("customers/$id") }

    // ---- products ------------------------------------------------------------------------------

    /** Hard delete, as documented — there is no product tombstone. */
    suspend fun deleteProduct(id: String): AppResult<Unit> = apiCallUnit { client.delete("products/$id") }

    // ---- invoices ------------------------------------------------------------------------------

    /**
     * Soft delete (tombstone); idempotent.
     *
     * [deletedAtMillis] is the moment the user deleted it on this device. Sending it keeps the
     * tombstone's `updatedAt` in the same clock domain as every other edit and as the sync cursor,
     * which matters because a delete can sit in the queue for a long time before it is pushed.
     * Omitting it falls back to server time (BE-001).
     */
    suspend fun deleteInvoice(id: String, deletedAtMillis: Long? = null): AppResult<Unit> = apiCallUnit {
        client.delete("invoices/$id") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    deletedAtMillis?.let { put("updatedAt", JsonPrimitive(Iso8601.format(it))) }
                },
            )
        }
    }

    // ---- BE-021: forms and values maps ---------------------------------------------------------

    sealed interface FormsFetch {
        /** The forms document (without its envelope) and the ETag to revalidate it with. */
        data class Fetched(val json: String, val etag: String?) : FormsFetch
        data object NotModified : FormsFetch
    }

    /**
     * `GET /forms` (FIELDS.md §5). Public, so it works signed out. [ifNoneMatch] is the ETag exactly
     * as an earlier response sent it; a `304` comes back as [FormsFetch.NotModified].
     */
    suspend fun getForms(ifNoneMatch: String?): AppResult<FormsFetch> {
        val response = try {
            client.get("forms") { ifNoneMatch?.let { header(HttpHeaders.IfNoneMatch, it) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return AppError.Network(e.message).asFailure()
        }
        return try {
            when {
                response.status == HttpStatusCode.NotModified -> AppResult.Success(FormsFetch.NotModified)
                response.status.isSuccess() -> {
                    val env = response.body<Envelope<JsonObject>>()
                    val data = env.data
                    if (!env.success || data == null) {
                        AppError.Http(response.status.value, env.code, env.message).asFailure()
                    } else {
                        AppResult.Success(FormsFetch.Fetched(data.toString(), response.headers[HttpHeaders.ETag]))
                    }
                }
                else -> httpErrorOf(response).asFailure()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppError.Unexpected(e.message).asFailure()
        }
    }

    /** `data: null` until a profile exists. */
    suspend fun getCompanyValues(): AppResult<ValuesRecordDto?> = apiCallNullable { client.get("company") }

    /** Merges: a `null` value clears that key. Creates the profile when there is none yet. */
    suspend fun patchCompanyValues(body: ValuesBodyDto): AppResult<ValuesRecordDto> = apiCall {
        client.patch("company") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    /** Created on first read with the settings form's defaults. */
    suspend fun getSettingsValues(): AppResult<ValuesRecordDto> = apiCall { client.get("settings") }

    /** Merges. Never `PUT` settings: a full replace would clobber the running invoice number. */
    suspend fun patchSettingsValues(body: ValuesBodyDto): AppResult<ValuesRecordDto> = apiCall {
        client.patch("settings") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    /** Creates, or fully replaces, the customer with this id. */
    suspend fun createCustomerValues(customer: EntityValuesDto): AppResult<EntityValuesDto> = apiCall {
        client.post("customers") {
            contentType(ContentType.Application.Json)
            setBody(customer)
        }
    }

    suspend fun listCustomerValues(limit: Int = 100, cursor: String? = null): AppResult<PageDto<EntityValuesDto>> =
        apiCall {
            client.get("customers") {
                parameter("limit", pageSize(limit))
                cursor?.let { parameter("cursor", it) }
            }
        }

    suspend fun patchCustomerValues(id: String, body: ValuesBodyDto): AppResult<EntityValuesDto> = apiCall {
        client.patch("customers/$id") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    suspend fun createProductValues(product: EntityValuesDto): AppResult<EntityValuesDto> = apiCall {
        client.post("products") {
            contentType(ContentType.Application.Json)
            setBody(product)
        }
    }

    suspend fun listProductValues(limit: Int = 100, cursor: String? = null): AppResult<PageDto<EntityValuesDto>> =
        apiCall {
            client.get("products") {
                parameter("limit", pageSize(limit))
                cursor?.let { parameter("cursor", it) }
            }
        }

    suspend fun patchProductValues(id: String, body: ValuesBodyDto): AppResult<EntityValuesDto> = apiCall {
        client.patch("products/$id") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    /** Batch offline sync of values-map invoices: same mechanics as before, the BE-021 shape. */
    suspend fun syncInvoiceValues(request: InvoiceValuesSyncRequestDto): AppResult<InvoiceValuesSyncResponseDto> =
        apiCall {
            client.post("invoices/sync") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    // ---- templates (optional auth) -------------------------------------------------------------

    /** Cursor-paginated since BE-006, like every other list endpoint. */
    suspend fun listTemplates(limit: Int = 100, cursor: String? = null): AppResult<PageDto<TemplateDto>> =
        apiCall {
            client.get("templates") {
                parameter("limit", pageSize(limit))
                cursor?.let { parameter("cursor", it) }
            }
        }

    suspend fun getTemplate(id: String): AppResult<TemplateDto> = apiCall { client.get("templates/$id") }

    sealed interface CompiledFetch {
        /** The Billanta Template JSON document (raw) + its checksum ETag. */
        data class Fetched(val json: String, val etag: String?) : CompiledFetch
        data object NotModified : CompiledFetch
    }

    /**
     * Fetches the compiled render tree. Pass [ifNoneMatch] (the cached checksum) to revalidate —
     * a 304 comes back as [CompiledFetch.NotModified]. Premium templates need a premium session
     * (403 `PREMIUM_REQUIRED` otherwise).
     */
    suspend fun getCompiledTemplate(
        id: String,
        version: Long? = null,
        ifNoneMatch: String? = null,
    ): AppResult<CompiledFetch> {
        val response = try {
            client.get("templates/$id/compiled") {
                version?.let { parameter("version", it) }
                ifNoneMatch?.let { header(HttpHeaders.IfNoneMatch, "\"$it\"") }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return AppError.Network(e.message).asFailure()
        }
        return try {
            when {
                response.status == HttpStatusCode.NotModified ->
                    AppResult.Success(CompiledFetch.NotModified)
                response.status.isSuccess() -> {
                    val env = response.body<Envelope<JsonObject>>()
                    val data = env.data
                    if (!env.success || data == null) {
                        AppError.Http(response.status.value, env.code, env.message).asFailure()
                    } else {
                        val etag = response.headers[HttpHeaders.ETag]?.trim('"')
                        AppResult.Success(CompiledFetch.Fetched(json = data.toString(), etag = etag))
                    }
                }
                else -> httpErrorOf(response).asFailure()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppError.Unexpected(e.message).asFailure()
        }
    }

    // ---- media ---------------------------------------------------------------------------------

    /** Uploads an image; the server compresses to WebP and returns full + thumbnail URLs. */
    suspend fun uploadMedia(fileName: String, contentType: String, bytes: ByteArray): AppResult<MediaDto> =
        apiCall {
            client.post("media") {
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append(
                                "file",
                                bytes,
                                Headers.build {
                                    append(HttpHeaders.ContentType, contentType)
                                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                                },
                            )
                        },
                    ),
                )
            }
        }
}
