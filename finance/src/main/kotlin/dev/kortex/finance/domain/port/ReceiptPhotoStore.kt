package dev.kortex.finance.domain.port

import dev.kortex.finance.domain.model.ReceiptPhoto

/** Keeps a scanned page with its expense when "Keep the receipt photo" is checked (Scan receipt 03). */
interface ReceiptPhotoStore {
    /** Null when the page couldn't be copied. */
    suspend fun keep(pageUri: String, transactionUid: String): ReceiptPhoto?
}
