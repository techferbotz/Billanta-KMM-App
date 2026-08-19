package com.ferbotz.billanta.render

import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.domain.model.CompanySnapshot
import com.ferbotz.billanta.domain.model.InvoiceRecord
import com.ferbotz.billanta.render.text.FakeTextShaper
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * BE-011 split the catch-all `company` editor into `bankDetails` and `signature`, so a section says
 * exactly what it edits. `company` is still parsed: template versions are immutable, so a version
 * already published keeps emitting it forever.
 */
class SectionVocabularyTest {

    private val renderer = InvoiceRenderer(FakeTextShaper())

    private fun template(name: String): TemplateDoc {
        val file = listOf(
            File("src/androidHostTest/resources/templates/$name.json"),
            File("shared/src/androidHostTest/resources/templates/$name.json"),
        ).firstOrNull { it.exists() }
        assertNotNull(file, "fixture $name.json not found")
        return assertNotNull(TemplateParser.parse(file.readText()))
    }

    private fun record(company: CompanySnapshot?) = InvoiceRecord(
        id = "inv-1",
        invoiceNumber = "INV-1",
        invoiceDateMillis = Iso8601.epochMillisFor(2026, 8, 19),
        companySnapshot = company,
        updatedAtMillis = 1L,
    )

    @Test
    fun the_new_kinds_parse_and_the_retired_one_still_does() {
        assertEquals(SectionEdits.BankDetails, SectionEdits.fromWire("bankDetails"))
        assertEquals(SectionEdits.Signature, SectionEdits.fromWire("signature"))
        // Immutable template versions mean the old value never stops arriving.
        assertEquals(SectionEdits.Company, SectionEdits.fromWire("company"))
        assertEquals(SectionEdits.None, SectionEdits.fromWire("letterhead"))
        SectionEdits.entries.forEach {
            assertEquals(it, SectionEdits.fromWire(it.wireName), "${it.wireName} did not round-trip")
        }
    }

    /** The live template. Without these two, both blocks vanish from the editor list entirely. */
    @Test
    fun the_live_template_declares_both_new_kinds() {
        val doc = template("amethyst")
        val byEdits = doc.sections.associate { it.id to it.edits }

        assertEquals(SectionEdits.BankDetails, byEdits["payment"], "payment should edit bank details")
        assertEquals(SectionEdits.Signature, byEdits["signature"], "signature should edit the signature")
        assertTrue(
            doc.sections.filter { it.id in setOf("payment", "signature") }.all { it.isEditable },
            "both must be editable, or they drop out of the section list",
        )
    }

    @Test
    fun bank_details_count_as_empty_only_when_every_field_is_blank() {
        val doc = template("amethyst")

        val none = emptySectionsFor(doc, record(CompanySnapshot(name = "Studio Nine")))
        assertTrue("payment" in none, "a company with no bank fields has nothing to print")

        val blanks = emptySectionsFor(
            doc,
            record(CompanySnapshot(name = "Studio Nine", bankName = "", accountNumber = "", ifsc = "", upiId = "")),
        )
        assertTrue("payment" in blanks, "blank strings are not bank details")

        // Any one of the four is enough to make the block worth printing.
        listOf(
            CompanySnapshot(name = "S", bankName = "HDFC"),
            CompanySnapshot(name = "S", accountNumber = "12345"),
            CompanySnapshot(name = "S", ifsc = "HDFC0001"),
            CompanySnapshot(name = "S", upiId = "s@okbank"),
        ).forEach { snapshot ->
            assertFalse(
                "payment" in emptySectionsFor(doc, record(snapshot)),
                "a company with $snapshot should not report payment empty",
            )
        }
    }

    @Test
    fun the_signature_section_is_empty_until_there_is_an_image() {
        val doc = template("amethyst")
        assertTrue("signature" in emptySectionsFor(doc, record(CompanySnapshot(name = "S"))))
        assertTrue("signature" in emptySectionsFor(doc, record(CompanySnapshot(name = "S", signature = "   "))))
        assertFalse(
            "signature" in emptySectionsFor(doc, record(CompanySnapshot(name = "S", signature = "https://x/sig.png"))),
        )
    }

    /** The sections are hidable, so the switch has to reach them — and hiding has to work. */
    @Test
    fun both_sections_can_be_switched_off() {
        val doc = template("amethyst")
        val filled = record(
            CompanySnapshot(name = "Studio Nine", bankName = "HDFC", accountNumber = "12345", ifsc = "HDFC0001"),
        )
        assertTrue(
            doc.sections.filter { it.id in setOf("payment", "signature") }.all { it.hidable },
            "the template should let both be left off",
        )

        val shown = renderer.render(doc, filled).pages.flatMap { it.sections }.map { it.id }
        assertTrue("payment" in shown, "the payment block should render when there are bank details")

        val hidden = renderer
            .render(doc, filled, theme = InvoiceTheme(hiddenSections = setOf("payment")))
            .pages.flatMap { it.sections }.map { it.id }
        assertFalse("payment" in hidden, "hiding the payment section left it on the page")
    }
}
