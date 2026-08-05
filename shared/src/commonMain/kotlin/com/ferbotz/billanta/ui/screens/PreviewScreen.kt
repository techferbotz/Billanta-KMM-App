package com.ferbotz.billanta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.domain.model.CompanySnapshot
import com.ferbotz.billanta.domain.model.InvoiceDocStatus
import com.ferbotz.billanta.domain.model.InvoiceRecord
import com.ferbotz.billanta.domain.model.toSnapshot
import com.ferbotz.billanta.model.formatPaise
import com.ferbotz.billanta.state.BillantaState
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.AppIcon
import com.ferbotz.billanta.ui.BillantaIcon
import com.ferbotz.billanta.ui.components.Overline
import com.ferbotz.billanta.ui.components.PrimaryButton
import com.ferbotz.billanta.ui.components.SecondaryButton
import com.ferbotz.billanta.ui.components.StackTopBar
import com.ferbotz.billanta.ui.components.StatusPill

// Fixed "paper" palette so the document reads like print in both light and dark themes.
private val Paper = Color(0xFFFFFFFF)
private val Ink = Color(0xFF1C1F2A)
private val InkMuted = Color(0xFF6B7280)
private val InkFaint = Color(0xFFAAB0BC)
private val PaperLine = Color(0xFFE6E8EC)
private val Accent = Color(0xFF5B4FE0)

