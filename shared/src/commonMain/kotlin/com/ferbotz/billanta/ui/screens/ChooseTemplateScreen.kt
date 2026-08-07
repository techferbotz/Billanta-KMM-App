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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.ferbotz.billanta.domain.model.TemplateInfo
import com.ferbotz.billanta.state.BillantaState
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.AppIcon
import com.ferbotz.billanta.ui.BillantaIcon
import com.ferbotz.billanta.ui.components.StackTopBar

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
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(2) }) {
                Text(
                    "Pick the look for your invoices. This becomes your default — you can change it " +
                        "any time, on any invoice.",
                    style = BillantaTheme.type.body,
                    color = c.textSecondary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            items(state.templates, key = { it.id }) { template ->
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
    Column(Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.74f).clip(RoundedCornerShape(16.dp))
                .background(if (template.isPremium) Color(0xFF17171C) else Color.White)
                .border(1.dp, c.border, RoundedCornerShape(16.dp)),
        ) {
            if (template.thumbnailUrl != null) {
                AsyncImage(
                    model = template.thumbnailUrl,
                    contentDescription = template.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)),
                )
            }
            if (locked) {
                Row(
                    Modifier.align(Alignment.TopEnd).padding(6.dp)
                        .clip(RoundedCornerShape(999.dp)).background(c.primary)
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
        }
        Spacer(Modifier.height(10.dp))
        Text(template.name, style = BillantaTheme.type.bodyStrong, color = c.textPrimary)
        template.category?.let {
            Text(it, style = BillantaTheme.type.caption, color = c.textSecondary, maxLines = 1)
        }
    }
}
