package com.ferbotz.billanta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ferbotz.billanta.model.ProductKind
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.AppIcon
import com.ferbotz.billanta.ui.BillantaIcon

/**
 * A product's picture: its photo when it has one, otherwise a tile tinted for what it is — a
 * service, goods, or unclassified — with the matching icon. The tile stays underneath the photo,
 * so it is also what shows while the photo loads or if it cannot be fetched.
 */
@Composable
fun ProductImage(
    url: String?,
    kind: ProductKind,
    modifier: Modifier = Modifier,
    iconSize: Dp = 30.dp,
) {
    val look = productKindLook(kind)
    Box(modifier.background(look.background), contentAlignment = Alignment.Center) {
        BillantaIcon(look.icon, look.tint, size = iconSize)
        if (url != null) {
            AsyncImage(
                model = url,
                // Decorative: the product's name is always shown beside it.
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** The colours and icon that say "service", "goods" or "not classified" at a glance. */
class ProductKindLook(val background: Color, val tint: Color, val icon: AppIcon)

@Composable
fun productKindLook(kind: ProductKind): ProductKindLook {
    val c = BillantaTheme.colors
    return when (kind) {
        ProductKind.Service -> ProductKindLook(c.primaryMuted, c.primary, AppIcon.Briefcase)
        ProductKind.Goods -> ProductKindLook(c.warningBg, c.warning, AppIcon.Package)
        ProductKind.Unspecified -> ProductKindLook(c.surfaceAlt, c.textSecondary, AppIcon.PriceTag)
    }
}
