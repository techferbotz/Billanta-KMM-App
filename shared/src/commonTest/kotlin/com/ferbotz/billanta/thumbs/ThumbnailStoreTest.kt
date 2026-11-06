package com.ferbotz.billanta.thumbs

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class ThumbnailStoreTest {

    private val fs = FakeFileSystem()
    private val dir = "/cache/invoice-thumbnails".toPath()
    private val store = ThumbnailStore(fs, dir).also { it.load() }

    private val first = "0123456789abcdef"
    private val second = "fedcba9876543210"

    private fun names() = fs.list(dir).map { it.name }.sorted()

    @Test
    fun a_new_picture_replaces_the_old_one() {
        store.write("inv-1", first, byteArrayOf(1))
        store.write("inv-1", second, byteArrayOf(2))
        assertEquals(listOf("inv-1.$second.jpg"), names(), "one picture per invoice, and no temp file left")
        assertEquals(second, store.fingerprintOf("inv-1"))
        assertEquals(byteArrayOf(2).toList(), fs.read(store.files.value.getValue("inv-1")) { readByteArray() }.toList())
    }

    @Test
    fun the_pictures_are_found_again_after_a_restart() {
        store.write("inv-1", first, byteArrayOf(1))
        store.write("inv-2", second, byteArrayOf(2))
        val restarted = ThumbnailStore(fs, dir).also { it.load() }
        assertEquals(mapOf("inv-1" to first, "inv-2" to second), restarted.files.value.mapValues { (id, _) -> restarted.fingerprintOf(id) })
    }

    @Test
    fun a_crash_mid_write_leaves_nothing_behind_on_the_next_start() {
        store.write("inv-1", first, byteArrayOf(1))
        fs.write(dir / "inv-1.$second.jpg.tmp") { write(byteArrayOf(9)) }
        fs.write(dir / "notes.txt") { write(byteArrayOf(9)) }
        val restarted = ThumbnailStore(fs, dir).also { it.load() }
        assertEquals(listOf("inv-1.$first.jpg"), names())
        assertEquals(first, restarted.fingerprintOf("inv-1"))
    }

    @Test
    fun of_two_pictures_for_one_invoice_the_newer_is_kept() {
        val clock = SteppedClock()
        val fs = FakeFileSystem(clock).also { it.createDirectories(dir) }
        // The newer file sorts first by name, so this proves the choice is by age.
        fs.write(dir / "inv-1.$second.jpg") { write(byteArrayOf(2)) }
        clock.millis += 1_000
        fs.write(dir / "inv-1.$first.jpg") { write(byteArrayOf(1)) }
        val restarted = ThumbnailStore(fs, dir).also { it.load() }
        assertEquals(listOf("inv-1.$first.jpg"), fs.list(dir).map { it.name })
        assertEquals(first, restarted.fingerprintOf("inv-1"))
    }

    @OptIn(ExperimentalTime::class)
    private class SteppedClock : Clock {
        var millis = 1_000_000L
        override fun now(): Instant = Instant.fromEpochMilliseconds(millis)
    }

    @Test
    fun pictures_of_deleted_invoices_are_swept() {
        store.write("inv-1", first, byteArrayOf(1))
        store.write("inv-2", second, byteArrayOf(2))
        store.retainOnly(setOf("inv-2"))
        assertEquals(listOf("inv-2.$second.jpg"), names())
        assertNull(store.fingerprintOf("inv-1"))
    }

    @Test
    fun clearing_removes_every_picture() {
        store.write("inv-1", first, byteArrayOf(1))
        store.clear()
        assertFalse(fs.exists(dir))
        assertTrue(store.files.value.isEmpty())
        // And the next picture still has somewhere to go.
        store.write("inv-2", second, byteArrayOf(2))
        assertEquals(listOf("inv-2.$second.jpg"), names())
    }

    @Test
    fun an_id_that_is_not_a_plain_identifier_is_never_turned_into_a_path() {
        assertNull(store.write("../escape", first, byteArrayOf(1)))
        assertNull(store.write("inv-1", "not-a-fingerprint", byteArrayOf(1)))
        assertTrue(names().isEmpty())
    }
}
