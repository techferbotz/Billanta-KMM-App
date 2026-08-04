package com.ferbotz.billanta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.AppIcon
import com.ferbotz.billanta.ui.BillantaIcon

@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = BillantaTheme.type.label, color = BillantaTheme.colors.textSecondary, modifier = modifier)
}

/** Editable text field styled to match the mock's rounded inputs. */
@Composable
fun BillantaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    label: String? = null,
    leadingIcon: AppIcon? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
) {
    val c = BillantaTheme.colors
    Column(modifier) {
        if (label != null) {
            FieldLabel(label)
            Spacer(Modifier.height(6.dp))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(14.dp))
                .heightIn(min = 52.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (leadingIcon != null) BillantaIcon(leadingIcon, c.textMuted, size = 20.dp)
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(placeholder, style = BillantaTheme.type.body, color = c.textMuted)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = singleLine,
                    textStyle = LocalTextStyle.current.merge(BillantaTheme.type.body).copy(color = c.textPrimary),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(c.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Read-only field that opens a picker when tapped (e.g. Customer, Template). */
@Composable
fun PickerField(
    label: String,
    value: String?,
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingIcon: AppIcon = AppIcon.ChevronRight,
    leadingSlot: (@Composable () -> Unit)? = null,
) {
    val c = BillantaTheme.colors
    Column(modifier) {
        FieldLabel(label)
        Box(Modifier.padding(top = 6.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.surface)
                    .border(1.dp, c.border, RoundedCornerShape(14.dp))
                    .clickable(onClick = onClick)
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (leadingSlot != null) leadingSlot()
                Text(
                    text = value ?: placeholder,
                    style = BillantaTheme.type.body,
                    color = if (value == null) c.textMuted else c.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                BillantaIcon(trailingIcon, c.textMuted, size = 20.dp)
            }
        }
    }
}
