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
import androidx.compose.material3.Text
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
import com.ferbotz.billanta.domain.model.CustomerRecord
import com.ferbotz.billanta.model.formatPaise
import com.ferbotz.billanta.model.initialsOf
import com.ferbotz.billanta.model.stateCodeFromGstin
import com.ferbotz.billanta.state.BillantaState
import com.ferbotz.billanta.state.EditCustomerRoute
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.AppIcon
import com.ferbotz.billanta.ui.BillantaIcon
import com.ferbotz.billanta.ui.components.Avatar
import com.ferbotz.billanta.ui.components.BillantaTextField
import com.ferbotz.billanta.ui.components.BottomActionBar
import com.ferbotz.billanta.ui.components.BottomBarSpace
import com.ferbotz.billanta.ui.components.IconButtonBox
import com.ferbotz.billanta.ui.components.LargeTopBar
import com.ferbotz.billanta.ui.components.ListRow
import com.ferbotz.billanta.ui.components.PrimaryButton
import com.ferbotz.billanta.ui.components.StackTopBar
import com.ferbotz.billanta.ui.components.SurfaceCard
import com.ferbotz.billanta.ui.components.TextButtonLink

@Composable
fun EditCustomerScreen(state: BillantaState, customerId: String?, attachToInvoiceId: String? = null) {
    val c = BillantaTheme.colors
    val existing = state.customerById(customerId)
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var phone by remember { mutableStateOf(existing?.phone ?: "") }
    var email by remember { mutableStateOf(existing?.email ?: "") }
    var gstin by remember { mutableStateOf(existing?.gstin ?: "") }
    var addressLine1 by remember { mutableStateOf(existing?.addressLine1 ?: "") }
    var addressLine2 by remember { mutableStateOf(existing?.addressLine2 ?: "") }
    var city by remember { mutableStateOf(existing?.city ?: "") }
    var stateName by remember { mutableStateOf(existing?.state ?: "") }
    var stateCode by remember { mutableStateOf(existing?.stateCode ?: "") }
    var pincode by remember { mutableStateOf(existing?.pincode ?: "") }

    Column(Modifier.fillMaxSize().background(c.background)) {
        StackTopBar(if (existing == null) "New customer" else "Edit customer", onBack = { state.pop() }, actions = {
            if (existing != null) {
                TextButtonLink("Delete", color = c.danger, onClick = {
                    state.deleteCustomer(existing.id); state.pop()
                })
            }
        })
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (existing != null) {
                val (count, billed) = state.invoiceCountFor(existing.id) to state.billedTotalFor(existing.id)
                if (count > 0) {
                    Text(
                        "$count invoices · ${billed.formatPaise(withPaise = false)} billed",
                        style = BillantaTheme.type.caption, color = c.textSecondary,
                    )
                }
            }
            BillantaTextField(name, { name = it }, label = "Name", placeholder = "Full name", modifier = Modifier.fillMaxWidth())
            BillantaTextField(phone, { phone = it }, label = "Phone", placeholder = "+91 …", keyboardType = KeyboardType.Phone, modifier = Modifier.fillMaxWidth())
            BillantaTextField(email, { email = it }, label = "Email", placeholder = "name@company.in", keyboardType = KeyboardType.Email, modifier = Modifier.fillMaxWidth())
            BillantaTextField(
                gstin,
                {
                    gstin = it
                    // The first two GSTIN digits ARE the state code — prefill while it's untouched.
                    stateCodeFromGstin(it)?.let { derived -> if (stateCode.isBlank()) stateCode = derived }
                },
                label = "GSTIN (optional)", placeholder = "27ABCDE1234F1Z5", modifier = Modifier.fillMaxWidth(),
            )
            BillantaTextField(addressLine1, { addressLine1 = it }, label = "Address line 1", placeholder = "Street, area", modifier = Modifier.fillMaxWidth())
            BillantaTextField(addressLine2, { addressLine2 = it }, label = "Address line 2 (optional)", placeholder = "Landmark, floor", modifier = Modifier.fillMaxWidth())
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
            Text(
                "The 2-digit state code drives the CGST+SGST vs IGST split on invoices.",
                style = BillantaTheme.type.caption, color = c.textMuted,
            )
            Spacer(Modifier.height(4.dp))
        }
        BottomActionBar {
            PrimaryButton(
                if (existing == null) "Add customer" else "Save changes",
                onClick = {
                    if (name.isNotBlank()) {
                        val record = CustomerRecord(
                            id = existing?.id ?: "",
                            name = name.trim(),
                            phone = phone.trim().ifBlank { null },
                            email = email.trim().ifBlank { null },
                            gstin = gstin.trim().ifBlank { null },
                            addressLine1 = addressLine1.trim().ifBlank { null },
                            addressLine2 = addressLine2.trim().ifBlank { null },
                            city = city.trim().ifBlank { null },
                            state = stateName.trim().ifBlank { null },
                            stateCode = stateCode.trim().ifBlank { null },
                            pincode = pincode.trim().ifBlank { null },
                            country = existing?.country,
                            createdAtMillis = existing?.createdAtMillis,
                        )
                        state.upsertCustomer(record) { saved ->
                            // Opened from an invoice's "Bill to" screen: saving a new customer
                            // there means "use this one", so put it on the invoice and step back
                            // past the chooser rather than making the user pick it again.
                            if (attachToInvoiceId != null && existing == null) {
                                state.setInvoiceCustomer(attachToInvoiceId, saved.id) { state.pop() }
                            }
                        }
                        state.pop()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
