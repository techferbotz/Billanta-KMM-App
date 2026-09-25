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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/**
 * A stable colour pair for someone's avatar, picked from [key] — their id, not their position.
 *
 * The same customer is the same colour on every screen and every launch. That is most of what
 * makes a repeat customer recognisable in a long list, and what one shared tint for everybody
 * could never do. Returns background to foreground, with a separate pair for dark theme.
 */
@Composable
fun avatarColors(key: String): Pair<Color, Color> {
    val dark = BillantaTheme.colors.background.luminance() < 0.5f
    val hue = AVATAR_HUES[(key.hashCode() and Int.MAX_VALUE) % AVATAR_HUES.size]
    return if (dark) hue.darkBg to hue.darkFg else hue.lightBg to hue.lightFg
}

private class AvatarHue(val lightBg: Color, val lightFg: Color, val darkBg: Color, val darkFg: Color)

/** Indigo (the brand), teal, coral, amber, pink, blue — distinct from each other at a glance. */
private val AVATAR_HUES = listOf(
    AvatarHue(Color(0xFFECEBFB), Color(0xFF4B3FD0), Color(0xFF2D2A5C), Color(0xFFB9B3F7)),
    AvatarHue(Color(0xFFE1F5EE), Color(0xFF0F6E56), Color(0xFF123D33), Color(0xFF8FD9C0)),
    AvatarHue(Color(0xFFFAECE7), Color(0xFF993C1D), Color(0xFF4A2419), Color(0xFFF2B39B)),
    AvatarHue(Color(0xFFFAEEDA), Color(0xFF854F0B), Color(0xFF453012), Color(0xFFF3C77E)),
    AvatarHue(Color(0xFFFBEAF0), Color(0xFF993556), Color(0xFF4A2231), Color(0xFFF0A9C0)),
    AvatarHue(Color(0xFFE6F1FB), Color(0xFF185FA5), Color(0xFF16304A), Color(0xFF9CC7F1)),
)

/** A small rounded label on a tint: "GST registered", "18% GST". */
@Composable
fun Tag(text: String, color: Color, background: Color, modifier: Modifier = Modifier) {
    androidx.compose.material3.Text(
        text = text,
        style = BillantaTheme.type.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
        color = color,
        maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(999.dp)).background(background)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
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
