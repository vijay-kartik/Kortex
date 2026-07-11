package dev.kortex.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kortex.app.ui.Edge
import dev.kortex.app.ui.KortexTheme
import dev.kortex.app.ui.Muted
import dev.kortex.app.ui.Panel
import dev.kortex.app.ui.Synapse
import dev.kortex.app.ui.SynapseDim
import dev.kortex.app.ui.Void
import dev.kortex.core.state.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Landing point for photos/PDFs shared from other apps (gallery, file manager). Renders
 * as a floating composer over the host app — no full app launch: the user adds an
 * instruction, hits send, and [ShareAgentRunner] takes it from there in the background.
 */
class ShareToKortexActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (intent.action != Intent.ACTION_SEND || uri == null) {
            finish()
            return
        }

        // The completion ping needs POST_NOTIFICATIONS on 13+; ask up front, don't block.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val runner = (application as KortexApp).container.shareAgentRunner

        setContent {
            KortexTheme {
                ShareComposer(
                    loadAttachment = { readAttachment(uri) },
                    onSend = { text, attachment ->
                        runner.submit(text, attachment)
                        Toast.makeText(
                            this,
                            "Kortex is on it — you'll get a notification.",
                            Toast.LENGTH_SHORT,
                        ).show()
                        finish()
                    },
                    onDismiss = { finish() },
                )
            }
        }
    }

    /** Resolves the shared Uri into an in-memory [Attachment] (name, MIME, base64 bytes). */
    private suspend fun readAttachment(uri: Uri): Attachment? = withContext(Dispatchers.IO) {
        runCatching {
            val mimeType = intent.type?.takeIf { it != "*/*" }
                ?: contentResolver.getType(uri)
                ?: "application/octet-stream"
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@runCatching null
            var filename: String? = null
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val i = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) filename = cursor.getString(i)
                }
            }
            Attachment(
                mimeType = mimeType,
                dataBase64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
                filename = filename,
            )
        }.getOrNull()
    }
}

/**
 * The floating intake card. Same composer grammar as the chat screen — rounded field,
 * 52dp circular Synapse send — under a mono "SHARE → KORTEX" eyebrow, so it reads as
 * Kortex's instrument panel surfacing inside another app.
 */
@Composable
private fun ShareComposer(
    loadAttachment: suspend () -> Attachment?,
    onSend: (String, Attachment) -> Unit,
    onDismiss: () -> Unit,
) {
    var attachment by remember { mutableStateOf<Attachment?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val att = loadAttachment()
        if (att == null) loadFailed = true else attachment = att
    }

    // Scrim: tap anywhere outside the card to dismiss.
    Box(
        Modifier
            .fillMaxSize()
            .background(Void.copy(alpha = 0.6f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Panel,
            border = BorderStroke(1.dp, Edge),
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .navigationBarsPadding()
                .imePadding()
                // Swallow clicks so taps inside the card don't hit the scrim.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Image(
                        painterResource(R.drawable.ic_kortex_mark),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Text("SHARE → KORTEX", style = MaterialTheme.typography.labelSmall, color = Synapse)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "✕",
                        fontSize = 14.sp,
                        color = Muted,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(onClick = onDismiss)
                            .padding(6.dp),
                    )
                }

                when {
                    loadFailed -> Text(
                        "Couldn't read the shared file. Go back and try sharing it again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                    attachment == null -> Text(
                        "reading file…",
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                    )
                    else -> AttachmentPreview(attachment!!)
                }

                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = Void,
                        border = BorderStroke(1.dp, Edge),
                        modifier = Modifier.weight(1f),
                    ) {
                        TextField(
                            value = input,
                            onValueChange = { input = it },
                            placeholder = { Text("What should Kortex do with this?", color = Muted) },
                            maxLines = 3,
                            textStyle = MaterialTheme.typography.bodyLarge,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                                cursorColor = Synapse,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    val ready = attachment != null && input.isNotBlank()
                    FilledIconButton(
                        onClick = { attachment?.let { onSend(input.trim(), it) } },
                        enabled = ready,
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Synapse,
                            contentColor = Void,
                            disabledContainerColor = SynapseDim,
                            disabledContentColor = Muted,
                        ),
                        modifier = Modifier.size(52.dp),
                    ) {
                        Text("↑", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** Image shares get a real thumbnail; PDFs a filename chip — proof Kortex got the file. */
@Composable
private fun AttachmentPreview(att: Attachment) {
    if (att.mimeType.startsWith("image/")) {
        val bitmap = remember(att.dataBase64) {
            runCatching {
                val bytes = android.util.Base64.decode(att.dataBase64, android.util.Base64.DEFAULT)
                // Bound decode size: the preview is at most a card-width strip.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val sample = (bounds.outWidth / 1024).coerceAtLeast(1)
                BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            }.getOrNull()
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = att.filename,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            return
        }
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Void,
        border = BorderStroke(1.dp, Edge),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_attach_file),
                contentDescription = null,
                tint = Synapse,
                modifier = Modifier.size(16.dp),
            )
            Column {
                Text(
                    att.filename ?: "Shared file",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
                Text(
                    att.mimeType,
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
        }
    }
}
