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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.domain.model.InvoiceDocStatus
import com.ferbotz.billanta.domain.model.InvoiceRecord
import com.ferbotz.billanta.model.Paise
import com.ferbotz.billanta.model.format
import com.ferbotz.billanta.model.formatPaise
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

@Composable
fun InvoiceCard(
    invoice: InvoiceRecord,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = BillantaTheme.colors
    SurfaceCard(modifier.fillMaxWidth(), onClick = onClick, padding = 16) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        invoice.customerName ?: "No customer",
                        style = BillantaTheme.type.cardTitle,
                        color = c.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // Sync at a glance: red = server rejected it, amber = waiting to push.
                    when {
                        invoice.syncError != null -> ColorDot(c.danger, size = 7)
                        invoice.pendingSync -> ColorDot(c.accentDot, size = 7)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "${invoice.invoiceNumber} · ${Iso8601.formatDisplayDate(invoice.invoiceDateMillis)}",
                    style = BillantaTheme.type.caption,
                    color = c.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(invoice.grandTotalPaise.formatPaise(), style = BillantaTheme.type.amount, color = c.textPrimary, maxLines = 1)
                Spacer(Modifier.height(8.dp))
                StatusPill(invoice.status)
            }
        }
    }
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
