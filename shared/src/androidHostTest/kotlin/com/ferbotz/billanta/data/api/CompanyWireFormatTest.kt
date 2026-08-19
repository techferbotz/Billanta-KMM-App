package com.ferbotz.billanta.data.api

import com.ferbotz.billanta.core.BillantaJson
import com.ferbotz.billanta.domain.model.CompanyProfile
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The exact keys `/company` uses (BE-013).
 *
 * Five of them differ from both the domain model and the template binding namespace. Because
 * `PUT /company` is a full replace, a key the server does not recognise reads as omitted and
 * *clears* that field — which is how account number and IFSC were actually lost server-side before
 * this was pinned down. Asserting the literal JSON is the only thing that stops it recurring: the
 * previous names were plausible, self-consistent, and completely wrong.
 */
class CompanyWireFormatTest {

    private fun fullProfile() = CompanyProfile(
        name = "Studio Nine",
        gstin = "27ABCDE1234F1Z5",
        addressLine1 = "12 Linking Road",
        addressLine2 = "Bandra West",
        city = "Mumbai",
        state = "Maharashtra",
        stateCode = "27",
        pincode = "400050",
        country = "India",
        phone = "9876543210",
        email = "hi@studionine.in",
        logo = "https://cdn/logo.png",
        signature = "https://cdn/sig.png",
        upiId = "studio@okbank",
        qr = "https://cdn/qr.png",
        bankName = "HDFC Bank",
        accountNumber = "12345678901",
        ifsc = "HDFC0001234",
        signatoryName = "Vishal B",
        signatoryDesignation = "Director",
    )

    @Test
    fun the_renamed_fields_go_out_under_their_rest_names() {
        val json = BillantaJson.encodeToString(CompanyDto.serializer(), fullProfile().toDto()).let {
            BillantaJson.parseToJsonElement(it).jsonObject
        }

        // The five that differ from the domain model, and cost real data when they were assumed.
        assertEquals("\"https://cdn/logo.png\"", json["logoUrl"].toString())
        assertEquals("\"https://cdn/sig.png\"", json["signatureUrl"].toString())
        assertEquals("\"https://cdn/qr.png\"", json["qrImageUrl"].toString())
        assertEquals("\"12345678901\"", json["bankAccountNumber"].toString())
        assertEquals("\"HDFC0001234\"", json["bankIfsc"].toString())

        // The render-view names must not appear on the wire at all.
        listOf("logo", "signature", "qr", "accountNumber", "ifsc").forEach {
            assertTrue(it !in json, "'$it' is a binding path, not a REST field — sending it clears the real one")
        }
    }

    @Test
    fun the_fields_that_already_matched_are_unchanged() {
        val json = BillantaJson.encodeToString(CompanyDto.serializer(), fullProfile().toDto()).let {
            BillantaJson.parseToJsonElement(it).jsonObject
        }
        listOf(
            "name", "gstin", "addressLine1", "addressLine2", "city", "state", "stateCode",
            "pincode", "country", "phone", "email", "upiId", "bankName",
            "signatoryName", "signatoryDesignation",
        ).forEach { assertTrue(it in json, "'$it' should be on the wire but is missing") }
    }

    /** A body from the server has to come back as the same profile, or the pull nulls fields. */
    @Test
    fun a_server_body_reads_back_into_every_field() {
        val body = """
            { "name": "Studio Nine", "gstin": "27ABCDE1234F1Z5",
              "addressLine1": "12 Linking Road", "addressLine2": "Bandra West",
              "city": "Mumbai", "state": "Maharashtra", "stateCode": "27",
              "pincode": "400050", "country": "India",
              "phone": "9876543210", "email": "hi@studionine.in",
              "logoUrl": "https://cdn/logo.png", "signatureUrl": "https://cdn/sig.png",
              "qrImageUrl": "https://cdn/qr.png", "upiId": "studio@okbank",
              "bankName": "HDFC Bank", "bankAccountNumber": "12345678901", "bankIfsc": "HDFC0001234",
              "signatoryName": "Vishal B", "signatoryDesignation": "Director" }
        """.trimIndent()

        val parsed = BillantaJson.decodeFromString(CompanyDto.serializer(), body).toDomain()
        assertEquals(fullProfile(), parsed, "a populated server body did not round-trip into the profile")
    }

    /** The whole round trip, which is what the sync pull actually does. */
    @Test
    fun a_profile_survives_the_wire_in_both_directions() {
        val original = fullProfile()
        val json = BillantaJson.encodeToString(CompanyDto.serializer(), original.toDto())
        assertEquals(original, BillantaJson.decodeFromString(CompanyDto.serializer(), json).toDomain())
    }
}
