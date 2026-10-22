package com.ferbotz.billanta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.ferbotz.billanta.records.Product
import com.ferbotz.billanta.state.BillantaState
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.components.BottomActionBar
import com.ferbotz.billanta.ui.components.PrimaryButton
import com.ferbotz.billanta.ui.components.StackTopBar
import com.ferbotz.billanta.ui.components.TextButtonLink
import com.ferbotz.billanta.ui.fields.RecordForm

/**
 * Add or edit a catalogue product. The form is `GET /forms` → `product`, over the same field ids a
 * line carries, so picking the product on an invoice copies everything into the line (FIELDS.md
 * §3.3). Editing a product here never changes an invoice already made.
 *
 * The photo is part of the draft like every field: uploaded when picked, kept on Save.
 */
@Composable
fun EditProductScreen(state: BillantaState, productId: String?) {
    val c = BillantaTheme.colors
    val existing = state.productById(productId)
    val id = remember(productId) { existing?.id ?: randomUuid() }
    var values by remember(productId) {
        mutableStateOf(
            existing?.values ?: StartingValues.forNewRecord(
                state.forms.product,
                state.settings?.values ?: FieldValues.EMPTY,
                SystemClock.todayIso(),
            ),
        )
    }

    Column(Modifier.fillMaxSize().background(c.background)) {
        StackTopBar(
            if (existing == null) "New product" else "Edit product",
            onBack = { state.pop() },
            actions = {
                if (existing != null) {
                    TextButtonLink("Delete", color = c.danger, onClick = {
                        state.deleteProduct(existing.id)
                        state.pop()
                    })
                }
            },
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (existing != null && existing.usageCount > 0) {
                Text(
                    "Added to invoices ${existing.usageCount} ${if (existing.usageCount == 1L) "time" else "times"}",
                    style = BillantaTheme.type.caption,
                    color = c.textSecondary,
                )
            }
            RecordForm(
                sections = state.forms.product,
                values = values,
                onValuesChange = { values = it },
                context = state.fieldContext(valueOf = { values[it] }),
            )
        }
        BottomActionBar {
            PrimaryButton(
                "Save",
                onClick = {
                    state.saveProduct(Product(id = id, values = values)) {}
                    state.pop()
                },
                // Not mid-upload: saving then would keep the old photo and drop the new one.
                enabled = values.string(Product.DESCRIPTION)?.isNotBlank() == true && !state.uploadingImage,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
