package com.ferbotz.billanta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ferbotz.billanta.model.formatPaise
import com.ferbotz.billanta.model.initialsOf
import com.ferbotz.billanta.state.BillantaState
import com.ferbotz.billanta.state.EditCustomerRoute
import com.ferbotz.billanta.state.EditProductRoute
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.AppIcon
import com.ferbotz.billanta.ui.BillantaIcon
import com.ferbotz.billanta.ui.components.Avatar
import com.ferbotz.billanta.ui.components.BottomBarSpace
import com.ferbotz.billanta.ui.components.IconButtonBox
import com.ferbotz.billanta.ui.components.IconTile
import com.ferbotz.billanta.ui.components.LargeTopBar
import com.ferbotz.billanta.ui.components.ListRow
import com.ferbotz.billanta.ui.components.PrimaryButton
import com.ferbotz.billanta.ui.components.SurfaceCard
import kotlinx.coroutines.launch

private enum class CataloguePane(val label: String) {
    CUSTOMERS("Customers"),
    PRODUCTS("Products"),
}

/**
 * The reusable things an invoice is built from — who you bill and what you sell.
 *
 * One tab rather than two on the bottom bar: both are supporting data the user visits rarely, and
 * the bottom bar has room for one more idea, not two. New kinds of reusable data (units, tax
 * presets) belong here as further panes rather than as further tabs.
 */
@Composable
fun CatalogueScreen(state: BillantaState) {
    val c = BillantaTheme.colors
    val panes = CataloguePane.entries
    val pager = rememberPagerState(pageCount = { panes.size })
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(c.background)) {
        LargeTopBar("Catalogue", actions = {
            // The add button follows the pane you are looking at, so it always means one thing.
            IconButtonBox(AppIcon.Plus, c.primary, onClick = {
                when (panes[pager.currentPage]) {
                    CataloguePane.CUSTOMERS -> state.push(EditCustomerRoute(null))
                    CataloguePane.PRODUCTS -> state.push(EditProductRoute(null))
                }
            })
        })

        PaneTabs(
            panes = panes,
            selected = pager.currentPage,
            offsetFraction = pager.currentPage + pager.currentPageOffsetFraction,
            onSelect = { index -> scope.launch { pager.animateScrollToPage(index) } },
        )

        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            when (panes[page]) {
                CataloguePane.CUSTOMERS -> CustomersPane(state)
                CataloguePane.PRODUCTS -> ProductsPane(state)
            }
        }
    }
}

/**
 * Two labels with an underline that tracks the pager.
 *
 * Driven by the pager's fractional offset rather than its settled page, so the indicator follows
 * the swipe under the finger instead of snapping once the gesture ends.
 */
@Composable
private fun PaneTabs(
    panes: List<CataloguePane>,
    selected: Int,
    offsetFraction: Float,
    onSelect: (Int) -> Unit,
) {
    val c = BillantaTheme.colors
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        val tabWidth = maxWidth / panes.size
        Column {
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth()) {
                panes.forEachIndexed { index, pane ->
                    val active = index == selected
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                            .clickable { onSelect(index) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            pane.label,
                            style = BillantaTheme.type.body,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (active) c.textPrimary else c.textSecondary,
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(2.dp).background(c.border)) {
                Box(
                    Modifier.offset(x = tabWidth * offsetFraction)
                        .width(tabWidth)
                        .height(2.dp)
                        .background(c.primary),
                )
            }
        }
    }
}

@Composable
private fun CustomersPane(state: BillantaState) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = BottomBarSpace),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (state.customers.isEmpty()) {
            item {
                EmptyPane(
                    icon = AppIcon.People,
                    title = "No customers yet",
                    body = "Add a customer once and reuse them on every invoice.",
                    action = "Add customer",
                    onAction = { state.push(EditCustomerRoute(null)) },
                )
            }
        } else {
            item {
                SurfaceCard(Modifier.fillMaxWidth(), padding = 4) {
                    Column {
                        state.customers.forEachIndexed { i, cust ->
                            ListRow(
                                title = cust.name,
                                subtitle = listOfNotNull(cust.phone, cust.email, cust.city).firstOrNull(),
                                leading = { Avatar(initialsOf(cust.name), size = 44) },
                                trailingText = "${state.invoiceCountFor(cust.id)} inv",
                                onClick = { state.push(EditCustomerRoute(cust.id)) },
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                            if (i < state.customers.size - 1) RowSeparator(inset = 66)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductsPane(state: BillantaState) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = BottomBarSpace),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (state.products.isEmpty()) {
            item {
                EmptyPane(
                    icon = AppIcon.Catalogue,
                    title = "No products yet",
                    body = "Items you add to an invoice are saved here automatically, " +
                        "so you never retype a rate. You can also add one now.",
                    action = "Add product",
                    onAction = { state.push(EditProductRoute(null)) },
                )
            }
        } else {
            item {
                SurfaceCard(Modifier.fillMaxWidth(), padding = 4) {
                    Column {
                        state.products.forEachIndexed { i, product ->
                            ListRow(
                                title = product.name,
                                subtitle = listOfNotNull(
                                    product.hsnSac?.let { "HSN $it" },
                                    "${product.taxRatePercent}% GST",
                                ).joinToString(" · "),
                                leading = { IconTile(AppIcon.Catalogue) },
                                trailingText = product.unitPricePaise.formatPaise(withPaise = false),
                                onClick = { state.push(EditProductRoute(product.id)) },
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                            if (i < state.products.size - 1) RowSeparator(inset = 66)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowSeparator(inset: Int) {
    Spacer(
        Modifier.height(1.dp).fillMaxWidth().padding(start = inset.dp)
            .background(BillantaTheme.colors.border),
    )
}

@Composable
private fun EmptyPane(
    icon: AppIcon,
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
) {
    val c = BillantaTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(76.dp).clip(RoundedCornerShape(22.dp)).background(c.primaryMuted),
            contentAlignment = Alignment.Center,
        ) { BillantaIcon(icon, c.primary, size = 34.dp) }
        Spacer(Modifier.height(18.dp))
        Text(title, style = BillantaTheme.type.sectionTitle, color = c.textPrimary)
        Spacer(Modifier.height(6.dp))
        Text(body, style = BillantaTheme.type.body, color = c.textSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        PrimaryButton(action, onAction, leadingIcon = AppIcon.Plus)
    }
}
