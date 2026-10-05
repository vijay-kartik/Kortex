package dev.kortex.finance.domain.model

enum class AccountKind {
    BANK,
    CASH,
    WALLET,
    CREDIT_CARD,
    DEBIT_CARD;

    /** Cards show ••last4 and a network; only a credit card has a limit, statement and outstanding. */
    val isCard: Boolean get() = this == CREDIT_CARD || this == DEBIT_CARD

    /** Bank, cash and wallets make up the total balance. Cards never do. */
    val countsInTotal: Boolean get() = this == BANK || this == CASH || this == WALLET
}

enum class BankType { SAVINGS, CURRENT }

/**
 * A bank account, cash, wallet or card (Figma: Finances › Accounts, Add / Edit account). There is
 * no balance here: it's always the sum of the account's transactions, starting with its
 * [TransactionType.OPENING] entry (docs/FINANCE_PLAN.md › Decisions).
 */
data class Account(
    val uid: String,
    val kind: AccountKind,
    val name: String,
    /** HDFC Bank. */
    val institution: String? = null,
    /** Matched against SMS and receipts. */
    val last4: String? = null,
    /** The full card / account number is kept, encrypted, in finSecrets. */
    val hasSecret: Boolean = false,
    val bankType: BankType? = null,
    val ifsc: String? = null,
    /** A debit card's bank account: its spends move money there. */
    val linkedAccountUid: String? = null,
    /** VISA, Mastercard, RuPay. */
    val network: String? = null,
    /** MM/YY. */
    val expiry: String? = null,
    val holder: String? = null,
    val creditLimitMinor: Long? = null,
    /** Day of month the statement is generated on, 1–31, clamped to short months. */
    val statementDay: Int? = null,
    /** Day of month the bill is due, in the month after the statement. */
    val dueDay: Int? = null,
    val colorToken: String? = null,
    val archived: Boolean = false,
    val createdAtMillis: Long,
    val updatedAtMillis: Long = createdAtMillis,
)
