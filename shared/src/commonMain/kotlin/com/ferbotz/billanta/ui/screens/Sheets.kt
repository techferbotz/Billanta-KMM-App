package com.ferbotz.billanta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ferbotz.billanta.state.BillantaState
import com.ferbotz.billanta.state.PremiumSheet
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.AppIcon
import com.ferbotz.billanta.ui.GoogleMark
import com.ferbotz.billanta.ui.BillantaIcon
import com.ferbotz.billanta.ui.components.LegalLinksRow
import com.ferbotz.billanta.ui.components.PrimaryButton
import com.ferbotz.billanta.ui.components.SecondaryButton
import com.ferbotz.billanta.ui.components.SignInConsentLine

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
            is PremiumSheet -> PremiumSheetContent(state, sheet.templateId)
        }
    }
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
        // Signing in is how premium unlocks, so with sign-in switched off there is no button to
        // offer — and the copy must not tell people to press one.
        val canSignIn = !state.signedIn && state.features.googleLogin
        Text(
            when {
                state.signedIn ->
                    "Premium templates unlock when your account has Billanta Premium. Once it's active, they download automatically."
                canSignIn ->
                    "Premium templates are tied to your account. Sign in first — if your account has Billanta Premium, they unlock automatically."
                else ->
                    "Premium templates are tied to your account, and signing in isn't available right now. Please try again later."
            },
            style = BillantaTheme.type.body, color = c.textSecondary, textAlign = TextAlign.Center,
        )
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PremiumFeature("All premium templates, including $templateName")
            PremiumFeature("New designs arrive without app updates")
        }
        if (canSignIn) {
            PrimaryButton(
                "Continue with Google",
                onClick = { state.signInWithGoogle { state.closeSheet() } },
                modifier = Modifier.fillMaxWidth(),
                leadingSlot = { GoogleMark(size = 20.dp) },
            )
        }
        SecondaryButton(if (state.signedIn) "Got it" else "Maybe later", onClick = { state.closeSheet() }, modifier = Modifier.fillMaxWidth())
        // The upgrade screen links both legal pages (BE-015). Where it also asks for sign-in, the
        // consent line carries them; otherwise they stand on their own.
        if (canSignIn) {
            SignInConsentLine(
                onTerms = { state.openTerms() },
                onPrivacy = { state.openPrivacyPolicy() },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LegalLinksRow(
                onTerms = { state.openTerms() },
                onPrivacy = { state.openPrivacyPolicy() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
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
