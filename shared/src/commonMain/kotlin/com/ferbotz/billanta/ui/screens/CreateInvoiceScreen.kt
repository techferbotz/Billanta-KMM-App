package com.ferbotz.billanta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.domain.model.InvoiceDocStatus
import com.ferbotz.billanta.domain.money.DiscountType
import com.ferbotz.billanta.model.formatPaise
import com.ferbotz.billanta.model.initialsOf
import com.ferbotz.billanta.state.AddItemSheet
import com.ferbotz.billanta.state.BillantaState
import com.ferbotz.billanta.state.CustomerPickerSheet
import com.ferbotz.billanta.state.PreviewRoute
import com.ferbotz.billanta.state.todayUtcMidnightMillis
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.AppIcon
import com.ferbotz.billanta.ui.BillantaIcon
import com.ferbotz.billanta.ui.components.Avatar
import com.ferbotz.billanta.ui.components.BillantaTextField
import com.ferbotz.billanta.ui.components.BottomActionBar
import com.ferbotz.billanta.ui.components.FilterChip
import com.ferbotz.billanta.ui.components.Overline
import com.ferbotz.billanta.ui.components.PickerField
import com.ferbotz.billanta.ui.components.PrimaryButton
import com.ferbotz.billanta.ui.components.StackTopBar
import com.ferbotz.billanta.ui.components.SurfaceCard

@Composable
fun CreateInvoiceScreen(state: BillantaState) {
    val c = BillantaTheme.colors
    val totals = state.draftTotals
    Column(Modifier.fillMaxSize().background(c.background)) {
        StackTopBar("New invoice", onBack = { state.pop() })
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Customer
            Column {
                Overline("Bill to")
                Spacer(Modifier.height(8.dp))
                val customer = state.draftCustomer
                PickerField(
                    label = "Customer",
                    value = customer?.name,
                    placeholder = "Select a customer",
                    onClick = { state.openSheet(CustomerPickerSheet) },
                    leadingSlot = { Avatar(initialsOf(customer?.name ?: "?"), size = 36) },
                )
            }

            // Number + dates
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BillantaTextField(
                        state.draftNumber, { state.draftNumber = it },
                        label = "Invoice number", modifier = Modifier.weight(1f),
                    )
                    MetaField(
                        "Issue date",
                        Iso8601.formatDisplayDate(todayUtcMidnightMillis()),
                        Modifier.weight(1f),
                    )
                }
                Column {
                    Text("Due in", style = BillantaTheme.type.label, color = c.textSecondary)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(7, 14, 30).forEach { days ->
                            FilterChip(
                                label = "$days days",
                                selected = state.draftDueDays == days,
                                onClick = { state.draftDueDays = days },
                            )
                        }
                        Text(
                            Iso8601.formatDisplayDate(todayUtcMidnightMillis() + state.draftDueDays * 86_400_000L),
                            style = BillantaTheme.type.caption, color = c.textMuted,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                }
            }

            // Items
            Column {
                Overline("Items")
                Spacer(Modifier.height(8.dp))
                SurfaceCard(Modifier.fillMaxWidth(), padding = 6) {
                    Column {
                        state.draftItems.forEachIndexed { i, item ->
                            ItemRow(
                                item = item,
                                lineTotal = totals?.lines?.getOrNull(i)?.lineTotal,
                                onDelete = { state.removeDraftItem(item.uiId) },
                            )
                            if (i < state.draftItems.size - 1) Divider()
                        }
                        if (state.draftItems.isNotEmpty()) Divider()
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable { state.openSheet(AddItemSheet) }
                                .padding(horizontal = 10.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            BillantaIcon(AppIcon.Plus, c.primary, size = 20.dp)
                            Text("Add item", style = BillantaTheme.type.bodyStrong, color = c.primary)
                        }
                    }
                }
            }

            // Discount
            Column {
                Overline("Discount")
                Spacer(Modifier.height(8.dp))
                SurfaceCard(Modifier.fillMaxWidth(), padding = 14) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip("None", state.draftDiscountType == null, onClick = {
                                state.draftDiscountType = null; state.draftDiscountValue = ""
                            })
                            FilterChip("Percent", state.draftDiscountType == DiscountType.Percentage, onClick = {
                                state.draftDiscountType = DiscountType.Percentage
                            })
                            FilterChip("Flat ₹", state.draftDiscountType == DiscountType.Flat, onClick = {
                                state.draftDiscountType = DiscountType.Flat
                            })
                        }
                        if (state.draftDiscountType != null) {
                            BillantaTextField(
                                state.draftDiscountValue,
                                { state.draftDiscountValue = it },
                                label = if (state.draftDiscountType == DiscountType.Percentage) "Percent (e.g. 10)" else "Amount (₹)",
                                keyboardType = KeyboardType.Decimal,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Apply before tax", style = BillantaTheme.type.bodyStrong, color = c.textPrimary)
                                    Text(
                                        "GST-correct: tax is charged on the discounted value.",
                                        style = BillantaTheme.type.caption, color = c.textMuted,
                                    )
                                }
                                Switch(
                                    checked = state.draftDiscountBeforeTax,
                                    onCheckedChange = { state.draftDiscountBeforeTax = it },
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
                    }
                }
            }

            // Totals — live, computed with the exact server algorithm
            Column {
                Overline("Totals")
                Spacer(Modifier.height(8.dp))
                SurfaceCard(Modifier.fillMaxWidth(), padding = 16) {
                    Column {
                        if (totals == null) {
                            Text(
                                if (state.draftItems.isEmpty()) "Add items to see totals." else "Check the item amounts — something doesn't parse.",
                                style = BillantaTheme.type.body, color = c.textMuted,
                            )
                        } else {
                            TotalRow("Subtotal", totals.subtotal)
                            if (totals.discountTotal > 0) {
                                Spacer(Modifier.height(10.dp))
                                TotalRow("Discount", -totals.discountTotal)
                            }
                            val split = state.draftGstSplit
                            if (state.draftGstKnown && split != null) {
                                if (split.intraState) {
                                    Spacer(Modifier.height(10.dp))
                                    TotalRow("CGST", split.cgst)
                                    Spacer(Modifier.height(10.dp))
                                    TotalRow("SGST", split.sgst)
                                } else {
                                    Spacer(Modifier.height(10.dp))
                                    TotalRow("IGST", split.igst)
                                }
                            } else {
                                Spacer(Modifier.height(10.dp))
                                TotalRow("Tax (GST)", totals.taxTotal)
                            }
                            Spacer(Modifier.height(12.dp))
                            Divider()
                            Spacer(Modifier.height(12.dp))
                            TotalRow("Total", totals.grandTotal, emphasize = true)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                when {
                                    !state.draftGstKnown -> "Add your business + customer state codes for the CGST/SGST split."
                                    split?.intraState == true -> "Same-state supply · GST split as CGST + SGST"
                                    else -> "Inter-state supply · GST charged as IGST"
                                },
                                style = BillantaTheme.type.caption, color = c.textMuted,
                            )
                        }
                    }
                }
            }

            // Notes
            Column {
                Overline("Notes")
                Spacer(Modifier.height(8.dp))
                BillantaTextField(
                    value = state.draftNotes,
                    onValueChange = { state.draftNotes = it },
                    placeholder = "Payment terms, thank-you note…",
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(4.dp))
        }
        BottomActionBar {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.draftError?.let {
                    Text(it, style = BillantaTheme.type.caption, color = c.danger)
                }
                PrimaryButton(
                    if (state.savingDraft) "Saving…" else "Save & preview",
                    onClick = {
                        state.saveDraft(InvoiceDocStatus.Pending) { saved ->
                            state.replaceTop(PreviewRoute(saved.id))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = AppIcon.Share,
                )
            }
        }
    }
}

