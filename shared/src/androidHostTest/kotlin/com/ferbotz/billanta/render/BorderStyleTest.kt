package com.ferbotz.billanta.render

import com.ferbotz.billanta.render.layout.DrawCommand
import com.ferbotz.billanta.render.layout.dashPatternFor
import com.ferbotz.billanta.render.layout.dashRuns
import com.ferbotz.billanta.render.layout.flattenCommands
import com.ferbotz.billanta.render.text.FakeTextShaper
import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.domain.model.InvoiceRecord
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `border-*-style` was on the wire from the start — TEMPLATE_JSON.md documents it and the compiler
 * emits it — but the app dropped it, so an authored dashed border rendered solid. That matters now
 * that templates are going to draw their own dashed empty-state boxes (APP-008).
 */
class BorderStyleTest {

    private val renderer = InvoiceRenderer(FakeTextShaper())

    private fun record() = InvoiceRecord(
        id = "inv-1",
        invoiceNumber = "INV-1",
        invoiceDateMillis = Iso8601.epochMillisFor(2026, 8, 12),
        updatedAtMillis = 1L,
    )

    /** One box, so the assertions are about the border and nothing else. */
    private fun boxWith(style: String): TemplateDoc = assertNotNull(
        TemplateParser.parse(
            """
            { "schemaVersion": 1, "compilerVersion": 1,
              "page": { "size": "A4", "margin": { "top": 0, "right": 0, "bottom": 0, "left": 0 } },
              "root": { "type": "box", "style": {
                  "width": 200, "height": 100,
                  "borderTopWidth": 2, "borderRightWidth": 2,
                  "borderBottomWidth": 2, "borderLeftWidth": 2,
                  "borderTopColor": "#FF0000", "borderRightColor": "#FF0000",
                  "borderBottomColor": "#FF0000", "borderLeftColor": "#FF0000",
                  $style
                }, "children": [] } }
            """,
        ),
    )

    private fun bordersOf(doc: TemplateDoc): DrawCommand.Borders {
        val commands = renderer.render(doc, record()).pages.flatMap { it.commands.flattenCommands() }
        return assertNotNull(
            commands.filterIsInstance<DrawCommand.Borders>().firstOrNull(),
            "no border command was emitted at all",
        )
    }

    @Test
    fun a_dashed_border_survives_parsing() {
        val borders = bordersOf(
            boxWith(
                """"borderTopStyle": "dashed", "borderRightStyle": "dashed",
                   "borderBottomStyle": "dashed", "borderLeftStyle": "dashed"""",
            ),
        )
        assertEquals(BorderStyle.Dashed, borders.styles.top)
        assertEquals(BorderStyle.Dashed, borders.styles.left)
    }

    @Test
    fun an_absent_style_stays_solid_and_an_unknown_one_does_not_break_the_border() {
        assertNull(bordersOf(boxWith(""""borderRadius": 0""")).styles.top, "absent should mean solid")

        // The forward-compat rule: a keyword this build has never heard of must still draw a
        // border, not vanish. `groove` is real CSS the compiler could legitimately start emitting.
        val exotic = bordersOf(boxWith(""""borderTopStyle": "groove""""))
        assertEquals(BorderStyle.Solid, exotic.styles.top, "an unknown keyword should fall back to solid")
    }

    @Test
    fun a_dashed_edge_is_drawn_as_several_bands_and_a_solid_one_as_a_single_band() {
        val dashPattern = assertNotNull(dashPatternFor(widthPt = 2f, style = BorderStyle.Dashed))
        val runs = dashRuns(lengthPt = 100f, pattern = dashPattern)
        assertTrue(runs.size > 1, "a 100pt dashed edge should be more than one band")

        assertNull(dashPatternFor(widthPt = 2f, style = BorderStyle.Solid), "solid has no pattern")
        assertNull(dashPatternFor(widthPt = 2f, style = null), "absent has no pattern")
    }

    /**
     * A stub at one corner reads as a rendering fault rather than a style, so the pattern is scaled
     * to fit a whole number of dashes with one at each end.
     */
    @Test
    fun dashes_start_and_end_flush_with_the_edge() {
        listOf(10f, 37.5f, 100f, 512.25f).forEach { length ->
            val runs = dashRuns(length, floatArrayOf(6f, 4f))
            assertTrue(runs.isNotEmpty(), "$length produced no dashes")
            assertEquals(0f, runs.first().first, 0.01f, "the first dash should start at the edge")
            val end = runs.last().first + runs.last().second
            assertTrue(abs(end - length) < 0.01f, "the last dash ended at $end, not $length")
            // No dash may run past the edge, or a box's border would bleed into its neighbour.
            runs.forEach {
                assertTrue(it.first >= -0.01f && it.first + it.second <= length + 0.01f, "dash $it escaped $length")
            }
        }
    }

    @Test
    fun a_degenerate_edge_produces_nothing_rather_than_looping() {
        assertTrue(dashRuns(0f, floatArrayOf(6f, 4f)).isEmpty(), "a zero-length edge has no dashes")
        assertTrue(dashRuns(-5f, floatArrayOf(6f, 4f)).isEmpty(), "a negative edge has no dashes")
        assertTrue(dashRuns(50f, floatArrayOf(0f, 4f)).isEmpty(), "a zero-length dash has no bands")
        // Shorter than one dash: a single band covering the edge beats an empty border.
        assertEquals(listOf(0f to 3f), dashRuns(3f, floatArrayOf(6f, 4f)))
    }

    /**
     * The preview and the PDF have to be the same picture, so both painters take their pattern from
     * the same place. If they ever diverge, the file the customer receives stops matching what the
     * user approved on screen.
     */
    @Test
    fun both_painters_share_one_dash_pattern() {
        listOf(0.5f, 1f, 1.5f, 3f).forEach { width ->
            val dashed = assertNotNull(dashPatternFor(width, BorderStyle.Dashed))
            val dotted = assertNotNull(dashPatternFor(width, BorderStyle.Dotted))
            assertEquals(2, dashed.size, "a pattern is dash+gap")
            assertTrue(dashed[0] > dotted[0], "a dash should be longer than a dot at width $width")
            assertTrue(dashed.all { it > 0f } && dotted.all { it > 0f }, "no zero-length run at $width")
        }
    }

    @Test
    fun the_style_survives_a_theme_recolour() {
        val doc = assertNotNull(
            TemplateParser.parse(
                """
                { "schemaVersion": 1, "compilerVersion": 1,
                  "theme": { "tokens": { "accent": { "default": "#FF0000", "label": "Accent" } } },
                  "page": { "size": "A4", "margin": { "top": 0, "right": 0, "bottom": 0, "left": 0 } },
                  "root": { "type": "box",
                    "tokens": { "borderTopColor": "accent" },
                    "style": { "width": 200, "height": 100, "borderTopWidth": 2,
                               "borderTopColor": "#FF0000", "borderTopStyle": "dashed" },
                    "children": [] } }
                """,
            ),
        )
        val commands = renderer
            .render(doc, record(), theme = InvoiceTheme(colorOverrides = mapOf("accent" to 0xFF00FF00)))
            .pages.flatMap { it.commands.flattenCommands() }
        val borders = assertNotNull(commands.filterIsInstance<DrawCommand.Borders>().firstOrNull())

        assertEquals(0xFF00FF00, borders.colors.top, "the override should have applied")
        assertEquals(BorderStyle.Dashed, borders.styles.top, "recolouring must not reset the style to solid")
    }
}
