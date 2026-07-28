package dev.kortex.core.llm

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import dev.kortex.core.state.Attachment
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

/**
 * Renders a PDF's pages to JPEG image attachments — used both by [dev.kortex.core.tool.builtin.readFileTool]
 * and by providers whose API has no PDF content type (e.g. Ollama's OpenAI-compat endpoint only
 * understands "text" and "image_url" parts, not OpenAI's "file" extension).
 */
fun renderPdfPagesAsImages(pdfFile: File, filenameBase: String, maxPages: Int = 10): List<Attachment> {
    val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
    val renderer = PdfRenderer(pfd)
    val attachments = buildList {
        for (i in 0 until renderer.pageCount.coerceAtMost(maxPages)) {
            val page = renderer.openPage(i)
            // Render at 2x resolution for better readability
            val bmp = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            val stream = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, stream)
            add(
                Attachment(
                    mimeType = "image/jpeg",
                    dataBase64 = Base64.getEncoder().encodeToString(stream.toByteArray()),
                    filename = "${filenameBase}_page_$i.jpg",
                )
            )
        }
    }
    renderer.close()
    pfd.close()
    return attachments
}
