package dev.kortex.myinfo.topics.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.kortex.design.Muted
import dev.kortex.design.Sunken
import dev.kortex.design.Synapse
import dev.kortex.design.Void

/** Full-width accent pill at the foot of a screen (Figma: Topics 1g). */
@Composable
internal fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Text(
        label,
        style = ButtonStyle,
        color = if (enabled) Void else Muted,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(if (enabled) Synapse else Sunken)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 14.dp),
    )
}
