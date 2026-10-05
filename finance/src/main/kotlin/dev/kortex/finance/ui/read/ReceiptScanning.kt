package dev.kortex.finance.ui.read

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * The Android side of Scan receipt (Figma: Scan receipt 01–02): Google's document scanner finds the
 * edges, crops and straightens (auto capture on, gallery allowed), and on-device text recognition
 * reads the pages. Nothing here leaves the phone.
 */
object ReceiptScanning {
    /** Up to two pages: a long receipt can be taken in two photos (Scan receipt 06's tip). */
    val options: GmsDocumentScannerOptions = GmsDocumentScannerOptions.Builder()
        .setGalleryImportAllowed(true)
        .setPageLimit(2)
        .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
        .build()

    /** The text on [pages], one receipt row per line, pages one after another. */
    suspend fun recognise(context: Context, pages: List<Uri>): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val texts = mutableListOf<String>()
            for (uri in pages) texts += rows(recognizer.process(InputImage.fromFilePath(context, uri)).await())
            texts.joinToString("\n")
        } finally {
            recognizer.close()
        }
    }

    /**
     * Rebuilds the receipt's rows: recognition groups text into blocks, and a receipt's item names
     * and prices often come back as two separate columns. Lines whose middles sit at the same
     * height are joined left to right.
     */
    private fun rows(text: Text): String {
        val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line -> line.boundingBox?.let { it to line.text } }
        if (lines.isEmpty()) return text.text
        val sorted = lines.sortedBy { it.first.centerY() }
        val rows = mutableListOf<MutableList<Pair<android.graphics.Rect, String>>>()
        for (line in sorted) {
            val row = rows.lastOrNull()
            val height = line.first.height().coerceAtLeast(1)
            if (row != null && kotlin.math.abs(row.first().first.centerY() - line.first.centerY()) < height / 2) row += line else rows += mutableListOf(line)
        }
        return rows.joinToString("\n") { row -> row.sortedBy { it.first.left }.joinToString("   ") { it.second } }
    }

    /** Keeps a scanned page with its expense: `files/receipts/{transactionUid}.jpg`, this phone only. */
    suspend fun keep(context: Context, page: Uri, transactionUid: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, "receipts").apply { mkdirs() }
            val file = File(dir, "$transactionUid.jpg")
            context.contentResolver.openInputStream(page)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
            file.absolutePath
        }.getOrNull()
    }
}
