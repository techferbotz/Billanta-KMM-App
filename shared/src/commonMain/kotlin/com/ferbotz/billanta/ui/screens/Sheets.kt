package com.ferbotz.billanta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ferbotz.billanta.core.BigMath
import com.ferbotz.billanta.core.DecimalString
import com.ferbotz.billanta.domain.model.ProductRecord
import com.ferbotz.billanta.model.formatPaise
import com.ferbotz.billanta.model.initialsOf
import com.ferbotz.billanta.model.parseRupeesToPaise
import com.ferbotz.billanta.state.AddItemSheet
import com.ferbotz.billanta.state.BillantaState
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
            is AddItemSheet -> AddItemSheetContent(state, sheet.invoiceId)
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

/** Item entry in the API's shape: description + HSN/SAC, decimal qty, rate in ₹, per-item GST %. */
@Composable
private fun AddItemSheetContent(state: BillantaState, invoiceId: String) {
    val c = BillantaTheme.colors
    var desc by remember { mutableStateOf("") }
    var hsn by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("1") }
    var rate by remember { mutableStateOf("") }
    var taxRate by remember { mutableStateOf(state.settings.defaultTaxPercent) }

    val ratePaise = parseRupeesToPaise(rate)
    val qtyParsed = DecimalString.parseOrNull(qty)
    val taxParsed = DecimalString.parseOrNull(taxRate)
    val lineTotal = if (ratePaise != null && qtyParsed != null) {
        try {
            BigMath.mulDivHalfUp(qtyParsed.unscaled, ratePaise, qtyParsed.scaleDivisor)
        } catch (_: ArithmeticException) {
            null
        }
    } else null
    val valid = desc.isNotBlank() && ratePaise != null && ratePaise > 0 &&
        qtyParsed != null && !qtyParsed.isZero &&
        taxParsed != null && taxParsed.unscaled <= 100L * taxParsed.scaleDivisor

    Column(
        Modifier.fillMaxWidth().padding(bottom = 16.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SheetHeader("Add item")

        // Things already invoiced, most-used first — tapping one fills the whole form.
        val suggestions = remember(state.products, desc) {
            val typed = desc.trim()
            if (typed.isEmpty()) state.products.take(8)
            else state.products.filter { it.name.contains(typed, ignoreCase = true) }.take(8)
        }
        if (suggestions.isNotEmpty()) {
            LazyRow(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(suggestions, key = { it.id }) { product ->
                    SavedProductChip(
                        product = product,
                        onClick = {
                            desc = product.name
                            hsn = product.hsnSac.orEmpty()
                            rate = paiseToRupeeInput(product.unitPricePaise)
                            taxRate = product.taxRatePercent
                        },
                    )
                }
            }
        }

        Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            BillantaTextField(desc, { desc = it }, label = "Description", placeholder = "e.g. Logo design", modifier = Modifier.fillMaxWidth())
            BillantaTextField(hsn, { hsn = it }, label = "HSN/SAC (optional)", placeholder = "9983", modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BillantaTextField(qty, { qty = it }, label = "Qty", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
                BillantaTextField(rate, { rate = it }, label = "Rate (₹)", placeholder = "0", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(2f))
                BillantaTextField(taxRate, { taxRate = it }, label = "GST %", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Line amount (before tax)", style = BillantaTheme.type.body, color = c.textSecondary)
                Text(lineTotal?.formatPaise() ?: "—", style = BillantaTheme.type.amountLarge, color = c.textPrimary)
            }
            PrimaryButton(
                "Add item",
                onClick = {
                    if (valid && ratePaise != null) {
                        state.addInvoiceItem(
                            invoiceId = invoiceId,
                            description = desc.trim(),
                            hsnSac = hsn.trim().ifBlank { null },
                            quantity = qty.trim(),
                            unitPricePaise = ratePaise,
                            taxRatePercent = taxRate.trim(),
                        )
                        state.closeSheet()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** One saved product, shown as a tappable chip above the add-item form. */
@Composable
private fun SavedProductChip(product: ProductRecord, onClick: () -> Unit) {
    val c = BillantaTheme.colors
    Column(
        Modifier.clip(RoundedCornerShape(12.dp))
            .background(c.surfaceAlt)
            .border(1.dp, c.border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            product.name,
            style = BillantaTheme.type.bodyStrong,
            color = c.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${product.unitPricePaise.formatPaise(withPaise = false)} · GST ${product.taxRatePercent}%",
            style = BillantaTheme.type.caption,
            color = c.textSecondary,
            maxLines = 1,
        )
    }
}

/** Paise back into the rupee string the rate field expects, dropping a trailing `.00`. */
private fun paiseToRupeeInput(paise: Long): String {
    val whole = paise / 100
    val fraction = paise % 100
    return if (fraction == 0L) whole.toString() else "$whole.${fraction.toString().padStart(2, '0')}"
}

@Composable
private fun PremiumSheetContent(state: BillantaState, templateId: String) {
    val c = BillantaTheme.colors
    val templateName = state.templateById(templateId)?.name ?: "this template"
    Column(
        Modifier.fillMaxWidth().padding(18.dp).navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(c.primaryMuted), contentAlignment = Alignment.Center) {
            BillantaIcon(AppIcon.Star, c.primary, size = 30.dp)
        }
        Text("$templateName is a premium template", style = BillantaTheme.type.sectionTitle, color = c.textPrimary, textAlign = TextAlign.Center)
        Text(
            if (state.signedIn) {
                "Premium templates unlock when your account has Billanta Premium. Once it's active, they download automatically."
            } else {
                "Premium templates are tied to your account. Sign in first — if your account has Billanta Premium, they unlock automatically."
            },
            style = BillantaTheme.type.body, color = c.textSecondary, textAlign = TextAlign.Center,
        )
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PremiumFeature("All premium templates, including $templateName")
            PremiumFeature("New designs arrive without app updates")
        }
        if (!state.signedIn) {
            PrimaryButton(
                "Sign in",
                onClick = { state.signInWithGoogle { state.closeSheet() } },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = AppIcon.Google,
            )
        }
        SecondaryButton(if (state.signedIn) "Got it" else "Maybe later", onClick = { state.closeSheet() }, modifier = Modifier.fillMaxWidth())
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
