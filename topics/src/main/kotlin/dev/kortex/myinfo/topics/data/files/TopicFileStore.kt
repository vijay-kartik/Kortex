package dev.kortex.myinfo.topics.data.files

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import dev.kortex.myinfo.topics.data.local.TopicDao
import dev.kortex.myinfo.topics.domain.model.PickedFile
import dev.kortex.myinfo.topics.domain.model.StoredFile
import dev.kortex.myinfo.topics.domain.port.FileVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * [FileVault] on app storage: `files/topic-files`, one file per item, named after a fresh id so
 * moving an item between topics doesn't move its file.
 *
 * Files are copied in as soon as the user picks one, before they decide to save, so an abandoned
 * capture sheet can leave one behind. [sweepOrphans] clears those out, but only once they are old
 * enough that no sheet could still be holding one.
 */
class TopicFileStore(
    private val context: Context,
    private val dao: TopicDao,
) : FileVault {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch { sweepOrphans() }
    }

    override suspend fun store(uri: String): PickedFile? = withContext(Dispatchers.IO) {
        val source = runCatching { Uri.parse(uri) }.getOrNull() ?: return@withContext null
        val resolver = context.contentResolver
        val mimeType = resolver.getType(source) ?: guessMimeType(source) ?: DEFAULT_MIME
        val name = displayName(source) ?: DEFAULT_NAME
        val target = File(filesDir(), "${UUID.randomUUID()}${extensionFor(name, mimeType)}")
        try {
            val bytes = resolver.openInputStream(source)?.use { input ->
                target.outputStream().use { output -> input.copyBoundedTo(output) }
            } ?: return@withContext null
            if (bytes <= 0L) {
                target.delete()
                return@withContext null
            }
            val stored = StoredFile(target.path, mimeType)
            PickedFile(
                file = stored,
                name = name,
                isImage = mimeType.startsWith("image/"),
                pageCount = pageCount(stored),
            )
        } catch (e: IOException) {
            Log.w(TAG, "Could not keep $uri", e)
            target.delete()
            null
        }
    }

    override suspend fun delete(paths: Collection<String>) {
        if (paths.isEmpty()) return
        withContext(Dispatchers.IO) {
            val dir = filesDir()
            paths.map(::File)
                // Only files this vault handed out; a path from anywhere else isn't ours to delete.
                .filter { it.parentFile == dir }
                .forEach { it.delete() }
        }
    }

    /** Files no item points at any more, from a capture that was never saved or a delete that died half-way. */
    private suspend fun sweepOrphans() {
        val files = filesDir().listFiles().orEmpty()
        if (files.isEmpty()) return
        val cutoff = System.currentTimeMillis() - ORPHAN_MIN_AGE_MS
        val kept = dao.allFilePaths().toHashSet()
        files.filter { it.lastModified() < cutoff && it.path !in kept }.forEach { it.delete() }
    }

    /** Pages of a PDF, so a doc card can say how long it is; null for anything else or an unreadable file. */
    private fun pageCount(file: StoredFile): Int? {
        if (file.mimeType != PDF_MIME) return null
        return runCatching {
            ParcelFileDescriptor.open(File(file.path), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { it.pageCount }
            }
        }.getOrNull()
    }

    private fun displayName(uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

    private fun guessMimeType(uri: Uri): String? =
        uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() }
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }

    /** Keeps the original suffix so other apps still recognise the file; falls back to the type's own. */
    private fun extensionFor(name: String, mimeType: String): String {
        val fromName = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= MAX_EXTENSION }
        val extension = fromName ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        return extension?.let { ".${it.lowercase()}" }.orEmpty()
    }

    private fun filesDir() = File(context.filesDir, DIRECTORY).apply { mkdirs() }

    private companion object {
        const val TAG = "TopicFileStore"
        const val DIRECTORY = "topic-files"
        const val DEFAULT_MIME = "application/octet-stream"
        const val PDF_MIME = "application/pdf"
        const val DEFAULT_NAME = "File"
        const val MAX_EXTENSION = 8

        /** Long enough that a capture sheet left open over lunch still has its file. */
        const val ORPHAN_MIN_AGE_MS = 12L * 60 * 60 * 1000
    }
}

/** Refuses anything past [MAX_FILE_BYTES] rather than filling the device with one file. */
private fun java.io.InputStream.copyBoundedTo(output: java.io.OutputStream): Long {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > MAX_FILE_BYTES) throw IOException("File larger than ${MAX_FILE_BYTES / 1024 / 1024}MB")
        output.write(buffer, 0, read)
    }
    return total
}

private const val MAX_FILE_BYTES = 64L * 1024 * 1024

/** Where stored files are served from, so another app can open one. */
object TopicFiles {
    /** A `content://` address for [path], or null when it isn't a file this app kept. */
    fun contentUri(context: Context, path: String): Uri? =
        runCatching { FileProvider.getUriForFile(context, authority(context), File(path)) }.getOrNull()

    private fun authority(context: Context) = "${context.packageName}.topics.fileprovider"
}
