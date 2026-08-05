package com.ferbotz.billanta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ferbotz.billanta.theme.BillantaTheme

@Composable
fun ColorDot(color: Color, size: Int = 7, modifier: Modifier = Modifier) {
    Box(modifier.size(size.dp).clip(CircleShape).background(color))
}

@Composable
fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = BillantaTheme.colors
    Box(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .then(
                if (selected) Modifier.background(c.primary)
                else Modifier.background(c.surface).border(1.dp, c.border, RoundedCornerShape(999.dp))
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        androidx.compose.material3.Text(
            text = label,
            style = BillantaTheme.type.label,
            color = if (selected) c.onPrimary else c.textSecondary,
        )
    }
}

@Composable
fun Avatar(
    initials: String,
    modifier: Modifier = Modifier,
    size: Int = 44,
    bg: Color? = null,
    fg: Color? = null,
) {
    val c = BillantaTheme.colors
    Box(
        modifier.size(size.dp).clip(CircleShape).background(bg ?: c.primaryMuted),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            text = initials,
            style = BillantaTheme.type.label.copy(fontWeight = FontWeight.SemiBold),
            color = fg ?: c.primary,
        )
    }
}

@Composable
fun Overline(text: String, modifier: Modifier = Modifier) {
    androidx.compose.material3.Text(
        text = text.uppercase(),
        style = BillantaTheme.type.overline,
        color = BillantaTheme.colors.textMuted,
        modifier = modifier,
    )
}

/** Horizontal chip strip used for the invoice filters. */
@Composable
fun <T> ChipRow(
    items: List<T>,
    isSelected: (T) -> Boolean,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    contentPadding: PaddingValues = PaddingValues(horizontal = 0.dp),
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = contentPadding,
    ) {
        items(items.size) { i ->
            val item = items[i]
            FilterChip(label(item), isSelected(item), { onSelect(item) })
        }
    }
}

@Composable
fun HGap(width: Int) = Spacer(Modifier.width(width.dp))
