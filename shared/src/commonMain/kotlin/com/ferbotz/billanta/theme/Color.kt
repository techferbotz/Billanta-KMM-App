package com.ferbotz.billanta.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Billanta's semantic color set. Material3's [androidx.compose.material3.ColorScheme] does not model
 * things like "success", "the amber used for a Pending pill" or "the muted secondary text", so the
 * app carries its own palette and exposes it through [LocalBillantaColors].
 */
@Immutable
data class BillantaColors(
    val background: Color,
    val surface: Color,
    val surfaceAlt: Color,      // subtly raised / banners
    val surfaceSunken: Color,   // input fields, sunken wells
    val primary: Color,
    val onPrimary: Color,
    val primaryMuted: Color,    // faint indigo wash behind selected states
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val border: Color,
    val borderStrong: Color,
    val success: Color,
    val successBg: Color,
    val warning: Color,
    val warningBg: Color,
    val draft: Color,
    val draftBg: Color,
    val danger: Color,
    val dangerBg: Color,
    val accentDot: Color,       // small orange "unpaid" dot next to a name
    val scrim: Color,
    val isDark: Boolean,
)

// Brand indigo used across chips, FAB, links, active tab.
private val Indigo = Color(0xFF5B4FE0)
private val IndigoLight = Color(0xFF7B72F0)

val LightColors = BillantaColors(
    background = Color(0xFFF4F5F7),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFEFF1F4),
    surfaceSunken = Color(0xFFFFFFFF),
    primary = Indigo,
    onPrimary = Color(0xFFFFFFFF),
    primaryMuted = Color(0xFFECEBFB),
    textPrimary = Color(0xFF1C1F2A),
    textSecondary = Color(0xFF6B7280),
    textMuted = Color(0xFF9AA0AD),
    border = Color(0xFFE6E8EC),
    borderStrong = Color(0xFFD7DAE0),
    success = Color(0xFF16A34A),
    successBg = Color(0xFFDCFCE7),
    warning = Color(0xFFB45309),
    warningBg = Color(0xFFFDECC8),
    draft = Color(0xFF6B7280),
    draftBg = Color(0xFFEDEFF2),
    danger = Color(0xFFDC2626),
    dangerBg = Color(0xFFFEE2E2),
    accentDot = Color(0xFFE8850C),
    scrim = Color(0x66101114),
    isDark = false,
)

val DarkColors = BillantaColors(
    background = Color(0xFF0F1117),
    surface = Color(0xFF1A1D26),
    surfaceAlt = Color(0xFF222633),
    surfaceSunken = Color(0xFF13161E),
    primary = IndigoLight,
    onPrimary = Color(0xFFFFFFFF),
    primaryMuted = Color(0xFF2A2A47),
    textPrimary = Color(0xFFF2F3F5),
    textSecondary = Color(0xFFA6ADBB),
    textMuted = Color(0xFF767D8C),
    border = Color(0xFF2A2E3A),
    borderStrong = Color(0xFF3A3F4D),
    success = Color(0xFF4ADE80),
    successBg = Color(0xFF14321F),
    warning = Color(0xFFF0B24B),
    warningBg = Color(0xFF3A2E14),
    draft = Color(0xFFA6ADBB),
    draftBg = Color(0xFF262B37),
    danger = Color(0xFFF87171),
    dangerBg = Color(0xFF3A1D1D),
    accentDot = Color(0xFFF0A03C),
    scrim = Color(0x99000000),
    isDark = true,
)
