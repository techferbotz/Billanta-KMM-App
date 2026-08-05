package com.ferbotz.billanta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ferbotz.billanta.core.AppError
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.domain.model.InvoiceRecord
import com.ferbotz.billanta.render.PageSpec
import com.ferbotz.billanta.render.RenderedInvoicePage
import com.ferbotz.billanta.render.TemplateDoc
import com.ferbotz.billanta.render.TemplateParser
import com.ferbotz.billanta.share.ExportFormat
import com.ferbotz.billanta.state.BillantaState
import com.ferbotz.billanta.state.PremiumSheet
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.AppIcon
import com.ferbotz.billanta.ui.BillantaIcon
import com.ferbotz.billanta.ui.components.BottomActionBar
import com.ferbotz.billanta.ui.components.IconButtonBox
import com.ferbotz.billanta.ui.components.Overline
import com.ferbotz.billanta.ui.components.PrimaryButton
import com.ferbotz.billanta.ui.components.SecondaryButton
import com.ferbotz.billanta.ui.components.StackTopBar
import kotlinx.coroutines.launch

private sealed interface CompiledUi {
    data object Loading : CompiledUi
    data class Ready(val doc: TemplateDoc) : CompiledUi
    data class Failed(val message: String, val premium: Boolean = false) : CompiledUi
}

