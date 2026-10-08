package dev.kortex.finance.domain.port

/**
 * Scan receipt 02: the text on the scanned pages, read on the phone. Pages are `content:` /
 * `file:` URIs as plain strings, so the domain stays free of Android types.
 */
interface ReceiptImageReader {
    /** One receipt row per line, pages one after another; blank when nothing could be read. */
    suspend fun text(pageUris: List<String>): String
}
