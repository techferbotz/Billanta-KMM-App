package com.ferbotz.billanta.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.ferbotz.billanta.theme.BillantaTheme

/**
 * The line that goes next to every "Sign in with Google" (BE-015 / BE-017), both phrases linked.
 *
 * The wording is the protocol's, word for word, because it is what the legal pages rely on. The
 * links are callbacks rather than URLs so they open in the in-app browser tab, not whatever the
 * platform does with a bare link.
 */
@Composable
fun SignInConsentLine(onTerms: () -> Unit, onPrivacy: () -> Unit, modifier: Modifier = Modifier) {
    val c = BillantaTheme.colors
    val link = TextLinkStyles(SpanStyle(color = c.primary, fontWeight = FontWeight.SemiBold))
    Text(
        buildAnnotatedString {
            append("By continuing you agree to our ")
            withLink(LinkAnnotation.Clickable("terms", link) { onTerms() }) { append("Terms & Conditions") }
            append(" and ")
            withLink(LinkAnnotation.Clickable("privacy", link) { onPrivacy() }) { append("Privacy Policy") }
            append(".")
        },
        style = BillantaTheme.type.caption,
        color = c.textSecondary,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

/** "Terms & Conditions · Privacy Policy" — for a screen that needs the links but asks no consent. */
@Composable
fun LegalLinksRow(onTerms: () -> Unit, onPrivacy: () -> Unit, modifier: Modifier = Modifier) {
    val c = BillantaTheme.colors
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegalLink("Terms & Conditions", onTerms)
        Text("·", style = BillantaTheme.type.caption, color = c.textMuted)
        LegalLink("Privacy Policy", onPrivacy)
    }
}

@Composable
private fun LegalLink(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = BillantaTheme.type.caption.copy(fontWeight = FontWeight.SemiBold),
        color = BillantaTheme.colors.textSecondary,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    )
}
