package com.ferbotz.billanta.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.ferbotz.billanta.domain.model.TemplateInfo
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.AppIcon
import com.ferbotz.billanta.ui.BillantaIcon

/** Width over height of an A4 page — the only page size a template has in V1 (`page.size`). */
const val A4_ASPECT = 595f / 842f

/** The corner of a template card in the grids: the page and the card's tap ripple both use it. */
val TemplateCardShape = RoundedCornerShape(16.dp)

/**
 * Keeps the name and category under a template card inside the card's rounded corners.
 *
 * A card is a single tap target, so its ripple is clipped to [TemplateCardShape], and the label
 * used to run flush to the card's edges. The curve of the bottom corners then cut the first letter
 * of the category and the end of "Free"/"Premium". For a corner of radius r, a point inset h from
 * the side and b from the bottom clears the curve when (r − h)² + (r − b)² ≤ r²; 4 dp and 8 dp
 * clear a 16 dp corner with room to spare, while the text still lines up with the page above it.
 */
val TemplateLabelPadding = PaddingValues(start = 4.dp, end = 4.dp, bottom = 8.dp)

private const val THUMBNAIL_FADE_MILLIS = 220

/**
 * A template's first page, page-shaped, wherever the user chooses between templates.
 *
 * The server's thumbnail when there is one — APP-012 asks for a 640 px WebP of page 1 filled with
 * sample data. While it loads, if it fails to, and for a template that has none, a drawn sketch of
 * an invoice stands in, so a card is never a blank rectangle. The first-run picker used to be
 * exactly that, because no template had a thumbnail.
 *
 * The sketch fades out as the image fades in rather than simply lying underneath it, so a
 * thumbnail with any transparency cannot show the sketch through.
 *
 * [badges] are drawn over the page — the premium mark, the selected tick — and [borderColor] draws
 * over the image too, so a selection outline is never hidden by the picture it outlines.
 */
@Composable
fun TemplateThumbnail(
    template: TemplateInfo,
    modifier: Modifier = Modifier,
    shape: Shape = TemplateCardShape,
    borderWidth: Dp = 1.dp,
    borderColor: Color = BillantaTheme.colors.border,
    badges: @Composable BoxScope.() -> Unit = {},
) {
    val url = template.thumbnailUrl
    var loaded by remember(url) { mutableStateOf(false) }
    val sketchAlpha by animateFloatAsState(
        targetValue = if (loaded) 0f else 1f,
        animationSpec = tween(THUMBNAIL_FADE_MILLIS),
    )
    Box(
        modifier
            .aspectRatio(A4_ASPECT)
            .clip(shape)
            // Paper, not a theme surface: a page is white in dark mode too, and a premium sketch
            // paints its own dark page, so fading between the two never passes through a third colour.
            .background(Color.White)
            .border(borderWidth, borderColor, shape),
    ) {
        if (sketchAlpha > 0f) {
            PageSketch(
                premium = template.isPremium,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = sketchAlpha },
            )
        }
        if (url != null) {
            val context = LocalPlatformContext.current
            val request = remember(url, context) {
                ImageRequest.Builder(context).data(url).crossfade(THUMBNAIL_FADE_MILLIS).build()
            }
            AsyncImage(
                model = request,
                // Decorative: the template's name is printed right under its page, and reading it
                // twice is all a description here would add.
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onSuccess = { loaded = true },
                onError = { loaded = false },
                modifier = Modifier.fillMaxSize(),
            )
        }
        badges()
    }
}

/** The "Premium" pill a premium template wears in the corner of its page. */
@Composable
fun PremiumBadge(modifier: Modifier = Modifier) {
    val c = BillantaTheme.colors
    Row(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(c.primary)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        BillantaIcon(AppIcon.Star, c.onPrimary, size = 12.dp)
        Text(
            "Premium",
            style = BillantaTheme.type.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
            color = c.onPrimary,
        )
    }
}

/**
 * A drawn invoice: a letterhead, a rule, four line items and a total.
 *
 * Laid out on a 24-unit grid across the page rather than in fixed dp, so the same sketch reads
 * correctly on a ~170 dp grid card and on the ~96 dp cards in an invoice's template switcher. The
 * premium variant is the dark page the premium cards have always shown.
 */
@Composable
private fun PageSketch(premium: Boolean, modifier: Modifier = Modifier) {
    val accent = if (premium) Color.White else BillantaTheme.colors.primary
    val ink = if (premium) PremiumInk else PaperInk
    Canvas(modifier) {
        if (premium) drawRect(PremiumPaper)
        val u = size.width / 24f
        val left = 2f * u
        val right = size.width - 2f * u
        val corner = CornerRadius(0.3f * u)
        fun bar(x: Float, y: Float, width: Float, height: Float, color: Color) =
            drawRoundRect(color, Offset(x, y), Size(width, height), corner)

        var y = 2f * u
        bar(left, y, 3.6f * u, 1.1f * u, accent) // business name
        bar(right - 2.3f * u, y, 2.3f * u, 0.8f * u, ink) // invoice number
        y += 1.95f * u
        drawRect(accent, Offset(left, y), Size(right - left, maxOf(0.28f * u, 1f))) // rule
        y += 2.25f * u
        repeat(4) { // line items: description, amount
            bar(left, y, (right - left) * 0.5f, 0.7f * u, ink)
            bar(right - 2.5f * u, y, 2.5f * u, 0.7f * u, ink)
            y += 1.55f * u
        }
        bar(right - 4.8f * u, size.height - 3.1f * u, 4.8f * u, 1.1f * u, accent) // total
    }
}

private val PaperInk = Color(0xFFE6E8EC)
private val PremiumPaper = Color(0xFF17171C)
private val PremiumInk = Color(0xFF3A3A45)
