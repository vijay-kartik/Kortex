package dev.kortex.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kortex.app.ui.screens.chat.VoiceState
import dev.kortex.design.Edge
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.Synapse
import dev.kortex.design.Void
import dev.kortex.app.ui.util.formatVoiceDuration

/**
 * The composer's listening face — same silhouette as the text field so dictation reads as
 * the composer changing state, not a new widget. The Synapse dot is scaled by the live mic
 * level (real telemetry, like the reasoning trace), the clock speaks mono, and the partial
 * transcript streams in the conversation face.
 */
@Composable
internal fun ListeningBar(
    state: VoiceState,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Panel,
        border = BorderStroke(1.dp, Edge),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.heightIn(min = 56.dp).padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        ) {
            when (state) {
                is VoiceState.Listening -> {
                    // rms arrives in dB (~ -2..10); normalize to a 1x–1.9x dot scale.
                    val level = ((state.rms + 2f) / 12f).coerceIn(0f, 1f)
                    val scale by animateFloatAsState(1f + level * 0.9f, label = "micLevel")
                    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .size(9.dp)
                                .graphicsLayer { scaleX = scale; scaleY = scale }
                                .background(Synapse, CircleShape)
                        )
                    }
                    Text(
                        formatVoiceDuration(state.elapsedMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = Synapse,
                    )
                    Text(
                        state.partial.ifBlank { "Listening…" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.partial.isBlank()) Muted else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onCancel, modifier = Modifier.size(44.dp)) {
                        Text("✕", fontSize = 18.sp, color = Muted)
                    }
                    FilledIconButton(
                        onClick = onDone,
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Synapse,
                            contentColor = Void,
                        ),
                        modifier = Modifier.size(44.dp),
                    ) {
                        Text("✓", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
                VoiceState.Transcribing -> {
                    PulsingDot(8.dp)
                    Text(
                        "transcribing…",
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onCancel, modifier = Modifier.size(44.dp)) {
                        Text("✕", fontSize = 18.sp, color = Muted)
                    }
                }
                VoiceState.Idle -> {}
            }
        }
    }
}
