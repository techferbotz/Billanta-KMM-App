package com.ferbotz.billanta.data.repo

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.core.EpochClock
import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.data.db.BillantaDb
import com.ferbotz.billanta.data.local.CustomerLocalDataSource
import com.ferbotz.billanta.data.local.InvoiceLocalDataSource
import com.ferbotz.billanta.data.local.ProfileLocalDataSource
import com.ferbotz.billanta.domain.model.CompanySnapshot
import com.ferbotz.billanta.domain.model.CustomerSnapshot
import com.ferbotz.billanta.domain.model.InvoiceItemRecord
import com.ferbotz.billanta.domain.model.InvoiceRecord
import com.ferbotz.billanta.render.InvoiceRenderer
import com.ferbotz.billanta.render.InvoiceTheme
import com.ferbotz.billanta.render.TemplateParser
import com.ferbotz.billanta.render.layout.DrawCommand
import com.ferbotz.billanta.render.layout.flattenCommands
import com.ferbotz.billanta.render.text.FakeTextShaper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The half the render tests never covered: a customisation chosen in the edit sheet has to survive
 * the round trip through the database before it can affect anything on screen.
 */
class InvoiceCustomisationTest {

    private val fixedNow = Iso8601.epochMillisFor(2026, 8, 6, 12, 0)
    private val clock = EpochClock { fixedNow }

    private fun repository(dispatcher: kotlinx.coroutines.CoroutineDispatcher): Pair<InvoiceRepository, InvoiceLocalDataSource> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        BillantaDb.Schema.create(driver)
        val db = BillantaDb(driver)
        val local = InvoiceLocalDataSource(db, dispatcher)
        return InvoiceRepository(
            local = local,
            customerLocal = CustomerLocalDataSource(db, dispatcher),
            profileLocal = ProfileLocalDataSource(db, dispatcher),
            clock = clock,
            onLocalMutation = {},
        ) to local
    }

    private fun invoice() = InvoiceRecord(
        id = "inv-1",
        invoiceNumber = "INV-1",
        invoiceDateMillis = Iso8601.epochMillisFor(2026, 8, 1),
        companySnapshot = CompanySnapshot(name = "Studio Nine", stateCode = "27", upiId = "studio@okbank"),
        customerSnapshot = CustomerSnapshot(name = "Kavya Iyer", stateCode = "27"),
        items = listOf(
            InvoiceItemRecord(
                description = "Design", quantity = "1", unitPricePaise = 100000,
                taxRatePercent = "18", lineTotalPaise = 100000, taxAmountPaise = 18000,
            ),
        ),
        notes = "Payable within 14 days.",
        updatedAtMillis = fixedNow,
    )

    @Test
    fun a_hidden_section_survives_the_database_round_trip() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val (repo, local) = repository(dispatcher)
        local.upsert(invoice(), dirty = false)

        val saved = repo.setCustomisation(
            id = "inv-1",
            themeOverrides = emptyMap(),
            hiddenSections = setOf("payment"),
        )
        assertTrue(saved is AppResult.Success, "setCustomisation failed: $saved")

        val reloaded = assertNotNull(local.getById("inv-1"), "invoice vanished")
        assertEquals(
            setOf("payment"),
            reloaded.hiddenSections,
            "the hidden section did not survive being written and read back",
        )
    }

    @Test
    fun a_colour_override_survives_the_database_round_trip() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val (repo, local) = repository(dispatcher)
        local.upsert(invoice(), dirty = false)

        repo.setCustomisation("inv-1", mapOf("accent" to 0xFFC2410C), emptySet())

        val reloaded = assertNotNull(local.getById("inv-1"))
        assertEquals(mapOf("accent" to 0xFFC2410C), reloaded.themeOverrides)
    }

    @Test
    fun clearing_a_customisation_persists_as_cleared() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val (repo, local) = repository(dispatcher)
        local.upsert(invoice(), dirty = false)

        repo.setCustomisation("inv-1", emptyMap(), setOf("payment", "notes"))
        repo.setCustomisation("inv-1", emptyMap(), setOf("notes"))

        assertEquals(setOf("notes"), assertNotNull(local.getById("inv-1")).hiddenSections)

        repo.setCustomisation("inv-1", emptyMap(), emptySet())
        assertEquals(emptySet(), assertNotNull(local.getById("inv-1")).hiddenSections)
    }

    /**
     * The link the screen actually depends on. `PreviewScreen` renders from
     * `observeInvoice(id)`, not from a direct read — if that flow does not re-emit after the
     * toggle writes, the page keeps drawing the old customisation and nothing appears to happen.
     */
    @Test
    fun the_observed_invoice_flow_re_emits_after_a_customisation_change() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val (repo, local) = repository(dispatcher)
        local.upsert(invoice(), dirty = false)

        // First: does the flow emit at all? (Separates a broken flow from a test that never ran.)
        val initial = assertNotNull(repo.observeInvoice("inv-1").first(), "flow never emitted")
        assertEquals(emptySet(), initial.hiddenSections)

        // Then: collect eagerly on the unconfined dispatcher so the write's notification is seen.
        val seen = mutableListOf<Set<String>>()
        val job = backgroundScope.launch(dispatcher) {
            repo.observeInvoice("inv-1").collect { record ->
                if (record != null) seen += record.hiddenSections
            }
        }
        testScheduler.advanceUntilIdle()

        repo.setCustomisation("inv-1", emptyMap(), setOf("payment"))
        testScheduler.advanceUntilIdle()

        assertTrue(seen.isNotEmpty(), "the flow emitted nothing even on subscribe")
        assertEquals(
            setOf("payment"),
            seen.last(),
            "the screen observes this flow, so it must re-emit with the new hidden sections; " +
                "emissions seen: $seen",
        )
        job.cancel()
    }

    /** The whole path the user actually exercises: toggle in the sheet, then look at the page. */
    @Test
    fun a_section_hidden_through_the_repository_disappears_from_the_render() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val (repo, local) = repository(dispatcher)
        local.upsert(invoice(), dirty = false)

        val file = listOf(
            File("src/androidHostTest/resources/templates/classic.json"),
            File("shared/src/androidHostTest/resources/templates/classic.json"),
        ).firstOrNull { it.exists() }
        val doc = assertNotNull(TemplateParser.parse(assertNotNull(file).readText()))
        val renderer = InvoiceRenderer(FakeTextShaper())

        fun textFor(record: InvoiceRecord): String {
            val theme = InvoiceTheme(record.themeOverrides, record.hiddenSections)
            return renderer.render(doc, record, theme)
                .pages.flatMap { it.commands.flattenCommands() }
                .filterIsInstance<DrawCommand.Text>()
                .flatMap { it.paragraph.lines }
                .flatMap { line -> line.runs.map { it.text } }
                .joinToString(" ")
        }

        val before = textFor(assertNotNull(local.getById("inv-1")))
        assertTrue(before.contains("studio@okbank"), "fixture should show payment details to begin with")

        repo.setCustomisation("inv-1", emptyMap(), setOf("payment"))

        val after = textFor(assertNotNull(local.getById("inv-1")))
        assertTrue(
            !after.contains("studio@okbank"),
            "the payment block should be gone after hiding it through the repository",
        )
    }
}
