package dev.kortex.finance.domain.usecase

import dev.kortex.finance.domain.FinanceIds
import dev.kortex.finance.domain.model.AccountKind
import dev.kortex.finance.domain.model.CategoryKind
import dev.kortex.finance.domain.model.Merchant
import dev.kortex.finance.domain.model.Receipt
import dev.kortex.finance.domain.model.Transaction
import dev.kortex.finance.domain.model.TransactionSource
import dev.kortex.finance.domain.model.TransactionType
import dev.kortex.finance.domain.port.Clock
import dev.kortex.finance.domain.repository.FinanceRepository
import java.time.LocalDate

/** An entry from any screen, SMS, receipt, recurring payment, the agent or the API. */
data class TransactionDraft(
    val type: TransactionType,
    val amountMinor: Long,
    val accountUid: String,
    val toAccountUid: String? = null,
    val categoryUid: String? = null,
    val merchant: String? = null,
    val note: String? = null,
    /** Defaults to now. */
    val occurredAtMillis: Long? = null,
    val source: TransactionSource = TransactionSource.MANUAL,
    val sourceRef: String? = null,
    val recurringUid: String? = null,
    val dueOn: LocalDate? = null,
    val statementUid: String? = null,
    val receipt: Receipt? = null,
    /** A derived id ([FinanceIds.smsTransaction], [FinanceIds.recurringOccurrence]); random when null. */
    val uid: String? = null,
)

sealed interface TransactionSaveResult {
    data class Saved(val uid: String) : TransactionSaveResult

    /** A transaction with this derived id exists: the same SMS, or the occurrence already paid. */
    data class AlreadySaved(val uid: String) : TransactionSaveResult
    data object InvalidAmount : TransactionSaveResult
    data object UnknownAccount : TransactionSaveResult

    /** Transfers and card payments need another account; card payments a credit card. */
    data object InvalidTarget : TransactionSaveResult
    data object WrongCategoryKind : TransactionSaveResult

    /** Opening entries are only made by [AddAccount]. */
    data object OpeningNotAllowed : TransactionSaveResult

    /** Income into a credit card would be a refund, which v1 doesn't handle. */
    data object RefundNotSupported : TransactionSaveResult
}

/**
 * The one way an entry is saved (docs/FINANCE_PLAN.md › How entries get in), so validation,
 * duplicates and merchant learning behave the same whichever screen started it.
 */
class AddTransaction(
    private val repository: FinanceRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(draft: TransactionDraft): TransactionSaveResult =
        when (val prepared = prepare(draft)) {
            is Prepared.Ready -> {
                repository.addTransaction(prepared.transaction, prepared.merchant)
                TransactionSaveResult.Saved(prepared.transaction.uid)
            }
            is Prepared.Rejected -> prepared.result
        }

    /** A validated entry and its merchant, not yet saved; or why it can't be. */
    internal sealed interface Prepared {
        data class Ready(val transaction: Transaction, val merchant: Merchant?) : Prepared
        data class Rejected(val result: TransactionSaveResult) : Prepared
    }

    /** Everything [invoke] does short of saving, for use cases that save more alongside it. */
    internal suspend fun prepare(draft: TransactionDraft): Prepared {
        fun reject(result: TransactionSaveResult) = Prepared.Rejected(result)
        if (draft.amountMinor <= 0) return reject(TransactionSaveResult.InvalidAmount)
        if (draft.type == TransactionType.OPENING) return reject(TransactionSaveResult.OpeningNotAllowed)
        draft.uid?.let { uid -> if (repository.getTransaction(uid) != null) return reject(TransactionSaveResult.AlreadySaved(uid)) }
        val account = repository.getAccount(draft.accountUid) ?: return reject(TransactionSaveResult.UnknownAccount)

        val movesBetweenAccounts = draft.type == TransactionType.TRANSFER || draft.type == TransactionType.CARD_PAYMENT
        if (movesBetweenAccounts) {
            val target = draft.toAccountUid?.takeIf { it != account.uid }?.let { repository.getAccount(it) }
                ?: return reject(TransactionSaveResult.InvalidTarget)
            val valid = when (draft.type) {
                TransactionType.CARD_PAYMENT -> target.kind == AccountKind.CREDIT_CARD && account.kind != AccountKind.CREDIT_CARD
                else -> target.kind != AccountKind.CREDIT_CARD && account.kind != AccountKind.CREDIT_CARD
            }
            if (!valid) return reject(TransactionSaveResult.InvalidTarget)
        } else {
            if (draft.type == TransactionType.INCOME && account.kind == AccountKind.CREDIT_CARD) {
                return reject(TransactionSaveResult.RefundNotSupported)
            }
            draft.categoryUid?.let { uid ->
                val wanted = if (draft.type == TransactionType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
                if (repository.getCategory(uid)?.kind != wanted) return reject(TransactionSaveResult.WrongCategoryKind)
            }
        }

        val now = clock.nowMillis()
        val at = draft.occurredAtMillis ?: now
        val merchantName = draft.merchant.clean().takeIf { !movesBetweenAccounts }
        val payeeKey = merchantName?.let(FinanceIds::payeeKey)
        val categoryUid = draft.categoryUid.takeIf { !movesBetweenAccounts }
        val transaction = Transaction(
            uid = draft.uid ?: FinanceIds.random(),
            type = draft.type,
            amountMinor = draft.amountMinor,
            occurredAtMillis = at,
            occurredOn = clock.dayOf(at),
            accountUid = account.uid,
            toAccountUid = draft.toAccountUid.takeIf { movesBetweenAccounts },
            categoryUid = categoryUid,
            merchant = merchantName,
            payeeKey = payeeKey,
            note = draft.note.clean(),
            source = draft.source,
            sourceRef = draft.sourceRef.clean(),
            recurringUid = draft.recurringUid,
            dueOn = draft.dueOn,
            statementUid = draft.statementUid.takeIf { draft.type == TransactionType.CARD_PAYMENT },
            receipt = draft.receipt,
            createdAtMillis = now,
        )
        // Remember the payee's name and the category picked for it, so the next entry is suggested.
        val merchant = payeeKey?.let { key ->
            val known = repository.findMerchant(key)
            Merchant(
                uid = known?.uid ?: FinanceIds.merchant(key),
                payeeKey = key,
                displayName = merchantName,
                categoryUid = categoryUid ?: known?.categoryUid,
                updatedAtMillis = now,
            )
        }
        return Prepared.Ready(transaction, merchant)
    }
}
