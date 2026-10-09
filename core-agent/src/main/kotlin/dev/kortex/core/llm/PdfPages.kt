package dev.kortex.core.llm

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import dev.kortex.core.state.Attachment
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

/** Longest side, in pixels, of a rendered page — bounds each bitmap to at most 1600×1600×4 ≈ 10 MB. */
private const val MAX_PAGE_SIDE_PX = 1600

/**
 * Renders a PDF's pages to JPEG image attachments — used both by [dev.kortex.core.tool.builtin.readFileTool]
 * and by providers whose API has no PDF content type (e.g. Ollama's OpenAI-compat endpoint only
 * understands "text" and "image_url" parts, not OpenAI's "file" extension).
 *
 * The file descriptor, renderer and each page are closed on every path, and only one page
 * bitmap is alive at a time (recycled once it's compressed).
 */
fun renderPdfPagesAsImages(pdfFile: File, filenameBase: String, maxPages: Int = 10): List<Attachment> =
    ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
        PdfRenderer(pfd).use { renderer ->
            List(renderer.pageCount.coerceAtMost(maxPages)) { i ->
                val stream = ByteArrayOutputStream()
                renderer.openPage(i).use { page ->
                    // Render at 2x for readability, capped so large-format pages stay bounded.
                    val scale = minOf(2f, MAX_PAGE_SIDE_PX.toFloat() / maxOf(page.width, page.height))
                    val bmp = Bitmap.createBitmap(
                        (page.width * scale).toInt().coerceAtLeast(1),
                        (page.height * scale).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                    try {
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                    } finally {
                        bmp.recycle()
                    }
                }
                Attachment(
                    mimeType = "image/jpeg",
                    dataBase64 = Base64.getEncoder().encodeToString(stream.toByteArray()),
                    filename = "${filenameBase}_page_$i.jpg",
                )
            }
        }
    }

/** [renderPdfPagesAsImages] for an in-memory base64 PDF attachment, via a temp file. */
fun renderPdfAttachmentAsImages(pdf: Attachment): List<Attachment> {
    val tmp = File.createTempFile("attach_pdf", ".pdf")
    return try {
        tmp.writeBytes(Base64.getDecoder().decode(pdf.dataBase64))
        renderPdfPagesAsImages(tmp, pdf.filename ?: "document")
    } finally {
        tmp.delete()
    }
}

/**
 * Remembers the page images rendered for recent PDF attachments, so a provider without a PDF
 * content type doesn't re-render the whole document on every request of an agent loop (the
 * full history, attachments included, is re-sent each time).
 *
 * Keyed by the attachment's base64 payload: the same [Attachment] instance is re-sent across
 * iterations, so lookups hit the identity fast path of [String.equals]. Bounded LRU, since each
 * entry holds up to 10 JPEG pages.
 */
internal class PdfPageCache(
    private val maxEntries: Int = 4,
    private val render: (Attachment) -> List<Attachment> = ::renderPdfAttachmentAsImages,
) {
    private val entries = object : LinkedHashMap<String, List<Attachment>>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Attachment>>) =
            size > maxEntries
    }

    /** Page images for [pdf], rendered on first use. A failed render isn't cached. */
    @Synchronized
    fun pagesFor(pdf: Attachment): List<Attachment> = entries.getOrPut(pdf.dataBase64) { render(pdf) }
}
