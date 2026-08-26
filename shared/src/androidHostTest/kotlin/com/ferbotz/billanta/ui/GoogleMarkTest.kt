package com.ferbotz.billanta.ui

import androidx.compose.ui.graphics.vector.PathParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Google mark is the supplied SVG's own path data, transcribed into Kotlin and split across
 * source lines. A dropped space at one of those joins would not fail to compile, and often would
 * not even fail to parse — in SVG a minus sign is its own separator, so two merged numbers stay
 * syntactically valid while the geometry quietly bends.
 *
 * So this compares what the app draws against `design/google-g.svg` character for character. It
 * also fails if someone replaces the SVG without updating the code, which is the other half.
 */
class GoogleMarkTest {

    private fun sourceSvg(): String {
        val file = listOf(
            File("../design/google-g.svg"),
            File("design/google-g.svg"),
        ).firstOrNull { it.exists() }
        return assertNotNull(file, "design/google-g.svg not found").readText()
    }

    /** Every `d="…"` in the file, normalised so line wrapping cannot matter. */
    private fun pathsInSvg(): List<String> =
        Regex("""\sd="([^"]+)"""").findAll(sourceSvg())
            .map { it.groupValues[1].replace(Regex("\\s+"), " ").trim() }
            .toList()

    private fun String.normalised() = replace(Regex("\\s+"), " ").trim()

    @Test
    fun every_drawn_path_matches_the_source_svg() {
        val fromSvg = pathsInSvg().toSet()
        assertTrue(fromSvg.isNotEmpty(), "no paths found in the svg")

        GoogleG.shapes.forEachIndexed { i, shape ->
            assertTrue(
                shape.data.normalised() in fromSvg,
                "shape $i does not match any path in design/google-g.svg — a transcription slip",
            )
        }
    }

    @Test
    fun the_clip_matches_the_masks_path() {
        assertTrue(
            GoogleG.MASK.normalised() in pathsInSvg().toSet(),
            "the clip path does not match the mask in design/google-g.svg",
        )
    }

    @Test
    fun the_artwork_is_all_there() {
        // 10 paths in the file: one mask plus the nine drawn shapes.
        assertEquals(10, pathsInSvg().size, "the source svg no longer has ten paths")
        assertEquals(9, GoogleG.shapes.size, "the source svg has nine drawn shapes")
    }

    /**
     * Parsing only — `toPath()` needs android.graphics.Path, which is not available on the JVM test
     * classpath. This still catches malformed data, which is what the transcription risks.
     */
    @Test
    fun every_path_parses_into_nodes() {
        assertTrue(PathParser().parsePathString(GoogleG.MASK).toNodes().isNotEmpty(), "mask parsed to nothing")
        GoogleG.shapes.forEachIndexed { i, shape ->
            assertTrue(
                PathParser().parsePathString(shape.data).toNodes().isNotEmpty(),
                "shape $i parsed to no nodes",
            )
        }
    }

    @Test
    fun the_viewport_matches_the_source() {
        val svg = sourceSvg()
        val viewBox = assertNotNull(Regex("""viewBox="([^"]+)"""").find(svg)).groupValues[1].split(" ")
        assertEquals(viewBox[2].toFloat(), GoogleG.VIEWPORT_W, "width drifted from the svg's viewBox")
        assertEquals(viewBox[3].toFloat(), GoogleG.VIEWPORT_H, "height drifted from the svg's viewBox")
    }
}
