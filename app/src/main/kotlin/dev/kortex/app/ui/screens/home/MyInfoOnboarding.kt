package dev.kortex.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kortex.app.ui.Edge
import dev.kortex.app.ui.Ink
import dev.kortex.app.ui.Mono
import dev.kortex.app.ui.Muted
import dev.kortex.app.ui.Panel
import dev.kortex.app.ui.Synapse
import dev.kortex.app.ui.Void
import dev.kortex.app.ui.components.dashedBorder

/**
 * First run under My Info (Figma: Tabs / 1 First run). Explains what the category is for and
 * hands the user into Links, which is also how they first see a category unfold.
 */
@Composable
fun MyInfoOnboarding(onOpenLinks: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 28.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "Your info, kept by you",
            style = MaterialTheme.typography.headlineSmall,
            color = Ink,
        )
        Text(
            "My Info holds what Kortex keeps on your behalf — not what the agent does. " +
                "Links is the first of them. More will appear here as they arrive.",
            style = MaterialTheme.typography.bodyLarge,
            color = Muted,
        )
        InfoRow(
            tag = "LINKS",
            description = "Saved pages, copied to any device.",
            placeholder = false,
        )
        InfoRow(
            tag = "SOON",
            description = "Notes, files, and whatever you keep next.",
            placeholder = true,
        )
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onOpenLinks,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Synapse, contentColor = Void),
        ) {
            Text(
                "Open Links",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun InfoRow(tag: String, description: String, placeholder: Boolean) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (placeholder) {
                    Modifier.dashedBorder(Edge, cornerRadius = 14.dp)
                } else {
                    Modifier.background(Panel).border(1.dp, Edge, shape)
                }
            )
            .padding(horizontal = 15.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            tag,
            style = TextStyle(fontFamily = Mono, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 1.2.sp),
            color = if (placeholder) Muted else Synapse,
        )
        Text(
            description,
            style = MaterialTheme.typography.bodyLarge,
            color = if (placeholder) Muted else Ink,
        )
    }
}
