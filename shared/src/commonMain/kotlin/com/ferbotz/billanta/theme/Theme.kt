package com.ferbotz.billanta.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

val LocalBillantaColors = staticCompositionLocalOf { LightColors }

/** Corner radii used consistently across the app. */
object BillantaRadii {
    val card = 18.dp
    val chip = 24.dp
    val field = 14.dp
    val pill = 999.dp
    val sheet = 26.dp
    val small = 10.dp
}

/** Standard spacing scale. */
object BillantaSpacing {
    val screenH = 18.dp
    val gutter = 12.dp
    val section = 22.dp
}

/** Ergonomic accessor: `BillantaTheme.colors.primary`, `BillantaTheme.type.cardTitle`. */
object BillantaTheme {
    val colors: BillantaColors
        @Composable @ReadOnlyComposable get() = LocalBillantaColors.current
    val type: BillantaTypography
        @Composable @ReadOnlyComposable get() = LocalBillantaTypography.current
}

@Composable
fun BillantaTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.primary,
            onPrimary = colors.onPrimary,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceAlt,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.border,
            error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.primary,
            onPrimary = colors.onPrimary,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceAlt,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.border,
            error = colors.danger,
        )
    }
    CompositionLocalProvider(
        LocalBillantaColors provides colors,
        LocalBillantaTypography provides BillantaTypography(),
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = billantaMaterialTypography(),
            content = content,
        )
    }
}
