package com.ferbotz.billanta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.ferbotz.billanta.state.BillantaState
import com.ferbotz.billanta.theme.BillantaTheme
import com.ferbotz.billanta.ui.AppIcon
import com.ferbotz.billanta.ui.BillantaIcon
import com.ferbotz.billanta.ui.components.BottomActionBar
import com.ferbotz.billanta.ui.components.PrimaryButton
import com.ferbotz.billanta.ui.components.StackTopBar
import com.ferbotz.billanta.ui.components.SurfaceCard
import com.ferbotz.billanta.ui.components.TextButtonLink

/**
 * Settings → Account → Delete account: the in-app deletion Google Play requires (BE-015 / BE-017).
 *
 * The public deletion page sends people — and Play reviewers, who follow it to the letter — to this
 * screen and tells them to confirm here, so this is the confirmation: what happens, a link to the
 * page's own account of what is deleted and kept, and the one destructive button.
 *
 * It must not promise to remove uploaded images. Deleting the account keeps the logo, signature and
 * QR files, and the page tells people to email support *before* deleting if they want those gone —
 * so this says the same, and puts the address one tap away.
 */
@Composable
fun DeleteAccountScreen(state: BillantaState) {
    val c = BillantaTheme.colors
    // A failure from an earlier visit belongs to that visit.
    LaunchedEffect(Unit) { state.deleteAccountError = null }

    Column(Modifier.fillMaxSize().background(c.background)) {
        StackTopBar("Delete account", onBack = { state.pop() })
        val user = state.currentUser
        if (user == null) {
            // Reached by back-stack after signing out elsewhere: there is no account to delete.
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("You're signed out.", style = BillantaTheme.type.body, color = c.textMuted)
            }
            return@Column
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(c.dangerBg),
                contentAlignment = Alignment.Center,
            ) { BillantaIcon(AppIcon.Trash, c.danger, size = 30.dp) }

            Text("Delete your Billanta account?", style = BillantaTheme.type.sectionTitle, color = c.textPrimary)
            Text(
                "This permanently deletes the account ${user.email} and everything synced to it:",
                style = BillantaTheme.type.body,
                color = c.textSecondary,
            )
            SurfaceCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Bullet("Your business profile and invoice settings")
                    Bullet("Your customers and products")
                    Bullet("Every invoice")
                }
            }
            Text(
                "It's removed from this phone too, and you'll be signed out. This can't be undone.",
                style = BillantaTheme.type.body,
                color = c.textSecondary,
            )

            val link = TextLinkStyles(SpanStyle(color = c.primary, fontWeight = FontWeight.SemiBold))
            Text(
                buildAnnotatedString {
                    append("Uploaded images — your logo, signature and QR code — aren't removed. ")
                    append("If you want those gone too, email ")
                    withLink(LinkAnnotation.Clickable("support", link) { state.contactSupport() }) {
                        append(state.links.supportEmail)
                    }
                    append(" before deleting.")
                },
                style = BillantaTheme.type.body,
                color = c.textSecondary,
            )
            // Pulled left by the link's own inner padding, so its text lines up with the paragraphs.
            TextButtonLink(
                "What's deleted and kept",
                onClick = { state.openAccountDeletionInfo() },
                modifier = Modifier.offset(x = (-6).dp),
            )

            state.deleteAccountError?.let { error ->
                Text(
                    error,
                    style = BillantaTheme.type.caption,
                    color = c.danger,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(c.dangerBg).padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }

        BottomActionBar {
            PrimaryButton(
                if (state.deletingAccount) "Deleting…" else "Delete account",
                onClick = { state.deleteAccount() },
                enabled = !state.deletingAccount,
                leadingIcon = AppIcon.Trash,
                color = c.danger,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Bullet(text: String) {
    val c = BillantaTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(c.danger))
        Text(text, style = BillantaTheme.type.body, color = c.textPrimary)
    }
}
