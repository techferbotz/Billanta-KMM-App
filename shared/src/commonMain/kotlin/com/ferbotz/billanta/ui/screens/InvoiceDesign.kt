package com.ferbotz.billanta.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ferbotz.billanta.domain.model.InvoiceRecord
import com.ferbotz.billanta.render.CustomisationControl
import com.ferbotz.billanta.render.TemplateDoc
import com.ferbotz.billanta.state.BillantaState
import com.ferbotz.billanta.state.PremiumSheet
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.AppIcon
import com.ferbotz.billanta.ui.BillantaIcon
import com.ferbotz.billanta.ui.components.Overline
import com.ferbotz.billanta.ui.components.PrimaryButton
import com.ferbotz.billanta.ui.components.SecondaryButton
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI

// ---- colour ------------------------------------------------------------------------------------

/** Fifteen accents chosen to stay legible printed on white. */
private val PALETTE = listOf(
    0xFF5B4FE0, 0xFF3B33B5, 0xFF2B3648, 0xFF0F172A, 0xFF475569,
    0xFF0F766E, 0xFF047857, 0xFF15803D, 0xFF4D7C0F, 0xFFB45309,
    0xFFC2410C, 0xFFB91C1C, 0xFFBE123C, 0xFF9333EA, 0xFF7E22CE,
)

/**
 * Picks the colour for one of the template's theme tokens: fifteen presets for the common case, a
 * wheel for anything else.
 *
 * The choice is held locally and written once on Done, rather than on every drag — a colour wheel
 * emits a value per frame, and each of those would otherwise be a database write and a sync nudge.
 */