@Composable
private fun MetaField(label: String, value: String, modifier: Modifier = Modifier) {
    val c = BillantaTheme.colors
    Column(modifier) {
        Text(label, style = BillantaTheme.type.label, color = c.textSecondary)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 15.dp),
        ) { Text(value, style = BillantaTheme.type.body, color = c.textPrimary) }
    }
}

@Composable
private fun ItemRow(item: BillantaState.DraftLine, lineTotal: Long?, onDelete: () -> Unit) {
    val c = BillantaTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.description, style = BillantaTheme.type.bodyStrong, color = c.textPrimary)
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append("${item.quantity} × ${item.unitPricePaise.formatPaise()}")
                    append(" · GST ${item.taxRatePercent}%")
                    item.hsnSac?.let { append(" · HSN $it") }
                },
                style = BillantaTheme.type.caption, color = c.textSecondary,
            )
        }
        Text(lineTotal?.formatPaise() ?: "—", style = BillantaTheme.type.amount, color = c.textPrimary)
        Box(
            Modifier.padding(start = 8.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onDelete).padding(4.dp),
        ) { BillantaIcon(AppIcon.Trash, c.textMuted, size = 18.dp) }
    }
}

@Composable
private fun TotalRow(label: String, valuePaise: Long, emphasize: Boolean = false) {
    val c = BillantaTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = if (emphasize) BillantaTheme.type.bodyStrong else BillantaTheme.type.body,
            color = if (emphasize) c.textPrimary else c.textSecondary,
        )
        Text(
            valuePaise.formatPaise(),
            style = if (emphasize) BillantaTheme.type.amountLarge.copy(fontWeight = FontWeight.Bold) else BillantaTheme.type.bodyStrong,
            color = c.textPrimary,
        )
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(BillantaTheme.colors.border))
}
