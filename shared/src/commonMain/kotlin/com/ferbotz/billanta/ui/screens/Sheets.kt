package com.ferbotz.billanta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ferbotz.billanta.model.LineItem
import com.ferbotz.billanta.model.Paise
import com.ferbotz.billanta.model.TemplateTier
import com.ferbotz.billanta.model.format
import com.ferbotz.billanta.model.rupees
import com.ferbotz.billanta.state.AddItemSheet
import com.ferbotz.billanta.state.BillantaState
import com.ferbotz.billanta.state.CustomerPickerSheet
import com.ferbotz.billanta.state.EditCustomerRoute
import com.ferbotz.billanta.state.PremiumSheet
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.AppIcon
import com.ferbotz.billanta.ui.BillantaIcon
import com.ferbotz.billanta.ui.components.Avatar
import com.ferbotz.billanta.ui.components.BillantaTextField
import com.ferbotz.billanta.ui.components.PrimaryButton
import com.ferbotz.billanta.ui.components.SecondaryButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillantaSheetHost(state: BillantaState) {
    val sheet = state.sheet ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { state.closeSheet() },
        sheetState = sheetState,
        containerColor = BillantaTheme.colors.surface,
        scrimColor = BillantaTheme.colors.scrim,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        when (sheet) {
            AddItemSheet -> AddItemSheetContent(state)
            CustomerPickerSheet -> CustomerPickerSheetContent(state)
            is PremiumSheet -> PremiumSheetContent(state, sheet.templateId)
        }
    }
}

@Composable
private fun SheetHeader(title: String) {
    Text(
        title,
        style = BillantaTheme.type.sectionTitle,
        color = BillantaTheme.colors.textPrimary,
        modifier = Modifier.padding(horizontal = 18.dp),
    )
}

@Composable
private fun AddItemSheetContent(state: BillantaState) {
    val c = BillantaTheme.colors
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("1") }
    var rate by remember { mutableStateOf("") }

    val qtyInt = qty.toIntOrNull()?.coerceAtLeast(1) ?: 1
    val rateLong = rate.filter { it.isDigit() }.toLongOrNull() ?: 0L
    val amount = Paise(rupees(rateLong).value * qtyInt)

    Column(
        Modifier.fillMaxWidth().padding(bottom = 16.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SheetHeader("Add item")
        Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            BillantaTextField(name, { name = it }, label = "Item name", placeholder = "e.g. Logo design", modifier = Modifier.fillMaxWidth())
            BillantaTextField(desc, { desc = it }, label = "Description (optional)", placeholder = "Short description", modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BillantaTextField(qty, { qty = it.filter { ch -> ch.isDigit() } }, label = "Qty", keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                BillantaTextField(rate, { rate = it.filter { ch -> ch.isDigit() } }, label = "Rate (₹)", placeholder = "0", keyboardType = KeyboardType.Number, modifier = Modifier.weight(2f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Amount · GST 18%", style = BillantaTheme.type.body, color = c.textSecondary)
                Text(amount.format(), style = BillantaTheme.type.amountLarge, color = c.textPrimary)
            }
            PrimaryButton(
                "Add item",
                onClick = {
                    if (name.isNotBlank() && rateLong > 0) {
                        state.addDraftItem(LineItem(state.nextItemId(), name.trim(), desc.trim().ifBlank { null }, qtyInt, rupees(rateLong)))
                        state.closeSheet()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CustomerPickerSheetContent(state: BillantaState) {
    val c = BillantaTheme.colors
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).navigationBarsPadding()) {
        SheetHeader("Select customer")
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().clickable {
                state.closeSheet(); state.push(EditCustomerRoute(null))
            }.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(999.dp)).background(c.primaryMuted), contentAlignment = Alignment.Center) {
                BillantaIcon(AppIcon.Plus, c.primary, size = 22.dp)
            }
            Text("Add new customer", style = BillantaTheme.type.bodyStrong, color = c.primary)
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            items(state.customers.size) { i ->
                val cust = state.customers[i]
                val selected = cust.id == state.draftCustomerId
                Row(
                    Modifier.fillMaxWidth().clickable { state.setDraftCustomer(cust.id); state.closeSheet() }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Avatar(cust.initials, size = 44)
                    Column(Modifier.weight(1f)) {
                        Text(cust.name, style = BillantaTheme.type.bodyStrong, color = c.textPrimary)
                        if (cust.company != null) Text(cust.company, style = BillantaTheme.type.caption, color = c.textSecondary)
                    }
                    if (selected) BillantaIcon(AppIcon.Check, c.primary, size = 22.dp)
                }
            }
        }
    }
}

@Composable
private fun PremiumSheetContent(state: BillantaState, templateId: String) {
    val c = BillantaTheme.colors
    val template = state.templateById(templateId)
    Column(
        Modifier.fillMaxWidth().padding(18.dp).navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(c.primaryMuted), contentAlignment = Alignment.Center) {
            BillantaIcon(AppIcon.Star, c.primary, size = 30.dp)
        }
        Text("Unlock ${template.name}", style = BillantaTheme.type.sectionTitle, color = c.textPrimary, textAlign = TextAlign.Center)
        Text(
            "Premium templates give your invoices a distinctive, polished look — no Billanta watermark.",
            style = BillantaTheme.type.body, color = c.textSecondary, textAlign = TextAlign.Center,
        )
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PremiumFeature("All premium templates, including ${template.name}")
            PremiumFeature("Remove Billanta branding")
            PremiumFeature("Custom accent colours & fonts")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text("₹499", style = BillantaTheme.type.moneyHero, color = c.textPrimary)
            Text("  one-time", style = BillantaTheme.type.body, color = c.textSecondary)
        }
        PrimaryButton("Go Premium", onClick = { state.closeSheet() }, modifier = Modifier.fillMaxWidth(), leadingIcon = AppIcon.Star)
        SecondaryButton("Maybe later", onClick = { state.closeSheet() }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun PremiumFeature(text: String) {
    val c = BillantaTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(22.dp).clip(RoundedCornerShape(999.dp)).background(c.successBg), contentAlignment = Alignment.Center) {
            BillantaIcon(AppIcon.Check, c.success, size = 15.dp)
        }
        Text(text, style = BillantaTheme.type.body, color = c.textPrimary)
    }
}
