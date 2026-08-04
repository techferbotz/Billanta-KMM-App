package com.ferbotz.billanta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ferbotz.billanta.model.Customer
import com.ferbotz.billanta.model.format
import com.ferbotz.billanta.state.BillantaState
import com.ferbotz.billanta.state.EditCustomerRoute
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.AppIcon
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
fun CustomersScreen(state: BillantaState) {
    val c = BillantaTheme.colors
    Column(Modifier.fillMaxSize().background(c.background)) {
        LargeTopBar("Customers", actions = {
            IconButtonBox(AppIcon.Plus, c.primary, onClick = { state.push(EditCustomerRoute(null)) })
        })
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = BottomBarSpace),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                SurfaceCard(Modifier.fillMaxWidth(), padding = 4) {
                    Column {
                        state.customers.forEachIndexed { i, cust ->
                            ListRow(
                                title = cust.name,
                                subtitle = cust.company ?: cust.email ?: cust.phone,
                                leading = { Avatar(cust.initials, size = 44) },
                                trailingText = "${state.invoiceCountFor(cust.id)} inv",
                                onClick = { state.push(EditCustomerRoute(cust.id)) },
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                            if (i < state.customers.size - 1) {
                                Spacer(Modifier.height(1.dp).fillMaxWidth().padding(start = 66.dp).background(c.border))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EditCustomerScreen(state: BillantaState, customerId: String?) {
    val c = BillantaTheme.colors
    val existing = state.customerById(customerId)
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var company by remember { mutableStateOf(existing?.company ?: "") }
    var email by remember { mutableStateOf(existing?.email ?: "") }
    var phone by remember { mutableStateOf(existing?.phone ?: "") }
    var gstin by remember { mutableStateOf(existing?.gstin ?: "") }
    var address by remember { mutableStateOf(existing?.address ?: "") }

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
            BillantaTextField(name, { name = it }, label = "Name", placeholder = "Full name", modifier = Modifier.fillMaxWidth())
            BillantaTextField(company, { company = it }, label = "Company (optional)", placeholder = "Company name", modifier = Modifier.fillMaxWidth())
            BillantaTextField(email, { email = it }, label = "Email", placeholder = "name@company.in", keyboardType = KeyboardType.Email, modifier = Modifier.fillMaxWidth())
            BillantaTextField(phone, { phone = it }, label = "Phone", placeholder = "+91 …", keyboardType = KeyboardType.Phone, modifier = Modifier.fillMaxWidth())
            BillantaTextField(gstin, { gstin = it }, label = "GSTIN (optional)", placeholder = "27ABCDE1234F1Z5", modifier = Modifier.fillMaxWidth())
            BillantaTextField(address, { address = it }, label = "Address", placeholder = "Street, city, PIN", singleLine = false, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
        }
        BottomActionBar {
            PrimaryButton(
                if (existing == null) "Add customer" else "Save changes",
                onClick = {
                    if (name.isNotBlank()) {
                        val id = existing?.id ?: state.nextCustomerId()
                        val cust = Customer(
                            id = id, name = name.trim(),
                            company = company.trim().ifBlank { null },
                            email = email.trim().ifBlank { null },
                            phone = phone.trim().ifBlank { null },
                            gstin = gstin.trim().ifBlank { null },
                            address = address.trim().ifBlank { null },
                        )
                        state.upsertCustomer(cust)
                        if (existing == null) state.setDraftCustomer(id)
                        state.pop()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
