package dev.kortex.finance.data.scan

import android.content.Context
import android.net.Uri
import dev.kortex.finance.domain.model.ReceiptPhoto
import dev.kortex.finance.domain.port.ReceiptPhotoStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** `files/receipts/{transactionUid}.jpg`, this phone only. */
class FileReceiptPhotoStore(private val context: Context) : ReceiptPhotoStore {

    override suspend fun keep(pageUri: String, transactionUid: String): ReceiptPhoto? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, "receipts").apply { mkdirs() }
            val file = File(dir, "$transactionUid.jpg")
            context.contentResolver.openInputStream(Uri.parse(pageUri))!!.use { input -> file.outputStream().use { input.copyTo(it) } }
            ReceiptPhoto(file.absolutePath, "image/jpeg")
        }.getOrNull()
    }
}
