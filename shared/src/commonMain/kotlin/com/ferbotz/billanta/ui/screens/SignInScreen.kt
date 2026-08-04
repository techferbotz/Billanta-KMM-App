package com.ferbotz.billanta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ferbotz.billanta.state.BillantaState
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.AppIcon
import com.ferbotz.billanta.ui.BillantaIcon
import com.ferbotz.billanta.ui.components.BillantaTextField
import com.ferbotz.billanta.ui.components.PrimaryButton
import com.ferbotz.billanta.ui.components.SecondaryButton
import com.ferbotz.billanta.ui.components.StackTopBar
import com.ferbotz.billanta.ui.components.TextButtonLink

@Composable
fun SignInScreen(state: BillantaState) {
    val c = BillantaTheme.colors
    var email by remember { mutableStateOf("") }

    fun signIn() { state.signedIn = true; state.isOffline = false; state.pop() }

    Column(Modifier.fillMaxSize().background(c.background)) {
        StackTopBar("", onBack = { state.pop() })
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(20.dp))
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(c.primary), contentAlignment = Alignment.Center) {
                Text("B", color = c.onPrimary, fontWeight = FontWeight.Bold, fontSize = 30.sp)
            }
            Spacer(Modifier.height(20.dp))
            Text("Sign in to Billanta", style = BillantaTheme.type.screenTitle, color = c.textPrimary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "Back up and sync across devices. It's optional — your invoices stay on this device until you sign in.",
                style = BillantaTheme.type.body, color = c.textSecondary, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))

            SecondaryButton(
                "Continue with Google",
                onClick = { signIn() },
                leadingIcon = AppIcon.Google,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))
            DividerWithText("or")
            Spacer(Modifier.height(18.dp))
            BillantaTextField(
                email, { email = it },
                label = "Email", placeholder = "you@studio.in",
                keyboardType = KeyboardType.Email, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            PrimaryButton("Continue with email", onClick = { signIn() }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))
            TextButtonLink("Continue offline", color = c.textSecondary, onClick = { state.pop() })
            Spacer(Modifier.height(24.dp))
            Text(
                "By continuing you agree to the Terms & Privacy Policy.",
                style = BillantaTheme.type.caption, color = c.textMuted, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DividerWithText(text: String) {
    val c = BillantaTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f).height(1.dp).background(c.border))
        Text(text, style = BillantaTheme.type.caption, color = c.textMuted)
        Box(Modifier.weight(1f).height(1.dp).background(c.border))
    }
}
