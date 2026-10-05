package dev.kortex.finance.domain.usecase

import dev.kortex.finance.domain.model.TransactionType
import dev.kortex.finance.domain.port.Clock
import dev.kortex.finance.domain.repository.FinanceRepository
import java.time.LocalDate

sealed interface PayBillResult {
    data class Paid(val transactionUid: String) : PayBillResult
    data object NotFound : PayBillResult
    data class Failed(val reason: TransactionSaveResult) : PayBillResult
}

/**
 * Pay card bill (Figma: Recurring 04): a card payment from an account against the statement. It
 * moves money between your accounts; the purchases were already counted as spending.
 */
class PayCardBill(
    private val repository: FinanceRepository,
    private val addTransaction: AddTransaction,
    private val clock: Clock,
) {
    suspend operator fun invoke(statementUid: String, amountMinor: Long, fromAccountUid: String, paidOn: LocalDate? = null): PayBillResult {
        val statement = repository.getStatement(statementUid) ?: return PayBillResult.NotFound
        val result = addTransaction(
            TransactionDraft(
                type = TransactionType.CARD_PAYMENT,
                amountMinor = amountMinor,
                accountUid = fromAccountUid,
                toAccountUid = statement.cardUid,
                statementUid = statement.uid,
                occurredAtMillis = clock.millisOn(paidOn ?: clock.today()),
            ),
        )
        return if (result is TransactionSaveResult.Saved) PayBillResult.Paid(result.uid) else PayBillResult.Failed(result)
    }
}
