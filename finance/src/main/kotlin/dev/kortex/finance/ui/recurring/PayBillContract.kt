package dev.kortex.finance.ui.recurring

import dev.kortex.finance.domain.calc.Statements
import dev.kortex.finance.domain.model.AccountKind
import dev.kortex.finance.domain.model.TransactionType
import dev.kortex.finance.domain.usecase.FinanceSnapshot
import dev.kortex.finance.domain.usecase.PayBillResult
import dev.kortex.finance.domain.usecase.TransactionSaveResult
import dev.kortex.finance.ui.accounts.AccountsUi
import dev.kortex.finance.ui.common.FinanceFormat
import dev.kortex.finance.ui.entry.AccountOption
import java.time.LocalDate

enum class PayChoice { FULL, MINIMUM, OTHER }

/** Pay card bill (Figma: Recurring 04). */
data class PayBillState(
    val loading: Boolean = true,
    /** "KORTEX ••8824". */
    val cardLabel: String = "",
    val statementOn: LocalDate = LocalDate.now(),
    val dueOn: LocalDate = LocalDate.now(),
    val totalDueMinor: Long = 0,
    /** What's left of the bill and of its minimum, after payments already made. */
    val unpaidMinor: Long = 0,
    val minRemainingMinor: Long = 0,
    val paidSoFarMinor: Long = 0,
    val choice: PayChoice = PayChoice.FULL,
    val otherAmount: String = "",
    val fromAccountUid: String? = null,
    val paidOn: LocalDate = LocalDate.now(),
    val today: LocalDate = LocalDate.now(),
    val accounts: List<AccountOption> = emptyList(),
    val error: String? = null,
    val saving: Boolean = false,
) {
    /** Minimum due is only a separate choice while it's less than what's left. */
    val showMinimum: Boolean get() = minRemainingMinor in 1 until unpaidMinor

    val payingMinor: Long?
        get() = when (choice) {
            PayChoice.FULL -> unpaidMinor
            PayChoice.MINIMUM -> minRemainingMinor
            PayChoice.OTHER -> FinanceFormat.parseAmount(otherAmount)
        }?.takeIf { it > 0 }

    val selectedAccount: AccountOption? get() = accounts.find { it.uid == fromAccountUid }

    companion object {
        /** Accounts a bill can be paid from: anything but a credit card. */
        fun accounts(snapshot: FinanceSnapshot): List<AccountOption> = snapshot.accounts
            .filter { !it.archived && it.kind != AccountKind.CREDIT_CARD }
            .map { AccountOption(it.uid, RecurringLabels.account(it), AccountsUi.subtitle(it, snapshot.accountsByUid)) }

        /** The account this card's bill was last paid from, else the first bank account. */
        fun defaultFrom(snapshot: FinanceSnapshot, cardUid: String, options: List<AccountOption>): String? {
            val last = snapshot.transactions
                .filter { it.type == TransactionType.CARD_PAYMENT && it.toAccountUid == cardUid }
                .maxByOrNull { it.occurredAtMillis }?.accountUid
            return last?.takeIf { uid -> options.any { it.uid == uid } }
                ?: snapshot.accounts.firstOrNull { it.kind == AccountKind.BANK && options.any { o -> o.uid == it.uid } }?.uid
                ?: options.firstOrNull()?.uid
        }

        fun build(snapshot: FinanceSnapshot, statementUid: String, today: LocalDate): PayBillState? {
            val statement = snapshot.statements.find { it.uid == statementUid } ?: return null
            val card = snapshot.accountsByUid[statement.cardUid]
            val paid = Statements.paidMinor(statement, snapshot.transactions)
            val accounts = accounts(snapshot)
            return PayBillState(
                loading = false,
                cardLabel = card?.let(RecurringLabels::account) ?: "Deleted card",
                statementOn = statement.statementOn,
                dueOn = statement.dueOn,
                totalDueMinor = statement.totalDueMinor,
                unpaidMinor = Statements.unpaidMinor(statement, snapshot.transactions),
                minRemainingMinor = (statement.minDueMinor - paid).coerceAtLeast(0),
                paidSoFarMinor = paid,
                fromAccountUid = defaultFrom(snapshot, statement.cardUid, accounts),
                paidOn = today,
                today = today,
                accounts = accounts,
            )
        }

        fun message(result: PayBillResult): String? = when (result) {
            is PayBillResult.Paid -> null
            PayBillResult.NotFound -> "This statement is gone."
            is PayBillResult.Failed -> when (result.reason) {
                TransactionSaveResult.InvalidAmount -> "Enter an amount above zero."
                TransactionSaveResult.UnknownAccount -> "Pick the account you paid from."
                TransactionSaveResult.InvalidTarget -> "Pay from a bank account or cash, not a card."
                else -> "Couldn’t record it. Try again."
            }
        }
    }
}

sealed interface PayBillIntent {
    data class Choose(val choice: PayChoice) : PayBillIntent
    data class OtherAmount(val text: String) : PayBillIntent
    data class From(val uid: String) : PayBillIntent
    data class PaidOn(val date: LocalDate) : PayBillIntent
    data object Pay : PayBillIntent
}

sealed interface PayBillEffect {
    data object Close : PayBillEffect
}
