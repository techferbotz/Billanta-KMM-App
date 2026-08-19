package com.ferbotz.billanta.domain.model

import kotlinx.serialization.Serializable

/**
 * Company/customer state captured at issue time and stored ON the invoice, so it re-renders
 * identically forever. Field names match the template binding namespace exactly
 * (`company.*` / `customer.*` in TEMPLATE_JSON.md).
 */
@Serializable
data class CompanySnapshot(
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
    val logo: String? = null,
    val signature: String? = null,
    val upiId: String? = null,
    val qr: String? = null,
    val bankName: String? = null,
    val accountNumber: String? = null,
    val ifsc: String? = null,
    /** The authorised signatory printed beside the signature image (BE-012). */
    val signatoryName: String? = null,
    val signatoryDesignation: String? = null,
)

@Serializable
data class CustomerSnapshot(
    val name: String,
    val gstin: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val addressLine1: String? = null,
    val addressLine2: String? = null,
    val city: String? = null,
    val state: String? = null,
    val stateCode: String? = null,
    val pincode: String? = null,
)

fun CompanyProfile.toSnapshot() = CompanySnapshot(
    name = name, gstin = gstin, addressLine1 = addressLine1, addressLine2 = addressLine2,
    city = city, state = state, stateCode = stateCode, pincode = pincode, country = country,
    phone = phone, email = email, logo = logo, signature = signature, upiId = upiId, qr = qr,
    bankName = bankName, accountNumber = accountNumber, ifsc = ifsc,
    signatoryName = signatoryName, signatoryDesignation = signatoryDesignation,
)

fun CustomerRecord.toSnapshot() = CustomerSnapshot(
    name = name, gstin = gstin, phone = phone, email = email, addressLine1 = addressLine1,
    addressLine2 = addressLine2, city = city, state = state, stateCode = stateCode, pincode = pincode,
)
