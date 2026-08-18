package com.ferbotz.billanta.ui

import com.ferbotz.billanta.ui.screens.argbToHsv
import com.ferbotz.billanta.ui.screens.hsvToArgb
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The colour wheel converts both ways on every drag — position to colour, and the stored colour
 * back to a marker position. A conversion that drifts would make the marker sit somewhere the user
 * did not tap, and repeated opens would walk the colour away from what they chose.
 */
class ColorMathTest {

    private fun hex(argb: Long) = argb.toString(16).uppercase()

    @Test
    fun the_primaries_convert_exactly() {
        assertEquals(0xFFFF0000, hsvToArgb(0f, 1f, 1f), "red")
        assertEquals(0xFF00FF00, hsvToArgb(120f, 1f, 1f), "green")
        assertEquals(0xFF0000FF, hsvToArgb(240f, 1f, 1f), "blue")
        assertEquals(0xFFFFFFFF, hsvToArgb(0f, 0f, 1f), "white")
        assertEquals(0xFF000000, hsvToArgb(0f, 0f, 0f), "black")
    }

    @Test
    fun every_colour_survives_a_round_trip() {
        // The presets a user is most likely to pick, plus the app's own accent.
        listOf(
            0xFF5B4FE0, 0xFF0F766E, 0xFFC2410C, 0xFF9333EA, 0xFF15803D,
            0xFFB91C1C, 0xFF2B3648, 0xFF0F172A, 0xFFBE123C, 0xFF4D7C0F,
        ).forEach { original ->
            val (h, s, v) = argbToHsv(original)
            val again = hsvToArgb(h, s, v)
            // One step of rounding per channel is the most 8-bit quantisation can cost.
            listOf(16, 8, 0).forEach { shift ->
                val a = (original shr shift) and 0xFF
                val b = (again shr shift) and 0xFF
                assertTrue(
                    abs(a - b) <= 1,
                    "channel at bit $shift drifted: ${hex(original)} -> ${hex(again)}",
                )
            }
        }
    }

    @Test
    fun the_result_is_always_a_fully_opaque_colour() {
        listOf(0f, 45f, 180f, 359.9f).forEach { hue ->
            listOf(0f, 0.5f, 1f).forEach { sat ->
                listOf(0f, 0.5f, 1f).forEach { value ->
                    val argb = hsvToArgb(hue, sat, value)
                    assertEquals(0xFFL, (argb shr 24) and 0xFF, "alpha lost at $hue/$sat/$value")
                    assertTrue(argb in 0xFF000000..0xFFFFFFFF, "out of range at $hue/$sat/$value")
                }
            }
        }
    }

    @Test
    fun angles_outside_the_circle_wrap_instead_of_clipping() {
        // atan2 gives -180..180, and the wheel adds 360 — so both forms must land on the same red.
        assertEquals(hsvToArgb(0f, 1f, 1f), hsvToArgb(360f, 1f, 1f), "360 should equal 0")
        assertEquals(hsvToArgb(30f, 1f, 1f), hsvToArgb(390f, 1f, 1f), "hue should wrap past 360")
        assertEquals(hsvToArgb(350f, 1f, 1f), hsvToArgb(-10f, 1f, 1f), "a negative hue should wrap")
    }

    @Test
    fun out_of_range_saturation_and_value_are_clamped_not_wrapped() {
        assertEquals(hsvToArgb(200f, 1f, 1f), hsvToArgb(200f, 5f, 9f), "over-range should clamp high")
        assertEquals(hsvToArgb(200f, 0f, 0f), hsvToArgb(200f, -3f, -3f), "under-range should clamp low")
    }

    @Test
    fun a_grey_reports_no_hue_rather_than_a_random_one() {
        val (h, s, _) = argbToHsv(0xFF808080)
        assertEquals(0f, s, 0.001f, "grey has no saturation")
        assertEquals(0f, h, 0.001f, "an undefined hue should settle at 0, not drift")
    }
}
