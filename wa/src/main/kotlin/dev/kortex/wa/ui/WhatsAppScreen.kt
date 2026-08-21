package dev.kortex.wa.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dev.kortex.wa.session.WhatsAppManager
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The WhatsApp account space: QR pairing (also usable as a first-run onboarding gate via
 * [onSkip]), and — once linked — account status plus a running list of what has been observed on
 * the connection, with log out.
 *
 * A host app supplies its own [manager] instance (there is exactly one connection per process);
 * this composable has no dependency on any particular app's DI container or pipeline. A
 * [WhatsAppManager.Received.annotation], if the host app attaches one via
 * [WhatsAppManager.annotate], is rendered under the message it belongs to.
 */
@Composable
fun WhatsAppScreen(manager: WhatsAppManager, modifier: Modifier = Modifier, onSkip: (() -> Unit)? = null) {
    val state by manager.state.collectAsStateWithLifecycle()
    var confirmLogout by remember { mutableStateOf(false) }

    // The server sends several refs at once; each is only valid for ~20s, so rotate through them
    // like whatsmeow does. Showing just the first means a slightly slow scan fails with no visible
    // reason.
    val codes = state.qrCodes
    var index by remember(codes) { mutableIntStateOf(0) }
    LaunchedEffect(codes) {
        while (index < codes.size - 1) {
            delay(20_000)
            index++
        }
    }
    val qr = codes.getOrNull(index)
    val linked = (state.connected || state.alreadyLinked || state.paired) && qr == null

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Log out of WhatsApp?") },
            text = {
                Text(
                    "This unlinks the device and erases its WhatsApp keys and message sessions. " +
                        "You'll need to scan a new QR code to link again."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; manager.logout() }) { Text("Log out") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
    }

    if (linked) {
        LinkedAccount(
            modifier = modifier,
            status = state.status,
            deviceJid = state.deviceJid,
            recent = state.recent,
            onLogout = { confirmLogout = true },
        )
    } else {
        Pairing(modifier = modifier, status = state.status, qr = qr, paired = state.paired, onSkip = onSkip, onConnect = manager::connect)
    }
}

/** Linked: account header plus the running list of what has actually come in. */
@Composable
private fun LinkedAccount(
    modifier: Modifier,
    status: String,
    deviceJid: String?,
    recent: List<WhatsAppManager.Received>,
    onLogout: () -> Unit,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("WhatsApp", style = MaterialTheme.typography.titleMedium)
                Text(status, style = MaterialTheme.typography.bodySmall)
                deviceJid?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            TextButton(onClick = onLogout) { Text("Log out") }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        Text(
            "Messages",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        if (recent.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing yet.\nMessages appear here as they arrive.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // No `key`: one <message> node can produce several parts under the same id
                // (a group message carries its sender-key enc alongside the skmsg), and
                // duplicate keys are a hard error in LazyColumn.
                items(recent) { ReceivedRow(it) }
            }
        }
    }
}

/**
 * Messages we sent are shown for visibility only — offset and tinted like a chat bubble so they
 * read as ours at a glance.
 */
@Composable
private fun ReceivedRow(message: WhatsAppManager.Received) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromMe) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (message.fromMe) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(0.9f),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        when {
                            message.fromMe && message.phone != null -> "You → ${message.phone}"
                            message.fromMe -> "You"
                            else -> message.phone ?: "Unknown sender"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(clockTime(message.timestampMillis), style = MaterialTheme.typography.labelSmall)
                }
                Text(
                    // A message with no text still deserves a row — otherwise a photo that arrived
                    // fine is indistinguishable from one that never came.
                    message.text ?: "(${message.kind})",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                message.annotation?.let { annotation ->
                    Text(
                        annotation.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (annotation.isError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun clockTime(millis: Long): String =
    CLOCK.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

/** Not linked: the QR onboarding flow, centred. */
@Composable
private fun Pairing(
    modifier: Modifier,
    status: String,
    qr: String?,
    paired: Boolean,
    onSkip: (() -> Unit)?,
    onConnect: () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text("WhatsApp", style = MaterialTheme.typography.headlineSmall)
        Text(status, style = MaterialTheme.typography.bodyMedium)

        when {
            qr != null -> {
                val bitmap = remember(qr) { qrBitmap(qr, 640) }
                bitmap?.let {
                    Image(it.asImageBitmap(), contentDescription = "WhatsApp pairing QR", modifier = Modifier.size(280.dp))
                }
                Text(
                    "Open WhatsApp → Settings → Linked devices → Link a device, then scan.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // Scanned: pairing done on the phone, finishing the companion login. Keep this open.
            paired -> {
                CircularProgressIndicator()
                Text("Finishing sign-in… keep this screen open.", style = MaterialTheme.typography.bodySmall)
            }
            else -> Button(onClick = onConnect) { Text("Connect WhatsApp") }
        }

        if (onSkip != null) {
            TextButton(onClick = onSkip) { Text("Skip for now") }
        }
    }
}

private fun qrBitmap(content: String, size: Int): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
        for (x in 0 until size) {
            for (y in 0 until size) {
                setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
    }
}.getOrNull()
