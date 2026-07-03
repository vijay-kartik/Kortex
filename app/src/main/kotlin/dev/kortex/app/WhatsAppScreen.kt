package dev.kortex.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

@Composable
fun WhatsAppScreen(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val manager = remember { (context.applicationContext as KortexApp).container.whatsApp }
    val state by manager.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text("WhatsApp", style = MaterialTheme.typography.headlineSmall)
        Text(state.status, style = MaterialTheme.typography.bodyMedium)

        // The server sends several refs at once; each is only valid for ~20s, so rotate through
        // them like whatsmeow does. Showing just the first one means a slightly slow scan fails
        // with no visible reason.
        val codes = state.qrCodes
        var index by remember(codes) { mutableIntStateOf(0) }
        LaunchedEffect(codes) {
            while (index < codes.size - 1) {
                delay(20_000)
                index++
            }
        }
        val qr = codes.getOrNull(index)
        if (qr != null) {
            val bitmap = remember(qr) { qrBitmap(qr, 640) }
            bitmap?.let {
                Image(it.asImageBitmap(), contentDescription = "WhatsApp pairing QR", modifier = Modifier.size(280.dp))
            }
            Text(
                "Open WhatsApp → Settings → Linked devices → Link a device, then scan.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else if (!state.connected) {
            Button(onClick = { manager.connect() }) { Text("Connect WhatsApp") }
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
