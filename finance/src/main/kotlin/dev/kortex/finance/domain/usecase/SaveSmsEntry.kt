package dev.kortex.finance.domain.usecase

import dev.kortex.finance.domain.calc.Statements
import dev.kortex.finance.domain.model.CardStatement
import dev.kortex.finance.domain.model.TransactionSource
import dev.kortex.finance.domain.port.Clock
import java.time.LocalDate
import kotlinx.coroutines.flow.first

/** What can be changed on the Paste SMS review before saving; [SmsEntryPlan.edits] is what it starts as. */
data class SmsEntryEdits(
    val amountMinor: Long,
    /** Blank for a UPI id nobody has named yet; it's saved under the id. */
    val merchant: String,
    val date: LocalDate,
    /** Where the money went or came from; for a card payment, the card that was paid. */
    val accountUid: String?,
    /** Card payments: the account the bill was paid from. */
    val fromAccountUid: String? = null,
    val categoryUid: String?,
    val note: String? = null,
)

sealed interface SmsSaveResult {
    data class Done(val result: TransactionSaveResult) : SmsSaveResult

    /** No account picked; for a card payment, the one the bill was paid from. */
    data object NoAccount : SmsSaveResult

    /** A card payment with no card picked. */
    data object NoCard : SmsSaveResult
}

/**
 * Saving a read SMS from its review (Paste SMS, To review). [asNew] is "Add anyway": a second entry
 * for an SMS already saved, so it doesn't take the SMS's id.
 */
class SaveSmsEntry(
    private val addTransaction: AddTransaction,
    private val observeFinance: ObserveFinance,
    private val clock: Clock,
) {
    suspend operator fun invoke(plan: SmsEntryPlan, edits: SmsEntryEdits, asNew: Boolean = false): SmsSaveResult {
        if (plan.type == SmsEntryType.CARD_REFUND) return SmsSaveResult.Done(TransactionSaveResult.RefundNotSupported)
        val statements = observeFinance().first().statements
        val draft = plan.toDraft(edits, clock, statements, asNew)
            ?: return if (plan.type == SmsEntryType.CARD_PAYMENT && edits.fromAccountUid != null) SmsSaveResult.NoCard else SmsSaveResult.NoAccount
        return SmsSaveResult.Done(addTransaction(draft))
    }
}

/**
 * The one mapping from a read SMS to its entry, for review and for [ProcessSmsInbox]; null when an
 * account it needs isn't picked.
 */
internal fun SmsEntryPlan.toDraft(edits: SmsEntryEdits, clock: Clock, statements: List<CardStatement>, asNew: Boolean = false): TransactionDraft? {
    val cardPayment = type == SmsEntryType.CARD_PAYMENT
    // A card payment moves money from the paying account to the card; it has no category or merchant.
    val accountUid = (if (cardPayment) edits.fromAccountUid else edits.accountUid) ?: return null
    val cardUid = if (cardPayment) edits.accountUid ?: return null else null
    val upiId = sms.payee?.takeIf { sms.payeeIsUpiId }
    // A UPI id nobody has named yet is the merchant until someone does.
    val merchant = edits.merchant.trim().ifEmpty { upiId?.trim().orEmpty() }
    return TransactionDraft(
        type = type.transactionType,
        amountMinor = edits.amountMinor,
        accountUid = accountUid,
        toAccountUid = cardUid,
        categoryUid = edits.categoryUid.takeIf { !cardPayment },
        merchant = merchant.takeIf { it.isNotEmpty() && !cardPayment },
        payeeKey = upiId,
        note = edits.note,
        occurredAtMillis = clock.millisAt(edits.date, sms.time),
        source = TransactionSource.SMS,
        sourceRef = sms.ref,
        // The bill a payment settles is the card's latest statement.
        statementUid = cardUid?.let { Statements.latest(it, statements)?.uid },
        // The same SMS pasted twice, received then pasted, or on two phones, is one entry.
        uid = if (asNew) null else uid,
    )
}
