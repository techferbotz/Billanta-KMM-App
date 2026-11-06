package com.ferbotz.billanta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.domain.model.InvoiceDocStatus
import com.ferbotz.billanta.model.formatPaise
import com.ferbotz.billanta.model.formatPaiseTrimmed
import com.ferbotz.billanta.records.Invoice
import com.ferbotz.billanta.state.invoiceDateMillis
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.AppIcon
import com.ferbotz.billanta.ui.BillantaIcon
import okio.Path

/** The part of page 1 a card shows: all of its width and the top 60% of its height (§12.4). */
private const val CARD_ASPECT = A4_ASPECT / 0.6f
private const val PICTURE_FADE_MILLIS = 220

/**
 * One invoice in the list: a picture of its page 1, with what matters on top (BE-023,
 * FIELDS_APP_GUIDE.md §12.4).
 *
 * The picture is drawn on the phone and arrives when it arrives. Until then the card is neutral
 * grey with the overlay already filled in, so the list reads at once — offline, or on a phone that
 * has just been set up — and the picture fades in over it.
 */
@Composable
fun InvoiceThumbnailCard(
    invoice: Invoice,
    picture: Path?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = BillantaTheme.colors
    val number = invoice.invoiceNumber.ifBlank { "Draft" }
    val customer = invoice.customerName?.takeIf { it.isNotBlank() } ?: "No customer"
    val amount = amountOf(invoice)
    val date = invoice.invoiceDateMillis?.let(Iso8601::formatDayMonth)
    val status = statusLook(invoice.status)
    val syncError = invoice.syncError
    val spoken = listOfNotNull(
        "Invoice $number", customer, amount, date, status.label,
        syncError?.let { "Not synced: $it" },
    ).joinToString(", ")

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(CARD_ASPECT)
            .clip(TemplateCardShape)
            .background(c.surfaceAlt)
            // Drawn over the picture, so a white page still has an edge on a light background.
            .border(1.dp, c.border, TemplateCardShape)
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
            },
    ) {
        if (picture != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(picture)
                    .crossfade(PICTURE_FADE_MILLIS)
                    .build(),
                contentDescription = null,
                // Full width, top-anchored: the card shows the head of the page and crops the rest.
                contentScale = ContentScale.FillWidth,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Darkens the bottom so the white text reads over any page.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f)),
            ),
        )
        if (syncError != null) {
            // The one thing on a card that needs the user: the server refused this invoice (a
            // number already used, say), so it won't sync until it is changed.
            Box(
                Modifier.align(Alignment.TopEnd).padding(8.dp).size(26.dp).clip(CircleShape).background(c.danger),
                contentAlignment = Alignment.Center,
            ) { BillantaIcon(AppIcon.CloudOff, Color.White, size = 15.dp) }
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    number,
                    style = BillantaTheme.type.label.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    status.label,
                    style = BillantaTheme.type.overline,
                    color = status.foreground,
                    maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(status.background)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                customer,
                style = BillantaTheme.type.caption,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(1.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(amount, style = BillantaTheme.type.label.copy(fontWeight = FontWeight.Bold), color = Color.White, maxLines = 1)
                if (date != null) {
                    Text(date, style = BillantaTheme.type.caption, color = Color.White.copy(alpha = 0.85f), maxLines = 1)
                }
            }
        }
    }
}

/** The whole-card placeholder the list shows before its first rows load. */
@Composable
fun InvoiceThumbnailCardSkeleton(alpha: Float, modifier: Modifier = Modifier) {
    val c = BillantaTheme.colors
    Box(
        modifier.fillMaxWidth().aspectRatio(CARD_ASPECT).clip(TemplateCardShape).background(c.surfaceAlt)
            .border(1.dp, c.border, TemplateCardShape),
    ) {
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp)) {
            SkeletonBar(0.5f, 12, alpha)
            Spacer(Modifier.height(6.dp))
            SkeletonBar(0.7f, 10, alpha)
            Spacer(Modifier.height(6.dp))
            SkeletonBar(0.4f, 10, alpha)
        }
    }
}

@Composable
private fun SkeletonBar(widthFraction: Float, heightDp: Int, alpha: Float) {
    Box(
        Modifier.fillMaxWidth(widthFraction).height(heightDp.dp).clip(RoundedCornerShape(5.dp))
            .background(BillantaTheme.colors.border.copy(alpha = alpha)),
    )
}

private class StatusLook(val label: String, val foreground: Color, val background: Color)

@Composable
private fun statusLook(status: InvoiceDocStatus): StatusLook {
    val c = BillantaTheme.colors
    return when (status) {
        InvoiceDocStatus.Draft -> StatusLook("Draft", c.draft, c.draftBg)
        InvoiceDocStatus.Pending -> StatusLook("Pending", c.warning, c.warningBg)
        InvoiceDocStatus.Paid -> StatusLook("Paid", c.success, c.successBg)
    }
}

/** The total in the invoice's own currency (§12.4): rupees as the app writes them everywhere else. */
private fun amountOf(invoice: Invoice): String {
    val paise = invoice.computed.grandTotal
    val currency = invoice.values.string(Invoice.CURRENCY)
    return if (currency.isNullOrBlank() || currency.equals("INR", ignoreCase = true)) {
        paise.formatPaiseTrimmed()
    } else {
        "$currency ${paise.formatPaise(withPaise = paise % 100 != 0L, withSymbol = false)}"
    }
}
