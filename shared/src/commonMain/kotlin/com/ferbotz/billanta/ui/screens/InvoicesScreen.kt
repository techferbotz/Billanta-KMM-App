package com.ferbotz.billanta.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.model.formatPaiseTrimmed
import com.ferbotz.billanta.state.BillantaState
import com.ferbotz.billanta.state.invoiceDateMillis
import com.ferbotz.billanta.state.PreviewRoute
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.thumbs.InvoiceThumbnails
import com.ferbotz.billanta.ui.AppIcon
import com.ferbotz.billanta.ui.BillantaIcon
import com.ferbotz.billanta.ui.components.BottomBarSpace
import com.ferbotz.billanta.ui.components.InvoiceThumbnailCard
import com.ferbotz.billanta.ui.components.InvoiceThumbnailCardSkeleton
import com.ferbotz.billanta.ui.components.LargeTopBar

@Composable
fun InvoicesScreen(state: BillantaState) {
    val c = BillantaTheme.colors
    val thumbnails = state.container.invoiceThumbnails
    val pictures by thumbnails.files.collectAsState()
    val grid = rememberLazyGridState()
    ReportOnScreenInvoices(grid, thumbnails)
    Column(Modifier.fillMaxSize().background(c.background)) {
        LargeTopBar("Invoices")
        val list = state.invoices
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = grid,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 18.dp, end = 18.dp, top = 14.dp, bottom = BottomBarSpace,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SearchBar(state.query, { state.query = it }, Modifier.fillMaxWidth())
            }
            if (state.loading) {
                items(6) { SkeletonCard() }
            } else if (list.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyInvoices(hasQuery = state.query.isNotBlank(), onCreate = { state.openCreate() })
                }
            } else {
                // Newest first already, so grouping keeps the order: each month's heading lands
                // directly above that month's invoices.
                list.groupBy { Iso8601.monthRange(it.invoiceDateMillis ?: 0L).first }.forEach { (month, invoices) ->
                    item(key = "$MONTH_KEY$month", span = { GridItemSpan(maxLineSpan) }) {
                        MonthHeader(month, invoices.sumOf { it.computed.grandTotal })
                    }
                    items(invoices, key = { it.id }) { inv ->
                        InvoiceThumbnailCard(inv, pictures[inv.id], onClick = { state.push(PreviewRoute(inv.id)) })
                    }
                }
            }
        }
    }
}

/** Month headings are keyed apart from invoices, which are keyed by their id. */
private const val MONTH_KEY = "month-"

/**
 * Tells the thumbnails which invoices are on screen, top to bottom, so those are drawn first and
 * one that scrolls away before its turn is dropped (FIELDS_APP_GUIDE.md §12.3). Leaving the list
 * says nothing is on screen.
 */
@Composable
private fun ReportOnScreenInvoices(grid: LazyGridState, thumbnails: InvoiceThumbnails) {
    LaunchedEffect(grid, thumbnails) {
        snapshotFlow {
            grid.layoutInfo.visibleItemsInfo.mapNotNull { item -> (item.key as? String)?.takeUnless { it.startsWith(MONTH_KEY) } }
        }.distinctUntilChanged().collect { thumbnails.setVisible(it) }
    }
    DisposableEffect(thumbnails) {
        onDispose { thumbnails.setVisible(emptyList()) }
    }
}

/** "September 2026 · ₹3,43,905": where one month's invoices start, and what they add up to. */
@Composable
private fun MonthHeader(monthStartMillis: Long, totalPaise: Long) {
    val c = BillantaTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            Iso8601.formatMonthYear(monthStartMillis),
            style = BillantaTheme.type.label,
            color = c.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(totalPaise.formatPaiseTrimmed(), style = BillantaTheme.type.label, color = c.textSecondary)
    }
}

@Composable
private fun SearchBar(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = BillantaTheme.colors
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(14.dp))
            .height(52.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BillantaIcon(AppIcon.Search, c.textMuted, size = 20.dp)
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Search by number or customer", style = BillantaTheme.type.body, color = c.textMuted)
            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = BillantaTheme.type.body.copy(color = c.textPrimary),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(c.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A card-shaped stand-in while the first rows load, so the grid doesn't jump when they arrive. */
@Composable
private fun SkeletonCard() {
    val transition = rememberInfiniteTransition()
    val alpha by transition.animateFloat(
        initialValue = 0.45f, targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse),
    )
    InvoiceThumbnailCardSkeleton(alpha)
}

@Composable
private fun EmptyInvoices(hasQuery: Boolean, onCreate: () -> Unit) {
    val c = BillantaTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(76.dp).clip(RoundedCornerShape(22.dp)).background(c.primaryMuted),
            contentAlignment = Alignment.Center,
        ) { BillantaIcon(AppIcon.Receipt, c.primary, size = 34.dp) }
        Spacer(Modifier.height(18.dp))
        Text(
            if (hasQuery) "No matching invoices" else "No invoices yet",
            style = BillantaTheme.type.sectionTitle, color = c.textPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (hasQuery) "Try a different number or customer name." else "Create your first invoice in under 30 seconds.",
            style = BillantaTheme.type.body, color = c.textSecondary, textAlign = TextAlign.Center,
        )
        if (!hasQuery) {
            Spacer(Modifier.height(20.dp))
            com.ferbotz.billanta.ui.components.PrimaryButton("New invoice", onCreate, leadingIcon = AppIcon.Plus)
        }
    }
}
