package com.ferbotz.billanta.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ferbotz.billanta.model.InvoiceFilter
import com.ferbotz.billanta.model.Paise
import com.ferbotz.billanta.state.BillantaState
import com.ferbotz.billanta.state.PreviewRoute
import com.ferbotz.billanta.state.SignInRoute
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.AppIcon
import com.ferbotz.billanta.ui.BillantaIcon
import com.ferbotz.billanta.ui.components.ChipRow
import com.ferbotz.billanta.ui.components.BottomBarSpace
import com.ferbotz.billanta.ui.components.IconButtonBox
import com.ferbotz.billanta.ui.components.InvoiceCard
import com.ferbotz.billanta.ui.components.LargeTopBar
import com.ferbotz.billanta.ui.components.StatusBanner
import com.ferbotz.billanta.ui.components.SummaryCard
import com.ferbotz.billanta.ui.components.TextButtonLink

@Composable
fun InvoicesScreen(state: BillantaState) {
    val c = BillantaTheme.colors
    Column(Modifier.fillMaxSize().background(c.background)) {
        LargeTopBar("Invoices", actions = {
            if (!state.signedIn) {
                TextButtonLink("Sign in", onClick = { state.push(SignInRoute) })
            }
        })
        when {
            !state.signedIn ->
                StatusBanner(
                    "Working offline",
                    actionLabel = "Sign in to back up",
                    onAction = { state.push(SignInRoute) },
                )
            !state.isOnline ->
                StatusBanner("You're offline — changes will sync when you're back", dotColor = c.warning)
            state.syncStatus.running ->
                StatusBanner("Syncing…", dotColor = c.primary)
            state.syncStatus.lastError != null ->
                StatusBanner(
                    "Sync issue",
                    dotColor = c.danger,
                    actionLabel = "Retry",
                    onAction = { state.requestSyncNow() },
                )
        }

        val list = state.invoices
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 18.dp, end = 18.dp, top = 14.dp, bottom = BottomBarSpace,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SearchBar(state.query, { state.query = it }, Modifier.weight(1f))
                    SquareIconButton(AppIcon.Tune)
                }
            }
            item {
                ChipRow(
                    items = InvoiceFilter.entries.toList(),
                    isSelected = { it == state.filter },
                    label = { it.label },
                    onSelect = { state.filter = it },
                )
            }
            if (state.loading) {
                items(4) { ShimmerCard() }
            } else if (list.isEmpty()) {
                item { EmptyInvoices(hasQuery = state.query.isNotBlank() || state.filter != InvoiceFilter.ALL, onCreate = { state.openCreate() }) }
            } else {
                item {
                    val stats = state.dashboard
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SummaryCard(
                            label = "This month",
                            amount = Paise(stats?.monthTotalPaise ?: 0),
                            footnote = stats?.deltaPercentVsLastMonth?.let { delta ->
                                if (delta >= 0) "+$delta% vs last month" else "$delta% vs last month"
                            } ?: "vs last month —",
                            footnoteColor = if ((stats?.deltaPercentVsLastMonth ?: 0) >= 0) c.success else c.danger,
                            modifier = Modifier.weight(1f),
                        )
                        SummaryCard(
                            label = "Unpaid",
                            amount = Paise(stats?.unpaidTotalPaise ?: 0),
                            footnote = "${stats?.pendingCount ?: 0} pending",
                            footnoteColor = c.warning,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                items(list, key = { it.id }) { inv ->
                    InvoiceCard(inv, onClick = { state.push(PreviewRoute(inv.id)) })
                }
            }
        }
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

@Composable
private fun SquareIconButton(icon: AppIcon) {
    val c = BillantaTheme.colors
    Box(
        Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) { BillantaIcon(icon, c.textSecondary, size = 20.dp) }
}

@Composable
private fun ShimmerCard() {
    val c = BillantaTheme.colors
    val transition = rememberInfiniteTransition()
    val alpha by transition.animateFloat(
        initialValue = 0.45f, targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse),
    )
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(18.dp)).padding(16.dp),
    ) {
        Column {
            Bar(0.5f, 16, alpha)
            Spacer(Modifier.height(10.dp))
            Bar(0.72f, 12, alpha)
        }
    }
}

@Composable
private fun Bar(widthFraction: Float, heightDp: Int, alpha: Float) {
    Box(
        Modifier.fillMaxWidth(widthFraction).height(heightDp.dp)
            .clip(RoundedCornerShape(6.dp))
            .graphicsLayer { this.alpha = alpha }
            .background(BillantaTheme.colors.surfaceAlt),
    )
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
            if (hasQuery) "Try a different search or filter." else "Create your first invoice in under 30 seconds.",
            style = BillantaTheme.type.body, color = c.textSecondary, textAlign = TextAlign.Center,
        )
        if (!hasQuery) {
            Spacer(Modifier.height(20.dp))
            com.ferbotz.billanta.ui.components.PrimaryButton("New invoice", onCreate, leadingIcon = AppIcon.Plus)
        }
    }
}
