package com.ferbotz.billanta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.records.Invoice
import com.ferbotz.billanta.state.invoiceDateMillis
import com.ferbotz.billanta.model.Paise
import com.ferbotz.billanta.model.format
import com.ferbotz.billanta.model.formatPaise
import com.ferbotz.billanta.model.formatPaiseTrimmed
import com.ferbotz.billanta.model.initialsOf
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.AppIcon
import com.ferbotz.billanta.ui.BillantaIcon

/** Rounded white card container used across the app. */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Int = 16,
    content: @Composable () -> Unit,
) {
    val c = BillantaTheme.colors
    var m = modifier
        .clip(RoundedCornerShape(18.dp))
        .background(c.surface)
        .border(1.dp, c.border, RoundedCornerShape(18.dp))
    if (onClick != null) m = m.clickable(onClick = onClick)
    Box(m.padding(padding.dp)) { content() }
}

@Composable
fun SummaryCard(
    label: String,
    amount: Paise,
    footnote: String,
    footnoteColor: Color,
    modifier: Modifier = Modifier,
) {
    SurfaceCard(modifier, padding = 16) {
        Column {
            Text(label, style = BillantaTheme.type.label, color = BillantaTheme.colors.textSecondary)
            Spacer(Modifier.height(6.dp))
            Text(
                amount.format(withPaise = false),
                style = BillantaTheme.type.amountLarge,
                color = BillantaTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(footnote, style = BillantaTheme.type.caption, color = footnoteColor)
        }
    }
}

/**
 * One invoice in the list, readable at a glance: who it is for (a face, in that customer's own
 * colour), how much, what it was for, and when it was issued and is due.
 *
 * It deliberately says nothing about being paid. An invoice cannot be marked sent or paid yet, so
 * phrasing the due date as "due in 3 days" or "overdue" would be wrong for every invoice that has
 * in fact been paid — the plain dates are the honest version until status exists.
 */
@Composable
fun InvoiceCard(
    invoice: Invoice,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = BillantaTheme.colors
    val customer = invoice.customerName?.takeIf { it.isNotBlank() }
    val lines = invoice.lineSummary
    SurfaceCard(modifier.fillMaxWidth(), onClick = onClick, padding = 0) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (customer != null) {
                    val (bg, fg) = avatarColors(invoice.customerId ?: customer)
                    Avatar(initialsOf(customer), size = 42, bg = bg, fg = fg)
                } else {
                    // Not yet billed to anyone: the document itself stands in for a face.
                    IconTile(AppIcon.Receipt, Modifier.size(42.dp))
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            customer ?: "No customer yet",
                            style = BillantaTheme.type.cardTitle,
                            color = if (customer != null) c.textPrimary else c.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            invoice.computed.grandTotal.formatPaiseTrimmed(),
                            style = BillantaTheme.type.amount,
                            color = c.textPrimary,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${invoice.invoiceNumber.ifBlank { "No number" }} · ${itemCountLabel(lines.count)}",
                        style = BillantaTheme.type.caption,
                        color = c.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    lines.firstDescription?.let { first ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (lines.count > 1) "$first +${lines.count - 1} more" else first,
                            style = BillantaTheme.type.caption.copy(fontSize = 13.sp),
                            color = c.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val syncError = invoice.syncError
                if (syncError != null) {
                    // The one thing on a card that needs the user: the server refused this invoice
                    // (a number already used, say), so it will not sync until it is changed.
                    BillantaIcon(AppIcon.CloudOff, c.danger, size = 14.dp)
                    Text(
                        syncError,
                        style = BillantaTheme.type.caption,
                        color = c.danger,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    BillantaIcon(AppIcon.Calendar, c.textMuted, size = 14.dp)
                    Text(
                        invoice.invoiceDateMillis?.let { "Issued ${Iso8601.formatDayMonth(it)}" } ?: "No date",
                        style = BillantaTheme.type.caption,
                        color = c.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        invoice.values.string("due_date")?.let(Iso8601::parseOrNull)?.let { "Due ${Iso8601.formatDayMonth(it)}" } ?: "Due on receipt",
                        style = BillantaTheme.type.caption,
                        color = c.textSecondary,
                    )
                }
            }
        }
    }
}

private fun itemCountLabel(count: Int): String = when (count) {
    0 -> "no items yet"
    1 -> "1 item"
    else -> "$count items"
}

/** Leading-icon → title/subtitle → trailing row, used in Settings and Customers. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailingText: String? = null,
    trailingIcon: AppIcon? = AppIcon.ChevronRight,
    onClick: (() -> Unit)? = null,
    danger: Boolean = false,
) {
    val c = BillantaTheme.colors
    var m = modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
    if (onClick != null) m = m.clickable(onClick = onClick)
    Row(
        m.padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (leading != null) leading()
        Column(Modifier.weight(1f)) {
            Text(title, style = BillantaTheme.type.bodyStrong, color = if (danger) c.danger else c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = BillantaTheme.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailingText != null) Text(trailingText, style = BillantaTheme.type.label, color = c.textSecondary)
        if (trailingIcon != null) BillantaIcon(trailingIcon, c.textMuted, size = 20.dp)
    }
}

/** Round icon tile used as a leading element in list rows. */
@Composable
fun IconTile(icon: AppIcon, modifier: Modifier = Modifier, tint: Color? = null, bg: Color? = null) {
    val c = BillantaTheme.colors
    Box(
        modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(bg ?: c.surfaceAlt),
        contentAlignment = Alignment.Center,
    ) {
        BillantaIcon(icon, tint ?: c.textSecondary, size = 20.dp)
    }
}
