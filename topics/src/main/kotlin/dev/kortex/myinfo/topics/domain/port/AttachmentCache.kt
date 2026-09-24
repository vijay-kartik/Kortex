package dev.kortex.myinfo.topics.domain.port

import dev.kortex.myinfo.topics.domain.model.StoredFile

/**
 * Somewhere to put an email's attachment long enough for another app to open it. Unlike
 * [FileVault], nothing here belongs to a topic: the files are short-lived and cleared on their own.
 */
interface AttachmentCache {
    /** Writes [bytes] under [name]. Null when it couldn't be written. */
    suspend fun put(name: String, mimeType: String, bytes: ByteArray): StoredFile?
}
