package com.ferbotz.billanta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import com.ferbotz.billanta.model.ProductKind
import com.ferbotz.billanta.ui.components.ProductImage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ferbotz.billanta.model.formatPaise
import com.ferbotz.billanta.state.BillantaState
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.components.BillantaTextField
import com.ferbotz.billanta.ui.components.BottomActionBar
import com.ferbotz.billanta.ui.components.PrimaryButton
import com.ferbotz.billanta.ui.components.StackTopBar
import com.ferbotz.billanta.ui.components.TextButtonLink

/**
 * Add or edit a catalogue product. The same fields a line item carries, so picking one on an
 * invoice fills the row completely.
 */
@Composable
fun EditProductScreen(state: BillantaState, productId: String?) {
    val c = BillantaTheme.colors
    val existing = state.productById(productId)

    var name by remember(productId) { mutableStateOf(existing?.name ?: "") }
    // Part of the draft like every field: uploaded when picked, saved with the product on Save.
    var imageUrl by remember(productId) { mutableStateOf(existing?.imageUrl) }
    var imageThumbnailUrl by remember(productId) { mutableStateOf(existing?.imageThumbnailUrl) }
    var hsnSac by remember(productId) { mutableStateOf(existing?.hsnSac ?: "") }
    var unit by remember(productId) { mutableStateOf(existing?.unit ?: "") }
    var taxRate by remember(productId) { mutableStateOf(existing?.taxRatePercent ?: "18") }
    // Stored in paise, typed in rupees.
    var price by remember(productId) {
        mutableStateOf(
            existing?.unitPricePaise?.takeIf { it > 0 }?.let { paise ->
                if (paise % 100 == 0L) (paise / 100).toString()
                else "${paise / 100}.${(paise % 100).toString().padStart(2, '0')}"
            } ?: "",
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
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PhotoSection(
                imageUrl = imageUrl ?: imageThumbnailUrl,
                kind = ProductKind.of(hsnSac),
                uploading = state.uploadingProductPhoto,
                blocker = state.productPhotoBlocker,
                onPick = {
                    state.pickProductPhoto { url, thumbnail ->
                        imageUrl = url
                        imageThumbnailUrl = thumbnail
                    }
                },
                onRemove = {
                    imageUrl = null
                    imageThumbnailUrl = null
                },
            )
            if (existing != null && existing.usageCount > 0) {
                Text(
                    "Used on ${existing.usageCount} ${if (existing.usageCount == 1L) "invoice" else "invoices"}",
                    style = BillantaTheme.type.caption,
                    color = c.textSecondary,
                )
            }
            BillantaTextField(
                name, { name = it },
                label = "Name", placeholder = "Design work",
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BillantaTextField(
                    price, { price = it },
                    label = "Rate (₹)", placeholder = "0",
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(2f),
                )
                BillantaTextField(
                    taxRate, { taxRate = it.filter { ch -> ch.isDigit() || ch == '.' }.take(5) },
                    label = "GST %", placeholder = "18",
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BillantaTextField(
                    hsnSac, { hsnSac = it },
                    label = "HSN/SAC (optional)", placeholder = "998314",
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f),
                )
                BillantaTextField(
                    unit, { unit = it },
                    label = "Unit (optional)", placeholder = "hr, pc",
                    modifier = Modifier.weight(1f),
                )
            }
            state.productError?.let {
                Text(it, style = BillantaTheme.type.caption, color = c.danger)
            }
        }

        BottomActionBar {
            PrimaryButton(
                if (state.savingProduct) "Saving…" else "Save",
                onClick = {
                    state.saveProduct(
                        productId, name, hsnSac, price, taxRate, unit,
                        imageUrl = imageUrl,
                        imageThumbnailUrl = imageThumbnailUrl,
                    ) { state.pop() }
                },
                // Not mid-upload: saving then would keep the old photo and drop the new one.
                enabled = name.isNotBlank() && !state.savingProduct && !state.uploadingProductPhoto,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The product's photo, at the top of its editor: tap the empty tile to add one; change or remove
 * it once it is there.
 *
 * Adding needs an account and a connection, because the photo is uploaded as soon as it is picked
 * (media has no offline queue). When that is not possible the tile says why instead of offering a
 * button that would fail. Removing never needs either: it only changes what Save will store.
 */
@Composable
private fun PhotoSection(
    imageUrl: String?,
    kind: ProductKind,
    uploading: Boolean,
    blocker: String?,
    onPick: () -> Unit,
    onRemove: () -> Unit,
) {
    val c = BillantaTheme.colors
    val shape = RoundedCornerShape(18.dp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(shape)
                .border(1.dp, c.border, shape)
                .clickable(enabled = imageUrl == null && blocker == null && !uploading, onClick = onPick),
        ) {
            ProductImage(url = imageUrl, kind = kind, modifier = Modifier.fillMaxSize(), iconSize = 44.dp)
            if (imageUrl == null && !uploading) {
                Text(
                    blocker ?: "Tap to add a photo",
                    style = BillantaTheme.type.caption,
                    color = c.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(14.dp),
                )
            }
            if (uploading) {
                Column(
                    Modifier.fillMaxSize().background(c.scrim),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(color = c.onPrimary, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                    Text(
                        "Uploading…",
                        style = BillantaTheme.type.caption,
                        color = c.onPrimary,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }
        if (imageUrl != null && !uploading) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (blocker == null) TextButtonLink("Change photo", onClick = onPick)
                TextButtonLink("Remove photo", onClick = onRemove, color = c.danger)
            }
        }
    }
}
