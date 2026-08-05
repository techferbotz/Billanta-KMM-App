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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.ferbotz.billanta.domain.model.TemplateInfo
import com.ferbotz.billanta.state.BillantaState
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.AppIcon
import com.ferbotz.billanta.ui.BillantaIcon
import com.ferbotz.billanta.ui.components.BottomBarSpace
import com.ferbotz.billanta.ui.components.LargeTopBar

@Composable
fun TemplatesScreen(state: BillantaState) {
    val c = BillantaTheme.colors
    // Revalidate the catalogue whenever the tab opens; the cached copy shows meanwhile.
    LaunchedEffect(Unit) { state.refreshTemplates() }

    Column(Modifier.fillMaxSize().background(c.background)) {
        LargeTopBar("Templates")
        if (state.templates.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(top = 60.dp, start = 32.dp, end = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(76.dp).clip(RoundedCornerShape(22.dp)).background(c.primaryMuted),
                    contentAlignment = Alignment.Center,
                ) { BillantaIcon(AppIcon.Grid, c.primary, size = 34.dp) }
                Spacer(Modifier.height(18.dp))
                Text("No templates yet", style = BillantaTheme.type.sectionTitle, color = c.textPrimary)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Templates come from the server — connect once and they're cached for offline use.",
                    style = BillantaTheme.type.body, color = c.textSecondary, textAlign = TextAlign.Center,
                )
            }
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = BottomBarSpace),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(2) }) {
                Text(
                    "Pick a look for your invoices. Switch anytime — your data stays the same.",
                    style = BillantaTheme.type.body, color = c.textSecondary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            items(state.templates, key = { it.id }) { t ->
                TemplateCard(
                    template = t,
                    selected = t.id == state.selectedTemplateId,
                    onClick = { state.selectTemplate(t) },
                )
            }
        }
    }
}

@Composable
private fun TemplateCard(template: TemplateInfo, selected: Boolean, onClick: () -> Unit) {
    val c = BillantaTheme.colors
    val premium = template.isPremium
    Column(Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.74f).clip(RoundedCornerShape(16.dp))
                .background(if (premium) androidx.compose.ui.graphics.Color(0xFF17171C) else androidx.compose.ui.graphics.Color.White)
                .border(if (selected) 2.dp else 1.dp, if (selected) c.primary else c.border, RoundedCornerShape(16.dp)),
        ) {
            if (template.thumbnailUrl != null) {
                AsyncImage(
                    model = template.thumbnailUrl,
                    contentDescription = template.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)),
                )
            } else {
                Box(Modifier.fillMaxSize().padding(14.dp)) { MiniInvoice(premium) }
            }
            if (premium) {
                Row(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(999.dp)).background(c.primary).padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    BillantaIcon(AppIcon.Star, c.onPrimary, size = 12.dp)
                    Text("Premium", style = BillantaTheme.type.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold), color = c.onPrimary)
                }
            }
            if (selected) {
                Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(26.dp).clip(RoundedCornerShape(999.dp)).background(c.primary), contentAlignment = Alignment.Center) {
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
        template.category?.let {
            Text(it, style = BillantaTheme.type.caption, color = c.textSecondary, maxLines = 1)
        }
    }
}

/** Placeholder page used until the server thumbnail loads (or when a template has none). */
@Composable
private fun MiniInvoice(premium: Boolean) {
    val c = BillantaTheme.colors
    val accent = if (premium) androidx.compose.ui.graphics.Color.White else c.primary
    val line = if (premium) androidx.compose.ui.graphics.Color(0xFF3A3A45) else androidx.compose.ui.graphics.Color(0xFFE6E8EC)
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
