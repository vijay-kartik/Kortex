package dev.kortex.app.data.files

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import dev.kortex.core.state.Attachment
import dev.kortex.core.state.Attachments
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64

/** What came of turning a picked or shared file into an [Attachment]. */
sealed interface AttachmentRead {
    data class Ok(val attachment: Attachment) : AttachmentRead

    /** @param bytes the file's size when the provider reported it; null when the read was cut off. */
    data class TooLarge(val name: String?, val bytes: Long?, val maxBytes: Long) : AttachmentRead {
        val message: String
            get() {
                val what = name ?: "This file"
                val limit = "${maxBytes / MB} MB"
                return if (bytes != null) "$what is ${bytes / MB} MB; the limit is $limit."
                else "$what is over the $limit limit."
            }
    }

    data object Unreadable : AttachmentRead
}

private const val MB = 1024 * 1024

/**
 * Reads a content [uri] into an [Attachment], never holding more than [maxBytes] of it.
 * The provider's reported size is checked before the stream is opened; when it's unknown,
 * the read stops as soon as the limit is passed.
 */
fun ContentResolver.readAttachment(
    uri: Uri,
    mimeType: String,
    maxBytes: Long = Attachments.MAX_BYTES,
): AttachmentRead {
    var name: String? = null
    var size: Long? = null
    runCatching {
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val n = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (n >= 0 && !cursor.isNull(n)) name = cursor.getString(n)
                val s = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (s >= 0 && !cursor.isNull(s)) size = cursor.getLong(s)
            }
        }
    }
    return readAttachment(name, size, mimeType, maxBytes) { openInputStream(uri) }
}

/** The Android-free part of [ContentResolver.readAttachment]; [size] is null when unknown. */
internal fun readAttachment(
    name: String?,
    size: Long?,
    mimeType: String,
    maxBytes: Long,
    open: () -> InputStream?,
): AttachmentRead {
    if (size != null && size > maxBytes) return AttachmentRead.TooLarge(name, size, maxBytes)
    val bytes = try {
        open()?.use { readBounded(it, maxBytes) } ?: return AttachmentRead.Unreadable
    } catch (e: Exception) {
        return AttachmentRead.Unreadable
    }
    if (bytes.size > maxBytes) return AttachmentRead.TooLarge(name, null, maxBytes)
    return AttachmentRead.Ok(
        Attachment(
            mimeType = mimeType,
            dataBase64 = Base64.getEncoder().encodeToString(bytes),
            filename = name,
        )
    )
}

/**
 * Reads [input] up to [maxBytes] + 1 bytes. A result longer than [maxBytes] means the
 * input was over the limit; the rest of it is never read.
 */
internal fun readBounded(input: InputStream, maxBytes: Long): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (total <= maxBytes) {
        val want = minOf(buf.size.toLong(), maxBytes + 1 - total).toInt()
        val read = input.read(buf, 0, want)
        if (read < 0) break
        out.write(buf, 0, read)
        total += read
    }
    return out.toByteArray()
}
