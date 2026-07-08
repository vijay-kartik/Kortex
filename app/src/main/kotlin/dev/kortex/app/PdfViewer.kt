package dev.kortex.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.kortex.app.ui.Muted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun PdfViewer(base64: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var pfd by remember { mutableStateOf<ParcelFileDescriptor?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(base64) {
        withContext(Dispatchers.IO) {
            try {
                val file = File.createTempFile("temp_pdf", ".pdf", context.cacheDir)
                file.writeBytes(android.util.Base64.decode(base64, android.util.Base64.DEFAULT))
                val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                pfd = descriptor
                val pdfRenderer = PdfRenderer(descriptor)
                renderer = pdfRenderer
                pageCount = pdfRenderer.pageCount
            } catch (e: Exception) {
                e.printStackTrace()
                error = "Failed to load PDF: ${e.message}"
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                renderer?.close()
                pfd?.close()
            } catch (e: Exception) {
                // Ignore closing errors
            }
        }
    }

    if (error != null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(error!!, color = dev.kortex.app.ui.Alarm)
        }
    } else if (pageCount > 0 && renderer != null) {
        LazyColumn(modifier = modifier) {
            items(pageCount) { index ->
                PdfPageImage(renderer = renderer!!, pageIndex = index)
            }
        }
    } else {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("Loading PDF...", color = Muted)
        }
    }
}

@Composable
fun PdfPageImage(renderer: PdfRenderer, pageIndex: Int) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(renderer, pageIndex) {
        withContext(Dispatchers.IO) {
            synchronized(renderer) {
                try {
                    val page = renderer.openPage(pageIndex)
                    // Render at 2x resolution for better readability
                    val w = (page.width * 2f).toInt()
                    val h = (page.height * 2f).toInt()
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    canvas.drawColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    bitmap = bmp
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "Page ${pageIndex + 1}",
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .background(androidx.compose.ui.graphics.Color.White),
            contentScale = ContentScale.FillWidth
        )
    } else {
        Box(
            Modifier
                .fillMaxWidth()
                .height(400.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("Loading page ${pageIndex + 1}...", color = Muted)
        }
    }
}
