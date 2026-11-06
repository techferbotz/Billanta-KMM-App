package com.ferbotz.billanta.thumbs

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** FIELDS_APP_GUIDE.md §12.3's rules for when a list thumbnail is drawn, against a fake drawer. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThumbnailSchedulerTest {

    /** A drawer that takes [DRAW_MILLIS] per invoice and records what it was asked for. */
    private class Harness(scope: TestScope) {
        val pictures = HashSet<String>()
        val drawn = ArrayList<String>()
        val outcomes = HashMap<String, ThumbnailOutcome>()
        var running = 0
        var mostAtOnce = 0

        val scheduler = ThumbnailScheduler(
            scope = scope.backgroundScope,
            dispatcher = StandardTestDispatcher(scope.testScheduler),
            now = { scope.testScheduler.currentTime },
            hasPicture = { it in pictures },
            seeTo = { id ->
                running++
                mostAtOnce = maxOf(mostAtOnce, running)
                drawn += id
                delay(DRAW_MILLIS)
                running--
                (outcomes[id] ?: ThumbnailOutcome.Done).also { if (it == ThumbnailOutcome.Done) pictures += id }
            },
        )

        fun rows(vararg rows: Pair<String, Int>) = scheduler.rows(rows.toMap())
    }

    private fun TestScope.harness(ready: Boolean = true) = Harness(this).also { if (ready) it.scheduler.ready(true) }

    /**
     * Lets everything due happen. The scheduler runs in `backgroundScope`, which `advanceUntilIdle`
     * deliberately doesn't wait for, so time is moved on instead.
     */
    private fun TestScope.drain() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    @Test
    fun rows_on_screen_are_drawn_top_to_bottom_one_at_a_time() = runTest {
        val h = harness()
        h.rows("a" to 1, "b" to 1, "c" to 1)
        h.scheduler.visible(listOf("c", "a"))
        drain()
        assertEquals(listOf("c", "a"), h.drawn)
        assertEquals(1, h.mostAtOnce)
    }

    @Test
    fun a_row_that_scrolls_away_before_its_turn_is_dropped() = runTest {
        val h = harness()
        h.rows("a" to 1, "b" to 1, "c" to 1)
        h.scheduler.visible(listOf("a", "b", "c"))
        advanceTimeBy(DRAW_MILLIS / 2)
        h.scheduler.visible(listOf("c"))
        drain()
        assertEquals(listOf("a", "c"), h.drawn, "b scrolled away while a was drawing")
    }

    @Test
    fun nothing_is_drawn_before_there_is_a_renderer() = runTest {
        val h = harness(ready = false)
        h.rows("a" to 1)
        h.scheduler.visible(listOf("a"))
        drain()
        assertTrue(h.drawn.isEmpty())
        h.scheduler.ready(true)
        drain()
        assertEquals(listOf("a"), h.drawn)
    }

    @Test
    fun a_row_is_not_seen_to_again_until_it_changes() = runTest {
        val h = harness()
        h.rows("a" to 1)
        h.scheduler.visible(listOf("a"))
        drain()
        h.rows("a" to 1)
        h.scheduler.visible(emptyList())
        h.scheduler.visible(listOf("a"))
        drain()
        assertEquals(listOf("a"), h.drawn)
        h.rows("a" to 2)
        drain()
        assertEquals(listOf("a", "a"), h.drawn)
    }

    @Test
    fun a_stale_picture_redraws_itself_a_second_after_the_change() = runTest {
        val h = harness()
        h.pictures += "a"
        h.rows("a" to 1)
        drain()
        assertTrue(h.drawn.isEmpty(), "first sight of a row that isn't on screen draws nothing")

        h.rows("a" to 2)
        advanceTimeBy(SETTLE_MILLIS - 1)
        runCurrent()
        assertTrue(h.drawn.isEmpty(), "still settling")
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("a"), h.drawn)
    }

    @Test
    fun a_changed_invoice_without_a_picture_waits_until_it_is_on_screen() = runTest {
        val h = harness()
        h.rows("a" to 1)
        h.rows("a" to 2)
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(h.drawn.isEmpty(), "fifty synced invoices on a new phone must not all draw")
        h.scheduler.visible(listOf("a"))
        drain()
        assertEquals(listOf("a"), h.drawn)
    }

    @Test
    fun nothing_is_drawn_while_an_invoice_editor_is_open() = runTest {
        val h = harness()
        h.pictures += "a"
        h.rows("a" to 1)
        h.scheduler.editorOpen(true)
        h.rows("a" to 2)
        h.rows("a" to 3)
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(h.drawn.isEmpty(), "every keystroke saves; drawing waits")

        h.scheduler.editorOpen(false)
        advanceTimeBy(SETTLE_MILLIS - 1)
        runCurrent()
        assertTrue(h.drawn.isEmpty(), "a second after the editor closes, not straight away")
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("a"), h.drawn, "and once, for the last change")
    }

    @Test
    fun a_waiting_invoice_is_tried_again_on_retry_and_when_it_comes_back_on_screen() = runTest {
        val h = harness()
        h.outcomes["a"] = ThumbnailOutcome.Waiting
        h.rows("a" to 1)
        h.scheduler.visible(listOf("a"))
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf("a"), h.drawn, "a missing template must not be retried in a loop")

        h.scheduler.retry()
        drain()
        assertEquals(listOf("a", "a"), h.drawn)

        h.outcomes["a"] = ThumbnailOutcome.Done
        h.scheduler.visible(emptyList())
        h.scheduler.visible(listOf("a"))
        drain()
        assertEquals(listOf("a", "a", "a"), h.drawn)
    }

    @Test
    fun a_change_while_drawing_is_drawn_again() = runTest {
        val h = harness()
        h.rows("a" to 1)
        h.scheduler.visible(listOf("a"))
        advanceTimeBy(DRAW_MILLIS / 2)
        h.rows("a" to 2)
        drain()
        assertEquals(listOf("a", "a"), h.drawn)
    }

    @Test
    fun a_deleted_invoice_is_not_drawn() = runTest {
        val h = harness()
        h.pictures += "a"
        h.rows("a" to 1)
        h.rows("a" to 2)
        h.rows()
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(h.drawn.isEmpty())
    }

    @Test
    fun after_a_wipe_everything_is_seen_to_afresh() = runTest {
        val h = harness()
        h.rows("a" to 1)
        h.scheduler.visible(listOf("a"))
        drain()
        h.scheduler.reset()
        h.scheduler.visible(emptyList())
        h.scheduler.visible(listOf("a"))
        drain()
        assertEquals(listOf("a", "a"), h.drawn)
    }

    private companion object {
        const val DRAW_MILLIS = 100L
        const val SETTLE_MILLIS = 1_000L
    }
}
