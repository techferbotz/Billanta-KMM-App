package com.ferbotz.billanta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.core.InvoiceDateFormat
import com.ferbotz.billanta.core.SystemClock
import com.ferbotz.billanta.core.todayIso
import com.ferbotz.billanta.fields.FieldValues
import com.ferbotz.billanta.fields.StartingValues
import com.ferbotz.billanta.state.InvoiceDefaultsRoute
import com.ferbotz.billanta.ui.components.ChipRow
import com.ferbotz.billanta.ui.fields.RecordForm
import com.ferbotz.billanta.model.stateCodeFromGstin
import com.ferbotz.billanta.state.BillantaState
import com.ferbotz.billanta.state.BusinessProfileRoute
import com.ferbotz.billanta.state.DeleteAccountRoute
import com.ferbotz.billanta.state.SettingsRoute
import com.ferbotz.billanta.ui.components.SecondaryButton
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextAlign
import com.ferbotz.billanta.domain.model.UserAccount
import com.ferbotz.billanta.model.initialsOf
import com.ferbotz.billanta.ui.components.Avatar
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.AppIcon
import com.ferbotz.billanta.ui.GoogleMark
import com.ferbotz.billanta.ui.BillantaIcon
import com.ferbotz.billanta.ui.components.BillantaTextField
import com.ferbotz.billanta.ui.components.BottomActionBar
import com.ferbotz.billanta.ui.components.BottomBarSpace
import com.ferbotz.billanta.ui.components.BottomTab
import com.ferbotz.billanta.ui.components.IconTile
import com.ferbotz.billanta.ui.components.LargeTopBar
import com.ferbotz.billanta.ui.components.ListRow
import com.ferbotz.billanta.ui.components.Overline
import com.ferbotz.billanta.ui.components.PrimaryButton
import com.ferbotz.billanta.ui.components.SignInConsentLine
import com.ferbotz.billanta.ui.components.StackTopBar
import com.ferbotz.billanta.ui.components.SurfaceCard

