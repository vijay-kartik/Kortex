package dev.kortex.finance.data.scan

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.kortex.finance.domain.port.ReceiptImageReader
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** On-device text recognition (ML Kit): nothing here leaves the phone. */
class MlKitReceiptImageReader(private val context: Context) : ReceiptImageReader {

    override suspend fun text(pageUris: List<String>): String = runCatching {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val texts = mutableListOf<String>()
            for (page in pageUris) {
                val image = withContext(Dispatchers.IO) { InputImage.fromFilePath(context, Uri.parse(page)) }
                texts += rows(recognizer.process(image).await())
            }
            texts.joinToString("\n")
        } finally {
            recognizer.close()
        }
    }.getOrDefault("")

    /**
     * Rebuilds the receipt's rows: recognition groups text into blocks, and a receipt's item names
     * and prices often come back as two separate columns. Lines whose middles sit at the same
     * height are joined left to right.
     */
    private fun rows(text: Text): String {
        val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line -> line.boundingBox?.let { it to line.text } }
        if (lines.isEmpty()) return text.text
        val sorted = lines.sortedBy { it.first.centerY() }
        val rows = mutableListOf<MutableList<Pair<Rect, String>>>()
        for (line in sorted) {
            val row = rows.lastOrNull()
            val height = line.first.height().coerceAtLeast(1)
            if (row != null && abs(row.first().first.centerY() - line.first.centerY()) < height / 2) row += line else rows += mutableListOf(line)
        }
        return rows.joinToString("\n") { row -> row.sortedBy { it.first.left }.joinToString("   ") { it.second } }
    }
}
