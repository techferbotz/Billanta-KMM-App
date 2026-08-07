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
class BillantaApi(private val client: HttpClient) {

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

    // ---- company -------------------------------------------------------------------------------

    /** `data: null` until the company is first set up. */
    suspend fun getCompany(): AppResult<CompanyDto?> = apiCallNullable { client.get("company") }

    suspend fun putCompany(company: CompanyDto): AppResult<CompanyDto> = apiCall {
        client.put("company") {
            contentType(ContentType.Application.Json)
            setBody(company)
        }
    }

    // ---- settings ------------------------------------------------------------------------------

    suspend fun getSettings(): AppResult<SettingsDto> = apiCall { client.get("settings") }

    suspend fun putSettings(settings: SettingsDto): AppResult<SettingsDto> = apiCall {
        client.put("settings") {
            contentType(ContentType.Application.Json)
            setBody(settings)
        }
    }

    // ---- customers -----------------------------------------------------------------------------

    suspend fun createCustomer(customer: CustomerDto): AppResult<CustomerDto> = apiCall {
        client.post("customers") {
            contentType(ContentType.Application.Json)
            setBody(customer)
        }
    }

    suspend fun listCustomers(q: String? = null, limit: Int = 100, cursor: String? = null): AppResult<PageDto<CustomerDto>> =
        apiCall {
            client.get("customers") {
                if (!q.isNullOrBlank()) parameter("q", q)
                parameter("limit", limit)
                cursor?.let { parameter("cursor", it) }
            }
        }

    suspend fun getCustomer(id: String): AppResult<CustomerDto> = apiCall { client.get("customers/$id") }

    /** Full-field patch body (explicit nulls) so cleared fields clear server-side too. */
    suspend fun patchCustomer(id: String, patch: JsonObject): AppResult<CustomerDto> = apiCall {
        client.patch("customers/$id") {
            contentType(ContentType.Application.Json)
            setBody(patch)
        }
    }

    suspend fun deleteCustomer(id: String): AppResult<Unit> = apiCallUnit { client.delete("customers/$id") }

    // ---- products ------------------------------------------------------------------------------

    suspend fun createProduct(product: ProductDto): AppResult<ProductDto> = apiCall {
        client.post("products") {
            contentType(ContentType.Application.Json)
            setBody(product)
        }
    }

    suspend fun listProducts(q: String? = null, limit: Int = 100, cursor: String? = null): AppResult<PageDto<ProductDto>> =
        apiCall {
            client.get("products") {
                if (!q.isNullOrBlank()) parameter("q", q)
                parameter("limit", limit)
                cursor?.let { parameter("cursor", it) }
            }
        }

    suspend fun patchProduct(id: String, patch: JsonObject): AppResult<ProductDto> = apiCall {
        client.patch("products/$id") {
            contentType(ContentType.Application.Json)
            setBody(patch)
        }
    }

    /** Hard delete, as documented — there is no product tombstone. */
    suspend fun deleteProduct(id: String): AppResult<Unit> = apiCallUnit { client.delete("products/$id") }

    // ---- invoices ------------------------------------------------------------------------------

    /** Create or idempotently replace by client id. Totals in the response are authoritative. */
    suspend fun upsertInvoice(invoice: InvoiceDto): AppResult<InvoiceDto> = apiCall {
        client.post("invoices") {
            contentType(ContentType.Application.Json)
            setBody(invoice)
        }
    }

    suspend fun listInvoices(
        status: String? = null,
        q: String? = null,
        limit: Int = 100,
        cursor: String? = null,
    ): AppResult<PageDto<InvoiceDto>> = apiCall {
        client.get("invoices") {
            status?.let { parameter("status", it) }
            if (!q.isNullOrBlank()) parameter("q", q)
            parameter("limit", limit)
            cursor?.let { parameter("cursor", it) }
        }
    }

    suspend fun getInvoice(id: String): AppResult<InvoiceDto> = apiCall { client.get("invoices/$id") }

    /** Quick scalar edits (status/notes/dueDate/pdfPath/invoiceDate/invoiceNumber/currency). */
    suspend fun patchInvoice(id: String, patch: JsonObject): AppResult<InvoiceDto> = apiCall {
        client.patch("invoices/$id") {
            contentType(ContentType.Application.Json)
            setBody(patch)
        }
    }

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

    /** Batch offline sync: pushes are LWW by `updatedAt`; pull pages via `since`/`nextCursor`. */
    suspend fun syncInvoices(request: SyncRequestDto): AppResult<SyncResponseDto> = apiCall {
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
                parameter("limit", limit)
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
