package com.ferbotz.billanta

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ferbotz.billanta.di.AppContainer
import com.ferbotz.billanta.config.LaunchGate
import com.ferbotz.billanta.state.BillantaState
import com.ferbotz.billanta.state.BusinessProfileRoute
import com.ferbotz.billanta.state.ChooseTemplateRoute
import com.ferbotz.billanta.state.DeleteAccountRoute
import com.ferbotz.billanta.state.EditCustomerRoute
import com.ferbotz.billanta.state.EditProductRoute
import com.ferbotz.billanta.state.EditInvoiceDataRoute
import com.ferbotz.billanta.state.EditSectionRoute
import com.ferbotz.billanta.state.EditLineRoute
import com.ferbotz.billanta.state.InvoiceDefaultsRoute
import com.ferbotz.billanta.state.PickCustomerRoute
import com.ferbotz.billanta.state.PreviewRoute
import com.ferbotz.billanta.state.Route
import com.ferbotz.billanta.state.SettingsRoute
import androidx.compose.material3.AlertDialog
import com.ferbotz.billanta.ui.components.TextButtonLink
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.components.BottomTab
import com.ferbotz.billanta.ui.components.BottomTabBar
import com.ferbotz.billanta.ui.components.PrimaryButton
import com.ferbotz.billanta.ui.AppIcon
import com.ferbotz.billanta.ui.BillantaIcon
import com.ferbotz.billanta.ui.screens.BillantaSheetHost
import com.ferbotz.billanta.ui.screens.BusinessProfileScreen
import com.ferbotz.billanta.ui.screens.ChooseTemplateScreen
import com.ferbotz.billanta.ui.screens.DeleteAccountScreen
import com.ferbotz.billanta.ui.screens.EditInvoiceDataScreen
import com.ferbotz.billanta.ui.screens.EditSectionScreen
import com.ferbotz.billanta.ui.screens.EditLineScreen
import com.ferbotz.billanta.ui.screens.InvoiceDefaultsScreen
import com.ferbotz.billanta.ui.screens.PickCustomerScreen
import com.ferbotz.billanta.ui.screens.CatalogueScreen
import com.ferbotz.billanta.ui.screens.EditCustomerScreen
import com.ferbotz.billanta.ui.screens.EditProductScreen
import com.ferbotz.billanta.ui.screens.InvoicesScreen
import com.ferbotz.billanta.ui.screens.PreviewScreen
import com.ferbotz.billanta.ui.screens.ProfileScreen
import com.ferbotz.billanta.ui.screens.SettingsScreen
import com.ferbotz.billanta.ui.screens.TemplatesScreen
import kotlinx.coroutines.delay

@Composable
fun App(
    container: AppContainer,
    /**
     * Closes the app when the user confirms they want to leave.
     *
     * Supplied by the platform because only it can do this meaningfully: on Android it finishes the
     * activity, and on iOS an app is not supposed to exit itself, so the default does nothing.
     */
    onExit: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val state = remember { BillantaState(container, scope) }
    BillantaTheme(darkTheme = state.isDark) {
        // Always enabled: back now means something on a tab root too — return to Invoices, then
        // ask before leaving — rather than falling through to the system and closing the app.
        SystemBackHandler(enabled = true) { state.back() }

        Surface(Modifier.fillMaxSize(), color = BillantaTheme.colors.background) {
            // One gate at the root, in the protocol's order (BE-014): maintenance, then a forced
            // update, then a recommended one. Only a forced update keeps the app's content away.
            val gate = state.launchGate
            Box(Modifier.fillMaxSize()) {
                if (gate is LaunchGate.UpdateRequired) {
                    UpdateRequiredScreen(gate, onUpdate = container.openUrl)
                } else {
                    Column(Modifier.fillMaxSize()) {
                        val maintenance = gate is LaunchGate.Maintenance
                        if (gate is LaunchGate.Maintenance) MaintenanceBanner(gate.message)
                        // The banner has taken the status bar's space, so the screens below must
                        // not pad for it a second time.
                        Box(
                            Modifier.weight(1f).fillMaxWidth()
                                .then(if (maintenance) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier),
                        ) {
                            Crossfade(targetState = state.currentRoute, animationSpec = tween(240)) { route ->
                                if (route == null) TabRootHost(state) else RouteHost(state, route)
                            }
                        }
                    }
                    BillantaSheetHost(state)
                    UiMessageHost(state, Modifier.align(Alignment.BottomCenter))
                    if (gate is LaunchGate.UpdateRecommended && !state.recommendedUpdateDismissed) {
                        RecommendedUpdateDialog(
                            gate,
                            onUpdate = container.openUrl,
                            onDismiss = { state.recommendedUpdateDismissed = true },
                        )
                    }
                }
                if (state.confirmingExit) {
                    ExitConfirmation(
                        onDismiss = { state.dismissExitConfirmation() },
                        onExit = { state.dismissExitConfirmation(); onExit() },
                    )
                }
            }
        }
    }
}

