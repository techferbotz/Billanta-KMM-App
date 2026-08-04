package com.ferbotz.billanta

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ferbotz.billanta.state.BillantaState
import com.ferbotz.billanta.state.BusinessProfileRoute
import com.ferbotz.billanta.state.CreateInvoiceRoute
import com.ferbotz.billanta.state.EditCustomerRoute
import com.ferbotz.billanta.state.PreviewRoute
import com.ferbotz.billanta.state.Route
import com.ferbotz.billanta.state.SettingsRoute
import com.ferbotz.billanta.state.SignInRoute
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.components.BottomTab
import com.ferbotz.billanta.ui.components.BottomTabBar
import com.ferbotz.billanta.ui.screens.BillantaSheetHost
import com.ferbotz.billanta.ui.screens.BusinessProfileScreen
import com.ferbotz.billanta.ui.screens.CreateInvoiceScreen
import com.ferbotz.billanta.ui.screens.CustomersScreen
import com.ferbotz.billanta.ui.screens.EditCustomerScreen
import com.ferbotz.billanta.ui.screens.InvoicesScreen
import com.ferbotz.billanta.ui.screens.PreviewScreen
import com.ferbotz.billanta.ui.screens.ProfileScreen
import com.ferbotz.billanta.ui.screens.SettingsScreen
import com.ferbotz.billanta.ui.screens.SignInScreen
import com.ferbotz.billanta.ui.screens.TemplatesScreen
import kotlinx.coroutines.delay

@Composable
fun App() {
    val state = remember { BillantaState() }
    BillantaTheme(darkTheme = state.isDark) {
        LaunchedEffect(Unit) {
            delay(900)
            state.loading = false
        }
        SystemBackHandler(enabled = state.currentRoute != null || state.sheet != null) { state.back() }

        Surface(Modifier.fillMaxSize(), color = BillantaTheme.colors.background) {
            Box(Modifier.fillMaxSize()) {
                Crossfade(targetState = state.currentRoute, animationSpec = tween(240)) { route ->
                    if (route == null) TabRootHost(state) else RouteHost(state, route)
                }
                BillantaSheetHost(state)
            }
        }
    }
}

@Composable
private fun TabRootHost(state: BillantaState) {
    Box(Modifier.fillMaxSize()) {
        when (state.tab) {
            BottomTab.INVOICES -> InvoicesScreen(state)
            BottomTab.CUSTOMERS -> CustomersScreen(state)
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
        CreateInvoiceRoute -> CreateInvoiceScreen(state)
        is PreviewRoute -> PreviewScreen(state, route.invoiceId)
        BusinessProfileRoute -> BusinessProfileScreen(state)
        SettingsRoute -> SettingsScreen(state)
        SignInRoute -> SignInScreen(state)
        is EditCustomerRoute -> EditCustomerScreen(state, route.customerId)
    }
}
