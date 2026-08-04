package com.ferbotz.billanta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ferbotz.billanta.model.BusinessProfile
import com.ferbotz.billanta.state.BillantaState
import com.ferbotz.billanta.state.BusinessProfileRoute
import com.ferbotz.billanta.state.SettingsRoute
import com.ferbotz.billanta.state.SignInRoute
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
    val biz = state.business
    Column(Modifier.fillMaxSize().background(c.background)) {
        LargeTopBar("Profile")
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = BottomBarSpace),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // Business summary
            item {
                SurfaceCard(Modifier.fillMaxWidth(), onClick = { state.push(BusinessProfileRoute) }, padding = 16) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(c.primary), contentAlignment = Alignment.Center) {
                            Text(biz.name.take(1), color = c.onPrimary, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(biz.name, style = BillantaTheme.type.cardTitle, color = c.textPrimary)
                            Text(biz.tagline ?: biz.email, style = BillantaTheme.type.caption, color = c.textSecondary)
                            Text("GSTIN ${biz.gstin}", style = BillantaTheme.type.caption, color = c.textMuted)
                        }
                        BillantaIcon(AppIcon.ChevronRight, c.textMuted, size = 20.dp)
                    }
                }
            }

            // Account
            item {
                Column {
                    Overline("Account")
                    Spacer(Modifier.height(8.dp))
                    SurfaceCard(Modifier.fillMaxWidth(), padding = 4) {
                        if (state.signedIn) {
                            ListRow(
                                title = "Signed in",
                                subtitle = biz.email,
                                leading = { IconTile(AppIcon.Person, tint = c.success, bg = c.successBg) },
                                trailingText = "Backed up",
                                trailingIcon = null,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        } else {
                            ListRow(
                                title = "Sign in to back up",
                                subtitle = "Optional · sync across devices",
                                leading = { IconTile(AppIcon.CloudOff, tint = c.warning, bg = c.warningBg) },
                                onClick = { state.push(SignInRoute) },
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }
                    }
                }
            }

            // Quick settings
            item {
                Column {
                    Overline("Preferences")
                    Spacer(Modifier.height(8.dp))
                    SurfaceCard(Modifier.fillMaxWidth(), padding = 4) {
                        Column {
                            SwitchRow("Dark mode", AppIcon.Moon, state.isDark) { state.isDark = it }
                            RowDivider()
                            SwitchRow("Work offline", AppIcon.CloudOff, state.isOffline) { state.isOffline = it }
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

@Composable
fun BusinessProfileScreen(state: BillantaState) {
    val c = BillantaTheme.colors
    val biz = state.business
    var name by remember { mutableStateOf(biz.name) }
    var tagline by remember { mutableStateOf(biz.tagline ?: "") }
    var owner by remember { mutableStateOf(biz.ownerName) }
    var email by remember { mutableStateOf(biz.email) }
    var phone by remember { mutableStateOf(biz.phone) }
    var gstin by remember { mutableStateOf(biz.gstin) }
    var address by remember { mutableStateOf(biz.address) }
    var upi by remember { mutableStateOf(biz.upiId) }
    var bank by remember { mutableStateOf(biz.bankName) }

    Column(Modifier.fillMaxSize().background(c.background)) {
        StackTopBar("Business profile", onBack = { state.pop() })
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Logo placeholder
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(c.primaryMuted), contentAlignment = Alignment.Center) {
                    BillantaIcon(AppIcon.Camera, c.primary, size = 26.dp)
                }
                Column {
                    Text("Business logo", style = BillantaTheme.type.bodyStrong, color = c.textPrimary)
                    Text("Shown on every invoice (placeholder)", style = BillantaTheme.type.caption, color = c.textSecondary)
                }
            }
            Overline("Details")
            BillantaTextField(name, { name = it }, label = "Business name", modifier = Modifier.fillMaxWidth())
            BillantaTextField(tagline, { tagline = it }, label = "Tagline", modifier = Modifier.fillMaxWidth())
            BillantaTextField(owner, { owner = it }, label = "Owner name", modifier = Modifier.fillMaxWidth())
            BillantaTextField(email, { email = it }, label = "Email", modifier = Modifier.fillMaxWidth())
            BillantaTextField(phone, { phone = it }, label = "Phone", modifier = Modifier.fillMaxWidth())
            BillantaTextField(gstin, { gstin = it }, label = "GSTIN", modifier = Modifier.fillMaxWidth())
            BillantaTextField(address, { address = it }, label = "Address", singleLine = false, modifier = Modifier.fillMaxWidth())
            Overline("Payment")
            BillantaTextField(upi, { upi = it }, label = "UPI ID", modifier = Modifier.fillMaxWidth())
            BillantaTextField(bank, { bank = it }, label = "Bank", modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
        }
        BottomActionBar {
            PrimaryButton("Save profile", onClick = {
                state.business = BusinessProfile(
                    name = name.trim(), tagline = tagline.trim().ifBlank { null }, ownerName = owner.trim(),
                    email = email.trim(), phone = phone.trim(), gstin = gstin.trim(), address = address.trim(),
                    stateCode = biz.stateCode, upiId = upi.trim(), bankName = bank.trim(), accountLast4 = biz.accountLast4,
                )
                state.pop()
            }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun SettingsScreen(state: BillantaState) {
    val c = BillantaTheme.colors
    var reminders by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().background(c.background)) {
        StackTopBar("Settings", onBack = { state.pop() })
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                SettingsGroup("Appearance") {
                    SwitchRow("Dark mode", AppIcon.Moon, state.isDark) { state.isDark = it }
                }
            }
            item {
                SettingsGroup("Invoicing") {
                    ValueRow("Default GST", "18%")
                    RowDivider()
                    ValueRow("Number prefix", "INV-2026-")
                    RowDivider()
                    ValueRow("Currency", "INR (₹)")
                }
            }
            item {
                SettingsGroup("Backup & sync") {
                    SwitchRow("Work offline", AppIcon.CloudOff, state.isOffline) { state.isOffline = it }
                    RowDivider()
                    if (state.signedIn) {
                        ValueRow("Account", state.business.email)
                    } else {
                        ListRow("Sign in to back up", leading = { IconTile(AppIcon.Lock) }, onClick = { state.push(SignInRoute) }, modifier = Modifier.padding(horizontal = 8.dp))
                    }
                }
            }
            item {
                SettingsGroup("Notifications") {
                    SwitchRow("Payment reminders", AppIcon.Bell, reminders) { reminders = it }
                }
            }
            item {
                SettingsGroup("About") {
                    ValueRow("Version", "1.0.0")
                    RowDivider()
                    ListRow("Terms of service", leading = { IconTile(AppIcon.Info) }, modifier = Modifier.padding(horizontal = 8.dp))
                    RowDivider()
                    ListRow("Privacy policy", leading = { IconTile(AppIcon.Info) }, modifier = Modifier.padding(horizontal = 8.dp))
                }
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