@Composable
fun PreviewScreen(state: BillantaState, invoiceId: String) {
    val c = BillantaTheme.colors
    val invoice by remember(invoiceId) { state.invoiceFlow(invoiceId) }.collectAsState(initial = null)
    val record = invoice

    Column(Modifier.fillMaxSize().background(c.background)) {
        StackTopBar("Invoice", onBack = { state.pop() }, actions = {
            if (record != null) {
                IconButtonBox(AppIcon.Trash, c.danger, onClick = {
                    state.deleteInvoice(record.id)
                    state.pop()
                })
            }
        })

        if (record == null || record.deletedAtMillis != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Invoice not found", style = BillantaTheme.type.body, color = c.textMuted)
            }
            return@Column
        }

        // Which compiled tree to render: the invoice's own (id, version) pair, else the default.
        val templateId = record.templateId ?: state.selectedTemplateId
        val templateVersion = if (record.templateId != null) record.templateVersion else null
        var reloadKey by remember { mutableIntStateOf(0) }
        val compiled by produceState<CompiledUi>(CompiledUi.Loading, templateId, templateVersion, reloadKey) {
            value = CompiledUi.Loading
            if (templateId == null) {
                value = CompiledUi.Failed("No templates yet — connect once to download them.")
                return@produceState
            }
            value = when (val result = state.container.templateRepository.getCompiled(templateId, templateVersion)) {
                is AppResult.Success -> TemplateParser.parse(result.value.json)
                    ?.let { CompiledUi.Ready(it) }
                    ?: CompiledUi.Failed("This template can't be displayed — try updating the app.")
                is AppResult.Failure -> {
                    val error = result.error
                    CompiledUi.Failed(
                        message = if (error is AppError.Http && error.isPremiumRequired) {
                            "This is a premium template."
                        } else {
                            error.userMessage()
                        },
                        premium = error is AppError.Http && error.isPremiumRequired,
                    )
                }
            }
        }

        // Sync state strip — from the row's dirty/syncError flags.
        val (stripBg, stripFg, stripText) = when {
            record.syncError != null -> Triple(c.dangerBg, c.danger, record.syncError!!)
            record.pendingSync && state.signedIn -> Triple(c.warningBg, c.warning, "Waiting to sync")
            record.pendingSync -> Triple(c.surfaceAlt, c.textSecondary, "Saved on this device — sign in to back up")
            else -> Triple(c.successBg, c.success, "Synced")
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(stripBg)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BillantaIcon(if (record.syncError != null) AppIcon.Info else AppIcon.Check, stripFg, size = 16.dp)
            Text(stripText, style = BillantaTheme.type.caption, color = stripFg)
        }

        val captureLayer = rememberGraphicsLayer()
        var exporting by remember { mutableStateOf<ExportFormat?>(null) }
        val scope = rememberCoroutineScope()

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            when (val ui = compiled) {
                CompiledUi.Loading -> PagePlaceholder("Preparing template…")
                is CompiledUi.Failed -> TemplateProblem(
                    message = ui.message,
                    actionLabel = if (ui.premium) "See premium" else "Retry",
                    onAction = {
                        if (ui.premium && templateId != null) {
                            state.openSheet(PremiumSheet(templateId))
                        } else {
                            reloadKey++
                        }
                    },
                )
                is CompiledUi.Ready -> CapturedInvoicePage(ui.doc, record, captureLayer)
            }

            // Template switcher — swapping re-renders and re-syncs the invoice.
            if (state.templates.isNotEmpty()) {
                Column {
                    Overline("Template")
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.templates.take(4).forEach { t ->
                            TemplateSwatch(
                                name = t.name,
                                premium = t.isPremium,
                                selected = t.id == (record.templateId ?: state.selectedTemplateId),
                                onClick = {
                                    if (t.isPremium && !state.isPremium) {
                                        state.openSheet(PremiumSheet(t.id))
                                    } else {
                                        state.setInvoiceTemplate(record.id, t)
                                    }
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        // The whole point of the app: hand the invoice over as a file.
        BottomActionBar {
            val ready = compiled is CompiledUi.Ready && exporting == null
            fun shareAs(format: ExportFormat) {
                if (compiled !is CompiledUi.Ready || exporting != null) return
                scope.launch {
                    exporting = format
                    val bitmap = captureLayer.toImageBitmap()
                    state.container.invoiceExporter
                        .export(bitmap, format, record.invoiceNumber)
                        .onFailure { state.uiMessage = it.userMessage() }
                    exporting = null
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(
                    if (exporting == ExportFormat.PNG) "…" else "PNG",
                    onClick = { shareAs(ExportFormat.PNG) },
                    enabled = ready,
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    if (exporting == ExportFormat.JPEG) "…" else "JPG",
                    onClick = { shareAs(ExportFormat.JPEG) },
                    enabled = ready,
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(
                    if (exporting == ExportFormat.PDF) "…" else "Share PDF",
                    onClick = { shareAs(ExportFormat.PDF) },
                    enabled = ready,
                    leadingIcon = AppIcon.Share,
                    modifier = Modifier.weight(1.6f),
                )
            }
        }
    }
}

/**
 * The A4 page composed at full 595×842 (1pt = 1dp), recorded into [layer] for pixel-perfect
 * export, and scaled down to the screen width for viewing.
 */
@Composable
private fun CapturedInvoicePage(doc: TemplateDoc, record: InvoiceRecord, layer: GraphicsLayer) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val scale = maxWidth / PageSpec.A4_WIDTH_PT.dp
        Box(
            Modifier
                .fillMaxWidth()
                .height(PageSpec.A4_HEIGHT_PT.dp * scale)
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, BillantaTheme.colors.border, RoundedCornerShape(6.dp)),
        ) {
            Box(
                Modifier
                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        transformOrigin = TransformOrigin(0f, 0f),
                    )
                    .drawWithContent {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                    },
            ) {
                RenderedInvoicePage(doc, record)
            }
        }
    }
}

@Composable
private fun PagePlaceholder(text: String) {
    val c = BillantaTheme.colors
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val scale = maxWidth / PageSpec.A4_WIDTH_PT.dp
        Box(
            Modifier.fillMaxWidth().height(PageSpec.A4_HEIGHT_PT.dp * scale)
                .clip(RoundedCornerShape(6.dp)).background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = BillantaTheme.type.body, color = c.textMuted)
        }
    }
}

@Composable
private fun TemplateProblem(message: String, actionLabel: String, onAction: () -> Unit) {
    val c = BillantaTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(14.dp)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(c.primaryMuted), contentAlignment = Alignment.Center) {
            BillantaIcon(AppIcon.Grid, c.primary, size = 26.dp)
        }
        Text(message, style = BillantaTheme.type.body, color = c.textSecondary, textAlign = TextAlign.Center)
        SecondaryButton(actionLabel, onClick = onAction)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TemplateSwatch(
    name: String,
    premium: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c = BillantaTheme.colors
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(12.dp))
                .background(c.surface)
                .border(if (selected) 2.dp else 1.dp, if (selected) c.primary else c.border, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.fillMaxWidth(0.5f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (premium) c.textPrimary else c.primary))
                Box(Modifier.fillMaxWidth(0.9f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.border))
                Box(Modifier.fillMaxWidth(0.75f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.border))
            }
            if (premium) {
                Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).clip(RoundedCornerShape(999.dp)).background(c.primaryMuted), contentAlignment = Alignment.Center) {
                    BillantaIcon(AppIcon.Star, c.primary, size = 13.dp)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(name, style = BillantaTheme.type.caption.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal), color = if (selected) c.textPrimary else c.textSecondary)
    }
}
