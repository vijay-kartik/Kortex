package dev.kortex.finance.ui.read

import dev.kortex.finance.domain.model.Transaction
import dev.kortex.finance.domain.read.ParsedReceipt
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.finance.ui.entry.AccountOption
import dev.kortex.finance.ui.entry.CategoryOption
import java.time.LocalDate
import java.time.LocalTime

enum class ReceiptStage {
    /** The scanner is open (Scan receipt 01). */
    Scanning,

    /** Recognising and reading (Scan receipt 02). */
    Reading,

    /** Too blurry, or not a receipt (Scan receipt 06). */
    Unreadable,

    /** More than one amount could be the total (Scan receipt 04). */
    PickTotal,

    /** Matches an expense already saved (Scan receipt 05). */
    Duplicate,

    /** The fields, ready to save (Scan receipt 03). */
    Review,

    /** This phone has no document scanner (no Google Play services). */
    NoScanner,
}

data class ReceiptEntryState(
    val stage: ReceiptStage = ReceiptStage.Scanning,
    /** The scanned pages, as `content:` / `file:` URIs. */
    val pages: List<String> = emptyList(),
    val receipt: ParsedReceipt = ParsedReceipt(),
    /** Scan receipt 04: the candidate picked, or -1 for "Another amount". */
    val pickedTotal: Int = 0,
    val otherTotal: String = "",
    val amount: String = "",
    val merchant: String = "",
    val date: LocalDate = LocalDate.now(),
    val time: LocalTime? = null,
    val today: LocalDate = LocalDate.now(),
    val accountUid: String? = null,
    val unknownLast4: String? = null,
    val categoryUid: String? = null,
    val categorySuggested: Boolean = false,
    val note: String = "",
    val keepPhoto: Boolean = true,
    val duplicate: Transaction? = null,
    val duplicateAccount: String? = null,
    val duplicateCategory: String? = null,
    val viewingPhoto: Boolean = false,
    val accounts: List<AccountOption> = emptyList(),
    val categories: List<CategoryOption> = emptyList(),
    val error: String? = null,
    val saving: Boolean = false,
) {
    val selectedAccount: AccountOption? get() = accounts.find { it.uid == accountUid }
    val selectedCategory: CategoryOption? get() = categories.find { it.uid == categoryUid }
    val choosingAccount: Boolean get() = accountUid == null && unknownLast4 != null

    /** "5 items · ₹59 GST included". */
    val receiptSummary: String
        get() = listOfNotNull(
            receipt.items.sumOf { it.quantity }.takeIf { it > 0 }?.let { if (it == 1) "1 item" else "$it items" },
            receipt.taxMinor?.let { "${dev.kortex.finance.ui.common.FinanceFormat.rupees(it, paise = false)} GST included" },
        ).joinToString(" · ").ifEmpty { "Read from the photo" }

    /** What Scan receipt 04's button uses; null until there's an amount. */
    val pickedTotalMinor: Long?
        get() = if (pickedTotal < 0) dev.kortex.finance.ui.common.FinanceFormat.parseAmount(otherTotal) else receipt.candidates.getOrNull(pickedTotal)?.amountMinor
}

sealed interface ReceiptEntryIntent {
    data class Scanned(val pages: List<String>) : ReceiptEntryIntent
    data object Cancelled : ReceiptEntryIntent
    data object ScannerUnavailable : ReceiptEntryIntent
    data object Retake : ReceiptEntryIntent
    data object EnterManually : ReceiptEntryIntent
    data class PickTotal(val index: Int) : ReceiptEntryIntent
    data class OtherTotal(val text: String) : ReceiptEntryIntent
    data object UseTotal : ReceiptEntryIntent
    data class Amount(val text: String) : ReceiptEntryIntent
    data class Merchant(val text: String) : ReceiptEntryIntent
    data class Date(val date: LocalDate) : ReceiptEntryIntent
    data class Account(val uid: String) : ReceiptEntryIntent
    data class Category(val uid: String?) : ReceiptEntryIntent
    data class Note(val text: String) : ReceiptEntryIntent
    data class KeepPhoto(val keep: Boolean) : ReceiptEntryIntent
    data class ViewPhoto(val open: Boolean) : ReceiptEntryIntent
    data object AddCard : ReceiptEntryIntent
    data object Save : ReceiptEntryIntent
    data object Attach : ReceiptEntryIntent
    data object AddAsNew : ReceiptEntryIntent
}

sealed interface ReceiptEntryEffect {
    data object LaunchScanner : ReceiptEntryEffect
    data object Close : ReceiptEntryEffect
    data class Navigate(val route: FinanceRoute) : ReceiptEntryEffect
    data class Replace(val route: FinanceRoute) : ReceiptEntryEffect
}
