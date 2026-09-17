package dev.kortex.app.ui.components

import android.media.MediaPlayer
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kortex.app.R
import dev.kortex.app.ui.Void
import dev.kortex.app.util.formatVoiceDuration
import dev.kortex.core.state.Attachment
import java.io.File

/**
 * A voice message inside a user bubble: a mono "VOICE · 0:07" eyebrow over the transcript
 * in the conversation face. Dictated notes carry only a transcript; audio files picked
 * from storage carry real bytes and get a play/pause control instead of the mic glyph.
 */
@Composable
internal fun VoiceNoteContent(
    att: Attachment,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (att.dataBase64.isNotBlank()) {
                AudioPlayButton(att)
            } else {
                Icon(
                    painterResource(R.drawable.ic_mic),
                    contentDescription = null,
                    tint = Void.copy(alpha = 0.7f),
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                buildString {
                    append("VOICE")
                    att.durationMs?.let { append(" · ${formatVoiceDuration(it)}") }
                        ?: att.filename?.let { append(" · $it") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = Void.copy(alpha = 0.7f),
            )
        }
        att.transcript?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** Plays a byte-carrying audio attachment via MediaPlayer (base64 -> cache file). */
@Composable
private fun AudioPlayButton(att: Attachment) {
    val context = LocalContext.current
    var playing by remember { mutableStateOf(false) }
    val player = remember { MediaPlayer() }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }
    Surface(
        color = Void.copy(alpha = 0.2f),
        shape = CircleShape,
        modifier = Modifier
            .size(26.dp)
            .clickable {
                if (playing) {
                    player.stop()
                    player.reset()
                    playing = false
                } else {
                    runCatching {
                        val bytes = Base64.decode(att.dataBase64, Base64.DEFAULT)
                        val ext = att.mimeType.substringAfter("/").ifBlank { "bin" }
                        val file = File(context.cacheDir, "audio_${att.dataBase64.hashCode()}.$ext")
                        if (!file.exists()) file.writeBytes(bytes)
                        player.reset()
                        player.setDataSource(file.absolutePath)
                        player.setOnCompletionListener { playing = false }
                        player.prepare()
                        player.start()
                        playing = true
                    }.onFailure {
                        Toast.makeText(context, "Can't play this audio.", Toast.LENGTH_SHORT).show()
                    }
                }
            },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (playing) "■" else "▶", fontSize = 11.sp, color = Void)
        }
    }
}
