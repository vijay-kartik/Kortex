package dev.kortex.finance.ui.read

import dev.kortex.finance.domain.model.Transaction
import dev.kortex.finance.domain.model.TransactionType
import dev.kortex.finance.domain.read.ParsedSms
import dev.kortex.finance.domain.read.SmsField
import dev.kortex.finance.domain.read.SmsKind
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.finance.ui.entry.AccountOption
import dev.kortex.finance.ui.entry.CategoryOption
import java.time.LocalDate
import java.time.LocalTime

enum class SmsStage {
    /** Waiting for the clipboard, or for the LLM (Paste SMS 01). */
    Reading,

    /** Paste box: nothing on the clipboard, or it wasn't a payment (Paste SMS 07). */
    Paste,

    /** The fields, ready to save (Paste SMS 02, 04, 05, 06, 08). */
    Review,
}

/** What saving this SMS records. */
enum class SmsEntryType {
    EXPENSE,
    INCOME,

    /** "Payment received" on a card (docs/FINANCE_PLAN.md › Paste SMS). */
    CARD_PAYMENT,

    /** Any other credit on a card: a refund, which v1 doesn't handle. */
    CARD_REFUND,
}

data class SmsEntryState(
    val stage: SmsStage = SmsStage.Reading,
    val text: String = "",
    val pasteBox: String = "",
    /** Why the paste box is showing: an OTP, an advert, or nothing readable. Null on first open with an empty clipboard. */
    val rejected: SmsKind? = null,
    val spans: Map<SmsField, IntRange> = emptyMap(),
    /** "HDFC BANK · 28 SEP, 6:42 PM". */
    val header: String = "",
    val sms: ParsedSms? = null,
    val type: SmsEntryType = SmsEntryType.EXPENSE,
    val amount: String = "",
    val merchant: String = "",
    /** A UPI id with no name yet: "Who was this for?" (Paste SMS 06). */
    val needsName: Boolean = false,
    val upiId: String? = null,
    val date: LocalDate = LocalDate.now(),
    val time: LocalTime? = null,
    val today: LocalDate = LocalDate.now(),
    val accountUid: String? = null,
    /** The card or account in the SMS isn't one of yours (Paste SMS 08). */
    val unknownLast4: String? = null,
    /** Card payments: the account the bill was paid from. */
    val fromAccountUid: String? = null,
    val categoryUid: String? = null,
    val categorySuggested: Boolean = false,
    val note: String = "",
    /** An entry this one probably repeats (Paste SMS 05); null once dismissed with "Add anyway". */
    val duplicate: Transaction? = null,
    val duplicateAccount: String? = null,
    val accounts: List<AccountOption> = emptyList(),
    /** Bank accounts and cash, for paying a card bill from. */
    val payingAccounts: List<AccountOption> = emptyList(),
    val categories: List<CategoryOption> = emptyList(),
    val error: String? = null,
    val saving: Boolean = false,
) {
    val title: String get() = if (type == SmsEntryType.INCOME) "Add Income" else if (type == SmsEntryType.CARD_PAYMENT) "Card payment" else "Add Expense"
    val selectedAccount: AccountOption? get() = accounts.find { it.uid == accountUid }
    val selectedFrom: AccountOption? get() = payingAccounts.find { it.uid == fromAccountUid }
    val selectedCategory: CategoryOption? get() = categories.find { it.uid == categoryUid }
    val choosingAccount: Boolean get() = stage == SmsStage.Review && accountUid == null && unknownLast4 != null
    val transactionType: TransactionType
        get() = when (type) {
            SmsEntryType.INCOME -> TransactionType.INCOME
            SmsEntryType.CARD_PAYMENT -> TransactionType.CARD_PAYMENT
            else -> TransactionType.EXPENSE
        }
}

sealed interface SmsEntryIntent {
    /** The clipboard's text, read once when the screen opens; null when it had none. */
    data class Clipboard(val text: String?) : SmsEntryIntent
    data class PasteBox(val text: String) : SmsEntryIntent
    data object ReadPasteBox : SmsEntryIntent
    data object EnterManually : SmsEntryIntent
    data class Amount(val text: String) : SmsEntryIntent
    data class Merchant(val text: String) : SmsEntryIntent
    data class Date(val date: LocalDate) : SmsEntryIntent
    data class Account(val uid: String) : SmsEntryIntent
    data class FromAccount(val uid: String) : SmsEntryIntent
    data class Category(val uid: String?) : SmsEntryIntent
    data class Note(val text: String) : SmsEntryIntent
    data object AddCard : SmsEntryIntent
    data object Save : SmsEntryIntent
    data object AddAnyway : SmsEntryIntent
    data object Skip : SmsEntryIntent
}

sealed interface SmsEntryEffect {
    data object Close : SmsEntryEffect
    data class Navigate(val route: FinanceRoute) : SmsEntryEffect

    /** Close this sheet and open [route] in its place. */
    data class Replace(val route: FinanceRoute) : SmsEntryEffect
}
