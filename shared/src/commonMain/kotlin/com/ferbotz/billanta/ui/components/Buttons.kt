package com.ferbotz.billanta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.AppIcon
import com.ferbotz.billanta.ui.BillantaIcon

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: AppIcon? = null,
    /**
     * Drawn instead of [leadingIcon] when the mark must keep its own colours — [leadingIcon] is
     * tinted to match the label, which is right for a glyph and wrong for a brand.
     */
    leadingSlot: (@Composable () -> Unit)? = null,
) {
    val c = BillantaTheme.colors
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) c.primary else c.primary.copy(alpha = 0.45f))
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                leadingSlot != null -> leadingSlot()
                leadingIcon != null -> BillantaIcon(leadingIcon, c.onPrimary, size = 20.dp)
            }
            Text(text, style = BillantaTheme.type.bodyStrong, color = c.onPrimary, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: AppIcon? = null,
    tint: Color? = null,
) {
    val c = BillantaTheme.colors
    val fg = (tint ?: c.textPrimary).let { if (enabled) it else it.copy(alpha = 0.45f) }
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (leadingIcon != null) BillantaIcon(leadingIcon, fg, size = 20.dp)
            Text(text, style = BillantaTheme.type.bodyStrong, color = fg)
        }
    }
}

@Composable
fun TextButtonLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color? = null,
) {
    Text(
        text = text,
        style = BillantaTheme.type.bodyStrong,
        color = color ?: BillantaTheme.colors.primary,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
    )
}
