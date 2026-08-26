package com.ferbotz.billanta.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Google's multicolour "G", drawn rather than tinted.
 *
 * This is not an [AppIcon]: those are single-colour glyphs stroked in whatever tint the caller
 * passes, and Google's brand mark may not be recoloured. It is drawn straight from the supplied
 * SVG's path data via [PathParser], so the geometry is the artwork's own rather than a redrawing.
 *
 * Two deliberate departures from the source file:
 *
 *  - **The blur filters are dropped.** Eight of the nine are `stdDeviation` 0.2351 on a 269-unit
 *    viewport — under a tenth of a percent — and the largest is 1.65. At the ~20dp this renders at,
 *    none of them is a visible pixel, and Compose has no cheap per-shape blur to spend on it.
 *  - **Elliptical gradients become circular**, with the radius as the geometric mean of the
 *    ellipse's axes. Compose radial brushes take one radius; the alternative is a per-shape canvas
 *    transform for a difference that is sub-pixel at this size.
 *
 * The luminance mask in the SVG is a single filled path, which is exactly a clip, so it is one.
 */
@Composable
fun GoogleMark(size: Dp = 20.dp, modifier: Modifier = Modifier) {
    val paths = remember { GoogleG.shapes.map { it to PathParser().parsePathString(it.data).toPath() } }
    val clip = remember { PathParser().parsePathString(GoogleG.MASK).toPath() }

    Canvas(modifier.size(size)) {
        // The artwork is authored in a 269 x 274 viewport; scale it onto whatever we were given.
        scale(this.size.width / GoogleG.VIEWPORT_W, this.size.height / GoogleG.VIEWPORT_H, Offset.Zero) {
            clipPath(clip) {
                paths.forEach { (shape, path) -> drawPath(path, shape.brush(), alpha = shape.alpha) }
            }
        }
    }
}

/** The supplied artwork, kept as its own path data so it can be re-exported without redrawing. */
internal object GoogleG {

    const val VIEWPORT_W = 269f
    const val VIEWPORT_H = 274f

    /** `mask-type: luminance` over a single white path — i.e. a clip to the G's silhouette. */
    const val MASK =
        "M265.577 111.534H136.933V164.184H210.853C209.664 171.635 206.996 178.965 203.089 185.648C198.611 " +
            "193.307 193.077 199.136 187.405 203.576C170.411 216.875 150.601 219.594 136.843 219.594C102.086 " +
            "219.594 72.391 196.651 60.894 165.476C60.4304 164.344 60.1229 163.176 59.7474 162.021C57.1467 " +
            "153.932 55.8205 145.468 55.819 136.948C55.819 127.858 57.3219 119.158 60.0625 110.94C70.8727 " +
            "78.5279 101.239 54.3203 136.868 54.3203C144.034 54.3203 150.935 55.1912 157.479 56.9292C169.437 " +
            "60.0982 180.44 66.2774 189.498 74.91L228.613 35.7838C204.82 13.5014 173.802 -0.000488281 136.802 " +
            "-0.000488281C107.224 -0.000488281 79.9154 9.4116 57.5365 25.3204C39.3887 38.2203 24.5045 55.4937 " +
            "14.4588 75.5534C5.11624 94.1539 0 114.766 0 136.929C0 159.092 5.12487 179.918 14.4675 " +
            "198.346V198.47C24.336 218.034 38.768 234.879 56.3075 247.721C71.6313 258.939 99.1074 273.88 " +
            "136.802 273.88C158.48 273.88 177.693 269.889 194.637 262.408C206.86 257.011 217.689 249.972 " +
            "227.494 240.926C240.45 228.973 250.597 214.187 257.522 197.177C264.448 180.166 268.152 160.929 " +
            "268.152 140.074C268.152 130.362 267.196 120.498 265.577 111.534Z"

    class Shape(
        val data: String,
        private val fill: Fill,
        val alpha: Float = 1f,
    ) {
        fun brush(): Brush = fill.brush()
    }

    sealed interface Fill {
        fun brush(): Brush

        /** A flat colour. */
        class Solid(private val argb: Long) : Fill {
            override fun brush() = Brush.linearGradient(listOf(Color(argb), Color(argb)))
        }

        /**
         * A radial ramp. [radius] is supplied by the caller from the SVG's gradient transform —
         * see [radiusOf] for how an ellipse is reduced to one number.
         */
        class Radial(
            private val cx: Float,
            private val cy: Float,
            private val radius: Float,
            private val stops: Array<Pair<Float, Long>>,
        ) : Fill {
            override fun brush() = Brush.radialGradient(
                colorStops = stops.map { it.first to Color(it.second) }.toTypedArray(),
                center = Offset(cx, cy),
                radius = radius,
            )
        }

