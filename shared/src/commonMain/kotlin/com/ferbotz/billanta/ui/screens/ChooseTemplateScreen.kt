package com.ferbotz.billanta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ferbotz.billanta.domain.model.TemplateInfo
import com.ferbotz.billanta.state.BillantaState
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.components.PremiumBadge
import com.ferbotz.billanta.ui.components.StackTopBar
import com.ferbotz.billanta.ui.components.TemplateCardShape
import com.ferbotz.billanta.ui.components.TemplateLabelPadding
import com.ferbotz.billanta.ui.components.TemplateThumbnail

/**
 * Shown once, the first time someone makes an invoice. Whatever they pick becomes the default, so
 * every later invoice skips straight past this — it can still be changed per invoice from the
 * preview, or globally from Templates.
 */
@Composable
fun ChooseTemplateScreen(state: BillantaState) {
    val c = BillantaTheme.colors
    Column(Modifier.fillMaxSize().background(c.background)) {
        StackTopBar("Choose a template", onBack = { state.pop() })
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Pick the look for your invoices. This becomes your default — you can change it " +
                        "any time, on any invoice.",
                    style = BillantaTheme.type.body,
                    color = c.textSecondary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            items(state.visibleTemplates, key = { it.id }) { template ->
                ChoiceCard(
                    template = template,
                    locked = template.isPremium && !state.isPremium,
                    onClick = { state.chooseDefaultTemplate(template) },
                )
            }
        }
    }
}

@Composable
private fun ChoiceCard(template: TemplateInfo, locked: Boolean, onClick: () -> Unit) {
    val c = BillantaTheme.colors
    Column(Modifier.clip(TemplateCardShape).clickable(onClick = onClick)) {
        TemplateThumbnail(template, Modifier.fillMaxWidth()) {
            if (locked) PremiumBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        Spacer(Modifier.height(10.dp))
        Column(Modifier.padding(TemplateLabelPadding)) {
            Text(
                template.name,
                style = BillantaTheme.type.bodyStrong,
                color = c.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
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