@Composable
fun ProfileScreen(state: BillantaState) {
    val c = BillantaTheme.colors
    Column(Modifier.fillMaxSize().background(c.background)) {
        LargeTopBar("Profile")
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = BottomBarSpace),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // Who you are. There is no business card here any more: "Business profile" in
            // the list below opens the same editor, and two entry points to one form on one screen
            // was the duplication rather than a shortcut.
            item {
                val user = state.currentUser
                when {
                    user != null -> AccountHeader(user)
                    // Sign-in switched off (or the backend is in maintenance): the app is fully
                    // usable offline, so there is nothing to offer here rather than a dead button.
                    state.features.googleLogin -> SignInCard(state)
                }
            }

            // Quick links
            item {
                Column {
                    Overline("Preferences")
                    Spacer(Modifier.height(8.dp))
                    SurfaceCard(Modifier.fillMaxWidth(), padding = 4) {
                        Column {
                            ListRow(
                                title = "Business profile",
                                leading = { IconTile(AppIcon.Receipt) },
                                onClick = { state.push(BusinessProfileRoute) },
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                            if (state.features.templateStore) {
                                RowDivider()
                                ListRow(
                                    title = "Invoice templates",
                                    leading = { IconTile(AppIcon.Grid) },
                                    onClick = { state.selectTab(BottomTab.TEMPLATES) },
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                )
                            }
                            RowDivider()
                            ListRow(
                                title = "Settings",
                                leading = { IconTile(AppIcon.Tune) },
                                onClick = { state.push(SettingsRoute) },
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }
                    }
                }
            }

            if (state.currentUser != null) {
                item {
                    SecondaryButton(
                        "Sign out",
                        onClick = { state.signOut() },
                        leadingIcon = AppIcon.Lock,
                        tint = c.danger,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item {
                Text(
                    "Billanta v${state.container.identity.version}",
                    style = BillantaTheme.type.caption,
                    color = c.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}

/** The signed-in identity, given the room it deserves — this is what the screen is about. */
@Composable
private fun AccountHeader(user: UserAccount) {
    val c = BillantaTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val photo = user.photoUrl
        if (photo.isNullOrBlank()) {
            Avatar(initialsOf(user.name ?: user.email), size = 96)
        } else {
            AsyncImage(
                model = photo,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(96.dp).clip(CircleShape).background(c.primaryMuted),
            )
        }
        Text(
            user.name ?: user.email,
            style = BillantaTheme.type.screenTitle,
            color = c.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            user.email,
            style = BillantaTheme.type.body,
            color = c.textSecondary,
            textAlign = TextAlign.Center,
        )
        if (user.isPremium) {
            Text(
                "Premium",
                style = BillantaTheme.type.caption.copy(fontWeight = FontWeight.SemiBold),
                color = c.primary,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(c.primaryMuted)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}

/** Signing in is one button, so it lives inline rather than on a screen of its own. */
@Composable
private fun SignInCard(state: BillantaState) {
    val c = BillantaTheme.colors
    SurfaceCard(Modifier.fillMaxWidth(), padding = 16) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Your invoices are on this device", style = BillantaTheme.type.cardTitle, color = c.textPrimary)
            Text(
                "Sign in to back them up and sync across devices. It's optional.",
                style = BillantaTheme.type.caption,
                color = c.textSecondary,
            )
            PrimaryButton(
                if (state.signingIn) "Signing in…" else "Continue with Google",
                onClick = { state.signInWithGoogle {} },
                leadingSlot = { GoogleMark(size = 20.dp) },
                enabled = !state.signingIn,
                modifier = Modifier.fillMaxWidth(),
            )
            SignInConsentLine(
                onTerms = { state.openTerms() },
                onPrivacy = { state.openPrivacyPolicy() },
                modifier = Modifier.fillMaxWidth(),
            )
            state.signInError?.let { error ->
                Text(
                    error,
                    style = BillantaTheme.type.caption,
                    color = c.danger,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(c.dangerBg).padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/**
 * The business profile: `GET /forms` → `company`, so a field the backend adds shows up here without
 * an app release. Invoices not yet shared follow every change saved here (FIELDS.md §8.4).
 */
@Composable
fun BusinessProfileScreen(state: BillantaState) {
    val c = BillantaTheme.colors
    val existing = state.profile?.values
    var values by remember {
        mutableStateOf(
            existing ?: StartingValues.forNewRecord(state.forms.company, state.settings?.values ?: FieldValues.EMPTY, SystemClock.todayIso()),
        )
    }

    Column(Modifier.fillMaxSize().background(c.background)) {
        StackTopBar("Business profile", onBack = { state.pop() })
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RecordForm(
                sections = state.forms.company,
                values = values,
                onValuesChange = { values = it.withStateFromGstin(COMPANY_GSTIN, COMPANY_STATE) },
                context = state.fieldContext(valueOf = { values[it] }),
            )
            Text(
                "These details appear on your invoices. Invoices you haven't shared yet follow every change you save here.",
                style = BillantaTheme.type.caption, color = c.textMuted,
            )
            Spacer(Modifier.height(4.dp))
        }
        BottomActionBar {
            PrimaryButton(
                "Save profile",
                onClick = {
                    state.saveProfile(values)
                    state.pop()
                },
                enabled = values != existing && !state.uploadingImage,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Invoice numbering and the defaults a new invoice starts from: `GET /forms` → `settings`. Saved
 * with a PATCH of the changed keys, so the running invoice number is never rolled back by a device
 * that had an older copy.
 */
@Composable
fun InvoiceDefaultsScreen(state: BillantaState) {
    val c = BillantaTheme.colors
    val stored = state.settings?.values ?: FieldValues.EMPTY
    val shown = StartingValues.withFormDefaults(state.forms.settings, stored)
    var values by remember { mutableStateOf(shown) }

    Column(Modifier.fillMaxSize().background(c.background)) {
        StackTopBar("Invoice defaults", onBack = { state.pop() })
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RecordForm(
                sections = state.forms.settings,
                values = values,
                onValuesChange = { values = it },
                context = state.fieldContext(valueOf = { values[it] }),
            )
            Text(
                "Next invoice: ${values.string(SETTINGS_PREFIX).orEmpty()}${values.string(SETTINGS_NEXT_NUMBER) ?: "1"}",
                style = BillantaTheme.type.caption, color = c.textMuted,
            )
        }
        BottomActionBar {
            PrimaryButton(
                "Save",
                onClick = {
                    state.saveSettings(values)
                    state.pop()
                },
                enabled = values != shown,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Invoicing defaults (the PUT /settings fields) + account actions + legal links. */
@Composable
fun SettingsScreen(state: BillantaState) {
    val c = BillantaTheme.colors

    Column(Modifier.fillMaxSize().background(c.background)) {
        StackTopBar("Settings", onBack = { state.pop() })
        LazyColumn(
            Modifier.weight(1f).fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                SettingsGroup("Appearance") {
                    SwitchRow("Dark mode", AppIcon.Moon, state.isDark) { state.setDarkTheme(it) }
                }
            }
            item {
                SettingsGroup("Invoicing") {
                    ListRow(
                        "Invoice defaults",
                        subtitle = "Numbering, currency, GST %, due date, notes",
                        leading = { IconTile(AppIcon.Receipt) },
                        onClick = { state.push(InvoiceDefaultsRoute) },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
            item {
                Column {
                    Overline("Date format")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "How dates are printed on every invoice.",
                        style = BillantaTheme.type.caption,
                        color = c.textSecondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    // The sample *is* the label: nobody recognises a format from its name.
                    ChipRow(
                        items = InvoiceDateFormat.entries,
                        isSelected = { it == state.dateFormat },
                        label = { it.format(SystemClock.nowMillis()) },
                        onSelect = { state.chooseDateFormat(it) },
                    )
                }
            }
            // Signing in and out live on Profile. What is left here is the one account action that
            // should take some finding: Settings → Account → Delete account. Hidden for now — see
            // SHOW_DELETE_ACCOUNT.
            if (SHOW_DELETE_ACCOUNT && state.currentUser != null) {
                item {
                    SettingsGroup("Account") {
                        ListRow(
                            "Delete account",
                            subtitle = "Permanently delete your account and synced data",
                            leading = { IconTile(AppIcon.Trash, tint = c.danger, bg = c.dangerBg) },
                            onClick = { state.push(DeleteAccountRoute) },
                            danger = true,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
            }
            // Every legal link comes from /config (BE-015), so the pages can move without a release.
            item {
                SettingsGroup("About") {
                    ValueRow("Version", state.container.identity.version)
                    RowDivider()
                    ListRow(
                        "Privacy Policy",
                        leading = { IconTile(AppIcon.Lock) },
                        onClick = { state.openPrivacyPolicy() },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    RowDivider()
                    ListRow(
                        "Terms & Conditions",
                        leading = { IconTile(AppIcon.Info) },
                        onClick = { state.openTerms() },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    RowDivider()
                    ListRow(
                        "Contact support",
                        subtitle = state.links.supportEmail,
                        leading = { IconTile(AppIcon.Plane) },
                        onClick = { state.contactSupport() },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * In-app account deletion (BE-015 / BE-017) is built but not offered yet: a product decision on
 * 2026-09-24. Everything behind the row is in place — [DeleteAccountRoute], its confirmation
 * screen, `DELETE /users/me` and the local wipe — so turning this on is the whole change.
 *
 * Until it is on, the public delete-account page must not send people to an in-app path that is
 * not there; APP-013 asked the backend to give only the email route meanwhile. Google Play expects
 * deletion to be possible inside any app that creates accounts, so revisit this before release.
 */
private const val SHOW_DELETE_ACCOUNT = false

private const val COMPANY_GSTIN = "company_gstin"
private const val COMPANY_STATE = "company_state"
private const val SETTINGS_PREFIX = "settings_invoice_prefix"
private const val SETTINGS_NEXT_NUMBER = "settings_next_invoice_number"

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column {
        Overline(title)
        Spacer(Modifier.height(8.dp))
        SurfaceCard(Modifier.fillMaxWidth(), padding = 4) { Column { content() } }
    }
}

@Composable
private fun SwitchRow(title: String, icon: AppIcon, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = BillantaTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconTile(icon)
        Text(title, style = BillantaTheme.type.bodyStrong, color = c.textPrimary, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onPrimary,
                checkedTrackColor = c.primary,
                uncheckedTrackColor = c.surfaceAlt,
                uncheckedBorderColor = c.border,
                uncheckedThumbColor = c.textMuted,
            ),
        )
    }
}

@Composable
private fun ValueRow(title: String, value: String) {
    val c = BillantaTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = BillantaTheme.type.bodyStrong, color = c.textPrimary, modifier = Modifier.weight(1f))
        Text(value, style = BillantaTheme.type.body, color = c.textSecondary)
    }
}

@Composable
private fun RowDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(BillantaTheme.colors.border))
}
