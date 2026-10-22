package com.ferbotz.billanta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import com.ferbotz.billanta.ui.components.ProductImage
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.domain.model.CustomerRecord
import com.ferbotz.billanta.domain.model.ProductRecord
import com.ferbotz.billanta.model.ProductKind
import com.ferbotz.billanta.model.formatPaiseCompact
import com.ferbotz.billanta.model.formatPaiseTrimmed
import com.ferbotz.billanta.model.initialsOf
import com.ferbotz.billanta.model.isSameState
import com.ferbotz.billanta.model.stateCodeFromGstin
import com.ferbotz.billanta.state.BillantaState
import com.ferbotz.billanta.state.CustomerStats
import com.ferbotz.billanta.state.EditCustomerRoute
import com.ferbotz.billanta.state.EditProductRoute
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.AppIcon
import com.ferbotz.billanta.ui.BillantaIcon
import com.ferbotz.billanta.ui.components.Avatar
import com.ferbotz.billanta.ui.components.BottomBarSpace
import com.ferbotz.billanta.ui.components.IconButtonBox
import com.ferbotz.billanta.ui.components.LargeTopBar
import com.ferbotz.billanta.ui.components.PrimaryButton
import com.ferbotz.billanta.ui.components.SurfaceCard
import com.ferbotz.billanta.ui.components.Tag
import com.ferbotz.billanta.ui.components.avatarColors
import kotlinx.coroutines.launch

/** Declaration order is the pane order, left to right. */
private enum class CataloguePane(val label: String) {
    PRODUCTS("Products"),
    CUSTOMERS("Customers"),
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
    // `features.productCatalogue` off hides the products; customers are not part of that switch.
    val panes = if (state.features.productCatalogue) CataloguePane.entries else listOf(CataloguePane.CUSTOMERS)
    val pager = rememberPagerState(pageCount = { panes.size })
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(c.background)) {
        LargeTopBar("Catalogue", actions = {
            // The add button follows the pane you are looking at, so it always means one thing.
            IconButtonBox(AppIcon.Plus, c.primary, onClick = {
                when (panes.getOrElse(pager.currentPage) { panes.first() }) {
                    CataloguePane.CUSTOMERS -> state.push(EditCustomerRoute(null))
                    CataloguePane.PRODUCTS -> state.push(EditProductRoute(null))
                }
            })
        })

        // One pane needs no tabs to choose between.
        if (panes.size > 1) {
            PaneTabs(
                panes = panes,
                selected = pager.currentPage,
                offsetFraction = pager.currentPage + pager.currentPageOffsetFraction,
                onSelect = { index -> scope.launch { pager.animateScrollToPage(index) } },
            )
        }

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
        verticalArrangement = Arrangement.spacedBy(10.dp),
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
            // One card per customer, and one LazyColumn item per card. The whole list used to be a
            // single item, so nothing recycled — every row composed however far down it was.
            val stats = state.customerStats
            val sellerState = state.sellerStateCode
            items(state.customers, key = { it.id }) { cust ->
                CustomerCard(
                    customer = cust,
                    stats = stats[cust.id],
                    sellerStateCode = sellerState,
                    onClick = { state.push(EditCustomerRoute(cust.id)) },
                )
            }
        }
    }
}

@Composable
private fun ProductsPane(state: BillantaState) {
    // A grid of photo cards: a catalogue is browsed by eye, and two across still leaves each card
    // room for a two-line name. Adaptive, so a tablet gets more columns rather than wider cards.
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = ProductCardMinWidth),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = BottomBarSpace),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.products.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
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
            items(state.products, key = { it.id }) { product ->
                ProductGridCard(product, onClick = { state.push(EditProductRoute(product.id)) })
            }
        }
    }
}

/**
 * A person or business you bill: a face in their own colour, whether they are GST registered,
 * where they are — and whether that is your state, which decides CGST + SGST against IGST — how
 * to reach them, and what you have billed them.
 */
