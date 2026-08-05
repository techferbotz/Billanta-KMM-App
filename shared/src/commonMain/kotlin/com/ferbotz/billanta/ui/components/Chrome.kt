package com.ferbotz.billanta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.AppIcon
import com.ferbotz.billanta.ui.BillantaIcon

enum class BottomTab(val icon: AppIcon, val label: String) {
    INVOICES(AppIcon.Receipt, "Invoices"),
    CUSTOMERS(AppIcon.People, "Customers"),
    TEMPLATES(AppIcon.Grid, "Templates"),
    PROFILE(AppIcon.Person, "Profile"),
}

/** Space the tabbed screens should reserve at the bottom of scrolling content. */
val BottomBarSpace: Dp = 96.dp

@Composable
fun IconButtonBox(icon: AppIcon, tint: Color, onClick: () -> Unit, size: Dp = 24.dp) {
    Box(
        Modifier.clip(CircleShape).clickable(onClick = onClick).padding(6.dp),
        contentAlignment = Alignment.Center,
    ) { BillantaIcon(icon, tint, size = size) }
}

/** Large-title top bar for the primary tab screens. */
@Composable
fun LargeTopBar(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    val c = BillantaTheme.colors
    Column(modifier.fillMaxWidth().background(c.background).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = BillantaTheme.type.screenTitle, color = c.textPrimary, modifier = Modifier.weight(1f))
            actions()
        }
    }
}

/** Compact top bar with a back affordance for pushed screens. */
@Composable
fun StackTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    val c = BillantaTheme.colors
    Column(modifier.fillMaxWidth().background(c.background).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButtonBox(AppIcon.ArrowLeft, c.textPrimary, onBack)
            Text(
                title,
                style = BillantaTheme.type.sectionTitle,
                color = c.textPrimary,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            actions()
        }
    }
}

/** Thin status strip under the top bar: offline / sign-in nudge / sync progress. */
@Composable
fun StatusBanner(
    text: String,
    modifier: Modifier = Modifier,
    dotColor: Color? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = BillantaTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(c.surfaceAlt)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ColorDot(dotColor ?: c.accentDot, size = 7)
        Text(text, style = BillantaTheme.type.caption, color = c.textSecondary)
        if (actionLabel != null && onAction != null) {
            Text("·", style = BillantaTheme.type.caption, color = c.textMuted)
            Text(
                actionLabel,
                style = BillantaTheme.type.caption,
                color = c.textSecondary,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onAction),
            )
        }
    }
}

/** Bottom navigation bar with the centered, elevated FAB straddling its top edge. */
@Composable
fun BottomTabBar(
    current: BottomTab,
    onSelect: (BottomTab) -> Unit,
    onFab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = BillantaTheme.colors
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.surface)
                .navigationBarsPadding()
                .padding(top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabItem(BottomTab.INVOICES, current, onSelect, Modifier.weight(1f))
            TabItem(BottomTab.CUSTOMERS, current, onSelect, Modifier.weight(1f))
            Spacer(Modifier.weight(1f)) // gap under the FAB
            TabItem(BottomTab.TEMPLATES, current, onSelect, Modifier.weight(1f))
            TabItem(BottomTab.PROFILE, current, onSelect, Modifier.weight(1f))
        }
        // Elevated FAB, lifted so it overlaps the bar's top edge.
        Box(
            Modifier
                .padding(bottom = 0.dp)
                .offset(y = (-22).dp)
                .size(58.dp)
                .shadow(10.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .background(c.surface)
                .padding(3.dp)
                .clip(CircleShape)
                .background(c.primary)
                .clickable(onClick = onFab),
            contentAlignment = Alignment.Center,
        ) {
            BillantaIcon(AppIcon.Plus, c.onPrimary, size = 26.dp)
        }
    }
}

@Composable
private fun TabItem(
    tab: BottomTab,
    current: BottomTab,
    onSelect: (BottomTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = BillantaTheme.colors
    val active = tab == current
    val tint = if (active) c.primary else c.textMuted
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).clickable { onSelect(tab) }.padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        BillantaIcon(tab.icon, tint, size = 23.dp)
        Text(tab.label, style = BillantaTheme.type.caption.copy(fontSize = 11.sp), color = tint)
    }
}

/** Sticky bottom action area for stack screens (e.g. Save & Preview). */
@Composable
fun BottomActionBar(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = BillantaTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(c.surface)
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .navigationBarsPadding(),
    ) { content() }
}
