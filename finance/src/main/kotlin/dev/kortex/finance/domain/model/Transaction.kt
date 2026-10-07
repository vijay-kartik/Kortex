package dev.kortex.finance.domain.model

import java.time.LocalDate

enum class TransactionType {
    /** An account's or card's starting balance, written once when it's added. */
    OPENING,
    EXPENSE,
    INCOME,

    /** Between two of your own accounts. Never income or spending. */
    TRANSFER,

    /** Paying a credit card's bill from an account. Never spending: the purchases already were. */
    CARD_PAYMENT,
}

enum class TransactionSource {
    MANUAL,
    SMS,
    RECEIPT,
    RECURRING,
    AGENT,
    API,

    /** Imported from an account statement by Kortex for Mac (Add account from statement). */
    STATEMENT,
}

data class ReceiptItem(val name: String, val quantity: Int, val amountMinor: Long)

/** On the device that scanned it, and only if "Keep the receipt photo" was checked. */
data class ReceiptPhoto(val devicePath: String, val mimeType: String)

/** What Scan receipt read, kept with the expense (Figma: Scan receipt 03, "5 items"). */
data class Receipt(
    val itemCount: Int,
    val items: List<ReceiptItem> = emptyList(),
    val taxMinor: Long? = null,
    val photo: ReceiptPhoto? = null,
)

/**
 * Every change to a balance is one of these: balances are never set any other way.
 *
 * [amountMinor] is always positive; [type] says which way the money moved. [accountUid] is where it
 * was paid from or received into (for [TransactionType.OPENING], the account it opens);
 * [toAccountUid] is the other side of a transfer or card payment.
 */
data class Transaction(
    val uid: String,
    val type: TransactionType,
    val amountMinor: Long,
    val currency: String = DEFAULT_CURRENCY,
    val occurredAtMillis: Long,
    /** The day it happened on in the device's zone when saved. Every day and month total groups by it. */
    val occurredOn: LocalDate,
    val accountUid: String,
    val toAccountUid: String? = null,
    /** Null is Uncategorised. */
    val categoryUid: String? = null,
    /** As shown: "Whole Foods Market". */
    val merchant: String? = null,
    /** [dev.kortex.finance.domain.FinanceIds.payeeKey] of the merchant or UPI id. */
    val payeeKey: String? = null,
    val note: String? = null,
    val source: TransactionSource = TransactionSource.MANUAL,
    /** The bank's or UPI reference, for spotting the same payment twice. */
    val sourceRef: String? = null,
    /** Set when this paid a recurring payment: which one, and the occurrence's due day. */
    val recurringUid: String? = null,
    val dueOn: LocalDate? = null,
    /** Set on a card payment: the statement it paid. */
    val statementUid: String? = null,
    val receipt: Receipt? = null,
    val createdAtMillis: Long = occurredAtMillis,
    val updatedAtMillis: Long = createdAtMillis,
) {
    companion object {
        const val DEFAULT_CURRENCY = "INR"
    }
}
