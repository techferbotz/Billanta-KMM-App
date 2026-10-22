package com.ferbotz.billanta.data.repo

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.ferbotz.billanta.core.BillantaJson
import com.ferbotz.billanta.core.EpochClock
import com.ferbotz.billanta.data.api.BillantaApi
import com.ferbotz.billanta.data.api.Envelope
import com.ferbotz.billanta.data.api.PageDto
import com.ferbotz.billanta.data.api.TemplateDto
import com.ferbotz.billanta.data.db.BillantaDb
import com.ferbotz.billanta.data.local.TemplateLocalDataSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** BE-006: `GET /templates` is cursor-paginated, and the catalogue must drain every page. */
class TemplateCatalogueTest {

    @Test
    fun the_template_catalogue_drains_every_page() = runTest {
        fun template(id: String) = TemplateDto(id = id, name = id.replaceFirstChar { it.uppercase() })
        val pages = listOf(
            PageDto(listOf(template("amber"), template("amethyst")), nextCursor = "c1", hasMore = true),
            PageDto(listOf(template("cobalt")), nextCursor = null, hasMore = false),
        )
        val queries = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                queries += request.url.encodedQuery
                val page = pages[queries.size - 1]
                respond(
                    BillantaJson.encodeToString(Envelope.serializer(PageDto.serializer(TemplateDto.serializer())), Envelope(success = true, data = page)),
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
            },
        ) {
            expectSuccess = false
            install(ContentNegotiation) { json(BillantaJson) }
            defaultRequest { url("https://test.local/") }
        }
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        BillantaDb.Schema.create(driver)
        val repository = TemplateRepository(
            TemplateLocalDataSource(BillantaDb(driver), UnconfinedTestDispatcher(testScheduler)),
            BillantaApi(client),
            EpochClock { 1L },
        )

        val result = repository.refreshCatalogue()

        assertTrue(result.isSuccess, "refresh failed: ${result.errorOrNull()}")
        assertEquals(listOf("amber", "amethyst", "cobalt"), result.getOrNull()?.map { it.id }, "stopping at page one would hide templates")
        assertEquals(2, queries.size, "the second page should have been requested")
        assertTrue(queries.last().contains("cursor=c1"), "the follow-up call must pass the cursor the first page returned")
    }
}
