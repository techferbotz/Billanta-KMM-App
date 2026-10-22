package com.ferbotz.billanta.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.ferbotz.billanta.core.BillantaJson
import com.ferbotz.billanta.data.api.ProductDto
import com.ferbotz.billanta.data.api.toDomain
import com.ferbotz.billanta.data.api.toDto
import com.ferbotz.billanta.data.api.toPatchBody
import com.ferbotz.billanta.data.db.BillantaDb
import com.ferbotz.billanta.data.local.ProductLocalDataSource
import com.ferbotz.billanta.domain.model.ProductRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Product photos (APP-015) ship in the app before the backend knows about them. A pull from that
 * older backend returns products without the photo keys at all, and reading that silence as
 * "removed" would wipe every photo on the phone at the next sync — so absent and `null` must stay
 * two different answers all the way from the JSON to the database.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProductPhotoSyncTest {

    private val full = "https://billanta-media.s3.ap-south-1.amazonaws.com/u/1/full.webp"
    private val thumb = "https://billanta-media.s3.ap-south-1.amazonaws.com/u/1/thumb.webp"

    private fun decode(json: String) = BillantaJson.decodeFromString(ProductDto.serializer(), json)

    @Test
    fun a_server_that_never_heard_of_photos_is_not_saying_they_were_removed() {
        val dto = decode("""{ "id": "p1", "name": "Logo design", "unitPrice": "150000" }""")
        assertFalse(dto.carriesImages)
        // The record itself has no photo; keeping the phone's is the merge's job (below).
        assertNull(dto.toDomain(fallbackUpdatedAtMillis = 1).imageUrl)
    }

    @Test
    fun an_explicit_null_is_a_removal() {
        val dto = decode("""{ "id": "p1", "name": "Logo design", "imageUrl": null, "imageThumbnailUrl": null }""")
        assertTrue(dto.carriesImages)
        val record = dto.toDomain(fallbackUpdatedAtMillis = 1)
        assertNull(record.imageUrl)
        assertNull(record.imageThumbnailUrl)
    }

    @Test
    fun a_photo_from_the_server_reads_back_as_is() {
        val dto = decode("""{ "id": "p1", "name": "Logo design", "imageUrl": "$full", "imageThumbnailUrl": "$thumb" }""")
        assertTrue(dto.carriesImages)
        val record = dto.toDomain(fallbackUpdatedAtMillis = 1)
        assertEquals(full, record.imageUrl)
        assertEquals(thumb, record.imageThumbnailUrl)
    }

    /** The marker that stands for "not in the JSON" must never itself end up in the JSON. */
    @Test
    fun the_not_sent_marker_never_goes_over_the_wire() {
        val noPhoto = BillantaJson.encodeToString(ProductDto.serializer(), ProductRecord(id = "p1", name = "Logo design").toDto())
        assertFalse(noPhoto.contains("not-sent"), noPhoto)
        val withPhoto = BillantaJson.encodeToString(
            ProductDto.serializer(),
            ProductRecord(id = "p1", name = "Logo design", imageUrl = full, imageThumbnailUrl = thumb).toDto(),
        )
        assertTrue(withPhoto.contains(full) && withPhoto.contains(thumb), withPhoto)
    }

    /** PATCH is partial on the server, so removing a photo has to be an explicit null. */
    @Test
    fun a_patch_always_names_both_photo_keys() {
        val removed = ProductRecord(id = "p1", name = "Logo design").toPatchBody()
        assertEquals(JsonNull, removed["imageUrl"])
        assertEquals(JsonNull, removed["imageThumbnailUrl"])
        val set = ProductRecord(id = "p1", name = "Logo design", imageUrl = full, imageThumbnailUrl = thumb).toPatchBody()
        assertEquals(JsonPrimitive(full), set["imageUrl"])
        assertEquals(JsonPrimitive(thumb), set["imageThumbnailUrl"])
    }

    private fun local(): ProductLocalDataSource {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        BillantaDb.Schema.create(driver)
        return ProductLocalDataSource(BillantaDb(driver), UnconfinedTestDispatcher())
    }

    private val withPhoto = ProductRecord(
        id = "p1", name = "Logo design", unitPricePaise = 150_000,
        imageUrl = full, imageThumbnailUrl = thumb, updatedAtMillis = 1,
    )

    @Test
    fun a_pull_from_an_older_server_keeps_the_phones_photo() = runTest {
        val products = local()
        products.upsert(withPhoto, dirty = false, isSynced = true)

        // The server's copy: same product, a newer rate, and no idea photos exist.
        val server = withPhoto.copy(unitPricePaise = 180_000, imageUrl = null, imageThumbnailUrl = null, updatedAtMillis = 2)
        products.applyServer(server, serverKnowsImages = false)

        val stored = assertNotNull(products.getById("p1"))
        assertEquals(180_000, stored.unitPricePaise, "everything else still comes from the server")
        assertEquals(full, stored.imageUrl)
        assertEquals(thumb, stored.imageThumbnailUrl)
    }

    @Test
    fun a_server_that_knows_photos_is_believed_when_it_removes_one() = runTest {
        val products = local()
        products.upsert(withPhoto, dirty = false, isSynced = true)

        products.applyServer(withPhoto.copy(imageUrl = null, imageThumbnailUrl = null, updatedAtMillis = 2), serverKnowsImages = true)

        val stored = assertNotNull(products.getById("p1"))
        assertNull(stored.imageUrl)
        assertNull(stored.imageThumbnailUrl)
    }

    /** The merge half of BE-020's handover: kept, queued, and reported — see ProductPhotoHandoverTest. */
    @Test
    fun during_the_handover_a_server_null_keeps_the_photo_and_queues_it() = runTest {
        val products = local()
        products.upsert(withPhoto, dirty = false, isSynced = true)

        val kept = products.applyServer(
            withPhoto.copy(imageUrl = null, imageThumbnailUrl = null, updatedAtMillis = 2),
            serverKnowsImages = true,
            rescueUnsentPhoto = true,
        )

        assertTrue(kept)
        val stored = assertNotNull(products.getById("p1"))
        assertEquals(full, stored.imageUrl)
        assertTrue(stored.pendingSync, "queued, so the next push sends it")
        // Still known to the server, so that push is a PATCH, not a replacing POST.
        assertEquals(listOf("p1"), products.dirtyRows().filter { it.isSynced != 0L }.map { it.id })
    }

    /**
     * A phone that already has a catalogue gets the photo columns by migration, not by a fresh
     * create. This builds the table as schema 3 had it, runs the 3 -> 4 step, and checks that the
     * old row survives with no photo and that a photo can then be stored.
     */
    @Test
    fun the_migration_adds_the_photo_columns_to_an_existing_catalogue() = runTest {
        assertEquals(4, BillantaDb.Schema.version)
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(
            null,
            """
            CREATE TABLE productRow (
                id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, nameKey TEXT NOT NULL, hsnSac TEXT,
                unitPrice INTEGER NOT NULL, taxRatePercent TEXT NOT NULL, unit TEXT,
                usageCount INTEGER NOT NULL DEFAULT 1, lastUsedAtMillis INTEGER NOT NULL,
                createdAtMillis INTEGER, updatedAtMillis INTEGER NOT NULL,
                dirty INTEGER NOT NULL DEFAULT 0, isDeleted INTEGER NOT NULL DEFAULT 0,
                isSynced INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            "INSERT INTO productRow(id, name, nameKey, unitPrice, taxRatePercent, lastUsedAtMillis, updatedAtMillis) " +
                "VALUES ('p0', 'Old product', 'old product', 5000, '18', 0, 1)",
            0,
        )

        BillantaDb.Schema.migrate(driver, oldVersion = 3, newVersion = 4)

        val products = ProductLocalDataSource(BillantaDb(driver), UnconfinedTestDispatcher())
        val old = assertNotNull(products.getById("p0"))
        assertNull(old.imageUrl)
        products.upsert(old.copy(imageUrl = full, imageThumbnailUrl = thumb), dirty = true, isSynced = false)
        assertEquals(thumb, assertNotNull(products.getById("p0")).imageThumbnailUrl)
    }
}
