package com.ferbotz.billanta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ferbotz.billanta.core.SystemClock
import com.ferbotz.billanta.core.randomUuid
import com.ferbotz.billanta.core.todayIso
import com.ferbotz.billanta.fields.FieldValues
import com.ferbotz.billanta.fields.StartingValues
import com.ferbotz.billanta.model.formatPaise
import com.ferbotz.billanta.model.stateCodeFromGstin
import com.ferbotz.billanta.records.Customer
import com.ferbotz.billanta.state.BillantaState
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.components.BottomActionBar
import com.ferbotz.billanta.ui.components.PrimaryButton
import com.ferbotz.billanta.ui.components.StackTopBar
import com.ferbotz.billanta.ui.components.TextButtonLink
import com.ferbotz.billanta.ui.fields.RecordForm

/**
 * Add or edit a customer. The form is `GET /forms` → `customer`, so a field the backend adds shows
 * up here without an app release (FIELDS.md §5).
 *
 * Opened from an invoice's customer picker ([attachToInvoiceId]), saving a new customer means "bill
 * this one", so it is put on the invoice and both screens step back.
 */
@Composable
fun EditCustomerScreen(state: BillantaState, customerId: String?, attachToInvoiceId: String? = null) {
    val c = BillantaTheme.colors
    val existing = state.customerById(customerId)
    val id = remember(customerId) { existing?.id ?: randomUuid() }
    var values by remember(customerId) {
        mutableStateOf(
            existing?.values ?: StartingValues.forNewRecord(
                state.forms.customer,
                state.settings?.values ?: FieldValues.EMPTY,
                SystemClock.todayIso(),
            ),
        )
    }

    Column(Modifier.fillMaxSize().background(c.background)) {
        StackTopBar(if (existing == null) "New customer" else "Edit customer", onBack = { state.pop() }, actions = {
            if (existing != null) {
                TextButtonLink("Delete", color = c.danger, onClick = { state.deleteCustomer(existing.id); state.pop() })
            }
        })
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            state.customerStats[id]?.takeIf { it.invoiceCount > 0 }?.let { stats ->
                Text(
                    "${stats.invoiceCount} invoices · ${stats.billedPaise.formatPaise(withPaise = false)} billed",
                    style = BillantaTheme.type.caption,
                    color = c.textSecondary,
                )
            }
            RecordForm(
                sections = state.forms.customer,
                values = values,
                onValuesChange = { values = it.withStateFromGstin(GSTIN, STATE) },
                context = state.fieldContext(valueOf = { values[it] }),
            )
            Text(
                "The state decides whether an invoice charges CGST + SGST or IGST.",
                style = BillantaTheme.type.caption,
                color = c.textMuted,
            )
            Spacer(Modifier.height(4.dp))
        }
        BottomActionBar {
            PrimaryButton(
                if (existing == null) "Add customer" else "Save changes",
                enabled = !values.isEmpty(),
                onClick = {
                    state.saveCustomer(Customer(id = id, values = values)) { saved ->
                        if (attachToInvoiceId != null && existing == null) state.pickCustomer(attachToInvoiceId, saved.id)
                    }
                    state.pop()
                    // Past the picker too: the new customer is the answer to it.
                    if (attachToInvoiceId != null && existing == null) state.pop()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The first two GSTIN digits *are* the state code, so a state still empty is filled from the GSTIN
 * as it is typed. A state the user has chosen is never overwritten.
 */
internal fun FieldValues.withStateFromGstin(gstinId: String, stateId: String): FieldValues {
    if (stateId in this) return this
    val code = string(gstinId)?.let(::stateCodeFromGstin) ?: return this
    return withString(stateId, code)
}

private const val GSTIN = "customer_gstin"
private const val STATE = "customer_state"
