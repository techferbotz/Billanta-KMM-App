package com.ferbotz.billanta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ferbotz.billanta.model.InvoiceTemplate
import com.ferbotz.billanta.model.TemplateTier
import com.ferbotz.billanta.state.BillantaState
import com.ferbotz.billanta.state.PremiumSheet
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.AppIcon
import com.ferbotz.billanta.ui.BillantaIcon
import com.ferbotz.billanta.ui.components.BottomBarSpace
import com.ferbotz.billanta.ui.components.LargeTopBar

@Composable
fun TemplatesScreen(state: BillantaState) {
    val c = BillantaTheme.colors
    Column(Modifier.fillMaxSize().background(c.background)) {
        LargeTopBar("Templates")
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = BottomBarSpace),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                Text(
                    "Pick a look for your invoices. Switch anytime — your data stays the same.",
                    style = BillantaTheme.type.body, color = c.textSecondary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            items(state.templates.size) { i ->
                val t = state.templates[i]
                TemplateCard(
                    template = t,
                    selected = t.id == state.selectedTemplateId,
                    onClick = {
                        if (t.tier == TemplateTier.PREMIUM) state.openSheet(PremiumSheet(t.id))
                        else state.selectedTemplateId = t.id
                    },
                )
            }
        }
    }
}

@Composable
private fun TemplateCard(template: InvoiceTemplate, selected: Boolean, onClick: () -> Unit) {
    val c = BillantaTheme.colors
    val premium = template.tier == TemplateTier.PREMIUM
    Column(Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.74f).clip(RoundedCornerShape(16.dp))
                .background(if (premium) Color(0xFF17171C) else Color.White)
                .border(if (selected) 2.dp else 1.dp, if (selected) c.primary else c.border, RoundedCornerShape(16.dp))
                .padding(14.dp),
        ) {
            MiniInvoice(premium)
            if (premium) {
                Row(
                    Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(999.dp)).background(c.primary).padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    BillantaIcon(AppIcon.Star, c.onPrimary, size = 12.dp)
                    Text("Premium", style = BillantaTheme.type.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold), color = c.onPrimary)
                }
            }
            if (selected) {
                Box(Modifier.align(Alignment.BottomEnd).size(26.dp).clip(RoundedCornerShape(999.dp)).background(c.primary), contentAlignment = Alignment.Center) {
                    BillantaIcon(AppIcon.Check, c.onPrimary, size = 16.dp)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(template.name, style = BillantaTheme.type.bodyStrong, color = c.textPrimary, modifier = Modifier.weight(1f))
            Text(
                if (premium) "Premium" else "Free",
                style = BillantaTheme.type.caption,
                color = if (premium) c.primary else c.textMuted,
            )
        }
        Text(
            template.description,
            style = BillantaTheme.type.caption, color = c.textSecondary, maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/** Abstract miniature of an invoice page used as a template thumbnail. */
@Composable
private fun MiniInvoice(premium: Boolean) {
    val c = BillantaTheme.colors
    val accent = if (premium) Color.White else c.primary
    val line = if (premium) Color(0xFF3A3A45) else Color(0xFFE6E8EC)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.size(width = 26.dp, height = 8.dp).clip(RoundedCornerShape(2.dp)).background(accent))
            Box(Modifier.size(width = 16.dp, height = 6.dp).clip(RoundedCornerShape(2.dp)).background(line))
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(accent))
        Spacer(Modifier.height(2.dp))
        repeat(4) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.fillMaxWidth(0.5f).height(5.dp).clip(RoundedCornerShape(2.dp)).background(line))
                Box(Modifier.size(width = 18.dp, height = 5.dp).clip(RoundedCornerShape(2.dp)).background(line))
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(Modifier.size(width = 34.dp, height = 8.dp).clip(RoundedCornerShape(2.dp)).background(accent))
        }
    }
}
