package com.ferbotz.billanta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ferbotz.billanta.domain.model.TemplateInfo
import com.ferbotz.billanta.state.BillantaState
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.AppIcon
import com.ferbotz.billanta.ui.BillantaIcon
import com.ferbotz.billanta.ui.components.BottomBarSpace
import com.ferbotz.billanta.ui.components.LargeTopBar
import com.ferbotz.billanta.ui.components.PremiumBadge
import com.ferbotz.billanta.ui.components.TemplateCardShape
import com.ferbotz.billanta.ui.components.TemplateLabelPadding
import com.ferbotz.billanta.ui.components.TemplateThumbnail

@Composable
fun TemplatesScreen(state: BillantaState) {
    val c = BillantaTheme.colors
    // Revalidate the catalogue whenever the tab opens; the cached copy shows meanwhile.
    LaunchedEffect(Unit) { state.refreshTemplates() }

    Column(Modifier.fillMaxSize().background(c.background)) {
        LargeTopBar("Templates")
        // The store switched off (or in maintenance): cached templates still render on every
        // invoice, there is just no browsing for new ones.
        if (!state.features.templateStore) {
            TemplatesNotice(
                title = "Templates are unavailable",
                body = "Browsing templates is switched off for now. Your invoices keep the template " +
                    "they already use.",
            )
            return@Column
        }
        if (state.visibleTemplates.isEmpty()) {
            TemplatesNotice(
                title = "No templates yet",
                body = "Templates come from the server — connect once and they're cached for offline use.",
            )
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = TemplateCardMinWidth),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = BottomBarSpace),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Pick a look for your invoices. Switch anytime — your data stays the same.",
                    style = BillantaTheme.type.body, color = c.textSecondary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            items(state.visibleTemplates, key = { it.id }) { t ->
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
private fun TemplatesNotice(title: String, body: String) {
    val c = BillantaTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(top = 60.dp, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(76.dp).clip(RoundedCornerShape(22.dp)).background(c.primaryMuted),
            contentAlignment = Alignment.Center,
        ) { BillantaIcon(AppIcon.Grid, c.primary, size = 34.dp) }
        Spacer(Modifier.height(18.dp))
        Text(title, style = BillantaTheme.type.sectionTitle, color = c.textPrimary)
        Spacer(Modifier.height(6.dp))
        Text(body, style = BillantaTheme.type.body, color = c.textSecondary, textAlign = TextAlign.Center)
    }
}

@Composable
private fun TemplateCard(template: TemplateInfo, selected: Boolean, onClick: () -> Unit) {
    val c = BillantaTheme.colors
    val premium = template.isPremium
    Column(Modifier.clip(TemplateCardShape).clickable(onClick = onClick)) {
        TemplateThumbnail(
            template = template,
            modifier = Modifier.fillMaxWidth(),
            borderWidth = if (selected) 2.dp else 1.dp,
            borderColor = if (selected) c.primary else c.border,
        ) {
            if (premium) PremiumBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
            if (selected) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(8.dp).size(26.dp)
                        .clip(RoundedCornerShape(999.dp)).background(c.primary),
                    contentAlignment = Alignment.Center,
                ) { BillantaIcon(AppIcon.Check, c.onPrimary, size = 16.dp) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Column(Modifier.padding(TemplateLabelPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    template.name,
                    style = BillantaTheme.type.bodyStrong,
                    color = c.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (premium) "Premium" else "Free",
                    style = BillantaTheme.type.caption,
                    color = if (premium) c.primary else c.textMuted,
                )
            }
            template.category?.let {
                Text(
                    it,
                    style = BillantaTheme.type.caption,
                    color = c.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The narrowest a template card may get before the grid drops a column.
 *
 * Two columns on any phone, more on a tablet — a fixed two stretched each page to half a tablet's
 * width, which is far wider than the 640 px thumbnails the server is asked for (APP-012).
 */
private val TemplateCardMinWidth = 150.dp