/**
 * Maintenance: the message takes the place of the cloud features, and nothing else.
 *
 * Billanta keeps every invoice on the phone, so there is no reason a backend outage should stop
 * anyone invoicing — the banner says what is paused while everything local carries on.
 */
@Composable
private fun MaintenanceBanner(message: String?) {
    val c = BillantaTheme.colors
    Row(
        Modifier.fillMaxWidth().background(c.warningBg).statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BillantaIcon(AppIcon.CloudOff, c.warning, size = 20.dp)
        Text(
            message ?: "Billanta is down for maintenance. Everything on this phone still works — " +
                "sign-in and syncing are paused until it's back.",
            style = BillantaTheme.type.caption,
            color = c.textPrimary,
        )
    }
}

/**
 * This build may no longer be used. There is no way past it but updating — which is what
 * `minSupportedBuild` means — so it replaces the app rather than sitting over it.
 */
@Composable
private fun UpdateRequiredScreen(gate: LaunchGate.UpdateRequired, onUpdate: (String) -> Unit) {
    val c = BillantaTheme.colors
    Column(
        Modifier.fillMaxSize().background(c.background).systemBarsPadding().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(76.dp).clip(RoundedCornerShape(22.dp)).background(c.primaryMuted),
            contentAlignment = Alignment.Center,
        ) { BillantaIcon(AppIcon.Download, c.primary, size = 34.dp) }
        Spacer(Modifier.height(18.dp))
        Text("Update Billanta", style = BillantaTheme.type.sectionTitle, color = c.textPrimary)
        Spacer(Modifier.height(8.dp))
        Text(
            gate.message ?: "This version of Billanta is no longer supported. Update to keep using it " +
                "— your invoices stay safe on this phone.",
            style = BillantaTheme.type.body,
            color = c.textSecondary,
            textAlign = TextAlign.Center,
        )
        // No listing yet means no button: a message beats a link that goes nowhere.
        gate.storeUrl?.let { url ->
            Spacer(Modifier.height(24.dp))
            PrimaryButton("Update", onClick = { onUpdate(url) }, leadingIcon = AppIcon.Download)
        }
    }
}

@Composable
private fun RecommendedUpdateDialog(
    gate: LaunchGate.UpdateRecommended,
    onUpdate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = BillantaTheme.colors
    val storeUrl = gate.storeUrl
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text("Update available", style = BillantaTheme.type.sectionTitle, color = c.textPrimary) },
        text = {
            Text(
                gate.message ?: "A newer version of Billanta is available.",
                style = BillantaTheme.type.body,
                color = c.textSecondary,
            )
        },
        confirmButton = {
            if (storeUrl != null) {
                TextButtonLink("Update", onClick = { onDismiss(); onUpdate(storeUrl) })
            } else {
                TextButtonLink("OK", onClick = onDismiss)
            }
        },
        dismissButton = { if (storeUrl != null) TextButtonLink("Later", onClick = onDismiss) },
    )
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
        is EditSectionRoute -> EditSectionScreen(state, route.invoiceId, route.sectionId)
        is EditLineRoute -> EditLineScreen(state, route.invoiceId, route.lineId)
        is PickCustomerRoute -> PickCustomerScreen(state, route.invoiceId)
        InvoiceDefaultsRoute -> InvoiceDefaultsScreen(state)
        BusinessProfileRoute -> BusinessProfileScreen(state)
        SettingsRoute -> SettingsScreen(state)
        DeleteAccountRoute -> DeleteAccountScreen(state)
        is EditCustomerRoute -> EditCustomerScreen(state, route.customerId, route.attachToInvoiceId)
        is EditProductRoute -> EditProductScreen(state, route.productId)
    }
}

/** Asked once, on the last back press — leaving is cheap to undo but annoying to do by accident. */
@Composable
private fun ExitConfirmation(onDismiss: () -> Unit, onExit: () -> Unit) {
    val c = BillantaTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text("Close Billanta?", style = BillantaTheme.type.sectionTitle, color = c.textPrimary) },
        text = {
            Text(
                "You can continue from where you left off next time.",
                style = BillantaTheme.type.body,
                color = c.textSecondary,
            )
        },
        confirmButton = { TextButtonLink("Confirm", color = c.danger, onClick = onExit) },
        dismissButton = { TextButtonLink("Cancel", onClick = onDismiss) },
    )
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