@Composable
fun InvoiceColorDialog(
    state: BillantaState,
    record: InvoiceRecord,
    doc: TemplateDoc,
    onDismiss: () -> Unit,
) {
    val c = BillantaTheme.colors
    val colorControls = doc.controls.filterIsInstance<CustomisationControl.Color>()
        .filter { control -> doc.themeTokens.any { it.name == control.token } }
    if (colorControls.isEmpty()) {
        onDismiss()
        return
    }

    var tokenName by remember { mutableStateOf(colorControls.first().token) }
    val token = doc.themeTokens.first { it.name == tokenName }
    var picked by remember(tokenName) {
        mutableStateOf(record.themeOverrides[tokenName] ?: token.defaultArgb)
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.surface)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Colour",
                    style = BillantaTheme.type.sectionTitle,
                    color = c.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(Color(picked.toInt()))
                        .border(1.dp, c.border, CircleShape),
                )
            }

            // Only worth showing when the template exposes more than one colour.
            if (colorControls.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    colorControls.forEach { control ->
                        val active = control.token == tokenName
                        Text(
                            control.title,
                            style = BillantaTheme.type.caption,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (active) c.textPrimary else c.textSecondary,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                .background(if (active) c.primaryMuted else c.surfaceAlt)
                                .clickable { tokenName = control.token }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            Overline("Presets")
            val swatches = listOf(token.defaultArgb) + PALETTE.filterNot { it == token.defaultArgb }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                swatches.take(15).chunked(5).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { argb ->
                            Swatch(
                                argb = argb,
                                selected = picked == argb,
                                isTemplateDefault = argb == token.defaultArgb,
                                onClick = { picked = argb },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            Overline("Custom")
            ColorWheel(
                selected = picked,
                onPick = { picked = it },
                modifier = Modifier.fillMaxWidth(0.72f).align(Alignment.CenterHorizontally),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton("Cancel", onClick = onDismiss, modifier = Modifier.weight(1f))
                PrimaryButton(
                    "Done",
                    onClick = {
                        val overrides = record.themeOverrides.toMutableMap()
                        // Choosing the template's own colour clears the override rather than
                        // pinning it, so the invoice follows the template if its palette changes.
                        if (picked == token.defaultArgb) overrides.remove(tokenName)
                        else overrides[tokenName] = picked
                        state.setInvoiceCustomisation(record.id, overrides, record.hiddenSections)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun Swatch(
    argb: Long,
    selected: Boolean,
    isTemplateDefault: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = BillantaTheme.colors
    Box(
        modifier.aspectRatio(1f).clip(CircleShape)
            .background(Color(argb.toInt()))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) c.primary else c.border,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Marks the template's own colour, so "back to default" stays findable.
        if (isTemplateDefault && !selected) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.85f)))
        }
    }
}

/**
 * An HSV wheel — hue around, saturation outward — with a brightness bar beneath it.
 *
 * Drawn as two composited gradients rather than per-pixel: a sweep for hue and a white radial for
 * saturation. Value is a separate bar because a flat disc cannot express all three axes at once.
 */
@Composable
private fun ColorWheel(
    selected: Long,
    onPick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = BillantaTheme.colors
    val hsv = remember(selected) { argbToHsv(selected) }
    var value by remember(selected) { mutableStateOf(hsv.third) }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val hueRing = remember {
            (0..360 step 30).map { Color(hsvToArgb(it.toFloat(), 1f, 1f).toInt()) }
        }
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            Canvas(
                Modifier.fillMaxWidth().aspectRatio(1f)
                    .pointerInput(value) {
                        fun pick(pos: Offset) {
                            val radius = min(size.width, size.height) / 2f
                            val dx = pos.x - size.width / 2f
                            val dy = pos.y - size.height / 2f
                            val dist = sqrt(dx * dx + dy * dy)
                            val hue = ((atan2(dy, dx) * 180f / PI.toFloat()) + 360f) % 360f
                            val sat = (dist / radius).coerceIn(0f, 1f)
                            onPick(hsvToArgb(hue, sat, value))
                        }
                        detectTapGestures { pick(it) }
                    }
                    .pointerInput(value) {
                        detectDragGestures { change, _ ->
                            val radius = min(size.width, size.height) / 2f
                            val dx = change.position.x - size.width / 2f
                            val dy = change.position.y - size.height / 2f
                            val hue = ((atan2(dy, dx) * 180f / PI.toFloat()) + 360f) % 360f
                            val sat = (sqrt(dx * dx + dy * dy) / radius).coerceIn(0f, 1f)
                            onPick(hsvToArgb(hue, sat, value))
                        }
                    },
            ) {
                val radius = min(size.width, size.height) / 2f
                val centre = Offset(size.width / 2f, size.height / 2f)
                drawCircle(Brush.sweepGradient(hueRing, centre), radius, centre)
                drawCircle(
                    Brush.radialGradient(listOf(Color.White, Color.Transparent), centre, radius),
                    radius,
                    centre,
                )
                // Value is not on the disc, so darken the whole thing to show it.
                if (value < 1f) drawCircle(Color.Black.copy(alpha = 1f - value), radius, centre)

                // Where the current colour sits.
                val angle = hsv.first * PI.toFloat() / 180f
                val marker = Offset(
                    centre.x + cos(angle) * hsv.second * radius,
                    centre.y + sin(angle) * hsv.second * radius,
                )
                drawCircle(Color.White, 7.dp.toPx(), marker, style = androidx.compose.ui.graphics.drawscope.Stroke(2.5.dp.toPx()))
            }
        }

        Spacer(Modifier.height(14.dp))
        val fullValue = remember(hsv) { Color(hsvToArgb(hsv.first, hsv.second, 1f).toInt()) }
        Box(
            Modifier.fillMaxWidth().height(24.dp).clip(RoundedCornerShape(12.dp))
                .background(Brush.horizontalGradient(listOf(Color.Black, fullValue)))
                .border(1.dp, c.border, RoundedCornerShape(12.dp))
                .pointerInput(hsv) {
                    fun pick(x: Float) {
                        val v = (x / size.width).coerceIn(0f, 1f)
                        value = v
                        onPick(hsvToArgb(hsv.first, hsv.second, v))
                    }
                    detectTapGestures { pick(it.x) }
                }
                .pointerInput(hsv) {
                    detectDragGestures { change, _ ->
                        val v = (change.position.x / size.width).coerceIn(0f, 1f)
                        value = v
                        onPick(hsvToArgb(hsv.first, hsv.second, v))
                    }
                },
        )
    }
}

// ---- HSV <-> ARGB ------------------------------------------------------------------------------
// Hand-rolled rather than platform colour APIs, so the wheel behaves identically on Android and iOS.

internal fun hsvToArgb(hue: Float, saturation: Float, value: Float): Long {
    val h = ((hue % 360f) + 360f) % 360f
    val s = saturation.coerceIn(0f, 1f)
    val v = value.coerceIn(0f, 1f)
    val chroma = v * s
    val x = chroma * (1f - abs((h / 60f) % 2f - 1f))
    val m = v - chroma
    val (r, g, b) = when {
        h < 60f -> Triple(chroma, x, 0f)
        h < 120f -> Triple(x, chroma, 0f)
        h < 180f -> Triple(0f, chroma, x)
        h < 240f -> Triple(0f, x, chroma)
        h < 300f -> Triple(x, 0f, chroma)
        else -> Triple(chroma, 0f, x)
    }
    fun channel(f: Float): Long = ((f + m) * 255f + 0.5f).toLong().coerceIn(0L, 255L)
    return (0xFFL shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
}

/** Returns hue (0–360), saturation and value (both 0–1). */
internal fun argbToHsv(argb: Long): Triple<Float, Float, Float> {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val hue = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }
    return Triple(((hue % 360f) + 360f) % 360f, if (max == 0f) 0f else delta / max, max)
}

// ---- template ----------------------------------------------------------------------------------

/** Switching the template is the only setting an invoice has for now. */
@Composable
fun InvoiceSettingsSheetContent(
    state: BillantaState,
    record: InvoiceRecord,
    modifier: Modifier = Modifier,
) {
    val c = BillantaTheme.colors
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, bottom = 24.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Template", style = BillantaTheme.type.sectionTitle, color = c.textPrimary)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            state.templates.take(4).forEach { template ->
                TemplateChoice(
                    name = template.name,
                    premium = template.isPremium,
                    selected = template.id == (record.templateId ?: state.selectedTemplateId),
                    onClick = {
                        if (template.isPremium && !state.isPremium) state.openSheet(PremiumSheet(template.id))
                        else state.setInvoiceTemplate(record.id, template)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (state.templates.isEmpty()) {
            Text(
                "No templates yet — connect once to download them.",
                style = BillantaTheme.type.caption,
                color = c.textMuted,
            )
        }
    }
}

@Composable
private fun TemplateChoice(
    name: String,
    premium: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = BillantaTheme.colors
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(12.dp))
                .background(c.surface)
                .border(
                    if (selected) 2.dp else 1.dp,
                    if (selected) c.primary else c.border,
                    RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    Modifier.fillMaxWidth(0.5f).height(5.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (premium) c.textPrimary else c.primary),
                )
                Box(Modifier.fillMaxWidth(0.9f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(c.border))
                Box(Modifier.fillMaxWidth(0.75f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(c.border))
            }
            if (premium) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(5.dp).size(18.dp)
                        .clip(CircleShape).background(c.primaryMuted),
                    contentAlignment = Alignment.Center,
                ) { BillantaIcon(AppIcon.Star, c.primary, size = 11.dp) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            name,
            style = BillantaTheme.type.caption.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = if (selected) c.textPrimary else c.textSecondary,
        )
    }
}
