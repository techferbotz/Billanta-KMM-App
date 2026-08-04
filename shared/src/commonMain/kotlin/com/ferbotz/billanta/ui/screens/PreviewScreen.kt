package com.ferbotz.billanta.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ferbotz.billanta.model.Invoice
import com.ferbotz.billanta.model.Paise
import com.ferbotz.billanta.model.TemplateTier
import com.ferbotz.billanta.model.format
import com.ferbotz.billanta.state.BillantaState
import com.ferbotz.billanta.state.PremiumSheet
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.AppIcon
import com.ferbotz.billanta.ui.BillantaIcon
import com.ferbotz.billanta.ui.components.BottomActionBar
import com.ferbotz.billanta.ui.components.IconButtonBox
import com.ferbotz.billanta.ui.components.Overline
import com.ferbotz.billanta.ui.components.PrimaryButton
import com.ferbotz.billanta.ui.components.SecondaryButton
import com.ferbotz.billanta.ui.components.StackTopBar
import kotlinx.coroutines.delay

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
    val invoice = state.invoiceById(invoiceId) ?: state.draft
    val template = state.templateById(state.selectedTemplateId)

    var generating by remember(invoiceId) { mutableStateOf(true) }
    LaunchedEffect(invoiceId) { delay(1400); generating = false }

    Column(Modifier.fillMaxSize().background(c.background)) {
        StackTopBar("Preview", onBack = { state.pop() }, actions = {
            IconButtonBox(AppIcon.Share, c.textSecondary, onClick = {})
        })

        // PDF generating / ready strip
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (generating) c.primaryMuted else c.successBg)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (generating) {
                MiniSpinner(c.primary)
                Text("Generating A4 PDF…", style = BillantaTheme.type.caption, color = c.primary)
            } else {
                BillantaIcon(AppIcon.Check, c.success, size = 16.dp)
                Text("PDF ready · A4 · ${template.name} template", style = BillantaTheme.type.caption, color = c.success)
            }
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            InvoicePaper(state, invoice)

            // Template switcher
            Column {
                Overline("Template")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.templates.forEach { t ->
                        TemplateSwatch(
                            name = t.name,
                            premium = t.tier == TemplateTier.PREMIUM,
                            selected = t.id == state.selectedTemplateId,
                            onClick = {
                                if (t.tier == TemplateTier.PREMIUM) state.openSheet(PremiumSheet(t.id))
                                else state.selectedTemplateId = t.id
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        BottomActionBar {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton("Download", onClick = {}, leadingIcon = AppIcon.Download, modifier = Modifier.weight(1f))
                PrimaryButton("Share", onClick = {}, leadingIcon = AppIcon.Share, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun InvoicePaper(state: BillantaState, invoice: Invoice) {
    val biz = state.business
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
                    Text(biz.name.take(1), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Spacer(Modifier.height(10.dp))
                Text(biz.name, style = BillantaTheme.type.sectionTitle, color = Ink)
                biz.tagline?.let { Text(it, style = BillantaTheme.type.caption, color = InkMuted) }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("INVOICE", color = Accent, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(4.dp))
                Text(invoice.number, style = BillantaTheme.type.label, color = Ink)
                Spacer(Modifier.height(8.dp))
                PaperMeta("Issued", invoice.issueDate)
                PaperMeta("Due", invoice.dueDate)
            }
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).background(Accent))
        Spacer(Modifier.height(16.dp))

        // Bill to
        Text("BILL TO", color = InkFaint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        Spacer(Modifier.height(6.dp))
        Text(invoice.customer.name, style = BillantaTheme.type.bodyStrong, color = Ink)
        invoice.customer.company?.let { Text(it, style = BillantaTheme.type.caption, color = InkMuted) }
        invoice.customer.address?.let { Text(it, style = BillantaTheme.type.caption, color = InkMuted) }
        invoice.customer.gstin?.let { Text("GSTIN: $it", style = BillantaTheme.type.caption, color = InkMuted) }

        Spacer(Modifier.height(18.dp))

        // Items table header
        Row(Modifier.fillMaxWidth()) {
            Text("ITEM", color = InkFaint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
            Text("AMOUNT", color = InkFaint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        }
        Spacer(Modifier.height(8.dp))
        PaperDivider()
        invoice.items.forEach { item ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = BillantaTheme.type.body.copy(color = Ink))
                    Text(
                        "${item.quantity} × ${item.rate.format()}${item.description?.let { " · $it" } ?: ""}",
                        style = BillantaTheme.type.caption, color = InkMuted,
                    )
                }
                Text(item.amount.format(), style = BillantaTheme.type.bodyStrong.copy(color = Ink))
            }
            PaperDivider()
        }

        Spacer(Modifier.height(14.dp))
        // Totals
        Column(Modifier.fillMaxWidth()) {
            PaperTotal("Subtotal", invoice.subtotal)
            Spacer(Modifier.height(8.dp))
            if (invoice.sameState) {
                PaperTotal("CGST (9%)", invoice.cgst)
                Spacer(Modifier.height(8.dp))
                PaperTotal("SGST (9%)", invoice.sgst)
            } else {
                PaperTotal("IGST (18%)", invoice.igst)
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Accent.copy(alpha = 0.08f)).padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Total due", style = BillantaTheme.type.bodyStrong, color = Ink)
                Text(invoice.total.format(), color = Accent, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
        }

        Spacer(Modifier.height(18.dp))
        // Footer: payment + signature
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text("PAY VIA UPI", color = InkFaint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFF3F4F6)).border(1.dp, PaperLine, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                        BillantaIcon(AppIcon.Qr, Ink, size = 40.dp)
                    }
                    Column {
                        Text(biz.upiId, style = BillantaTheme.type.caption.copy(color = Ink))
                        Text("${biz.bankName} ••${biz.accountLast4}", style = BillantaTheme.type.caption, color = InkMuted)
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Box(Modifier.size(width = 90.dp, height = 34.dp), contentAlignment = Alignment.Center) {
                    Text("Ananya", color = InkFaint, fontSize = 20.sp, fontWeight = FontWeight.Normal)
                }
                Box(Modifier.width(96.dp).height(1.dp).background(PaperLine))
                Spacer(Modifier.height(4.dp))
                Text("Authorised sign", style = BillantaTheme.type.caption, color = InkMuted)
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
private fun PaperTotal(label: String, value: Paise) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = BillantaTheme.type.body, color = InkMuted)
        Text(value.format(), style = BillantaTheme.type.body.copy(color = Ink))
    }
}

@Composable
private fun PaperDivider() = Box(Modifier.fillMaxWidth().height(1.dp).background(PaperLine))

/** Small indeterminate spinner drawn by hand (avoids the churning M3 progress-indicator API). */
@Composable
private fun MiniSpinner(color: Color) {
    val transition = rememberInfiniteTransition()
    val angle by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing)),
    )
    Canvas(Modifier.size(15.dp).graphicsLayer { rotationZ = angle }) {
        val sw = 2.dp.toPx()
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(sw / 2, sw / 2),
            size = Size(size.width - sw, size.height - sw),
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
    }
}

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
            // tiny abstract "page"
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
