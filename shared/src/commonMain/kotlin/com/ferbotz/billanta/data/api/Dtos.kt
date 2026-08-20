package com.ferbotz.billanta.data.api

import com.ferbotz.billanta.domain.model.CompanySnapshot
import com.ferbotz.billanta.domain.model.CustomerSnapshot
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

// ---- auth --------------------------------------------------------------------------------------

@Serializable
data class GoogleSignInRequest(val idToken: String)

@Serializable
data class RefreshTokenRequest(val refreshToken: String)

@Serializable
data class AuthResponseDto(
    val accessToken: String,
    val refreshToken: String,
    /** Access-token lifetime in seconds (the server issues 900). */
    val expiresIn: Long = 900,
    val user: UserDto,
)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val name: String? = null,
    val photoUrl: String? = null,
    val isPremium: Boolean = false,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

// ---- company / settings ------------------------------------------------------------------------

/**
 * PUT /company is a full replace and "omitting a field clears it" — which matches our Json config
 * dropping nulls. Sending this DTO with nulls therefore clears those fields server-side.
 */
@Serializable
data class CompanyDto(
    // These property names ARE the wire names. Five of them differ from the domain model's, because
    // the template *binding* namespace (company.logo, .signature, .qr, .accountNumber, .ifsc) is a
    // render view the client assembles — it was never the REST shape. Inferring one from the other
    // silently cleared account number and IFSC on every save, since PUT /company is a full replace
    // and an unrecognised key reads as omitted. See BE-013.
    val name: String,
    val gstin: String? = null,
    val addressLine1: String? = null,
    val addressLine2: String? = null,
    val city: String? = null,
    val state: String? = null,
    val stateCode: String? = null,
    val pincode: String? = null,
    val country: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val logoUrl: String? = null,
    val signatureUrl: String? = null,
    val upiId: String? = null,
    val qrImageUrl: String? = null,
    val bankName: String? = null,
    val bankAccountNumber: String? = null,
    val bankIfsc: String? = null,
    val signatoryName: String? = null,
    val signatoryDesignation: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class SettingsDto(
    val defaultCurrency: String = "INR",
    val defaultTaxPercent: String = "18",
    val invoiceNumberPrefix: String = "",
    val nextInvoiceNumber: Long = 1,
    val defaultTemplateId: String? = null,
    val defaultNotes: String? = null,
    val updatedAt: String? = null,
)

// ---- customers ---------------------------------------------------------------------------------

@Serializable
data class CustomerDto(
    val id: String? = null,
    val name: String,
    val phone: String? = null,
    val email: String? = null,
    val gstin: String? = null,
    val addressLine1: String? = null,
    val addressLine2: String? = null,
    val city: String? = null,
    val state: String? = null,
    val stateCode: String? = null,
    val pincode: String? = null,
    val country: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

// ---- products ----------------------------------------------------------------------------------

/**
 * Same shape and rules as [CustomerDto]: client uuid, idempotent by (userId, id).
 *
 * `unitPrice` and `taxRatePercent` are force-encoded: our Json drops values equal to their default,
 * which would silently omit a "18" tax rate or a zero price and let the server substitute its own.
 * They keep defaults so a response that omits them still decodes.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ProductDto(
    val id: String? = null,
    val name: String,
    val hsnSac: String? = null,
    /** Paise, as a decimal string, like every other money field. */
    @EncodeDefault val unitPrice: String = "0",
    @EncodeDefault val taxRatePercent: String = "18",
    val unit: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

// ---- pagination --------------------------------------------------------------------------------

@Serializable
data class PageDto<T>(
    val items: List<T> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

// ---- invoices ----------------------------------------------------------------------------------

/** All money fields are decimal strings of paise, exactly as on the wire. */
@Serializable
data class InvoiceItemDto(
    val id: String? = null,
    val description: String,
    val hsnSac: String? = null,
    val quantity: String,
    val unitPrice: String,
    val taxRatePercent: String,
    val lineTotal: String? = null,
    val taxAmount: String? = null,
)

@Serializable
data class GstSplitDto(
    val intraState: Boolean = false,
    val cgst: String = "0",
    val sgst: String = "0",
    val igst: String = "0",
)

/**
 * Used in both directions. Outbound, the server ignores any totals we send and recomputes;
 * inbound, totals/gstSplit are the server's authoritative values. `updatedAt` is the client's
 * edit time and drives last-write-wins in `/invoices/sync`.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class InvoiceDto(
    val id: String,
    val invoiceNumber: String,
    val invoiceDate: String,
    val dueDate: String? = null,
    @EncodeDefault val currency: String = "INR",
    @EncodeDefault val status: String = "Draft",
    val templateId: String? = null,
    val templateVersion: Long? = null,
    val customerId: String? = null,
    val customerSnapshot: CustomerSnapshot? = null,
    val companySnapshot: CompanySnapshot? = null,
    val notes: String? = null,
    val discountType: String? = null,
    val discountValue: String? = null,
    /** Force-encoded: it selects the tax mode, so it must never be inferred. */
    @EncodeDefault val discountBeforeTax: Boolean = true,
    val items: List<InvoiceItemDto> = emptyList(),
    val subtotal: String? = null,
    val discountTotal: String? = null,
    val taxTotal: String? = null,
    val grandTotal: String? = null,
    val gstSplit: GstSplitDto? = null,
    /**
     * Per-invoice customisation (BE-003), stored verbatim by the server: token name → hex, and the
     * ids of sections switched off. Always sent — an empty map/list is how "cleared" is expressed,
     * since POST /invoices is a full replace.
     */
    val themeOverrides: Map<String, String>? = null,
    val hiddenSections: List<String>? = null,
    val pdfPath: String? = null,
    val deletedAt: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class SyncRequestDto(
    val invoices: List<InvoiceDto> = emptyList(),
    val since: String? = null,
)

@Serializable
data class SyncConflictDto(
    val id: String,
    val reason: String? = null,
)

@Serializable
data class SyncResponseDto(
    val changed: List<InvoiceDto> = emptyList(),
    val conflicts: List<SyncConflictDto> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

// ---- templates ---------------------------------------------------------------------------------

@Serializable
data class TemplateDto(
    val id: String,
    val name: String,
    val description: String? = null,
    val category: String? = null,
    val thumbnailUrl: String? = null,
    val isPremium: Boolean = false,
    val currentVersion: Long = 1,
    val checksum: String = "",
    val isActive: Boolean = true,
)

// ---- media -------------------------------------------------------------------------------------

@Serializable
data class MediaDto(
    val url: String,
    val thumbnailUrl: String? = null,
    val contentType: String? = null,
)
