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
import com.ferbotz.billanta.domain.model.CompanyProfile
import com.ferbotz.billanta.model.stateCodeFromGstin
import com.ferbotz.billanta.state.BillantaState
import com.ferbotz.billanta.state.BusinessProfileRoute
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
                if (user != null) AccountHeader(user) else SignInCard(state)
            }

            // Quick links
            item {
                Column {
                    Overline("Preferences")
                    Spacer(Modifier.height(8.dp))
                    SurfaceCard(Modifier.fillMaxWidth(), padding = 4) {
                        Column {
                            SwitchRow("Dark mode", AppIcon.Moon, state.isDark) { state.setDarkTheme(it) }
                            RowDivider()
                            ListRow(
                                title = "Business profile",
                                leading = { IconTile(AppIcon.Receipt) },
                                onClick = { state.push(BusinessProfileRoute) },
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                            RowDivider()
                            ListRow(
                                title = "Invoice templates",
                                leading = { IconTile(AppIcon.Grid) },
                                onClick = { state.selectTab(BottomTab.TEMPLATES) },
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
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
                    "Billanta · v1.0 · Made for Indian freelancers",
                    style = BillantaTheme.type.caption, color = c.textMuted,
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
                leadingIcon = AppIcon.Google,
                enabled = !state.signingIn,
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

/** The company form — exactly the PUT /company field set. */
@Composable
fun BusinessProfileScreen(state: BillantaState) {
    val c = BillantaTheme.colors
    val existing = state.company
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var gstin by remember { mutableStateOf(existing?.gstin ?: "") }
    var email by remember { mutableStateOf(existing?.email ?: "") }
    var phone by remember { mutableStateOf(existing?.phone ?: "") }
    var addressLine1 by remember { mutableStateOf(existing?.addressLine1 ?: "") }
    var addressLine2 by remember { mutableStateOf(existing?.addressLine2 ?: "") }
    var city by remember { mutableStateOf(existing?.city ?: "") }
    var stateName by remember { mutableStateOf(existing?.state ?: "") }
    var stateCode by remember { mutableStateOf(existing?.stateCode ?: "") }
    var pincode by remember { mutableStateOf(existing?.pincode ?: "") }
    var upiId by remember { mutableStateOf(existing?.upiId ?: "") }
    var bankName by remember { mutableStateOf(existing?.bankName ?: "") }
    var accountNumber by remember { mutableStateOf(existing?.accountNumber ?: "") }
    var ifsc by remember { mutableStateOf(existing?.ifsc ?: "") }

    Column(Modifier.fillMaxSize().background(c.background)) {
        StackTopBar("Business profile", onBack = { state.pop() })
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Overline("Details")
            BillantaTextField(name, { name = it }, label = "Business name", modifier = Modifier.fillMaxWidth())
            BillantaTextField(
                gstin,
                {
                    gstin = it
                    stateCodeFromGstin(it)?.let { derived -> if (stateCode.isBlank()) stateCode = derived }
                },
                label = "GSTIN", placeholder = "27ABCDE1234F1Z5", modifier = Modifier.fillMaxWidth(),
            )
            BillantaTextField(email, { email = it }, label = "Email", keyboardType = KeyboardType.Email, modifier = Modifier.fillMaxWidth())
            BillantaTextField(phone, { phone = it }, label = "Phone", keyboardType = KeyboardType.Phone, modifier = Modifier.fillMaxWidth())
            BillantaTextField(addressLine1, { addressLine1 = it }, label = "Address line 1", modifier = Modifier.fillMaxWidth())
            BillantaTextField(addressLine2, { addressLine2 = it }, label = "Address line 2 (optional)", modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BillantaTextField(city, { city = it }, label = "City", modifier = Modifier.weight(1f))
                BillantaTextField(pincode, { pincode = it }, label = "PIN code", keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BillantaTextField(stateName, { stateName = it }, label = "State", placeholder = "Maharashtra", modifier = Modifier.weight(2f))
                BillantaTextField(
                    stateCode, { stateCode = it.filter { ch -> ch.isDigit() }.take(2) },
                    label = "Code", placeholder = "27", keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f),
                )
            }
            Overline("Payment")
            BillantaTextField(upiId, { upiId = it }, label = "UPI ID", placeholder = "you@bank", modifier = Modifier.fillMaxWidth())
            BillantaTextField(bankName, { bankName = it }, label = "Bank", modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BillantaTextField(accountNumber, { accountNumber = it }, label = "Account number", keyboardType = KeyboardType.Number, modifier = Modifier.weight(3f))
                BillantaTextField(ifsc, { ifsc = it }, label = "IFSC", modifier = Modifier.weight(2f))
            }
            Text(
                "These details appear on your invoices and in the payment block.",
                style = BillantaTheme.type.caption, color = c.textMuted,
            )
            Spacer(Modifier.height(4.dp))
        }
        BottomActionBar {
            PrimaryButton("Save profile", onClick = {
                if (name.isNotBlank()) {
                    state.saveCompany(
                        CompanyProfile(
                            name = name.trim(),
                            gstin = gstin.trim().ifBlank { null },
                            addressLine1 = addressLine1.trim().ifBlank { null },
                            addressLine2 = addressLine2.trim().ifBlank { null },
                            city = city.trim().ifBlank { null },
                            state = stateName.trim().ifBlank { null },
                            stateCode = stateCode.trim().ifBlank { null },
                            pincode = pincode.trim().ifBlank { null },
                            country = existing?.country ?: "India",
                            phone = phone.trim().ifBlank { null },
                            email = email.trim().ifBlank { null },
                            logo = existing?.logo,
                            signature = existing?.signature,
                            upiId = upiId.trim().ifBlank { null },
                            qr = existing?.qr,
                            bankName = bankName.trim().ifBlank { null },
                            accountNumber = accountNumber.trim().ifBlank { null },
                            ifsc = ifsc.trim().ifBlank { null },
                        ),
                    )
                    state.pop()
                }
            }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Invoicing defaults (the PUT /settings fields) + account actions + legal links. */
@Composable
fun SettingsScreen(state: BillantaState) {
    val c = BillantaTheme.colors
    val settings = state.settings
    var taxPercent by remember(settings) { mutableStateOf(settings.defaultTaxPercent) }
    var prefix by remember(settings) { mutableStateOf(settings.invoiceNumberPrefix) }
    var nextNumber by remember(settings) { mutableStateOf(settings.nextInvoiceNumber.toString()) }
    var currency by remember(settings) { mutableStateOf(settings.defaultCurrency) }
    var defaultNotes by remember(settings) { mutableStateOf(settings.defaultNotes ?: "") }

    val dirty = taxPercent != settings.defaultTaxPercent ||
        prefix != settings.invoiceNumberPrefix ||
        nextNumber != settings.nextInvoiceNumber.toString() ||
        currency != settings.defaultCurrency ||
        defaultNotes != (settings.defaultNotes ?: "")

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
                Column {
                    Overline("Invoicing defaults")
                    Spacer(Modifier.height(8.dp))
                    SurfaceCard(Modifier.fillMaxWidth(), padding = 14) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                BillantaTextField(taxPercent, { taxPercent = it }, label = "Default GST %", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
                                BillantaTextField(currency, { currency = it.uppercase().take(3) }, label = "Currency", modifier = Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                BillantaTextField(prefix, { prefix = it }, label = "Number prefix", placeholder = "INV-", modifier = Modifier.weight(2f))
                                BillantaTextField(nextNumber, { nextNumber = it.filter { ch -> ch.isDigit() } }, label = "Next #", keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                            }
                            Text(
                                "Next invoice: $prefix${nextNumber.ifBlank { "1" }}",
                                style = BillantaTheme.type.caption, color = c.textMuted,
                            )
                            BillantaTextField(defaultNotes, { defaultNotes = it }, label = "Default notes", placeholder = "Payment terms shown on every new invoice", singleLine = false, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            // Signing in and out moved to Profile. What is left here is the one account action
            // that should take some finding.
            if (state.currentUser != null) {
                item {
                    SettingsGroup("Account") {
                        ListRow(
                            "Delete account",
                            subtitle = "Removes your account and all synced data",
                            leading = { IconTile(AppIcon.Trash, tint = c.danger, bg = c.dangerBg) },
                            onClick = { state.deleteAccount { state.popToRoot() } },
                            danger = true,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
            }
            item {
                SettingsGroup("About") {
                    ValueRow("Version", "1.0.0")
                    RowDivider()
                    ListRow(
                        "Terms of service",
                        leading = { IconTile(AppIcon.Info) },
                        onClick = { state.container.openUrl("${state.container.config.normalizedBaseUrl}terms") },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    RowDivider()
                    ListRow(
                        "Privacy policy",
                        leading = { IconTile(AppIcon.Info) },
                        onClick = { state.container.openUrl("${state.container.config.normalizedBaseUrl}privacy") },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
        if (dirty) {
            BottomActionBar {
                PrimaryButton("Save settings", onClick = {
                    state.saveSettings(
                        settings.copy(
                            defaultTaxPercent = taxPercent.trim(),
                            invoiceNumberPrefix = prefix,
                            nextInvoiceNumber = nextNumber.toLongOrNull() ?: settings.nextInvoiceNumber,
                            defaultCurrency = currency.trim().ifBlank { "INR" },
                            defaultNotes = defaultNotes.trim().ifBlank { null },
                        ),
                    )
                }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

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
