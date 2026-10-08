package dev.kortex.finance.ui.read

import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions

/**
 * The scanner side of Scan receipt (Figma: Scan receipt 01): Google's document scanner finds the
 * edges, crops and straightens (auto capture on, gallery allowed). Reading the pages and keeping
 * the photo are behind `ReceiptImageReader` and `ReceiptPhotoStore`.
 */
object ReceiptScanning {
    /** Up to two pages: a long receipt can be taken in two photos (Scan receipt 06's tip). */
    val options: GmsDocumentScannerOptions = GmsDocumentScannerOptions.Builder()
        .setGalleryImportAllowed(true)
        .setPageLimit(2)
        .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
        .build()
}
