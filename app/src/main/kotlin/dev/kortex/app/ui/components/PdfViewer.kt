package dev.kortex.app.ui.components

import android.graphics.Bitmap
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.kortex.core.llm.pdfPageBitmapSize
import dev.kortex.design.Muted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

private sealed interface PdfLoad {
    data object Loading : PdfLoad
    data class Loaded(val document: PdfDocument) : PdfLoad
    data class Failed(val message: String) : PdfLoad
}

private sealed interface PageLoad {
    data object Loading : PageLoad
    data class Loaded(val bitmap: Bitmap) : PageLoad
    data object Failed : PageLoad
}

@Composable
fun PdfViewer(base64: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Owns the document for as long as this `base64` is shown: closed when it changes or the viewer leaves.
    val load by produceState<PdfLoad>(PdfLoad.Loading, base64) {
        value = PdfLoad.Loading
        var document: PdfDocument? = null
        try {
            // Assigned inside the IO block so a document opened just as we're cancelled is still closed below.
            withContext(Dispatchers.IO) { document = PdfDocument.open(context.cacheDir, base64) }
            value = PdfLoad.Loaded(document!!)
            awaitCancellation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            value = PdfLoad.Failed("Failed to load PDF: ${e.message}")
        } catch (e: OutOfMemoryError) {
            value = PdfLoad.Failed("PDF is too large to preview")
        } finally {
            // Off the main thread: closing waits for a page render in flight.
            document?.let { withContext(NonCancellable + Dispatchers.IO) { it.close() } }
        }
    }

    when (val state = load) {
        is PdfLoad.Failed -> Box(modifier, contentAlignment = Alignment.Center) {
            Text(state.message, color = dev.kortex.design.Alarm)
        }
        is PdfLoad.Loaded -> LazyColumn(modifier = modifier) {
            items(state.document.pageCount) { index ->
                PdfPageImage(document = state.document, pageIndex = index)
            }
        }
        PdfLoad.Loading -> Box(modifier, contentAlignment = Alignment.Center) {
            Text("Loading PDF...", color = Muted)
        }
    }
}

@Composable
private fun PdfPageImage(document: PdfDocument, pageIndex: Int) {
    val load by produceState<PageLoad>(PageLoad.Loading, document, pageIndex) {
        value = withContext(Dispatchers.IO) {
            try {
                document.renderPage(pageIndex)?.let { PageLoad.Loaded(it) } ?: PageLoad.Failed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                PageLoad.Failed
            } catch (e: OutOfMemoryError) {
                PageLoad.Failed
            }
        }
    }

    when (val state = load) {
        is PageLoad.Loaded -> Image(
            bitmap = state.bitmap.asImageBitmap(),
            contentDescription = "Page ${pageIndex + 1}",
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .background(androidx.compose.ui.graphics.Color.White),
            contentScale = ContentScale.FillWidth
        )
        else -> Box(
            Modifier
                .fillMaxWidth()
                .height(400.dp),
            contentAlignment = Alignment.Center
        ) {
            if (state is PageLoad.Failed) {
                Text("Couldn't render page ${pageIndex + 1}", color = dev.kortex.design.Alarm)
            } else {
                Text("Loading page ${pageIndex + 1}...", color = Muted)
            }
        }
    }
}

/**
 * An open PDF: its descriptor and [PdfRenderer]. [PdfRenderer] allows one open page at a time and
 * can't be closed while a page is open, so rendering and [close] share this object's lock.
 */
private class PdfDocument(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : Closeable {
    val pageCount = renderer.pageCount
    private var closed = false

    /** The page as a bitmap (2x, size-capped), or null once the document is closed. */
    fun renderPage(index: Int): Bitmap? = synchronized(this) {
        if (closed) return@synchronized null
        renderer.openPage(index).use { page ->
            val (w, h) = pdfPageBitmapSize(page.width, page.height)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            } catch (e: Throwable) {
                bmp.recycle()
                throw e
            }
            bmp
        }
    }

    override fun close() = synchronized(this) {
        if (closed) return@synchronized
        closed = true
        try {
            renderer.close()
        } finally {
            descriptor.close()
        }
    }

    companion object {
        /**
         * Opens [base64] via a temp file in [cacheDir], deleted as soon as the descriptor is open
         * (the open descriptor keeps the data readable), so no copy of the PDF is left behind.
         */
        fun open(cacheDir: File, base64: String): PdfDocument {
            val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            val file = File.createTempFile("temp_pdf", ".pdf", cacheDir)
            val descriptor = try {
                file.writeBytes(bytes)
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } finally {
                file.delete()
            }
            val renderer = try {
                PdfRenderer(descriptor)
            } catch (e: Throwable) {
                descriptor.close()
                throw e
            }
            return PdfDocument(descriptor, renderer)
        }
    }
}
