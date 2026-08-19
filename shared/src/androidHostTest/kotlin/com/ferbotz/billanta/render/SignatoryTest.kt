package com.ferbotz.billanta.render

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.data.db.BillantaDb
import com.ferbotz.billanta.data.local.ProfileLocalDataSource
import com.ferbotz.billanta.domain.model.CompanyProfile
import com.ferbotz.billanta.domain.model.CompanySnapshot
import com.ferbotz.billanta.domain.model.InvoiceRecord
import com.ferbotz.billanta.domain.model.toSnapshot
import com.ferbotz.billanta.render.layout.DrawCommand
import com.ferbotz.billanta.render.layout.flattenCommands
import com.ferbotz.billanta.render.text.FakeTextShaper
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BE-012: the authorised signatory printed beside the signature image.
 *
 * Two optional company fields surfaced as `signature.name` / `signature.designation`, and frozen
 * into the invoice's company snapshot so a saved invoice re-renders identically.
 */
class SignatoryTest {

    private val renderer = InvoiceRenderer(FakeTextShaper())

    private fun profile() = CompanyProfile(
        name = "Studio Nine",
        signature = "https://cdn/sig.png",
        signatoryName = "Vishal B",
        signatoryDesignation = "Director",
    )

    private fun record(company: CompanySnapshot?) = InvoiceRecord(
        id = "inv-1",
        invoiceNumber = "INV-1",
        invoiceDateMillis = Iso8601.epochMillisFor(2026, 8, 19),
        companySnapshot = company,
        updatedAtMillis = 1L,
    )

    /** A template that prints the signatory, so the binding is exercised end to end. */
    private fun signatoryTemplate(): TemplateDoc = assertNotNull(
        TemplateParser.parse(
            """
            { "schemaVersion": 1, "compilerVersion": 1,
              "page": { "size": "A4", "margin": { "top": 0, "right": 0, "bottom": 0, "left": 0 } },
              "root": { "type": "box", "style": {}, "children": [
                { "type": "text", "style": {}, "spans": [
                  { "value": { "kind": "bind", "path": "signature.name", "format": "text", "fallback": "" } } ] },
                { "type": "text", "style": {}, "spans": [
                  { "value": { "kind": "bind", "path": "signature.designation", "format": "text", "fallback": "" } } ] }
              ] } }
            """,
        ),
    )

    private fun textOf(record: InvoiceRecord): List<String> =
        renderer.render(signatoryTemplate(), record).pages
            .flatMap { it.commands.flattenCommands() }
            .filterIsInstance<DrawCommand.Text>()
            .flatMap { it.paragraph.lines }
            .flatMap { line -> line.runs.map { it.text } }

    @Test
    fun the_signatory_reaches_the_page_through_the_new_binding_paths() {
        val printed = textOf(record(profile().toSnapshot()))
        assertTrue(printed.any { it.contains("Vishal B") }, "signature.name never rendered: $printed")
        assertTrue(printed.any { it.contains("Director") }, "signature.designation never rendered: $printed")
    }

    /** Both are optional; a profile that leaves them blank must render nothing, not "null". */
    @Test
    fun an_unset_signatory_renders_as_nothing() {
        val printed = textOf(record(CompanySnapshot(name = "Studio Nine")))
        assertTrue(
            printed.none { it.contains("null", ignoreCase = true) },
            "an unset signatory leaked into the page: $printed",
        )
    }

    /** The snapshot is what the PDF prints, so the signatory has to be frozen with the rest. */
    @Test
    fun the_signatory_is_carried_into_the_company_snapshot() {
        val snapshot = profile().toSnapshot()
        assertEquals("Vishal B", snapshot.signatoryName)
        assertEquals("Director", snapshot.signatoryDesignation)
    }

    /**
     * The two columns arrive by migration on devices that already have a profile. If the migration
     * did not run, this write would fail rather than silently drop the fields.
     */
    @Test
    fun the_new_columns_survive_the_database_round_trip() = runTest {
        val dispatcher = UnconfinedTestDispatcher()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        BillantaDb.Schema.create(driver)
        val local = ProfileLocalDataSource(BillantaDb(driver), dispatcher)

        local.saveCompany(profile(), dirty = true, updatedAtMillis = 1L)
        val reloaded = assertNotNull(local.getCompany())

        assertEquals("Vishal B", reloaded.signatoryName)
        assertEquals("Director", reloaded.signatoryDesignation)

        // Full-replace semantics: clearing a field on the profile clears it in storage too.
        local.saveCompany(profile().copy(signatoryDesignation = null), dirty = true, updatedAtMillis = 2L)
        assertNull(assertNotNull(local.getCompany()).signatoryDesignation)
    }
}
