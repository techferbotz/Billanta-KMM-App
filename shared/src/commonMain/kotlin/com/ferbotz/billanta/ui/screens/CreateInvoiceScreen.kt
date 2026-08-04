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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ferbotz.billanta.model.LineItem
import com.ferbotz.billanta.model.Paise
import com.ferbotz.billanta.model.format
import com.ferbotz.billanta.state.AddItemSheet
import com.ferbotz.billanta.state.BillantaState
import com.ferbotz.billanta.state.CustomerPickerSheet
import com.ferbotz.billanta.state.PreviewRoute
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.AppIcon
import com.ferbotz.billanta.ui.BillantaIcon
import com.ferbotz.billanta.ui.components.Avatar
import com.ferbotz.billanta.ui.components.BillantaTextField
import com.ferbotz.billanta.ui.components.BottomActionBar
import com.ferbotz.billanta.ui.components.Overline
import com.ferbotz.billanta.ui.components.PickerField
import com.ferbotz.billanta.ui.components.PrimaryButton
import com.ferbotz.billanta.ui.components.StackTopBar
import com.ferbotz.billanta.ui.components.SurfaceCard

@Composable
fun CreateInvoiceScreen(state: BillantaState) {
    val c = BillantaTheme.colors
    val draft = state.draft
    Column(Modifier.fillMaxSize().background(c.background)) {
        StackTopBar("New invoice", onBack = { state.pop() }, actions = {
            Text(draft.number, style = BillantaTheme.type.label, color = c.textMuted, modifier = Modifier.padding(end = 12.dp))
        })
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Customer
            Column {
                Overline("Bill to")
                Spacer(Modifier.height(8.dp))
                PickerField(
                    label = "Customer",
                    value = "${draft.customer.name}${draft.customer.company?.let { " · $it" } ?: ""}",
                    placeholder = "Select a customer",
                    onClick = { state.openSheet(CustomerPickerSheet) },
                    leadingSlot = { Avatar(draft.customer.initials, size = 36) },
                )
            }

            // Dates
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetaField("Issue date", draft.issueDate, Modifier.weight(1f))
                MetaField("Due date", draft.dueDate, Modifier.weight(1f))
            }

            // Items
            Column {
                Overline("Items")
                Spacer(Modifier.height(8.dp))
                SurfaceCard(Modifier.fillMaxWidth(), padding = 6) {
                    Column {
                        state.draftItems.forEachIndexed { i, item ->
                            ItemRow(item, onDelete = { state.removeDraftItem(item.id) })
                            if (i < state.draftItems.size - 1) Divider()
                        }
                        Divider()
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

            // Totals
            Column {
                Overline("Totals")
                Spacer(Modifier.height(8.dp))
                SurfaceCard(Modifier.fillMaxWidth(), padding = 16) {
                    Column {
                        TotalRow("Subtotal", draft.subtotal)
                        Spacer(Modifier.height(10.dp))
                        TotalRow("CGST (9%)", draft.cgst)
                        Spacer(Modifier.height(10.dp))
                        TotalRow("SGST (9%)", draft.sgst)
                        Spacer(Modifier.height(12.dp))
                        Divider()
                        Spacer(Modifier.height(12.dp))
                        TotalRow("Total", draft.total, emphasize = true)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Same-state supply · GST 18% split as CGST + SGST",
                            style = BillantaTheme.type.caption, color = c.textMuted,
                        )
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
            PrimaryButton(
                "Save & preview",
                onClick = {
                    val committed = state.commitDraftAsInvoice()
                    state.replaceTop(PreviewRoute(committed.id))
                },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = AppIcon.Share,
            )
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
private fun ItemRow(item: LineItem, onDelete: () -> Unit) {
    val c = BillantaTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.name, style = BillantaTheme.type.bodyStrong, color = c.textPrimary)
            Spacer(Modifier.height(2.dp))
            Text(
                "${item.quantity} × ${item.rate.format()}",
                style = BillantaTheme.type.caption, color = c.textSecondary,
            )
        }
        Text(item.amount.format(), style = BillantaTheme.type.amount, color = c.textPrimary)
        Box(
            Modifier.padding(start = 8.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onDelete).padding(4.dp),
        ) { BillantaIcon(AppIcon.Trash, c.textMuted, size = 18.dp) }
    }
}

@Composable
private fun TotalRow(label: String, value: Paise, emphasize: Boolean = false) {
    val c = BillantaTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = if (emphasize) BillantaTheme.type.bodyStrong else BillantaTheme.type.body,
            color = if (emphasize) c.textPrimary else c.textSecondary,
        )
        Text(
            value.format(),
            style = if (emphasize) BillantaTheme.type.amountLarge.copy(fontWeight = FontWeight.Bold) else BillantaTheme.type.bodyStrong,
            color = c.textPrimary,
        )
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(BillantaTheme.colors.border))
}
