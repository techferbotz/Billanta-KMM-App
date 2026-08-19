package com.ferbotz.billanta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ferbotz.billanta.core.AppError
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.domain.model.InvoiceRecord
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import com.ferbotz.billanta.render.CustomisationControl
import com.ferbotz.billanta.render.InvoiceRenderer
import com.ferbotz.billanta.render.InvoiceTheme
import com.ferbotz.billanta.render.TemplateDoc
import com.ferbotz.billanta.render.TemplateParser
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.ferbotz.billanta.render.emptySectionsFor
import com.ferbotz.billanta.render.layout.PlaceholderMode
import com.ferbotz.billanta.render.layout.RenderedDocument
import com.ferbotz.billanta.render.layout.RenderedPage
import com.ferbotz.billanta.render.layout.SectionBounds
import com.ferbotz.billanta.render.paint.InvoicePageView
import com.ferbotz.billanta.render.paint.intrinsicSizes
import com.ferbotz.billanta.render.paint.rememberInvoiceRenderer
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

private sealed interface TemplateState {
    data object Loading : TemplateState
    data class Ready(val doc: TemplateDoc) : TemplateState
    data class Failed(val message: String, val premium: Boolean) : TemplateState
}

/** Everything needed to draw and to share, resolved together so the two can never disagree. */
private class PreparedInvoice(
    val document: RenderedDocument,
    /** The same invoice with empty sections held open, for the preview only — never exported. */
    val editing: RenderedDocument,
    val images: Map<String, ImageBitmap>,
) {
    val painters: Map<String, Painter> = images.mapValues { (_, bitmap) -> BitmapPainter(bitmap) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(state: BillantaState, invoiceId: String) {
    val c = BillantaTheme.colors
    val invoice by remember(invoiceId) { state.invoiceFlow(invoiceId) }.collectAsState(initial = null)
    val record = invoice

    Column(Modifier.fillMaxSize().background(c.background)) {
        var choosingColor by remember { mutableStateOf(false) }
        var showingSettings by remember { mutableStateOf(false) }
        // Resolved once the template loads, below — the bar is drawn first so that "invoice not
        // found" still gets a back button.
        var accentArgb by remember(invoiceId) { mutableStateOf<Long?>(null) }

        StackTopBar("Invoice", onBack = { state.pop() }, actions = {
            if (record != null) {
                // The swatch *is* the button: the current colour is the only useful label for it.
                accentArgb?.let { argb ->
                    Box(
                        Modifier.size(24.dp).clip(CircleShape)
                            .background(Color(argb.toInt()))
                            .border(1.5.dp, c.border, CircleShape)
                            .clickable { choosingColor = true },
                    )
                    Spacer(Modifier.width(14.dp))
                }
                IconButtonBox(AppIcon.Gear, c.textSecondary, onClick = { showingSettings = true })
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

        val templateId = record.templateId ?: state.selectedTemplateId
        // Make sure "current" is actually current before resolving a version below.
        LaunchedEffect(Unit) { state.refreshTemplates() }
        // Render the template's CURRENT version, not the one pinned when the invoice was created.
        // A compiled tree is cached forever per (id, version), so a pinned version meant an invoice
        // made before a template gained sections or colour tokens could never gain them — the edit
        // sheet would offer nothing to hide, or offer controls the old tree has no tags for.
        val templateVersion = templateId?.let { id -> state.templateById(id)?.currentVersion }
        var reloadKey by remember { mutableIntStateOf(0) }

        val template by produceState<TemplateState>(
            TemplateState.Loading,
            templateId,
            templateVersion,
            reloadKey,
        ) {
            value = TemplateState.Loading
            if (templateId == null) {
                value = TemplateState.Failed("No templates yet — connect once to download them.", premium = false)
                return@produceState
            }
            value = when (val result = state.container.templateRepository.getCompiled(templateId, templateVersion)) {
                is AppResult.Success -> TemplateParser.parse(result.value.json)
                    ?.let { TemplateState.Ready(it) }
                    ?: TemplateState.Failed("This template can't be displayed — try updating the app.", premium = false)
                is AppResult.Failure -> {
                    val error = result.error
                    val premium = error is AppError.Http && error.isPremiumRequired
                    TemplateState.Failed(
                        message = if (premium) "This is a premium template." else error.userMessage(),
                        premium = premium,
                    )
                }
            }
        }

        val ready = template as? TemplateState.Ready
        LaunchedEffect(ready?.doc, record.themeOverrides) {
            accentArgb = ready?.doc?.let { doc ->
                doc.controls.filterIsInstance<CustomisationControl.Color>()
                    .firstOrNull { control -> doc.themeTokens.any { it.name == control.token } }
                    ?.let { control ->
                        record.themeOverrides[control.token]
                            ?: doc.themeTokens.first { it.name == control.token }.defaultArgb
                    }
            }
        }
        val prepared = ready?.let { RememberPreparedInvoice(state, it.doc, record) }
        var exporting by remember { mutableStateOf<ExportFormat?>(null) }
        val scope = rememberCoroutineScope()

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            when {
                template is TemplateState.Failed -> {
                    val failure = template as TemplateState.Failed
                    TemplateProblem(
                        message = failure.message,
                        actionLabel = if (failure.premium) "See premium" else "Retry",
                        onAction = {
                            if (failure.premium && templateId != null) state.openSheet(PremiumSheet(templateId))
                            else reloadKey++
                        },
                    )
                }

                prepared == null -> PagePlaceholder("Preparing invoice…")

                else -> {
                    val document = prepared.editing
                    val labels = ready.doc.sections.associate { it.id to it.label }
                    document.pages.forEachIndexed { index, page ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val scale = maxWidth.value / document.pageWidthPt
                                InvoicePageView(
                                    page = page,
                                    pageWidthPt = document.pageWidthPt,
                                    pageHeightPt = document.pageHeightPt,
                                    modifier = Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .border(1.dp, c.border, RoundedCornerShape(6.dp))
                                        .pointerInput(page, scale, ready.doc) {
                                            detectTapGestures { offset ->
                                                val hit = page.sectionAt(
                                                    offset.x.toDp().value / scale,
                                                    offset.y.toDp().value / scale,
                                                )
                                                val section = hit?.let { bounds ->
                                                    ready.doc.sections.firstOrNull { it.id == bounds.id }
                                                }
                                                // Straight to the editor only for a section that
                                                // already has something in it. A dashed box means
                                                // nothing is there yet, and a tap on one is usually
                                                // "let me fill this invoice in" rather than a
                                                // considered choice of that section — so it opens
                                                // the list, where everything still missing is
                                                // visible at once. Margins do the same.
                                                if (hit != null && !hit.isEmpty && section?.isEditable == true) {
                                                    state.openSectionViaList(record.id, section)
                                                } else {
                                                    state.openInvoiceData(record.id)
                                                }
                                            }
                                        },
                                    imageFor = { prepared.painters[it] },
                                )
                                EmptySectionHints(
                                    page = page,
                                    scale = scale,
                                    labelFor = { labels[it] },
                                )
                            }
                            if (document.pageCount > 1) {
                                Text(
                                    "Page ${index + 1} of ${document.pageCount}",
                                    style = BillantaTheme.type.caption,
                                    color = c.textMuted,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
        }

        if (choosingColor) {
            val doc = (template as? TemplateState.Ready)?.doc
            if (doc == null) choosingColor = false
            else InvoiceColorDialog(state, record, doc, onDismiss = { choosingColor = false })
        }

        if (showingSettings) {
            ModalBottomSheet(
                onDismissRequest = { showingSettings = false },
                containerColor = c.surface,
                scrimColor = c.scrim,
                shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                dragHandle = { BottomSheetDefaults.DragHandle() },
            ) {
                InvoiceSettingsSheetContent(state, record)
            }
        }

        BottomActionBar {
            val canShare = prepared != null && exporting == null
            fun shareAs(format: ExportFormat) {
                val ready = prepared ?: return
                if (exporting != null) return
                scope.launch {
                    exporting = format
                    state.container.invoiceExporter
                        .export(ready.document, format, record.invoiceNumber, ready.images)
                        .onFailure { state.uiMessage = it.userMessage() }
                    exporting = null
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(
                    "Edit",
                    onClick = { state.openInvoiceData(record.id) },
                    leadingIcon = AppIcon.Pencil,
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(
                    if (exporting == ExportFormat.PDF) "…" else "Share PDF",
                    onClick = { shareAs(ExportFormat.PDF) },
                    enabled = canShare,
                    leadingIcon = AppIcon.Share,
                    modifier = Modifier.weight(1.6f),
                )
            }
        }
    }
}

/**
 * Downloads every image the template binds, then lays the invoice out with their real dimensions.
 * Doing this before rendering — rather than letting images stream in behind an already-drawn page
 * — is what stops a shared PDF going out with the logo missing.
 */
@Composable
private fun RememberPreparedInvoice(
    state: BillantaState,
    doc: TemplateDoc,
    record: InvoiceRecord,
): PreparedInvoice? {
    val measuringRenderer = rememberInvoiceRenderer()
    val urls = remember(doc, record) { measuringRenderer.imageUrlsFor(doc, record) }
    val images by produceState(initialValue = emptyMap<String, ImageBitmap>(), urls) {
        value = if (urls.isEmpty()) emptyMap() else state.container.invoiceImageLoader.load(urls)
    }
    val renderer = rememberInvoiceRenderer(images.intrinsicSizes())
    val theme = InvoiceTheme(record.themeOverrides, record.hiddenSections)
    val dateFormat = state.dateFormat
    return remember(doc, record, images, renderer, theme, dateFormat) {
        val document = renderer.render(doc, record, theme, dateFormat = dateFormat)
        // A section with nothing in it collapses to nothing, which leaves the user no way to fill
        // it. Only the on-screen copy holds the space open; the shared file must never show a gap.
        val empty = emptySectionsFor(doc, record) - record.hiddenSections
        // A template may also carry its own editing-only content (BE-009), which the export render
        // above has correctly dropped — so a second pass is needed for that too, not just for gaps.
        val editing =
            if (empty.isEmpty() && !doc.hasEditorOnly) document
            else renderer.render(doc, record, theme, PlaceholderMode.Reserve(empty), dateFormat)
        PreparedInvoice(document, editing, images)
    }
}

/**
 * The section under a tap, preferring the most specific one.
 *
 * Sections nest — classic tags `totals` on a row inside the `items` table — so a point can fall
 * inside more than one rect. The smallest one containing it is what the user aimed at.
 */
private fun RenderedPage.sectionAt(xPt: Float, yPt: Float): SectionBounds? = sections
    .filter { xPt >= it.rect.x && xPt <= it.rect.right && yPt >= it.rect.y && yPt <= it.rect.bottom }
    .minByOrNull { it.rect.width * it.rect.height }

/**
 * A dashed "add this" box over every section the invoice has not filled in yet.
 *
 * The rectangles come from the layout engine, not from guesswork: each is exactly where that
 * section would have been drawn had it any content. They carry no click handling of their own —
 * the page underneath handles every tap uniformly — so they are purely a hint about what is
 * missing, and must not intercept the gesture.
 */
@Composable
private fun EmptySectionHints(
    page: RenderedPage,
    scale: Float,
    labelFor: (String) -> String?,
) {
    val c = BillantaTheme.colors
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(9f, 7f), 0f) }
    page.sections.filter { it.isEmpty }.forEach { section ->
        val label = labelFor(section.id) ?: return@forEach
        Box(
            Modifier
                .offset((section.rect.x * scale).dp, (section.rect.y * scale).dp)
                .size((section.rect.width * scale).dp, (section.rect.height * scale).dp)
                .clip(RoundedCornerShape(8.dp))
                .drawBehind {
                    drawRoundRect(
                        color = c.primary,
                        style = Stroke(width = 1.5.dp.toPx(), pathEffect = dash),
                        cornerRadius = CornerRadius(8.dp.toPx()),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Add $label",
                style = BillantaTheme.type.caption,
                color = c.primary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun PagePlaceholder(text: String) {
    val c = BillantaTheme.colors
    Box(
        Modifier.fillMaxWidth().aspectRatio(A4_ASPECT)
            .clip(RoundedCornerShape(6.dp)).background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = BillantaTheme.type.body, color = c.textMuted)
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
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(c.primaryMuted),
            contentAlignment = Alignment.Center,
        ) { BillantaIcon(AppIcon.Grid, c.primary, size = 26.dp) }
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
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp)
                        .clip(RoundedCornerShape(999.dp)).background(c.primaryMuted),
                    contentAlignment = Alignment.Center,
                ) { BillantaIcon(AppIcon.Star, c.primary, size = 13.dp) }
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

/** A4 proportions, used for the placeholder before the real page size is known. */
private const val A4_ASPECT = 595f / 842f
