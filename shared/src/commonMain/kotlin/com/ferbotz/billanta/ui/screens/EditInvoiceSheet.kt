package com.ferbotz.billanta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ferbotz.billanta.domain.model.InvoiceRecord
import com.ferbotz.billanta.render.TemplateDoc
import com.ferbotz.billanta.state.BillantaState
import com.ferbotz.billanta.state.PremiumSheet
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.AppIcon
import com.ferbotz.billanta.ui.BillantaIcon
import com.ferbotz.billanta.ui.components.Overline

/**
 * Everything about how one invoice *looks*: which template, what accent colour, which blocks are
 * shown. Colour and section controls only appear when the loaded template actually declares them,
 * so an older template simply offers the template switcher and nothing misleading.
 */
@Composable
fun EditInvoiceSheetContent(
    state: BillantaState,
    record: InvoiceRecord,
    doc: TemplateDoc?,
    modifier: Modifier = Modifier,
) {
    val c = BillantaTheme.colors
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, bottom = 24.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Invoice design", style = BillantaTheme.type.sectionTitle, color = c.textPrimary)

        Column {
            Overline("Template")
            Spacer(Modifier.height(10.dp))
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
        }

        val tokens = doc?.themeTokens.orEmpty()
        if (tokens.isNotEmpty()) {
            tokens.forEach { token ->
                Column {
                    Overline(token.label)
                    Spacer(Modifier.height(10.dp))
                    val current = record.themeOverrides[token.name] ?: token.defaultArgb
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        (listOf(token.defaultArgb) + PALETTE.filterNot { it == token.defaultArgb })
                            .take(7)
                            .forEach { argb ->
                                ColorChoice(
                                    argb = argb,
                                    selected = current == argb,
                                    isTemplateDefault = argb == token.defaultArgb,
                                    onClick = {
                                        val overrides = record.themeOverrides.toMutableMap()
                                        // Choosing the template's own colour clears the override
                                        // rather than pinning it, so the invoice follows the
                                        // template if its palette is ever revised.
                                        if (argb == token.defaultArgb) overrides.remove(token.name)
                                        else overrides[token.name] = argb
                                        state.setInvoiceCustomisation(record.id, overrides, record.hiddenSections)
                                    },
                                )
                            }
                    }
                }
            }
        }

        val hidable = doc?.sections?.filter { it.hidable }.orEmpty()
        if (hidable.isNotEmpty()) {
            Column {
                Overline("Sections")
                Spacer(Modifier.height(4.dp))
                hidable.forEach { section ->
                    val visible = section.id !in record.hiddenSections
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            section.label,
                            style = BillantaTheme.type.bodyStrong,
                            color = c.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = visible,
                            onCheckedChange = { show ->
                                val hidden = record.hiddenSections.toMutableSet()
                                if (show) hidden.remove(section.id) else hidden.add(section.id)
                                state.setInvoiceCustomisation(record.id, record.themeOverrides, hidden)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = c.onPrimary,
                                checkedTrackColor = c.primary,
                                uncheckedTrackColor = c.surfaceAlt,
                                uncheckedBorderColor = c.border,
                                uncheckedThumbColor = c.textMuted,
                            ),
                        )
                    }
                }
            }
        }

        if (doc != null && tokens.isEmpty() && hidable.isEmpty()) {
            Text(
                "This template doesn't offer colour or section options yet. Other templates might.",
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
                .border(if (selected) 2.dp else 1.dp, if (selected) c.primary else c.border, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.fillMaxWidth(0.5f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(if (premium) c.textPrimary else c.primary))
                Box(Modifier.fillMaxWidth(0.9f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(c.border))
                Box(Modifier.fillMaxWidth(0.75f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(c.border))
            }
            if (premium) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(5.dp).size(18.dp)
                        .clip(RoundedCornerShape(999.dp)).background(c.primaryMuted),
                    contentAlignment = Alignment.Center,
                ) { BillantaIcon(AppIcon.Star, c.primary, size = 11.dp) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            name,
            style = BillantaTheme.type.caption.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
            color = if (selected) c.textPrimary else c.textSecondary,
        )
    }
}

@Composable
private fun ColorChoice(
    argb: Long,
    selected: Boolean,
    isTemplateDefault: Boolean,
    onClick: () -> Unit,
) {
    val c = BillantaTheme.colors
    Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(999.dp))
            .background(Color(argb.toInt()))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) c.primary else c.border,
                shape = RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isTemplateDefault && !selected) {
            // Marks which swatch is the template's own colour, so "back to default" is findable.
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(999.dp)).background(Color.White.copy(alpha = 0.8f)))
        }
    }
}

/** A small, deliberately safe set of accents that print legibly on white. */
private val PALETTE = listOf(
    0xFF5B4FE0, // indigo — the app's own accent
    0xFF2B3648, // slate
    0xFF0F766E, // teal
    0xFFC2410C, // rust
    0xFF9333EA, // violet
    0xFF15803D, // green
    0xFFB91C1C, // red
)