@Composable
private fun CustomerCard(
    customer: CustomerRecord,
    stats: CustomerStats?,
    sellerStateCode: String?,
    onClick: () -> Unit,
) {
    val c = BillantaTheme.colors
    val (avatarBg, avatarFg) = avatarColors(customer.id)
    val gstin = customer.gstin?.trim()?.takeIf { it.isNotEmpty() }
    val place = listOfNotNull(customer.city, customer.state).map(String::trim).filter(String::isNotEmpty)
        .joinToString(", ")
    val sameState = isSameState(sellerStateCode, customer.stateCode ?: gstin?.let(::stateCodeFromGstin))
    val location = listOfNotNull(
        place.takeIf { it.isNotEmpty() },
        sameState?.let { if (it) "same state" else "other state" },
    ).joinToString(" · ")
    val contact = listOfNotNull(customer.phone, customer.email).map(String::trim).filter(String::isNotEmpty)
        .joinToString(" · ")

    SurfaceCard(Modifier.fillMaxWidth(), onClick = onClick, padding = 0) {
        Column {
            Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(initialsOf(customer.name), size = 46, bg = avatarBg, fg = avatarFg)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            customer.name,
                            style = BillantaTheme.type.cardTitle,
                            color = c.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        // Only the registered case is labelled: without a GSTIN a customer may be a
                        // person or an unregistered business, and a label would have to guess which.
                        if (gstin != null) {
                            Spacer(Modifier.width(8.dp))
                            Tag("GST registered", color = c.primary, background = c.primaryMuted)
                        }
                    }
                    gstin?.let { Text("GSTIN $it", style = BillantaTheme.type.caption, color = c.textSecondary) }
                    if (location.isNotEmpty()) DetailLine(AppIcon.MapPin, location)
                    if (contact.isNotEmpty()) DetailLine(AppIcon.Phone, contact)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
            if (stats == null || stats.invoiceCount == 0) {
                Text(
                    "No invoices yet",
                    style = BillantaTheme.type.caption,
                    color = c.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                )
            } else {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Stat("${stats.invoiceCount}", if (stats.invoiceCount == 1) "invoice" else "invoices", Modifier.weight(1f))
                    Stat(stats.billedPaise.formatPaiseCompact(), "billed", Modifier.weight(1f))
                    Stat(Iso8601.formatDayMonth(stats.lastInvoiceMillis), "last invoice", Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Something you sell, as a card in the catalogue grid: its photo on top — or the service/goods
 * tile until it has one — then the name, the rate and what it is per, its GST rate and SAC/HSN
 * code, and how often it has actually been invoiced.
 *
 * Every card reserves two lines for the name and shows every row, so the cards in a grid row come
 * out the same height and their bottoms line up.
 */
@Composable
private fun ProductGridCard(product: ProductRecord, onClick: () -> Unit) {
    val c = BillantaTheme.colors
    val kind = ProductKind.of(product.hsnSac)
    val code = product.hsnSac?.trim()?.takeIf { it.isNotEmpty() }
    SurfaceCard(Modifier.fillMaxWidth(), onClick = onClick, padding = 0) {
        Column {
            Box {
                ProductImage(
                    url = product.previewImageUrl,
                    kind = kind,
                    modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f),
                    iconSize = 34.dp,
                )
                // With a photo the tile's icon is hidden, so what the product is moves to a label.
                if (product.previewImageUrl != null && kind != ProductKind.Unspecified) {
                    Tag(
                        if (kind == ProductKind.Service) "Service" else "Goods",
                        color = c.textPrimary,
                        background = c.surface.copy(alpha = 0.92f),
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    )
                }
            }
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    product.name,
                    style = BillantaTheme.type.bodyStrong,
                    color = c.textPrimary,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        product.unitPricePaise.formatPaiseTrimmed(),
                        style = BillantaTheme.type.amount,
                        color = c.textPrimary,
                        maxLines = 1,
                    )
                    product.unit?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        Text(
                            " / $it",
                            style = BillantaTheme.type.caption,
                            color = c.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = 1.dp),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tag("${product.taxRatePercent}% GST", color = c.textSecondary, background = c.surfaceAlt)
                    Text(
                        when (kind) {
                            ProductKind.Service -> "SAC $code"
                            ProductKind.Goods -> "HSN $code"
                            ProductKind.Unspecified -> "No HSN/SAC"
                        },
                        style = BillantaTheme.type.caption,
                        color = if (kind == ProductKind.Unspecified) c.textMuted else c.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    usageLine(product),
                    style = BillantaTheme.type.caption,
                    color = c.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The narrowest a product card gets before the grid drops a column: two across on any phone. */
private val ProductCardMinWidth = 150.dp

/** Counted per line added, not per invoice — so "times", which is what it actually measures. */
private fun usageLine(product: ProductRecord): String {
    if (product.usageCount <= 0) return "Not on an invoice yet"
    val count = if (product.usageCount == 1L) "Used once" else "Used ${product.usageCount} times"
    return if (product.lastUsedAtMillis > 0) "$count · last ${Iso8601.formatDayMonth(product.lastUsedAtMillis)}" else count
}

@Composable
private fun DetailLine(icon: AppIcon, text: String) {
    val c = BillantaTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        BillantaIcon(icon, c.textMuted, size = 14.dp)
        Text(text, style = BillantaTheme.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    val c = BillantaTheme.colors
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = BillantaTheme.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary, maxLines = 1)
        Text(label, style = BillantaTheme.type.caption, color = c.textMuted, maxLines = 1)
    }
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