        class Linear(
            private val from: Offset,
            private val to: Offset,
            private val stops: Array<Pair<Float, Long>>,
        ) : Fill {
            override fun brush() = Brush.linearGradient(
                colorStops = stops.map { it.first to Color(it.second) }.toTypedArray(),
                start = from,
                end = to,
            )
        }
    }

    /**
     * One radius for an SVG gradient transform that describes an ellipse.
     *
     * The transform maps the unit circle through two basis vectors; their lengths are the ellipse's
     * semi-axes. The geometric mean preserves the ellipse's area, which keeps the ramp reaching the
     * same average distance as the original rather than favouring either axis.
     */
    private fun radiusOf(ax: Float, ay: Float, bx: Float, by: Float): Float =
        sqrt(hypot(ax, ay) * hypot(bx, by))

    val shapes: List<Shape> = listOf(
        // Bottom-left sweep: green through yellow.
        Shape(
            "M-1.97229 137.858C-1.83052 159.671 4.25515 182.177 13.4665 200.346V200.471C20.1231 213.666 " +
                "29.2195 224.088 39.5794 234.415L102.157 211.094C90.3176 204.951 88.5109 201.188 80.0247 " +
                "194.319C71.3517 185.387 64.8877 175.134 60.8625 163.11H60.6996L60.8625 162.985C58.2138 " +
                "155.045 57.9533 146.617 57.8546 137.858H-1.97229Z",
            Fill.Radial(
                100.904f, 232.359f, radiusOf(136.506f, 0f, 0f, 200.511f),
                arrayOf(
                    0.142f to 0xFF1ABD4D, 0.248f to 0xFF6EC30D, 0.312f to 0xFF8AC502,
                    0.366f to 0xFFA2C600, 0.446f to 0xFFC8C903, 0.540f to 0xFFEBCB03,
                    0.616f to 0xFFF7CD07, 0.699f to 0xFFFDCD04, 0.771f to 0xFFFDCE05,
                    0.861f to 0xFFFFCE0A,
                ),
            ),
        ),
        // Top-right: the red arm.
        Shape(
            "M136.933 -0.996582C130.748 21.1972 133.113 42.7692 136.933 55.3213C144.076 55.3272 150.957 " +
                "56.1962 157.48 57.9292C169.438 61.0969 180.441 67.2763 189.498 75.9101L229.615 " +
                "35.7848C205.849 13.528 177.249 -0.962098 136.933 -0.996582Z",
            Fill.Radial(
                225.862f, 73.0076f, radiusOf(94.6304f, 0f, 0f, 122.21f),
                arrayOf(0.408f to 0xFFFB4E5A, 1f to 0xFFFF4540),
            ),
        ),
        // Top-left: red into orange.
        Shape(
            "M136.799 -1.17188C106.461 -1.17188 78.4517 8.48161 55.499 24.7983C47.0031 30.8349 39.1858 " +
                "37.8238 32.1909 45.6364C30.367 63.1187 45.8479 84.604 76.5062 84.4257C91.3808 66.7542 " +
                "113.381 55.3194 137.867 55.3194L137.934 55.3214L136.934 -1.16794L136.799 -1.17188Z",
            Fill.Radial(
                174.182f, -18.8626f, radiusOf(-132.585f, 73.4334f, 99.649f, 179.917f),
                arrayOf(
                    0.231f to 0xFFFF4541, 0.312f to 0xFFFF4540, 0.458f to 0xFFFF4640,
                    0.540f to 0xFFFF473F, 0.699f to 0xFFFF5138, 0.771f to 0xFFFF5B33,
                    0.861f to 0xFFFF6C29, 1f to 0xFFFF8C18,
                ),
            ),
        ),
        // Right and bottom: green into blue.
        Shape(
            "M236.932 144.184L209.853 163.184C208.666 170.635 205.995 177.965 202.088 184.649C197.61 " +
                "192.307 192.077 198.137 186.404 202.577C169.446 215.848 149.688 218.581 135.934 " +
                "218.592C121.716 243.324 119.224 255.711 136.933 275.671C158.847 275.656 178.274 271.615 " +
                "195.41 264.049C207.797 258.58 218.772 251.446 228.708 242.278C241.837 230.165 252.122 " +
                "215.181 259.14 197.942C266.159 180.703 269.912 161.21 269.912 140.074L236.932 144.184Z",
            Fill.Radial(
                138.901f, 257.946f, radiusOf(-240.448f, -313.876f, -115.86f, 88.7552f),
                arrayOf(
                    0.132f to 0xFF0CBA65, 0.210f to 0xFF0BB86D, 0.297f to 0xFF09B479,
                    0.396f to 0xFF08AD93, 0.477f to 0xFF0AA6A9, 0.568f to 0xFF0D9CC6,
                    0.667f to 0xFF1893DD, 0.769f to 0xFF258BF1, 0.859f to 0xFF3086FF,
                ),
            ),
        ),
        // The blue crossbar.
        Shape(
            "M134.934 109.534V166.184H265.217C266.362 158.426 270.152 148.386 270.152 140.074C270.152 " +
                "130.362 269.198 118.498 267.578 109.534H134.934Z",
            Fill.Solid(0xFF3086FF),
        ),
        // Left: red into yellow.
        Shape(
            "M32.8136 43.6372C24.7728 52.6217 17.9045 62.6781 12.4587 73.5533C3.11613 92.1539 -2.00012 " +
                "114.766 -2.00012 136.929C-2.00012 137.241 -1.97522 137.546 -1.97234 137.859C2.16587 " +
                "145.961 55.1829 144.41 57.8555 137.859C57.8517 137.553 57.8181 137.255 57.8181 " +
                "136.948C57.8181 127.858 59.3221 121.159 62.0617 112.941C65.4432 102.803 70.7367 93.4692 " +
                "77.5063 85.4266C79.0409 83.4255 83.1341 79.1239 84.3276 76.5436C84.7827 75.5613 83.5019 " +
                "75.0095 83.4301 74.6637C83.3505 74.2765 81.633 74.5878 81.2489 74.2992C80.0275 73.3839 " +
                "77.6088 72.906 76.1393 72.4814C73.0002 71.574 67.7978 69.5719 64.9077 67.496C55.773 " +
                "60.9362 41.5191 53.0995 32.8136 43.6372Z",
            Fill.Radial(
                125.183f, 24.6955f, radiusOf(147.649f, 0f, 0f, 204.151f),
                arrayOf(
                    0.366f to 0xFFFF4E3A, 0.458f to 0xFFFF8A1B, 0.540f to 0xFFFFA312,
                    0.616f to 0xFFFFB60C, 0.771f to 0xFFFFCD0A, 0.861f to 0xFFFECF0A,
                    0.915f to 0xFFFECF08, 1f to 0xFFFDCD01,
                ),
            ),
        ),
        // Upper-left highlight.
        Shape(
            "M65.1042 74.7023C86.2857 87.8071 92.3771 68.0874 106.46 61.9167L81.9626 10.0295C73.0251 " +
                "13.8662 64.5032 18.6557 56.5365 24.3196C44.7388 32.7061 34.3204 42.9408 25.7145 " +
                "54.5865L65.1042 74.7023Z",
            Fill.Radial(
                101.256f, 23.1727f, radiusOf(-49.184f, 54.3963f, -153.428f, -138.726f),
                arrayOf(
                    0.316f to 0xFFFF4C3C, 0.604f to 0xFFFF692C, 0.727f to 0xFFFF7825,
                    0.885f to 0xFFFF8D1B, 1f to 0xFFFF9F13,
                ),
            ),
        ),
        // Bottom: the green foot.
        Shape(
            "M73.7253 207.085C45.2914 217.569 40.8399 217.946 38.2229 235.943C43.2401 240.946 48.6172 " +
                "245.551 54.3083 249.721C69.6312 260.939 99.1064 275.88 136.802 275.88L136.934 " +
                "275.877V217.592L136.844 217.594C122.728 217.594 111.447 213.808 99.8823 " +
                "207.222C97.0306 205.599 91.8568 209.958 89.2273 208.01C85.6007 205.321 76.8702 210.325 " +
                "73.7253 207.085Z",
            Fill.Radial(
                174.182f, 292.739f, radiusOf(-132.585f, -73.4334f, 99.649f, -179.917f),
                arrayOf(
                    0.231f to 0xFF0FBC5F, 0.312f to 0xFF0FBC5F, 0.366f to 0xFF0FBC5E,
                    0.458f to 0xFF0FBC5D, 0.540f to 0xFF12BC58, 0.699f to 0xFF28BF3C,
                    0.771f to 0xFF38C02B, 0.861f to 0xFF52C218, 0.915f to 0xFF67C30F,
                    1f to 0xFF86C504,
                ),
            ),
        ),
        // A soft seam down the middle of the foot; half-opacity in the source too.
        Shape(
            "M120.281 215.757V274.868C125.556 275.498 131.044 275.88 136.803 275.88C142.575 275.88 " +
                "148.159 275.578 153.585 275.021V216.155C148.054 217.114 142.453 217.595 136.844 " +
                "217.594C131.161 217.594 125.636 216.918 120.281 215.757Z",
            Fill.Linear(
                Offset(120.281f, 245.819f), Offset(153.585f, 245.819f),
                arrayOf(0f to 0xFF0FBC5C, 1f to 0xFF0CBA65),
            ),
            alpha = 0.5f,
        ),
    )
}
