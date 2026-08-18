package com.ferbotz.billanta

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.ferbotz.billanta.di.AppContainer
import com.ferbotz.billanta.state.BillantaState
import com.ferbotz.billanta.state.BusinessProfileRoute
import com.ferbotz.billanta.state.ChooseTemplateRoute
import com.ferbotz.billanta.state.EditCustomerRoute
import com.ferbotz.billanta.state.EditProductRoute
import com.ferbotz.billanta.state.EditInvoiceDataRoute
import com.ferbotz.billanta.state.EditSectionRoute
import com.ferbotz.billanta.state.PreviewRoute
import com.ferbotz.billanta.state.Route
import com.ferbotz.billanta.state.SettingsRoute
import com.ferbotz.billanta.state.SignInRoute
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.components.BottomTab
import com.ferbotz.billanta.ui.components.BottomTabBar
import com.ferbotz.billanta.ui.screens.BillantaSheetHost
import com.ferbotz.billanta.ui.screens.BusinessProfileScreen
import com.ferbotz.billanta.ui.screens.ChooseTemplateScreen
import com.ferbotz.billanta.ui.screens.EditInvoiceDataScreen
import com.ferbotz.billanta.ui.screens.EditSectionScreen
import com.ferbotz.billanta.ui.screens.CatalogueScreen
import com.ferbotz.billanta.ui.screens.EditCustomerScreen
import com.ferbotz.billanta.ui.screens.EditProductScreen
import com.ferbotz.billanta.ui.screens.InvoicesScreen
import com.ferbotz.billanta.ui.screens.PreviewScreen
import com.ferbotz.billanta.ui.screens.ProfileScreen
import com.ferbotz.billanta.ui.screens.SettingsScreen
import com.ferbotz.billanta.ui.screens.SignInScreen
import com.ferbotz.billanta.ui.screens.TemplatesScreen
import kotlinx.coroutines.delay

@Composable
fun App(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val state = remember { BillantaState(container, scope) }
    BillantaTheme(darkTheme = state.isDark) {
        SystemBackHandler(enabled = state.currentRoute != null || state.sheet != null) { state.back() }

        Surface(Modifier.fillMaxSize(), color = BillantaTheme.colors.background) {
            Box(Modifier.fillMaxSize()) {
                Crossfade(targetState = state.currentRoute, animationSpec = tween(240)) { route ->
                    if (route == null) TabRootHost(state) else RouteHost(state, route)
                }
                BillantaSheetHost(state)
                UiMessageHost(state, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

@Composable
private fun TabRootHost(state: BillantaState) {
    Box(Modifier.fillMaxSize()) {
        when (state.tab) {
            BottomTab.INVOICES -> InvoicesScreen(state)
            BottomTab.CATALOGUE -> CatalogueScreen(state)
            BottomTab.TEMPLATES -> TemplatesScreen(state)
            BottomTab.PROFILE -> ProfileScreen(state)
        }
        BottomTabBar(
            current = state.tab,
            onSelect = { state.selectTab(it) },
            onFab = { state.openCreate() },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun RouteHost(state: BillantaState, route: Route) {
    when (route) {
        ChooseTemplateRoute -> ChooseTemplateScreen(state)
        is PreviewRoute -> PreviewScreen(state, route.invoiceId)
        is EditInvoiceDataRoute -> EditInvoiceDataScreen(state, route.invoiceId)
        is EditSectionRoute -> EditSectionScreen(state, route.invoiceId, route.edits, route.label)
        BusinessProfileRoute -> BusinessProfileScreen(state)
        SettingsRoute -> SettingsScreen(state)
        SignInRoute -> SignInScreen(state)
        is EditCustomerRoute -> EditCustomerScreen(state, route.customerId, route.attachToInvoiceId)
        is EditProductRoute -> EditProductScreen(state, route.productId)
    }
}

/** Transient action errors (sync conflicts, validation) — tap or wait to dismiss. */
@Composable
private fun UiMessageHost(state: BillantaState, modifier: Modifier = Modifier) {
    val message = state.uiMessage ?: return
    val c = BillantaTheme.colors
    LaunchedEffect(message) {
        delay(4_000)
        if (state.uiMessage == message) state.uiMessage = null
    }
    Box(
        modifier
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 96.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.textPrimary)
            .clickable { state.uiMessage = null }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(message, style = BillantaTheme.type.body, color = c.background)
    }
}
