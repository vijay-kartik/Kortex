package dev.kortex.myinfo.topics.data.files

import android.content.Context
import android.util.Log
import dev.kortex.myinfo.topics.domain.model.StoredFile
import dev.kortex.myinfo.topics.domain.port.AttachmentCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * [AttachmentCache] in `cache/email-attachments`: each file in a folder of its own under its own
 * name, so the app that opens it shows the name the sender gave it. Files older than a day are
 * cleared whenever another is written, and the system may clear the cache sooner.
 */
class EmailAttachmentCache(private val context: Context) : AttachmentCache {

    override suspend fun put(name: String, mimeType: String, bytes: ByteArray): StoredFile? = withContext(Dispatchers.IO) {
        val root = File(context.cacheDir, DIRECTORY)
        sweep(root)
        val folder = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        val target = File(folder, safeName(name))
        try {
            target.writeBytes(bytes)
            StoredFile(target.path, mimeType)
        } catch (e: IOException) {
            Log.w(TAG, "Could not write attachment $name", e)
            folder.deleteRecursively()
            null
        }
    }

    private fun sweep(root: File) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        root.listFiles().orEmpty().filter { it.lastModified() < cutoff }.forEach { it.deleteRecursively() }
    }

    /** The sender's file name, minus anything that would make it a path. */
    private fun safeName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\').trim().trimStart('.').ifBlank { DEFAULT_NAME }.take(MAX_NAME)

    private companion object {
        const val TAG = "EmailAttachmentCache"

        /** Served by the topics FileProvider; see `res/xml/topic_file_paths.xml`. */
        const val DIRECTORY = "email-attachments"
        const val DEFAULT_NAME = "attachment"
        const val MAX_NAME = 120

        /** Long enough to open one again later the same day, short enough not to pile up. */
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    }
}