@Composable
fun PreviewScreen(state: BillantaState, invoiceId: String) {
    val c = BillantaTheme.colors
    val invoice by remember(invoiceId) { state.invoiceFlow(invoiceId) }.collectAsState(initial = null)
    val record = invoice

    Column(Modifier.fillMaxSize().background(c.background)) {
        StackTopBar("Invoice", onBack = { state.pop() }, actions = {
            record?.let { StatusPill(it.status, Modifier.padding(end = 12.dp)) }
        })

        if (record == null || record.deletedAtMillis != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Invoice not found", style = BillantaTheme.type.body, color = c.textMuted)
            }
            return@Column
        }

        // Sync state strip — real, from the row's dirty/syncError flags.
        val (stripBg, stripFg, stripText) = when {
            record.syncError != null -> Triple(c.dangerBg, c.danger, record.syncError!!)
            record.pendingSync && state.signedIn -> Triple(c.warningBg, c.warning, "Waiting to sync")
            record.pendingSync -> Triple(c.surfaceAlt, c.textSecondary, "Saved on this device — sign in to back up")
            else -> Triple(c.successBg, c.success, "Synced")
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(stripBg)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BillantaIcon(if (record.syncError != null) AppIcon.Info else AppIcon.Check, stripFg, size = 16.dp)
            Text(stripText, style = BillantaTheme.type.caption, color = stripFg)
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            InvoicePaper(state, record)

            // Template switcher — the server catalogue; premium gates on account status.
            if (state.templates.isNotEmpty()) {
                Column {
                    Overline("Template")
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.templates.take(4).forEach { t ->
                            TemplateSwatch(
                                name = t.name,
                                premium = t.isPremium,
                                selected = t.id == (record.templateId ?: state.selectedTemplateId),
                                onClick = {
                                    if (t.isPremium && !state.isPremium) {
                                        state.openSheet(com.ferbotz.billanta.state.PremiumSheet(t.id))
                                    } else {
                                        state.setInvoiceTemplate(record.id, t)
                                    }
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        com.ferbotz.billanta.ui.components.BottomActionBar {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(
                    "Delete",
                    onClick = { state.deleteInvoice(record.id); state.pop() },
                    modifier = Modifier.weight(1f),
                )
                when (record.status) {
                    InvoiceDocStatus.Draft -> PrimaryButton(
                        "Mark as pending",
                        onClick = { state.setInvoiceStatus(record.id, InvoiceDocStatus.Pending) },
                        modifier = Modifier.weight(1f),
                    )
                    InvoiceDocStatus.Pending -> PrimaryButton(
                        "Mark as paid",
                        onClick = { state.setInvoiceStatus(record.id, InvoiceDocStatus.Paid) },
                        leadingIcon = AppIcon.Check,
                        modifier = Modifier.weight(1f),
                    )
                    InvoiceDocStatus.Paid -> PrimaryButton(
                        "Mark as pending",
                        onClick = { state.setInvoiceStatus(record.id, InvoiceDocStatus.Pending) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun InvoicePaper(state: BillantaState, invoice: InvoiceRecord) {
    // Snapshots are the render source of truth (frozen at issue time); a live-company fallback
    // covers legacy rows only.
    val company: CompanySnapshot? = invoice.companySnapshot ?: state.company?.toSnapshot()
    val customer = invoice.customerSnapshot
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Paper)
            .border(1.dp, PaperLine, RoundedCornerShape(14.dp))
            .padding(22.dp),
    ) {
        // Header
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Accent), contentAlignment = Alignment.Center) {
                    Text((company?.name ?: "B").take(1), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Spacer(Modifier.height(10.dp))
                Text(company?.name ?: "Your business", style = BillantaTheme.type.sectionTitle, color = Ink)
                company?.gstin?.let { Text("GSTIN $it", style = BillantaTheme.type.caption, color = InkMuted) }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("INVOICE", color = Accent, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(4.dp))
                Text(invoice.invoiceNumber, style = BillantaTheme.type.label, color = Ink)
                Spacer(Modifier.height(8.dp))
                PaperMeta("Issued", Iso8601.formatDisplayDate(invoice.invoiceDateMillis))
                invoice.dueDateMillis?.let { PaperMeta("Due", Iso8601.formatDisplayDate(it)) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).background(Accent))
        Spacer(Modifier.height(16.dp))

        // Bill to — from the customer snapshot
        Text("BILL TO", color = InkFaint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        Spacer(Modifier.height(6.dp))
        Text(customer?.name ?: "—", style = BillantaTheme.type.bodyStrong, color = Ink)
        val addressLine = listOfNotNull(
            customer?.addressLine1, customer?.addressLine2,
            listOfNotNull(customer?.city, customer?.pincode).joinToString(" ").ifBlank { null },
        ).joinToString(", ").ifBlank { null }
        addressLine?.let { Text(it, style = BillantaTheme.type.caption, color = InkMuted) }
        customer?.gstin?.let { Text("GSTIN: $it", style = BillantaTheme.type.caption, color = InkMuted) }

        Spacer(Modifier.height(18.dp))

        // Items table
        Row(Modifier.fillMaxWidth()) {
            Text("ITEM", color = InkFaint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
            Text("AMOUNT", color = InkFaint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        }
        Spacer(Modifier.height(8.dp))
        PaperDivider()
        invoice.items.forEach { item ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(item.description, style = BillantaTheme.type.body.copy(color = Ink))
                    Text(
                        buildString {
                            append("${item.quantity} × ${item.unitPricePaise.formatPaise()}")
                            append(" · GST ${item.taxRatePercent}%")
                            item.hsnSac?.let { append(" · HSN $it") }
                        },
                        style = BillantaTheme.type.caption, color = InkMuted,
                    )
                }
                Text(item.lineTotalPaise.formatPaise(), style = BillantaTheme.type.bodyStrong.copy(color = Ink))
            }
            PaperDivider()
        }

        Spacer(Modifier.height(14.dp))
        // Totals — the stored, server-parity figures; GST split derived from the snapshots.
        Column(Modifier.fillMaxWidth()) {
            PaperTotal("Subtotal", invoice.subtotalPaise)
            if (invoice.discountTotalPaise > 0) {
                Spacer(Modifier.height(8.dp))
                PaperTotal("Discount", -invoice.discountTotalPaise)
            }
            val split = state.gstSplitFor(invoice)
            val codesKnown = !company?.stateCode.isNullOrBlank() && !customer?.stateCode.isNullOrBlank()
            Spacer(Modifier.height(8.dp))
            if (codesKnown && split.intraState) {
                PaperTotal("CGST", split.cgst)
                Spacer(Modifier.height(8.dp))
                PaperTotal("SGST", split.sgst)
            } else if (codesKnown) {
                PaperTotal("IGST", split.igst)
            } else {
                PaperTotal("Tax (GST)", invoice.taxTotalPaise)
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Accent.copy(alpha = 0.08f)).padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Total due", style = BillantaTheme.type.bodyStrong, color = Ink)
                Text(invoice.grandTotalPaise.formatPaise(), color = Accent, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
        }

        // Payment block — only what the company snapshot actually has.
        val hasPayment = company?.upiId != null || company?.bankName != null || company?.accountNumber != null
        if (hasPayment) {
            Spacer(Modifier.height(18.dp))
            Column(Modifier.fillMaxWidth()) {
                Text("PAYMENT", color = InkFaint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                Spacer(Modifier.height(8.dp))
                company?.upiId?.let { Text("UPI: $it", style = BillantaTheme.type.caption.copy(color = Ink)) }
                val bankLine = listOfNotNull(
                    company?.bankName,
                    company?.accountNumber?.let { "••${it.takeLast(4)}" },
                    company?.ifsc,
                ).joinToString(" · ").ifBlank { null }
                bankLine?.let { Text(it, style = BillantaTheme.type.caption, color = InkMuted) }
            }
        }
        invoice.notes?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, style = BillantaTheme.type.caption, color = InkMuted)
        }
    }
}

@Composable
private fun PaperMeta(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = BillantaTheme.type.caption, color = InkFaint)
        Text(value, style = BillantaTheme.type.caption.copy(color = Ink))
    }
}

@Composable
private fun PaperTotal(label: String, valuePaise: Long) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = BillantaTheme.type.body, color = InkMuted)
        Text(valuePaise.formatPaise(), style = BillantaTheme.type.body.copy(color = Ink))
    }
}

@Composable
private fun PaperDivider() = Box(Modifier.fillMaxWidth().height(1.dp).background(PaperLine))

@Composable
private fun androidx.compose.foundation.layout.RowScope.TemplateSwatch(
    name: String,
    premium: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c = BillantaTheme.colors
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(12.dp))
                .background(c.surface)
                .border(if (selected) 2.dp else 1.dp, if (selected) c.primary else c.border, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.fillMaxWidth(0.5f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (premium) c.textPrimary else c.primary))
                Box(Modifier.fillMaxWidth(0.9f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.border))
                Box(Modifier.fillMaxWidth(0.75f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.border))
            }
            if (premium) {
                Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).clip(RoundedCornerShape(999.dp)).background(c.primaryMuted), contentAlignment = Alignment.Center) {
                    BillantaIcon(AppIcon.Star, c.primary, size = 13.dp)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(name, style = BillantaTheme.type.caption.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal), color = if (selected) c.textPrimary else c.textSecondary)
    }
}
